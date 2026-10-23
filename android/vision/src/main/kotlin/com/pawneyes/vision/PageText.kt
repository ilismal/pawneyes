package com.pawneyes.vision

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

/**
 * Port de ml/caption/page.py: la página enderezada alrededor del diagrama.
 * La homografía del tablero vale para el texto cercano: se proyecta un rectángulo
 * mayor que el tablero, ya girado para que las piezas queden derechas.
 */
object PageText {
    /** px por casilla en la página enderezada: el texto queda de unos 30–40 px de alto. */
    const val SQUARE = 80
    const val LEFT = 2
    const val TOP = 4
    const val RIGHT = 2
    const val BOTTOM = 4

    /** Origen del tablero dentro de la página. */
    val origin = Point((LEFT * SQUARE).toDouble(), (TOP * SQUARE).toDouble())

    /** `gray`: foto en gris; `quad`: esquinas tl, tr, br, bl en sus coordenadas; `k`: giro de las piezas. */
    fun rectify(gray: Mat, quad: DoubleArray, k: Int): Mat {
        val s = SQUARE.toDouble()
        val ox = origin.x
        val oy = origin.y
        // Al encoger, warpPerspective (bilineal) mete aliasing en el texto: primero se reduce
        // la foto por áreas hasta que el tablero mida lo que medirá enderezado. Así el OCR
        // ve lo mismo sea cual sea la resolución de la foto.
        val side = (0 until 4).map { i ->
            val j = (i + 1) % 4
            hypot(quad[2 * i] - quad[2 * j], quad[2 * i + 1] - quad[2 * j + 1])
        }.average()
        val f = 8 * s / side
        var src0 = gray
        var q = quad
        if (f < 1) {
            src0 = Mat()
            Imgproc.resize(gray, src0, Size(), f, f, Imgproc.INTER_AREA)
            q = DoubleArray(8) { quad[it] * f }
        }
        // np.roll(quad, -k): la esquina de arriba a la izquierda tras girar k×90° antihorario.
        val src = MatOfPoint2f(*Array(4) { i -> ((i + k) % 4).let { Point(q[2 * it], q[2 * it + 1]) } })
        val dst = MatOfPoint2f(Point(ox, oy), Point(ox + 8 * s, oy), Point(ox + 8 * s, oy + 8 * s), Point(ox, oy + 8 * s))
        val m = Geometry.getPerspectiveTransform(src, dst)
        val page = Mat()
        val size = Size(((LEFT + 8 + RIGHT) * SQUARE).toDouble(), ((TOP + 8 + BOTTOM) * SQUARE).toDouble())
        Imgproc.warpPerspective(src0, page, m, size, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(255.0))
        listOf(src, dst, m).forEach { it.release() }
        if (src0 !== gray) src0.release()
        return page
    }

    /** Binariza con umbral local (sombras, papel amarillento) y, salvo `keepBoard`, borra el tablero. */
    fun clean(page: Mat, keepBoard: Boolean = false): Mat {
        val s = SQUARE
        val bw = Mat()
        Imgproc.adaptiveThreshold(page, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
            2 * (s / 2) + 1, 15.0)
        if (!keepBoard) {
            val pad = s / 4
            val ox = origin.x.toInt()
            val oy = origin.y.toInt()
            val x0 = maxOf(0, ox - pad)
            val y0 = maxOf(0, oy - pad)
            bw.submat(Rect(x0, y0, ox + 8 * s + pad - x0, oy + 8 * s + pad - y0)).setTo(Scalar(255.0))
        }
        return bw
    }

    private fun crop(page: Mat, x0: Int, y0: Int, x1: Int, y1: Int): Mat {
        val ax = x0.coerceIn(0, page.cols())
        val ay = y0.coerceIn(0, page.rows())
        val bx = x1.coerceIn(ax, page.cols())
        val by = y1.coerceIn(ay, page.rows())
        return page.submat(Rect(ax, ay, bx - ax, by - ay))
    }

    /** Franjas de texto encima y debajo del tablero (lo bastante anchas para el pie). */
    fun strips(page: Mat): Map<String, Mat> {
        val s = SQUARE
        val ox = origin.x.toInt()
        val oy = origin.y.toInt()
        val x0 = maxOf(0, ox - 2 * s)
        val x1 = minOf(page.cols(), ox + 10 * s)
        return mapOf(
            "above" to crop(page, x0, maxOf(0, oy - 4 * s), x1, maxOf(0, oy - s / 8)),
            "below" to crop(page, x0, oy + 8 * s + s / 8, x1, oy + 12 * s),
        )
    }

    /** Franjas justo por fuera del tablero, donde van las coordenadas. */
    fun coordinateStrips(page: Mat): Map<String, Mat> {
        val s = SQUARE
        val ox = origin.x.toInt()
        val oy = origin.y.toInt()
        val near = s / 16
        val far = (s * 0.75).toInt()
        return mapOf(
            "left" to crop(page, maxOf(0, ox - far), oy, ox - near, oy + 8 * s),
            "right" to crop(page, ox + 8 * s + near, oy, ox + 8 * s + far, oy + 8 * s),
            "top" to crop(page, ox, maxOf(0, oy - far), ox + 8 * s, oy - near),
            "bottom" to crop(page, ox, oy + 8 * s + near, ox + 8 * s, oy + 8 * s + far),
        )
    }
}
