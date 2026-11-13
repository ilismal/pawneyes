package com.pawneyes.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.pawneyes.board.LocalPieceLetters
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawneyes.board.MoveInputBoard
import com.pawneyes.chess.Game
import com.pawneyes.chess.GameResult
import com.pawneyes.chess.Move
import com.pawneyes.chess.Position
import com.pawneyes.chess.Side
import com.pawneyes.maia.MaiaPlayer

/** Elección de bando y rival antes de empezar a jugar desde una posición. */
@Composable
fun PlaySetupDialog(position: Position, onPlay: (Side, Opponent, TimeControl?) -> Unit, onDismiss: () -> Unit) {
    var side by rememberSaveable { mutableStateOf(position.sideToMove) }
    var maia by rememberSaveable { mutableStateOf(true) }
    var maiaLevel by rememberSaveable { mutableStateOf(1500) }
    var stockfishLevel by rememberSaveable { mutableStateOf<Int?>(1600) }
    var clock by rememberSaveable { mutableStateOf<String?>(null) }  // TimeControl.label

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.game_you_play), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(side == Side.WHITE, { side = Side.WHITE }, { Text(stringResource(R.string.game_side_white)) })
                    FilterChip(side == Side.BLACK, { side = Side.BLACK }, { Text(stringResource(R.string.game_side_black)) })
                }
                Text(stringResource(R.string.game_opponent), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(maia, { maia = true }, { Text(stringResource(R.string.game_maia)) })
                    FilterChip(!maia, { maia = false }, { Text(stringResource(R.string.game_stockfish)) })
                }
                Text(stringResource(R.string.game_level), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (maia) {
                        for (level in MaiaPlayer.LEVELS) {
                            FilterChip(maiaLevel == level, { maiaLevel = level }, { Text(stringResource(R.string.game_elo, level)) })
                        }
                    } else {
                        for (level in GameViewModel.STOCKFISH_LEVELS) {
                            FilterChip(stockfishLevel == level, { stockfishLevel = level }, {
                                Text(level?.let { stringResource(R.string.game_elo, it) } ?: stringResource(R.string.game_full_strength))
                            })
                        }
                    }
                }
                Text(stringResource(R.string.game_clock), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(clock == null, { clock = null }, { Text(stringResource(R.string.game_no_clock)) })
                    for (tc in TimeControl.ALL) FilterChip(clock == tc.label, { clock = tc.label }, { Text(tc.label) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onPlay(side, if (maia) Opponent.Maia(maiaLevel) else Opponent.Stockfish(stockfishLevel),
                    TimeControl.ALL.find { it.label == clock })
            }) { Text(stringResource(R.string.game_play)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.game_cancel)) } },
    )
}

@Composable
private fun opponentName(o: Opponent): String = when (o) {
    is Opponent.Maia -> stringResource(R.string.game_title_maia, o.level)
    is Opponent.Stockfish -> o.elo?.let { stringResource(R.string.game_title_stockfish, it) }
        ?: stringResource(R.string.game_title_stockfish_full)
}

/** El resultado como en un PGN: «1-0», «0-1» o «½-½». */
private fun score(result: GameResult): String = when (result) {
    is GameResult.Checkmate -> if (result.winner == Side.WHITE) "1-0" else "0-1"
    else -> "½-½"
}

/** Lo mismo cuando un bando se queda sin tiempo (#49). */
private fun timeoutScore(position: Position, flagged: Side): String = when {
    Clock.timeoutIsDraw(position, flagged) -> "½-½"
    flagged == Side.WHITE -> "0-1"
    else -> "1-0"
}

@Composable
private fun timeoutText(position: Position, flagged: Side, human: Side): String = stringResource(when {
    Clock.timeoutIsDraw(position, flagged) -> R.string.game_timeout_draw
    flagged == human -> R.string.game_timeout_lost
    else -> R.string.game_timeout_won
})

