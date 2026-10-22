package com.pawneyes.camera

import kotlin.math.hypot

/**
 * Decide cuándo el tablero lleva quieto lo bastante para hacer la foto sola.
 *
 * Cada fotograma aporta las esquinas detectadas (x0, y0, …, x3, y3) o `null`.
 * Si ninguna esquina se ha movido más de `tolerance` × lado medio respecto al
 * fotograma anterior, la racha crece; si se mueve o desaparece, vuelve a cero.
 */
class StabilityTracker(
    private val requiredFrames: Int = 4,
    private val tolerance: Float = 0.03f,
) {
    private var last: FloatArray? = null
    private var streak = 0

    /** 0..1: cuánto falta para considerarlo quieto. */
    val progress: Float get() = (streak.toFloat() / requiredFrames).coerceAtMost(1f)

    val isStable: Boolean get() = streak >= requiredFrames

    fun update(quad: FloatArray?) {
        val previous = last
        last = quad
        streak = when {
            quad == null -> 0
            previous == null -> 1
            maxCornerShift(previous, quad) <= tolerance * meanSide(quad) -> streak + 1
            else -> 1
        }
    }

    fun reset() {
        last = null
        streak = 0
    }

    private fun maxCornerShift(a: FloatArray, b: FloatArray): Float =
        (0 until 4).maxOf { hypot(a[2 * it] - b[2 * it], a[2 * it + 1] - b[2 * it + 1]) }

    private fun meanSide(q: FloatArray): Float = (0 until 4).map {
        val j = (it + 1) % 4
        hypot(q[2 * it] - q[2 * j], q[2 * it + 1] - q[2 * j + 1])
    }.average().toFloat()
}
