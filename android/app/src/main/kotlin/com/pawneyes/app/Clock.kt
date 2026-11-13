package com.pawneyes.app

import com.pawneyes.chess.Position
import com.pawneyes.chess.Side

/** Ritmo de juego (#49): minutos para cada bando y segundos que se suman en cada jugada. */
data class TimeControl(val minutes: Int, val increment: Int) {
    val label: String get() = "$minutes+$increment"

    companion object {
        val ALL = listOf(TimeControl(5, 3), TimeControl(10, 5), TimeControl(15, 10))
    }
}

/** Tiempo que le queda a cada bando, en milisegundos. */
data class Clock(val white: Long, val black: Long, val incrementMs: Long) {
    operator fun get(side: Side): Long = if (side == Side.WHITE) white else black

    private fun set(side: Side, ms: Long) = if (side == Side.WHITE) copy(white = ms) else copy(black = ms)

    /** Descuenta `ms` al bando que está pensando; no baja de cero. */
    fun spend(side: Side, ms: Long): Clock = set(side, (this[side] - ms).coerceAtLeast(0))

    /** Tras la jugada de `side`, su incremento. */
    fun moved(side: Side): Clock = set(side, this[side] + incrementMs)

    /** El bando que se ha quedado sin tiempo, si alguno. */
    val flagged: Side? get() = when {
        white <= 0 -> Side.WHITE
        black <= 0 -> Side.BLACK
        else -> null
    }

    companion object {
        fun of(tc: TimeControl): Clock = (tc.minutes * 60_000L).let { Clock(it, it, tc.increment * 1000L) }

        /**
         * Si quedarse sin tiempo es tablas: cuando el rival ya no puede dar mate. Basta con
         * mirar si solo le queda el rey; otros finales sin mate posible son raros en la práctica.
         */
        fun timeoutIsDraw(position: Position, flagged: Side): Boolean =
            position.board.count { it?.side == flagged.opponent } == 1

        /** «4:59», y con décimas en los últimos diez segundos: «9.7». */
        fun format(ms: Long): String {
            if (ms < 10_000) return "%d.%d".format(ms / 1000, ms % 1000 / 100)
            val s = (ms + 999) / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
