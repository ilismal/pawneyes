"""Compara la política de MaiaNet (PyTorch) con la de lc0 cargando los mismos pesos.

  cd ml && uv run python maia/check_lc0.py ruta/maia-1500.pb.gz
Necesita lc0 instalado (brew install lc0).
"""

import re
import subprocess
import sys
from pathlib import Path

import chess
import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).parent))
from encode import legal_policy, planes  # noqa: E402
from model import MaiaNet  # noqa: E402

FENS = [
    chess.STARTING_FEN,
    "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",              # 1.e4 e5, blancas
    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",                # 1.e4, negras
    "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4",       # mate del pastor
    "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R b KQkq - 0 1",      # Kiwipete, negras
    "8/1P6/8/8/8/8/6k1/K7 w - - 0 1",                                            # coronación
    "8/8/3k4/8/3K4/3P4/8/8 w - - 37 60",                                         # regla de 50 en 37
    "1K1k4/1P6/8/8/8/8/r7/2R5 w - - 0 1",                                        # Lucena
]


def lc0_policy(weights: Path, fen: str) -> dict[str, float]:
    cmds = (f"uci\nsetoption name VerboseMoveStats value true\nsetoption name PolicyTemperature value 1.0\n"
            f"isready\nposition fen {fen}\ngo nodes 1\n")
    p = subprocess.Popen(["lc0", f"--weights={weights}"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, text=True)
    p.stdin.write(cmds)
    p.stdin.flush()
    out = {}
    for line in p.stdout:
        m = re.match(r"info string (\S+)\s+\(\s*\d+\s*\).*\(P:\s*([\d.]+)%\)", line)
        if m and m.group(1) != "node":
            out[m.group(1)] = float(m.group(2)) / 100
        if line.startswith("bestmove"):
            break
    p.stdin.write("quit\n")
    p.stdin.flush()
    p.wait(timeout=30)
    return out


def main() -> None:
    weights = Path(sys.argv[1])
    model = MaiaNet.from_lc0(weights)
    worst = 0.0
    for fen in FENS:
        board = chess.Board(fen)
        with torch.no_grad():
            logits = model(torch.from_numpy(planes(board))[None])[0].numpy()
        mine = legal_policy(board, logits)
        ref = lc0_policy(weights, fen)
        diff = max(abs(mine.get(m, 0) - ref.get(m, 0)) for m in set(mine) | set(ref))
        worst = max(worst, diff)
        top_mine = max(mine, key=mine.get)
        top_ref = max(ref, key=ref.get)
        print(f"{'ok ' if top_mine == top_ref else 'DIF'} dif máx {diff * 100:5.2f} pp  "
              f"mía {top_mine} {mine[top_mine] * 100:5.1f}%  lc0 {top_ref} {ref[top_ref] * 100:5.1f}%  {fen}")
    print(f"diferencia máxima: {worst * 100:.2f} puntos porcentuales")


if __name__ == "__main__":
    main()
