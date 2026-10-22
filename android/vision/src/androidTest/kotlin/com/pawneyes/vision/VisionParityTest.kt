package com.pawneyes.vision

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.hypot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Compara cada paso del pipeline en Android con lo que dio Python (fixtures/,
 * generados por ml/export/fixtures.py).
 */
@RunWith(AndroidJUnit4::class)
class VisionParityTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val boards: List<JSONObject> by lazy {
        val manifest = JSONObject(context.assets.open("manifest.json").bufferedReader().readText())
        manifest.getJSONArray("boards").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    }

    // Los fixtures salen de fotos propias que no se publican: sin ellos, se omite.
    @Before
    fun requireFixtures() = assumeTrue("sin fixtures (ver README)", "manifest.json" in context.assets.list("").orEmpty())

    private fun rgba(asset: String): Mat {
        val bitmap = context.assets.open(asset).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })!!
        }
        return Mat().also { Utils.bitmapToMat(bitmap, it) }
    }

    private fun gray(asset: String): Mat =
        Mat().also { Imgproc.cvtColor(rgba(asset), it, Imgproc.COLOR_RGBA2GRAY) }

    private fun bytes(asset: String) = context.assets.open(asset).use { it.readBytes() }

    private fun log(msg: String) = Log.i(TAG, msg)

    @Test
    fun cropMatchesPython() {
        var worst = 0
        var differing = 0
        for (b in boards) {
            val mine = SquareCropper.crop(gray(b.getString("board")))
            val theirs = bytes(b.getString("squares"))
            assertEquals(theirs.size, mine.size)
            for (i in mine.indices) {
                val d = abs((mine[i].toInt() and 0xFF) - (theirs[i].toInt() and 0xFF))
                if (d > 0) differing++
                if (d > worst) worst = d
            }
        }
        log("recorte: diferencia máxima $worst, bytes distintos $differing")
        assertTrue("diferencia máxima $worst", worst <= 1)
    }

    @Test
    fun classifierMatchesPython() {
        SquareClassifier.fromAssets(context).use { model ->
            var worst = 0.0
            for (b in boards) {
                val logits = model.logits(bytes(b.getString("squares")))
                val expected = b.getJSONArray("logits").getJSONArray(0)
                for (sq in 0 until 64) for (c in 0 until 13) {
                    worst = maxOf(worst, abs(logits[sq][c] - expected.getJSONArray(sq).getDouble(c)))
                }
            }
            log("modelo: diferencia máxima de logits $worst")
            assertTrue("diferencia máxima $worst", worst < 1e-3)
        }
    }

    @Test
    fun detectorMatchesPython() {
        var worst = 0.0
        for (b in boards) {
            val name = b.getString("name")
            val detection = BoardDetector.detect(rgba(b.getString("photo")))
            assertNotNull("$name: sin tablero", detection)
            val expected = b.getJSONArray("quad")
            var dist = 0.0
            for (i in 0 until 4) {
                val p = expected.getJSONArray(i)
                dist = maxOf(dist, hypot(detection!!.quad[2 * i] - p.getDouble(0), detection.quad[2 * i + 1] - p.getDouble(1)))
            }
            log("detector $name: esquina más alejada a ${"%.2f".format(dist)} px")
            worst = maxOf(worst, dist)
        }
        assertTrue("esquina a $worst px", worst < 3.0)
    }

    @Test
    fun scanMatchesPython() {
        Scanner(SquareClassifier.fromAssets(context)).use { scanner ->
            var same = 0
            for (b in boards) {
                val name = b.getString("name")
                val result = scanner.scan(rgba(b.getString("photo")))
                assertNotNull("$name: sin tablero", result)
                val placement = result!!.position.placement()
                val ok = placement == b.getString("placement") && result.reading.rotation == b.getInt("rotation")
                if (ok) same++
                log("escaneo $name: ${if (ok) "igual" else "DISTINTO"}  $placement  dudosas ${result.doubtful.size}")
            }
            assertEquals(boards.size, same)
        }
    }

    companion object {
        private const val TAG = "VisionParity"

        @BeforeClass
        @JvmStatic
        fun loadOpenCv() {
            check(OpenCVLoader.initLocal())
        }
    }
}
