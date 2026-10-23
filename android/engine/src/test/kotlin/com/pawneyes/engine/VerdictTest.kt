package com.pawneyes.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictTest {
    private fun cp(v: Int) = EngineLine(1, 20, v, null, listOf("e2e4"))
    private fun mate(n: Int) = EngineLine(1, 20, null, n, listOf("e2e4"))

    @Test
    fun bestMoveIsBest() = assertEquals(Grade.BEST, Verdicts.judge(cp(50), null).grade)

    @Test
    fun gradesByCentipawnLoss() {
        assertEquals(Grade.BEST, Verdicts.judge(cp(50), cp(35)).grade)
        assertEquals(Grade.GOOD, Verdicts.judge(cp(50), cp(0)).grade)
        assertEquals(Grade.INACCURACY, Verdicts.judge(cp(50), cp(-50)).grade)
        assertEquals(Grade.MISTAKE, Verdicts.judge(cp(50), cp(-200)).grade)
        assertEquals(Grade.BLUNDER, Verdicts.judge(cp(50), cp(-400)).grade)
        assertEquals(250, Verdicts.judge(cp(50), cp(-200)).loss)
    }

    @Test
    fun missingAMate() {
        val v = Verdicts.judge(mate(1), cp(80))
        assertEquals(Grade.BLUNDER, v.grade)
        assertTrue(v.missedMate)
        assertEquals(Grade.MISTAKE, Verdicts.judge(mate(2), cp(900)).grade)  // sigue ganando
    }

    @Test
    fun slowerMates() {
        assertEquals(Grade.BEST, Verdicts.judge(mate(3), mate(3)).grade)
        assertEquals(Grade.GOOD, Verdicts.judge(mate(2), mate(4)).grade)
        assertEquals(Grade.INACCURACY, Verdicts.judge(mate(2), mate(7)).grade)
    }

    @Test
    fun walkingIntoMate() {
        assertEquals(Grade.BLUNDER, Verdicts.judge(cp(30), mate(-2)).grade)
        assertTrue(Verdicts.judge(cp(30), mate(-2)).allowsMate)
        // Si ya estaba perdido (mate en contra en cualquier caso), no es «permitir» el mate.
        assertTrue(!Verdicts.judge(mate(-3), mate(-1)).allowsMate)
        assertTrue(Verdicts.score(mate(-5)) > Verdicts.score(mate(-1)))  // aguantar más es menos malo
    }
}
