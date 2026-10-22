package com.pawneyes.vision

import android.content.Context
import android.graphics.Bitmap
import com.pawneyes.chess.Position
import kotlin.math.max
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Resultado de escanear una foto. */
class ScanResult(
    /** Posición leída, con blancas abajo (la rotación ya aplicada). */
    val position: Position,
    /** Casillas con confianza baja, para que el usuario las revise. */
    val doubtful: Set<Int>,
    /** Tablero enderezado y girado como se ha leído, para compararlo con la lectura. */
    val board: Bitmap,
    val reading: BoardReading,
    val detection: BoardDetector.Detection,
)

/**
 * Pipeline completo, igual que en ml/: foto -> detector -> tablero 512² ->
 * casillas en las 4 rotaciones -> modelo -> rotación + reglas.
 */
class Scanner(private val classifier: SquareClassifier) : AutoCloseable {

    /** `null` si no se encuentra ningún tablero en la foto. */
    fun scan(photo: Bitmap): ScanResult? {
        val rgba = Mat()
        Utils.bitmapToMat(photo, rgba)
        return scan(rgba).also { rgba.release() }
    }

    /** `rgba`: foto RGBA (CV_8UC4) de cualquier tamaño. */
    fun scan(rgba: Mat): ScanResult? {
        val photo = resizeToWorkSize(rgba)
        val detection = BoardDetector.detect(photo) ?: return null
        val color = BoardDetector.warp(photo, detection.quad)
        val board = Mat()
        Imgproc.cvtColor(color, board, Imgproc.COLOR_RGBA2GRAY)
        val logits = (0 until 4).map { k ->
            val rotated = SquareCropper.rotate(board, k)
            classifier.logits(SquareCropper.crop(rotated)).also { rotated.release() }
        }
        val reading = BoardReader.read(logits)
        val upright = SquareCropper.rotate(board, reading.rotation)
        val bitmap = Bitmap.createBitmap(upright.cols(), upright.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(upright, bitmap)
        listOf(photo, color, board, upright).forEach { it.release() }
        return ScanResult(reading.position(), reading.doubtful, bitmap, reading, detection)
    }

    override fun close() = classifier.close()

    companion object {
        /** La foto se lleva a PHOTO_PX de lado largo, como al generar los fixtures. */
        fun resizeToWorkSize(rgba: Mat): Mat {
            val f = VisionSpec.PHOTO_PX.toDouble() / max(rgba.rows(), rgba.cols())
            val out = Mat()
            Imgproc.resize(rgba, out, Size(), f, f, Imgproc.INTER_AREA)
            return out
        }

        fun create(context: Context): Scanner {
            check(OpenCVLoader.initLocal()) { "no se pudo cargar OpenCV" }
            return Scanner(SquareClassifier.fromAssets(context))
        }
    }
}
