package com.pawneyes.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovesTest {
    private fun perft(p: Position, depth: Int): Long =
        if (depth == 0) 1 else p.legalMoves().sumOf { perft(p.play(it), depth - 1) }

    // Valores de referencia de https://www.chessprogramming.org/Perft_Results
    @Test
    fun perftStartPosition() {
        assertEquals(20L, perft(Position.START, 1))
        assertEquals(400L, perft(Position.START, 2))
        assertEquals(8902L, perft(Position.START, 3))
        assertEquals(197281L, perft(Position.START, 4))
    }

    /** «Kiwipete»: enroques, capturas al paso y coronaciones a la vez. */
    @Test
    fun perftKiwipete() {
        val p = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48L, perft(p, 1))
        assertEquals(2039L, perft(p, 2))
        assertEquals(97862L, perft(p, 3))
    }

    /** Posición 3: clavadas y jaques descubiertos con captura al paso. */
    @Test
    fun perftPosition3() {
        val p = Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14L, perft(p, 1))
        assertEquals(191L, perft(p, 2))
        assertEquals(2812L, perft(p, 3))
        assertEquals(43238L, perft(p, 4))
    }

    /** Posición 4: coronaciones y enroques con jaques. */
    @Test
    fun perftPosition4() {
        val p = Position.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        assertEquals(6L, perft(p, 1))
        assertEquals(264L, perft(p, 2))
        assertEquals(9467L, perft(p, 3))
    }

    @Test
    fun sanInSpanish() {
        val p = Position.START
        assertEquals("e4", p.toSan(Move.fromUci("e2e4")!!))
        assertEquals("Cf3", p.toSan(Move.fromUci("g1f3")!!))
        assertEquals("Nf3", p.toSan(Move.fromUci("g1f3")!!, PieceLetters.ENGLISH))
    }

    @Test
    fun sanDisambiguationCapturesAndChecks() {
        // Los dos caballos pueden ir a d3.
        val p = Position.fromFen("4k3/8/8/8/8/8/1N3N2/R3K2R w KQ - 0 1")
        assertEquals("Cbd3", p.toSan(Move.fromUci("b2d3")!!))
        assertEquals("O-O", p.toSan(Move.fromUci("e1g1")!!))
        assertEquals("O-O-O", p.toSan(Move.fromUci("e1c1")!!))
        // Con el rey en e2, las dos torres de la 1ª fila llegan a d1.
        val q = Position.fromFen("4k3/8/8/8/8/8/4K3/R6R w - - 0 1")
        assertEquals("Tad1", q.toSan(Move.fromUci("a1d1")!!))
        assertEquals("Td1", Position.fromFen("4k3/8/8/8/8/8/8/R3K2R w - - 0 1").toSan(Move.fromUci("a1d1")!!))
        assertEquals("Ta8+", q.toSan(Move.fromUci("a1a8")!!))
        val mate = Position.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
        assertEquals("Ta8#", mate.toSan(Move.fromUci("a1a8")!!))
        assertTrue(mate.play(Move.fromUci("a1a8")!!).isCheckmate())
    }

    @Test
    fun checkmateAndStalemate() {
        val mated = Position.fromFen("r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4")
        assertTrue(mated.isCheckmate())
        assertTrue(!mated.isStalemate())
        val stalemate = Position.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertTrue(stalemate.isStalemate())
        assertTrue(!stalemate.isCheckmate())
        assertTrue(!Position.START.isCheckmate() && !Position.START.isStalemate())
    }

    @Test
    fun sanPawnsPromotionAndEnPassant() {
        val p = Position.fromFen("4k3/1P6/8/3pP3/8/8/8/4K3 w - d6 0 1")
        assertEquals("exd6", p.toSan(Move.fromUci("e5d6")!!))
        assertEquals("b8=D+", p.toSan(Move.fromUci("b7b8q")!!))
        assertEquals("b8=C", p.toSan(Move.fromUci("b7b8n")!!))
        assertEquals(null, p.play(Move.fromUci("e5d6")!!)[Square.parse("d5")!!])
    }

    @Test
    fun lineWithMoveNumbers() {
        val p = Position.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        assertEquals("1... e5 2. Cf3 Cc6", p.lineToSan(listOf("e7e5", "g1f3", "b8c6")))
        assertEquals("1. e4 e5", Position.START.lineToSan(listOf("e2e4", "e7e5", "a1a5")))  // se corta en la ilegal
    }
}
