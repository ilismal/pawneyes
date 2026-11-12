package com.pawneyes.app

import androidx.compose.runtime.saveable.SaverScope
import com.pawneyes.chess.Line
import com.pawneyes.chess.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExplorationTest {
    private val scope = SaverScope { true }

    private fun roundTrip(e: Exploration?): Exploration? {
        val saved = with(Exploration.Saver) { scope.save(e) } ?: return null
        return Exploration.Saver.restore(saved)
    }

    @Test
    fun survivesSaveAndRestore() {
        val start = Position.fromFen("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3")
        val e = Exploration(Line(start, listOf("f1b5", "a7a6", "b5a4", "g8f6")), 2)
        val back = roundTrip(e)!!
        assertEquals(start.toFen(), back.line.start.toFen())
        assertEquals(e.line.moves.map { it.uci }, back.line.moves.map { it.uci })
        assertEquals(2, back.index)
        assertEquals(e.position.toFen(), back.position.toFen())
    }

    @Test
    fun noExplorationStaysNull() {
        assertNull(roundTrip(null))
    }
}
