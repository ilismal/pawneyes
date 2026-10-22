package com.pawneyes.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilityTrackerTest {
    private val square = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
    private fun shifted(d: Float) = FloatArray(8) { square[it] + d }

    @Test
    fun becomesStableAfterEnoughStillFrames() {
        val t = StabilityTracker(requiredFrames = 3)
        t.update(square)
        t.update(shifted(1f))
        assertFalse(t.isStable)
        t.update(shifted(2f))
        assertTrue(t.isStable)
        assertEquals(1f, t.progress)
    }

    @Test
    fun movementRestartsTheStreak() {
        val t = StabilityTracker(requiredFrames = 3, tolerance = 0.03f)
        t.update(square)
        t.update(square)
        t.update(shifted(10f))  // 10 px de 100: se ha movido
        assertFalse(t.isStable)
        assertEquals(1f / 3, t.progress, 1e-6f)
    }

    @Test
    fun losingTheBoardRestartsTheStreak() {
        val t = StabilityTracker(requiredFrames = 2)
        t.update(square)
        t.update(null)
        t.update(square)
        assertFalse(t.isStable)
        t.update(square)
        assertTrue(t.isStable)
    }
}
