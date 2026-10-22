"""Rasteriza los juegos de piezas SVG (assets/pieces) a PNG con alfa.

Se ejecuta una vez: uv run python synth/pieces.py
"""

import re
from pathlib import Path

import cv2
import numpy as np
import resvg_py

ML = Path(__file__).resolve().parents[1]
SVG_DIR = ML / "assets" / "pieces"
PNG_DIR = ML / "assets" / "pieces_png"
SIZE = 128

# Códigos de pieza = nombres de fichero de Lichess (wK, bP, ...).
CODES = [c + p for c in "wb" for p in "KQRBNP"]


def _normalize_root(svg: str, size: int) -> str:
    """Fija width/height del <svg> raíz a `size` y garantiza un viewBox.

    Los juegos de Lichess traen tamaños heterogéneos (mm, pt, 100%, 1px o
    ninguno) que resvg no siempre acepta.
    """
    m = re.search(r"<svg\b[^>]*>", svg)
    root = m.group(0)
    attrs = dict(re.findall(r'\s([\w:-]+)="([^"]*)"', root))
    if "viewBox" not in attrs:
        w = float(re.sub(r"[^\d.]", "", attrs.get("width", "45")) or 45)
        h = float(re.sub(r"[^\d.]", "", attrs.get("height", "45")) or 45)
        attrs["viewBox"] = f"0 0 {w} {h}"
    attrs["width"] = attrs["height"] = str(size)
    new_root = "<svg " + " ".join(f'{k}="{v}"' for k, v in attrs.items()) + ">"
    return svg[:m.start()] + new_root + svg[m.end():]


def rasterize(svg: Path, size: int = SIZE) -> np.ndarray:
    """SVG -> BGRA de size×size (resvg entiende el CSS de estos SVG; MuPDF no)."""
    text = _normalize_root(svg.read_text(), size)
    png = bytes(resvg_py.svg_to_bytes(svg_string=text))
    img = cv2.imdecode(np.frombuffer(png, np.uint8), cv2.IMREAD_UNCHANGED)
    if img.shape[:2] != (size, size):
        img = cv2.resize(img, (size, size), interpolation=cv2.INTER_AREA)
    return img


def main() -> None:
    sets = sorted(d for d in SVG_DIR.iterdir() if d.is_dir())
    for d in sets:
        out = PNG_DIR / d.name
        out.mkdir(parents=True, exist_ok=True)
        for code in CODES:
            cv2.imwrite(str(out / f"{code}.png"), rasterize(d / f"{code}.svg"))
    print(f"{len(sets)} juegos rasterizados en {PNG_DIR}")


if __name__ == "__main__":
    main()
