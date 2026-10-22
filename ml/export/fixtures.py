"""Genera fixtures/ para comprobar que la implementación Android da lo mismo que Python.

  cd ml && uv run python -m export.fixtures

Por cada foto de ejemplos/ en la que el detector encuentra tablero:
  <nombre>.photo.png    la foto reducida a PHOTO_PX de lado largo, en color (entrada
                        del detector; PNG para no depender del decodificador JPEG)
  <nombre>.board.png    tablero enderezado 512×512, gris, sacado de esa foto
  <nombre>.squares.bin  64 casillas sin girar (k=0), 64×64×64 bytes uint8,
                        orden a8..h1, fila a fila (salida esperada del recorte)
y en manifest.json, para cada una:
  logits[k][casilla][clase]  salida del modelo TFLite para el tablero girado
                             k×90° (np.rot90, antihorario)
  quad           esquinas del tablero en la foto reducida (tl, tr, br, bl)
  rotation       k elegido por predict_board
  placement_raw  lectura sin reglas;  placement  lectura con reglas
  truth          posición real (del .fen)
"""

import json
from pathlib import Path

import cv2
import numpy as np
import torch

from common.legality import fix_position
from common.squares import (BOARD_PX, CLASSES, INPUT_PX, MARGIN, crop_squares,
                            labels_to_placement, placement_to_labels)
from detect.board import find_board_quad, warp_board
from export.export_tflite import TFLiteModel
from train.data import EXAMPLES, predict_board

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "fixtures"
MODEL = ROOT / "ml" / "models" / "square_net.tflite"
PHOTO_PX = 1200  # = detect.board.WORK_SIZE: el detector trabaja a esta escala


def main() -> None:
    FIXTURES.mkdir(exist_ok=True)
    tfl = TFLiteModel(MODEL)
    items = []
    for fen in sorted(EXAMPLES.glob("*.fen")):
        stem = fen.stem.replace(" ", "_")
        img = cv2.imread(str(fen.with_suffix(".jpg")))
        f = PHOTO_PX / max(img.shape[:2])
        photo = cv2.resize(img, None, fx=f, fy=f, interpolation=cv2.INTER_AREA)
        found = find_board_quad(photo)
        if found is None:
            print(f"{stem}: sin tablero")
            continue
        quad = found[0]
        board = cv2.cvtColor(warp_board(photo, quad), cv2.COLOR_BGR2GRAY)
        truth = placement_to_labels(fen.read_text().split()[0])
        cv2.imwrite(str(FIXTURES / f"{stem}.photo.png"), photo)
        cv2.imwrite(str(FIXTURES / f"{stem}.board.png"), board)
        squares = crop_squares(board)
        (FIXTURES / f"{stem}.squares.bin").write_bytes(squares.tobytes())

        logits = []
        for k in range(4):
            x = torch.from_numpy(crop_squares(np.ascontiguousarray(np.rot90(board, k)))[:, None])
            logits.append(tfl(x).numpy().round(5).tolist())
        raw, probs, k = predict_board(tfl, board, "cpu")
        fixed, _ = fix_position(probs)
        items.append({
            "name": stem,
            "photo": f"{stem}.photo.png",
            "quad": quad.round(2).tolist(),
            "board": f"{stem}.board.png",
            "squares": f"{stem}.squares.bin",
            "rotation": int(k),
            "placement_raw": labels_to_placement(raw),
            "placement": labels_to_placement(fixed),
            "truth": labels_to_placement(truth),
            "logits": logits,
        })
        ok = int((fixed == truth).sum())
        print(f"{stem}: giro {k * 90}°  {ok}/64  {labels_to_placement(fixed)}")

    manifest = {
        "board_px": BOARD_PX, "input_px": INPUT_PX, "margin": MARGIN, "classes": CLASSES,
        "photo_px": PHOTO_PX, "model": MODEL.name, "boards": items,
    }
    (FIXTURES / "manifest.json").write_text(json.dumps(manifest))
    print(f"{len(items)} tableros en {FIXTURES}")


if __name__ == "__main__":
    main()
