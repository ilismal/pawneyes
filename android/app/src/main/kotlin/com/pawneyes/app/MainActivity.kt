package com.pawneyes.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pawneyes.board.BoardEditor
import com.pawneyes.board.BoardEditorState
import com.pawneyes.board.rememberBoardEditorState
import com.pawneyes.camera.BoardCamera
import com.pawneyes.chess.Line

class MainActivity : ComponentActivity() {
    /** Foto recibida con «Compartir», pendiente de escanear. */
    private val shared = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) shared.value = intent.sharedImage()
        setContent { PawneyesApp(shared) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.sharedImage()?.let { shared.value = it }
    }
}

private fun Intent.sharedImage(): Uri? =
    if (action == Intent.ACTION_SEND && type?.startsWith("image/") == true) {
        IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        null
    }

@Composable
private fun PawneyesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PawneyesApp(shared: MutableState<Uri?>) {
    PawneyesTheme {
        val vm: ScanViewModel = viewModel()
        val analysisVm: AnalysisViewModel = viewModel()
        val editor = rememberBoardEditorState()
        var exploration by remember { mutableStateOf<Exploration?>(null) }
        // El motor analiza lo que se ve: la posición del editor o la de la línea que se recorre.
        val shown = exploration?.position ?: editor.position
        LaunchedEffect(shown) { analysisVm.onPosition(shown) }
        LifecycleEventEffect(Lifecycle.Event.ON_START) { analysisVm.setForeground(true) }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { analysisVm.setForeground(false) }
        var camera by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(camera, vm.state) { analysisVm.setBusy(camera || vm.state is ScanState.Scanning) }
        val scan = remember(vm, editor) { { uri: Uri -> vm.scan(uri) { editor.showScan(it) } } }
        val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> uri?.let(scan) }
        LaunchedEffect(shared.value) {
            shared.value?.let {
                shared.value = null
                camera = false
                scan(it)
            }
        }
        if (camera) {
            BackHandler { camera = false }
            BoardCamera(
                onCaptured = { photo ->
                    camera = false
                    vm.scan(photo) { editor.showScan(it) }
                },
                onClose = { camera = false },
            )
            return@PawneyesTheme
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        val idle = vm.state !is ScanState.Scanning
                        TextButton(onClick = { camera = true }, enabled = idle) {
                            Text(stringResource(R.string.scan_camera))
                        }
                        TextButton(
                            onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                            enabled = idle,
                        ) { Text(stringResource(R.string.scan_gallery)) }
                    },
                )
            },
        ) { padding ->
            val analysis by analysisVm.analysis.collectAsStateWithLifecycle()
            val arrows = if (analysisVm.enabled) analysis.bestArrow(shown) else emptyList()
            val panel: @Composable () -> Unit = {
                if (analysisVm.available) {
                    AnalysisPanel(
                        analysisVm.enabled, analysisVm::toggle, shown, analysis, analysisVm.error,
                        onLineClick = { line -> exploration = Exploration(Line(shown, line.pv), 1) },
                        // El ejercicio se lanza desde el editor, no desde el explorador.
                        onExercise = if (exploration == null) ({ analysisVm.startExercise(editor.position) }) else null,
                    )
                }
            }
            val context = LocalContext.current
            val explore = exploration
            val ex = analysisVm.exercise
            if (ex != null && explore == null) {
                BackHandler { analysisVm.exitExercise() }
                ExerciseScreen(
                    ex,
                    analysisVm.error,
                    onMove = analysisVm::play,
                    onRetry = analysisVm::retryExercise,
                    onSolution = analysisVm::showSolution,
                    onExploreSolution = {
                        ex.best?.let { exploration = Exploration(Line(ex.position, it.pv), 1) }
                        analysisVm.exitExercise()
                    },
                    onExit = analysisVm::exitExercise,
                    modifier = Modifier.padding(padding),
                )
            } else if (explore != null) {
                BackHandler { exploration = null }
                LineExplorer(
                    explore,
                    flipped = editor.flipped,
                    arrows = arrows,
                    onStep = { exploration = explore.at(it) },
                    onEdit = {
                        editor.load(explore.position)
                        exploration = null
                    },
                    onClose = { exploration = null },
                    // Se comparte la línea entera desde la posición de partida.
                    onSharePgn = { Sharing.sharePgn(context, explore.line.start, explore.line.moves) },
                    onShareImage = {
                        val last = explore.line.moves.getOrNull(explore.index - 1)
                        Sharing.shareImage(context, explore.position, editor.flipped, arrows,
                            last?.let { setOf(it.from, it.to) } ?: emptySet())
                    },
                    modifier = Modifier.padding(padding),
                    analysisPanel = panel,
                )
            } else {
                // Con el análisis activo, el PGN lleva también la mejor línea y su evaluación.
                val best = analysis?.takeIf { analysisVm.enabled && it.position == editor.position }?.lines?.firstOrNull()
                val share: @Composable () -> Unit = {
                    val bestLine = best?.let { Line(editor.position, it.pv) }
                    val comment = best?.let {
                        stringResource(R.string.share_engine_comment, it.depth, it.whiteScore(editor.position.sideToMove))
                    }
                    ShareButton(
                        onPgn = { Sharing.sharePgn(context, editor.position, bestLine?.moves.orEmpty(), comment) },
                        onImage = { Sharing.shareImage(context, editor.position, editor.flipped, arrows) },
                    )
                }
                BoardEditor(editor, Modifier.padding(padding), arrows = arrows, belowBoard = panel, shareButton = share) {
                    ScanPanel(vm.state, editor.marked.size, onDismiss = vm::dismiss)
                }
            }
        }
    }
}

/** Carga la lectura en el editor: blancas abajo y enroques por defecto si caben. */
private fun BoardEditorState.showScan(result: com.pawneyes.vision.ScanResult) {
    val p = result.position
    load(p.copy(castling = p.plausibleCastling()), result.doubtful)
    flipped = false
}

@Composable
private fun ScanPanel(state: ScanState, marked: Int, onDismiss: () -> Unit) {
    when (state) {
        ScanState.Idle -> Unit
        ScanState.Scanning -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Text(stringResource(R.string.scanning))
        }
        is ScanState.Done -> Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    state.result.board.asImageBitmap(),
                    contentDescription = stringResource(R.string.scan_image),
                    modifier = Modifier.size(120.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (marked > 0) pluralStringResource(R.plurals.scan_doubtful, marked, marked)
                        else stringResource(R.string.scan_no_doubts),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.hide)) }
                }
            }
        }
        ScanState.NoBoard -> Message(stringResource(R.string.scan_no_board), onDismiss)
        is ScanState.Failed -> Message(stringResource(R.string.scan_failed, state.message), onDismiss)
    }
}

@Composable
private fun Message(text: String, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.hide)) }
        }
    }
}
