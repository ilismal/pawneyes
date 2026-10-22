package com.pawneyes.chess

/** Motivos por los que una posición no puede darse en una partida. */
sealed interface Issue {
    data class KingCount(val side: Side, val count: Int) : Issue
    data class PawnOnBackRank(val square: Int) : Issue
    data class TooManyPawns(val side: Side, val count: Int) : Issue
    data class TooManyPieces(val side: Side, val count: Int) : Issue

    /** El bando que no mueve está en jaque: el que mueve podría capturar al rey. */
    data class OpponentInCheck(val side: Side) : Issue
    data class CastlingWithoutPieces(val right: Castling) : Issue
    data class InvalidEnPassant(val square: Int) : Issue
}

fun Position.issues(): List<Issue> {
    val issues = mutableListOf<Issue>()
    for (side in Side.entries) {
        val mine = board.filter { it?.side == side }
        val kings = mine.count { it!!.type == PieceType.KING }
        if (kings != 1) issues += Issue.KingCount(side, kings)
        val pawns = mine.count { it!!.type == PieceType.PAWN }
        if (pawns > 8) issues += Issue.TooManyPawns(side, pawns)
        if (mine.size > 16) issues += Issue.TooManyPieces(side, mine.size)
    }
    board.forEachIndexed { sq, piece ->
        if (piece?.type == PieceType.PAWN && Square.rank(sq) in setOf(0, 7)) {
            issues += Issue.PawnOnBackRank(sq)
        }
    }
    val waiting = sideToMove.opponent
    if (isInCheck(waiting)) issues += Issue.OpponentInCheck(waiting)
    (castling - plausibleCastling()).forEach { issues += Issue.CastlingWithoutPieces(it) }
    enPassant?.let { if (!isValidEnPassant(it)) issues += Issue.InvalidEnPassant(it) }
    return issues
}

fun Position.isLegal(): Boolean = issues().isEmpty()

fun Position.isInCheck(side: Side): Boolean {
    val king = board.indexOf(Piece(side, PieceType.KING))
    return king >= 0 && isAttacked(king, side.opponent)
}

/** ¿Ataca alguna pieza de `by` la casilla `square`? */
fun Position.isAttacked(square: Int, by: Side): Boolean =
    board.indices.any { from -> board[from]?.side == by && attacks(from).contains(square) }

private fun Position.isValidEnPassant(square: Int): Boolean {
    // Juegan blancas: el peón negro acaba de avanzar dos y la casilla está en la 6ª.
    val (epRank, pawnRank, pawnSide) =
        if (sideToMove == Side.WHITE) Triple(5, 4, Side.BLACK) else Triple(2, 3, Side.WHITE)
    val file = Square.file(square)
    return Square.rank(square) == epRank && this[square] == null &&
        this[Square.of(file, pawnRank)] == Piece(pawnSide, PieceType.PAWN)
}

private val KNIGHT_STEPS = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
private val KING_STEPS = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
private val ROOK_DIRS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
private val BISHOP_DIRS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

/** Casillas que ataca la pieza de `from` (no son jugadas: un peón ataca en diagonal). */
private fun Position.attacks(from: Int): Set<Int> {
    val piece = this[from] ?: return emptySet()
    val f = Square.file(from)
    val r = Square.rank(from)
    fun at(df: Int, dr: Int): Int? =
        if (f + df in 0..7 && r + dr in 0..7) Square.of(f + df, r + dr) else null
    fun rays(dirs: List<Pair<Int, Int>>): Set<Int> = buildSet {
        for ((df, dr) in dirs) {
            var k = 1
            while (true) {
                val sq = at(df * k, dr * k) ?: break
                add(sq)
                if (this@attacks[sq] != null) break
                k++
            }
        }
    }
    return when (piece.type) {
        PieceType.PAWN -> {
            val dr = if (piece.side == Side.WHITE) 1 else -1
            setOfNotNull(at(-1, dr), at(1, dr))
        }
        PieceType.KNIGHT -> KNIGHT_STEPS.mapNotNullTo(mutableSetOf()) { (df, dr) -> at(df, dr) }
        PieceType.KING -> KING_STEPS.mapNotNullTo(mutableSetOf()) { (df, dr) -> at(df, dr) }
        PieceType.BISHOP -> rays(BISHOP_DIRS)
        PieceType.ROOK -> rays(ROOK_DIRS)
        PieceType.QUEEN -> rays(ROOK_DIRS + BISHOP_DIRS)
    }
}
