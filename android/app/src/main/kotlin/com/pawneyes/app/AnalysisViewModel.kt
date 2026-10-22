package com.pawneyes.app

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawneyes.chess.Position
import com.pawneyes.chess.isLegal
import com.pawneyes.chess.legalMoves
import com.pawneyes.engine.Analysis
import com.pawneyes.engine.StockfishEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Stockfish analizando la posición del editor mientras el interruptor está
 * activo y la app en primer plano. El motor se arranca la primera vez.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class AnalysisViewModel(app: Application) : AndroidViewModel(app) {
    val available = StockfishEngine.isAvailable(app)

    var enabled by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val engine = MutableStateFlow<StockfishEngine?>(null)
    private val position = MutableStateFlow<Position?>(null)
    private val active = MutableStateFlow(false)  // interruptor, primer plano y sin escanear
    private var foreground = true
    private var busy = false

    val analysis: StateFlow<Analysis?> = engine
        .flatMapLatest { it?.analysis ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            // Un margen corto: al editar no se relanza la búsqueda en cada toque.
            combine(position, active) { p, on -> p.takeIf { on } }
                .debounce(250)
                .collectLatest { p ->
                    try {
                        // Sin jugadas legales (mate o ahogado) no hay nada que analizar:
                        // el panel muestra el resultado sin el motor.
                        if (p == null || !p.isLegal() || p.legalMoves().isEmpty()) engine.value?.stop()
                        else ensureEngine().analyze(p)
                    } catch (e: Exception) {
                        Log.e("Pawneyes", "error del motor", e)
                        error = e.message ?: e.javaClass.simpleName
                    }
                }
        }
    }

    private suspend fun ensureEngine(): StockfishEngine =
        engine.value ?: StockfishEngine.start(getApplication()).also { engine.value = it }

    fun toggle(on: Boolean) {
        enabled = on
        error = null
        update()
    }

    fun setForeground(visible: Boolean) {
        foreground = visible
        update()
    }

    /** Mientras se escanea o está abierta la cámara, el motor no compite por la CPU. */
    fun setBusy(scanning: Boolean) {
        busy = scanning
        update()
    }

    private fun update() {
        active.value = enabled && foreground && !busy
    }

    fun onPosition(p: Position) {
        position.value = p
    }

    override fun onCleared() {
        engine.value?.close()
    }
}
