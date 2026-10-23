"""Exporta Maia 1 (varios niveles) a TFLite y genera fixtures de paridad para Android.

  cd ml && uv run python maia/export.py CARPETA_CON_maia-XXXX.pb.gz

Escribe ml/models/maia/maia-XXXX.tflite (entrada float32 (1, 112, 8, 8), salida
(1, 1858) logits de política) y fixtures-publicos/maia.json.
"""

import json
import sys
from pathlib import Path

import chess
import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).parent))
import litert_torch  # noqa: E402
from ai_edge_litert.interpreter import Interpreter  # noqa: E402
from check_lc0 import FENS  # noqa: E402
from encode import legal_policy, planes  # noqa: E402
from model import MaiaNet  # noqa: E402

ML = Path(__file__).resolve().parents[1]
OUT = ML / "models" / "maia"
FIXTURES = ML.parent / "fixtures-publicos" / "maia.json"
LEVELS = [1100, 1300, 1500, 1700, 1900]


def main() -> None:
    src = Path(sys.argv[1])
    OUT.mkdir(parents=True, exist_ok=True)
    fixtures = {"levels": LEVELS, "positions": []}
    boards = [chess.Board(f) for f in FENS]
    for board in boards:
        x = planes(board)
        nz = np.argwhere(x[:104] > 0)
        fixtures["positions"].append({
            "fen": board.fen(),
            # Planos de piezas como (plano, fila, columna) activos; los 8 auxiliares, con su valor.
            "piece_planes": nz.tolist(),
            "aux": [float(x[p, 0, 0]) for p in range(104, 112)],
            "policy": {},
        })
    for level in LEVELS:
        model = MaiaNet.from_lc0(src / f"maia-{level}.pb.gz")
        dst = OUT / f"maia-{level}.tflite"
        litert_torch.convert(model, (torch.zeros(1, 112, 8, 8),)).export(str(dst))
        interp = Interpreter(model_path=str(dst))
        interp.allocate_tensors()
        inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
        worst = 0.0
        for board, fx in zip(boards, fixtures["positions"]):
            x = torch.from_numpy(planes(board))[None]
            with torch.no_grad():
                ref = model(x)[0].numpy()
            interp.set_tensor(inp["index"], x.numpy())
            interp.invoke()
            got = interp.get_tensor(out["index"])[0]
            worst = max(worst, float(np.abs(ref - got).max()))
            probs = legal_policy(board, got)
            fx["policy"][str(level)] = {m: round(p, 6) for m, p in sorted(probs.items(), key=lambda kv: -kv[1])[:5]}
        print(f"maia-{level}: {dst.stat().st_size // 1024} KB, diferencia máx. de logits con PyTorch {worst:.2e}")
    FIXTURES.write_text(json.dumps(fixtures))
    print(f"fixtures: {FIXTURES}")


if __name__ == "__main__":
    main()
