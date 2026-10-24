package com.pawneyes.vision

import android.content.Context
import android.graphics.Bitmap
import com.pawneyes.chess.Piece
import com.pawneyes.chess.Position
import kotlin.math.hypot
import kotlin.math.max
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Resultado de escanear una foto. */
class ScanResult(
    /** Posición leída, con blancas abajo (la rotación y, si hace falta, el giro de 180° ya aplicados). */
    val position: Position,
    /** Casillas con confianza baja, para que el usuario las revise. */
    val doubtful: Set<Int>,
    /** Tablero enderezado y girado como se ha leído (tal como está impreso), para compararlo con la lectura. */
    val board: Bitmap,
    val reading: BoardReading,
    val detection: BoardDetector.Detection,
    /** Orientación y turno según el texto de la página; null si no se ha leído el texto. */
    val context: TextContext? = null,
)

/**
 * Pipeline completo, igual que en ml/: foto -> detector -> tablero 512² ->
 * casillas en las 4 rotaciones -> modelo -> rotación + reglas.
 */
class Scanner(private val classifier: SquareClassifier, private val text: TextReader? = null) : AutoCloseable {

    /** `null` si no se encuentra ningún tablero en la foto. */
    fun scan(photo: Bitmap): ScanResult? {
        val rgba = Mat()
        Utils.bitmapToMat(photo, rgba)
        return scan(rgba).also { rgba.release() }
    }

    /** `rgba`: foto RGBA (CV_8UC4) de cualquier tamaño. El mejor tablero, o null si no hay. */
    fun scan(rgba: Mat): ScanResult? {
        val photo = resizeToWorkSize(rgba)
        val detection = BoardDetector.detect(photo)
        val result = detection?.let { read(rgba, photo, it) }
        photo.release()
        return result
    }

    /**
     * Todos los diagramas de la foto (#10), en orden de lectura: de arriba abajo y, a la
     * misma altura, de izquierda a derecha. Vacía si no hay ninguno.
     */
    fun scanAll(rgba: Mat): List<ScanResult> {
        val photo = resizeToWorkSize(rgba)
        val results = readingOrder(BoardDetector.detectAll(photo)).map { read(rgba, photo, it) }
        photo.release()
        return results
    }

    fun scanAll(photo: Bitmap): List<ScanResult> {
        val rgba = Mat()
        Utils.bitmapToMat(photo, rgba)
        return scanAll(rgba).also { rgba.release() }
    }

    private fun read(rgba: Mat, photo: Mat, detection: BoardDetector.Detection): ScanResult {
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
        listOf(color, board, upright).forEach { it.release() }
        val context = text?.let { reader ->
            // El texto se lee en la foto original: con la reducida se pierden letras.
            val f = VisionSpec.PHOTO_PX.toDouble() / max(rgba.rows(), rgba.cols())
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val quad = DoubleArray(8) { detection.quad[it] / f }
            reader.read(gray, quad, reading.rotation, reading.labels).also { gray.release() }
        }
        if (context?.flipped == true) {
            // Impreso desde las negras: la casilla impresa i es la 63 - i.
            val position = Position(reading.labels.reversed().map { Piece.fromFen(VisionSpec.CLASSES[it]) })
            val doubtful = reading.doubtful.mapTo(mutableSetOf()) { 63 - it }
            return ScanResult(position, doubtful, bitmap, reading, detection, context)
        }
        return ScanResult(reading.position(), reading.doubtful, bitmap, reading, detection, context)
    }

    override fun close() {
        classifier.close()
        text?.close()
    }

    companion object {
        /** La foto se lleva a PHOTO_PX de lado largo, como al generar los fixtures. */
        fun resizeToWorkSize(rgba: Mat): Mat {
            val f = VisionSpec.PHOTO_PX.toDouble() / max(rgba.rows(), rgba.cols())
            val out = Mat()
            Imgproc.resize(rgba, out, Size(), f, f, Imgproc.INTER_AREA)
            return out
        }

        /** De arriba abajo; dos tableros cuyo centro está a menos de medio tablero de altura van en la misma fila. */
        fun readingOrder(found: List<BoardDetector.Detection>): List<BoardDetector.Detection> {
            fun cx(d: BoardDetector.Detection) = (0 until 4).sumOf { d.quad[2 * it] } / 4
            fun cy(d: BoardDetector.Detection) = (0 until 4).sumOf { d.quad[2 * it + 1] } / 4
            fun side(d: BoardDetector.Detection) = hypot(d.quad[2] - d.quad[0], d.quad[3] - d.quad[1])
            val rows = mutableListOf<MutableList<BoardDetector.Detection>>()
            for (d in found.sortedBy { cy(it) }) {
                val row = rows.lastOrNull()
                if (row != null && cy(d) - cy(row.first()) < side(row.first()) / 2) row += d else rows += mutableListOf(d)
            }
            return rows.flatMap { row -> row.sortedBy { cx(it) } }
        }

        fun create(context: Context): Scanner {
            check(OpenCVLoader.initLocal()) { "no se pudo cargar OpenCV" }
            return Scanner(SquareClassifier.fromAssets(context), TextReader.create(context))
        }
    }
}
