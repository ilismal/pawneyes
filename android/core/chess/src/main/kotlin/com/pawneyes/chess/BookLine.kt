package com.pawneyes.chess

/**
 * Jugadas escritas como en los libros (#5): notación descriptiva española («P4R»,
 * «C3AR», «T×P», «C(3A)×D», «R1A») o algebraica («Cf3», «Nf3», «exd5»), mezcladas si
 * hace falta.
 *
 * La descriptiva no nombra casillas sino que da pistas: qué pieza, a qué fila y columna
 * vista desde el bando que juega, o qué captura. Cada jugada se traduce a esas
 * restricciones y se busca la única jugada legal que las cumple; si son varias, es
 * ambigua y no se elige ninguna.
 */
object BookLine {
    sealed interface Result {
        data class Ok(val moves: List<Move>) : Result

        /** `index`: número de jugada (desde 0) en la que se ha parado; `token`: tal como estaba escrita. */
        data class Error(val index: Int, val token: String, val reason: Reason, val moves: List<Move>) : Result
    }

    sealed interface Reason {
        /** No parece una jugada. */
        data object Unknown : Reason

        /** Se entiende, pero no hay ninguna jugada legal así. */
        data object Illegal : Reason

        /** Varias jugadas legales encajan (en notación algebraica, para mostrarlas). */
        data class Ambiguous(val candidates: List<String>) : Reason
    }

    /** Las jugadas de `text` desde `start`; para en la primera que no entienda. */
    fun parse(start: Position, text: String): Result {
        val moves = mutableListOf<Move>()
        var p = start
        for ((i, token) in tokens(text).withIndex()) {
            val found = candidates(p, token)
            val move = when {
                found == null -> return Result.Error(i, token, Reason.Unknown, moves)
                found.isEmpty() -> return Result.Error(i, token, Reason.Illegal, moves)
                found.size > 1 -> return Result.Error(i, token,
                    Reason.Ambiguous(found.map { p.toSan(it) }.sorted()), moves)
                else -> found.single()
            }
            moves += move
            p = p.play(move)
        }
        return Result.Ok(moves)
    }

    private val ANNOTATIONS = Regex("""(?i)\b(?:a\.?\s?p\.?|al paso|e\.?\s?p\.?|jaque mate|jaque|mate)(?=\s|,|$)|[!?+#]""")
    private val NUMBER = Regex("""^\d+\.+""")

    /** Las jugadas de un texto, sin números, puntos suspensivos ni comentarios de jugada. */
    fun tokens(text: String): List<String> {
        val clean = text.replace('…', ' ').replace(ANNOTATIONS, " ")
        return clean.split(Regex("""[\s,;|]+"""))
            .map { it.replace(NUMBER, "") }          // «19.C4D» y «19.» → «C4D» y «»
            .filter { it.isNotEmpty() && !Regex("""^\.+$""").matches(it) && !Regex("""^\d+$""").matches(it) }
    }

    /** null si no se entiende; si no, las jugadas legales que encajan. */
    fun candidates(p: Position, token: String): List<Move>? {
        castling(p, token)?.let { return it }
        descriptive(p, token)?.let { return it }
        return algebraic(p, token)
    }

    // ---------- Enroques ----------

    private fun castling(p: Position, token: String): List<Move>? {
        val t = token.uppercase().replace('0', 'O')
        val long = when (t) {
            "O-O", "OO" -> false
            "O-O-O", "OOO" -> true
            else -> return null
        }
        return p.legalMoves().filter { m ->
            p[m.from]?.type == PieceType.KING && kotlin.math.abs(Square.file(m.to) - Square.file(m.from)) == 2 &&
                (Square.file(m.to) == 2) == long
        }
    }

    // ---------- Descriptiva ----------

    private const val NAMES = "TR|TD|CR|CD|AR|AD|R|D|T|C|A"

    /**
     * pieza, calificativo, (origen), y destino («4R») o captura («×P», «×PD»), coronación.
     * Calificativos y columnas: R = rey, D = dama, A/C/T = alfil, caballo, torre de
     * cualquier flanco, AR = alfil de rey…
     */
    private val DESCRIPTIVE = Regex(
        """^([RDTACP])($NAMES)?(?:\(([1-8])?($NAMES)?\))?""" +
            """(?:([1-8])($NAMES)|[x×X:]([RDTACP])($NAMES)?|[x×X:]([1-8])($NAMES))""" +
            """(?:=?\(?([DTAC])\)?)?$""",
    )

