package com.pawneyes.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawneyes.board.ChessBoard
import com.pawneyes.chess.Line
import com.pawneyes.chess.Position

/** Una línea del motor que se está recorriendo y la jugada a la que se ha llegado. */
class Exploration(val line: Line, val index: Int) {
    val position: Position get() = line.positions[index]
    fun at(i: Int) = Exploration(line, i.coerceIn(0, line.size))
}

/**
 * Recorre una línea jugada a jugada. El análisis (`analysisPanel`) sigue la
 * posición que se está viendo, así que se puede seguir explorando desde ahí.
 */
@Composable
fun LineExplorer(
    exploration: Exploration,
    flipped: Boolean,
    arrows: List<Pair<Int, Int>>,
    onStep: (Int) -> Unit,
    onEdit: () -> Unit,
    onClose: () -> Unit,
    onSharePgn: () -> Unit,
    onShareImage: () -> Unit,
    modifier: Modifier = Modifier,
    analysisPanel: @Composable () -> Unit,
) {
    val line = exploration.line
    val i = exploration.index
    val last = line.moves.getOrNull(i - 1)
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.explore_title), style = MaterialTheme.typography.titleSmall)
        ChessBoard(
            exploration.position,
            Modifier.fillMaxWidth(),
            flipped = flipped,
            arrows = arrows,
            highlighted = last?.let { setOf(it.from, it.to) } ?: emptySet(),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = { onStep(0) }, enabled = i > 0) { Text("⏮") }
            FilledTonalIconButton(onClick = { onStep(i - 1) }, enabled = i > 0) { Text("◀") }
            Text("$i / ${line.size}", style = MaterialTheme.typography.titleMedium)
            FilledTonalIconButton(onClick = { onStep(i + 1) }, enabled = i < line.size) { Text("▶") }
            FilledTonalIconButton(onClick = { onStep(line.size) }, enabled = i < line.size) { Text("⏭") }
        }
        // La línea entera, con la jugada actual en negrita.
        Text(
            buildAnnotatedString {
                if (i == 0) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.explore_start)) }
                line.san.forEachIndexed { k, san ->
                    append(if (k == 0 && i != 0) "" else "  ")
                    val text = if (k > 0 && san.contains("...")) san.substringAfter("... ") else san
                    if (k == i - 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) { append(text) }
                    else append(text)
                }
            },
            Modifier.horizontalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        analysisPanel()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClose) { Text(stringResource(R.string.explore_back)) }
            Button(onClick = onEdit) { Text(stringResource(R.string.explore_edit)) }
            ShareButton(onSharePgn, onShareImage)
        }
    }
}
