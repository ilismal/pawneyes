package com.pawneyes.engine

import android.content.Context
import android.util.Log
import com.pawneyes.chess.Position
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Estado del análisis de una posición: las mejores líneas, ordenadas. */
data class Analysis(val position: Position, val lines: List<EngineLine>) {
    val depth: Int get() = lines.firstOrNull()?.depth ?: 0
}

/**
 * Stockfish ejecutándose como proceso aparte, hablando UCI por stdin/stdout.
 * El binario va en jniLibs como libstockfish.so: es el único sitio desde el
 * que Android deja ejecutar código nativo propio.
 */
class StockfishEngine private constructor(private val process: Process) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = process.outputStream.bufferedWriter()
    private val mutex = Mutex()
    private val handshake = Channel<String>(Channel.UNLIMITED)  // uciok, readyok
    private val bestmoves = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var current: Position? = null

    private val _analysis = MutableStateFlow<Analysis?>(null)
    val analysis: StateFlow<Analysis?> = _analysis.asStateFlow()

    @Volatile
    private var closing = false

    init {
        scope.launch {
            try {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach(::onLine) }
            } catch (e: java.io.IOException) {
                if (!closing) Log.w("Stockfish", "salida del motor interrumpida", e)
            }
        }
    }

    private fun onLine(line: String) {
        when {
            line == "uciok" || line == "readyok" -> handshake.trySend(line)
            line.startsWith("bestmove") -> bestmoves.trySend(Unit)
            else -> {
                val info = Uci.parseInfo(line) ?: return
                val position = current ?: return
                val previous = _analysis.value?.takeIf { it.position == position }?.lines.orEmpty()
                // Cada multipv llega por separado: se sustituye la de su número.
                val lines = (previous.filter { it.multipv != info.multipv } + info).sortedBy { it.multipv }
                _analysis.value = Analysis(position, lines)
            }
        }
    }

    private fun send(command: String) {
        writer.write(command)
        writer.newLine()
        writer.flush()
    }

    private suspend fun await(reply: String) {
        withTimeoutOrNull(10_000) {
            // Descarta cualquier otra respuesta hasta la esperada.
            while (handshake.receive() != reply) continue
        } ?: error("Stockfish no responde («$reply»)")
    }

    private suspend fun configure(threads: Int, hashMb: Int, lines: Int) = withContext(Dispatchers.IO) {
        send("uci")
        await("uciok")
        send("setoption name Threads value $threads")
        send("setoption name Hash value $hashMb")
        send("setoption name MultiPV value $lines")
        send("isready")
        await("readyok")
    }

    /** Para la búsqueda en curso, si la hay, y espera a que termine del todo. */
    private suspend fun stopSearch() {
        if (current == null) return
        send("stop")
        withTimeoutOrNull(3_000) { bestmoves.receive() }
        current = null
    }

    /** Analiza `position` indefinidamente; los resultados llegan por [analysis]. */
    suspend fun analyze(position: Position) = withContext(Dispatchers.IO) {
        mutex.withLock {
            stopSearch()
            bestmoves.tryReceive()  // descarta un bestmove viejo, si quedó alguno
            _analysis.value = Analysis(position, emptyList())
            current = position
            send("position fen ${position.toFen()}")
            send("go infinite")
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) { mutex.withLock { stopSearch() } }

    override fun close() {
        closing = true
        runCatching { send("quit") }
        scope.cancel()
        process.destroy()
    }

    companion object {
        private const val BINARY = "libstockfish.so"

        /** Hay motor para este dispositivo (solo se incluye para arm64). */
        fun isAvailable(context: Context): Boolean = binary(context).canExecute()

        private fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, BINARY)

        suspend fun start(context: Context, lines: Int = 3): StockfishEngine {
            val exe = binary(context)
            check(exe.canExecute()) { "Stockfish no está disponible en este dispositivo" }
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(exe.path).redirectErrorStream(true).start()
            }
            val engine = StockfishEngine(process)
            // Deja núcleos libres para la interfaz; la tabla hash, modesta para el móvil.
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)
            try {
                engine.configure(threads, hashMb = 64, lines = lines)
            } catch (e: Exception) {
                engine.close()
                throw e
            }
            Log.i("Stockfish", "listo: $threads hilos, $lines líneas")
            return engine
        }
    }
}
