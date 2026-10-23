"""Página enderezada alrededor del diagrama, para leer el texto (pie y coordenadas).

La homografía del tablero sirve para toda la página cercana: se proyecta un
rectángulo mayor que el tablero, ya girado para que las piezas queden derechas.
"""

import cv2
import numpy as np

SQUARE = 80  # px por casilla en la página enderezada: el texto queda de unos 30–40 px de alto


def upright_corners(quad: np.ndarray, k: int) -> np.ndarray:
    """Esquinas (tl, tr, br, bl) del tablero tras girarlo k×90° antihorario (np.rot90)."""
    return np.roll(np.asarray(quad, np.float32), -k, axis=0)


def rectify(photo: np.ndarray, quad: np.ndarray, k: int, left=2.0, top=4.0, right=2.0,
            bottom=4.0) -> tuple[np.ndarray, tuple[int, int]]:
    """Página enderezada con márgenes (en casillas) alrededor del tablero.

    Devuelve la imagen en gris y el origen (x, y) del tablero dentro de ella.
    """
    s = SQUARE
    ox, oy = int(left * s), int(top * s)
    dst = np.array([[ox, oy], [ox + 8 * s, oy], [ox + 8 * s, oy + 8 * s], [ox, oy + 8 * s]],
                   np.float32)
    gray = photo if photo.ndim == 2 else cv2.cvtColor(photo, cv2.COLOR_BGR2GRAY)
    # Al encoger, warpPerspective (bilineal) mete aliasing en el texto: primero se reduce
    # la foto por áreas hasta que el tablero mida lo que medirá enderezado. Así el OCR
    # ve lo mismo sea cual sea la resolución de la foto.
    quad = np.asarray(quad, np.float32)
    side = np.mean([np.linalg.norm(quad[i] - quad[(i + 1) % 4]) for i in range(4)])
    f = 8 * s / side
    if f < 1:
        gray = cv2.resize(gray, None, fx=f, fy=f, interpolation=cv2.INTER_AREA)
        quad = quad * f
    m = cv2.getPerspectiveTransform(upright_corners(quad, k), dst)
    size = (int((left + 8 + right) * s), int((top + 8 + bottom) * s))
    page = cv2.warpPerspective(gray, m, size, flags=cv2.INTER_LINEAR, borderValue=255)
    return page, (ox, oy)


def clean(page: np.ndarray, origin: tuple[int, int], keep_board: bool = False) -> np.ndarray:
    """Binariza con umbral local (sombras, papel amarillento) y borra el tablero."""
    s = SQUARE
    bw = cv2.adaptiveThreshold(page, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY,
                               2 * (s // 2) + 1, 15)
    ox, oy = origin
    pad = s // 4
    if not keep_board:
        bw[max(0, oy - pad):oy + 8 * s + pad, max(0, ox - pad):ox + 8 * s + pad] = 255
    return bw


def strips(page: np.ndarray, origin: tuple[int, int]) -> dict[str, np.ndarray]:
    """Franjas de texto encima y debajo del tablero (lo bastante anchas para el pie)."""
    s = SQUARE
    ox, oy = origin
    x0, x1 = max(0, ox - 2 * s), min(page.shape[1], ox + 10 * s)
    return {
        "above": page[max(0, oy - 4 * s):max(0, oy - s // 8), x0:x1],
        "below": page[oy + 8 * s + s // 8:oy + 12 * s, x0:x1],
    }
