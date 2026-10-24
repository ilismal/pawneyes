package com.pawneyes.board

import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pawneyes.chess.Castling
import com.pawneyes.chess.FenException
import com.pawneyes.chess.Issue
import com.pawneyes.chess.Piece
import com.pawneyes.chess.PieceType
import com.pawneyes.chess.Position
import com.pawneyes.chess.Side
import com.pawneyes.chess.Square
import com.pawneyes.chess.issues
import kotlinx.coroutines.launch

sealed interface EditTool {
    data class Place(val piece: Piece) : EditTool
    data object Erase : EditTool
}

@Stable
class BoardEditorState(initial: Position, flipped: Boolean = false, marked: Set<Int> = emptySet()) {
    var position by mutableStateOf(initial)
        private set
    var tool: EditTool by mutableStateOf(EditTool.Place(Piece(Side.WHITE, PieceType.PAWN)))
    var flipped by mutableStateOf(flipped)

    /** Casillas resaltadas (las dudosas del escáner); se desmarcan al tocarlas. */
    var marked by mutableStateOf(marked)
        private set

    /** Estados anteriores, para «Deshacer» (el más reciente al final). */
    private val history = mutableStateListOf<Pair<Position, Set<Int>>>()

    val canUndo: Boolean get() = history.isNotEmpty()

    /** Vuelve al estado anterior a la última modificación. */
    fun undo() {
        val (p, m) = history.removeLastOrNull() ?: return
        position = p
        marked = m
    }

    /** Todo cambio pasa por aquí: guarda el estado actual antes de aplicarlo. */
    private fun change(p: Position, m: Set<Int> = marked) {
        if (p == position && m == marked) return
        history += position to marked
        if (history.size > MAX_UNDO) history.removeAt(0)
        position = p
        marked = m
    }

    /** Coloca la pieza elegida; tocar una casilla con esa misma pieza la quita. */
    fun tap(square: Int) {
        if (square in marked) {
            // Primer toque en una casilla dudosa: se da por revisada, sin cambiarla.
            change(position, marked - square)
            return
        }
        val current = position[square]
        val next = when (val t = tool) {
            is EditTool.Place -> if (current == t.piece) null else t.piece
            EditTool.Erase -> null
        }
        // Tras mover piezas, solo quedan los enroques que la colocación permite.
        val p = position.with(square, next)
        change(p.copy(castling = p.castling intersect p.plausibleCastling(), enPassant = null))
    }

    fun setSideToMove(side: Side) = change(position.copy(sideToMove = side, enPassant = null))

    fun toggleCastling(right: Castling) {
        val rights = position.castling
        change(position.copy(castling = if (right in rights) rights - right else rights + right))
    }

    fun clear() = load(Position.EMPTY)

    fun reset() = load(Position.START)

    /** Carga otra posición (FEN, escáner…); también se puede deshacer. */
    fun load(p: Position, marked: Set<Int> = emptySet()) = change(p, marked)

    companion object {
        private const val MAX_UNDO = 100

        val Saver = listSaver<BoardEditorState, Any>(
            save = { listOf(it.position.toFen(), it.flipped, it.marked.toIntArray()) },
            restore = {
                BoardEditorState(Position.fromFen(it[0] as String), it[1] as Boolean, (it[2] as IntArray).toSet())
            },
        )
    }
}

@Composable
fun rememberBoardEditorState(initial: Position = Position.START): BoardEditorState =
    rememberSaveable(saver = BoardEditorState.Saver) { BoardEditorState(initial) }

@Composable
fun BoardEditor(
    state: BoardEditorState,
    modifier: Modifier = Modifier,
    arrows: List<Pair<Int, Int>> = emptyList(),
    belowBoard: @Composable () -> Unit = {},
    shareButton: @Composable () -> Unit = {},
    header: @Composable () -> Unit = {},
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header()
        ChessBoard(state.position, Modifier.fillMaxWidth(), state.flipped, state.marked, arrows, onSquareClick = state::tap)
        belowBoard()
        SideToMove(state)  // justo bajo el tablero: es lo primero que hay que decidir tras escanear
        PiecePalette(state)
        CastlingRights(state)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            @Composable
            fun Action(@StringRes label: Int, enabled: Boolean = true, onClick: () -> Unit) = OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            ) { Text(stringResource(label), maxLines = 1) }
            Action(R.string.action_flip) { state.flipped = !state.flipped }
            Action(R.string.action_clear, onClick = state::clear)
            Action(R.string.action_start, onClick = state::reset)
            Action(R.string.action_undo, enabled = state.canUndo, onClick = state::undo)
        }
        FenField(state)
        Issues(state.position)
        Exports(state.position, shareButton)
    }
}

