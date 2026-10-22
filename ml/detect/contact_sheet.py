"""Junta los tableros enderezados de ml/out/detect/ en una sola imagen."""

from pathlib import Path

import cv2
import numpy as np

OUT = Path(__file__).resolve().parents[1] / "out" / "detect"


def main() -> None:
    tiles = []
    for path in sorted(OUT.glob("*_board.jpg")):
        tile = cv2.resize(cv2.imread(str(path)), (320, 320))
        cv2.putText(tile, path.stem[9:15], (6, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 0, 0), 2)
        tiles.append(tile)
    cols = 4
    while len(tiles) % cols:
        tiles.append(np.full((320, 320, 3), 255, np.uint8))
    rows = [np.hstack(tiles[i:i + cols]) for i in range(0, len(tiles), cols)]
    cv2.imwrite(str(OUT / "contact_sheet.jpg"), np.vstack(rows))


if __name__ == "__main__":
    main()
