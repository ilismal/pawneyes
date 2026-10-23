"""Importa a entrenamiento/ los escaneos corregidos exportados desde la app.

  cd ml && uv run python -m train.import_scans ~/Downloads/pawneyes-entrenamiento-20261023.zip

En la app: tras escanear y corregir, «Guardar para entrenar»; después, en el menú
⋮, «Exportar para entrenar». Cada escaneo es una foto (ya derecha y sin EXIF) y
su .fen. Luego, ajuste fino con:

  uv run python -m train.train --init models/square_net.pt --real-frac 0.3 \\
      --epochs 4 --lr 5e-4 --out models/ft
"""

import sys
import zipfile
from pathlib import Path

import cv2
import numpy as np

from common.squares import placement_to_labels
from detect.board import find_board_quad
from train.data import TRAIN_PHOTOS


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    TRAIN_PHOTOS.mkdir(exist_ok=True)
    added = skipped = unusable = 0
    with zipfile.ZipFile(sys.argv[1]) as z:
        stems = sorted({Path(n).stem for n in z.namelist() if n.endswith(".fen")})
        for stem in stems:
            if f"{stem}.jpg" not in z.namelist():
                print(f"  {stem}: falta la foto, se omite")
                unusable += 1
                continue
            if (TRAIN_PHOTOS / f"{stem}.fen").exists():
                skipped += 1
                continue
            fen = z.read(f"{stem}.fen").decode().strip()
            try:
                placement_to_labels(fen.split()[0])
            except AssertionError:
                print(f"  {stem}: FEN no válido ({fen}), se omite")
                unusable += 1
                continue
            data = z.read(f"{stem}.jpg")
            img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
            if img is None or find_board_quad(img) is None:
                print(f"  {stem}: el detector no encuentra el tablero, se omite")
                unusable += 1
                continue
            (TRAIN_PHOTOS / f"{stem}.jpg").write_bytes(data)
            (TRAIN_PHOTOS / f"{stem}.fen").write_text(fen.split()[0] + "\n")
            added += 1
    total = len(list(TRAIN_PHOTOS.glob("*.fen")))
    print(f"{added} nuevos, {skipped} ya estaban, {unusable} descartados; "
          f"{total} fotos en {TRAIN_PHOTOS.name}/")


if __name__ == "__main__":
    main()
