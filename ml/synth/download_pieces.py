"""Descarga los juegos de piezas de Lichess usados para generar datos sintéticos.

  cd ml && uv run python -m synth.download_pieces && uv run python synth/pieces.py

No se incluyen en el repositorio porque sus licencias son muy dispares (GPL,
CC BY-NC-SA, «freeware», «solo uso personal»…): ver
https://github.com/lichess-org/lila/blob/master/COPYING.md
"""

import urllib.request
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[1] / "assets" / "pieces"
BASE = "https://raw.githubusercontent.com/lichess-org/lila/master/public/piece"
SETS = [
    "alpha", "caliente", "california", "cardinal", "cburnett", "celtic", "chess7", "chessnut",
    "companion", "cooke", "dubrovny", "fantasy", "firi", "fresca", "gioco", "governor",
    "icpieces", "kosal", "leipzig", "maestro", "merida", "mpchess", "pirouetti", "reillycraig",
    "rhosgfx", "riohacha", "spatial", "staunty", "tatiana",
]
CODES = [c + p for c in "wb" for p in "KQRBNP"]


def main() -> None:
    for name in SETS:
        out = ASSETS / name
        out.mkdir(parents=True, exist_ok=True)
        for code in CODES:
            target = out / f"{code}.svg"
            if not target.exists():
                urllib.request.urlretrieve(f"{BASE}/{name}/{code}.svg", target)
        print(f"{name}: ok")


if __name__ == "__main__":
    main()
