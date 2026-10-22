package com.pawneyes.chess

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FenTest {
    @Test
    fun startPositionRoundTrips() {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        assertEquals(fen, Position.fromFen(fen).toFen())
        assertEquals(Position.START, Position.fromFen(fen))
    }

    @Test
    fun placementOnlyGetsDefaults() {
        val p = Position.fromFen("8/8/8/2k5/8/4K3/3R1P1r/8")
        assertEquals("8/8/8/2k5/8/4K3/3R1P1r/8 w - - 0 1", p.toFen())
    }

    @Test
    fun squaresFollowVisionOrder() {
        assertEquals(0, Square.parse("a8"))
        assertEquals(7, Square.parse("h8"))
        assertEquals(56, Square.parse("a1"))
        assertEquals(63, Square.parse("h1"))
        assertEquals("e4", Square.name(Square.parse("e4")!!))
        assertEquals(Piece(Side.WHITE, PieceType.KING), Position.START[Square.parse("e1")!!])
        assertNull(Position.START[Square.parse("e4")!!])
    }

    @Test
    fun castlingIsWrittenInCanonicalOrder() {
        val p = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R b qkQK - 3 20")
        assertEquals("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 3 20", p.toFen())
    }

    @Test
    fun enPassantIsParsed() {
        val p = Position.fromFen("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2")
        assertEquals(Square.parse("e6"), p.enPassant)
    }

    @Test
    fun malformedFensAreRejected() {
        listOf(
            "",
            "8/8/8/8/8/8/8",                 // 7 filas
            "9/8/8/8/8/8/8/8",               // fila de 9
            "7/8/8/8/8/8/8/8",               // fila de 7
            "8/8/8/8/8/8/8/7x",              // pieza desconocida
            "8/8/8/8/8/8/8/8 x",             // turno
            "8/8/8/8/8/8/8/8 w KK",          // enroque repetido
            "8/8/8/8/8/8/8/8 w - e4",        // al paso en la 4ª
            "8/8/8/8/8/8/8/8 w - - -1 1",
            "8/8/8/8/8/8/8/8 w - - 0 0",
        ).forEach { fen -> assertThrows(FenException::class.java) { Position.fromFen(fen) } }
    }

    /**
     * Las posiciones de los fixtures (generadas en Python) se leen y reescriben igual.
     * Siempre están los públicos (sintéticos); los privados, solo si existen en local.
     */
    @Test
    fun fixturePlacementsRoundTrip() {
        val manifests = listOf("../../../fixtures-publicos", "../../../fixtures")
            .map { File(it, "manifest.json") }.filter { it.exists() }
        assertTrue("faltan fixtures-publicos/", manifests.isNotEmpty())
        val placements = manifests.flatMap { m ->
            Regex("\"(?:truth|placement)\": \"([^\"]+)\"").findAll(m.readText()).map { it.groupValues[1] }.toList()
        }
        assertTrue(placements.size >= 10)
        placements.forEach { assertEquals(it, Position.fromFen(it).placement()) }
    }
}
