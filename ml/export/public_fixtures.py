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
from detect.board import find_board_quads
from export.fixtures import generate

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures-publicos"

# (nombre, posición, juego de piezas y su licencia, estilo de casilla oscura, giro de la foto,
#  opciones: turno real, impreso desde las negras, coordenadas, pie)
BOARDS = [
    ("pastor", "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR", "chessnut", "hatch", 0),    # Apache 2.0
    ("espanola", "r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R", "fantasy", "dots", 0),   # MIT
    ("gambito-dama", "r1bq1rk1/pp1nbppp/2p1pn2/3p2B1/2PP4/2N1PN2/PP3PPP/R2QKB1R", "spatial", "solid", 0),  # MIT
    ("najdorf", "rnbqkb1r/1p2pppp/p2p1n2/8/3NP3/2N5/PPP2PPP/R1BQKB1R", "celtic", "cross", 0),     # MIT
    ("lucena", "1K1k4/1P6/8/8/8/8/r7/2R5", "rhosgfx", "hatch", 90),                              # CC0
    ("final-peones", "8/5k2/3p2p1/2pP1p2/2P2P2/4K1P1/8/8", "chessnut", "solid", 0),               # Apache 2.0
    # Orientación y turno (#3, #4).
    ("negras-abajo", "r2q1rk1/pp2bppp/2n1pn2/3p4/3P4/2NBPN2/PP3PPP/R2Q1RK1", "chessnut", "hatch", 0,
     {"flipped": True, "coords": True}),
    ("negras-abajo-sin-coordenadas", "r1bq1rk1/ppp2ppp/2np1n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1",
     "fantasy", "solid", 0, {"flipped": True, "coords": False}),
    ("juegan-negras", "6k1/5ppp/8/8/8/8/5PPP/3R2K1", "chessnut", "hatch", 0,
     {"side": "b", "caption": "Juegan las negras."}),
    # Dos diagramas en la misma página (#10); la posición es la del de arriba.
    ("dos-diagramas", "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR", "fantasy", "hatch", 0,
     {"second": "8/5k2/3p2p1/2pP1p2/2P2P2/4K1P1/8/8"}),
]


def photo(name: str, placement: str, set_name: str, dark: str, rotation: int, seed: int,
          options: dict | None = None) -> np.ndarray:
    options = options or {}

    def style(rng, s):
        st = original_style(rng, s)
        st.dark = dark
        st.piece_mode, st.posterize = "opaque", False
        if "coords" in options:
            st.coords = options["coords"]
        return st

    original_style = R.random_style
    R.random_style = style
    try:
        rng = np.random.default_rng(seed)
        if "second" in options:
            page, corners, s = R.render_page(
                rng, [placement_to_labels(placement), placement_to_labels(options["second"])], set_name)
        else:
            page, corners, s = R.render_board(rng, placement_to_labels(placement), set_name,
                                              flipped=options.get("flipped", False),
                                              caption=options.get("caption"))
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
        for i, (name, placement, set_name, dark, rotation, *rest) in enumerate(BOARDS):
            options = rest[0] if rest else {}
            # Algunas distorsiones aleatorias salen demasiado duras para el detector:
            # se usa la primera semilla con la que encuentra el tablero (determinista).
            wanted = 2 if "second" in options else 1
            for seed in range(100 * i, 100 * i + 100):
                img = photo(name, placement, set_name, dark, rotation, seed, options)
                if len(find_board_quads(img)) == wanted:
                    break
            cv2.imwrite(str(src / f"{name}.png"), img)
            extra = [options.get("side", "-")] + (["flipped"] if options.get("flipped") else [])
            (src / f"{name}.fen").write_text(" ".join([placement, *extra]) + "\n")
        for old in OUT.glob("*"):
            if old.name not in ("maia.json", "caption.json"):  # maia/export.py, caption.cases
                old.unlink()
        generate(src, OUT)


if __name__ == "__main__":
    main()
