"""Líneas de texto alrededor del diagrama, con su posición respecto al tablero."""

from collections import defaultdict

import numpy as np

from caption.ocr import words
from caption.page import SQUARE, clean, rectify, strips
from caption.turn import Line


def text_lines(photo: np.ndarray, quad: np.ndarray, k: int, tessdata: str | None = None
               ) -> list[Line]:
    page, origin = rectify(photo, quad, k)
    bw = clean(page, origin)
    result = []
    for name, img in strips(bw, origin).items():
        groups = defaultdict(list)
        for w in words(img, psm=6, tessdata=tessdata):
            groups[w.line].append(w)
        for ws in groups.values():
            ws.sort(key=lambda w: w.x)
            text = " ".join(w.text for w in ws)
            if name == "above":
                dist = (img.shape[0] - max(w.y + w.h for w in ws)) / SQUARE
            else:
                dist = min(w.y for w in ws) / SQUARE
            result.append(Line(text, name == "above", round(dist, 2)))
    return result
