package com.pawneyes.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PgnTest {
    @Test
    fun positionOnly() {
        val p = Position.fromFen("4k3/8/8/8/8/8/8/R3K3 w Q - 0 1")
        val pgn = Pgn.of(p, date = "2026.10.22")
        assertTrue(pgn.contains("[SetUp \"1\"]\n[FEN \"4k3/8/8/8/8/8/8/R3K3 w Q - 0 1\"]\n"))
        assertTrue(pgn.contains("[Date \"2026.10.22\"]"))
        assertTrue(pgn.endsWith("\n\n*\n"))
    }

    @Test
    fun lineInEnglishWithCommentAndBlackToMove() {
        val p = Position.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        val moves = listOf("e7e5", "g1f3", "b8c6").map { Move.fromUci(it)!! }
        val pgn = Pgn.of(p, moves, comment = "Stockfish: +0,30")
        assertTrue(pgn.endsWith("\n{ Stockfish: +0,30 } 1... e5 2. Nf3 Nc6 *\n"))
    }

    @Test
    fun longLinesWrapAt80() {
        val moves = "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6 e1g1 f8e7 f1e1 b7b5 a4b3 d7d6 c2c3 e8g8 h2h3 c6b8 d2d4 b8d7"
            .split(" ").map { Move.fromUci(it)!! }
        val pgn = Pgn.of(Position.START, moves)
        val movetext = pgn.substringAfter("\n\n").trimEnd()
        assertTrue(movetext.lines().size > 1)
        assertTrue(movetext.lines().all { it.length <= 80 })
        assertTrue(movetext.startsWith("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7"))
        assertEquals("*", movetext.split(Regex("\\s+")).last())
    }
}
