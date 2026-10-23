package com.pawneyes.vision

import com.pawneyes.chess.Side
import java.text.Normalizer

/** Una línea de texto leída junto al diagrama: encima o debajo y a qué distancia (en casillas). */
data class TextLine(val text: String, val above: Boolean, val distance: Double)

/**
 * Port de ml/caption/turn.py: el turno a partir del texto que rodea al diagrama.
 *
 * Tres pistas, por orden de prioridad: una frase explícita («Juegan las negras»),
 * la última jugada impresa justo encima («5.Cf3» → negras) y la siguiente justo
 * debajo («20. C5A!» → blancas). Si se contradicen, no se propone nada.
 */
object TurnRules {
    const val LINES_NEAR = 2
    const val MAX_DISTANCE = 3.0

    private val SIDES = mapOf("blancas" to Side.WHITE, "white" to Side.WHITE, "negras" to Side.BLACK, "black" to Side.BLACK)
    private val EXPLICIT = listOf(
        Regex("""\b(?:juegan|mueven|juega|mueve|turno de)\s+(?:las\s+)?(blancas|negras)\b"""),
        Regex("""\b(blancas|negras)\s+(?:juegan|mueven)\b"""),
        Regex("""\b(white|black)\s+to\s+(?:move|play)\b"""),
        Regex("""\b(white|black)\s+(?:plays|moves)\b"""),
    )

    // Puntos suspensivos tal y como los deja el OCR: «...», «…», «. . .», ««+.», «-.».
    private const val ELLIPSIS = """(?:\.\s*){2,}|…|[«»+\-]+\s*\.|\.\s*[«»+\-]+"""
    private val ELLIPSIS_RX = Regex(ELLIPSIS)
    private val LEADING_ELLIPSIS = Regex("""[\s,]*(?:$ELLIPSIS)[\s,]*""")
    private val NUMBER = Regex("""(?<![\w.])(\d{1,3})\s*[.,]""")
    private val MOVE = Regex("""[^\s|.,;:]{2,}""")
    private val DANGLING_COMMA = Regex(""",\W*$""")
    private val BARE_BLACK_MOVE = Regex("""\s*(?:$ELLIPSIS|\.)\s*\S*[a-h][1-8]""")

    private fun norm(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD).replace(Regex("""\p{M}"""), "")

    fun explicit(lines: List<TextLine>): Side? {
        val near = lines.filter { it.distance <= MAX_DISTANCE }.sortedBy { it.distance }
        val sides = mutableSetOf<Side>()
        for (above in listOf(true, false)) {
            for (line in near.filter { it.above == above }.take(LINES_NEAR)) {
                val t = norm(line.text)
                for (rx in EXPLICIT) rx.findAll(t).forEach { sides += SIDES.getValue(it.groupValues[1]) }
            }
        }
        return sides.singleOrNull()
    }

    private class After(val ellipsis: Boolean, val moves: Int, val dangling: Boolean)

    /**
     * Tras un número: ¿empieza por puntos suspensivos?, nº de jugadas y ¿acaba en coma sin más?
     * «9. R3R,» con la respuesta cortada (otra columna, borde de la foto) no es lo mismo
     * que «8. T2D, ...», donde la respuesta se omite a propósito.
     */
    private fun movesAfter(text: String): After {
        var rest = text.trim()
        val m = LEADING_ELLIPSIS.matchAt(rest, 0)
        if (m != null) rest = rest.substring(m.range.last + 1)
        val parts = rest.split(ELLIPSIS_RX)  // «8. T2D, ...»: lo que va tras «...» no cuenta
        val segment = parts[0].split("|")[0]
        val dangling = parts.size == 1 && DANGLING_COMMA.containsMatchIn(segment)
        return After(m != null, MOVE.findAll(segment).count(), dangling)
    }

    fun lastMoveAbove(line: TextLine): Side? {
        val t = line.text.trim()
        val last = NUMBER.findAll(t).lastOrNull()
        if (last == null) {
            // «...Ab4» sin número: acaban de jugar las negras.
            return if (BARE_BLACK_MOVE.matchAt(t, 0) != null) Side.WHITE else null
        }
        val after = movesAfter(t.substring(last.range.last + 1))
        if (after.ellipsis) return if (after.moves >= 1) Side.WHITE else null
        if (after.moves == 1 && after.dangling) return null
        return when (after.moves) {
            1 -> Side.BLACK
            2 -> Side.WHITE
            else -> null
        }
    }

    fun nextMoveBelow(line: TextLine): Side? {
        val t = line.text.trim()
        val m = NUMBER.matchAt(t, 0) ?: return null
        val after = movesAfter(t.substring(m.range.last + 1))
        if (after.moves == 0) return null
        return if (after.ellipsis) Side.BLACK else Side.WHITE
    }

    fun sideToMove(lines: List<TextLine>): Side? {
        explicit(lines)?.let { return it }
        val above = lines.filter { it.above && it.distance <= MAX_DISTANCE }
        val below = lines.filter { !it.above && it.distance <= MAX_DISTANCE }
        val hints = mutableSetOf<Side>()
        above.minByOrNull { it.distance }?.let { lastMoveAbove(it) }?.let { hints += it }
        below.minByOrNull { it.distance }?.let { nextMoveBelow(it) }?.let { hints += it }
        return hints.singleOrNull()
    }
}

/**
 * Port de ml/caption/orientation.py: ¿está impreso desde el lado de las negras?
 * Las piezas salen derechas también entonces, así que el giro elegido no lo
 * distingue: se miran las coordenadas impresas y, si no las hay, los peones.
 */
object Orientation {
    const val DIGITS = "87654321"
    const val LETTERS = "abcdefgh"
    const val MIN_COORDS = 4
    const val COORDS_MARGIN = 2
    const val PAWN_MARGIN = 1.5
    const val MIN_PAWNS = 2

    /** Subsecuencia común más larga: tolera caracteres de más o de menos del OCR. */
    fun lcs(a: String, b: String): Int {
        val t = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in a.indices) for (j in b.indices) {
            t[i + 1][j + 1] = if (a[i] == b[j]) t[i][j] + 1 else maxOf(t[i][j + 1], t[i + 1][j])
        }
        return t[a.length][b.length]
    }

    /** `found`: lo leído en cada franja ("left", "right", "top", "bottom"), en orden. */
    fun flippedByCoordinates(found: Map<String, String>): Boolean? {
        var normal = 0
        var flipped = 0
        for ((side, text) in found) {
            val expected = if (side == "left" || side == "right") DIGITS else LETTERS
            normal += lcs(text, expected)
            flipped += lcs(text, expected.reversed())
        }
        if (maxOf(normal, flipped) < MIN_COORDS || kotlin.math.abs(normal - flipped) < COORDS_MARGIN) return null
        return flipped > normal
    }

    /** `labels` (64, a8..h1 tal como está impreso): peones blancos claramente arriba. */
    fun flippedByPawns(labels: IntArray): Boolean {
        val white = labels.indices.filter { labels[it] == VisionSpec.W_PAWN }.map { it / 8 }
        val black = labels.indices.filter { labels[it] == VisionSpec.B_PAWN }.map { it / 8 }
        if (white.size < MIN_PAWNS || black.size < MIN_PAWNS) return false
        return white.average() - black.average() <= -PAWN_MARGIN
    }
}
