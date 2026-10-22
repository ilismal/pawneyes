"""Corrección de la predicción con reglas de legalidad básicas.

Trabaja sobre las probabilidades del clasificador (64×13, orden a8..h1):
cuando una regla se incumple, la casilla menos segura pasa a su siguiente
opción más probable. Solo numpy, para portarlo tal cual a Kotlin.

Reglas:
  - exactamente un rey de cada color;
  - ningún peón en la 1ª ni en la 8ª fila;
  - como mucho 8 peones y 16 piezas en total por color.
"""

import numpy as np

from common.squares import CLASSES

EMPTY = 0
WHITE = range(1, 7)
BLACK = range(7, 13)
W_PAWN, B_PAWN = CLASSES.index("P"), CLASSES.index("p")
W_KING, B_KING = CLASSES.index("K"), CLASSES.index("k")


def fix_position(probs: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Devuelve (etiquetas corregidas 64, máscara de casillas cambiadas 64)."""
    allowed = np.ones_like(probs, dtype=bool)
    allowed[:8, [W_PAWN, B_PAWN]] = False   # 8ª fila
    allowed[56:, [W_PAWN, B_PAWN]] = False  # 1ª fila
    raw = probs.argmax(axis=1)

    def relabel() -> np.ndarray:
        return np.where(allowed, probs, -1).argmax(axis=1)

    # Reyes: el par de casillas (blanco, negro) que menos contradice al modelo.
    # Coste de poner un rey en una casilla = cuánto peor es que la mejor
    # alternativa que no sea rey (en log-probabilidad).
    logp = np.log(np.clip(probs, 1e-9, 1))
    no_king = logp.copy()
    no_king[:, [W_KING, B_KING]] = -np.inf
    rest = no_king.max(axis=1)
    gain = {king: logp[:, king] - rest for king in (W_KING, B_KING)}
    top = {king: np.argsort(-gain[king])[:6] for king in (W_KING, B_KING)}
    w_sq, b_sq = max(((a, b) for a in top[W_KING] for b in top[B_KING] if a != b),
                     key=lambda ab: gain[W_KING][ab[0]] + gain[B_KING][ab[1]])
    taken = {int(w_sq), int(b_sq)}
    for king, sq in ((W_KING, w_sq), (B_KING, b_sq)):
        allowed[:, king] = False
        allowed[sq] = False
        allowed[sq, king] = True
    labels = relabel()

    # Límites por color: se degradan las piezas menos seguras.
    for colour, pawn in ((WHITE, W_PAWN), (BLACK, B_PAWN)):
        for cap, members in ((8, [pawn]), (16, list(colour))):
            while True:
                idx = np.flatnonzero(np.isin(labels, members))
                if len(idx) <= cap:
                    break
                idx = [i for i in idx if i not in taken]
                weakest = min(idx, key=lambda i: probs[i, labels[i]])
                allowed[weakest, members] = False
                labels = relabel()

    return labels, labels != raw
