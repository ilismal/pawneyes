"""Generador sintético de diagramas de libro fotografiados.

render_board() dibuja un diagrama "impreso" en una página (gris, canónico,
blancas abajo). photograph() lo deforma como lo haría una foto de móvil.
sample() encadena ambos, simula el error del detector y devuelve las 64
casillas recortadas con common.squares, igual que en el pipeline real.
"""

from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import cv2
import numpy as np

from common.squares import BOARD_PX, CLASSES, crop_squares

PNG_DIR = Path(__file__).resolve().parents[1] / "assets" / "pieces_png"
EXCLUDED_SETS = {"firi"}  # formas demasiado lejanas a las de un libro
VAL_SETS = ("cardinal", "gioco", "staunty")  # nunca se usan para entrenar

# Código Lichess de cada clase (índice = clase).
_CODES = [None, "wP", "wN", "wB", "wR", "wQ", "wK", "bP", "bN", "bB", "bR", "bQ", "bK"]
_ALPHABET = "abcdefghijklmnopqrstuvwxyzáéíóñ    "


def all_sets() -> list[str]:
    return sorted(d.name for d in PNG_DIR.iterdir() if d.is_dir() and d.name not in EXCLUDED_SETS)


def train_sets() -> list[str]:
    return [s for s in all_sets() if s not in VAL_SETS]


@lru_cache(maxsize=None)
def _piece(set_name: str, cls: int) -> tuple[np.ndarray, np.ndarray]:
    """(luminancia, alfa) en float32 0..1."""
    img = cv2.imread(str(PNG_DIR / set_name / f"{_CODES[cls]}.png"), cv2.IMREAD_UNCHANGED)
    lum = cv2.cvtColor(img[..., :3], cv2.COLOR_BGR2GRAY).astype(np.float32) / 255
    return lum, img[..., 3].astype(np.float32) / 255


def random_position(rng: np.random.Generator) -> np.ndarray:
    """64 etiquetas (a8..h1). No busca legalidad, solo plausibilidad visual."""
    labels = np.zeros(64, np.int64)
    free = list(rng.permutation(64))
    labels[free.pop()] = CLASSES.index("K")
    labels[free.pop()] = CLASSES.index("k")
    n = int(rng.integers(0, 31))
    weights = np.array([8, 2, 2, 2, 1, 0], np.float32)  # P N B R Q (K ya puesto)
    weights /= weights.sum()
    for _ in range(n):
        sq = free.pop()
        kind = int(rng.choice(5, p=weights[:5]))
        cls = 1 + kind + (6 if rng.random() < 0.5 else 0)
        if kind == 0 and sq // 8 in (0, 7):  # sin peones en 1ª/8ª
            cls += 1
        labels[sq] = cls
    return labels


@dataclass
class Style:
    paper: float
    ink: float
    dark: str  # "hatch", "cross", "dots", "solid", "blank"
    dark_level: float
    period: float
    thickness: float
    angle: int
    grid_lines: bool
    frame: int
    double_frame: bool
    coords: bool
    panel: bool
    piece_mode: str  # "opaque" o "transparent"
    posterize: bool
    piece_scale: float


