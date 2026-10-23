package com.pawneyes.vision

import com.pawneyes.chess.Side
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las reglas de turno y orientación dan lo mismo que en Python (fixtures-publicos/caption.json). */
class CaptionTest {
    private val cases = JSONObject(File("../../fixtures-publicos/caption.json").readText())

    private fun side(s: Any?): Side? = when (s) {
        "w" -> Side.WHITE
        "b" -> Side.BLACK
        else -> null
    }

    @Test
    fun turnMatchesPython() {
        val all = cases.getJSONArray("turn")
        assertTrue(all.length() > 20)
        for (i in 0 until all.length()) {
            val c = all.getJSONObject(i)
            val ls = c.getJSONArray("lines")
            val lines = (0 until ls.length()).map { ls.getJSONObject(it) }
                .map { TextLine(it.getString("text"), it.getBoolean("above"), it.getDouble("distance")) }
            assertEquals(lines.joinToString(" / ") { it.text }, side(c.opt("side")), TurnRules.sideToMove(lines))
        }
    }

    @Test
    fun coordinatesMatchPython() {
        val all = cases.getJSONArray("coordinates")
        for (i in 0 until all.length()) {
            val c = all.getJSONObject(i)
            val f = c.getJSONObject("found")
            val found = f.keys().asSequence().associateWith { f.getString(it) }
            val want = if (c.isNull("flipped")) null else c.getBoolean("flipped")
            assertEquals(found.toString(), want, Orientation.flippedByCoordinates(found))
        }
    }

    @Test
    fun pawnsAboveMeanBlackIsAtTheBottom() {
        fun labels(placement: String) = com.pawneyes.chess.Position.fromFen("$placement w - - 0 1").board
            .map { p -> p?.let { VisionSpec.CLASSES.indexOf(it.fen) } ?: 0 }.toIntArray()
        val normal = labels("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R")
        assertFalse(Orientation.flippedByPawns(normal))
        assertTrue(Orientation.flippedByPawns(normal.reversedArray()))
        // Sin peones de los dos colores no se decide nada.
        assertFalse(Orientation.flippedByPawns(labels("8/8/4k3/8/8/4K3/4P3/8").reversedArray()))
    }
}
