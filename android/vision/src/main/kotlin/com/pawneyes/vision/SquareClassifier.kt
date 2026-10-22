package com.pawneyes.vision

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

/**
 * El clasificador exportado por ml/export/export_tflite.py.
 * Entrada float32 (64, 1, 64, 64) con valores 0..255; salida (64, 13) logits.
 */
class SquareClassifier(model: ByteBuffer) : AutoCloseable {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
    private val px = VisionSpec.INPUT_PX
    private val input = ByteBuffer.allocateDirect(64 * px * px * 4).order(ByteOrder.nativeOrder())
    private val output = Array(64) { FloatArray(VisionSpec.CLASSES.length) }

    /** `squares`: salida de [SquareCropper.crop]. */
    fun logits(squares: ByteArray): Array<FloatArray> {
        require(squares.size == 64 * px * px)
        input.rewind()
        for (b in squares) input.putFloat((b.toInt() and 0xFF).toFloat())
        input.rewind()
        interpreter.run(input, output)
        return Array(64) { output[it].copyOf() }
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
