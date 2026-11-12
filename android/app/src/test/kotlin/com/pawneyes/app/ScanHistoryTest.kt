package com.pawneyes.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    private fun exported(h: ScanHistory) = ByteArrayOutputStream().also { h.export(it) }.toByteArray()

    private fun zip(vararg files: Pair<String, ByteArray>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z -> files.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
    }.toByteArray()

    @Test
    fun exportAndImportIntoAnotherPhone() {
        val old = ScanHistory(dir)
        val a = old.add(start, flipped = true, thumbnail = jpeg, now = 1000)
        old.update(a.id) { it.copy(label = "Capablanca, pág. 37") }
        old.add("8/8/8/8/8/8/8/K6k w - - 0 1", false, byteArrayOf(4, 5), now = 2000)

        val new = ScanHistory(Files.createTempDirectory("otro").toFile())
        val r = new.import(ByteArrayInputStream(exported(old)))
        assertEquals(2, r.added)
        assertEquals(0, r.present)
        assertEquals(old.entries(), new.entries())
        assertTrue(new.thumbnail(a.id).readBytes().contentEquals(jpeg))
    }

    @Test
    fun importMergesWithoutDuplicates() {
        val h = ScanHistory(dir)
        val a = h.add(start, false, jpeg, now = 1000)
        val zip = exported(h)
        h.update(a.id) { it.copy(label = "corregida aquí") }
        h.add(start, false, jpeg, now = 3000)

        val r = h.import(ByteArrayInputStream(zip))
        assertEquals(0, r.added)
        assertEquals(1, r.present)
        assertEquals(2, h.entries().size)
        assertEquals("corregida aquí", h.entries().first { it.id == a.id }.label)  // gana la local
    }

    @Test
    fun importIgnoresWhatItDoesNotKnow() {
        val index = """[
            {"id":"s1","createdAt":1,"fen":"$start","label":"buena"},
            {"id":"../../escapa","createdAt":2,"fen":"$start"},
            {"id":"s3","createdAt":3,"fen":"esto no es un FEN"},
            {"id":"s4","createdAt":-4,"fen":"$start"}
        ]""".toByteArray()
        val h = ScanHistory(dir)
        val r = h.import(ByteArrayInputStream(zip(
            "historial.json" to index,
            "s1.jpg" to jpeg,
            "../../escapa.jpg" to jpeg,
            "/etc/passwd" to jpeg,
        )))
        assertEquals(1, r.added)
        assertEquals(listOf("s1"), h.entries().map { it.id })
        assertEquals(setOf("historial.json", "s1.jpg"), dir.list()!!.toSet())
        assertFalse(File(dir.parentFile.parentFile, "escapa.jpg").exists())
    }

    @Test
    fun importRejectsOtherFiles() {
        val h = ScanHistory(dir)
        assertThrows(ScanHistory.NotAHistory::class.java) { h.import(ByteArrayInputStream("hola".toByteArray())) }
        assertThrows(ScanHistory.NotAHistory::class.java) { h.import(ByteArrayInputStream(zip("otra.txt" to jpeg))) }
        assertThrows(ScanHistory.NotAHistory::class.java) {
            h.import(ByteArrayInputStream(zip("historial.json" to ByteArray(3 * 1024 * 1024) { ' '.code.toByte() })))
        }
        assertTrue(h.entries().isEmpty())
    }
}
