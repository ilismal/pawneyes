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

/**
 * Un diagrama leído. `flipped`: la lectura se ha girado porque parece impreso desde las
 * negras. `entryId`: su entrada del historial (se crea al verlo), que recoge las
 * correcciones mientras se ve la tarjeta.
 */
class ScannedBoard(
    /** null mientras no se ha leído: los demás diagramas de la página se leen al elegirlos (#23). */
    val result: ScanResult?,
    val detection: com.pawneyes.vision.BoardDetector.Detection,
    /** La del tablero leído o, si aún no se ha leído, el tablero enderezado. */
    val thumbnail: Bitmap,
    val flipped: Boolean = result?.context?.flipped == true,
    val saved: Boolean = false,
    val entryId: String? = null,
) {
    fun copy(flipped: Boolean = this.flipped, saved: Boolean = this.saved, entryId: String? = this.entryId) =
        ScannedBoard(result, detection, thumbnail, flipped, saved, entryId)

    fun read(result: ScanResult) = ScannedBoard(result, detection, result.board)
}

sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState

    /**
     * Los diagramas de la foto (#10), en orden de lectura, y el que se está viendo.
     * `photo`: la foto escaneada, reducida, por si se guarda para entrenar.
     */
    class Done(
        val boards: List<ScannedBoard>,
        val index: Int,
        val millis: Long,
        val photo: Bitmap,
        /** La foto original, para leer los demás diagramas al elegirlos; null si solo hay uno. */
        val source: Bitmap? = null,
        /** El diagrama que se está leyendo ahora mismo, si hay alguno. */
        val loading: Int? = null,
    ) : ScanState {
        val board: ScannedBoard get() = boards[index]
        /** El que se ve siempre está leído. */
        val result: ScanResult get() = board.result!!
        val flipped: Boolean get() = board.flipped
        val saved: Boolean get() = board.saved
        val entryId: String? get() = board.entryId

        fun with(b: ScannedBoard, at: Int = index) =
            Done(boards.toMutableList().also { it[at] = b }, at, millis, photo, source)

        fun loading(at: Int?) = Done(boards, index, millis, photo, source, at)
    }

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
                var source: Bitmap? = null
                val boards = withContext(Dispatchers.Default) {
                    val bitmap = photo()
                    kept = reduced(bitmap)
                    // Se detectan todos, pero solo se lee el primero; los demás, al elegirlos (#23).
                    val found = scanner.detectAll(bitmap)
                    if (found.size > 1) source = bitmap
                    found.mapIndexed { i, d ->
                        if (i == 0) scanner.read(bitmap, d).let { r -> ScannedBoard(r, d, r.board) }
                        else ScannedBoard(null, d, scanner.thumbnail(bitmap, d))
                    }
                }
                val millis = SystemClock.elapsedRealtime() - start
                Log.i("Pawneyes", "escaneo en $millis ms: ${boards.size} diagramas")
                if (boards.isEmpty()) ScanState.NoBoard
                else {
                    val first = boards[0].result!!
                    // Al historial antes de cargarlo en el editor: así sus correcciones ya van a la entrada.
                    val entry = withContext(Dispatchers.IO) { addToHistory(first) }
                    val withEntry = listOf(boards[0].copy(entryId = entry.id)) + boards.drop(1)
                    ScanState.Done(withEntry, 0, millis, kept!!, source).also { onResult(first) }
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
        // Con varios diagramas se guarda solo el recorte de este: el entrenamiento busca un tablero por foto.
        val photo = if (done.boards.size > 1) cropTo(done.photo, done.result.detection.quad) else done.photo
        viewModelScope.launch {
            withContext(Dispatchers.IO) { training.save(photo, done.result.reading.rotation, printed) }
            trainingCount = training.count()
            (state as? ScanState.Done)?.let { now ->
                val i = now.boards.indexOfFirst { it.result === done.result }
                if (i >= 0) state = ScanState.Done(now.boards.toMutableList().also { it[i] = it[i].copy(saved = true) },
                    now.index, now.millis, now.photo, now.source, now.loading)
            }
        }
    }

    /** El usuario corrige la orientación deducida: devuelve la nueva. */
    fun toggleFlipped(): Boolean {
        val done = state as? ScanState.Done ?: return false
        state = done.with(done.board.copy(flipped = !done.flipped))
        return !done.flipped
    }

    /**
     * Pasa a otro diagrama de la foto (#10). Devuelve el diagrama y su posición tal como
     * quedó en el historial (con las correcciones, si ya se había visto) para el editor.
     */
    suspend fun select(index: Int): Pair<ScannedBoard, String>? {
        val done = state as? ScanState.Done ?: return null
        if (done.loading != null) return null
        var b = done.boards.getOrNull(index) ?: return null
        if (b.result == null) {
            // Se lee ahora, con la foto original que se guardó al escanear.
            val source = done.source ?: return null
            state = done.loading(index)
            val result = try {
                withContext(Dispatchers.Default) { scanner.read(source, b.detection) }
            } finally {
                (state as? ScanState.Done)?.let { state = it.loading(null) }
            }
            b = b.read(result)
            // En su sitio (`index`), no en el del diagrama que se estaba viendo.
            (state as? ScanState.Done)?.let { state = it.with(b, index) }
        }
        pendingSave?.join()  // que las correcciones del diagrama que se deja queden guardadas
        // Cada diagrama entra en el historial la primera vez que se ve.
        val entry = withContext(Dispatchers.IO) {
            b.entryId?.let { id -> history.entries().find { it.id == id } } ?: addToHistory(b.result!!)
        }
        val now = state as? ScanState.Done ?: return null
        val selected = b.copy(entryId = entry.id)
        state = now.with(selected, index)
        return selected to entry.fen
    }

    /** Recorte de la foto reducida alrededor de un tablero (esquinas en la escala del escáner), con margen. */
    private fun cropTo(photo: Bitmap, quad: DoubleArray): Bitmap {
        val f = maxOf(photo.width, photo.height).toDouble() / com.pawneyes.vision.VisionSpec.PHOTO_PX
        val xs = (0 until 4).map { quad[2 * it] * f }
        val ys = (0 until 4).map { quad[2 * it + 1] * f }
        val margin = 0.15 * maxOf(xs.max() - xs.min(), ys.max() - ys.min())
        val x0 = (xs.min() - margin).toInt().coerceIn(0, photo.width - 1)
        val y0 = (ys.min() - margin).toInt().coerceIn(0, photo.height - 1)
        val x1 = (xs.max() + margin).toInt().coerceIn(x0 + 1, photo.width)
        val y1 = (ys.max() + margin).toInt().coerceIn(y0 + 1, photo.height)
        return Bitmap.createBitmap(photo, x0, y0, x1 - x0, y1 - y0)
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
    private var pendingId: String? = null

    /** El editor ha cambiado: si se está viendo la tarjeta de un escaneo, se guarda en su entrada. */
    fun editorChanged(fen: String, flipped: Boolean) {
        val id = currentEntryId ?: return
        // Solo se descarta la escritura pendiente de esta misma entrada: si se acaba de pasar
        // a otro diagrama, la del anterior se completa.
        if (pendingId == id) pendingSave?.cancel()
        pendingId = id
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
