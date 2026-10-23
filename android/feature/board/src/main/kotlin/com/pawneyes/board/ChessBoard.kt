package com.pawneyes.board

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pawneyes.chess.Piece
import com.pawneyes.chess.PieceType
import com.pawneyes.chess.Position
import com.pawneyes.chess.Side
import kotlin.math.roundToInt

private val LightSquare = Color(0xFFF0D9B5)
private val DarkSquare = Color(0xFFB58863)
private val MarkColor = Color(0xFFD32F2F)
private val ArrowColor = Color(0xCC15781B)
private val HighlightColor = Color(0x80F6E05E)  // última jugada
private val DotColor = Color(0x5A000000)  // destinos posibles al mover

/** Casilla (0 = a8 … 63 = h1) que se dibuja en la fila/columna de pantalla dadas. */
fun squareAt(row: Int, col: Int, flipped: Boolean): Int =
    if (flipped) (7 - row) * 8 + (7 - col) else row * 8 + col

@DrawableRes
fun drawableFor(piece: Piece): Int = when (piece.side) {
    Side.WHITE -> when (piece.type) {
        PieceType.PAWN -> R.drawable.piece_wp
        PieceType.KNIGHT -> R.drawable.piece_wn
        PieceType.BISHOP -> R.drawable.piece_wb
        PieceType.ROOK -> R.drawable.piece_wr
        PieceType.QUEEN -> R.drawable.piece_wq
        PieceType.KING -> R.drawable.piece_wk
    }
    Side.BLACK -> when (piece.type) {
        PieceType.PAWN -> R.drawable.piece_bp
        PieceType.KNIGHT -> R.drawable.piece_bn
        PieceType.BISHOP -> R.drawable.piece_bb
        PieceType.ROOK -> R.drawable.piece_br
        PieceType.QUEEN -> R.drawable.piece_bq
        PieceType.KING -> R.drawable.piece_bk
    }
}

@Composable
private fun pieceImages(): Map<Piece, ImageBitmap> =
    Piece.ALL.associateWith { ImageBitmap.imageResource(drawableFor(it)) }

/**
 * Tablero de solo dibujo. `marked` resalta casillas (p. ej. las dudosas del
 * escáner) y `onSquareClick`, si se da, recibe la casilla tocada.
 */
@Composable
fun ChessBoard(
    position: Position,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    marked: Set<Int> = emptySet(),
    arrows: List<Pair<Int, Int>> = emptyList(),
    highlighted: Set<Int> = emptySet(),
    dots: Set<Int> = emptySet(),
    onSquareClick: ((Int) -> Unit)? = null,
) {
    val images = pieceImages()
    val textMeasurer = rememberTextMeasurer()
    val tap = if (onSquareClick == null) Modifier else Modifier.pointerInput(flipped, onSquareClick) {
        detectTapGestures { offset ->
            val cell = size.width / 8f
            val col = (offset.x / cell).toInt().coerceIn(0, 7)
            val row = (offset.y / cell).toInt().coerceIn(0, 7)
            onSquareClick(squareAt(row, col, flipped))
        }
    }
    Canvas(modifier.aspectRatio(1f).then(tap)) {
        val cell = size.width / 8f
        val coordStyle = TextStyle(fontSize = (cell / 5f).toSp(), fontWeight = FontWeight.Bold)
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val square = squareAt(row, col, flipped)
                val dark = (row + col) % 2 == 1
                val topLeft = Offset(col * cell, row * cell)
                drawRect(if (dark) DarkSquare else LightSquare, topLeft, Size(cell, cell))
                if (square in highlighted) drawRect(HighlightColor, topLeft, Size(cell, cell))
                position[square]?.let { piece ->
                    val x0 = (col * cell).roundToInt()
                    val y0 = (row * cell).roundToInt()
                    drawImage(
                        images.getValue(piece),
                        dstOffset = IntOffset(x0, y0),
                        dstSize = IntSize(((col + 1) * cell).roundToInt() - x0,
                            ((row + 1) * cell).roundToInt() - y0),
                        filterQuality = FilterQuality.High,
                    )
                }
                if (square in dots) {
                    // Destino posible: punto si está vacía, anillo si hay pieza que capturar.
                    val c = topLeft + Offset(cell / 2, cell / 2)
                    if (position[square] == null) drawCircle(DotColor, cell * 0.15f, c)
                    else drawCircle(DotColor, cell * 0.44f, c, style = Stroke(width = cell * 0.08f))
                }
                if (square in marked) {
                    val w = cell * 0.08f
                    drawRect(MarkColor, topLeft + Offset(w / 2, w / 2), Size(cell - w, cell - w),
                        style = Stroke(width = w))
                }
                // Coordenadas: números en la columna izquierda, letras en la fila de abajo.
                val ink = if (dark) LightSquare else DarkSquare
                if (col == 0) {
                    val rank = "${8 - square / 8}"
                    drawText(textMeasurer, rank, topLeft + Offset(cell * 0.05f, cell * 0.02f),
                        coordStyle.copy(color = ink))
                }
                if (row == 7) {
                    val file = "${'a' + square % 8}"
                    val layout = textMeasurer.measure(file, coordStyle)
                    drawText(layout, ink, topLeft + Offset(cell - layout.size.width - cell * 0.05f,
                        cell - layout.size.height))
                }
            }
        }
        // Flechas (p. ej. la mejor jugada del motor), de centro a centro de casilla.
        fun center(square: Int): Offset {
            val row = if (flipped) 7 - square / 8 else square / 8
            val col = if (flipped) 7 - square % 8 else square % 8
            return Offset((col + 0.5f) * cell, (row + 0.5f) * cell)
        }
        for ((from, to) in arrows) {
            val a = center(from)
            val b = center(to)
            val d = b - a
            val len = d.getDistance()
            if (len == 0f) continue
            val u = d / len
            val n = Offset(-u.y, u.x)
            val head = cell * 0.4f
            val tip = b - u * (cell * 0.15f)
            val base = tip - u * head
            drawLine(ArrowColor, a, base, strokeWidth = cell * 0.16f, cap = StrokeCap.Round)
            drawPath(Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(base.x + n.x * head * 0.6f, base.y + n.y * head * 0.6f)
                lineTo(base.x - n.x * head * 0.6f, base.y - n.y * head * 0.6f)
                close()
            }, ArrowColor)
        }
    }
}
