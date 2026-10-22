package com.pawneyes.vision

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc

/**
 * Port de ml/detect/board.py. Las esquinas se representan como DoubleArray de 8
 * valores (x0, y0, …, x3, y3) en el orden tl, tr, br, bl.
 */
object BoardDetector {
    const val WORK_SIZE = 1200
    const val MIN_SCORE = 0.15

    class Detection(val quad: DoubleArray, val score: Double)

    /**
     * `image`: foto en color RGBA (CV_8UC4). Devuelve las esquinas en sus coordenadas.
     * `refine = false` se salta el ajuste fino (lo más lento): para la vista en vivo,
     * donde basta con un contorno aproximado; la foto capturada usa el detector completo.
     */
    fun detect(image: Mat, refine: Boolean = true): Detection? {
        val scale = WORK_SIZE.toDouble() / max(image.rows(), image.cols())
        val small = Mat()
        Imgproc.resize(image, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)

        // Mapas para medir el patrón: gris (casillas lisas) y densidad de tinta (rayados).
        val features = mutableListOf(gray)
        val candidates = mutableListOf<DoubleArray>()
        for (block in intArrayOf(31, 151)) {
            val binary = binarize(gray, block)
            val ink = Mat()
            Imgproc.blur(binary, ink, Size(5.0, 5.0))
            features += ink
            candidates += quadsFromContours(binary)
            candidates += quadsFromSquares(binary)
        }

        var best: DoubleArray? = null
        var bestScore = 0.0
        for (quad in candidates) {
            val s = score(features, quad)
            if (s > bestScore) {
                best = quad
                bestScore = s
            }
        }
        if (best == null) return null
        val (refined, refinedScore) = if (refine) refineQuad(features, best, bestScore) else best to bestScore
        if (refinedScore < MIN_SCORE) return null
        return Detection(DoubleArray(8) { refined[it] / scale }, refinedScore)
    }

