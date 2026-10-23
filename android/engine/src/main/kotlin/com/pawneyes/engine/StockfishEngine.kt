package com.pawneyes.engine

import android.content.Context
import android.util.Log
import com.pawneyes.chess.Move
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

/**
 * Estado del análisis de una posición: las mejores líneas, ordenadas, y si el
 * motor ya ha terminado (llegó a la profundidad máxima o se agotó la búsqueda).
 */
data class Analysis(
    val position: Position,
    val lines: List<EngineLine>,
    val done: Boolean = false,
    /** Jugadas a las que se restringió la búsqueda (UCI `searchmoves`); vacío = todas. */
    val only: List<Move> = emptyList(),
) {
    val depth: Int get() = lines.firstOrNull()?.depth ?: 0
}

/**
 * Stockfish ejecutándose como proceso aparte, hablando UCI por stdin/stdout.
 * El binario va en jniLibs como libstockfish.so: es el único sitio desde el
 * que Android deja ejecutar código nativo propio.
 */
class StockfishEngine private constructor(
    private val process: Process,
    private val maxDepth: Int,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = process.outputStream.bufferedWriter()
    private val mutex = Mutex()
    private val handshake = Channel<String>(Channel.UNLIMITED)  // uciok, readyok
    private val bestmoves = Channel<String>(Channel.CONFLATED)  // la jugada de cada «bestmove»

    /** Líneas de análisis configuradas (se restauran al volver de jugar). */
    private var lines = 3

    /** true tras pedir una jugada para jugar: hay que restaurar la configuración de análisis. */
    private var playMode = false

    @Volatile
    private var current: Position? = null

    @Volatile
    private var currentOnly: List<Move> = emptyList()

    /** Búsqueda (posición y restricción) que ya se ha pedido parar por haber encontrado mate. */
    @Volatile
    private var stopping: Pair<Position, List<Move>>? = null

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
            line.startsWith("bestmove") -> {
                // Fin de la búsqueda: si era la de la posición actual, se marca terminada.
                val position = current
                _analysis.value?.takeIf { it.position == position && it.only == currentOnly }
                    ?.let { _analysis.value = it.copy(done = true) }
                bestmoves.trySend(line.split(" ").getOrElse(1) { "(none)" })
            }
            else -> {
                val info = Uci.parseInfo(line) ?: return
                val position = current ?: return
                val only = currentOnly
                val previous = _analysis.value?.takeIf { it.position == position && it.only == only }
                // Cada multipv llega por separado: se sustituye la de su número.
                val lines = (previous?.lines.orEmpty().filter { it.multipv != info.multipv } + info).sortedBy { it.multipv }
                _analysis.value = Analysis(position, lines, previous?.done ?: false, only)
                // Con un mate forzado ya confirmado, seguir afinando las demás líneas
                // gasta batería sin aportar nada: se termina la búsqueda.
                val best = lines.first()
                if (best.mate != null && best.depth >= MATE_CONFIRM_DEPTH && stopping != (position to only)) {
                    stopping = position to only
                    send("stop")
                }
            }
        }
    }

    // Se escribe desde el hilo lector (parada por mate) y desde analyze/stop.
    private fun send(command: String) = synchronized(writer) {
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

    private suspend fun configure(threads: Int, hashMb: Int, lines: Int, net: File) = withContext(Dispatchers.IO) {
        send("uci")
        await("uciok")
        send("setoption name EvalFile value ${net.path}")
        send("setoption name Threads value $threads")
        send("setoption name Hash value $hashMb")
        send("setoption name MultiPV value $lines")
        this@StockfishEngine.lines = lines
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

    /**
     * Analiza `position` hasta la profundidad máxima; los resultados llegan por
     * [analysis]. No se usa `go infinite`: si la búsqueda se agota antes (un mate,
     * un final sencillo), UCI obliga al motor a esperar un `stop` y Stockfish lo
     * hace en un bucle activo, con un núcleo al 100 % sin hacer nada útil.
     */
    suspend fun analyze(position: Position, only: List<Move> = emptyList()) = withContext(Dispatchers.IO) {
        mutex.withLock {
            stopSearch()
            bestmoves.tryReceive()  // descarta un bestmove viejo, si quedó alguno
            if (playMode) {
                // Vuelta al análisis tras jugar: todas las líneas y a plena fuerza.
                send("setoption name UCI_LimitStrength value false")
                send("setoption name MultiPV value $lines")
                playMode = false
            }
            _analysis.value = Analysis(position, emptyList(), only = only)
            currentOnly = only
            current = position
            send("position fen ${position.toFen()}")
            val restrict = if (only.isEmpty()) "" else " searchmoves " + only.joinToString(" ") { it.uci }
            send("go depth $maxDepth$restrict")
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) { mutex.withLock { stopSearch() } }

    /**
     * Jugada de Stockfish como rival: piensa `movetimeMs` y, con `elo`, juega con la
     * fuerza limitada a ese Elo (UCI_LimitStrength + UCI_Elo, de 1320 a 3190).
     * Null si no hay jugada (mate o ahogado).
     */
    suspend fun bestMove(position: Position, elo: Int?, movetimeMs: Long = 1000): Move? = withContext(Dispatchers.IO) {
        mutex.withLock {
            stopSearch()
            bestmoves.tryReceive()
            send("setoption name MultiPV value 1")
            if (elo != null) {
                send("setoption name UCI_LimitStrength value true")
                send("setoption name UCI_Elo value ${elo.coerceIn(MIN_ELO, MAX_ELO)}")
            } else {
                send("setoption name UCI_LimitStrength value false")
            }
            playMode = true
            send("position fen ${position.toFen()}")
            send("go movetime $movetimeMs")
            val reply = withTimeoutOrNull(movetimeMs + 10_000) { bestmoves.receive() }
                ?: error("Stockfish no ha respondido con una jugada")
            Move.fromUci(reply)
        }
    }

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

        /** Profundidad máxima por defecto: de sobra para posiciones de libro, y acotada en batería. */
        const val DEFAULT_DEPTH = 40

        /** Rango de UCI_Elo de Stockfish. */
        const val MIN_ELO = 1320
        const val MAX_ELO = 3190

        /** Profundidad a la que se da por bueno un mate y se para la búsqueda. */
        const val MATE_CONFIRM_DEPTH = 20

        /**
         * La red neuronal va como asset (no dentro del binario): se copia a disco la
         * primera vez y tras cada actualización de la app, porque Stockfish la lee de un fichero.
         */
        private fun network(context: Context): File {
            val name = context.assets.list("").orEmpty().single { it.startsWith("nn-") && it.endsWith(".nnue") }
            val dir = File(context.noBackupFilesDir, "stockfish")
            val file = File(dir, name)
            val marker = File(dir, "version")
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            if (!file.exists() || !marker.exists() || marker.readText() != installed) {
                dir.mkdirs()
                dir.listFiles()?.filter { it.name.endsWith(".nnue") }?.forEach { it.delete() }  // redes de versiones anteriores
                val tmp = File(dir, "$name.tmp")
                context.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                check(tmp.renameTo(file)) { "no se pudo guardar la red de Stockfish" }
                marker.writeText(installed)
            }
            return file
        }

        suspend fun start(context: Context, lines: Int = 3, maxDepth: Int = DEFAULT_DEPTH): StockfishEngine {
            val exe = binary(context)
            check(exe.canExecute()) { "Stockfish no está disponible en este dispositivo" }
            val net = withContext(Dispatchers.IO) { network(context) }
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(exe.path).redirectErrorStream(true).start()
            }
            val engine = StockfishEngine(process, maxDepth)
            // Deja núcleos libres para la interfaz; la tabla hash, modesta para el móvil.
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)
            try {
                engine.configure(threads, hashMb = 64, lines = lines, net = net)
            } catch (e: Exception) {
                engine.close()
                throw e
            }
            Log.i("Stockfish", "listo: $threads hilos, $lines líneas")
            return engine
        }
    }
}