    private val PIECES = mapOf(
        'R' to PieceType.KING, 'D' to PieceType.QUEEN, 'T' to PieceType.ROOK,
        'A' to PieceType.BISHOP, 'C' to PieceType.KNIGHT, 'P' to PieceType.PAWN,
    )

    /** Columnas (0 = a … 7 = h) que nombra cada nombre de columna. */
    private fun files(name: String): Set<Int> = when (name) {
        "TD" -> setOf(0); "CD" -> setOf(1); "AD" -> setOf(2); "D" -> setOf(3)
        "R" -> setOf(4); "AR" -> setOf(5); "CR" -> setOf(6); "TR" -> setOf(7)
        "A" -> setOf(2, 5); "C" -> setOf(1, 6); "T" -> setOf(0, 7)
        else -> error(name)
    }

    /**
     * Calificativo de una pieza: «TR» es la torre del flanco de rey (columnas e–h) y
     * «CD» el caballo del de dama (a–d); en un peón, en cambio, nombra su columna
     * («PR» = peón de rey, «PAR» = peón del alfil de rey).
     */
    private fun qualifierFiles(type: PieceType, name: String): Set<Int> = when {
        type == PieceType.PAWN -> files(name)
        name == "R" -> setOf(4, 5, 6, 7)
        name == "D" -> setOf(0, 1, 2, 3)
        else -> files(name)
    }

    /** Fila 1–8 contada desde el bando `side` → fila 0–7 del tablero. */
    private fun rank(side: Side, n: Int): Int = if (side == Side.WHITE) n - 1 else 8 - n

    private fun descriptive(p: Position, token: String): List<Move>? {
        val m = DESCRIPTIVE.matchEntire(token) ?: return null
        val g = m.groupValues
        val side = p.sideToMove
        val type = PIECES.getValue(g[1][0])
        val qualifier = g[2].takeIf { it.isNotEmpty() }?.let { qualifierFiles(type, it) }
        val originRank = g[3].takeIf { it.isNotEmpty() }?.let { rank(side, it.toInt()) }
        val originFiles = g[4].takeIf { it.isNotEmpty() }?.let { files(it) }
        val promotion = g[11].takeIf { it.isNotEmpty() }?.let { PIECES.getValue(it[0]) }
        return p.legalMoves().filter { mv ->
            val piece = p[mv.from] ?: return@filter false
            if (piece.type != type) return@filter false
            if (qualifier != null && Square.file(mv.from) !in qualifier) return@filter false
            if (originRank != null && Square.rank(mv.from) != originRank) return@filter false
            if (originFiles != null && Square.file(mv.from) !in originFiles) return@filter false
            // Sin pieza indicada, la coronación es a dama.
            if (mv.promotion != null && mv.promotion != (promotion ?: PieceType.QUEEN)) return@filter false
            if (mv.promotion == null && promotion != null) return@filter false
            when {
                g[5].isNotEmpty() -> Square.rank(mv.to) == rank(side, g[5].toInt()) && Square.file(mv.to) in files(g[6]) &&
                    p[mv.to] == null && !(type == PieceType.PAWN && mv.to == p.enPassant && Square.file(mv.to) != Square.file(mv.from))
                g[7].isNotEmpty() -> {
                    val capturedType = PIECES.getValue(g[7][0])
                    val target = p[mv.to]
                    val captured = target?.type ?: if (type == PieceType.PAWN && mv.to == p.enPassant) PieceType.PAWN else null
                    // Al paso, el peón capturado está en la columna de destino.
                    captured == capturedType && (target == null || target.side != side) &&
                        (g[8].isEmpty() || Square.file(mv.to) in qualifierFiles(capturedType, g[8]))
                }
                else -> p[mv.to] != null && Square.rank(mv.to) == rank(side, g[9].toInt()) && Square.file(mv.to) in files(g[10])
            }
        }
    }

    // ---------- Algebraica ----------

    private fun norm(san: String) = san.replace(Regex("""[+#!?=]"""), "").replace('×', 'x').replace(':', 'x')
        .replace('0', 'O')

    private fun algebraic(p: Position, token: String): List<Move>? {
        val t = norm(token)
        if (t.isEmpty()) return null
        val legal = p.legalMoves()
        val found = legal.filter { mv ->
            PieceLetters.entries.any { letters -> norm(p.toSan(mv, letters)) == t }
        }
        if (found.isNotEmpty()) return found
        // Parece algebraica (acaba en casilla), pero no es legal aquí.
        return if (Regex("""^[RDTACKQBN]?[a-h]?[1-8]?x?[a-h][1-8][RDTACQBN]?$""").matches(t)) emptyList() else null
    }
}
