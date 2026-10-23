package com.pawneyes.engine

/** Calificación de una jugada frente a la mejor del motor (modo ejercicio). */
enum class Grade { BEST, GOOD, INACCURACY, MISTAKE, BLUNDER }

/**
 * @property loss lo que se pierde respecto a la mejor jugada, en centipeones
 *   (desde el bando que mueve; 0 si es igual de buena). Con mates se usa una
 *   escala convencional, así que solo tiene sentido mostrarla sin mates.
 * @property missedMate había un mate forzado y la jugada no lo mantiene.
 * @property allowsMate tras la jugada, el rival tiene mate forzado (y antes no lo tenía).
 */
data class Verdict(val grade: Grade, val loss: Int, val missedMate: Boolean, val allowsMate: Boolean = false)

object Verdicts {
    private const val MATE = 30_000

    /** Puntuación comparable desde el bando que mueve: los mates, por encima de todo. */
    fun score(line: EngineLine): Int = when {
        line.mate == null -> line.cp ?: 0
        line.mate > 0 -> MATE - line.mate          // mate a favor: antes es mejor
        else -> -MATE - line.mate                  // mate en contra: cuanto más tarde, menos malo
    }

    /**
     * `best`: mejor línea del motor en la posición. `mine`: la línea restringida a la
     * jugada del usuario (UCI `searchmoves`), o null si la jugada es la mejor.
     */
    fun judge(best: EngineLine, mine: EngineLine?): Verdict {
        if (mine == null) return Verdict(Grade.BEST, 0, missedMate = false)
        val loss = (score(best) - score(mine)).coerceAtLeast(0)
        val bestMates = (best.mate ?: 0) > 0
        val keepsMate = (mine.mate ?: 0) > 0
        if (bestMates && !keepsMate) {
            // Se deja escapar un mate: grave salvo que siga ganando con claridad.
            val stillWinning = (mine.cp ?: 0) >= 500
            return Verdict(if (stillWinning) Grade.MISTAKE else Grade.BLUNDER, loss, missedMate = true)
        }
        if (bestMates && keepsMate) {
            // Dos mates: vale si no tarda mucho más que el mejor.
            val extra = mine.mate!! - best.mate!!
            return Verdict(if (extra <= 0) Grade.BEST else if (extra <= 2) Grade.GOOD else Grade.INACCURACY, loss, false)
        }
        val allowsMate = (mine.mate ?: 0) < 0 && (best.mate ?: 0) >= 0
        if (allowsMate) return Verdict(Grade.BLUNDER, loss, missedMate = false, allowsMate = true)
        val grade = when {
            loss <= 20 -> Grade.BEST
            loss <= 60 -> Grade.GOOD
            loss <= 150 -> Grade.INACCURACY
            loss <= 300 -> Grade.MISTAKE
            else -> Grade.BLUNDER
        }
        return Verdict(grade, loss, missedMate = false)
    }
}
