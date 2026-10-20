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

val Calm.style: UiStyle
    @Composable @ReadOnlyComposable get() = LocalUiStyle.current

/**
 * The surface every panel, pill and floating button sits on.
 *
 * Calm: an opaque panel ([overMap] makes it slightly see-through with a hairline).
 * Liquid glass: a translucent pane with a light top sheen and a bright rim, like iOS. A real
 * backdrop blur isn't possible over Google's map (a separate surface Compose can't sample),
 * so the glass is made from translucency and light instead; behind ordinary screens a
 * soft colour wash ([glassBackdrop]) gives it something to refract.
 */
@Composable
fun Modifier.calmSurface(shape: Shape, overMap: Boolean = false): Modifier {
    val colors = Calm.colors
    if (Calm.style == UiStyle.LIQUID_GLASS) return liquidGlass(shape, colors.isDark, overMap)
    return clip(shape)
        .background(if (overMap) colors.panelGlass else colors.panel)
        .then(if (overMap) Modifier.border(0.5.dp, colors.hairline, shape) else Modifier)
}

private fun Modifier.liquidGlass(shape: Shape, dark: Boolean, overMap: Boolean): Modifier {
    // Over the map there is no blur to soften busy streets and labels behind the pane, so it
    // needs more body to keep its own text legible.
    val (top, bottom) = when {
        dark && overMap -> 0.93f to 0.88f
        dark -> 0.62f to 0.55f
        overMap -> 0.92f to 0.84f
        else -> 0.72f to 0.48f
    }
    val fill = if (dark) {
        Brush.verticalGradient(listOf(Color(0xFF2A3446).copy(alpha = top), Color(0xFF151C2A).copy(alpha = bottom)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = top), Color.White.copy(alpha = bottom)))
    }
    val rim = Brush.verticalGradient(
        if (dark) listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f))
        else listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.7f)),
    )
    val sheen = if (dark) 0.10f else 0.45f
    return clip(shape)
        .background(fill)
        .drawWithCache {
            // Light catching the top of the pane.
            val highlight = Brush.verticalGradient(
                0f to Color.White.copy(alpha = sheen),
                0.45f to Color.Transparent,
                startY = 0f,
                endY = size.height,
            )
            onDrawWithContent {
                drawRect(highlight)
                drawContent()
            }
        }
        .border(1.dp, rim, shape)
}

/**
 * The colour wash behind liquid-glass screens: soft accent and sky-blue glows on the
 * canvas, so translucent panes have colour to show through.
 */
@Composable
fun Modifier.glassBackdrop(): Modifier {
    val colors = Calm.colors
    val base = if (colors.isDark) Color(0xFF0B1020) else Color(0xFFE8EDF6)
    val first = colors.accent.copy(alpha = if (colors.isDark) 0.45f else 0.35f)
    val second = Color(0xFF29A9E0).copy(alpha = if (colors.isDark) 0.30f else 0.28f)
    val third = Color(0xFFE3262E).copy(alpha = if (colors.isDark) 0.16f else 0.12f)
    return background(base).drawWithCache {
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
