package com.pawneyes.board

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Barra de evaluación (#46): la parte clara es la de las blancas, abajo si el tablero se mira
 * desde las blancas. `white` va de 0 (ganan negras) a 1 (ganan blancas).
 */
@Composable
fun EvalBar(white: Float, flipped: Boolean, modifier: Modifier = Modifier) {
    val share by animateFloatAsState(white.coerceIn(0f, 1f), label = "evaluación")
    val shape = RoundedCornerShape(4.dp)
    // Con borde: si no, en modo claro la parte blanca se confunde con el fondo.
    Canvas(modifier.clip(shape).border(1.dp, MaterialTheme.colorScheme.outline, shape)) {
        val whiteHeight = size.height * share
        drawRect(BLACK)
        val top = if (flipped) 0f else size.height - whiteHeight
        drawRect(WHITE, Offset(0f, top), Size(size.width, whiteHeight))
    }
}

/** El tablero con la barra de evaluación a su izquierda; sin ella si `white` es null. */
@Composable
fun BoardWithEvalBar(white: Float?, flipped: Boolean, modifier: Modifier = Modifier, board: @Composable (Modifier) -> Unit) {
    if (white == null) return board(modifier.fillMaxWidth())
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EvalBar(white, flipped, Modifier.width(12.dp).fillMaxHeight())
        board(Modifier.weight(1f))
    }
}

private val WHITE = Color(0xFFF0F0F0)
private val BLACK = Color(0xFF3A3A3A)
