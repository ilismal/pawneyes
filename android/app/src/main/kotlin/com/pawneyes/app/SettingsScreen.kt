package com.pawneyes.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pawneyes.board.BoardTheme
import com.pawneyes.board.ChessBoard
import com.pawneyes.board.LocalBoardStyle
import com.pawneyes.board.PieceSet
import com.pawneyes.chess.Move
import com.pawneyes.chess.Piece
import com.pawneyes.chess.PieceLetters
import com.pawneyes.chess.PieceType
import com.pawneyes.chess.Position
import com.pawneyes.chess.Side
import com.pawneyes.chess.lineToSan

private fun themeName(t: BoardTheme): Int = when (t) {
    BoardTheme.WOOD -> R.string.settings_theme_wood
    BoardTheme.GREEN -> R.string.settings_theme_green
    BoardTheme.BLUE -> R.string.settings_theme_blue
    BoardTheme.DARK -> R.string.settings_theme_dark
}

private fun piecesName(p: PieceSet): Int = when (p) {
    PieceSet.CHESSNUT -> R.string.settings_pieces_chessnut
    PieceSet.CBURNETT -> R.string.settings_pieces_cburnett
    PieceSet.MERIDA -> R.string.settings_pieces_merida
    PieceSet.FANTASY -> R.string.settings_pieces_fantasy
}

/** Ajustes (#11): colores del tablero, piezas, coordenadas y notación, con una vista previa. */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Vista previa: una Española con la última jugada marcada.
    val sample = remember {
        Position.fromFen("r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3")
    }
    val line = listOf("a7a6", "b5a4", "g8f6")
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text(stringResource(R.string.history_back)) }
        }
        CompositionLocalProvider(LocalBoardStyle provides settings.style) {
            ChessBoard(
                sample, Modifier.widthIn(max = 320.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                highlighted = setOf(Move.fromUci("f1b5")!!.from, Move.fromUci("f1b5")!!.to),
                arrows = listOf(Move.fromUci("a7a6")!!.let { it.from to it.to }),
            )
        }
        Text(sample.lineToSan(line, settings.letters), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally))

        Section(stringResource(R.string.settings_theme)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in BoardTheme.entries) {
                    FilterChip(
                        selected = settings.style.theme == t,
                        onClick = { onChange { it.copy(style = it.style.copy(theme = t)) } },
                        label = { Text(stringResource(themeName(t))) },
                        leadingIcon = { Swatch(t) },
                    )
                }
            }
        }
        Section(stringResource(R.string.settings_pieces)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in PieceSet.entries) {
                    FilterChip(
                        selected = settings.style.pieces == p,
                        onClick = { onChange { it.copy(style = it.style.copy(pieces = p)) } },
                        label = { Text(stringResource(piecesName(p))) },
                        leadingIcon = {
                            Image(painterResource(p.drawable(Piece(Side.WHITE, PieceType.KNIGHT))), contentDescription = null,
                                modifier = Modifier.size(24.dp))
                        },
                    )
                }
            }
            Text(stringResource(R.string.settings_pieces_credit, settings.style.pieces.credit),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Section(stringResource(R.string.settings_notation)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(settings.letters == PieceLetters.SPANISH,
                    { onChange { it.copy(letters = PieceLetters.SPANISH) } },
                    { Text(stringResource(R.string.settings_notation_spanish)) })
                FilterChip(settings.letters == PieceLetters.ENGLISH,
                    { onChange { it.copy(letters = PieceLetters.ENGLISH) } },
                    { Text(stringResource(R.string.settings_notation_english)) })
            }
            Text(stringResource(R.string.settings_notation_hint), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_coordinates), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f))
            Switch(checked = settings.style.coordinates,
                onCheckedChange = { on -> onChange { it.copy(style = it.style.copy(coordinates = on)) } })
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

/** Muestra de dos casillas del tema. */
@Composable
private fun Swatch(theme: BoardTheme) {
    Row(Modifier.size(width = 24.dp, height = 12.dp).clip(RoundedCornerShape(3.dp))) {
        Box(Modifier.size(12.dp).background(theme.light))
        Box(Modifier.size(12.dp).background(theme.dark))
    }
}
