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
