"""Propone el FEN de las fotos que aún no tienen .fen, para revisarlo a mano.

  cd ml && uv run python -m train.propose [carpeta]   (por defecto ejemplos/)

Para cada foto sin .fen escribe <foto>.pred.fen y una imagen de revisión en
ml/out/review/: el tablero enderezado (ya girado) junto a la lectura del
modelo, con las casillas dudosas en rojo. Una vez revisado, se renombra
.pred.fen a .fen.
"""

import sys
from pathlib import Path

import cv2
import numpy as np
import torch

from common.legality import fix_position
from common.squares import BOARD_PX, CELL, CLASSES, labels_to_placement
from detect.board import find_board_quad, warp_board
from train.data import EXAMPLES, predict_board
from train.model import SquareNet

ML = Path(__file__).resolve().parents[1]
REVIEW = ML / "out" / "review"
DOUBT = 0.45  # por debajo, la casilla se marca para revisar
PHOTO_EXT = {".jpg", ".jpeg", ".png"}


def render_reading(labels: np.ndarray, conf: np.ndarray) -> np.ndarray:
    """Tablero esquemático con la letra FEN de cada casilla."""
    img = np.full((BOARD_PX, BOARD_PX, 3), 255, np.uint8)
    for sq in range(64):
        i, j = divmod(sq, 8)
        y, x = i * CELL, j * CELL
        if (i + j) % 2:
            img[y:y + CELL, x:x + CELL] = 200
        if conf[sq] < DOUBT:
            cv2.rectangle(img, (x + 2, y + 2), (x + CELL - 3, y + CELL - 3), (0, 0, 255), 3)
        c = CLASSES[labels[sq]]
        if c != ".":
            colour = (0, 0, 0) if c.islower() else (160, 90, 0)
            cv2.putText(img, c, (x + 18, y + 46), cv2.FONT_HERSHEY_SIMPLEX, 1.4, colour, 3,
                        cv2.LINE_AA)
    return img


def main() -> None:
    folder = Path(sys.argv[1]) if len(sys.argv) > 1 else EXAMPLES
    device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    ckpt = torch.load(ML / "models" / "square_net.pt", map_location=device)
    model = SquareNet(ckpt["width"]).to(device)
    model.load_state_dict(ckpt["state"])
    model.eval()
    REVIEW.mkdir(parents=True, exist_ok=True)

    photos = sorted(p for p in folder.iterdir()
                    if p.suffix.lower() in PHOTO_EXT and not p.with_suffix(".fen").exists())
    for photo in photos:
        img = cv2.imread(str(photo))
        found = find_board_quad(img)
        if found is None:
            print(f"{photo.name}: sin tablero")
            continue
        board = cv2.cvtColor(warp_board(img, found[0]), cv2.COLOR_BGR2GRAY)
        _, probs, k = predict_board(model, board, device)
        labels, _ = fix_position(probs)
        conf = probs[np.arange(64), labels]
        placement = labels_to_placement(labels)
        photo.with_suffix(".pred.fen").write_text(placement + "\n")

        upright = cv2.cvtColor(np.ascontiguousarray(np.rot90(board, k)), cv2.COLOR_GRAY2BGR)
        sheet = np.hstack([upright, np.full((BOARD_PX, 16, 3), 255, np.uint8),
                           render_reading(labels, conf)])
        cv2.imwrite(str(REVIEW / f"{photo.stem}.jpg"), sheet)
        doubtful = [("abcdefgh"[s % 8] + str(8 - s // 8)) for s in np.flatnonzero(conf < DOUBT)]
        print(f"{photo.name}: {placement}  giro {k * 90}°  dudosas {doubtful or '-'}")


if __name__ == "__main__":
    main()
