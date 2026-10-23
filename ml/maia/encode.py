"""Entrada de 112 planos de lc0 (INPUT_CLASSICAL_112_PLANE) y decodificación de la política.

Referencia en Python para portar a Kotlin y para generar los fixtures de paridad.
"""

import chess
import numpy as np

from policy_index_lc0 import policy_index

PIECES = [chess.PAWN, chess.KNIGHT, chess.BISHOP, chess.ROOK, chess.QUEEN, chess.KING]
INDEX = {m: i for i, m in enumerate(policy_index)}


def _mirror(sq: int) -> int:
    return sq ^ 56  # misma columna, fila invertida


def planes(board: chess.Board) -> np.ndarray:
    """(112, 8, 8) float32. Sin historial, las 8 posiciones del historial son la actual
    (HistoryFill=fen_only de lc0), salvo en la posición inicial, que es un comienzo
    de partida real: ahí solo se rellena la primera."""
    x = np.zeros((112, 8, 8), np.float32)
    us = board.turn
    flip = us == chess.BLACK

    def put(plane: int, sq: int):
        s = _mirror(sq) if flip else sq
        x[plane, s // 8, s % 8] = 1

    start = board.board_fen() == chess.STARTING_BOARD_FEN and board.turn == chess.WHITE
    for h in range(1 if start else 8):
        base = 13 * h
        for i, kind in enumerate(PIECES):
            for sq in board.pieces(kind, us):
                put(base + i, sq)
            for sq in board.pieces(kind, not us):
                put(base + 6 + i, sq)
        # plano base+12: repeticiones (0 sin historial)
    them = not us
    x[104] = float(board.has_queenside_castling_rights(us))
    x[105] = float(board.has_kingside_castling_rights(us))
    x[106] = float(board.has_queenside_castling_rights(them))
    x[107] = float(board.has_kingside_castling_rights(them))
    x[108] = float(us == chess.BLACK)
    x[109] = float(board.halfmove_clock)
    x[110] = 0.0
    x[111] = 1.0
    return x


def policy_uci(board: chess.Board, move: chess.Move) -> str:
    """Jugada legal -> su cadena en la tabla de lc0 (vista desde el bando que mueve)."""
    f, t = move.from_square, move.to_square
    if board.turn == chess.BLACK:
        f, t = _mirror(f), _mirror(t)
    s = chess.square_name(f) + chess.square_name(t)
    if move.promotion and move.promotion != chess.KNIGHT:
        s += chess.piece_symbol(move.promotion)  # q, r, b; el caballo va sin sufijo
    return s


def legal_policy(board: chess.Board, logits: np.ndarray) -> dict[str, float]:
    """Probabilidades (softmax sobre las jugadas legales) por jugada UCI real."""
    moves = list(board.legal_moves)
    idx = np.array([INDEX[policy_uci(board, m)] for m in moves])
    z = logits[idx] - logits[idx].max()
    p = np.exp(z) / np.exp(z).sum()
    return {m.uci(): float(pi) for m, pi in zip(moves, p)}
