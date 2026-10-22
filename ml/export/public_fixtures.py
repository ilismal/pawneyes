"""Fixtures públicos: diagramas sintéticos deterministas, sin fotos de libros.

  cd ml && uv run python -m synth.download_pieces && uv run python synth/pieces.py
  cd ml && uv run python -m export.public_fixtures

Genera fixtures-publicos/ (versionado), con el que los tests de paridad
Python/Android funcionan en un clon limpio del repositorio. Solo usa juegos de
piezas con licencias que permiten redistribuirlos.
"""

import tempfile
from pathlib import Path

import cv2
import numpy as np

import synth.render as R
from common.squares import placement_to_labels
from detect.board import find_board_quad
from export.fixtures import generate

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures-publicos"

# (nombre, posición, juego de piezas y su licencia, estilo de casilla oscura, giro de la foto)
BOARDS = [
    ("pastor", "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR", "chessnut", "hatch", 0),    # Apache 2.0
    ("espanola", "r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R", "fantasy", "dots", 0),   # MIT
    ("gambito-dama", "r1bq1rk1/pp1nbppp/2p1pn2/3p2B1/2PP4/2N1PN2/PP3PPP/R2QKB1R", "spatial", "solid", 0),  # MIT
    ("najdorf", "rnbqkb1r/1p2pppp/p2p1n2/8/3NP3/2N5/PPP2PPP/R1BQKB1R", "celtic", "cross", 0),     # MIT
    ("lucena", "1K1k4/1P6/8/8/8/8/r7/2R5", "rhosgfx", "hatch", 90),                              # CC0
    ("final-peones", "8/5k2/3p2p1/2pP1p2/2P2P2/4K1P1/8/8", "chessnut", "solid", 0),               # Apache 2.0
]


def photo(name: str, placement: str, set_name: str, dark: str, rotation: int, seed: int) -> np.ndarray:
    def style(rng, s):
        st = original_style(rng, s)
        st.dark = dark
        st.piece_mode, st.posterize = "opaque", False
        return st

    original_style = R.random_style
    R.random_style = style
    try:
        rng = np.random.default_rng(seed)
        page, corners, s = R.render_board(rng, placement_to_labels(placement), set_name)
        img, _, _ = R.photograph(rng, page, corners, s)
    finally:
        R.random_style = original_style
    if rotation:
        img = np.ascontiguousarray(np.rot90(img, -rotation // 90))
    # Menos niveles de gris: el PNG ocupa bastante menos y a la paridad le da igual
    # (Python y Android reciben exactamente la misma imagen).
    img = (img // 4) * 4
    return cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp)
        for i, (name, placement, set_name, dark, rotation) in enumerate(BOARDS):
            # Algunas distorsiones aleatorias salen demasiado duras para el detector:
            # se usa la primera semilla con la que encuentra el tablero (determinista).
            for seed in range(100 * i, 100 * i + 100):
                img = photo(name, placement, set_name, dark, rotation, seed)
                if find_board_quad(img) is not None:
                    break
            cv2.imwrite(str(src / f"{name}.png"), img)
            (src / f"{name}.fen").write_text(placement + "\n")
        for old in OUT.glob("*"):
            old.unlink()
        generate(src, OUT)


if __name__ == "__main__":
    main()
