package com.pawneyes.chess

/**
 * PGN de una posición (con [SetUp] y [FEN]) y, opcionalmente, una línea de jugadas
 * desde ella. La notación va con letras inglesas, como exige el estándar, para que
 * cualquier app de ajedrez lo entienda.
 */
object Pgn {
    fun of(
        start: Position,
        moves: List<Move> = emptyList(),
        comment: String? = null,
        date: String = "????.??.??",
        event: String = "Pawneyes",
    ): String = buildString {
        val tags = listOf(
            "Event" to event, "Site" to "?", "Date" to date, "Round" to "-",
            "White" to "?", "Black" to "?", "Result" to "*",
            "SetUp" to "1", "FEN" to start.toFen(),
        )
        tags.forEach { (k, v) -> append("[$k \"${v.replace("\"", "'")}\"]\n") }
        append('\n')
        val tokens = mutableListOf<String>()
        comment?.let { tokens += "{ ${it.replace("}", ")")} }" }
        var p = start
        for ((i, move) in moves.withIndex()) {
            require(move in p.legalMoves()) { "jugada ilegal en el PGN: ${move.uci}" }
            if (p.sideToMove == Side.WHITE) tokens += "${p.fullmoveNumber}."
            else if (i == 0) tokens += "${p.fullmoveNumber}..."
            tokens += p.toSan(move, PieceLetters.ENGLISH)
            p = p.play(move)
        }
        tokens += "*"
        // Líneas de como mucho 80 caracteres, como recomienda el estándar.
        var width = 0
        for ((i, t) in tokens.withIndex()) {
            if (i > 0) {
                if (width + 1 + t.length > 80) { append('\n'); width = 0 } else { append(' '); width++ }
            }
            append(t)
            width += t.length
        }
        append('\n')
    }
}
