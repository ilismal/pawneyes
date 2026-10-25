package com.pawneyes.board

import com.pawneyes.chess.Castling
import com.pawneyes.chess.Piece
import com.pawneyes.chess.PieceType
import com.pawneyes.chess.Position
import com.pawneyes.chess.Side
import com.pawneyes.chess.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BoardEditorStateTest {
    private fun sq(name: String) = Square.parse(name)!!

    @Test
    fun tapSelectsAndSecondTapMoves() {
        // #27: tocar una pieza ya no la borra; la elige, y el siguiente toque la mueve.
        val s = BoardEditorState(Position.START)
        s.tap(sq("e2"))
        assertEquals(sq("e2"), s.selected)
        assertEquals(Piece(Side.WHITE, PieceType.PAWN), s.position[sq("e2")])
        s.tap(sq("e4"))
        assertNull(s.selected)
        assertNull(s.position[sq("e2")])
        assertEquals(Piece(Side.WHITE, PieceType.PAWN), s.position[sq("e4")])
        s.undo()
        assertEquals(Position.START, s.position)
    }

    @Test
    fun tappingTheSamePieceAgainDeselects() {
        val s = BoardEditorState(Position.START)
        s.tap(sq("g1"))
        s.tap(sq("g1"))
        assertNull(s.selected)
        assertEquals(Position.START, s.position)
        assertFalse(s.canUndo)
    }

    @Test
    fun tapOnEmptySquarePlacesPaletteAndEraserErases() {
        val s = BoardEditorState(Position.START)
        s.tool = EditTool.Place(Piece(Side.BLACK, PieceType.QUEEN))
        s.tap(sq("d4"))
        assertEquals(Piece(Side.BLACK, PieceType.QUEEN), s.position[sq("d4")])
        s.tool = EditTool.Erase
        s.tap(sq("d4"))
        assertNull(s.position[sq("d4")])
    }

    @Test
    fun setReplacesUnmarksAndFixesCastling() {
        // «Otra…» (#26) y la pulsación larga ponen cualquier pieza; quitar la torre quita su enroque.
        val s = BoardEditorState(Position.START, marked = setOf(sq("h1")))
        s.set(sq("h1"), Piece(Side.WHITE, PieceType.KNIGHT))
        assertEquals(Piece(Side.WHITE, PieceType.KNIGHT), s.position[sq("h1")])
        assertFalse(sq("h1") in s.marked)
        assertFalse(Castling.WHITE_KINGSIDE in s.position.castling)
    }

    @Test
    fun firstTapOnMarkedSquareAcceptsIt() {
        val s = BoardEditorState(Position.START, marked = setOf(sq("e2")))
        s.tap(sq("e2"))
        assertNull(s.selected)
        assertEquals(emptySet<Int>(), s.marked)
        assertEquals(Position.START, s.position)
    }
}
