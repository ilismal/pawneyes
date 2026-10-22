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
    class Done(val result: ScanResult, val millis: Long) : ScanState
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
                val result = withContext(Dispatchers.Default) { scanner.scan(photo()) }
                val millis = SystemClock.elapsedRealtime() - start
                Log.i("Pawneyes", "escaneo en $millis ms")
                if (result == null) ScanState.NoBoard
                else ScanState.Done(result, millis).also { onResult(result) }
            } catch (e: Exception) {
                Log.e("Pawneyes", "error al escanear", e)
                ScanState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun dismiss() {
        state = ScanState.Idle
    }

    override fun onCleared() {
        if (scannerDelegate.isInitialized()) scanner.close()
    }
}
