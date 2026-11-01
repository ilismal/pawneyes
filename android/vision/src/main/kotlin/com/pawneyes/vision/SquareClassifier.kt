package com.pawneyes.vision

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

/**
 * El clasificador exportado por ml/export/export_tflite.py, con los pesos en int8 (#13).
 * Entrada float32 (64, 1, 64, 64) con valores 0..255; salida (64, 13) logits.
 */
class SquareClassifier(model: ByteBuffer) : AutoCloseable {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
    private val px = VisionSpec.INPUT_PX
    private val input = ByteBuffer.allocateDirect(64 * px * px * 4).order(ByteOrder.nativeOrder())
    private val output = Array(64) { FloatArray(VisionSpec.CLASSES.length) }

    /**
     * Logits de n casillas seguidas (n·INPUT_PX² bytes, como las de [SquareCropper.crop]), en
     * lotes de 64, el tamaño fijo del modelo; el último se rellena repitiendo su primera casilla.
     */
    fun logits(squares: ByteArray): Array<FloatArray> {
        val cell = px * px
        require(squares.isNotEmpty() && squares.size % cell == 0)
        val n = squares.size / cell
        val result = ArrayList<FloatArray>(n)
        for (start in 0 until n step 64) {
            val count = minOf(64, n - start)
            input.rewind()
            for (i in 0 until 64) {
                val from = (start + if (i < count) i else 0) * cell
                for (b in from until from + cell) input.putFloat((squares[b].toInt() and 0xFF).toFloat())
            }
            input.rewind()
            interpreter.run(input, output)
            for (i in 0 until count) result += output[i].copyOf()
        }
        return result.toTypedArray()
    }

    override fun close() = interpreter.close()

    companion object {
        const val ASSET = "square_net.tflite"

        fun fromAssets(context: Context): SquareClassifier {
            context.assets.openFd(ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    val model: MappedByteBuffer = stream.channel.map(
                        FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    return SquareClassifier(model)
                }
            }
        }
    }
}
