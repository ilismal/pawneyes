package com.pawneyes.vision

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Con las salidas del modelo de los fixtures, Kotlin elige y corrige igual que Python. */
class BoardReaderTest {
    /** Fixtures públicos (sintéticos, siempre) y privados (fotos propias, si existen), con su carpeta. */
    private val boards: List<Pair<File, JSONObject>> = listOf("../../fixtures-publicos", "../../fixtures")
        .map { File(it) }.filter { File(it, "manifest.json").exists() }
        .flatMap { dir ->
            JSONObject(File(dir, "manifest.json").readText()).getJSONArray("boards")
                .let { a -> (0 until a.length()).map { dir to a.getJSONObject(it) } }
        }

    private fun logits(board: JSONObject): List<Array<FloatArray>> {
        val all = board.getJSONArray("logits")
        return (0 until 4).map { k ->
            val rot = all.getJSONArray(k)
            Array(64) { sq -> rot.getJSONArray(sq).let { c: JSONArray -> FloatArray(13) { c.getDouble(it).toFloat() } } }
        }
    }

    /**
     * El modelo, sustituido por lo que dio en Python: los logits de cada casilla en cada giro,
     * buscados por su contenido. Cuenta las llamadas, para comprobar que son 2 o 3 (#13).
     */
    private class FixtureModel(squares: ByteArray, logits: List<Array<FloatArray>>) {
        private val table = HashMap<String, FloatArray>()
        var calls = 0

        init {
            for (k in 0 until 4) {
                val turned = SquareRotation.rotateSquares(squares, k)
                for (sq in 0 until 64) table[key(turned, sq)] = logits[k][sq]
            }
        }

        private fun key(bytes: ByteArray, sq: Int): String {
            val cell = VisionSpec.INPUT_PX * VisionSpec.INPUT_PX
            return java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(sq * cell, (sq + 1) * cell))
        }

        fun classify(bytes: ByteArray): Array<FloatArray> {
            calls++
            val n = bytes.size / (VisionSpec.INPUT_PX * VisionSpec.INPUT_PX)
            return Array(n) { table.getValue(key(bytes, it)) }
        }
    }

    @Test
    fun matchesPythonOnEveryFixture() {
        assertTrue("faltan fixtures-publicos/", boards.isNotEmpty())
        for ((dir, b) in boards) {
            val model = FixtureModel(File(dir, b.getString("squares")).readBytes(), logits(b))
            val reading = BoardReader.read(File(dir, b.getString("squares")).readBytes(), model::classify)
            assertEquals(b.getString("name"), if (reading.rotation == 0) 2 else 3, model.calls)
            val name = b.getString("name")
            assertEquals(name, b.getInt("rotation"), reading.rotation)
            val raw = com.pawneyes.chess.Position(reading.rawLabels.map {
                com.pawneyes.chess.Piece.fromFen(VisionSpec.CLASSES[it])
            }).placement()
            assertEquals(name, b.getString("placement_raw"), raw)
            // Si el texto dice que está impreso desde las negras, Python ya lo gira.
            val labels = if (b.optBoolean("flipped")) reading.labels.reversedArray() else reading.labels
            val position = com.pawneyes.chess.Position(labels.map { com.pawneyes.chess.Piece.fromFen(VisionSpec.CLASSES[it]) })
            assertEquals(name, b.getString("placement"), position.placement())
            // #21: en cada casilla, primero lo leído y después las demás opciones, ordenadas.
            for (sq in 0 until 64) {
                val alt = reading.alternatives[sq]
                assertEquals(name, reading.labels[sq], alt.first().first)
                assertTrue(name, alt.size <= BoardReader.MAX_ALTERNATIVES)
                val others = alt.drop(1).map { it.second }
                assertEquals(name, others.sortedDescending(), others)
                assertTrue(name, others.all { it >= BoardReader.MIN_ALTERNATIVE })
            }
        }
    }

    @Test
    fun rotatesSquaresLikeNumpy() {
        // np.rot90 gira en sentido antihorario: la esquina superior izquierda (a8) pasa a ser la
        // superior derecha de antes (h8), y dentro de cada casilla, igual con los píxeles.
        val px = VisionSpec.INPUT_PX
        val squares = ByteArray(64 * px * px) { (it * 31 + it / 7).toByte() }
        val turned = SquareRotation.rotateSquares(squares, 1)
        assertEquals(squares[7 * px * px + (px - 1)], turned[0])
        assertEquals(squares[7 * px * px + 0], turned[(px - 1) * px])  // abajo a la izquierda
        var back = squares
        repeat(4) { back = SquareRotation.rotateSquares(back, 1) }
        assertTrue(back.contentEquals(squares))
        assertTrue(SquareRotation.rotateSquares(SquareRotation.rotateSquares(squares, 1), 3).contentEquals(squares))
    }
}
