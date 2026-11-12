package com.pawneyes.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingStoreTest {
    private val root = Files.createTempDirectory("entrenamiento").toFile()
    private val exports = File(root, "shared")
    private val store = TrainingStore(File(root, "fotos"), exports)

    private fun oldZip(name: String, modified: Long) =
        File(exports, name).apply { writeText("zip"); setLastModified(modified) }

    @Test
    fun exportReplacesPreviousZips() {
        val previous = oldZip("pawneyes-entrenamiento-20260101.zip", 0)
        val other = File(exports, "tablero.png").apply { writeText("png") }
        val zip = store.exportZip()
        assertTrue(zip.exists())
        assertFalse(previous.exists())
        assertTrue(other.exists())  // la imagen del tablero no es del entrenamiento
    }

    @Test
    fun startupDeletesOnlyOldZips() {
        val now = 10 * TrainingStore.EXPORT_TTL_MS
        val old = oldZip("pawneyes-entrenamiento-20260101.zip", now - TrainingStore.EXPORT_TTL_MS - 1)
        val recent = oldZip("pawneyes-entrenamiento-20260102.zip", now - 1000)
        store.deleteOldExports(now)
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun clearDeletesZipsToo() {
        store.exportZip()
        store.clear()
        assertEquals(0, store.count())
        assertEquals(0, exports.listFiles()!!.count { it.extension == "zip" })
    }
}
