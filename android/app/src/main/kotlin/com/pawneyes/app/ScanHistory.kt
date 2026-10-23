package com.pawneyes.app

import java.io.File
import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject

/** Un escaneo guardado: la miniatura del diagrama, la posición (con las correcciones) y una etiqueta. */
data class HistoryEntry(
    val id: String,
    val createdAt: Long,
    val fen: String,
    /** Libro y página, o lo que el usuario quiera; vacía al escanear. */
    val label: String = "",
    /** El tablero se miraba desde las negras (impreso así en el libro). */
    val flipped: Boolean = false,
)

/**
 * Historial de escaneos (#6): un índice JSON y una miniatura JPEG por escaneo, en
 * `dir`. Sin base de datos: para unos cientos de entradas, ficheros de sobra.
 * Las más antiguas se borran al pasar de [MAX_ENTRIES].
 */
class ScanHistory(private val dir: File) {
    private val index = File(dir, "historial.json")

    init {
        dir.mkdirs()
    }

    @Synchronized
    fun entries(): List<HistoryEntry> {
        if (!index.exists()) return emptyList()
        val a = runCatching { JSONArray(index.readText()) }.getOrElse { return emptyList() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            HistoryEntry(it.getString("id"), it.getLong("createdAt"), it.getString("fen"),
                it.optString("label"), it.optBoolean("flipped"))
        }.sortedByDescending { it.createdAt }
    }

    fun thumbnail(id: String): File = File(dir, "$id.jpg")

    /** Guarda un escaneo nuevo; `thumbnail`: la miniatura ya codificada en JPEG. */
    @Synchronized
    fun add(fen: String, flipped: Boolean, thumbnail: ByteArray, now: Long = System.currentTimeMillis()): HistoryEntry {
        val existing = entries()
        var id = "s$now"
        while (existing.any { it.id == id }) id += "b"
        thumbnail(id).writeBytes(thumbnail)
        val entry = HistoryEntry(id, now, fen, flipped = flipped)
        val kept = (listOf(entry) + existing).take(MAX_ENTRIES)
        (existing - kept.toSet()).forEach { thumbnail(it.id).delete() }
        write(kept)
        return entry
    }

    @Synchronized
    fun update(id: String, transform: (HistoryEntry) -> HistoryEntry): HistoryEntry? {
        val all = entries()
        val old = all.find { it.id == id } ?: return null
        val new = transform(old).copy(id = old.id, createdAt = old.createdAt)
        if (new != old) write(all.map { if (it.id == id) new else it })
        return new
    }

    @Synchronized
    fun delete(id: String) {
        write(entries().filter { it.id != id })
        thumbnail(id).delete()
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun write(entries: List<HistoryEntry>) {
        val a = JSONArray()
        for (e in entries) {
            a.put(JSONObject().put("id", e.id).put("createdAt", e.createdAt).put("fen", e.fen)
                .put("label", e.label).put("flipped", e.flipped))
        }
        // Se escribe aparte y se renombra: un cierre a medias no deja el índice roto.
        val tmp = File(dir, "historial.json.tmp")
        tmp.writeText(a.toString())
        check(tmp.renameTo(index)) { "no se pudo guardar el historial" }
    }

    companion object {
        const val MAX_ENTRIES = 500

        private fun norm(s: String) =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(Regex("""\p{M}"""), "")

        /** Entradas cuya etiqueta contiene todas las palabras de `query` (sin mayúsculas ni tildes). */
        fun search(entries: List<HistoryEntry>, query: String): List<HistoryEntry> {
            val words = norm(query).split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return entries
            return entries.filter { e -> norm(e.label).let { label -> words.all { it in label } } }
        }
    }
}
