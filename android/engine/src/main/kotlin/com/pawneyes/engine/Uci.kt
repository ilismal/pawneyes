package com.pawneyes.engine

/**
 * Una línea de análisis. La puntuación va desde el punto de vista del bando
 * que mueve, como la da UCI: `cp` en centipeones o `mate` en jugadas (negativo
 * si es el bando que mueve el que recibe mate).
 */
data class EngineLine(
    val multipv: Int,
    val depth: Int,
    val cp: Int?,
    val mate: Int?,
    val pv: List<String>,
)

object Uci {
    /** Interpreta «info … score … pv …»; el resto de mensajes devuelve null. */
    fun parseInfo(line: String): EngineLine? {
        val t = line.trim().split(Regex("\\s+"))
        if (t.firstOrNull() != "info" || "pv" !in t || "score" !in t) return null
        var multipv = 1
        var depth = 0
        var cp: Int? = null
        var mate: Int? = null
        var i = 1
        while (i < t.size) {
            when (t[i]) {
                "multipv" -> multipv = t.getOrNull(++i)?.toIntOrNull() ?: return null
                "depth" -> depth = t.getOrNull(++i)?.toIntOrNull() ?: return null
                "score" -> when (t.getOrNull(++i)) {
                    "cp" -> cp = t.getOrNull(++i)?.toIntOrNull() ?: return null
                    "mate" -> mate = t.getOrNull(++i)?.toIntOrNull() ?: return null
                    else -> return null
                }
                "pv" -> return EngineLine(multipv, depth, cp, mate, t.subList(i + 1, t.size))
            }
            i++
        }
        return null
    }
}
