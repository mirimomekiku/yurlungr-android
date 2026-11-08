package dev.yurlungr.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/** Shared motion tokens (Material 3 "emphasized" easing and durations). */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 420

    fun <T> fast() = tween<T>(MEDIUM, easing = Emphasized)
}

enum class NavDirection { Push, Pop, Replace }

/** Screen transitions: push slides in from the right, pop slides back, sidebar switches crossfade. */
fun <S> AnimatedContentTransitionScope<S>.screenTransition(direction: NavDirection): ContentTransform = when (direction) {
    NavDirection.Push ->
        (slideInHorizontally(tween(Motion.LONG, easing = Motion.EmphasizedDecelerate)) { it / 3 } + fadeIn(tween(Motion.MEDIUM)))
            .togetherWith(slideOutHorizontally(tween(Motion.LONG, easing = Motion.Emphasized)) { -it / 8 } + fadeOut(tween(Motion.SHORT)))
    NavDirection.Pop ->
        (slideInHorizontally(tween(Motion.LONG, easing = Motion.EmphasizedDecelerate)) { -it / 8 } + fadeIn(tween(Motion.MEDIUM)))
            .togetherWith(slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.EmphasizedAccelerate)) { it / 3 } + fadeOut(tween(Motion.SHORT)))
    NavDirection.Replace ->
        (fadeIn(tween(Motion.MEDIUM, delayMillis = 60)) + scaleIn(tween(Motion.MEDIUM, easing = Motion.Emphasized), initialScale = 0.97f))
            .togetherWith(fadeOut(tween(Motion.SHORT)))
}

/** Fades/rises its content in once, after [delayMillis] — for staggered entrances. */
@Composable
fun Appear(delayMillis: Int = 0, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(Motion.LONG, delayMillis)) +
            slideInVertically(tween(Motion.LONG, delayMillis, easing = Motion.EmphasizedDecelerate)) { it / 3 },
    ) { content() }
}
