"""Ejecuta la detección sobre ejemplos/ y guarda imágenes de depuración en ml/out/detect/."""

import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from board import find_board_quad, warp_board  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = ROOT / "ejemplos"
OUT = ROOT / "ml" / "out" / "detect"


def draw_grid(board: np.ndarray) -> np.ndarray:
    out = board.copy()
    n = out.shape[0]
    for i in range(9):
        p = round(i * (n - 1) / 8)
        cv2.line(out, (p, 0), (p, n - 1), (0, 0, 255), 1)
        cv2.line(out, (0, p), (n - 1, p), (0, 0, 255), 1)
    return out


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    found = 0
    photos = sorted(EXAMPLES.glob("*.jpg"))
    for path in photos:
        img = cv2.imread(str(path))  # aplica la orientación EXIF
        result = find_board_quad(img)
        if result is None:
            print(f"{path.stem}: NO ENCONTRADO")
            continue
        quad, score = result
        found += 1
        overlay = img.copy()
        cv2.polylines(overlay, [quad.astype(np.int32)], True, (0, 0, 255), 12)
        cv2.imwrite(str(OUT / f"{path.stem}_quad.jpg"),
                    cv2.resize(overlay, None, fx=0.25, fy=0.25))
        cv2.imwrite(str(OUT / f"{path.stem}_board.jpg"), draw_grid(warp_board(img, quad)))
        print(f"{path.stem}: ok (tablero {score:.2f})")
    print(f"{found}/{len(photos)} encontrados")


if __name__ == "__main__":
    main()
