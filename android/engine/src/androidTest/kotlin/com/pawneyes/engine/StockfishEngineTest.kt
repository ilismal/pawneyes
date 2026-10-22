package com.pawneyes.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pawneyes.chess.Move
import com.pawneyes.chess.Position
import com.pawneyes.chess.legalMoves
import com.pawneyes.chess.lineToSan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StockfishEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun StockfishEngine.until(position: Position, ok: (Analysis) -> Boolean): Analysis =
        withTimeout(30_000) { analysis.first { it != null && it.position == position && ok(it) }!! }

    @Test
    fun analysesAndFindsMate() = runBlocking {
        assertTrue("falta libstockfish.so", StockfishEngine.isAvailable(context))
        StockfishEngine.start(context, lines = 3).use { engine ->
            // Posición inicial: 3 líneas legales a profundidad 15.
            engine.analyze(Position.START)
            val a = engine.until(Position.START) { it.depth >= 15 && it.lines.size == 3 }
            for (line in a.lines) {
                assertTrue(Move.fromUci(line.pv.first()) in Position.START.legalMoves())
                assertTrue(line.cp != null && kotlin.math.abs(line.cp!!) < 100)
            }
            android.util.Log.i("StockfishTest", "inicial: " + a.lines.joinToString(" | ") {
                "${it.cp} ${Position.START.lineToSan(it.pv.take(6))}"
            })

            // Mate en 1 (Ta8#): se cambia de posición en plena búsqueda.
            val mate = Position.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
            engine.analyze(mate)
            val m = engine.until(mate) { it.lines.firstOrNull()?.mate != null }
            assertEquals(1, m.lines.first().mate)
            assertEquals("a1a8", m.lines.first().pv.first())
            assertEquals(mate, engine.analysis.value!!.position)
            engine.stop()
        }
    }
}
