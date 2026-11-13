package com.pawneyes.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.pawneyes.chess.Position
import com.pawneyes.chess.Side
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DATE = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag("es"))

/** Historial de escaneos: buscar por etiqueta, reabrir, etiquetar y borrar. */
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    thumbnail: (String) -> File,
    onOpen: (HistoryEntry) -> Unit,
    onRename: (HistoryEntry, String) -> Unit,
    /** Borra al momento; devuelve lo necesario para deshacerlo (#40). */
    onDelete: suspend (HistoryEntry) -> ScanHistory.Deleted?,
    onUndoDelete: (ScanHistory.Deleted) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // Por id, para que los diálogos sigan abiertos al girar el móvil (#35).
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearing by rememberSaveable { mutableStateOf(false) }
    val renaming = entries.find { it.id == renamingId }
    val shown = ScanHistory.search(entries, query)
    // Borrar no pregunta: se avisa unos segundos con «Deshacer» (#40). Vaciar el historial sí.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    val delete = { entry: HistoryEntry ->
        scope.launch {
            val deleted = onDelete(entry) ?: return@launch
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(res.getString(R.string.history_deleted), res.getString(R.string.history_undo),
                duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) onUndoDelete(deleted)
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.history_title, entries.size, entries.size),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (entries.isNotEmpty()) TextButton(onClick = { clearing = true }) { Text(stringResource(R.string.history_clear)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.history_back)) }
            }
            if (entries.isEmpty()) {
                Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.history_search)) }, modifier = Modifier.fillMaxWidth(),
            )
            if (shown.isEmpty()) Text(stringResource(R.string.history_no_match), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { entry ->
                    HistoryRow(entry, thumbnail(entry.id), onOpen = { onOpen(entry) },
                        onRename = { renamingId = entry.id }, onDelete = { delete(entry) })
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    renaming?.let { entry ->
        var label by rememberSaveable(entry.id) { mutableStateOf(entry.label) }
        AlertDialog(
            onDismissRequest = { renamingId = null },
            title = { Text(stringResource(R.string.history_label_title)) },
            text = {
                OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.history_label_hint)) })
            },
            confirmButton = {
                TextButton(onClick = { onRename(entry, label); renamingId = null }) { Text(stringResource(R.string.history_save)) }
            },
            dismissButton = { TextButton(onClick = { renamingId = null }) { Text(stringResource(R.string.game_cancel)) } },
        )
    }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(pluralStringResource(R.plurals.history_clear_title, entries.size, entries.size)) },
            confirmButton = {
                TextButton(onClick = { onClear(); clearing = false }) { Text(stringResource(R.string.history_delete)) }
            },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text(stringResource(R.string.game_cancel)) } },
        )
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, file: File, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, entry) {  // la de una partida cambia al guardarla otra vez
        value = withContext(Dispatchers.IO) { if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null }
    }
    val position = remember(entry.fen) { runCatching { Position.fromFen(entry.fen) }.getOrNull() }
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(72.dp)) {
                image?.let { Image(it, contentDescription = stringResource(R.string.scan_image), modifier = Modifier.size(72.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (entry.label.isNotBlank()) Text(entry.label, style = MaterialTheme.typography.titleSmall)
                else Text(stringResource(R.string.history_no_label), style = MaterialTheme.typography.titleSmall,
                    fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(DATE.format(Date(entry.createdAt)), style = MaterialTheme.typography.bodySmall)
                if (entry.isGame) {
                    val n = (entry.moves.size + 1) / 2
                    Text(pluralStringResource(R.plurals.history_game, n, n),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else position?.let { p ->
                    val pieces = p.board.count { it != null }
                    Text(stringResource(R.string.history_summary,
                        stringResource(if (p.sideToMove == Side.WHITE) R.string.side_white_short else R.string.side_black_short),
                        pluralStringResource(R.plurals.history_pieces, pieces, pieces)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.history_label)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.history_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
