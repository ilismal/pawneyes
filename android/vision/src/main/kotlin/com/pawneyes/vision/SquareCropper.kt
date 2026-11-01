package com.pawneyes.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Port de crop_squares (ml/common/squares.py). */
object SquareCropper {
    /**
     * Tablero enderezado (BOARD_PX², gris CV_8U) -> 64 casillas de INPUT_PX²,
     * en orden a8..h1, una detrás de otra (64·INPUT_PX² bytes).
     */
    fun crop(board: Mat): ByteArray {
        require(board.rows() == VisionSpec.BOARD_PX && board.cols() == VisionSpec.BOARD_PX &&
            board.type() == CvType.CV_8UC1) { "se esperaba un tablero ${VisionSpec.BOARD_PX}² en gris" }
        val m = VisionSpec.MARGIN
        val padded = Mat()
        Core.copyMakeBorder(board, padded, m, m, m, m, Core.BORDER_REPLICATE)
        val size = VisionSpec.CELL + 2 * m
        val px = VisionSpec.INPUT_PX
        val out = ByteArray(64 * px * px)
        val cell = Mat()
        val buffer = ByteArray(px * px)
        for (i in 0 until 8) {
            for (j in 0 until 8) {
                val crop = padded.submat(Rect(j * VisionSpec.CELL, i * VisionSpec.CELL, size, size))
                Imgproc.resize(crop, cell, Size(px.toDouble(), px.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                cell.get(0, 0, buffer)  // la salida de resize siempre es continua
                System.arraycopy(buffer, 0, out, (i * 8 + j) * px * px, buffer.size)
            }
        }
        padded.release()
        cell.release()
        return out
    }

    /** Gira el tablero k×90° en sentido antihorario, como np.rot90(board, k). */
    fun rotate(board: Mat, k: Int): Mat {
        val out = Mat()
        when (k % 4) {
            0 -> board.copyTo(out)
            1 -> Core.rotate(board, out, Core.ROTATE_90_COUNTERCLOCKWISE)
            2 -> Core.rotate(board, out, Core.ROTATE_180)
            else -> Core.rotate(board, out, Core.ROTATE_90_CLOCKWISE)
        }
        return out
    }
}

/**
 * Port de rotate_squares (ml/common/squares.py): girar el tablero y recortar da lo mismo,
 * píxel a píxel, que recortar y girar cada casilla, así que se recorta una vez (#13). Sin
 * OpenCV, para poder probarlo en la JVM.
 */
object SquareRotation {
    private const val PX = VisionSpec.INPUT_PX

    /** Índice (fila·n + columna) de la celda que acaba en (i, j) al girar k×90° antihorario, como np.rot90. */
    fun source(i: Int, j: Int, n: Int, k: Int): Int = when (k % 4) {
        0 -> i * n + j
        1 -> j * n + (n - 1 - i)
        2 -> (n - 1 - i) * n + (n - 1 - j)
        else -> (n - 1 - j) * n + i
    }

    /** Las 64 casillas de crop(rotate(board, k)) a partir de crop(board). */
    fun rotateSquares(squares: ByteArray, k: Int): ByteArray {
        val out = ByteArray(squares.size)
        for (sq in 0 until 64) rotate(squares, source(sq / 8, sq % 8, 8, k), k, out, sq)
        return out
    }

    /** Las casillas `which` (índices de crop(board)) giradas cada una k veces, para cada k de `ks`, seguidas. */
    fun select(squares: ByteArray, which: List<Int>, ks: List<Int>): ByteArray {
        val out = ByteArray(ks.size * which.size * PX * PX)
        var n = 0
        for (k in ks) for (sq in which) rotate(squares, sq, k, out, n++)
        return out
    }

    /** Copia la casilla `from` de `src` girada k×90° en la posición `to` de `dst`. */
    private fun rotate(src: ByteArray, from: Int, k: Int, dst: ByteArray, to: Int) {
        val s = from * PX * PX
        val d = to * PX * PX
        for (i in 0 until PX) for (j in 0 until PX) dst[d + i * PX + j] = src[s + source(i, j, PX, k)]
    }
}
