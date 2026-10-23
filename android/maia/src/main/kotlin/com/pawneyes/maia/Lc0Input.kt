package com.pawneyes.maia

import com.pawneyes.chess.Castling
import com.pawneyes.chess.Move
import com.pawneyes.chess.PieceType
import com.pawneyes.chess.Position
import com.pawneyes.chess.Side
import com.pawneyes.chess.Square

/**
 * Entrada de 112 planos de lc0 (INPUT_CLASSICAL_112_PLANE), port de ml/maia/encode.py.
 *
 * Planos de 8×8 con fila 0 = primera fila, vistos desde el bando que mueve (si
 * juegan las negras se invierten las filas). 8 posiciones de historial × 13 planos
 * (6 piezas propias, 6 del rival, repetición) y 8 auxiliares: enroques largo y corto
 * propios y del rival, turno de negras, regla de 50, cero y unos.
 */
object Lc0Input {
    const val PLANES = 112
    private val ORDER = listOf(PieceType.PAWN, PieceType.KNIGHT, PieceType.BISHOP, PieceType.ROOK,
        PieceType.QUEEN, PieceType.KING)
    private val START = Position.START.placement()

    /** Fila y columna de una casilla vista desde `side` (0 = su primera fila). */
    private fun rowCol(square: Int, side: Side): Pair<Int, Int> {
        val rank = Square.rank(square)
        return (if (side == Side.WHITE) rank else 7 - rank) to Square.file(square)
    }

    /**
     * FloatArray de 112·64 en orden [plano][fila][columna]. Sin historial, las 8
     * posiciones son la actual (HistoryFill=fen_only), salvo en la posición inicial,
     * que es un comienzo de partida real: ahí solo se rellena la primera.
     */
    fun planes(p: Position): FloatArray {
        val x = FloatArray(PLANES * 64)
        val us = p.sideToMove
        val history = if (p.placement() == START && us == Side.WHITE) 1 else 8
        for (h in 0 until history) {
            val base = 13 * h
            for (sq in 0 until 64) {
                val piece = p[sq] ?: continue
                val (r, c) = rowCol(sq, us)
                val plane = base + ORDER.indexOf(piece.type) + if (piece.side == us) 0 else 6
                x[plane * 64 + r * 8 + c] = 1f
            }
        }
        fun fill(plane: Int, v: Float) = (0 until 64).forEach { x[plane * 64 + it] = v }
        val them = us.opponent
        fun has(side: Side, kingside: Boolean) = Castling.entries.any {
            it.side == side && it in p.castling && (it.rookHome[0] == 'h') == kingside
        }
        fill(104, if (has(us, kingside = false)) 1f else 0f)
        fill(105, if (has(us, kingside = true)) 1f else 0f)
        fill(106, if (has(them, kingside = false)) 1f else 0f)
        fill(107, if (has(them, kingside = true)) 1f else 0f)
        fill(108, if (us == Side.BLACK) 1f else 0f)
        fill(109, p.halfmoveClock.toFloat())
        fill(111, 1f)
        return x
    }

    /** Índice en la política de lc0 de una jugada legal de `p`. */
    fun policyIndex(p: Position, move: Move): Int {
        fun name(sq: Int): String {
            val (r, c) = rowCol(sq, p.sideToMove)
            return "${'a' + c}${r + 1}"
        }
        val promo = move.promotion?.takeIf { it != PieceType.KNIGHT }?.letter?.toString() ?: ""
        return PolicyIndex.of(name(move.from) + name(move.to) + promo)
    }
}