    /** Endereza el cuadrilátero a size×size (como warp_board de Python). */
    fun warp(image: Mat, quad: DoubleArray, size: Int = VisionSpec.BOARD_PX): Mat {
        val s = (size - 1).toDouble()
        val src = MatOfPoint2f(*points(quad))
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(s, 0.0), Point(s, s), Point(0.0, s))
        val m = Geometry.getPerspectiveTransform(src, dst)
        val out = Mat()
        Imgproc.warpPerspective(image, out, m, Size(size.toDouble(), size.toDouble()), Imgproc.INTER_AREA)
        return out
    }

    // --- Utilidades geométricas -------------------------------------------------

    private fun points(quad: DoubleArray) = Array(4) { Point(quad[2 * it], quad[2 * it + 1]) }

    /** tl, tr, br, bl como order_corners de Python (argmin/argmax de x+y y de y−x). */
    fun orderCorners(pts: List<Point>): DoubleArray {
        require(pts.size == 4)
        val s = pts.map { it.x + it.y }
        val d = pts.map { it.y - it.x }
        fun argmin(v: List<Double>) = v.indices.minBy { v[it] }
        fun argmax(v: List<Double>) = v.indices.maxBy { v[it] }
        val order = listOf(argmin(s), argmin(d), argmax(s), argmax(d))
        return DoubleArray(8) { i -> pts[order[i / 2]].let { if (i % 2 == 0) it.x else it.y } }
    }

    private fun sides(quad: DoubleArray): DoubleArray = DoubleArray(4) { i ->
        val j = (i + 1) % 4
        hypot(quad[2 * i] - quad[2 * j], quad[2 * i + 1] - quad[2 * j + 1])
    }

    private fun squareness(quad: DoubleArray): Double = sides(quad).let { it.min() / it.max() }

    private fun approxQuad(hull: MatOfPoint): DoubleArray? {
        val hull2f = MatOfPoint2f(*hull.toArray())
        val peri = Geometry.arcLength(hull2f, true)
        for (eps in doubleArrayOf(0.01, 0.02, 0.03, 0.04, 0.05)) {
            val approx = MatOfPoint2f()
            Geometry.approxPolyDP(hull2f, approx, eps * peri, true)
            val n = approx.total().toInt()  // en Java sale como 1×N, no N×1 como en Python
            if (n == 4) return orderCorners(approx.toList())
            if (n < 4) return null
        }
        return null
    }

    private fun convexHull(contour: MatOfPoint): MatOfPoint {
        val idx = MatOfInt()
        Geometry.convexHull(contour, idx)
        val pts = contour.toArray()
        return MatOfPoint(*idx.toArray().map { pts[it] }.toTypedArray())
    }

    // --- Puntuación de patrón de tablero ----------------------------------------

    /**
     * Cuánto se parece el interior del cuadrilátero a un tablero 8×8: gris en las
     * esquinas de cada casilla y contraste alterno fila a fila y columna a columna.
     */
    fun checkerScore(feature: Mat, quad: DoubleArray): Double {
        val n = 256
        val cell = n / 8
        val warped = warp(feature, quad, n)
        val px = ByteArray(n * n)
        warped.get(0, 0, px)
        warped.release()
        val inset = cell / 8
        val patch = cell / 5
        val m = Array(8) { DoubleArray(8) }
        for (i in 0 until 8) for (j in 0 until 8) {
            val y0 = i * cell
            val x0 = j * cell
            var sum = 0.0
            for (y in intArrayOf(y0 + inset, y0 + cell - inset - patch))
                for (x in intArrayOf(x0 + inset, x0 + cell - inset - patch))
                    for (dy in 0 until patch) for (dx in 0 until patch)
                        sum += px[(y + dy) * n + x + dx].toInt() and 0xFF
            m[i][j] = sum / (4 * patch * patch)
        }
        val all = m.flatMap { it.asIterable() }
        val mean = all.average()
        val spread = sqrt(all.sumOf { (it - mean) * (it - mean) } / all.size)
        if (spread == 0.0) return 0.0
        val signed = Array(8) { i -> DoubleArray(8) { j ->
            (m[i][j] - mean) * (if ((i + j) % 2 == 1) 1 else -1) / spread
        } }
        if (signed.sumOf { it.sum() } < 0) for (row in signed) for (j in row.indices) row[j] = -row[j]
        val lines = (0 until 8).map { i -> signed[i].average() } + (0 until 8).map { j -> (0 until 8).map { signed[it][j] }.average() }
        return 0.5 * lines.average() + 0.5 * lines.min()
    }

    private fun score(features: List<Mat>, quad: DoubleArray): Double = features.maxOf { checkerScore(it, quad) }

    /** Descenso coordenado sobre las esquinas con pasos decrecientes. */
    private fun refineQuad(features: List<Mat>, start: DoubleArray, startScore: Double): Pair<DoubleArray, Double> {
        val side = sides(start).average() / 8
        var quad = start.copyOf()
        var best = startScore
        for (frac in doubleArrayOf(0.5, 0.25, 0.12, 0.06)) {
            val step = frac * side
            var improved = true
            while (improved) {
                improved = false
                for (k in 0 until 4) for (axis in 0 until 2) for (sign in intArrayOf(1, -1)) {
                    val trial = quad.copyOf()
                    trial[2 * k + axis] += sign * step
                    val s = score(features, trial)
                    if (s > best) {
                        quad = trial
                        best = s
                        improved = true
                    }
                }
            }
        }
        return quad to best
    }

    // --- Candidatos ------------------------------------------------------------

    private fun binarize(gray: Mat, block: Int): Mat {
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        val binary = Mat()
        Imgproc.adaptiveThreshold(blur, binary, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV, block, 10.0)
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, Mat.ones(3, 3, CvType.CV_8U))
        blur.release()
        return binary
    }

    /** Candidatos a partir del marco: envolvente convexa de cada contorno grande. */
    private fun quadsFromContours(binary: Mat): List<DoubleArray> {
        val imgArea = binary.rows().toDouble() * binary.cols()
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(binary, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        return contours.mapNotNull { c ->
            val hull = convexHull(c)
            if (Geometry.contourArea(hull) < 0.04 * imgArea) return@mapNotNull null
            approxQuad(hull)?.takeIf { squareness(it) >= 0.6 }
        }
    }

    private class DarkSquare(val x: Double, val y: Double, val side: Double)

    private fun darkSquares(binary: Mat, openK: Int): List<DarkSquare> {
        val mask = Mat()
        Imgproc.morphologyEx(binary, mask, Imgproc.MORPH_CLOSE, Mat.ones(9, 9, CvType.CV_8U))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, Mat.ones(openK, openK, CvType.CV_8U))
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        mask.release()
        val maxSide = max(binary.rows(), binary.cols()) / 6.0
        return contours.mapNotNull { c ->
            val area = Geometry.contourArea(c)
            val rect = Geometry.minAreaRect(MatOfPoint2f(*c.toArray()))
            val rw = rect.size.width
            val rh = rect.size.height
            if (rw < 8 || rh < 8 || max(rw, rh) > maxSide) return@mapNotNull null
            if (min(rw, rh) / max(rw, rh) < 0.6 || area / (rw * rh) < 0.7) return@mapNotNull null
            DarkSquare(rect.center.x, rect.center.y, sqrt(area))
        }
    }

    /** Grupo más grande de casillas oscuras unidas como vecinas diagonales. */
    private fun largestDiagonalCluster(squares: List<DarkSquare>): Pair<List<Int>, List<List<Int>>> {
        val n = squares.size
        val adj = List(n) { mutableListOf<Int>() }
        for (i in 0 until n) for (j in i + 1 until n) {
            val a = squares[i]
            val b = squares[j]
            if (max(a.side, b.side) / min(a.side, b.side) > 1.5) continue
            val d = hypot(a.x - b.x, a.y - b.y) / ((a.side + b.side) / 2)
            if (d > 1.15 && d < 1.75) {
                adj[i] += j
                adj[j] += i
            }
        }
        val seen = BooleanArray(n)
        var best = emptyList<Int>()
        for (start in 0 until n) {
            if (seen[start]) continue
            val comp = mutableListOf<Int>()
            val stack = ArrayDeque(listOf(start))
            seen[start] = true
            while (stack.isNotEmpty()) {
                val k = stack.removeLast()
                comp += k
                for (nb in adj[k]) if (!seen[nb]) {
                    seen[nb] = true
                    stack.addLast(nb)
                }
            }
            if (comp.size > best.size) best = comp
        }
        return best to adj
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** Asigna coordenadas de rejilla a las casillas y ajusta una homografía. */
    private fun quadsFromLattice(squares: List<DarkSquare>, cluster: List<Int>, adj: List<List<Int>>): List<DoubleArray> {
        if (cluster.size < 10) return emptyList()
        // Desplazamientos entre vecinas plegados al semiplano (v y −v cuentan igual).
        val offsets = mutableListOf<DoubleArray>()
        for (k in cluster) for (nb in adj[k]) {
            var ox = squares[nb].x - squares[k].x
            var oy = squares[nb].y - squares[k].y
            if (ox < 0 || (ox == 0.0 && oy < 0)) {
                ox = -ox
                oy = -oy
            }
            offsets += doubleArrayOf(ox, oy)
        }
        val angles = offsets.map { atan2(it[1], it[0]) }
        val med = median(angles)
        val pIdx = angles.indices.filter { angles[it] < med }
        val qIdx = angles.indices.filter { angles[it] >= med }
        if (pIdx.isEmpty() || qIdx.isEmpty()) return emptyList()
        val p = doubleArrayOf(median(pIdx.map { offsets[it][0] }), median(pIdx.map { offsets[it][1] }))
        val q = doubleArrayOf(median(qIdx.map { offsets[it][0] }), median(qIdx.map { offsets[it][1] }))

        // BFS: cada vecina es ±p o ±q respecto a la actual.
        val basis = listOf(
            doubleArrayOf(p[0], p[1]) to (1 to 0), doubleArrayOf(-p[0], -p[1]) to (-1 to 0),
            doubleArrayOf(q[0], q[1]) to (0 to 1), doubleArrayOf(-q[0], -q[1]) to (0 to -1),
        )
        val coords = LinkedHashMap<Int, Pair<Int, Int>>()
        coords[cluster[0]] = 0 to 0
        val queue = ArrayDeque(listOf(cluster[0]))
        while (queue.isNotEmpty()) {
            val k = queue.removeFirst()
            val (m, n) = coords.getValue(k)
            for (nb in adj[k]) {
                if (nb in coords) continue
                val ox = squares[nb].x - squares[k].x
                val oy = squares[nb].y - squares[k].y
                val (vec, delta) = basis.minBy { (v, _) -> hypot(ox - v[0], oy - v[1]) }
                if (hypot(ox - vec[0], oy - vec[1]) > 0.5 * hypot(vec[0], vec[1])) continue
                coords[nb] = (m + delta.first) to (n + delta.second)
                queue.addLast(nb)
            }
        }
        val keys = coords.keys.toList()
        if (keys.size < 10) return emptyList()

        // Rejilla de casillas: u = m + n, v = m − n.
        val uv = keys.map { coords.getValue(it).let { (m, n) -> doubleArrayOf((m + n).toDouble(), (m - n).toDouble()) } }
        val src = MatOfPoint2f(*uv.map { Point(it[0] + 0.5, it[1] + 0.5) }.toTypedArray())
        val dst = MatOfPoint2f(*keys.map { Point(squares[it].x, squares[it].y) }.toTypedArray())
        val threshold = 0.25 * keys.map { squares[it].side }.average()
        val inliers = Mat()
        val h = Geometry.findHomography(src, dst, Geometry.RANSAC, threshold, inliers)
        if (h.empty()) return emptyList()
        val mask = ByteArray(keys.size)
        inliers.get(0, 0, mask)
        val inUv = uv.filterIndexed { i, _ -> mask[i].toInt() != 0 }
        if (inUv.isEmpty()) return emptyList()
        val lo = intArrayOf(inUv.minOf { it[0] }.toInt(), inUv.minOf { it[1] }.toInt())
        val span = intArrayOf(inUv.maxOf { it[0] }.toInt() + 1 - lo[0], inUv.maxOf { it[1] }.toInt() + 1 - lo[1])
        if (span.any { it > 8 || it < 5 }) return emptyList()

        // Todas las formas de completar 8×8 (y ±1 por si se colaron manchas de fuera).
        val quads = mutableListOf<DoubleArray>()
        for (du in -1..(8 - span[0] + 1)) for (dv in -1..(8 - span[1] + 1)) {
            val u0 = (lo[0] - du).toDouble()
            val v0 = (lo[1] - dv).toDouble()
            val corners = MatOfPoint2f(Point(u0, v0), Point(u0 + 8, v0), Point(u0 + 8, v0 + 8), Point(u0, v0 + 8))
            val out = MatOfPoint2f()
            Core.perspectiveTransform(corners, out, h)
            quads += orderCorners(out.toList())
        }
        return quads
    }

    private fun quadsFromSquares(binary: Mat): List<DoubleArray> = intArrayOf(5, 9, 15, 25).flatMap { k ->
        val squares = darkSquares(binary, k)
        val (cluster, adj) = largestDiagonalCluster(squares)
        quadsFromLattice(squares, cluster, adj)
    }
}
