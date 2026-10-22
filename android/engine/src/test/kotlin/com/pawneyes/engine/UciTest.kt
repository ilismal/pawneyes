package com.pawneyes.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UciTest {
    @Test
    fun parsesCentipawnLines() {
        val l = Uci.parseInfo("info depth 22 seldepth 31 multipv 2 score cp -35 nodes 123 nps 4567 " +
            "hashfull 12 tbhits 0 time 900 pv e7e5 g1f3 b8c6")
        assertEquals(EngineLine(2, 22, -35, null, listOf("e7e5", "g1f3", "b8c6")), l)
    }

    @Test
    fun parsesMateAndBounds() {
        val l = Uci.parseInfo("info depth 30 multipv 1 score mate -3 lowerbound nodes 1 pv h7h8")
        assertEquals(EngineLine(1, 30, null, -3, listOf("h7h8")), l)
    }

    @Test
    fun ignoresOtherMessages() {
        assertNull(Uci.parseInfo("info string NNUE evaluation using nn-xxxx.nnue enabled"))
        assertNull(Uci.parseInfo("info depth 5 currmove e2e4 currmovenumber 1"))
        assertNull(Uci.parseInfo("bestmove e2e4 ponder e7e5"))
        assertNull(Uci.parseInfo("readyok"))
    }
}