def random_style(rng: np.random.Generator, s: int) -> Style:
    dark = rng.choice(["hatch", "hatch", "cross", "dots", "solid", "solid"], p=None)
    period = float(s / rng.uniform(5, 14))
    paper = float(rng.uniform(205, 250))
    thick = rng.uniform(0.25, 0.6) if dark != "dots" else rng.uniform(0.2, 0.4)
    transparent = rng.random() < 0.3
    return Style(
        paper=paper,
        ink=float(rng.uniform(0, 60)),
        dark=str(dark),
        dark_level=float(paper - rng.uniform(40, 130)),
        period=period,
        thickness=float(thick * period),
        angle=int(rng.choice([45, 135])),
        grid_lines=bool(rng.random() < 0.35),
        frame=int(rng.integers(0, max(2, s // 6))),
        double_frame=bool(rng.random() < 0.15),
        coords=bool(rng.random() < 0.35),
        panel=bool(rng.random() < 0.15),
        piece_mode="transparent" if transparent else "opaque",
        # Con fondo transparente solo queda el contorno: sin posterizar se pierde.
        posterize=transparent or bool(rng.random() < 0.5),
        piece_scale=float(rng.uniform(0.75, 1.0)),
    )


def _dark_pattern(style: Style, size: int) -> np.ndarray:
    """Textura de casilla oscura para todo el tablero (continua entre casillas)."""
    y, x = np.mgrid[0:size, 0:size].astype(np.float32)
    p, t = style.period, style.thickness
    paper, ink = style.paper, style.ink
    if style.dark == "solid":
        return np.full((size, size), style.dark_level, np.float32)
    if style.dark == "blank":
        return np.full((size, size), paper, np.float32)
    if style.dark == "dots":
        dx, dy = (x % p) - p / 2, (y % p) - p / 2
        mask = np.clip((t * 0.7 - np.sqrt(dx * dx + dy * dy)) + 0.5, 0, 1)
    else:
        diag = (x + y) if style.angle == 45 else (x - y)
        dist = np.abs(((diag / np.sqrt(2)) % p) - p / 2)
        mask = np.clip(t / 2 - (p / 2 - dist) + 0.5, 0, 1)
        if style.dark == "cross":
            diag2 = (x - y) if style.angle == 45 else (x + y)
            dist2 = np.abs(((diag2 / np.sqrt(2)) % p) - p / 2)
            mask = np.maximum(mask, np.clip(t / 2 - (p / 2 - dist2) + 0.5, 0, 1))
    return paper * (1 - mask) + ink * mask


def _put_text(canvas: np.ndarray, text: str, org, font, scale: float, ink: float,
              thickness: int) -> None:
    """putText solo admite uint8: se dibuja en una máscara y se mezcla."""
    mask = np.zeros(canvas.shape, np.uint8)
    cv2.putText(mask, text, org, font, scale, 255, thickness, cv2.LINE_AA)
    a = mask.astype(np.float32) / 255
    canvas[:] = canvas * (1 - a) + ink * a


def _random_text(rng: np.random.Generator, canvas: np.ndarray, region, style: Style, s: int):
    """Líneas de "texto" en una franja (x0, y0, x1, y1) del margen."""
    x0, y0, x1, y1 = region
    scale = s / 55 * rng.uniform(0.6, 1.2)
    line_h = int(30 * scale) + 4
    y = y0 + line_h
    while y < y1 - 2:
        n = int(rng.integers(10, 60))
        text = "".join(rng.choice(list(_ALPHABET), n))
        font = int(rng.choice([cv2.FONT_HERSHEY_SIMPLEX, cv2.FONT_HERSHEY_COMPLEX,
                               cv2.FONT_HERSHEY_TRIPLEX, cv2.FONT_HERSHEY_DUPLEX]))
        _put_text(canvas, text, (int(x0 + rng.uniform(0, 20)), int(y)), font, scale,
                  style.ink, max(1, int(scale * 1.5)))
        y += line_h


def render_board(rng: np.random.Generator, labels: np.ndarray, set_name: str,
                 flipped: bool = False, caption: str | None = None,
                 s: int | None = None, st: "Style | None" = None,
                 ) -> tuple[np.ndarray, np.ndarray, int]:
    """Página con el diagrama. Devuelve (imagen float32, esquinas tl,tr,br,bl, lado casilla).

    `flipped`: impreso desde el lado de las negras (casillas y coordenadas al revés).
    `caption`: pie bajo el tablero («Juegan las negras»…) en lugar del texto de relleno.
    """
    if flipped:
        labels = np.asarray(labels)[::-1]
    # `s` y `st` dados: varios diagramas con el mismo estilo en una página (render_page).
    if s is None:
        s = int(rng.integers(36, 84))
    if st is None:
        st = random_style(rng, s)
    board_px = 8 * s
    m = [int(s * rng.uniform(0.8, 2.6)) for _ in range(4)]  # izq, arriba, der, abajo
    if caption is not None:
        m[3] = max(m[3], int(s * 2.2))
    h, w = board_px + m[1] + m[3], board_px + m[0] + m[2]
    canvas = np.full((h, w), st.paper, np.float32)
    bx, by = m[0], m[1]

    if st.panel:
        pad = int(s * 0.6)
        canvas[by - pad:by + board_px + pad, bx - pad:bx + board_px + pad] = st.paper * 0.9

    # Casillas oscuras.
    pattern = _dark_pattern(st, board_px)
    board = np.full((board_px, board_px), st.paper * (0.9 if st.panel else 1), np.float32)
    for i in range(8):
        for j in range(8):
            if (i + j) % 2:
                board[i * s:(i + 1) * s, j * s:(j + 1) * s] = pattern[i * s:(i + 1) * s,
                                                                      j * s:(j + 1) * s]
    if st.grid_lines:
        for k in range(9):
            cv2.line(board, (k * s, 0), (k * s, board_px - 1), st.ink, 1)
            cv2.line(board, (0, k * s), (board_px - 1, k * s), st.ink, 1)

    # Casillas marcadas (última jugada en libros modernos).
    for _ in range(int(rng.choice([0, 0, 0, 1, 2]))):
        i, j = rng.integers(0, 8, 2)
        level = float(rng.uniform(60, 140))
        for k in range(0, s, 4):
            for (ax, ay, bx2, by2) in ((k, 0, k + 2, 0), (k, s - 1, k + 2, s - 1),
                                       (0, k, 0, k + 2), (s - 1, k, s - 1, k + 2)):
                cv2.line(board, (j * s + ax, i * s + ay), (j * s + bx2, i * s + by2), level, 2)

    # Piezas.
    for sq, cls in enumerate(labels):
        if not cls:
            continue
        lum, alpha = _piece(set_name, int(cls))
        ps = int(s * st.piece_scale)
        lum = cv2.resize(lum, (ps, ps), interpolation=cv2.INTER_AREA)
        alpha = cv2.resize(alpha, (ps, ps), interpolation=cv2.INTER_AREA)
        if st.posterize:
            lum = (lum > 0.55).astype(np.float32)
        tone = st.ink / 255 + lum * (st.paper / 255 - st.ink / 255)
        i, j = divmod(sq, 8)
        jit = int(s * 0.05)
        y0 = i * s + (s - ps) // 2 + int(rng.integers(-jit, jit + 1))
        x0 = j * s + (s - ps) // 2 + int(rng.integers(-jit, jit + 1))
        y0, x0 = np.clip(y0, 0, board_px - ps), np.clip(x0, 0, board_px - ps)
        region = board[y0:y0 + ps, x0:x0 + ps]
        if st.piece_mode == "transparent":
            ink = alpha * (1 - lum)
            region[:] = region * (1 - ink) + st.ink * ink
        else:
            region[:] = region * (1 - alpha) + tone * 255 * alpha

    canvas[by:by + board_px, bx:bx + board_px] = board

    # Marco.
    if st.frame:
        f = st.frame
        cv2.rectangle(canvas, (bx - f // 2 - 1, by - f // 2 - 1),
                      (bx + board_px + f // 2, by + board_px + f // 2), st.ink, max(1, f))
        if st.double_frame:
            g = f + int(s * 0.12)
            cv2.rectangle(canvas, (bx - g, by - g), (bx + board_px + g, by + board_px + g),
                          st.ink, 1)

    # Coordenadas.
    if st.coords:
        scale = s / 90
        for k in range(8):
            _put_text(canvas, str(k + 1 if flipped else 8 - k),
                      (bx - int(s * 0.45), by + k * s + int(s * 0.6)),
                      cv2.FONT_HERSHEY_SIMPLEX, scale, st.ink, 1)
            _put_text(canvas, ("hgfedcba" if flipped else "abcdefgh")[k], (bx + k * s + int(s * 0.4),
                                              by + board_px + int(s * 0.45)),
                      cv2.FONT_HERSHEY_SIMPLEX, scale, st.ink, 1)

    # Texto alrededor.
    gap = int(s * 0.7)
    if rng.random() < 0.8:
        _random_text(rng, canvas, (0, 0, w, by - gap), st, s)
    if rng.random() < 0.8 and caption is None:
        _random_text(rng, canvas, (0, by + board_px + gap, w, h), st, s)
    if caption is not None:
        scale = s / 55
        _put_text(canvas, caption, (bx, by + board_px + gap + int(30 * scale)),
                  cv2.FONT_HERSHEY_DUPLEX, scale, st.ink, max(1, int(scale * 1.5)))

    corners = np.array([[bx, by], [bx + board_px, by], [bx + board_px, by + board_px],
                        [bx, by + board_px]], np.float32)
    return canvas, corners, s


def render_page(rng: np.random.Generator, boards: list[np.ndarray], set_name: str
                ) -> tuple[np.ndarray, np.ndarray, int]:
    """Una página con varios diagramas, uno debajo de otro y con el mismo estilo (#10).

    Devuelve (imagen, esquinas de todos los tableros seguidas (4·n × 2), lado de casilla).
    """
    s = int(rng.integers(36, 60))
    st = random_style(rng, s)
    parts = [render_board(rng, labels, set_name, s=s, st=st) for labels in boards]
    w = max(p.shape[1] for p, _, _ in parts)
    pages, corners, y = [], [], 0
    for page, c, _ in parts:
        pad = w - page.shape[1]
        pages.append(np.pad(page, ((0, 0), (0, pad)), constant_values=st.paper))
        corners.append(c + [0, y])
        y += page.shape[0]
    return np.vstack(pages), np.vstack(corners).astype(np.float32), s


def photograph(rng: np.random.Generator, page: np.ndarray, corners: np.ndarray, s: int
               ) -> tuple[np.ndarray, np.ndarray, float]:
    """Simula la foto: curvatura, perspectiva, luz, resolución, desenfoque, ruido, JPEG."""
    h, w = page.shape
    paper = float(np.median(page))

    # Curvatura de página: desplazamiento suave, sobre todo vertical.
    if rng.random() < 0.5:
        amp = rng.uniform(0, 0.35) * s
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        phase = rng.uniform(0, np.pi)
        dy = amp * np.sin(np.pi * xx / w + phase) * (1 if rng.random() < 0.5 else -1)
        dx = 0.3 * amp * np.sin(np.pi * yy / h + rng.uniform(0, np.pi))
        page = cv2.remap(page, xx + dx, yy + dy, cv2.INTER_LINEAR,
                         borderMode=cv2.BORDER_CONSTANT, borderValue=paper)
        ci = corners.astype(int)
        ci[:, 0] = np.clip(ci[:, 0], 0, w - 1)
        ci[:, 1] = np.clip(ci[:, 1], 0, h - 1)
        corners = corners - np.stack([dx[ci[:, 1], ci[:, 0]], dy[ci[:, 1], ci[:, 0]]], axis=1)

    # Perspectiva y giro leve.
    src = np.array([[0, 0], [w, 0], [w, h], [0, h]], np.float32)
    jit = rng.uniform(0, 0.12) * min(w, h)
    dst = src + rng.uniform(-jit, jit, (4, 2)).astype(np.float32)
    ang = np.deg2rad(rng.uniform(-8, 8))
    rot = np.array([[np.cos(ang), -np.sin(ang)], [np.sin(ang), np.cos(ang)]], np.float32)
    dst = (dst - [w / 2, h / 2]) @ rot.T + [w / 2, h / 2]
    dst -= dst.min(axis=0)
    out_w, out_h = (int(v) + 1 for v in dst.max(axis=0))
    M = cv2.getPerspectiveTransform(src, dst.astype(np.float32))
    border = paper if rng.random() < 0.7 else float(rng.uniform(20, 120))
    img = cv2.warpPerspective(page, M, (out_w, out_h), flags=cv2.INTER_LINEAR,
                              borderValue=border)
    corners = cv2.perspectiveTransform(corners[None], M)[0]

    # Iluminación: gradiente + sombra con borde difuso.
    yy, xx = np.mgrid[0:out_h, 0:out_w].astype(np.float32)
    gain = 1 + rng.uniform(-0.35, 0.35) * (xx / out_w - 0.5) + \
        rng.uniform(-0.35, 0.35) * (yy / out_h - 0.5)
    if rng.random() < 0.4:
        a, b = rng.normal(size=2)
        c = rng.uniform(-0.3, 0.3)
        side = (a * (xx / out_w - 0.5) + b * (yy / out_h - 0.5) - c) > 0
        shadow = cv2.GaussianBlur(side.astype(np.float32), (0, 0), rng.uniform(5, 40))
        gain *= 1 - rng.uniform(0.2, 0.55) * shadow
    img = img * np.clip(gain, 0.3, 1.3) * rng.uniform(0.75, 1.05)

    # Resolución final: lado de casilla entre ~18 y ~75 px.
    side_now = float(np.linalg.norm(corners[1] - corners[0])) / 8
    target = rng.uniform(18, 75)
    f = target / side_now
    img = cv2.resize(img, None, fx=f, fy=f, interpolation=cv2.INTER_AREA if f < 1
                     else cv2.INTER_LINEAR)
    corners *= f

    sigma = rng.uniform(0, 1.6)
    if sigma > 0.3:
        img = cv2.GaussianBlur(img, (0, 0), sigma)
    img = img + rng.normal(0, rng.uniform(0, 9), img.shape)
    img = np.clip(img, 0, 255).astype(np.uint8)
    q = int(rng.integers(30, 96))
    img = cv2.imdecode(cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, q])[1],
                       cv2.IMREAD_GRAYSCALE)
    return img, corners, target


def detector_error(rng: np.random.Generator, corners: np.ndarray, side: float) -> np.ndarray:
    """Ruido en las esquinas como el del detector real (a veces grande)."""
    sigma = side * (rng.uniform(0, 0.06) if rng.random() < 0.8 else rng.uniform(0.06, 0.15))
    return corners + rng.normal(0, sigma, corners.shape).astype(np.float32)


def warp_to_board(img: np.ndarray, corners: np.ndarray) -> np.ndarray:
    dst = np.array([[0, 0], [BOARD_PX - 1, 0], [BOARD_PX - 1, BOARD_PX - 1],
                    [0, BOARD_PX - 1]], np.float32)
    M = cv2.getPerspectiveTransform(corners.astype(np.float32), dst)
    return cv2.warpPerspective(img, M, (BOARD_PX, BOARD_PX), flags=cv2.INTER_LINEAR,
                               borderMode=cv2.BORDER_REPLICATE)


def sample(rng: np.random.Generator, sets: list[str]) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """(casillas 64×INPUT², etiquetas 64, tablero enderezado) de un diagrama aleatorio."""
    labels = random_position(rng)
    set_name = sets[int(rng.integers(len(sets)))]
    page, corners, s = render_board(rng, labels, set_name)
    photo, corners, side = photograph(rng, page, corners, s)
    board = warp_to_board(photo, detector_error(rng, corners, side))
    return crop_squares(board), labels, board
