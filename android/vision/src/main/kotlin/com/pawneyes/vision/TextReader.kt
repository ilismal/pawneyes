package com.pawneyes.vision

import android.content.Context
import com.googlecode.tesseract.android.TessBaseAPI
import com.pawneyes.chess.Side
import java.io.File
import org.opencv.core.Mat

/** Lo que el diagrama no dice pero el texto de alrededor sí (port de ml/caption/context.py). */
data class TextContext(
    /** Impreso desde el lado de las negras. */
    val flipped: Boolean,
    /** "coordinates" o "pawns": de dónde se ha deducido; null si no está girado. */
    val flippedBy: String?,
    /** Turno según el texto; null si no lo dice. */
    val side: Side?,
)

/** OCR del texto y las coordenadas que rodean al diagrama, con Tesseract (modelo rápido de español). */
class TextReader(dataPath: String) : AutoCloseable {
    private val api = TessBaseAPI().apply {
        check(init(dataPath, LANGUAGE)) { "no se pudo iniciar Tesseract" }
        // El texto enderezado mide siempre lo mismo: así no depende de cómo estime la resolución.
        setVariable("user_defined_dpi", DPI)
        setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
    }

    private class Word(val text: String, val conf: Float, val x: Int, val y: Int, val w: Int, val h: Int, val line: Int)

    private fun words(gray: Mat, whitelist: String = ""): List<Word> {
        if (gray.cols() < 8 || gray.rows() < 8) return emptyList()
        val img = if (gray.isContinuous) gray else gray.clone()
        val bytes = ByteArray(img.cols() * img.rows())
        img.get(0, 0, bytes)
        if (img !== gray) img.release()
        api.setVariable("tessedit_char_whitelist", whitelist)
        api.setImage(bytes, gray.cols(), gray.rows(), 1, gray.cols())
        api.getUTF8Text()  // reconoce la imagen: el iterador solo recorre lo ya reconocido
        val result = mutableListOf<Word>()
        val it = api.resultIterator ?: return result
        it.begin()
        var line = -1
        do {
            if (it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) line++
            val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
            if (text.isEmpty()) continue
            val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
            result += Word(text, it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD), r.left, r.top, r.width(), r.height(), line)
        } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        it.delete()
        api.clear()
        return result
    }

    /** Líneas de texto encima y debajo del tablero, con su distancia en casillas. */
    fun lines(bw: Mat): List<TextLine> = PageText.strips(bw).flatMap { (name, img) ->
        val above = name == "above"
        words(img).groupBy { it.line }.values.map { ws ->
            val text = ws.sortedBy { it.x }.joinToString(" ") { it.text }
            val dist = if (above) (img.rows() - ws.maxOf { it.y + it.h }).toDouble() / PageText.SQUARE
            else ws.minOf { it.y }.toDouble() / PageText.SQUARE
            TextLine(text, above, Math.round(dist * 100) / 100.0)
        }.also { img.release() }
    }

    /** Lo que se lee en cada franja de coordenadas, en orden. */
    fun coordinates(bwWithBoard: Mat): Map<String, String> = PageText.coordinateStrips(bwWithBoard).mapValues { (side, img) ->
        val vertical = side == "left" || side == "right"
        val ws = words(img, if (vertical) Orientation.DIGITS else Orientation.LETTERS)
        img.release()
        ws.sortedBy { if (vertical) it.y else it.x }.filter { it.conf > 30 }.joinToString("") { it.text }
    }

    /**
     * `gray`: foto en gris (cuanta más resolución, mejor se lee); `quad`: esquinas del
     * tablero en ella; `k`: giro de las piezas; `labels`: lectura tal como está impresa.
     */
    fun read(gray: Mat, quad: DoubleArray, k: Int, labels: IntArray): TextContext {
        val page = PageText.rectify(gray, quad, k)
        val withBoard = PageText.clean(page, keepBoard = true)
        val byCoords = Orientation.flippedByCoordinates(coordinates(withBoard))
        withBoard.release()
        val flipped = byCoords ?: Orientation.flippedByPawns(labels)
        val by = when {
            byCoords != null -> "coordinates"
            flipped -> "pawns"
            else -> null
        }
        val bw = PageText.clean(page)
        val side = TurnRules.sideToMove(lines(bw))
        listOf(page, bw).forEach { it.release() }
        return TextContext(flipped, by, side)
    }

    override fun close() = api.recycle()

    companion object {
        const val LANGUAGE = "spa"
        const val DPI = "300"

        /**
         * Tesseract lee el modelo de un fichero: se copia de los assets la primera vez y
         * tras cada actualización de la app (en la APK va comprimido, no se puede mapear).
         */
        fun create(context: Context): TextReader {
            val root = File(context.noBackupFilesDir, "tesseract")
            val file = File(root, "tessdata/$LANGUAGE.traineddata")
            val marker = File(root, "version")
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            if (!file.exists() || !marker.exists() || marker.readText() != installed) {
                file.parentFile!!.mkdirs()
                val tmp = File(file.parentFile, "$LANGUAGE.traineddata.tmp")
                context.assets.open("$LANGUAGE.traineddata").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                check(tmp.renameTo(file)) { "no se pudo guardar el modelo de Tesseract" }
                marker.writeText(installed)
            }
            return TextReader(root.absolutePath)
        }
    }
}
