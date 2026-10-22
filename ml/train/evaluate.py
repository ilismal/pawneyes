"""Evaluación detallada en las fotos reales de ejemplos/.

  cd ml && uv run python -m train.evaluate [models/square_net.pt]
"""

import sys
from collections import Counter
from pathlib import Path

import torch

from common.legality import fix_position
from common.squares import CLASSES, labels_to_placement
from train.data import predict_board, real_boards
from train.model import SquareNet

MODELS = Path(__file__).resolve().parents[1] / "models"


def square_name(idx: int, k: int) -> str:
    """Casilla en notación algebraica (orientación ya corregida)."""
    i, j = divmod(idx, 8)
    return "abcdefgh"[j] + str(8 - i)


def main() -> None:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else MODELS / "square_net.pt"
    device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    ckpt = torch.load(path, map_location=device)
    model = SquareNet(ckpt["width"]).to(device)
    model.load_state_dict(ckpt["state"])
    model.eval()

    confusions = Counter()
    raw_correct = correct = total = raw_perfect = perfect = 0
    for name, board, labels in real_boards():
        raw, probs, k = predict_board(model, board, device)
        pred, changed = fix_position(probs)
        raw_ok, ok = int((raw == labels).sum()), int((pred == labels).sum())
        raw_correct, correct, total = raw_correct + raw_ok, correct + ok, total + 64
        raw_perfect, perfect = raw_perfect + (raw_ok == 64), perfect + (ok == 64)
        fixed = f"  (sin reglas {raw_ok}/64)" if raw_ok != ok else ""
        print(f"{name}  {ok}/64{fixed}  giro {k * 90}°  {labels_to_placement(pred)}")
        for idx in changed.nonzero()[0]:
            mark = "bien" if pred[idx] == labels[idx] else "mal"
            print(f"    {square_name(idx, k)}: regla {CLASSES[raw[idx]]} -> {CLASSES[pred[idx]]} ({mark})")
        for idx in (pred != labels).nonzero()[0]:
            t, p = CLASSES[labels[idx]], CLASSES[pred[idx]]
            confusions[(t, p)] += 1
            print(f"    {square_name(idx, k)}: real {t}  predicho {p} ({probs[idx, pred[idx]]:.2f})")
    print(f"\nsin reglas: casillas {raw_correct}/{total} = {raw_correct / total:.4f}   "
          f"tableros perfectos {raw_perfect}")
    print(f"con reglas: casillas {correct}/{total} = {correct / total:.4f}   "
          f"tableros perfectos {perfect}")
    print("confusiones más frecuentes (real -> predicho):")
    for (t, p), n in confusions.most_common(10):
        print(f"  {t} -> {p}: {n}")

if __name__ == "__main__":
    main()
