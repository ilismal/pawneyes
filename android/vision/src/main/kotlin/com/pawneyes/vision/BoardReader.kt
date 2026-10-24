package com.pawneyes.vision

import com.pawneyes.chess.Piece
import com.pawneyes.chess.Position
import kotlin.math.exp
import kotlin.math.ln

/**
 * Especificación compartida con ml/common/squares.py (y comprobada con fixtures/).
 */
object VisionSpec {
    const val PHOTO_PX = 1200   // lado largo al que se reduce la foto
    const val BOARD_PX = 512    // tablero enderezado
    const val CELL = BOARD_PX / 8
    const val MARGIN = CELL / 8 // contexto extra por lado al recortar cada casilla
    const val INPUT_PX = 64     // lado de cada casilla al entrar al modelo
    const val CLASSES = ".PNBRQKpnbrqk"
    const val DOUBT = 0.45      // por debajo de esta confianza, la casilla se marca

    const val EMPTY = 0
    val W_PAWN = CLASSES.indexOf('P')
    val B_PAWN = CLASSES.indexOf('p')
    val W_KING = CLASSES.indexOf('K')
    val B_KING = CLASSES.indexOf('k')
    val WHITE = 1..6
    val BLACK = 7..12
}

/**
 * Lectura del tablero ya decidida: rotación, etiquetas y confianza por casilla.
 * `alternatives[sq]`: las clases más probables de esa casilla (la elegida primero) con su
 * probabilidad, para corregir las dudosas con un toque (#21).
 */
data class BoardReading(
    val rotation: Int,
    val rawLabels: IntArray,
    val labels: IntArray,
    val confidence: FloatArray,
    val alternatives: List<List<Pair<Int, Float>>> = emptyList(),
) {
    val doubtful: Set<Int> get() = confidence.indices.filterTo(mutableSetOf()) { confidence[it] < VisionSpec.DOUBT }

    fun position(): Position = Position(labels.map { Piece.fromFen(VisionSpec.CLASSES[it]) })

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

object BoardReader {
    /** Opciones por casilla (contando la elegida) y probabilidad mínima para ofrecer una. */
    const val MAX_ALTERNATIVES = 4
    const val MIN_ALTERNATIVE = 0.02

    /**
     * `logits[k]` son las 64×13 salidas del modelo para el tablero girado k×90°
     * en sentido antihorario. Elige la rotación y aplica las reglas de legalidad.
     */
    fun read(logits: List<Array<FloatArray>>): BoardReading {
        require(logits.size == 4)
        val k = chooseRotation(logits)
        val probs = logits[k].map { softmax(it) }.toTypedArray()
        val raw = IntArray(64) { argmax(probs[it]) }
        val labels = fixPosition(probs)
        val conf = FloatArray(64) { probs[it][labels[it]].toFloat() }
        val alternatives = List(64) { sq ->
            val others = probs[sq].indices.filter { it != labels[sq] && probs[sq][it] >= MIN_ALTERNATIVE }
                .sortedByDescending { probs[sq][it] }.take(MAX_ALTERNATIVES - 1)
            (listOf(labels[sq]) + others).map { it to probs[sq][it].toFloat() }
        }
        return BoardReading(k, raw, labels, conf, alternatives)
    }

    /**
     * La rotación con más confianza media en las casillas que ve ocupadas: una
     * casilla vacía es igual en cualquier giro y, con pocas piezas, ahogaría la señal.
     */
    fun chooseRotation(logits: List<Array<FloatArray>>): Int {
        var best = -1
        var bestConf = Double.NEGATIVE_INFINITY
        logits.forEachIndexed { k, board ->
            var sum = 0.0
            var n = 0
            for (sq in board) {
                val logp = logSoftmax(sq)
                val top = argmax(logp)
                if (top != VisionSpec.EMPTY) {
                    sum += logp[top]
                    n++
                }
            }
            val conf = if (n > 0) sum / n else Double.NEGATIVE_INFINITY
            if (best < 0 || conf > bestConf) {
                best = k
                bestConf = conf
            }
        }
        return best
    }

    /**
     * Port de ml/common/legality.py: un rey de cada color, sin peones en la 1ª ni
     * en la 8ª, como mucho 8 peones y 16 piezas por color. Cuando algo no cuadra,
     * la casilla menos segura pasa a su siguiente opción más probable.
     */
    fun fixPosition(probs: Array<DoubleArray>): IntArray {
        val n = VisionSpec.CLASSES.length
        val allowed = Array(64) { BooleanArray(n) { true } }
        for (sq in 0 until 64) {
            if (sq < 8 || sq >= 56) {
                allowed[sq][VisionSpec.W_PAWN] = false
                allowed[sq][VisionSpec.B_PAWN] = false
            }
        }
        fun relabel(): IntArray = IntArray(64) { sq ->
            var best = 0
            var bestValue = Double.NEGATIVE_INFINITY
            for (c in 0 until n) {
                val v = if (allowed[sq][c]) probs[sq][c] else -1.0
                if (v > bestValue) {
                    best = c
                    bestValue = v
                }
            }
            best
        }

        // Reyes: el par (blanco, negro) que menos contradice al modelo.
        val logp = Array(64) { sq -> DoubleArray(n) { ln(probs[sq][it].coerceIn(1e-9, 1.0)) } }
        val rest = DoubleArray(64) { sq ->
            (0 until n).filter { it != VisionSpec.W_KING && it != VisionSpec.B_KING }.maxOf { logp[sq][it] }
        }
        val gainW = DoubleArray(64) { logp[it][VisionSpec.W_KING] - rest[it] }
        val gainB = DoubleArray(64) { logp[it][VisionSpec.B_KING] - rest[it] }
        val topW = (0 until 64).sortedByDescending { gainW[it] }.take(6)
        val topB = (0 until 64).sortedByDescending { gainB[it] }.take(6)
        var pair = topW[0] to topB.first { it != topW[0] }
        var pairGain = Double.NEGATIVE_INFINITY
        for (a in topW) for (b in topB) {
            if (a != b && gainW[a] + gainB[b] > pairGain) {
                pair = a to b
                pairGain = gainW[a] + gainB[b]
            }
        }
        val taken = setOf(pair.first, pair.second)
        for ((king, sq) in listOf(VisionSpec.W_KING to pair.first, VisionSpec.B_KING to pair.second)) {
            for (s in 0 until 64) allowed[s][king] = false
            allowed[sq].fill(false)
            allowed[sq][king] = true
        }
        var labels = relabel()

        // Límites por color: se degradan las piezas menos seguras.
        for ((colour, pawn) in listOf(VisionSpec.WHITE to VisionSpec.W_PAWN, VisionSpec.BLACK to VisionSpec.B_PAWN)) {
            for ((cap, members) in listOf(8 to listOf(pawn), 16 to colour.toList())) {
                while (true) {
                    val idx = (0 until 64).filter { labels[it] in members }
                    if (idx.size <= cap) break
                    val weakest = idx.filter { it !in taken }.minBy { probs[it][labels[it]] }
                    for (c in members) allowed[weakest][c] = false
                    labels = relabel()
                }
            }
        }
        return labels
    }

    private fun argmax(v: DoubleArray): Int = v.indices.maxBy { v[it] }

    private fun logSoftmax(logits: FloatArray): DoubleArray {
        val m = logits.max().toDouble()
        val lse = m + ln(logits.sumOf { exp(it - m) })
        return DoubleArray(logits.size) { logits[it] - lse }
    }

    fun softmax(logits: FloatArray): DoubleArray = logSoftmax(logits).let { l -> DoubleArray(l.size) { exp(l[it]) } }
}