@Composable
private fun PiecePalette(state: BoardEditorState) {
    @Composable
    fun Cell(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
        val border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        Box(
            Modifier.aspectRatio(1f).border(border, RoundedCornerShape(6.dp)).clickable(onClick = onClick)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (side in Side.entries) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (type in listOf(PieceType.KING, PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP,
                    PieceType.KNIGHT, PieceType.PAWN)) {
                    val piece = Piece(side, type)
                    Box(Modifier.weight(1f)) {
                        Cell(state.tool == EditTool.Place(piece), { state.tool = EditTool.Place(piece) }) {
                            Image(painterResource(LocalBoardStyle.current.pieces.drawable(piece)), contentDescription = "${piece.fen}")
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    if (side == Side.WHITE) {
                        Cell(state.tool == EditTool.Erase, { state.tool = EditTool.Erase }) {
                            Text("✕", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
        if (state.tool == EditTool.Erase) {
            Text(stringResource(R.string.eraser), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SideToMove(state: BoardEditorState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.side_to_move), style = MaterialTheme.typography.titleSmall)
        for (side in Side.entries) {
            FilterChip(
                selected = state.position.sideToMove == side,
                onClick = { state.setSideToMove(side) },
                label = { Text(sideName(side)) },
            )
        }
    }
}

@StringRes
private fun castlingLabel(right: Castling): Int = when (right) {
    Castling.WHITE_KINGSIDE -> R.string.castling_white_kingside
    Castling.WHITE_QUEENSIDE -> R.string.castling_white_queenside
    Castling.BLACK_KINGSIDE -> R.string.castling_black_kingside
    Castling.BLACK_QUEENSIDE -> R.string.castling_black_queenside
}

@Composable
private fun CastlingRights(state: BoardEditorState) {
    val plausible = state.position.plausibleCastling()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.castling), style = MaterialTheme.typography.titleSmall)
        for (pair in Castling.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (right in pair) {
                    FilterChip(
                        selected = right in state.position.castling,
                        onClick = { state.toggleCastling(right) },
                        enabled = right in plausible,
                        label = { Text(stringResource(castlingLabel(right))) },
                    )
                }
            }
        }
    }
}

/** El FEN se edita como texto y se aplica con «Cargar»: así no salta mientras se escribe. */
@Composable
private fun FenField(state: BoardEditorState) {
    var text by remember(state.position) { mutableStateOf(state.position.toFen()) }
    var error by remember(state.position) { mutableStateOf<String?>(null) }
    fun apply() {
        try {
            state.load(Position.fromFen(text))
        } catch (e: FenException) {
            error = e.message
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.fen_label)) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            isError = error != null,
            supportingText = error?.let { { Text(stringResource(R.string.fen_invalid, it)) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { apply() }),
        )
        TextButton(onClick = ::apply) { Text(stringResource(R.string.fen_load)) }
    }
}

@Composable
private fun sideName(side: Side): String =
    stringResource(if (side == Side.WHITE) R.string.side_white else R.string.side_black)

@Composable
private fun issueText(issue: Issue): String = when (issue) {
    is Issue.KingCount -> pluralStringResource(R.plurals.issue_kings, issue.count, sideName(issue.side), issue.count)
    is Issue.PawnOnBackRank -> stringResource(R.string.issue_pawn_back_rank, Square.name(issue.square))
    is Issue.TooManyPawns -> pluralStringResource(R.plurals.issue_too_many_pawns, issue.count, sideName(issue.side), issue.count)
    is Issue.TooManyPieces -> pluralStringResource(R.plurals.issue_too_many_pieces, issue.count, sideName(issue.side), issue.count)
    is Issue.OpponentInCheck -> stringResource(R.string.issue_opponent_in_check, sideName(issue.side))
    is Issue.CastlingWithoutPieces -> stringResource(R.string.issue_castling, stringResource(castlingLabel(issue.right)))
    is Issue.InvalidEnPassant -> stringResource(R.string.issue_en_passant, Square.name(issue.square))
}

@Composable
private fun Issues(position: Position) {
    val issues = position.issues()
    if (issues.isEmpty()) {
        Text(stringResource(R.string.position_ok), color = MaterialTheme.colorScheme.primary)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        issues.forEach { Text("• ${issueText(it)}", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Exports(position: Position, shareButton: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val fen = position.toFen()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("FEN", fen))) }
        }) { Text(stringResource(R.string.copy_fen)) }
        Button(onClick = { uriHandler.openUri("https://lichess.org/analysis/" + fen.replace(' ', '_')) }) {
            Text(stringResource(R.string.open_lichess))
        }
        shareButton()
    }
}
