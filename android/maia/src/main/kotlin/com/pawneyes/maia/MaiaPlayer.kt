package com.pawneyes.maia

import android.content.Context
import com.pawneyes.chess.Move
import com.pawneyes.chess.Position
import com.pawneyes.chess.legalMoves
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.random.Random
import org.tensorflow.lite.Interpreter

/**
 * Maia 1 (https://github.com/CSSLab/maia-chess, GPLv3): predice la jugada que
 * haría un humano de cierto Elo de Lichess, sin buscar variantes. Una inferencia
 * por jugada, de pocos milisegundos.
 */
class MaiaPlayer(model: ByteBuffer, val level: Int) : AutoCloseable {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    private val input = ByteBuffer.allocateDirect(Lc0Input.PLANES * 64 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(PolicyIndex.moves.size) }

    /** Probabilidad de cada jugada legal (softmax de la política sobre las legales). */
    fun policy(position: Position): Map<Move, Double> {
        val moves = position.legalMoves()
        if (moves.isEmpty()) return emptyMap()
        input.rewind()
        for (v in Lc0Input.planes(position)) input.putFloat(v)
        input.rewind()
        interpreter.run(input, output)
        val logits = moves.map { output[0][Lc0Input.policyIndex(position, it)].toDouble() }
        val max = logits.max()
        val e = logits.map { exp(it - max) }
        val sum = e.sum()
        return moves.indices.associate { moves[it] to e[it] / sum }
    }

    /**
     * Jugada elegida. Con `temperature` 0, la más probable; con algo más, sortea
     * entre las probables (más variado y más «humano»), ignorando las de menos del 1 %.
     */
    fun choose(position: Position, temperature: Double = 0.0, random: Random = Random.Default): Move? {
        val p = policy(position)
        if (p.isEmpty()) return null
        if (temperature <= 0.0) return p.maxBy { it.value }.key
        val weighted = p.filter { it.value >= 0.01 }.ifEmpty { p }
            .mapValues { Math.pow(it.value, 1.0 / temperature) }
        var r = random.nextDouble() * weighted.values.sum()
        for ((move, w) in weighted) {
            r -= w
            if (r <= 0) return move
        }
        return weighted.keys.last()
    }

    override fun close() = interpreter.close()

    companion object {
        val LEVELS = listOf(1100, 1300, 1500, 1700, 1900)

        fun load(context: Context, level: Int): MaiaPlayer {
            require(level in LEVELS) { "nivel de Maia no disponible: $level" }
            context.assets.openFd("maia/maia-$level.tflite").use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    val model = stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    return MaiaPlayer(model, level)
                }
            }
        }
    }
}
