"""Datos: casillas sintéticas al vuelo, fotos reales etiquetadas y tableros de ejemplos/."""

from pathlib import Path

import cv2
import numpy as np
import torch
from torch.utils.data import IterableDataset, get_worker_info

from common.squares import crop_squares, placement_to_labels, rotate_squares
from detect.board import find_board_quad, warp_board
from synth.render import detector_error, sample

ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = ROOT / "ejemplos"
TRAIN_PHOTOS = ROOT / "entrenamiento"


def labelled_photos(folder: Path) -> list[tuple[str, np.ndarray, np.ndarray, np.ndarray]]:
    """[(nombre, foto en gris, esquinas del tablero, etiquetas)] de las fotos con .fen.

    Las esquinas son las del detector real, en el orden tl, tr, br, bl de la
    foto: se asume que el diagrama está derecho (blancas abajo) en la foto.
    """
    out = []
    for fen in sorted(folder.glob("*.fen")):
        img = cv2.imread(str(fen.with_suffix(".jpg")))
        found = find_board_quad(img)
        if found is None:
            print(f"  aviso: sin tablero en {fen.stem}")
            continue
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        out.append((fen.stem, gray, found[0], placement_to_labels(fen.read_text().split()[0])))
    return out


def augment_real(rng: np.random.Generator, gray: np.ndarray, quad: np.ndarray) -> np.ndarray:
    """Tablero enderezado de una foto real con variaciones: ajuste de esquinas
    imperfecto (como el del detector), luz, contraste, desenfoque, ruido y JPEG."""
    side = float(np.linalg.norm(quad[1] - quad[0])) / 8
    board = warp_board(gray, detector_error(rng, quad, side)).astype(np.float32)
    board = (board - 128) * rng.uniform(0.75, 1.25) + 128 + rng.uniform(-25, 25)
    sigma = rng.uniform(0, 1.5)
    if sigma > 0.3:
        board = cv2.GaussianBlur(board, (0, 0), sigma)
    board = np.clip(board + rng.normal(0, rng.uniform(0, 6), board.shape), 0, 255).astype(np.uint8)
    q = int(rng.integers(40, 96))
    return cv2.imdecode(cv2.imencode(".jpg", board, [cv2.IMWRITE_JPEG_QUALITY, q])[1],
                        cv2.IMREAD_GRAYSCALE)


class SyntheticSquares(IterableDataset):
    """Flujo infinito de (casilla, etiqueta).

    Las vacías son ~70 % de las casillas; se queda una fracción para que el
    modelo no aprenda a decir "vacía" por defecto.
    """

    def __init__(self, sets: list[str], seed: int, keep_empty: float = 0.35,
                 real: list | None = None, real_frac: float = 0.0):
        """`real`: salida de labelled_photos(); `real_frac`: fracción de tableros
        que salen de esas fotos en vez del generador."""
        self.sets, self.seed, self.keep_empty = sets, seed, keep_empty
        self.real, self.real_frac = real or [], real_frac if real else 0.0

    def __iter__(self):
        info = get_worker_info()
        wid = info.id if info else 0
        rng = np.random.default_rng([self.seed, wid])
        while True:
            if rng.random() < self.real_frac:
                _, gray, quad, labels = self.real[int(rng.integers(len(self.real)))]
                squares = crop_squares(augment_real(rng, gray, quad))
            else:
                squares, labels, _ = sample(rng, self.sets)
            keep = (labels != 0) | (rng.random(64) < self.keep_empty)
            for sq, lab in zip(squares[keep], labels[keep]):
                yield torch.from_numpy(sq[None]), int(lab)


def synthetic_boards(sets: list[str], n: int, seed: int) -> tuple[np.ndarray, np.ndarray]:
    """Conjunto fijo de tableros completos para validación: (n,64,1,64,64), (n,64)."""
    rng = np.random.default_rng(seed)
    xs, ys = [], []
    for _ in range(n):
        squares, labels, _ = sample(rng, sets)
        xs.append(squares[:, None])
        ys.append(labels)
    return np.stack(xs), np.stack(ys)


def real_boards() -> list[tuple[str, np.ndarray, np.ndarray]]:
    """[(nombre, tablero enderezado 512² gris, etiquetas)] de ejemplos/ con .fen.

    Usa el detector real, así que sus errores de ajuste entran en la medida.
    Las fotos pueden estar giradas: la orientación se resuelve al predecir.
    """
    return [(name, warp_board(gray, quad), labels)
            for name, gray, quad, labels in labelled_photos(EXAMPLES)]


ROTATION_SQUARES = 21  # casillas con las que se prueba cada giro: 3 × 21 = 63 caben en un lote


def run_squares(model, squares: np.ndarray, device) -> np.ndarray:
    """log-probabilidades de cualquier número de casillas, en lotes de 64 (el tamaño fijo del
    modelo exportado); el último se rellena repitiendo la primera casilla."""
    out = []
    for i in range(0, len(squares), 64):
        chunk = squares[i:i + 64]
        batch = np.concatenate([chunk, np.repeat(chunk[:1], 64 - len(chunk), axis=0)])
        x = torch.from_numpy(batch[:, None]).to(device)
        out.append(torch.log_softmax(model(x), dim=1).cpu().numpy()[:len(chunk)])
    return np.concatenate(out)


def rotation_score(logp: np.ndarray) -> float:
    """Confianza media en las casillas que el modelo ve ocupadas."""
    occupied = logp.argmax(axis=1) != 0
    return float(logp.max(axis=1)[occupied].mean()) if occupied.any() else -np.inf


@torch.no_grad()
def predict_board(model, board: np.ndarray, device) -> tuple[np.ndarray, np.ndarray, int]:
    """Elige el giro del tablero (k × 90°, antihorario) y lo clasifica.

    Primero las 64 casillas tal cual. Para los otros tres giros basta con las casillas
    ocupadas, que son las que cambian al girar (una vacía es igual en cualquier giro):
    las ROTATION_SQUARES más claramente ocupadas, giradas, en un solo lote (#13). Gana
    el giro con más confianza media en sus piezas; si no es el 0, se clasifica entero.

    Devuelve (etiquetas 64, probabilidades 64×13, nº de giros de 90°).
    """
    squares = crop_squares(board)
    logp = run_squares(model, squares, device)
    occupied = np.flatnonzero(logp.argmax(axis=1) != 0)
    if len(occupied) == 0:
        return logp.argmax(axis=1), np.exp(logp), 0
    probe = occupied[np.argsort(logp[occupied, 0], kind="stable")[:ROTATION_SQUARES]]
    turned = run_squares(model, np.concatenate(
        [np.rot90(squares[probe], k, axes=(1, 2)) for k in (1, 2, 3)]), device)
    scores = [rotation_score(logp[probe])] + [rotation_score(t) for t in np.split(turned, 3)]
    k = int(np.argmax(scores))
    if k:
        logp = run_squares(model, rotate_squares(squares, k), device)
    return logp.argmax(axis=1), np.exp(logp), k
