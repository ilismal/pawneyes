package com.pawneyes.chess

enum class Castling(val letter: Char, val side: Side, val kingHome: String, val rookHome: String) {
    WHITE_KINGSIDE('K', Side.WHITE, "e1", "h1"),
    WHITE_QUEENSIDE('Q', Side.WHITE, "e1", "a1"),
    BLACK_KINGSIDE('k', Side.BLACK, "e8", "h8"),
    BLACK_QUEENSIDE('q', Side.BLACK, "e8", "a8"),
}

class FenException(message: String) : IllegalArgumentException(message)

/** Posición completa (lo que describe un FEN). Inmutable. */
data class Position(
    val board: List<Piece?>,
    val sideToMove: Side = Side.WHITE,
    val castling: Set<Castling> = emptySet(),
    val enPassant: Int? = null,
    val halfmoveClock: Int = 0,
    val fullmoveNumber: Int = 1,
) {
    init {
        require(board.size == Square.COUNT) { "el tablero debe tener 64 casillas" }
    }

    operator fun get(square: Int): Piece? = board[square]

    fun with(square: Int, piece: Piece?): Position =
        copy(board = board.toMutableList().also { it[square] = piece })

    /** Enroques posibles por la colocación: rey y torre en sus casillas iniciales. */
    fun plausibleCastling(): Set<Castling> = Castling.entries.filterTo(mutableSetOf()) {
        this[Square.parse(it.kingHome)!!] == Piece(it.side, PieceType.KING) &&
            this[Square.parse(it.rookHome)!!] == Piece(it.side, PieceType.ROOK)
    }

    /** Parte de piezas del FEN. */
    fun placement(): String = (0 until 8).joinToString("/") { row ->
        buildString {
            var empty = 0
            for (col in 0 until 8) {
                val piece = board[row * 8 + col]
                if (piece == null) {
                    empty++
                    continue
                }
                if (empty > 0) append(empty).also { empty = 0 }
                append(piece.fen)
            }
            if (empty > 0) append(empty)
        }
    }

    fun toFen(): String {
        val side = if (sideToMove == Side.WHITE) "w" else "b"
        val rights = Castling.entries.filter { it in castling }.joinToString("") { "${it.letter}" }
        val ep = enPassant?.let { Square.name(it) } ?: "-"
        return "${placement()} $side ${rights.ifEmpty { "-" }} $ep $halfmoveClock $fullmoveNumber"
    }

    companion object {
        val EMPTY = Position(List(Square.COUNT) { null })
        val START = fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")

        /**
         * Lee un FEN completo o solo la parte de piezas (lo que da el escáner);
         * los campos que falten toman su valor por defecto.
         */
        fun fromFen(fen: String): Position {
            val fields = fen.trim().split(Regex("\\s+"))
            if (fields.isEmpty() || fields.size > 6 || fields[0].isEmpty()) {
                throw FenException("FEN vacío o con demasiados campos")
            }
            val board = parsePlacement(fields[0])
            val side = when (fields.getOrElse(1) { "w" }) {
                "w" -> Side.WHITE
                "b" -> Side.BLACK
                else -> throw FenException("el turno debe ser «w» o «b»")
            }
            val castling = parseCastling(fields.getOrElse(2) { "-" })
            val enPassant = fields.getOrElse(3) { "-" }.let {
                if (it == "-") null else Square.parse(it)
                    ?.takeIf { sq -> Square.rank(sq) == 2 || Square.rank(sq) == 5 }
                    ?: throw FenException("casilla de captura al paso no válida: $it")
            }
            val halfmove = fields.getOrElse(4) { "0" }.toIntOrNull()?.takeIf { it >= 0 }
                ?: throw FenException("contador de medias jugadas no válido")
            val fullmove = fields.getOrElse(5) { "1" }.toIntOrNull()?.takeIf { it >= 1 }
                ?: throw FenException("número de jugada no válido")
            return Position(board, side, castling, enPassant, halfmove, fullmove)
        }

        private fun parsePlacement(placement: String): List<Piece?> {
            val rows = placement.split("/")
            if (rows.size != 8) throw FenException("la colocación debe tener 8 filas")
            val board = ArrayList<Piece?>(Square.COUNT)
            rows.forEachIndexed { i, row ->
                var count = 0
                for (c in row) {
                    if (c in '1'..'8') {
                        repeat(c - '0') { board.add(null) }
                        count += c - '0'
                    } else {
                        board.add(Piece.fromFen(c) ?: throw FenException("pieza desconocida: «$c»"))
                        count++
                    }
                }
                if (count != 8) throw FenException("la fila ${8 - i} no tiene 8 casillas")
            }
            return board
        }

        private fun parseCastling(field: String): Set<Castling> {
            if (field == "-") return emptySet()
            val rights = field.map { c ->
                Castling.entries.find { it.letter == c } ?: throw FenException("enroque no válido: «$c»")
            }
            if (rights.size != rights.toSet().size) throw FenException("enroque repetido")
            return rights.toSet()
        }
    }
}
