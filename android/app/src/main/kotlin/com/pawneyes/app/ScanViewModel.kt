package com.pawneyes.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawneyes.vision.ScanResult
import com.pawneyes.vision.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState

    /** `photo`: la foto escaneada, reducida, por si se guarda para entrenar. */
    class Done(val result: ScanResult, val millis: Long, val photo: Bitmap, val saved: Boolean = false) : ScanState
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
                else ScanState.Done(result, millis, kept!!).also { onResult(result) }
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) { training.save(done.photo, done.result.reading.rotation, fen) }
            trainingCount = training.count()
            if (state === done) state = ScanState.Done(done.result, done.millis, done.photo, saved = true)
        }
    }

    suspend fun exportTraining(): java.io.File = withContext(Dispatchers.IO) { training.exportZip() }

    fun clearTraining() {
        training.clear()
        trainingCount = 0
    }

    /** Lado largo de VisionSpec.PHOTO_PX: es la resolución con la que trabaja el escáner. */
    private fun reduced(bitmap: Bitmap): Bitmap {
        val f = com.pawneyes.vision.VisionSpec.PHOTO_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (f >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt(), (bitmap.height * f).toInt(), true)
    }

    override fun onCleared() {
        if (scannerDelegate.isInitialized()) scanner.close()
    }
}
