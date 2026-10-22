package com.pawneyes.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {
    private fun issues(fen: String) = Position.fromFen(fen).issues()

    @Test
    fun startPositionIsLegal() {
        assertTrue(Position.START.isLegal())
    }

    @Test
    fun kingsAreCounted() {
        assertEquals(
            listOf(Issue.KingCount(Side.WHITE, 2), Issue.KingCount(Side.BLACK, 0)),
            issues("4K3/8/5K2/8/8/8/8/8"),
        )
    }

    @Test
    fun pawnsOnBackRanks() {
        assertEquals(listOf(Issue.PawnOnBackRank(Square.parse("a8")!!)), issues("P3k3/8/8/8/8/8/8/4K3"))
    }

    @Test
    fun tooManyPawns() {
        assertEquals(
            listOf(Issue.TooManyPawns(Side.WHITE, 9)),
            issues("4k3/8/8/1P6/8/8/PPPPPPPP/4K3"),
        )
    }

    @Test
    fun sideNotToMoveMustNotBeInCheck() {
        // Torre blanca en e1 da jaque al rey negro de e8, y les toca a las blancas.
        assertEquals(listOf(Issue.OpponentInCheck(Side.BLACK)), issues("4k3/8/8/8/8/8/8/4R1K1 w"))
        // Con turno de las negras, es un jaque normal.
        assertTrue(Position.fromFen("4k3/8/8/8/8/8/8/4R1K1 b").isLegal())
    }

    @Test
    fun blockedLinesDoNotAttack() {
        assertTrue(Position.fromFen("4k3/4p3/8/8/8/8/8/4R1K1 w").isLegal())
    }

    @Test
    fun pawnsAttackDiagonally() {
        val p = Position.fromFen("4k3/8/8/8/8/8/3P4/4K3")
        assertTrue(p.isAttacked(Square.parse("e3")!!, Side.WHITE))
        assertFalse(p.isAttacked(Square.parse("d3")!!, Side.WHITE))
    }

    @Test
    fun castlingNeedsKingAndRookAtHome() {
        assertEquals(
            listOf(Issue.CastlingWithoutPieces(Castling.WHITE_KINGSIDE)),
            issues("4k3/8/8/8/8/8/8/4K3 w K"),
        )
        assertEquals(setOf(Castling.WHITE_KINGSIDE, Castling.WHITE_QUEENSIDE,
            Castling.BLACK_KINGSIDE, Castling.BLACK_QUEENSIDE), Position.START.plausibleCastling())
    }

    @Test
    fun enPassantNeedsThePawnThatJustMoved() {
        assertTrue(Position.fromFen("4k3/8/8/4pP2/8/8/8/4K3 w - e6").isLegal())
        assertEquals(
            listOf(Issue.InvalidEnPassant(Square.parse("e6")!!)),
            issues("4k3/8/8/5P2/8/8/8/4K3 w - e6"),
        )
    }
}
