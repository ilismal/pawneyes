package com.pawneyes.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawneyes.chess.Position
import com.pawneyes.vision.ScanResult
import com.pawneyes.vision.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState

    /**
     * `photo`: la foto escaneada, reducida, por si se guarda para entrenar.
     * `flipped`: la lectura se ha girado porque el diagrama parece impreso desde las negras.
     * `entryId`: su entrada del historial, que recoge las correcciones mientras se ve la tarjeta.
     */
    class Done(
        val result: ScanResult,
        val millis: Long,
        val photo: Bitmap,
        val saved: Boolean = false,
        val flipped: Boolean = result.context?.flipped == true,
        val entryId: String? = null,
    ) : ScanState

    /** Un escaneo reabierto desde el historial; las correcciones también se guardan en él. */
    class FromHistory(val entry: HistoryEntry, val thumbnail: Bitmap?) : ScanState
    data object NoBoard : ScanState
    class Failed(val message: String) : ScanState
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val scannerDelegate = lazy { Scanner.create(app) }
    private val scanner by scannerDelegate

    var state: ScanState by mutableStateOf(ScanState.Idle)
        private set

    /** Foto de la galería o compartida. */
    fun scan(uri: Uri, onResult: (ScanResult) -> Unit) =
        run(onResult) { PhotoLoader.load(getApplication(), uri) }

    /** Foto recién hecha con la cámara (ya derecha). */
    fun scan(photo: Bitmap, onResult: (ScanResult) -> Unit) = run(onResult) { photo }

    private fun run(onResult: (ScanResult) -> Unit, photo: () -> Bitmap) {
        state = ScanState.Scanning
        viewModelScope.launch {
            state = try {
                val start = SystemClock.elapsedRealtime()
                var kept: Bitmap? = null
                val result = withContext(Dispatchers.Default) {
                    val bitmap = photo()
                    kept = reduced(bitmap)
                    scanner.scan(bitmap)
                }
                val millis = SystemClock.elapsedRealtime() - start
                Log.i("Pawneyes", "escaneo en $millis ms")
                if (result == null) ScanState.NoBoard
                else {
                    // Al historial antes de cargarlo en el editor: así sus correcciones ya van a la entrada.
                    val entry = withContext(Dispatchers.IO) { addToHistory(result) }
                    ScanState.Done(result, millis, kept!!, entryId = entry.id).also { onResult(result) }
                }
            } catch (e: Exception) {
                Log.e("Pawneyes", "error al escanear", e)
                ScanState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun dismiss() {
        state = ScanState.Idle
    }

    private val training = TrainingStore(app)

    /** Escaneos guardados para entrenar (se actualiza al guardar, exportar o borrar). */
    var trainingCount by mutableStateOf(training.count())
        private set

    /** Guarda la foto del último escaneo con la posición `fen` (la ya corregida). */
    fun saveForTraining(fen: String) {
        val done = state as? ScanState.Done ?: return
        if (done.saved) return
        // Se entrena con el tablero tal como está impreso: si se ha girado, se deshace el giro.
        val printed = if (done.flipped) Position.fromFen(fen).rotated().toFen() else fen
        viewModelScope.launch {
            withContext(Dispatchers.IO) { training.save(done.photo, done.result.reading.rotation, printed) }
            trainingCount = training.count()
            if (state === done) {
                state = ScanState.Done(done.result, done.millis, done.photo, saved = true, done.flipped, done.entryId)
            }
        }
    }

    /** El usuario corrige la orientación deducida: devuelve la nueva. */
    fun toggleFlipped(): Boolean {
        val done = state as? ScanState.Done ?: return false
        state = ScanState.Done(done.result, done.millis, done.photo, done.saved, !done.flipped, done.entryId)
        return !done.flipped
    }

    private val history = ScanHistory(java.io.File(app.filesDir, "historial"))

    /** Escaneos anteriores, el más reciente primero. */
    var historyEntries: List<HistoryEntry> by mutableStateOf(emptyList())
        private set

    init {
        viewModelScope.launch { historyEntries = withContext(Dispatchers.IO) { history.entries() } }
    }

    private fun addToHistory(result: ScanResult): HistoryEntry {
        val side = result.context?.side ?: com.pawneyes.chess.Side.WHITE
        val fen = result.position.copy(sideToMove = side).let { it.copy(castling = it.plausibleCastling()) }.toFen()
        val thumb = result.board.scale(THUMBNAIL_PX, THUMBNAIL_PX)
        val jpeg = java.io.ByteArrayOutputStream().also { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        return history.add(fen, result.context?.flipped == true, jpeg).also { historyEntries = history.entries() }
    }

    private val currentEntryId: String?
        get() = when (val s = state) {
            is ScanState.Done -> s.entryId
            is ScanState.FromHistory -> s.entry.id
            else -> null
        }

    private var pendingSave: kotlinx.coroutines.Job? = null

    /** El editor ha cambiado: si se está viendo la tarjeta de un escaneo, se guarda en su entrada. */
    fun editorChanged(fen: String, flipped: Boolean) {
        val id = currentEntryId ?: return
        pendingSave?.cancel()
        pendingSave = viewModelScope.launch {
            kotlinx.coroutines.delay(SAVE_DELAY_MS)  // tras una ráfaga de cambios, una sola escritura
            withContext(Dispatchers.IO) { history.update(id) { it.copy(fen = fen, flipped = flipped) } }
            historyEntries = withContext(Dispatchers.IO) { history.entries() }
        }
    }

    /** Reabre una entrada: el editor la carga con lo que devuelve. */
    suspend fun open(entry: HistoryEntry): HistoryEntry {
        val thumb = withContext(Dispatchers.IO) {
            history.thumbnail(entry.id).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
        }
        state = ScanState.FromHistory(entry, thumb)
        return entry
    }

    fun rename(id: String, label: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { history.update(id) { it.copy(label = label.trim()) } }
        historyEntries = withContext(Dispatchers.IO) { history.entries() }
        (state as? ScanState.FromHistory)?.takeIf { it.entry.id == id }?.let {
            state = ScanState.FromHistory(it.entry.copy(label = label.trim()), it.thumbnail)
        }
    }

    fun delete(id: String) = viewModelScope.launch {
        if (currentEntryId == id) state = ScanState.Idle
        withContext(Dispatchers.IO) { history.delete(id) }
        historyEntries = withContext(Dispatchers.IO) { history.entries() }
    }

    fun clearHistory() = viewModelScope.launch {
        if (currentEntryId != null) state = ScanState.Idle
        withContext(Dispatchers.IO) { history.clear() }
        historyEntries = emptyList()
    }

    fun thumbnailFile(id: String): java.io.File = history.thumbnail(id)

    suspend fun exportTraining(): java.io.File = withContext(Dispatchers.IO) { training.exportZip() }

    fun clearTraining() {
        training.clear()
        trainingCount = 0
    }

    /** Lado largo de VisionSpec.PHOTO_PX: es la resolución con la que trabaja el escáner. */
    private fun reduced(bitmap: Bitmap): Bitmap {
        val f = com.pawneyes.vision.VisionSpec.PHOTO_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (f >= 1f) return bitmap
        return bitmap.scale((bitmap.width * f).toInt(), (bitmap.height * f).toInt())
    }

    companion object {
        const val THUMBNAIL_PX = 256
        const val SAVE_DELAY_MS = 800L
    }

    override fun onCleared() {
        if (scannerDelegate.isInitialized()) scanner.close()
    }
}
