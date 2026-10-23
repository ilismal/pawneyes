package com.pawneyes.app

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanHistoryTest {
    private val dir = Files.createTempDirectory("historial").toFile()
    private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val jpeg = byteArrayOf(1, 2, 3)

    @Test
    fun addUpdateAndPersist() {
        val h = ScanHistory(dir)
        val a = h.add(start, flipped = false, thumbnail = jpeg, now = 1000)
        val b = h.add("8/8/8/8/8/8/8/K6k w - - 0 1", flipped = true, thumbnail = jpeg, now = 2000)
        // Lo más reciente primero, y sobrevive a reabrir.
        assertEquals(listOf(b.id, a.id), ScanHistory(dir).entries().map { it.id })
        assertTrue(h.thumbnail(a.id).exists())

        h.update(a.id) { it.copy(fen = "8/8/8/8/8/8/8/k6K b - - 0 1", label = "Capablanca, pág. 37") }
        val reread = ScanHistory(dir).entries().single { it.id == a.id }
        assertEquals("8/8/8/8/8/8/8/k6K b - - 0 1", reread.fen)
        assertEquals("Capablanca, pág. 37", reread.label)
        assertEquals(1000, reread.createdAt)
        assertTrue(ScanHistory(dir).entries().single { it.id == b.id }.flipped)

        assertNull(h.update("no-existe") { it })
        h.delete(b.id)
        assertEquals(listOf(a.id), h.entries().map { it.id })
        assertFalse(h.thumbnail(b.id).exists())
    }

    @Test
    fun sameMillisecondGetsADifferentId() {
        val h = ScanHistory(dir)
        val a = h.add(start, false, jpeg, now = 5)
        val b = h.add(start, false, jpeg, now = 5)
        assertTrue(a.id != b.id)
        assertEquals(2, h.entries().size)
    }

    @Test
    fun oldestAreDroppedWithTheirThumbnails() {
        val h = ScanHistory(dir)
        val first = h.add(start, false, jpeg, now = 0)
        repeat(ScanHistory.MAX_ENTRIES) { h.add(start, false, jpeg, now = it + 1L) }
        assertEquals(ScanHistory.MAX_ENTRIES, h.entries().size)
        assertFalse(h.entries().any { it.id == first.id })
        assertFalse(h.thumbnail(first.id).exists())
    }

    @Test
    fun searchIgnoresCaseAndAccents() {
        val e = listOf(
            HistoryEntry("1", 1, start, "Capablanca, página 37"),
            HistoryEntry("2", 2, start, "Kubbel: mate en 5"),
            HistoryEntry("3", 3, start, ""),
        )
        assertEquals(listOf("1"), ScanHistory.search(e, "capablanca PAGINA").map { it.id })
        assertEquals(listOf("2"), ScanHistory.search(e, "kubbel").map { it.id })
        assertEquals(e, ScanHistory.search(e, "  "))
        assertEquals(emptyList<HistoryEntry>(), ScanHistory.search(e, "nimzowitsch"))
    }
}
