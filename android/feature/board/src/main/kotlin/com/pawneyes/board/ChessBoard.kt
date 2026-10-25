package com.pawneyes.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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

private val MarkColor = Color(0xFFD32F2F)
private val ArrowColor = Color(0xCC15781B)
private val HighlightColor = Color(0x80F6E05E)  // última jugada
private val DotColor = Color(0x5A000000)  // destinos posibles al mover
private val DragTargetColor = Color(0x5515781B)  // casilla bajo la pieza arrastrada

/** Casilla (0 = a8 … 63 = h1) que se dibuja en la fila/columna de pantalla dadas. */
fun squareAt(row: Int, col: Int, flipped: Boolean): Int =
    if (flipped) (7 - row) * 8 + (7 - col) else row * 8 + col

@Composable
private fun pieceImages(set: PieceSet): Map<Piece, ImageBitmap> =
    Piece.ALL.associateWith { ImageBitmap.imageResource(set.drawable(it)) }

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
    /** Pulsación larga en una casilla (#27); null si no hace nada. */
    onSquareLongClick: ((Int) -> Unit)? = null,
    /** Arrastrar (#25): ¿se puede arrastrar la pieza de esta casilla? */
    onDragStart: ((Int) -> Boolean)? = null,
    /** Se suelta la pieza arrastrada desde `from` en `to` (null: fuera del tablero). */
    onDrop: ((from: Int, to: Int?) -> Unit)? = null,
) {
    val style = LocalBoardStyle.current
    val images = pieceImages(style.pieces)
    val lightSquare = style.theme.light
    val darkSquare = style.theme.dark
    val textMeasurer = rememberTextMeasurer()
    // Los callbacks se leen al vuelo: si fueran clave de pointerInput, cada recomposición
    // (la selección cambia al empezar a arrastrar) reiniciaría el gesto y lo cancelaría.
    val click by rememberUpdatedState(onSquareClick)
    val longClick by rememberUpdatedState(onSquareLongClick)
    val clickable = onSquareClick != null
    val longClickable = onSquareLongClick != null
    val tap = if (!clickable && !longClickable) Modifier else Modifier.pointerInput(flipped, clickable, longClickable) {
        fun squareOf(offset: Offset): Int {
            val cell = size.width / 8f
            val col = (offset.x / cell).toInt().coerceIn(0, 7)
            val row = (offset.y / cell).toInt().coerceIn(0, 7)
            return squareAt(row, col, flipped)
        }
        detectTapGestures(
            onLongPress = if (longClickable) { o -> longClick?.invoke(squareOf(o)) } else null,
            onTap = if (clickable) { o -> click?.invoke(squareOf(o)) } else null,
        )
    }
    // Arrastre: la pieza sigue al dedo y se suelta en la casilla de debajo. Empieza al pasar
    // el umbral de movimiento, así que un toque normal sigue siendo un toque.
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragAt by remember { mutableStateOf(Offset.Zero) }
    val current by rememberUpdatedState(position)
    val canDrag by rememberUpdatedState(onDragStart)
    val drop by rememberUpdatedState(onDrop)
    val draggable = onDragStart != null && onDrop != null
    val drag = if (!draggable) Modifier else Modifier.pointerInput(flipped, draggable) {
        fun squareUnder(o: Offset): Int? {
            val cell = size.width / 8f
            if (o.x < 0 || o.y < 0 || o.x >= size.width || o.y >= size.height) return null
            return squareAt((o.y / cell).toInt().coerceIn(0, 7), (o.x / cell).toInt().coerceIn(0, 7), flipped)
        }
        detectDragGestures(
            onDragStart = { o ->
                val sq = squareUnder(o)
                if (sq != null && current[sq] != null && canDrag?.invoke(sq) == true) {
                    dragFrom = sq
                    dragAt = o
                }
            },
            onDrag = { change, delta ->
                if (dragFrom != null) {
                    change.consume()
                    dragAt += delta
                }
            },
            onDragEnd = {
                dragFrom?.let { from -> drop?.invoke(from, squareUnder(dragAt)) }
                dragFrom = null
            },
            onDragCancel = {
                dragFrom?.let { from -> drop?.invoke(from, from) }  // como si no se hubiera movido
                dragFrom = null
            },
        )
    }
    Canvas(modifier.aspectRatio(1f).then(tap).then(drag)) {
        val cell = size.width / 8f
        val coordStyle = TextStyle(fontSize = (cell / 5f).toSp(), fontWeight = FontWeight.Bold)
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val square = squareAt(row, col, flipped)
                val dark = (row + col) % 2 == 1
                val topLeft = Offset(col * cell, row * cell)
                drawRect(if (dark) darkSquare else lightSquare, topLeft, Size(cell, cell))
                if (square in highlighted) drawRect(HighlightColor, topLeft, Size(cell, cell))
                position[square]?.takeIf { square != dragFrom }?.let { piece ->
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
                val ink = if (dark) lightSquare else darkSquare
                if (style.coordinates && col == 0) {
                    val rank = "${8 - square / 8}"
                    drawText(textMeasurer, rank, topLeft + Offset(cell * 0.05f, cell * 0.02f),
                        coordStyle.copy(color = ink))
                }
                if (style.coordinates && row == 7) {
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
        // La pieza arrastrada, encima de todo, un poco más grande y levantada para que el
        // dedo no la tape; y la casilla de debajo, marcada.
        dragFrom?.let { from ->
            val piece = position[from] ?: return@let
            val col = (dragAt.x / cell).toInt()
            val row = (dragAt.y / cell).toInt()
            if (dragAt.x >= 0 && dragAt.y >= 0 && col in 0..7 && row in 0..7) {
                drawRect(DragTargetColor, Offset(col * cell, row * cell), Size(cell, cell))
            }
            val s = cell * 1.25f
            drawImage(
                images.getValue(piece),
                dstOffset = IntOffset((dragAt.x - s / 2).roundToInt(), (dragAt.y - s * 0.9f).roundToInt()),
                dstSize = IntSize(s.roundToInt(), s.roundToInt()),
                filterQuality = FilterQuality.High,
            )
        }
    }
}
