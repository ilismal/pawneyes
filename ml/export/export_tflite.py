"""Exporta el clasificador a TFLite (LiteRT) y comprueba que da lo mismo que PyTorch.

  cd ml && uv run python -m export.export_tflite [models/square_net.pt]

Escribe models/square_net.tflite. Entrada: float32 (64, 1, 64, 64) en 0..255,
las 64 casillas de un tablero en el orden de common.squares; la
estandarización por casilla va dentro del modelo. Salida: logits (64, 13).
"""

import sys
import time
from pathlib import Path

import numpy as np
import torch

import litert_torch
from ai_edge_litert.interpreter import Interpreter

from common.legality import fix_position
from common.squares import crop_squares
from train.data import predict_board, real_boards
from train.model import SquareNet

MODELS = Path(__file__).resolve().parents[1] / "models"
BATCH = 64  # un tablero por llamada


class TFLiteModel:
    """Envoltorio con la misma interfaz que SquareNet para reutilizar predict_board."""

    def __init__(self, path: Path):
        self.interp = Interpreter(model_path=str(path))
        self.interp.allocate_tensors()
        self.inp = self.interp.get_input_details()[0]
        self.out = self.interp.get_output_details()[0]

    def __call__(self, x: torch.Tensor) -> torch.Tensor:
        self.interp.set_tensor(self.inp["index"], x.cpu().numpy().astype(np.float32))
        self.interp.invoke()
        return torch.from_numpy(self.interp.get_tensor(self.out["index"]).copy())


def main() -> None:
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else MODELS / "square_net.pt"
    dst = src.with_suffix(".tflite")
    ckpt = torch.load(src, map_location="cpu")
    model = SquareNet(ckpt["width"]).eval()
    model.load_state_dict(ckpt["state"])

    sample = torch.zeros(BATCH, 1, 64, 64)
    litert_torch.convert(model, (sample,)).export(str(dst))
    print(f"exportado {dst.name}: {dst.stat().st_size / 1024:.0f} KB")

    tfl = TFLiteModel(dst)
    print(f"entrada {tfl.inp['shape'].tolist()} {tfl.inp['dtype'].__name__}, "
          f"salida {tfl.out['shape'].tolist()}")

    # 1. Mismas casillas por ambos modelos (las 4 rotaciones de cada tablero real).
    boards = real_boards()
    max_diff, mismatches, total = 0.0, 0, 0
    for _, board, _ in boards:
        for k in range(4):
            x = torch.from_numpy(crop_squares(np.ascontiguousarray(np.rot90(board, k)))[:, None])
            with torch.no_grad():
                p_torch = torch.softmax(model(x), 1).numpy()
            p_tfl = torch.softmax(tfl(x), 1).numpy()
            max_diff = max(max_diff, float(np.abs(p_torch - p_tfl).max()))
            mismatches += int((p_torch.argmax(1) != p_tfl.argmax(1)).sum())
            total += BATCH
    print(f"probabilidades: diferencia máxima {max_diff:.2e}; "
          f"clase distinta en {mismatches}/{total} casillas")

    # 2. Evaluación completa (rotación + reglas) usando solo TFLite.
    correct = perfect = 0
    for _, board, labels in boards:
        _, probs, _ = predict_board(tfl, board, "cpu")
        pred, _ = fix_position(probs)
        correct += int((pred == labels).sum())
        perfect += int((pred == labels).all())
    print(f"TFLite en ejemplos/: casillas {correct}/{64 * len(boards)} = "
          f"{correct / (64 * len(boards)):.4f}   tableros perfectos {perfect}/{len(boards)}")

    # 3. Tiempo por tablero en este Mac (CPU), como referencia.
    x = torch.from_numpy(crop_squares(boards[0][1])[:, None])
    tfl(x)
    t0 = time.perf_counter()
    for _ in range(50):
        tfl(x)
    print(f"tiempo por tablero (64 casillas, CPU del Mac): "
          f"{(time.perf_counter() - t0) / 50 * 1000:.1f} ms")


if __name__ == "__main__":
    main()
