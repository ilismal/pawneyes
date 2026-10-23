package com.pawneyes.app

import android.content.Context
import com.pawneyes.engine.StockfishEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Un único Stockfish para toda la app: lo comparten el análisis y las partidas. */
object EngineHolder {
    private val mutex = Mutex()
    private var engine: StockfishEngine? = null

    suspend fun get(context: Context): StockfishEngine = mutex.withLock {
        engine ?: StockfishEngine.start(context.applicationContext).also { engine = it }
    }

    /** El motor si ya está arrancado (para pararlo sin arrancarlo). */
    fun current(): StockfishEngine? = engine

    fun close() {
        engine?.close()
        engine = null
    }
}
