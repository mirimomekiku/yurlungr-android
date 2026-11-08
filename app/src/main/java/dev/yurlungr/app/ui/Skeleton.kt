package dev.yurlungr.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Loading placeholders that mirror the real layout, so content fades into place instead of
 * popping in after a spinner.
 */

/** A soft highlight sweeping across the placeholder. */
fun Modifier.shimmer(shape: Shape = RoundedCornerShape(8.dp)): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceBright
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-sweep",
    )
    clip(shape).background(
        Brush.linearGradient(
            colors = listOf(base, highlight.copy(alpha = 0.55f), base),
            start = Offset(sweep * 600f, 0f),
            end = Offset(sweep * 600f + 400f, 200f),
        ),
    )
}

@Composable
fun SkeletonLine(modifier: Modifier = Modifier, width: Float = 1f, height: Dp = 12.dp) {
    Box(modifier.fillMaxWidth(width).height(height).shimmer(RoundedCornerShape(50)))
}

/** A title + subtitle row, like a list item or card header. */
@Composable
fun SkeletonListItem(modifier: Modifier = Modifier, leadingDot: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leadingDot) {
            Box(Modifier.size(10.dp).shimmer(CircleShape))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonLine(width = 0.55f, height = 14.dp)
            SkeletonLine(width = 0.35f, height = 10.dp)
        }
    }
}

/** A card-shaped placeholder with a few lines. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier, lines: Int = 2) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        SkeletonLine(width = 0.5f, height = 14.dp)
        repeat(lines - 1) { i -> SkeletonLine(width = if (i % 2 == 0) 0.85f else 0.65f, height = 10.dp) }
    }
}

/** A conversation taking shape: a user bubble on the right, an answer's lines on the left. */
@Composable
fun SkeletonChat(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.width(190.dp).height(40.dp).shimmer(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)))
        }
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SkeletonLine(width = 0.92f)
            SkeletonLine(width = 0.97f)
            SkeletonLine(width = 0.74f)
        }
        Box(Modifier.fillMaxWidth().height(52.dp).shimmer(RoundedCornerShape(12.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SkeletonLine(width = 0.88f)
            SkeletonLine(width = 0.6f)
        }
    }
}

/** Code-like lines for the file viewer. */
@Composable
fun SkeletonCode(modifier: Modifier = Modifier, lines: Int = 18) {
    val widths = listOf(0.45f, 0.7f, 0.62f, 0.3f, 0.8f, 0.55f, 0.68f, 0.4f, 0.75f, 0.5f)
    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        repeat(lines) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(18.dp).height(10.dp).shimmer(RoundedCornerShape(50)))
                Spacer(Modifier.width(12.dp))
                SkeletonLine(width = widths[i % widths.size], height = 10.dp)
            }
        }
    }
}
