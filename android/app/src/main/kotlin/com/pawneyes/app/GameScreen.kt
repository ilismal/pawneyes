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
fun PlaySetupDialog(position: Position, onPlay: (Side, Opponent) -> Unit, onDismiss: () -> Unit) {
    var side by rememberSaveable { mutableStateOf(position.sideToMove) }
    var maia by rememberSaveable { mutableStateOf(true) }
    var maiaLevel by rememberSaveable { mutableStateOf(1500) }
    var stockfishLevel by rememberSaveable { mutableStateOf<Int?>(1600) }

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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onPlay(side, if (maia) Opponent.Maia(maiaLevel) else Opponent.Stockfish(stockfishLevel))
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
    shareButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = game.position
    val result = game.result
    val last = game.moves.lastOrNull()
    val yourTurn = result == null && !thinking && p.sideToMove == human

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(opponentName(opponent), style = MaterialTheme.typography.titleMedium)
        MoveInputBoard(
            p, onMove = onMove, modifier = Modifier.fillMaxWidth(), flipped = human == Side.BLACK,
            enabled = yourTurn, highlighted = last?.let { setOf(it.from, it.to) } ?: emptySet(),
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    error != null -> Text(stringResource(R.string.analysis_error, error), color = MaterialTheme.colorScheme.error)
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
            OutlinedButton(onClick = onUndo, enabled = game.positions.dropLast(1).any { it.sideToMove == human }) { Text(stringResource(R.string.game_undo)) }
            OutlinedButton(onClick = onRestart, enabled = game.moves.isNotEmpty()) { Text(stringResource(R.string.game_restart)) }
            OutlinedButton(onClick = onAnalyse, enabled = !thinking) { Text(stringResource(R.string.game_analyse)) }
            shareButton()
            OutlinedButton(onClick = onExit) { Text(stringResource(R.string.game_exit)) }
        }
    }
}
