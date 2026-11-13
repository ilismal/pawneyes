package com.pawneyes.app

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pawneyes.chess.Game
import com.pawneyes.chess.Move
import com.pawneyes.chess.Position
import com.pawneyes.chess.Side
import com.pawneyes.maia.MaiaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Contra quién se juega. */
sealed interface Opponent {
    /** `elo` null = a plena fuerza. */
    data class Stockfish(val elo: Int?) : Opponent

    data class Maia(val level: Int) : Opponent
}

/** Partida en curso desde una posición: el usuario contra Stockfish o Maia. */
class GameViewModel(app: Application) : AndroidViewModel(app) {
    var game by mutableStateOf<Game?>(null)
        private set
    var human by mutableStateOf(Side.WHITE)
        private set
    var opponent by mutableStateOf<Opponent>(Opponent.Maia(1500))
        private set
    var thinking by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Entrada del historial donde se guardó esta partida y con cuántas jugadas (#47). */
    var savedId by mutableStateOf<String?>(null)
        private set
    private var savedMoves by mutableStateOf(-1)
    val saved: Boolean get() = savedId != null && savedMoves == game?.moves?.size

    fun markSaved(id: String, moves: Int) {
        savedId = id
        savedMoves = moves
    }

    /** Reloj de la partida (#49); null, sin reloj. */
    var timeControl by mutableStateOf<TimeControl?>(null)
        private set
    var clock by mutableStateOf<Clock?>(null)
        private set
    /** El bando que se quedó sin tiempo: la partida ha terminado. */
    var flagged by mutableStateOf<Side?>(null)
        private set
    private var foreground = true

    private var maia: MaiaPlayer? = null
    private var job: Job? = null
    private var clockJob: Job? = null

    fun start(position: Position, side: Side, rival: Opponent, timeControl: TimeControl? = null) {
        stopThinking()
        maia?.close()
        maia = (rival as? Opponent.Maia)?.let { MaiaPlayer.load(getApplication(), it.level) }
        human = side
        opponent = rival
        error = null
        savedId = null
        savedMoves = -1
        game = Game(position)
        this.timeControl = timeControl
        clock = timeControl?.let(Clock::of)
        flagged = null
        startClock()
        if (position.sideToMove != side) reply()
    }

    /** En segundo plano el reloj se para (también el del rival). */
    fun setForeground(value: Boolean) {
        foreground = value
    }

    private fun startClock() {
        clockJob?.cancel()
        if (clock == null) return
        clockJob = viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(100)
                val now = SystemClock.elapsedRealtime()
                val g = game
                val c = clock
                if (g != null && c != null && g.result == null && flagged == null && foreground) {
                    val next = c.spend(g.position.sideToMove, now - last)
                    clock = next
                    next.flagged?.let {
                        flagged = it
                        stopThinking()
                    }
                }
                last = now
            }
        }
    }

    fun play(move: Move) {
        val g = game ?: return
        if (thinking || g.result != null || flagged != null || g.position.sideToMove != human) return
        game = g.play(move)
        clock = clock?.moved(human)
        if (game?.result == null) reply()
    }

    /** Deshace hasta que vuelva a tocar al usuario (su jugada y la respuesta del rival). */
    fun undo() {
        if (clock != null) return  // con reloj no se deshace
        stopThinking()
        var g = game ?: return
        do {
            g = g.undo()
        } while (g.moves.isNotEmpty() && g.position.sideToMove != human)
        game = g
        if (g.position.sideToMove != human) reply()  // al principio, si empezaba el rival
    }

    fun restart() {
        game?.let { start(it.start, human, opponent, timeControl) }
    }

    fun exit() {
        stopThinking()
        clockJob?.cancel()
        clock = null
        flagged = null
        game = null
        maia?.close()
        maia = null
    }

    private fun stopThinking() {
        job?.cancel()
        thinking = false
    }

    private fun reply() {
        val g = game ?: return
        thinking = true
        job = viewModelScope.launch {
            try {
                val move = when (val o = opponent) {
                    // Maia responde en milisegundos: una pausa breve para que se vea jugar.
                    is Opponent.Maia -> withContext(Dispatchers.Default) {
                        delay(400)
                        maia!!.choose(g.position, temperature = MAIA_TEMPERATURE)
                    }
                    is Opponent.Stockfish -> EngineHolder.get(getApplication()).bestMove(g.position, o.elo, THINK_MS)
                }
                if (move != null && game === g && flagged == null) {
                    game = g.play(move)
                    clock = clock?.moved(g.position.sideToMove)
                }
                thinking = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // deshacer o salir: ya lo gestiona stopThinking()
            } catch (e: Exception) {
                Log.e("Pawneyes", "error del rival", e)
                error = e.message ?: e.javaClass.simpleName
                thinking = false
            }
        }
    }

    override fun onCleared() {
        maia?.close()
    }

    companion object {
        /** Algo de variedad, como un humano, sin jugadas improbables (Maia ignora las de menos del 1 %). */
        const val MAIA_TEMPERATURE = 0.6
        const val THINK_MS = 800L
        val STOCKFISH_LEVELS: List<Int?> = listOf(1350, 1600, 1900, 2200, 2600, null)
    }
}
