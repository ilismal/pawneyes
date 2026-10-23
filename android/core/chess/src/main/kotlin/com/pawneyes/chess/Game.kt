package com.pawneyes.chess

/** Cómo ha terminado una partida. */
sealed interface GameResult {
    data class Checkmate(val winner: Side) : GameResult
    data object Stalemate : GameResult
    data object InsufficientMaterial : GameResult
    data object FiftyMoves : GameResult
    data object Repetition : GameResult
}

/** Una partida desde una posición cualquiera: jugadas, deshacer y final de partida. */
class Game(val start: Position, moves: List<Move> = emptyList()) {
    val moves: List<Move>
    /** positions[i] = posición tras i jugadas. */
    val positions: List<Position>

    init {
        val ps = mutableListOf(start)
        for (m in moves) {
            require(m in ps.last().legalMoves()) { "jugada ilegal: ${m.uci}" }
            ps += ps.last().play(m)
        }
        this.moves = moves
        this.positions = ps
    }

    val position: Position get() = positions.last()

    fun play(move: Move): Game = Game(start, moves + move)

    /** Deshace `n` jugadas (contra un rival, normalmente 2: la suya y la tuya). */
    fun undo(n: Int = 1): Game = Game(start, moves.dropLast(n.coerceAtMost(moves.size)))

    /** Notación de cada jugada, con letras españolas: «1. e4», «1... e5»… */
    fun san(letters: PieceLetters = PieceLetters.SPANISH): List<String> = moves.mapIndexed { i, m ->
        val p = positions[i]
        val num = if (p.sideToMove == Side.WHITE) "${p.fullmoveNumber}. " else if (i == 0) "${p.fullmoveNumber}... " else ""
        num + p.toSan(m, letters)
    }

    val result: GameResult?
        get() {
            val p = position
            if (p.legalMoves().isEmpty()) {
                return if (p.isInCheck(p.sideToMove)) GameResult.Checkmate(p.sideToMove.opponent) else GameResult.Stalemate
            }
            if (insufficientMaterial(p)) return GameResult.InsufficientMaterial
            if (p.halfmoveClock >= 100) return GameResult.FiftyMoves
            val key = repetitionKey(p)
            if (positions.count { repetitionKey(it) == key } >= 3) return GameResult.Repetition
            return null
        }

    private fun repetitionKey(p: Position) = "${p.placement()} ${p.sideToMove} ${p.castling} ${p.enPassant}"

    /** Sin peones, torres ni damas, y como mucho una pieza menor en total (o alfiles del mismo color). */
    private fun insufficientMaterial(p: Position): Boolean {
        val pieces = p.board.withIndex().filter { it.value != null && it.value!!.type != PieceType.KING }
        if (pieces.any { it.value!!.type in setOf(PieceType.PAWN, PieceType.ROOK, PieceType.QUEEN) }) return false
        if (pieces.size <= 1) return true
        // Solo alfiles, todos en casillas del mismo color.
        if (pieces.all { it.value!!.type == PieceType.BISHOP }) {
            return pieces.map { (Square.file(it.index) + Square.rank(it.index)) % 2 }.toSet().size == 1
        }
        return false
    }
}
