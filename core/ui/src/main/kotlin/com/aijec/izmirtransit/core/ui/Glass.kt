package com.aijec.izmirtransit.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.aijec.izmirtransit.core.model.UiStyle

val LocalUiStyle = staticCompositionLocalOf { UiStyle.CALM }

/** True on the AMOLED theme: pure black canvas, glass glows dimmed. */
val LocalAmoled = staticCompositionLocalOf { false }

val Calm.style: UiStyle
    @Composable @ReadOnlyComposable get() = LocalUiStyle.current

/**
 * The surface every panel, pill and floating button sits on.
 *
 * Calm: an opaque panel ([overMap] makes it slightly see-through with a hairline).
 *
 * Liquid glass, like iOS 26:
 * - Light: frosted white with a soft top sheen and a bright rim.
 * - Dark: smoky glass with no sheen, a faint fill, and a thin rim brightest at the top-left
 *   where light would catch it.
 *
 * A real backdrop blur isn't possible over Google's map (a separate surface Compose can't
 * sample), so panes over the map are more opaque to keep their text legible. Behind ordinary
 * screens a colour wash ([glassBackdrop]) gives the glass something to show.
 */
@Composable
fun Modifier.calmSurface(shape: Shape, overMap: Boolean = false): Modifier {
    val colors = Calm.colors
    if (Calm.style == UiStyle.LIQUID_GLASS) {
        return if (colors.isDark) darkGlass(shape, overMap, LocalAmoled.current) else lightGlass(shape, overMap)
    }
    return clip(shape)
        .background(if (overMap) colors.panelGlass else colors.panel)
        .then(if (overMap) Modifier.border(0.5.dp, colors.hairline, shape) else Modifier)
}

/** The fill of a selected segment or tab sitting on a [calmSurface]. */
@Composable
fun Calm.selectedFill(): Color {
    val colors = Calm.colors
    return when {
        style == UiStyle.LIQUID_GLASS && colors.isDark -> Color.White.copy(alpha = 0.13f)
        style == UiStyle.LIQUID_GLASS -> Color.White.copy(alpha = 0.9f)
        else -> colors.panel
    }
}

private fun Modifier.lightGlass(shape: Shape, overMap: Boolean): Modifier {
    val (top, bottom) = if (overMap) 0.92f to 0.84f else 0.72f to 0.48f
    val fill = Brush.verticalGradient(listOf(Color.White.copy(alpha = top), Color.White.copy(alpha = bottom)))
    val rim = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.7f)))
    return clip(shape)
        .background(fill)
        .drawWithCache {
            // Light catching the top of the pane.
            val highlight = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.45f), 0.45f to Color.Transparent, endY = size.height)
            onDrawWithContent {
                drawRect(highlight)
                drawContent()
            }
        }
        .border(1.dp, rim, shape)
}

private fun Modifier.darkGlass(shape: Shape, overMap: Boolean, amoled: Boolean): Modifier {
    // Over the map the pane must hide the streets behind its text; elsewhere it stays faint.
    val fill = if (overMap) {
        val base = if (amoled) Color(0xFF07090D) else Color(0xFF0E1626)
        Brush.linearGradient(listOf(base.copy(alpha = 0.90f), base.copy(alpha = 0.84f)))
    } else {
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f)))
    }
    // A thin rim, brightest at the top-left, fading, catching a little again bottom-right.
    // A linear gradient with default ends spans the pane's own diagonal.
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.34f),
        0.45f to Color.White.copy(alpha = 0.07f),
        1f to Color.White.copy(alpha = 0.18f),
    )
    return clip(shape).background(fill).border(1.dp, rim, shape)
}

/**
 * The colour wash behind liquid-glass screens.
 * - Light: soft accent and sky-blue glows on a pale canvas.
 * - Dark: near black with a deep blue glow rising from the bottom right, like iOS 26's
 *   wallpapers. AMOLED keeps the black truly black, with only a dim glow.
 */
@Composable
fun Modifier.glassBackdrop(): Modifier {
    val colors = Calm.colors
    val amoled = LocalAmoled.current
    if (!colors.isDark) {
        val first = colors.accent.copy(alpha = 0.35f)
        val second = Color(0xFF29A9E0).copy(alpha = 0.28f)
        val third = Color(0xFFE3262E).copy(alpha = 0.12f)
        return background(Color(0xFFE8EDF6)).drawWithCache {
            val w = size.width
            val h = size.height
            val glowA = Brush.radialGradient(listOf(first, Color.Transparent), center = Offset(w * 0.1f, h * 0.12f), radius = w * 0.9f)
            val glowB = Brush.radialGradient(listOf(second, Color.Transparent), center = Offset(w * 0.95f, h * 0.55f), radius = w * 0.85f)
            val glowC = Brush.radialGradient(listOf(third, Color.Transparent), center = Offset(w * 0.2f, h * 0.95f), radius = w * 0.8f)
            onDrawBehind {
                drawRect(glowA)
                drawRect(glowB)
                drawRect(glowC)
            }
        }
    }
    val base = if (amoled) Color.Black else Color(0xFF04060C)
    val blue = Color(0xFF1C4FA8).copy(alpha = if (amoled) 0.35f else 0.62f)
    val violet = Color(0xFF4B36B8).copy(alpha = if (amoled) 0.14f else 0.26f)
    val accent = colors.accent.copy(alpha = if (amoled) 0.08f else 0.14f)
    return background(base).drawWithCache {
        val w = size.width
        val h = size.height
        val glowBlue = Brush.radialGradient(listOf(blue, Color.Transparent), center = Offset(w * 1.0f, h * 0.92f), radius = w * 1.2f)
        val glowViolet = Brush.radialGradient(listOf(violet, Color.Transparent), center = Offset(w * 0.0f, h * 0.55f), radius = w * 0.9f)
        val glowAccent = Brush.radialGradient(listOf(accent, Color.Transparent), center = Offset(w * 0.15f, h * 0.05f), radius = w * 0.8f)
        onDrawBehind {
            drawRect(glowBlue)
            drawRect(glowViolet)
            drawRect(glowAccent)
        }
    }
}
