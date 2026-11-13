package com.pawneyes.app

import com.pawneyes.chess.Position
import com.pawneyes.chess.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTest {
    @Test
    fun spendsAndAddsIncrement() {
        var c = Clock.of(TimeControl(5, 3))
        assertEquals(300_000L, c[Side.WHITE])
        c = c.spend(Side.WHITE, 10_000).moved(Side.WHITE)
        assertEquals(293_000L, c[Side.WHITE])
        assertEquals(300_000L, c[Side.BLACK])
        assertNull(c.flagged)
    }

    @Test
    fun flagsAtZeroWithoutGoingNegative() {
        val c = Clock.of(TimeControl(5, 3)).spend(Side.BLACK, 400_000)
        assertEquals(0L, c[Side.BLACK])
        assertEquals(Side.BLACK, c.flagged)
    }

    @Test
    fun timeoutAgainstALoneKingIsADraw() {
        val loneBlackKing = Position.fromFen("7k/8/8/8/8/8/8/K6Q w - - 0 1")
        assertTrue(Clock.timeoutIsDraw(loneBlackKing, Side.WHITE))   // a las blancas se les acaba: negras no pueden dar mate
        assertFalse(Clock.timeoutIsDraw(loneBlackKing, Side.BLACK))  // a las negras se les acaba: pierden
    }

    @Test
    fun formatsMinutesAndTenths() {
        assertEquals("5:00", Clock.format(300_000))
        assertEquals("4:59", Clock.format(298_500))
        assertEquals("0:10", Clock.format(10_000))
        assertEquals("9.7", Clock.format(9_750))
        assertEquals("0.0", Clock.format(0))
    }
}
