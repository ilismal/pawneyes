"""¿Está el diagrama impreso desde el lado de las negras?

Las piezas se imprimen derechas también en ese caso, así que el giro que elige el
clasificador no lo distingue. Dos pistas:
  1. Las coordenadas impresas (1–8 en un lado, a–h abajo o arriba), si las hay.
  2. Si no, los peones: las blancas suelen tener los suyos más abajo que las negras.
"""

import numpy as np

from caption.ocr import words
from caption.page import SQUARE
from common.squares import CLASSES

DIGITS, LETTERS = "87654321", "abcdefgh"
MIN_COORDS = 4          # caracteres que tienen que cuadrar para fiarse de las coordenadas
COORDS_MARGIN = 2       # y con esta ventaja sobre la orientación contraria
PAWN_MARGIN = 1.5       # filas de diferencia media entre peones blancos y negros
MIN_PAWNS = 2           # de cada color


def _lcs(a: str, b: str) -> int:
    """Subsecuencia común más larga: tolera caracteres de más o de menos del OCR."""
    t = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i, x in enumerate(a):
        for j, y in enumerate(b):
            t[i + 1][j + 1] = t[i][j] + 1 if x == y else max(t[i][j + 1], t[i + 1][j])
    return t[-1][-1]


def coordinate_strips(page: np.ndarray, origin: tuple[int, int]) -> dict[str, np.ndarray]:
    """Franjas justo por fuera del tablero, donde van las coordenadas."""
    s = SQUARE
    ox, oy = origin
    near, far = s // 16, int(s * 0.75)
    return {
        "left": page[oy:oy + 8 * s, max(0, ox - far):ox - near],
        "right": page[oy:oy + 8 * s, ox + 8 * s + near:ox + 8 * s + far],
        "top": page[max(0, oy - far):oy - near, ox:ox + 8 * s],
        "bottom": page[oy + 8 * s + near:oy + 8 * s + far, ox:ox + 8 * s],
    }


def read_coordinates(page: np.ndarray, origin: tuple[int, int], tessdata: str | None = None
                     ) -> str:
    """Lo que se lee en cada franja, en orden (de arriba abajo o de izquierda a derecha)."""
    found = {}
    for side, img in coordinate_strips(page, origin).items():
        vertical = side in ("left", "right")
        ws = words(img, psm=6, whitelist=DIGITS if vertical else LETTERS, tessdata=tessdata)
        ws.sort(key=(lambda w: w.y) if vertical else (lambda w: w.x))
        found[side] = "".join(w.text for w in ws if w.conf > 30)
    return found


def flipped_by_coordinates(found: dict[str, str]) -> bool | None:
    normal = flipped = 0
    for side, text in found.items():
        expected = DIGITS if side in ("left", "right") else LETTERS
        normal += _lcs(text, expected)
        flipped += _lcs(text, expected[::-1])
    if max(normal, flipped) < MIN_COORDS or abs(normal - flipped) < COORDS_MARGIN:
        return None
    return flipped > normal


def flipped_by_pawns(labels: np.ndarray) -> bool:
    """`labels` (64, a8..h1 tal como está impreso): peones blancos claramente arriba."""
    rows = np.arange(64) // 8
    white = rows[np.asarray(labels) == CLASSES.index("P")]
    black = rows[np.asarray(labels) == CLASSES.index("p")]
    if len(white) < MIN_PAWNS or len(black) < MIN_PAWNS:
        return False
    return white.mean() - black.mean() <= -PAWN_MARGIN
