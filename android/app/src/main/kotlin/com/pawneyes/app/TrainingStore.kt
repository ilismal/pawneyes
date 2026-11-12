package com.pawneyes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Escaneos corregidos por el usuario, para el siguiente ajuste fino del modelo.
 *
 * Cada uno es una foto JPEG (reducida y sin metadatos: se codifica de nuevo desde
 * el bitmap, así que no lleva EXIF ni GPS) y un .fen con la posición corregida,
 * el mismo formato que entrenamiento/ en ml/. Se quedan en el móvil hasta que se
 * exportan.
 *
 * El ZIP exportado lleva fotos de libros: no se deja en la caché más de lo necesario (#34).
 */
class TrainingStore(private val dir: File, private val exportDir: File) {
    constructor(context: Context) : this(File(context.filesDir, "entrenamiento"), File(context.cacheDir, "shared"))

    init {
        dir.mkdirs()
        exportDir.mkdirs()
    }

    fun count(): Int = dir.listFiles { f -> f.extension == "fen" }?.size ?: 0

    /**
     * `photo`: la foto escaneada. `rotation`: giros de 90° (antihorarios) con los que
     * se leyó el tablero; la foto se guarda girada para que el diagrama quede derecho,
     * que es lo que espera el entrenamiento.
     */
    fun save(photo: Bitmap, rotation: Int, fen: String): String {
        val name = "app-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        val upright = if (rotation % 4 == 0) photo else Bitmap.createBitmap(
            photo, 0, 0, photo.width, photo.height, Matrix().apply { postRotate(-90f * rotation) }, true,
        )
        File(dir, "$name.jpg").outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        File(dir, "$name.fen").writeText(fen + "\n")
        return name
    }

    /** ZIP con todas las fotos y .fen guardados, listo para compartir. */
    fun exportZip(): File {
        deleteExports()
        val zip = File(exportDir, EXPORT_PREFIX +
            SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date()) + ".zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                out.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        deleteExports()
    }

    /**
     * Al arrancar, los ZIP de exportaciones anteriores. Solo los de más de una hora: la app
     * con la que se compartió puede estar leyéndolo todavía (una subida a la nube, por ejemplo).
     */
    fun deleteOldExports(now: Long = System.currentTimeMillis()) = deleteExports { now - it.lastModified() > EXPORT_TTL_MS }

    private fun deleteExports(also: (File) -> Boolean = { true }) {
        exportDir.listFiles { f -> f.name.startsWith(EXPORT_PREFIX) && f.extension == "zip" }
            ?.filter(also)?.forEach { it.delete() }
    }

    companion object {
        private const val EXPORT_PREFIX = "pawneyes-entrenamiento-"
        const val EXPORT_TTL_MS = 60 * 60 * 1000L
    }
}
