"""Especificación del preprocesado de casillas, compartida por generador,
entrenamiento y evaluación (y la referencia para portarlo a Android).

  - El tablero enderezado mide BOARD_PX × BOARD_PX en gris, con a8 arriba a
    la izquierda y h1 abajo a la derecha (blancas abajo).
  - Cada casilla se recorta con MARGIN px extra por lado (contexto para
    tolerar errores de ajuste de la cuadrícula) y se escala a INPUT_PX.
  - El orden de las 64 casillas es por filas: a8, b8, ..., h8, a7, ..., h1.
"""

import cv2
import numpy as np

BOARD_PX = 512
CELL = BOARD_PX // 8
MARGIN = CELL // 8
INPUT_PX = 64

# Clase 0 = vacía; 1..6 blancas, 7..12 negras (orden de FEN).
CLASSES = ".PNBRQKpnbrqk"


def crop_squares(board: np.ndarray) -> np.ndarray:
    """Tablero enderezado (BOARD_PX², gris uint8) -> (64, INPUT_PX, INPUT_PX) uint8."""
    assert board.shape == (BOARD_PX, BOARD_PX), board.shape
    padded = cv2.copyMakeBorder(board, MARGIN, MARGIN, MARGIN, MARGIN, cv2.BORDER_REPLICATE)
    size = CELL + 2 * MARGIN
    out = np.empty((64, INPUT_PX, INPUT_PX), np.uint8)
    for i in range(8):
        for j in range(8):
            crop = padded[i * CELL:i * CELL + size, j * CELL:j * CELL + size]
            out[i * 8 + j] = cv2.resize(crop, (INPUT_PX, INPUT_PX), interpolation=cv2.INTER_AREA)
    return out


def labels_to_placement(labels) -> str:
    """64 etiquetas (orden a8..h1) -> parte de piezas del FEN."""
    rows = []
    for i in range(8):
        row, empty = "", 0
        for j in range(8):
            c = CLASSES[labels[i * 8 + j]]
            if c == ".":
                empty += 1
                continue
            if empty:
                row += str(empty)
                empty = 0
            row += c
        rows.append(row + (str(empty) if empty else ""))
    return "/".join(rows)


def placement_to_labels(placement: str) -> np.ndarray:
    labels = []
    for row in placement.split("/"):
        for c in row:
            labels.extend([0] * int(c) if c.isdigit() else [CLASSES.index(c)])
    assert len(labels) == 64, placement
    return np.array(labels, np.int64)
