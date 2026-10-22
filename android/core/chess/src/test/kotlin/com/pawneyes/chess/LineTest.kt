package com.pawneyes.chess

import org.junit.Assert.assertEquals
import org.junit.Test

class LineTest {
    @Test
    fun walksThroughPositions() {
        val line = Line(Position.START, listOf("e2e4", "e7e5", "g1f3", "zzzz", "b8c6"))
        assertEquals(3, line.size)  // se corta en la jugada no válida
        assertEquals(listOf("1. e4", "1... e5", "2. Cf3"), line.san)
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2", line.positions[3].toFen())
        assertEquals(Position.START, line.positions[0])
    }
}
