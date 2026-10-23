package com.pawneyes.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameTest {
    private fun m(uci: String) = Move.fromUci(uci)!!

    @Test
    fun playUndoAndSan() {
        val g = Game(Position.START).play(m("e2e4")).play(m("e7e5")).play(m("g1f3"))
        assertEquals(listOf("1. e4", "e5", "2. Cf3"), g.san())
        assertEquals(Position.START.play(m("e2e4")), g.undo(2).position)
        assertNull(g.result)
    }

    @Test
    fun checkmateAndStalemate() {
        val mate = Game(Position.fromFen("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"))
            .play(m("h5f7"))
        assertEquals(GameResult.Checkmate(Side.WHITE), mate.result)
        val stale = Game(Position.fromFen("7k/8/5QK1/8/8/8/8/8 w - - 0 1")).play(m("f6f7"))
        assertEquals(GameResult.Stalemate, stale.result)
    }

    @Test
    fun drawsByRule() {
        assertEquals(GameResult.InsufficientMaterial, Game(Position.fromFen("8/8/3k4/8/8/3NK3/8/8 w - - 0 1")).result)
        assertEquals(GameResult.InsufficientMaterial, Game(Position.fromFen("8/2b5/3k4/8/8/4K3/3B4/8 w - - 0 1")).result) // mismo color
        assertNull(Game(Position.fromFen("8/8/3k4/8/8/4K3/3B4/1b6 w - - 0 1")).result)  // alfiles de distinto color (d2 oscura, b1 clara)
        assertEquals(GameResult.FiftyMoves, Game(Position.fromFen("8/8/3k4/8/8/4K3/3R4/8 w - - 100 80")).result)
        // Caballos adelante y atrás: la posición inicial se repite tres veces.
        var g = Game(Position.START)
        repeat(2) { for (u in listOf("g1f3", "g8f6", "f3g1", "f6g8")) g = g.play(m(u)) }
        assertEquals(GameResult.Repetition, g.result)
    }
}
