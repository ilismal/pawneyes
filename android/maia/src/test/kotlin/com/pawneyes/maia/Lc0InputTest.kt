package com.pawneyes.maia

import com.pawneyes.chess.Move
import com.pawneyes.chess.Position
import com.pawneyes.chess.legalMoves
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La codificación de Kotlin da los mismos planos que ml/maia/encode.py (fixtures-publicos/maia.json). */
class Lc0InputTest {
    private val fixtures = JSONObject(File("../../fixtures-publicos/maia.json").readText())

    @Test
    fun planesMatchPython() {
        val positions = fixtures.getJSONArray("positions")
        assertTrue(positions.length() >= 8)
        for (i in 0 until positions.length()) {
            val fx = positions.getJSONObject(i)
            val p = Position.fromFen(fx.getString("fen"))
            val x = Lc0Input.planes(p)
            val expected = mutableSetOf<Int>()
            val nz = fx.getJSONArray("piece_planes")
            for (k in 0 until nz.length()) {
                val e = nz.getJSONArray(k)
                expected += e.getInt(0) * 64 + e.getInt(1) * 8 + e.getInt(2)
            }
            val actual = (0 until 104 * 64).filterTo(mutableSetOf()) { x[it] != 0f }
            assertEquals(fx.getString("fen"), expected, actual)
            val aux = fx.getJSONArray("aux")
            for (a in 0 until 8) assertEquals(fx.getString("fen"), aux.getDouble(a).toFloat(), x[(104 + a) * 64], 0f)
        }
    }

    @Test
    fun policyIndexCoversEveryLegalMove() {
        // La tabla de lc0 cubre todas las jugadas legales, también las de las negras y las coronaciones.
        for (fen in listOf("8/1P6/8/8/8/8/6k1/K7 w - - 0 1", "4k3/8/8/8/8/8/1p6/K7 b - - 0 1",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R b KQkq - 0 1")) {
            val p = Position.fromFen(fen)
            val idx = p.legalMoves().map { Lc0Input.policyIndex(p, it) }
            assertEquals(fen, idx.size, idx.toSet().size)  // sin colisiones
        }
        // Coronar a caballo va sin sufijo; a dama, con «q».
        val p = Position.fromFen("8/1P6/8/8/8/8/6k1/K7 w - - 0 1")
        assertEquals(PolicyIndex.of("b7b8"), Lc0Input.policyIndex(p, Move.fromUci("b7b8n")!!))
        assertEquals(PolicyIndex.of("b7b8q"), Lc0Input.policyIndex(p, Move.fromUci("b7b8q")!!))
    }
}