/** El reloj de un bando; resaltado mientras corre y en rojo en los últimos diez segundos. */
@Composable
private fun ClockText(ms: Long, running: Boolean, modifier: Modifier = Modifier) {
    val low = ms < 10_000
    Text(
        Clock.format(ms),
        modifier.background(
            if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            RoundedCornerShape(8.dp),
        ).padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.titleLarge,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (running) FontWeight.Bold else FontWeight.Normal,
        color = if (low) MaterialTheme.colorScheme.error
            else if (running) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun resultText(result: GameResult, human: Side): String = when (result) {
    is GameResult.Checkmate -> stringResource(if (result.winner == human) R.string.game_won else R.string.game_lost)
    GameResult.Stalemate -> stringResource(R.string.game_stalemate)
    GameResult.InsufficientMaterial -> stringResource(R.string.game_insufficient)
    GameResult.FiftyMoves -> stringResource(R.string.game_fifty)
    GameResult.Repetition -> stringResource(R.string.game_repetition)
}

/** Partida contra Stockfish o Maia: el usuario mueve y el rival responde solo. */
@Composable
fun GameScreen(
    game: Game,
    human: Side,
    opponent: Opponent,
    thinking: Boolean,
    error: String?,
    onMove: (Move) -> Unit,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    onAnalyse: () -> Unit,
    onExit: () -> Unit,
    /** Reloj (#49): null, sin reloj; `flagged`, el bando que se quedó sin tiempo. */
    clock: Clock?,
    flagged: Side?,
    /** Guardar en el historial (#47), con la etiqueta que se propone. */
    saved: Boolean,
    onSave: (label: String) -> Unit,
    shareButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = game.position
    val result = game.result
    val last = game.moves.lastOrNull()
    val over = result != null || flagged != null
    val yourTurn = !over && !thinking && p.sideToMove == human

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(opponentName(opponent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            clock?.let { ClockText(it[human.opponent], running = !over && p.sideToMove != human) }
        }
        MoveInputBoard(
            p, onMove = onMove, modifier = Modifier.fillMaxWidth(), flipped = human == Side.BLACK,
            enabled = yourTurn, highlighted = last?.let { setOf(it.from, it.to) } ?: emptySet(),
        )
        clock?.let { ClockText(it[human], running = !over && p.sideToMove == human, Modifier.align(Alignment.End)) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    error != null -> Text(stringResource(R.string.analysis_error, error), color = MaterialTheme.colorScheme.error)
                    flagged != null -> Text(timeoutText(p, flagged, human), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    result != null -> Text(resultText(result, human), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    thinking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.game_thinking))
                    }
                    else -> Text(stringResource(R.string.game_your_turn), style = MaterialTheme.typography.titleMedium)
                }
                val san = game.san(LocalPieceLetters.current)
                Text(if (san.isEmpty()) stringResource(R.string.game_no_moves) else san.joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Con reloj no se deshace (#49).
            OutlinedButton(onClick = onUndo, enabled = clock == null && game.positions.dropLast(1).any { it.sideToMove == human }) {
                Text(stringResource(R.string.game_undo))
            }
            OutlinedButton(onClick = onRestart, enabled = game.moves.isNotEmpty()) { Text(stringResource(R.string.game_restart)) }
            OutlinedButton(onClick = onAnalyse, enabled = !thinking) { Text(stringResource(R.string.game_analyse)) }
            shareButton()
            val outcome = flagged?.let { timeoutScore(p, it) } ?: result?.let(::score)
            val label = opponentName(opponent) + (outcome?.let { " · $it" } ?: "")
            OutlinedButton(onClick = { onSave(label) }, enabled = game.moves.isNotEmpty() && !saved) {
                Text(stringResource(if (saved) R.string.game_saved else R.string.game_save))
            }
            OutlinedButton(onClick = onExit) { Text(stringResource(R.string.game_exit)) }
        }
    }
}
