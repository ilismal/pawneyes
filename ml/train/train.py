"""Entrena el clasificador de casillas.

  cd ml && uv run python -m train.train --epochs 12 --max-minutes 100
  cd ml && uv run python -m train.train --resume        # continúa donde lo dejó

Ajuste fino con fotos reales etiquetadas (entrenamiento/):
  cd ml && uv run python -m train.train --init models/square_net.pt --real-frac 0.3 \
      --epochs 4 --lr 5e-4 --out models/ft

Cada época informa de la precisión en sintético (juegos de piezas reservados)
y en las fotos reales de ejemplos/ (con las reglas de legalidad aplicadas),
que es la métrica que importa. Al final de cada época se guarda el estado
completo en <out>/train_state.pt, así que un corte solo pierde la época en
curso. Con --max-minutes se para limpiamente antes de agotar ese tiempo.
"""

import argparse
import time
from pathlib import Path

import cv2
import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader

from common.legality import fix_position
from synth.render import VAL_SETS, train_sets
from train.data import (TRAIN_PHOTOS, SyntheticSquares, labelled_photos, predict_board,
                        real_boards, synthetic_boards)
from train.model import SquareNet

MODELS = Path(__file__).resolve().parents[1] / "models"


def _single_thread_worker(_: int) -> None:
    """Un hilo por proceso de datos: si no, OpenCV y PyTorch abren ~8 cada
    uno y los 6-7 procesos compiten entre sí por los 8 núcleos."""
    cv2.setNumThreads(1)
    torch.set_num_threads(1)


def evaluate(model, device, val_x, val_y, reals) -> tuple[float, float, int]:
    model.eval()
    with torch.no_grad():
        preds = []
        for xb in np.array_split(val_x.reshape(-1, 1, 64, 64), 50):
            preds.append(model(torch.from_numpy(xb).to(device)).argmax(1).cpu().numpy())
    syn = float((np.concatenate(preds) == val_y.ravel()).mean())
    correct = total = perfect = 0
    for _, board, labels in reals:
        _, probs, _ = predict_board(model, board, device)
        pred, _ = fix_position(probs)
        correct += int((pred == labels).sum())
        total += 64
        perfect += int((pred == labels).all())
    model.train()
    return syn, correct / max(total, 1), perfect


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--steps", type=int, default=800, help="pasos por época")
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--width", type=int, default=16, help="canales de la primera capa")
    ap.add_argument("--out", type=Path, default=MODELS, help="carpeta de modelos y estado")
    ap.add_argument("--resume", action="store_true", help="continuar desde train_state.pt")
    ap.add_argument("--init", type=Path, default=None, help="pesos de partida (ajuste fino)")
    ap.add_argument("--real-frac", type=float, default=0.0,
                    help=f"fracción de tableros sacados de las fotos de {TRAIN_PHOTOS.name}/")
    ap.add_argument("--max-minutes", type=float, default=None,
                    help="parar al acabar una época si no da tiempo a otra")
    args = ap.parse_args()

    device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    out = args.out
    state = torch.load(out / "train_state.pt", map_location=device) if args.resume else None
    if state:
        # La configuración del entrenamiento es la del estado guardado.
        for key in ("epochs", "steps", "batch", "lr", "width", "real_frac"):
            setattr(args, key, state["args"][key])

    torch.manual_seed(0)
    model = SquareNet(args.width).to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr,
                                                total_steps=args.epochs * args.steps)
    start, best = 1, (-1, -1.0)
    if args.init and not state:
        init = torch.load(args.init, map_location=device)
        if init["width"] != args.width:
            raise SystemExit(f"--width {args.width} no coincide con {args.init} ({init['width']})")
        model.load_state_dict(init["state"])
        print(f"partiendo de {args.init}")
    if state:
        model.load_state_dict(state["model"])
        opt.load_state_dict(state["opt"])
        sched.load_state_dict(state["sched"])
        start, best = state["epoch"] + 1, tuple(state["best"])
        print(f"reanudando en la época {start}/{args.epochs}")
    if start > args.epochs:
        print("el entrenamiento ya estaba terminado")
        return
    print(f"parámetros: {sum(p.numel() for p in model.parameters()):,}  dispositivo: {device}")

    print("preparando validación...")
    val_x, val_y = synthetic_boards(list(VAL_SETS), 150, seed=1234)
    reals = real_boards()
    print(f"  sintético: {val_y.size} casillas, reales: {len(reals)} tableros")
    train_photos = labelled_photos(TRAIN_PHOTOS) if args.real_frac > 0 else []
    if train_photos:
        print(f"  entrenamiento con {len(train_photos)} fotos reales "
              f"({args.real_frac:.0%} de los tableros)")

    # Semilla distinta al reanudar para no repetir los mismos tableros.
    dataset = SyntheticSquares(train_sets(), seed=start, real=train_photos,
                               real_frac=args.real_frac)
    loader = DataLoader(dataset, batch_size=args.batch,
                        num_workers=args.workers, persistent_workers=True, prefetch_factor=4,
                        worker_init_fn=_single_thread_worker)
    loss_fn = nn.CrossEntropyLoss(label_smoothing=0.05)

    out.mkdir(parents=True, exist_ok=True)
    began = time.time()
    it = iter(loader)
    for epoch in range(start, args.epochs + 1):
        t0, run_loss = time.time(), 0.0
        for _ in range(args.steps):
            x, y = next(it)
            x, y = x.to(device), y.to(device)
            loss = loss_fn(model(x), y)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            sched.step()
            run_loss += loss.item()
        syn, real, perfect = evaluate(model, device, val_x, val_y, reals)
        took = time.time() - t0
        print(f"época {epoch:2d}  pérdida {run_loss / args.steps:.4f}  "
              f"sintético {syn:.4f}  real {real:.4f}  perfectos {perfect}/{len(reals)}  "
              f"({took:.0f}s)", flush=True)

        ckpt = {"width": args.width, "state": model.state_dict(), "epoch": epoch}
        torch.save(ckpt, out / "square_net_last.pt")
        # Mejor = más tableros perfectos; a igualdad, más casillas bien.
        if (perfect, real) >= best:
            best = (perfect, real)
            torch.save(ckpt, out / "square_net.pt")
        torch.save({"model": model.state_dict(), "opt": opt.state_dict(),
                    "sched": sched.state_dict(), "epoch": epoch, "best": list(best),
                    "args": {k: v for k, v in vars(args).items() if k != "out"}},
                   out / "train_state.pt")

        elapsed = (time.time() - began) / 60
        if args.max_minutes and epoch < args.epochs and elapsed + took / 60 > args.max_minutes:
            print(f"parado por tiempo tras la época {epoch} ({elapsed:.0f} min); "
                  f"continuar con --resume", flush=True)
            return
    print("entrenamiento terminado", flush=True)


if __name__ == "__main__":
    main()
