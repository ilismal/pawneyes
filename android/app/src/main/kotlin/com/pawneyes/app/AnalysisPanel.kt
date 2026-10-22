package com.pawneyes.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawneyes.chess.Move
import com.pawneyes.chess.Position
import com.pawneyes.chess.Side
import com.pawneyes.chess.isLegal
import com.pawneyes.chess.lineToSan
import com.pawneyes.engine.Analysis
import com.pawneyes.engine.EngineLine
import java.util.Locale

/** Mejor jugada del análisis actual, como flecha para el tablero. */
fun Analysis?.bestArrow(position: Position): List<Pair<Int, Int>> {
    if (this == null || this.position != position) return emptyList()
    val move = lines.firstOrNull()?.pv?.firstOrNull()?.let(Move::fromUci) ?: return emptyList()
    return listOf(move.from to move.to)
}

/** Puntuación desde el punto de vista de las blancas: «+0,27», «−1,50», «M3», «−M2». */
private fun EngineLine.whiteScore(sideToMove: Side): String {
    val sign = if (sideToMove == Side.WHITE) 1 else -1
    mate?.let { m ->
        val forWhite = m * sign
        return (if (forWhite > 0) "M" else "−M") + kotlin.math.abs(forWhite)
    }
    val pawns = (cp ?: 0) * sign / 100.0
    val text = String.format(Locale.forLanguageTag("es"), "%.2f", kotlin.math.abs(pawns))
    return (if (pawns >= 0) "+" else "−") + text
}

@Composable
private fun headline(line: EngineLine, sideToMove: Side): String {
    val mate = line.mate ?: return line.whiteScore(sideToMove)
    val winner = if ((mate > 0) == (sideToMove == Side.WHITE)) R.string.side_white_short else R.string.side_black_short
    return stringResource(R.string.analysis_mate, kotlin.math.abs(mate), stringResource(winner))
}

@Composable
fun AnalysisPanel(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    position: Position,
    analysis: Analysis?,
    error: String?,
    onLineClick: (EngineLine) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = enabled, onCheckedChange = onToggle)
            Text(stringResource(R.string.analysis_toggle), style = MaterialTheme.typography.titleSmall)
        }
        if (!enabled) return@Column
        val current = analysis?.takeIf { it.position == position && it.lines.isNotEmpty() }
        when {
            error != null -> Text(stringResource(R.string.analysis_error, error), color = MaterialTheme.colorScheme.error)
            !position.isLegal() -> Text(stringResource(R.string.analysis_illegal), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            current == null -> Text(stringResource(R.string.analysis_thinking), style = MaterialTheme.typography.bodySmall)
            else -> {
                val side = position.sideToMove
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(headline(current.lines.first(), side), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.analysis_depth, current.depth), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
                Text(stringResource(R.string.analysis_tap_line), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (line in current.lines) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onLineClick(line) }.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(line.whiteScore(side), Modifier.width(56.dp), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Text(position.lineToSan(line.pv.take(10)), style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
