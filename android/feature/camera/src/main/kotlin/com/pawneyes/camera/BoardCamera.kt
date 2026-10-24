package com.pawneyes.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import org.opencv.android.OpenCVLoader

private val Searching = Color(0xFFFFC107)
private val Locked = Color(0xFF4CAF50)

/**
 * Cámara con el contorno del tablero en vivo. Cuando el tablero se queda quieto
 * hace la foto sola (o con el botón) y la entrega, ya derecha, a `onCaptured`.
 */
@Composable
fun BoardCamera(onCaptured: (Bitmap) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        when {
            granted -> CameraContent(onCaptured, onClose)
            asked -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.camera_permission), color = Color.White)
                Button(onClick = { request.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.camera_permission_retry))
                }
                OutlinedButton(onClick = onClose) { Text(stringResource(R.string.camera_close)) }
            }
        }
    }
}

@OptIn(TransformExperimental::class)
@Composable
private fun CameraContent(onCaptured: (Bitmap) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    var quad by remember { mutableStateOf<FloatArray?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var capturing by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    // Zoom y linterna (#22). El pellizco sobre la vista ya hace zoom; el deslizador lo hace visible.
    var zoom by remember { mutableStateOf(1f) }
    var zoomRange by remember { mutableStateOf(1f..1f) }
    var torch by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }
    val tracker = remember { StabilityTracker() }
    val executor = remember { Executors.newSingleThreadExecutor() }

    val controller = remember {
        check(OpenCVLoader.initLocal()) { "no se pudo cargar OpenCV" }
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
            imageAnalysisResolutionSelector = selector(Size(1280, 960))
            // El escáner trabaja a 1200 px: no hace falta más resolución que unos 2000.
            imageCaptureResolutionSelector = selector(Size(2048, 1536))
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }

    fun capture() {
        if (capturing) return
        capturing = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        controller.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = image.use { it.toUprightBitmap() }
                ContextCompat.getMainExecutor(context).execute { onCaptured(bitmap) }
            }

            override fun onError(exception: ImageCaptureException) {
                ContextCompat.getMainExecutor(context).execute {
                    capturing = false
                    tracker.reset()
                }
            }
        })
    }

    DisposableEffect(controller) {
        controller.setImageAnalysisAnalyzer(executor, LiveBoardAnalyzer { found, frame ->
            tracker.update(found)  // la quietud se mide en coordenadas del fotograma
            val stable = tracker.isStable
            val p = tracker.progress
            ContextCompat.getMainExecutor(context).execute {
                // previewView.outputTransform solo puede leerse en el hilo principal.
                val target = previewView?.outputTransform
                quad = if (found != null && frame != null && target != null) {
                    found.copyOf().also { CoordinateTransform(frame, target).mapPoints(it) }
                } else {
                    null
                }
                progress = p
                if (stable) capture()
            }
        })
        controller.bindToLifecycle(lifecycleOwner)
        controller.zoomState.observe(lifecycleOwner) { z ->
            zoom = z.zoomRatio
            zoomRange = z.minZoomRatio.coerceAtLeast(1f)..minOf(z.maxZoomRatio, MAX_ZOOM)
            // La cámara ya está abierta: se sabe si tiene flash.
            hasTorch = controller.cameraInfo?.hasFlashUnit() == true
        }
        controller.torchState.observe(lifecycleOwner) { torch = it == TorchState.ON }
        onDispose {
            controller.zoomState.removeObservers(lifecycleOwner)
            controller.torchState.removeObservers(lifecycleOwner)
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val q = quad ?: return@Canvas
            val path = Path().apply {
                moveTo(q[0], q[1])
                for (i in 1 until 4) lineTo(q[2 * i], q[2 * i + 1])
                close()
            }
            val colour = if (progress >= 1f) Locked else Searching
            drawPath(path, colour, style = Stroke(width = 4.dp.toPx()))
            for (i in 0 until 4) drawCircle(colour, 6.dp.toPx(), Offset(q[2 * i], q[2 * i + 1]))
        }
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp)
                .background(Color(0x99000000), MaterialTheme.shapes.medium).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(
                    when {
                        capturing -> R.string.camera_capturing
                        quad == null -> R.string.camera_searching
                        else -> R.string.camera_hold_still
                    },
                ),
                color = Color.White,
            )
            if (quad != null && !capturing) {
                LinearProgressIndicator(progress = { progress }, Modifier.padding(top = 8.dp))
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Solo si la cámara tiene zoom o linterna (la del emulador, p. ej., no tiene ninguno).
            val zoomable = zoomRange.endInclusive > zoomRange.start + 0.05f
            if (zoomable || hasTorch) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0x99000000), MaterialTheme.shapes.medium)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (zoomable) {
                        Text(stringResource(R.string.camera_zoom, zoom), color = Color.White)
                        Slider(
                            value = zoom.coerceIn(zoomRange),
                            onValueChange = { controller.setZoomRatio(it) },
                            valueRange = zoomRange,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (hasTorch) {
                        TextButton(onClick = { controller.enableTorch(!torch) }) {
                            Text(stringResource(if (torch) R.string.camera_torch_off else R.string.camera_torch_on), color = Color.White)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onClose) { Text(stringResource(R.string.camera_close), color = Color.White) }
                Button(onClick = ::capture, enabled = !capturing) { Text(stringResource(R.string.camera_shoot)) }
            }
        }
    }
}

/** Más zoom digital ya no aporta: el escáner necesita detalle real. */
private const val MAX_ZOOM = 4f

private fun selector(size: Size) = ResolutionSelector.Builder()
    .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
    .build()

/** La foto capturada, girada según cómo se sujetaba el móvil. */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val bitmap = toBitmap()
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return bitmap
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
}
