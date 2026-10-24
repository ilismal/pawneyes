package com.pawneyes.vision

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Con las salidas del modelo de los fixtures, Kotlin elige y corrige igual que Python. */
class BoardReaderTest {
    /** Fixtures públicos (sintéticos, siempre) y privados (fotos propias, si existen). */
    private val boards: List<JSONObject> = listOf("../../fixtures-publicos", "../../fixtures")
        .map { File(it, "manifest.json") }.filter { it.exists() }
        .flatMap { m -> JSONObject(m.readText()).getJSONArray("boards").let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }

    private fun logits(board: JSONObject): List<Array<FloatArray>> {
        val all = board.getJSONArray("logits")
        return (0 until 4).map { k ->
            val rot = all.getJSONArray(k)
            Array(64) { sq -> rot.getJSONArray(sq).let { c: JSONArray -> FloatArray(13) { c.getDouble(it).toFloat() } } }
        }
    }

    @Test
    fun matchesPythonOnEveryFixture() {
        assertTrue("faltan fixtures-publicos/", boards.isNotEmpty())
        for (b in boards) {
            val reading = BoardReader.read(logits(b))
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
}
