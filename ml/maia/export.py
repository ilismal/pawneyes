"""Exporta Maia 1 (varios niveles) a TFLite y genera fixtures de paridad para Android.

  cd ml && uv run python maia/export.py CARPETA_CON_maia-XXXX.pb.gz
  cd ml && uv run python maia/export.py --float16    # pasar a float16 los .tflite ya exportados

Escribe ml/models/maia/maia-XXXX.tflite (entrada float32 (1, 112, 8, 8), salida
(1, 1858) logits de política) y fixtures-publicos/maia.json.

Los pesos se guardan en float16 (#12): la mitad de tamaño y la misma política (en
1275 posiciones de partidas, como mucho un 0,2 % de diferencia). En int8 cambiaba la
jugada más probable en un 5 % de las posiciones: Maia ya no jugaría igual.
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
from ai_edge_quantizer import qtyping, quantizer, recipe, recipe_manager  # noqa: E402
from check_lc0 import FENS  # noqa: E402
from encode import legal_policy, planes  # noqa: E402
from model import MaiaNet  # noqa: E402

ML = Path(__file__).resolve().parents[1]
OUT = ML / "models" / "maia"
FIXTURES = ML.parent / "fixtures-publicos" / "maia.json"
LEVELS = [1100, 1300, 1500, 1700, 1900]


def to_float16(path: Path) -> None:
    """Reescribe el modelo con los pesos en float16; el cálculo sigue en float32."""
    rm = recipe_manager.RecipeManager()
    rm.add_weight_only_config(regex=".*", operation_name=qtyping.TFLOperationName.ALL_SUPPORTED,
                              num_bits=16, algorithm_key=recipe.AlgorithmName.FLOAT_CASTING)
    qt = quantizer.Quantizer(str(path))
    qt.load_quantization_recipe(rm.get_quantization_recipe())
    qt.quantize().export_model(str(path), overwrite=True)


def run(interp: Interpreter, board: chess.Board) -> np.ndarray:
    inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
    interp.set_tensor(inp["index"], planes(board)[None])
    interp.invoke()
    return interp.get_tensor(out["index"])[0]


def main() -> None:
    float16_only = sys.argv[1] == "--float16"
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
        dst = OUT / f"maia-{level}.tflite"
        if float16_only:
            before = Interpreter(model_path=str(dst))
            before.allocate_tensors()
            ref = [run(before, board) for board in boards]
            to_float16(dst)
        else:
            model = MaiaNet.from_lc0(src / f"maia-{level}.pb.gz")
            litert_torch.convert(model, (torch.zeros(1, 112, 8, 8),)).export(str(dst))
            to_float16(dst)
            with torch.no_grad():
                ref = [model(torch.from_numpy(planes(board))[None])[0].numpy() for board in boards]
        interp = Interpreter(model_path=str(dst))
        interp.allocate_tensors()
        worst = 0.0
        for board, fx, r in zip(boards, fixtures["positions"], ref):
            got = run(interp, board)
            worst = max(worst, float(np.abs(r - got).max()))
            probs = legal_policy(board, got)
            fx["policy"][str(level)] = {m: round(p, 6) for m, p in sorted(probs.items(), key=lambda kv: -kv[1])[:5]}
        print(f"maia-{level}: {dst.stat().st_size // 1024} KB, diferencia máx. de logits con el original {worst:.2e}")
    FIXTURES.write_text(json.dumps(fixtures))
    print(f"fixtures: {FIXTURES}")


if __name__ == "__main__":
    main()
