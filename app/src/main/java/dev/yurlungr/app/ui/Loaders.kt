package dev.yurlungr.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Loading indicators used instead of spinners: they read as "working" without the busy
 * rotation, and match the app's soft motion.
 */

/** Three dots rising and brightening in a smooth wave. */
@Composable
fun WaveDots(modifier: Modifier = Modifier, dotSize: Dp = 7.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "wave-phase",
    )
    Row(
        modifier.height(dotSize * 2.2f),
        horizontalArrangement = Arrangement.spacedBy(dotSize * 0.7f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            // 0..1, each dot trailing the previous one.
            val wave = (sin(phase - i * 0.9f) + 1f) / 2f
            Box(
                Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        translationY = -wave * dotSize.toPx() * 0.55f
                        val s = 0.75f + 0.25f * wave
                        scaleX = s
                        scaleY = s
                        alpha = 0.35f + 0.65f * wave
                    }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

private val FlowColors
    @Composable get() = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.primary,
    )

/** Indeterminate progress: a gradient flowing along a rounded track. */
@Composable
fun FlowBar(modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val shift by rememberInfiniteTransition(label = "flow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "flow-shift",
    )
    val colors = FlowColors
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        val span = size.width * 0.8f
        val x = shift * span * 2
        drawRect(
            Brush.linearGradient(colors, start = Offset(x - span, 0f), end = Offset(x, 0f), tileMode = TileMode.Mirror),
        )
    }
}

/** Determinate progress that glides to each new value instead of jumping. */
@Composable
fun SmoothProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(Motion.LONG, easing = Motion.Emphasized), label = "progress")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(FlowColors.take(3))),
        )
    }
}
