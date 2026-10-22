package com.pawneyes.chess

/** Jugada: casilla de origen y destino (0 = a8 … 63 = h1) y, si corona, la pieza. */
data class Move(val from: Int, val to: Int, val promotion: PieceType? = null) {
    /** Notación UCI (la que usan los motores): e2e4, e7e8q… */
    val uci: String get() = Square.name(from) + Square.name(to) + (promotion?.letter ?: "")

    companion object {
        fun fromUci(uci: String): Move? {
            if (uci.length !in 4..5) return null
            val from = Square.parse(uci.substring(0, 2)) ?: return null
            val to = Square.parse(uci.substring(2, 4)) ?: return null
            val promo = if (uci.length == 5) {
                PieceType.entries.find { it.letter == uci[4] } ?: return null
            } else {
                null
            }
            return Move(from, to, promo)
        }
    }
}

/** Letras de las piezas para la notación algebraica. */
enum class PieceLetters(private val letters: Map<PieceType, Char>) {
    /** R = rey, D = dama, T = torre, A = alfil, C = caballo. */
    SPANISH(mapOf(PieceType.KING to 'R', PieceType.QUEEN to 'D', PieceType.ROOK to 'T',
        PieceType.BISHOP to 'A', PieceType.KNIGHT to 'C')),
    ENGLISH(mapOf(PieceType.KING to 'K', PieceType.QUEEN to 'Q', PieceType.ROOK to 'R',
        PieceType.BISHOP to 'B', PieceType.KNIGHT to 'N'));

    fun of(type: PieceType): Char = letters.getValue(type)
}

private val PROMOTIONS = listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
private val KNIGHT = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
private val KING = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
private val ROOK = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
private val BISHOP = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

private fun step(square: Int, df: Int, dr: Int): Int? {
    val f = Square.file(square) + df
    val r = Square.rank(square) + dr
    return if (f in 0..7 && r in 0..7) Square.of(f, r) else null
}

/** Jugadas que siguen las reglas de movimiento, sin comprobar si dejan al rey en jaque. */
private fun Position.pseudoLegalMoves(): List<Move> {
    val side = sideToMove
    val moves = mutableListOf<Move>()
    fun add(from: Int, to: Int, pawn: Boolean) {
        val lastRank = if (side == Side.WHITE) 7 else 0
        if (pawn && Square.rank(to) == lastRank) PROMOTIONS.forEach { moves += Move(from, to, it) }
        else moves += Move(from, to)
    }
    for (from in 0 until 64) {
        val piece = this[from] ?: continue
        if (piece.side != side) continue
        when (piece.type) {
            PieceType.PAWN -> {
                val dir = if (side == Side.WHITE) 1 else -1
                val startRank = if (side == Side.WHITE) 1 else 6
                step(from, 0, dir)?.takeIf { this[it] == null }?.let { one ->
                    add(from, one, pawn = true)
                    if (Square.rank(from) == startRank) {
                        step(from, 0, 2 * dir)?.takeIf { this[it] == null }?.let { moves += Move(from, it) }
                    }
                }
                for (df in intArrayOf(-1, 1)) {
                    val to = step(from, df, dir) ?: continue
                    val target = this[to]
                    if ((target != null && target.side != side) || to == enPassant) add(from, to, pawn = true)
                }
            }
            PieceType.KNIGHT, PieceType.KING -> {
                for ((df, dr) in if (piece.type == PieceType.KNIGHT) KNIGHT else KING) {
                    val to = step(from, df, dr) ?: continue
                    if (this[to]?.side != side) moves += Move(from, to)
                }
            }
            else -> {
                val dirs = when (piece.type) {
                    PieceType.ROOK -> ROOK
                    PieceType.BISHOP -> BISHOP
                    else -> ROOK + BISHOP
                }
                for ((df, dr) in dirs) {
                    var to = step(from, df, dr)
                    while (to != null) {
                        val target = this[to]
                        if (target?.side == side) break
                        moves += Move(from, to)
                        if (target != null) break
                        to = step(to, df, dr)
                    }
                }
            }
        }
    }
    // Enroques: derecho, casillas libres y el rey no pasa por casillas atacadas.
    for (right in Castling.entries.filter { it.side == side && it in castling }) {
        val king = Square.parse(right.kingHome)!!
        val rook = Square.parse(right.rookHome)!!
        if (this[king] != Piece(side, PieceType.KING) || this[rook] != Piece(side, PieceType.ROOK)) continue
        val kingside = Square.file(rook) == 7
        val between = if (kingside) (king + 1 until rook) else (rook + 1 until king)
        if (between.any { this[it] != null }) continue
        val path = if (kingside) listOf(king, king + 1, king + 2) else listOf(king, king - 1, king - 2)
        if (path.any { isAttacked(it, side.opponent) }) continue
        moves += Move(king, if (kingside) king + 2 else king - 2)
    }
    return moves
}

