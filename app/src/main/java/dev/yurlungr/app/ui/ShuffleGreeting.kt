package dev.yurlungr.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

private val GREETINGS = listOf(
    "What are we building?",
    "What should we ship today?",
    "Got a bug to squash?",
    "Let's build something great.",
    "Where should we start?",
    "Ready when you are.",
    "What's on your mind?",
    "Pick up where you left off?",
    "Time to make an app?",
    "What are we fixing today?",
    "Let's write some code.",
    "Idea in your pocket?",
)

/** Three-stop palettes the gradient drifts between. */
private val PALETTES = listOf(
    listOf(Color(0xFF4F8BF9), Color(0xFFB36BF0), Color(0xFFE5677A)), // blue → violet → rose
    listOf(Color(0xFF22C1C3), Color(0xFF4F8BF9), Color(0xFF8E6CF0)), // teal → blue → indigo
    listOf(Color(0xFFFF8A4C), Color(0xFFF0568C), Color(0xFFA35CF0)), // orange → pink → purple
    listOf(Color(0xFF3DDC97), Color(0xFF27B5D9), Color(0xFF5B7CFA)), // mint → cyan → blue
    listOf(Color(0xFFFFC145), Color(0xFFFF6B6B), Color(0xFFC44CF0)), // amber → coral → magenta
)

private const val SHOW_MILLIS = 4_200L
private const val COLOR_MILLIS = 1_400

private fun nextIndex(current: Int, size: Int): Int {
    if (size < 2) return current
    var next: Int
    do next = Random.nextInt(size) while (next == current)
    return next
}

/**
 * The start screen's greeting: a random sentence that reshuffles every few seconds (or on tap),
 * sliding up into place, drawn with a gradient that flows continuously and drifts to a new palette
 * on each shuffle.
 */
@Composable
fun ShuffleGreeting(modifier: Modifier = Modifier) {
    var sentence by rememberSaveable { mutableIntStateOf(Random.nextInt(GREETINGS.size)) }
    var palette by rememberSaveable { mutableIntStateOf(Random.nextInt(PALETTES.size)) }
    var shuffles by remember { mutableIntStateOf(0) }

    fun shuffle() {
        sentence = nextIndex(sentence, GREETINGS.size)
        palette = nextIndex(palette, PALETTES.size)
        shuffles++
    }

    // Restarted by a manual shuffle, so a tap doesn't get overtaken a moment later.
    LaunchedEffect(shuffles) {
        while (true) {
            delay(SHOW_MILLIS)
            sentence = nextIndex(sentence, GREETINGS.size)
            palette = nextIndex(palette, PALETTES.size)
        }
    }

    val colors = PALETTES[palette].mapIndexed { i, c ->
        animateColorAsState(c, tween(COLOR_MILLIS, easing = Motion.Emphasized), label = "greeting-color-$i").value
    }
    val flow by rememberInfiniteTransition(label = "greeting-flow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5_000, easing = LinearEasing), RepeatMode.Restart),
        label = "greeting-flow-offset",
    )
    val span = 900f
    val brush = Brush.linearGradient(
        colors = colors + colors.first(),
        start = Offset(flow * span * 2 - span, 0f),
        end = Offset(flow * span * 2, span * 0.25f),
        tileMode = TileMode.Mirror,
    )

    AnimatedContent(
        targetState = sentence,
        transitionSpec = {
            (slideInVertically(tween(Motion.LONG, easing = Motion.EmphasizedDecelerate)) { it / 2 } + fadeIn(tween(Motion.MEDIUM)))
                .togetherWith(slideOutVertically(tween(Motion.MEDIUM, easing = Motion.EmphasizedAccelerate)) { -it / 2 } + fadeOut(tween(Motion.SHORT)))
                .using(SizeTransform(clip = false))
        },
        label = "greeting",
        modifier = modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { shuffle() },
    ) { index ->
        Text(
            GREETINGS[index],
            style = TextStyle(
                brush = brush,
                fontFamily = DisplayFont,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
