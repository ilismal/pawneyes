package com.pawneyes.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.pawneyes.board.LocalPieceLetters
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawneyes.board.MoveInputBoard
import com.pawneyes.chess.Move
import com.pawneyes.chess.Side
import com.pawneyes.chess.lineToSan
import com.pawneyes.chess.play
import com.pawneyes.chess.toSan
import com.pawneyes.engine.Grade
import java.util.Locale

private fun gradeText(grade: Grade): Int = when (grade) {
    Grade.BEST -> R.string.exercise_best
    Grade.GOOD -> R.string.exercise_good
    Grade.INACCURACY -> R.string.exercise_inaccuracy
    Grade.MISTAKE -> R.string.exercise_mistake
    Grade.BLUNDER -> R.string.exercise_blunder
}

private fun gradeColor(grade: Grade): Color = when (grade) {
    Grade.BEST, Grade.GOOD -> Color(0xFF2E7D32)
    Grade.INACCURACY -> Color(0xFFB7791F)
    Grade.MISTAKE -> Color(0xFFD9480F)
    Grade.BLUNDER -> Color(0xFFC62828)
}

/**
 * Modo ejercicio: el usuario busca la mejor jugada sin ver la evaluación; la app
 * la compara con la de Stockfish y da un veredicto.
 */
@Composable
fun ExerciseScreen(
    exercise: Exercise,
    error: String?,
    onMove: (Move) -> Unit,
    onRetry: () -> Unit,
    onSolution: () -> Unit,
    onHint: () -> Unit,
    onExploreSolution: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = exercise.position
    val played = exercise.played
    val best = exercise.best
    val bestMove = best?.pv?.firstOrNull()?.let(Move::fromUci)
    // El tablero se mira desde el bando que juega.
    val flipped = p.sideToMove == Side.BLACK
    val side = stringResource(if (p.sideToMove == Side.WHITE) R.string.side_white_short else R.string.side_black_short)

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.exercise_title, side), style = MaterialTheme.typography.titleMedium)
        when {
            // Solución: posición de partida con la flecha de la mejor jugada.
            exercise.showSolution && bestMove != null -> MoveInputBoard(
                p, onMove = {}, modifier = Modifier.fillMaxWidth(), flipped = flipped, enabled = false,
                arrows = listOf(bestMove.from to bestMove.to),
            )
            played != null -> MoveInputBoard(
                p.play(played), onMove = {}, modifier = Modifier.fillMaxWidth(), flipped = flipped, enabled = false,
                highlighted = setOf(played.from, played.to),
            )
            // Con pista, la casilla de la pieza que hay que mover queda resaltada.
            else -> MoveInputBoard(p, onMove = onMove, modifier = Modifier.fillMaxWidth(), flipped = flipped,
                highlighted = if (exercise.hint && bestMove != null) setOf(bestMove.from) else emptySet())
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    error != null -> Text(stringResource(R.string.analysis_error, error), color = MaterialTheme.colorScheme.error)
                    played == null -> {
                        Text(stringResource(R.string.exercise_prompt), style = MaterialTheme.typography.titleMedium)
                        if (exercise.hint) Text(stringResource(R.string.exercise_hint_shown), style = MaterialTheme.typography.bodySmall)
                        if (best == null) Busy(stringResource(R.string.exercise_preparing))
                    }
                    exercise.verdict == null -> Busy(stringResource(R.string.exercise_checking, p.toSan(played, LocalPieceLetters.current)))
                    else -> {
                        val v = exercise.verdict
                        Text(stringResource(gradeText(v.grade)), style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, color = gradeColor(v.grade))
                        if (exercise.hint) Text(stringResource(R.string.exercise_with_hint), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.exercise_you_played, p.toSan(played, LocalPieceLetters.current)))
                        when {
                            v.missedMate -> Text(stringResource(R.string.exercise_missed_mate))
                            v.allowsMate -> Text(stringResource(R.string.exercise_allows_mate))
                            v.grade != Grade.BEST && v.loss < 10_000 ->
                                Text(stringResource(R.string.exercise_loss, String.format(Locale.forLanguageTag("es"), "%.2f", v.loss / 100.0)))
                        }
                    }
                }
                if (exercise.showSolution && best != null) {
                    Text(stringResource(R.string.exercise_solution), style = MaterialTheme.typography.titleSmall)
                    Text(p.lineToSan(best.pv.take(10), LocalPieceLetters.current), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (played != null && exercise.verdict != null) OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.exercise_retry)) }
            if (!exercise.showSolution) {
                if (played == null && !exercise.hint) {
                    OutlinedButton(onClick = onHint, enabled = best != null) { Text(stringResource(R.string.exercise_hint)) }
                }
                OutlinedButton(onClick = onSolution, enabled = best != null) { Text(stringResource(R.string.exercise_show_solution)) }
            } else {
                Button(onClick = onExploreSolution) { Text(stringResource(R.string.exercise_explore)) }
            }
            OutlinedButton(onClick = onExit) { Text(stringResource(R.string.exercise_exit)) }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
