package com.pawneyes.vision

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Tiempos del escáner por paso en el dispositivo, con las fotos de fixtures/.
 *   ./gradlew :vision:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.pawneyes.vision.ScanBenchmarkTest
 * Resultados en logcat con la etiqueta ScanBenchmark.
 */
@RunWith(AndroidJUnit4::class)
class ScanBenchmarkTest {
    private val context = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun timeEachStep() {
        val boards = VisionParityTest.fixtureBoards(context)
        assumeTrue("sin fixtures", boards.isNotEmpty())
        val photos = boards.map { (dir, b) ->
            val bitmap = context.assets.open("$dir/${b.getString("photo")}").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })!!
            }
            Mat().also { Utils.bitmapToMat(bitmap, it) }
        }
        val detect = mutableListOf<Long>()
        val model = mutableListOf<Long>()
        val rules = mutableListOf<Long>()
        SquareClassifier.fromAssets(context).use { classifier ->
            repeat(ROUNDS) { round ->
                for (photo in photos) {
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val detection = BoardDetector.detect(photo)!!
                    val t1 = SystemClock.elapsedRealtimeNanos()
                    val color = BoardDetector.warp(photo, detection.quad)
                    val board = Mat().also { Imgproc.cvtColor(color, it, Imgproc.COLOR_RGBA2GRAY) }
                    val logits = (0 until 4).map { k -> classifier.logits(SquareCropper.crop(SquareCropper.rotate(board, k))) }
                    val t2 = SystemClock.elapsedRealtimeNanos()
                    BoardReader.read(logits)
                    val t3 = SystemClock.elapsedRealtimeNanos()
                    if (round > 0) {  // la primera vuelta solo calienta
                        detect += (t1 - t0) / 1_000_000
                        model += (t2 - t1) / 1_000_000
                        rules += (t3 - t2) / 1_000_000
                    }
                }
            }
        }
        fun stats(v: List<Long>) = v.sorted().let { "mediana ${it[it.size / 2]} ms, máx ${it.last()} ms" }
        Log.i(TAG, "dispositivo ${android.os.Build.MODEL}, ${Runtime.getRuntime().availableProcessors()} núcleos")
        Log.i(TAG, "detector: ${stats(detect)}")
        Log.i(TAG, "modelo (4 rotaciones): ${stats(model)}")
        Log.i(TAG, "rotación + reglas: ${stats(rules)}")
        Log.i(TAG, "total: ${stats(detect.indices.map { detect[it] + model[it] + rules[it] })}")
    }

    companion object {
        private const val TAG = "ScanBenchmark"
        private const val ROUNDS = 4

        @BeforeClass
        @JvmStatic
        fun loadOpenCv() {
            check(OpenCVLoader.initLocal())
        }
    }
}
