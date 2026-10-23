package com.pawneyes.maia

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pawneyes.chess.Move
import com.pawneyes.chess.Position
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Las probabilidades de Maia en el dispositivo coinciden con las de Python (fixtures-publicos/maia.json). */
@RunWith(AndroidJUnit4::class)
class MaiaParityTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun policyMatchesPython() {
        val fx = JSONObject(testContext.assets.open("maia.json").bufferedReader().readText())
        val positions = fx.getJSONArray("positions")
        var worst = 0.0
        var nanos = 0L
        var calls = 0
        for (level in MaiaPlayer.LEVELS) {
            MaiaPlayer.load(context, level).use { maia ->
                for (i in 0 until positions.length()) {
                    val pos = positions.getJSONObject(i)
                    val p = Position.fromFen(pos.getString("fen"))
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val mine = maia.policy(p).mapKeys { it.key.uci }
                    nanos += SystemClock.elapsedRealtimeNanos() - t0
                    calls++
                    val expected = pos.getJSONObject("policy").getJSONObject("$level")
                    for (uci in expected.keys()) {
                        val d = abs(mine.getValue(uci) - expected.getDouble(uci))
                        worst = maxOf(worst, d)
                    }
                    // La jugada elegida sin temperatura es la más probable de Python.
                    assertEquals(expected.keys().next(), maia.choose(p)!!.uci)
                }
            }
        }
        Log.i("MaiaParity", "diferencia máxima de probabilidad: $worst; ${nanos / calls / 1000} µs por jugada")
        assertTrue("diferencia máxima $worst", worst < 1e-3)
    }

    @Test
    fun temperatureVariesButStaysReasonable() {
        MaiaPlayer.load(context, 1500).use { maia ->
            val p = Position.START
            val picks = (0 until 40).map { maia.choose(p, temperature = 1.0, random = kotlin.random.Random(it))!!.uci }
            Log.i("MaiaParity", "aperturas con temperatura 1: ${picks.groupingBy { it }.eachCount()}")
            assertTrue(picks.toSet().size > 1)           // no siempre la misma
            assertTrue(picks.all { Move.fromUci(it) != null })
        }
    }
}
