package com.pawneyes.board

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pawneyes.chess.Move
import com.pawneyes.chess.Piece
import com.pawneyes.chess.PieceType
import com.pawneyes.chess.Position
import com.pawneyes.chess.legalMoves

/**
 * Tablero para jugar: tocar una pieza propia muestra sus destinos legales y
 * tocar uno juega la jugada. En las coronaciones se elige la pieza.
 */
@Composable
fun MoveInputBoard(
    position: Position,
    onMove: (Move) -> Unit,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    enabled: Boolean = true,
    arrows: List<Pair<Int, Int>> = emptyList(),
    highlighted: Set<Int> = emptySet(),
) {
    var selected by remember(position) { mutableStateOf<Int?>(null) }
    var promotion by remember(position) { mutableStateOf<List<Move>?>(null) }
    val legal = remember(position) { position.legalMoves() }
    val targets = selected?.let { s -> legal.filter { it.from == s }.mapTo(mutableSetOf()) { it.to } }.orEmpty()

    fun tap(square: Int) {
        val from = selected
        val candidates = if (from != null) legal.filter { it.from == from && it.to == square } else emptyList()
        when {
            candidates.size > 1 -> promotion = candidates      // coronación: hay que elegir pieza
            candidates.size == 1 -> {
                selected = null
                onMove(candidates.first())
            }
            position[square]?.side == position.sideToMove -> selected = if (square == from) null else square
            else -> selected = null
        }
    }

    ChessBoard(
        position,
        modifier,
        flipped = flipped,
        arrows = arrows,
        highlighted = highlighted + listOfNotNull(selected),
        dots = targets,
        onSquareClick = if (enabled) ::tap else null,
    )

    promotion?.let { options ->
        AlertDialog(
            onDismissRequest = { promotion = null },
            title = { Text(stringResource(R.string.promotion_title)) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (type in listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)) {
                        val move = options.first { it.promotion == type }
                        Image(
                            painterResource(LocalBoardStyle.current.pieces.drawable(Piece(position.sideToMove, type))),
                            contentDescription = type.name,
                            modifier = Modifier.size(56.dp).clickable {
                                promotion = null
                                selected = null
                                onMove(move)
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { promotion = null }) { Text(stringResource(R.string.promotion_cancel)) } },
        )
    }
}
