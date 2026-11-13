package com.pawneyes.app

import com.pawneyes.chess.Position
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.Normalizer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
        return (0 until a.length()).map { parse(a.getJSONObject(it)) }.sortedByDescending { it.createdAt }
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

    /** Lo que hace falta para deshacer un borrado: la entrada y su miniatura (#40). */
    class Deleted(val entry: HistoryEntry, val thumbnail: ByteArray?)

    @Synchronized
    fun delete(id: String): Deleted? {
        val all = entries()
        val entry = all.find { it.id == id } ?: return null
        val thumb = thumbnail(id).takeIf { it.exists() }?.readBytes()
        write(all - entry)
        thumbnail(id).delete()
        return Deleted(entry, thumb)
    }

    @Synchronized
    fun restore(deleted: Deleted) {
        val all = entries()
        if (all.any { it.id == deleted.entry.id }) return
        deleted.thumbnail?.let { thumbnail(deleted.entry.id).writeBytes(it) }
        write((all + deleted.entry).sortedByDescending { it.createdAt })
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** ZIP con el índice y las miniaturas, para llevarse el historial a otro móvil (#44). */
    @Synchronized
    fun export(out: OutputStream) {
        val all = entries()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(index.name))
            zip.write(toJson(all).toByteArray())
            zip.closeEntry()
            for (e in all) {
                val thumb = thumbnail(e.id).takeIf { it.exists() } ?: continue
                zip.putNextEntry(ZipEntry(thumb.name))
                thumb.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    class Imported(val added: Int, val present: Int)

    /**
     * Fusiona un ZIP de [export] con lo que ya hay: las entradas que ya están (mismo id) se
     * quedan como están. El ZIP viene de fuera: solo se lee el índice y las miniaturas de sus
     * entradas válidas, con tamaño limitado, y sus nombres nunca se usan como rutas.
     */
    @Synchronized
    fun import(input: InputStream): Imported {
        var json: String? = null
        val thumbs = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    name == index.name -> json = String(zip.readLimited(MAX_INDEX_BYTES))
                    name.endsWith(".jpg") && ID.matches(name.removeSuffix(".jpg")) && thumbs.size < MAX_ENTRIES ->
                        thumbs[name.removeSuffix(".jpg")] = zip.readLimited(MAX_THUMBNAIL_BYTES)
                }
            }
        }
        val a = runCatching { JSONArray(json ?: throw NotAHistory()) }.getOrElse { throw NotAHistory() }
        val imported = (0 until a.length()).mapNotNull { i -> runCatching { parse(a.getJSONObject(i)) }.getOrNull() }
            .filter { ID.matches(it.id) && it.createdAt > 0 && runCatching { Position.fromFen(it.fen) }.isSuccess }
            .distinctBy { it.id }
            .map { it.copy(label = it.label.trim().take(MAX_LABEL)) }
        val existing = entries()
        val known = existing.map { it.id }.toSet()
        val new = imported.filter { it.id !in known }
        val kept = (existing + new).sortedByDescending { it.createdAt }.take(MAX_ENTRIES)
        val keptIds = kept.map { it.id }.toSet()
        new.filter { it.id in keptIds }.forEach { e -> thumbs[e.id]?.let { thumbnail(e.id).writeBytes(it) } }
        existing.filter { it.id !in keptIds }.forEach { thumbnail(it.id).delete() }
        write(kept)
        return Imported(added = new.count { it.id in keptIds }, present = imported.size - new.size)
    }

    class NotAHistory : IllegalArgumentException("no es un historial de Pawneyes")

    private fun parse(o: JSONObject) = HistoryEntry(o.getString("id"), o.getLong("createdAt"), o.getString("fen"),
        o.optString("label"), o.optBoolean("flipped"))

    private fun toJson(entries: List<HistoryEntry>): String {
        val a = JSONArray()
        for (e in entries) {
            a.put(JSONObject().put("id", e.id).put("createdAt", e.createdAt).put("fen", e.fen)
                .put("label", e.label).put("flipped", e.flipped))
        }
        return a.toString()
    }

    private fun write(entries: List<HistoryEntry>) {
        // Se escribe aparte y se renombra: un cierre a medias no deja el índice roto.
        val tmp = File(dir, "historial.json.tmp")
        tmp.writeText(toJson(entries))
        check(tmp.renameTo(index)) { "no se pudo guardar el historial" }
    }

    private fun InputStream.readLimited(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > max) throw NotAHistory()
        }
    }

    companion object {
        const val MAX_ENTRIES = 500
        private const val MAX_INDEX_BYTES = 2 * 1024 * 1024
        private const val MAX_THUMBNAIL_BYTES = 512 * 1024
        private const val MAX_LABEL = 200
        /** Los ids que pone [add]: «s» y la hora en milisegundos, con «b» si coinciden. */
        private val ID = Regex("""s\d{1,19}b{0,10}""")

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
