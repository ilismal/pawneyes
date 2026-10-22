package com.pawneyes.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import com.pawneyes.vision.BoardDetector
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Busca el tablero en cada fotograma de la vista previa (formato RGBA).
 *
 * Entrega las esquinas en coordenadas del fotograma junto con su geometría
 * (tamaño y giro); quien dibuja las pasa a coordenadas de la vista con
 * CoordinateTransform. No se usa COORDINATE_SYSTEM_VIEW_REFERENCED: en el
 * Galaxy S25 su matriz espera coordenadas del sensor completo (4080×3060), no
 * del fotograma de análisis, y el contorno salía encogido y desplazado.
 */
class LiveBoardAnalyzer(
    private val onQuad: (quad: FloatArray?, frame: OutputTransform?) -> Unit,
) : ImageAnalysis.Analyzer {
    private val transforms = ImageProxyTransformFactory().apply { isUsingRotationDegrees = true }

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val frame = Mat(it.height, it.width, CvType.CV_8UC4, plane.buffer, plane.rowStride.toLong())
            val detection = BoardDetector.detect(frame, refine = false)
            frame.release()
            val quad = detection?.quad?.let { q -> FloatArray(8) { i -> q[i].toFloat() } }
            onQuad(quad, quad?.let { _ -> transforms.getOutputTransform(it) })
        }
    }
}
