package com.pawneyes.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookLineTest {
    private fun san(start: Position, text: String): String {
        val r = BookLine.parse(start, text)
        assertTrue("$text → $r", r is BookLine.Result.Ok)
        return Line(start, (r as BookLine.Result.Ok).moves.map { it.uci }).san().joinToString(" ")
    }

    @Test
    fun ruyLopezInDescriptive() {
        // «C3A» de las negras en la 4: c6 está ocupada por su caballo, así que es f6.
        assertEquals("1. e4 1... e5 2. Cf3 2... Cc6 3. Ab5 3... a6 4. Aa4 4... Cf6 5. O-O 5... Ae7",
            san(Position.START, "1. P4R, P4R 2. C3AR, C3AD 3. A5C, P3TD 4. A4T, C3A 5. O-O, A2R"))
    }

    @Test
    fun queensGambitWithQualifiedKnight() {
        // «CD2D»: el caballo del flanco de dama a la 2 de dama (d7).
        assertEquals("1. d4 1... d5 2. c4 2... e6 3. Cc3 3... Cf6 4. Ag5 4... Ae7 5. e3 5... O-O 6. Cf3 6... Cbd7",
            san(Position.START, "1. P4D, P4D 2. P4AD, P3R 3. C3AD, C3AR 4. A5CR, A2R 5. P3R, O-O 6. C3A, CD2D"))
    }

    @Test
    fun capturesAndRetreats() {
        // Escandinava: «P×P», «D×P» y «D1D» (para las negras, la 1 es su primera fila: d8).
        assertEquals("1. e4 1... d5 2. exd5 2... Dxd5 3. Cc3 3... Dd8",
            san(Position.START, "1.P4R P4D 2.P×P D×P 3.C3AD D1D"))
    }

    @Test
    fun originInParenthesesAndBlackFirst() {
        // Los caballos de c3 y e3 pueden capturar en d5: «C(3A)» es el de la columna del
        // alfil (c3) y «C(3R)», el de la del rey (e3).
        val p = Position.fromFen("4k3/8/8/3p4/8/2N1N3/8/4K3 w - - 0 1")
        assertEquals("1. Ccxd5", san(p, "C(3A)×P"))
        assertEquals("1. Cexd5", san(p, "C(3R)×P!"))
        // La línea puede empezar con las negras («4. ..., C×P»).
        val black = Position.fromFen("r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R b KQkq - 4 4")
        assertEquals("4... Cxe4", san(black, "4. ..., C×P"))
    }

    @Test
    fun pawnQualifierAndEnPassant() {
        // Dos peones pueden capturar en d5: «PR×P» es el de la columna de rey.
        val p = Position.fromFen("4k3/8/8/3p4/2P1P3/8/8/4K3 w - - 0 1")
        assertEquals("1. exd5", san(p, "PR×P"))
        assertEquals("1. cxd5", san(p, "PAD×P"))
        val ep = Position.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2")
        assertEquals("2. exd6", san(ep, "P×P a.p."))
    }

    @Test
    fun promotion() {
        val p = Position.fromFen("8/4P1k1/8/8/8/8/8/4K3 w - - 0 1")
        assertEquals("1. e8=D", san(p, "P8R"))
        assertEquals("1. e8=C+", san(p, "P8R=C"))
        assertEquals("1. e8=T", san(p, "P8R(T)"))
    }

    @Test
    fun algebraicAndMixed() {
        assertEquals("1. e4 1... e5 2. Cf3 2... Cc6", san(Position.START, "1. e4 e5 2. Nf3 C3AD"))
    }

    @Test
    fun ambiguousIllegalAndUnknownStop() {
        // «C×P» no dice cuál de los dos caballos: se para ahí, con lo leído hasta entonces.
        val p = Position.fromFen("4k3/8/8/3p4/8/2N1N3/8/4K3 w - - 0 1")
        val amb = BookLine.parse(p, "1. R2D, R1A 2. C×P")
        assertTrue(amb is BookLine.Result.Error)
        amb as BookLine.Result.Error
        assertEquals(2, amb.index)
        assertEquals(2, amb.moves.size)
        assertEquals(BookLine.Reason.Ambiguous(listOf("Ccxd5", "Cexd5")), amb.reason)

        val illegal = BookLine.parse(Position.START, "1. P5R")
        assertEquals(BookLine.Reason.Illegal, (illegal as BookLine.Result.Error).reason)
        val unknown = BookLine.parse(Position.START, "1. P4R, hola")
        assertEquals(BookLine.Reason.Unknown, (unknown as BookLine.Result.Error).reason)
        assertEquals(1, unknown.index)
    }

    @Test
    fun tokensIgnoreNumbersEllipsisAndComments() {
        assertEquals(listOf("C4D", "C(3A)xD", "R1A"), BookLine.tokens("18. C4D, … 19. C(3A)xD!, R1A | ..."))
        assertEquals(listOf("T1A"), BookLine.tokens("19. ..., T1A"))
    }
}
