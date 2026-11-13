package com.pawneyes.app

import com.pawneyes.chess.Side
import com.pawneyes.engine.EngineLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalBarTest {
    private fun line(cp: Int? = null, mate: Int? = null) = EngineLine(1, 20, cp, mate, listOf("e2e4"))

    @Test
    fun equalIsHalf() = assertEquals(0.5f, line(cp = 0).whiteShare(Side.WHITE), 1e-6f)

    @Test
    fun scoreIsFromTheSideToMove() {
        // +1 para el que mueve: ventaja blanca si mueven blancas, negra si mueven negras.
        val forWhite = line(cp = 100).whiteShare(Side.WHITE)
        assertEquals(1f - forWhite, line(cp = 100).whiteShare(Side.BLACK), 1e-6f)
        assertEquals(0.59f, forWhite, 0.01f)  // un peón se nota
    }

    @Test
    fun bigAdvantagesStayInsideAndMateFills() {
        val ten = line(cp = 1000).whiteShare(Side.WHITE)
        assertTrue(ten > 0.95f && ten < 1f)
        assertEquals(1f, line(mate = 3).whiteShare(Side.WHITE))
        assertEquals(0f, line(mate = 3).whiteShare(Side.BLACK))
        assertEquals(0f, line(mate = -2).whiteShare(Side.WHITE))
    }
}