/** Posición tras jugar `move` (que se supone pseudolegal). */
fun Position.play(move: Move): Position {
    val piece = requireNotNull(this[move.from]) { "no hay pieza en ${Square.name(move.from)}" }
    val board = board.toMutableList()
    val captured = board[move.to]
    board[move.to] = move.promotion?.let { Piece(piece.side, it) } ?: piece
    board[move.from] = null
    var epCapture = false
    if (piece.type == PieceType.PAWN && move.to == enPassant && captured == null) {
        // Captura al paso: el peón capturado está detrás de la casilla de destino.
        board[Square.of(Square.file(move.to), Square.rank(move.from))] = null
        epCapture = true
    }
    if (piece.type == PieceType.KING && kotlin.math.abs(Square.file(move.to) - Square.file(move.from)) == 2) {
        val kingside = Square.file(move.to) == 6
        val rank = Square.rank(move.from)
        val rookFrom = Square.of(if (kingside) 7 else 0, rank)
        val rookTo = Square.of(if (kingside) 5 else 3, rank)
        board[rookTo] = board[rookFrom]
        board[rookFrom] = null
    }
    // Se pierde el derecho de enroque si se mueve el rey o la torre, o si capturan la torre.
    val touched = setOf(Square.name(move.from), Square.name(move.to))
    val rights = castling.filterTo(mutableSetOf()) { it.kingHome !in touched && it.rookHome !in touched }
    val doublePush = piece.type == PieceType.PAWN && kotlin.math.abs(Square.rank(move.to) - Square.rank(move.from)) == 2
    return Position(
        board = board,
        sideToMove = sideToMove.opponent,
        castling = rights,
        enPassant = if (doublePush) Square.of(Square.file(move.from), (Square.rank(move.from) + Square.rank(move.to)) / 2) else null,
        halfmoveClock = if (piece.type == PieceType.PAWN || captured != null || epCapture) 0 else halfmoveClock + 1,
        fullmoveNumber = if (sideToMove == Side.BLACK) fullmoveNumber + 1 else fullmoveNumber,
    )
}

fun Position.legalMoves(): List<Move> = pseudoLegalMoves().filter { !play(it).isInCheck(sideToMove) }

fun Position.isCheckmate(): Boolean = isInCheck(sideToMove) && legalMoves().isEmpty()

/** Notación algebraica: Cf3, exd5, Txe8+, O-O, e8=D#… */
fun Position.toSan(move: Move, letters: PieceLetters = PieceLetters.SPANISH): String {
    val piece = requireNotNull(this[move.from])
    val castle = piece.type == PieceType.KING && kotlin.math.abs(Square.file(move.to) - Square.file(move.from)) == 2
    val san = StringBuilder()
    val capture = this[move.to] != null || (piece.type == PieceType.PAWN && move.to == enPassant)
    when {
        castle -> san.append(if (Square.file(move.to) == 6) "O-O" else "O-O-O")
        piece.type == PieceType.PAWN -> {
            if (capture) san.append('a' + Square.file(move.from)).append('x')
            san.append(Square.name(move.to))
            move.promotion?.let { san.append('=').append(letters.of(it)) }
        }
        else -> {
            san.append(letters.of(piece.type))
            // Desambiguación: otras piezas iguales que también pueden ir a la casilla.
            val rivals = legalMoves().filter { it.to == move.to && it.from != move.from && this[it.from] == piece }
            if (rivals.isNotEmpty()) {
                val sameFile = rivals.any { Square.file(it.from) == Square.file(move.from) }
                val sameRank = rivals.any { Square.rank(it.from) == Square.rank(move.from) }
                when {
                    !sameFile -> san.append('a' + Square.file(move.from))
                    !sameRank -> san.append(Square.rank(move.from) + 1)
                    else -> san.append(Square.name(move.from))
                }
            }
            if (capture) san.append('x')
            san.append(Square.name(move.to))
        }
    }
    val after = play(move)
    if (after.isInCheck(after.sideToMove)) san.append(if (after.legalMoves().isEmpty()) '#' else '+')
    return san.toString()
}

/**
 * Convierte una línea UCI (la «pv» de un motor) en notación algebraica con
 * números de jugada: «12. Cf3 Ad6 13. O-O» o «12... Ad6 13. O-O». Se detiene
 * en la primera jugada que no sea legal.
 */
fun Position.lineToSan(uciMoves: List<String>, letters: PieceLetters = PieceLetters.SPANISH): String {
    var p = this
    val out = StringBuilder()
    for ((i, uci) in uciMoves.withIndex()) {
        val move = Move.fromUci(uci)?.takeIf { it in p.legalMoves() } ?: break
        if (p.sideToMove == Side.WHITE) out.append("${p.fullmoveNumber}. ")
        else if (i == 0) out.append("${p.fullmoveNumber}... ")
        out.append(p.toSan(move, letters)).append(' ')
        p = p.play(move)
    }
    return out.toString().trim()
}
