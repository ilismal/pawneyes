"""Juegos de piezas para la app (#11): PNG de 256 px en feature/board/res/drawable-nodpi/.

  cd ml && uv run python -m synth.download_pieces && uv run python -m export.app_pieces

Solo juegos cuya licencia permite redistribuirlos con una app GPLv3 (ver
android/LICENSES.md). chessnut, el de siempre, ya está en el repositorio.
"""

from pathlib import Path

import cv2

from synth.pieces import SVG_DIR, rasterize

OUT = Path(__file__).resolve().parents[2] / "android/feature/board/src/main/res/drawable-nodpi"
SETS = {"cburnett": "GPLv2+", "merida": "GPLv2+", "fantasy": "MIT"}
SIZE = 256


def main() -> None:
    for name in SETS:
        for side in "wb":
            for piece in "KQRBNP":
                img = rasterize(SVG_DIR / name / f"{side}{piece}.svg", SIZE)
                cv2.imwrite(str(OUT / f"piece_{name}_{side}{piece.lower()}.png"), img,
                            [cv2.IMWRITE_PNG_COMPRESSION, 9])
        print(f"{name}: 12 piezas")


if __name__ == "__main__":
    main()
