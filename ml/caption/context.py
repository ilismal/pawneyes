"""Lo que el diagrama no dice pero el texto de alrededor sí: orientación y turno."""

from dataclasses import dataclass

import numpy as np

from caption.orientation import flipped_by_coordinates, flipped_by_pawns, read_coordinates
from caption.page import clean, rectify
from caption.read import text_lines
from caption.turn import side_to_move


@dataclass
class Context:
    flipped: bool             # impreso desde el lado de las negras
    flipped_by: str | None    # "coordinates" o "pawns"
    side: str | None          # "w", "b" o None si el texto no lo dice


def read_context(photo: np.ndarray, quad: np.ndarray, k: int, labels: np.ndarray,
                 tessdata: str | None = None) -> Context:
    """`labels`: lectura tal como está impresa (a8..h1 del diagrama, ya girado k)."""
    page, origin = rectify(photo, quad, k)
    by_coords = flipped_by_coordinates(read_coordinates(clean(page, origin, keep_board=True),
                                                        origin, tessdata))
    if by_coords is not None:
        flipped, by = by_coords, "coordinates"
    else:
        flipped = flipped_by_pawns(labels)
        by = "pawns" if flipped else None
    return Context(bool(flipped), by, side_to_move(text_lines(photo, quad, k, tessdata)))
