package com.aijec.izmirtransit.core.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The app's motion curve: quick out, long soft settle (the mockups' cubic-bezier(.2,.9,.25,1)). */
val SettleEasing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

/** A spring with a little overshoot, for panels and the tab pill. */
fun <T> bouncySpring() = spring<T>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)

/**
 * True when the user turned animations off (Settings › Accessibility › Remove animations,
 * which sets the animator duration scale to 0). Decorative motion then stays still.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * A screen's entrance: its cards rise and fade in one after another, the first time the
 * screen is shown (not when coming back to it, and not for items scrolled to later).
 */
class Entrance internal constructor(active: Boolean) {
    var active by mutableStateOf(active)
        internal set
}

@Composable
fun rememberEntrance(): Entrance {
    val reduced = rememberReducedMotion()
    var played by rememberSaveable { mutableStateOf(false) }
    val entrance = remember { Entrance(active = !played && !reduced) }
    LaunchedEffect(Unit) {
        delay(ENTRANCE_WINDOW_MILLIS)
        played = true
        entrance.active = false
    }
    return entrance
}

/** The [index]th card of an [Entrance]: waits its turn, then rises 18 dp and fades in. */
fun Modifier.entrance(entrance: Entrance, index: Int): Modifier = composed {
    if (!entrance.active) return@composed Modifier
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(MAX_STAGGERED) * STAGGER_MILLIS)
        progress.animateTo(1f, tween(ENTRANCE_MILLIS, easing = SettleEasing))
    }
    graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 18.dp.toPx()
        val scale = 0.98f + 0.02f * p
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Text that rolls when it changes, like a departure board: the old value slides up and out,
 * the new one comes up from below. Used for live minutes.
 */
@Composable
fun RollingText(value: String, modifier: Modifier = Modifier, content: @Composable (String) -> Unit) {
    if (rememberReducedMotion()) {
        content(value)
        return
    }
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            (slideInVertically(tween(380, easing = SettleEasing)) { it * 6 / 10 } + fadeIn(tween(380))) togetherWith
                (slideOutVertically(tween(300, easing = SettleEasing)) { -it * 6 / 10 } + fadeOut(tween(200))) using
                SizeTransform(clip = true)
        },
        label = "rolling",
    ) { content(it) }
}

private const val STAGGER_MILLIS = 55L
private const val MAX_STAGGERED = 8
private const val ENTRANCE_MILLIS = 560
private const val ENTRANCE_WINDOW_MILLIS = 1_400L
