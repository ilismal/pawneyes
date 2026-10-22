package com.pawneyes.chess

enum class Side {
    WHITE, BLACK;

    val opponent: Side get() = if (this == WHITE) BLACK else WHITE
}

enum class PieceType(val letter: Char) {
    PAWN('p'), KNIGHT('n'), BISHOP('b'), ROOK('r'), QUEEN('q'), KING('k'),
}

data class Piece(val side: Side, val type: PieceType) {
    /** Letra FEN: mayúscula para blancas, minúscula para negras. */
    val fen: Char get() = if (side == Side.WHITE) type.letter.uppercaseChar() else type.letter

    companion object {
        fun fromFen(c: Char): Piece? {
            val type = PieceType.entries.find { it.letter == c.lowercaseChar() } ?: return null
            return Piece(if (c.isUpperCase()) Side.WHITE else Side.BLACK, type)
        }

        val ALL: List<Piece> = Side.entries.flatMap { side -> PieceType.entries.map { Piece(side, it) } }
    }
}

/**
 * Casillas como índice 0..63, por filas desde arriba: 0 = a8, 7 = h8, 56 = a1, 63 = h1.
 * Es el mismo orden que usa el pipeline de visión (ml/common/squares.py).
 */
object Square {
    const val COUNT = 64

    /** `file` 0..7 = a..h, `rank` 0..7 = 1..8. */
    fun of(file: Int, rank: Int): Int = (7 - rank) * 8 + file

    fun file(square: Int): Int = square % 8

    fun rank(square: Int): Int = 7 - square / 8

    fun name(square: Int): String = "${'a' + file(square)}${rank(square) + 1}"

    fun parse(name: String): Int? {
        if (name.length != 2) return null
        val file = name[0] - 'a'
        val rank = name[1] - '1'
        return if (file in 0..7 && rank in 0..7) of(file, rank) else null
    }
}
