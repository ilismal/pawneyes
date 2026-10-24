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
import com.pawneyes.chess.Move
import com.pawneyes.engine.EngineLine
import com.pawneyes.engine.StockfishEngine
import com.pawneyes.engine.Verdict
import com.pawneyes.engine.Verdicts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

/** Un ejercicio en curso: posición, mejor jugada (oculta) y veredicto de la del usuario. */
data class Exercise(
    val position: Position,
    val best: EngineLine? = null,
    val played: Move? = null,
    val verdict: Verdict? = null,
    val showSolution: Boolean = false,
    /** Se ha pedido pista (#20): se marca la pieza que se mueve en la mejor jugada. */
    val hint: Boolean = false,
) {
    val checking: Boolean get() = played != null && verdict == null
}

/**
 * Stockfish analizando la posición del editor mientras el interruptor está
 * activo y la app en primer plano. El motor se arranca la primera vez. También
 * lleva el modo ejercicio, que usa el mismo motor con el análisis normal en pausa.
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
    /** Líneas y profundidad del análisis, de los ajustes (#24); no afectan al ejercicio. */
    private val limits = MutableStateFlow(3 to StockfishEngine.DEFAULT_DEPTH)
    private var foreground = true
    private var busy = false

    var exercise by mutableStateOf<Exercise?>(null)
        private set
    private var exerciseJob: Job? = null
    private var bestReady = CompletableDeferred<EngineLine>()

    val analysis: StateFlow<Analysis?> = engine
        .flatMapLatest { it?.analysis ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Posiciones cuyo análisis ya se ha visto en esta sesión (#19): no tiene sentido resolverlas como ejercicio. */
    private var seen by mutableStateOf(emptySet<String>())

    /** Clave de la posición sin los relojes: otra pieza, otro turno u otros enroques es otra posición. */
    private fun key(p: Position) = p.toFen().split(' ').take(4).joinToString(" ")

    fun analysisSeen(p: Position): Boolean = key(p) in seen

    init {
        viewModelScope.launch {
            // Solo cuenta lo que se muestra: con el análisis encendido y fuera del ejercicio
            // (que usa el mismo motor para preparar la solución sin enseñarla).
            analysis.collect { a ->
                if (a != null && a.lines.isNotEmpty() && enabled && exercise == null) seen = seen + key(a.position)
            }
        }
        viewModelScope.launch {
            // Un margen corto: al editar no se relanza la búsqueda en cada toque.
            combine(position, active, limits) { p, on, l -> p.takeIf { on }?.let { it to l } }
                .debounce(250)
                .collectLatest { req ->
                    val p = req?.first
                    val (lines, depth) = req?.second ?: limits.value
                    try {
                        // Sin jugadas legales (mate o ahogado) no hay nada que analizar:
                        // el panel muestra el resultado sin el motor.
                        if (p == null || !p.isLegal() || p.legalMoves().isEmpty()) engine.value?.stop()
                        else ensureEngine().analyze(p, lines = lines, depth = depth)
                    } catch (e: Exception) {
                        Log.e("Pawneyes", "error del motor", e)
                        error = e.message ?: e.javaClass.simpleName
                    }
                }
        }
    }

    private suspend fun ensureEngine(): StockfishEngine =
        engine.value ?: EngineHolder.get(getApplication()).also { engine.value = it }

    fun setLimits(lines: Int, depth: Int) {
        limits.value = lines to depth
    }

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
        active.value = enabled && foreground && !busy && exercise == null
    }

    // --- Modo ejercicio ---------------------------------------------------------

    /** Empieza un ejercicio: el motor busca en silencio la mejor jugada. */
    fun startExercise(p: Position) {
        exerciseJob?.cancel()
        exercise = Exercise(p)
        error = null
        update()
        val ready = CompletableDeferred<EngineLine>().also { bestReady = it }
        exerciseJob = viewModelScope.launch {
            try {
                val e = ensureEngine()
                // El ejercicio solo mira la mejor jugada: una línea basta y llega antes.
                e.analyze(p, lines = 1, depth = StockfishEngine.DEFAULT_DEPTH)
                val a = e.analysis.filterNotNull().first {
                    it.position == p && it.only.isEmpty() && it.lines.isNotEmpty() && (it.done || it.depth >= EXERCISE_DEPTH)
                }
                e.stop()  // con esto basta: no seguir gastando batería
                ready.complete(a.lines.first())
                exercise = exercise?.copy(best = a.lines.first())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("Pawneyes", "error en el ejercicio", e)
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** El usuario juega `move`: se compara con la mejor jugada del motor. */
    fun play(move: Move) {
        val ex = exercise ?: return
        if (ex.played != null) return
        exercise = ex.copy(played = move)
        viewModelScope.launch {
            try {
                val best = bestReady.await()
                val verdict = if (best.pv.firstOrNull() == move.uci) {
                    Verdicts.judge(best, null)
                } else {
                    // La jugada del usuario, evaluada a la misma profundidad que la mejor.
                    val e = ensureEngine()
                    e.analyze(ex.position, only = listOf(move), lines = 1, depth = StockfishEngine.DEFAULT_DEPTH)
                    val a = e.analysis.filterNotNull().first {
                        it.position == ex.position && it.only == listOf(move) && it.lines.isNotEmpty() &&
                            (it.done || it.depth >= best.depth)
                    }
                    e.stop()
                    Verdicts.judge(best, a.lines.first())
                }
                exercise = exercise?.takeIf { it.played == move }?.copy(verdict = verdict)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("Pawneyes", "error al comprobar la jugada", e)
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun retryExercise() {
        exercise = exercise?.copy(played = null, verdict = null, showSolution = false)
    }

    /** La pista se queda aunque se reintente: ya se ha visto. */
    fun showHint() {
        exercise = exercise?.takeIf { it.best != null }?.copy(hint = true)
    }

    fun showSolution() {
        exercise = exercise?.copy(showSolution = true)
    }

    fun exitExercise() {
        exerciseJob?.cancel()
        exercise = null
        viewModelScope.launch { runCatching { engine.value?.stop() } }
        update()
    }

    fun onPosition(p: Position) {
        position.value = p
    }

    companion object {
        /** Profundidad a la que se da por buena la jugada del motor en un ejercicio. */
        const val EXERCISE_DEPTH = 18
    }

    override fun onCleared() {
        EngineHolder.close()
    }
}
