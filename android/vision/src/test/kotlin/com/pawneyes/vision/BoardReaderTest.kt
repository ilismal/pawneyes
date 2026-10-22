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
            assertEquals(name, b.getString("placement"), reading.position().placement())
        }
    }
}
