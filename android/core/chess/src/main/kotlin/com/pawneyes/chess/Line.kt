package com.pawneyes.chess

/**
 * Una línea de jugadas desde una posición, con todas las posiciones intermedias.
 * Las jugadas UCI que no sean legales cortan la línea.
 */
class Line(val start: Position, uciMoves: List<String>) {
    val moves: List<Move>
    /** positions[i] = posición tras i jugadas (positions[0] = start). */
    val positions: List<Position>

    init {
        val moves = mutableListOf<Move>()
        val positions = mutableListOf(start)
        for (uci in uciMoves) {
            val p = positions.last()
            val move = Move.fromUci(uci)?.takeIf { it in p.legalMoves() } ?: break
            moves += move
            positions += p.play(move)
        }
        this.moves = moves
        this.positions = positions
    }

    /** Notación de cada jugada: «12. Cf3», «12... Ad6», «13. O-O»… */
    fun san(letters: PieceLetters = PieceLetters.SPANISH): List<String> = moves.mapIndexed { i, move ->
        val p = positions[i]
        val number = if (p.sideToMove == Side.WHITE) "${p.fullmoveNumber}. " else "${p.fullmoveNumber}... "
        number + p.toSan(move, letters)
    }

    val size: Int get() = moves.size
}
