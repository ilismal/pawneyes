package com.pawneyes.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.pawneyes.chess.Position
import kotlin.math.hypot

/**
 * El tablero como imagen (para compartirlo), con el mismo aspecto que [ChessBoard]:
 * colores, piezas, coordenadas, flechas y casillas resaltadas.
 */
object BoardImage {
    private const val LIGHT = 0xFFF0D9B5.toInt()
    private const val DARK = 0xFFB58863.toInt()
    private const val ARROW = 0xCC15781B.toInt()
    private const val HIGHLIGHT = 0x80F6E05E.toInt()

    fun render(
        context: Context,
        position: Position,
        flipped: Boolean = false,
        arrows: List<Pair<Int, Int>> = emptyList(),
        highlighted: Set<Int> = emptySet(),
        size: Int = 1024,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cell = size / 8f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = cell / 5f
            typeface = Typeface.DEFAULT_BOLD
        }
        val pieces = HashMap<Int, Bitmap>()
        val filter = Paint(Paint.FILTER_BITMAP_FLAG)

        for (row in 0 until 8) for (col in 0 until 8) {
            val square = squareAt(row, col, flipped)
            val dark = (row + col) % 2 == 1
            val rect = RectF(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell)
            canvas.drawRect(rect, fill.apply { color = if (dark) DARK else LIGHT })
            if (square in highlighted) canvas.drawRect(rect, fill.apply { color = HIGHLIGHT })
            position[square]?.let { piece ->
                val res = drawableFor(piece)
                val img = pieces.getOrPut(res) { BitmapFactory.decodeResource(context.resources, res) }
                canvas.drawBitmap(img, Rect(0, 0, img.width, img.height), rect, filter)
            }
            text.color = if (dark) LIGHT else DARK
            if (col == 0) canvas.drawText("${8 - square / 8}", rect.left + cell * 0.05f, rect.top + text.textSize, text)
            if (row == 7) {
                val file = "${'a' + square % 8}"
                canvas.drawText(file, rect.right - text.measureText(file) - cell * 0.05f, rect.bottom - cell * 0.06f, text)
            }
        }

        fun center(square: Int): Pair<Float, Float> {
            val row = if (flipped) 7 - square / 8 else square / 8
            val col = if (flipped) 7 - square % 8 else square % 8
            return (col + 0.5f) * cell to (row + 0.5f) * cell
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ARROW
            strokeWidth = cell * 0.16f
            strokeCap = Paint.Cap.ROUND
        }
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ARROW }
        for ((from, to) in arrows) {
            val (ax, ay) = center(from)
            val (bx, by) = center(to)
            val len = hypot(bx - ax, by - ay)
            if (len == 0f) continue
            val ux = (bx - ax) / len
            val uy = (by - ay) / len
            val tipX = bx - ux * cell * 0.15f
            val tipY = by - uy * cell * 0.15f
            val h = cell * 0.4f
            val baseX = tipX - ux * h
            val baseY = tipY - uy * h
            canvas.drawLine(ax, ay, baseX, baseY, line)
            canvas.drawPath(Path().apply {
                moveTo(tipX, tipY)
                lineTo(baseX - uy * h * 0.6f, baseY + ux * h * 0.6f)
                lineTo(baseX + uy * h * 0.6f, baseY - ux * h * 0.6f)
                close()
            }, head)
        }
        return bitmap
    }
}
