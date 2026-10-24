package com.pawneyes.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pawneyes.chess.BookLine
import com.pawneyes.chess.Move
import com.pawneyes.chess.Position

/**
 * Escribir o pegar jugadas del libro (#5), en descriptiva («1. P4R, P4R 2. C3AR») o en
 * algebraica, y recorrerlas desde la posición del tablero.
 */
@Composable
fun BookLineDialog(start: Position, onLoad: (List<Move>) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<BookLine.Result?>(null) }
    val error = result as? BookLine.Result.Error

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.book_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; result = null },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    placeholder = { Text(stringResource(R.string.book_placeholder)) },
                    isError = error != null,
                )
                error?.let { e ->
                    val n = e.index + 1
                    Text(
                        when (val r = e.reason) {
                            BookLine.Reason.Unknown -> stringResource(R.string.book_unknown, n, e.token)
                            BookLine.Reason.Illegal -> stringResource(R.string.book_illegal, n, e.token)
                            is BookLine.Reason.Ambiguous ->
                                stringResource(R.string.book_ambiguous, n, e.token, r.candidates.joinToString(" / "))
                        },
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (error != null && error.moves.isNotEmpty()) {
                // Lo que sí se ha entendido se puede recorrer igualmente.
                TextButton(onClick = { onLoad(error.moves) }) {
                    Text(pluralStringResource(R.plurals.book_load_partial, error.moves.size, error.moves.size))
                }
            } else {
                TextButton(onClick = {
                    val r = BookLine.parse(start, text)
                    result = r
                    if (r is BookLine.Result.Ok && r.moves.isNotEmpty()) onLoad(r.moves)
                }, enabled = text.isNotBlank()) { Text(stringResource(R.string.book_load)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.game_cancel)) } },
    )
}
