package com.aijec.izmirtransit.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aijec.izmirtransit.core.model.AccentTheme
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.model.ThemeMode

// "Calm glass": a soft blue-grey canvas, white rounded panels floating on it (or on the
// map), one blue accent. A fixed palette, deliberately not Material You dynamic colour,
// so the app has its own identity rather than the wallpaper's.

/** Colours Material's scheme has no slot for. Read with [Calm.colors]. */
@Immutable
data class CalmColors(
    val isDark: Boolean,
    val canvas: Color,
    val panel: Color,
    /** Panels floating over the map: slightly see-through. */
    val panelGlass: Color,
    val hairline: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val heart: Color,
    val warningSoft: Color,
    val onWarningSoft: Color,
    val pressed: Color,
)

private val LightCalm = CalmColors(
    isDark = false,
    canvas = Color(0xFFEEF1F5),
    panel = Color(0xFFFFFFFF),
    panelGlass = Color(0xF2FFFFFF),
    hairline = Color(0xFFE1E6ED),
    text = Color(0xFF1C2430),
    textSecondary = Color(0xFF6B7685),
    accent = Color(0xFF1F6FD1),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE6F0FB),
    onAccentSoft = Color(0xFF0C447C),
    heart = Color(0xFFD4537E),
    warningSoft = Color(0xFFFBF1DE),
    onWarningSoft = Color(0xFF6B4508),
    pressed = Color(0x0F1C2430),
)

private val DarkCalm = CalmColors(
    isDark = true,
    canvas = Color(0xFF0E1217),
    panel = Color(0xFF192028),
    panelGlass = Color(0xF2192028),
    hairline = Color(0xFF26303B),
    text = Color(0xFFE8EDF3),
    textSecondary = Color(0xFF97A2B0),
    accent = Color(0xFF7DB0FF),
    onAccent = Color(0xFF0B1A2E),
    accentSoft = Color(0xFF1C2F47),
    onAccentSoft = Color(0xFFCFE2FF),
    heart = Color(0xFFF08DAE),
    warningSoft = Color(0xFF3A2D14),
    onWarningSoft = Color(0xFFF5D9A3),
    pressed = Color(0x14E8EDF3),
)

private val LocalCalmColors = staticCompositionLocalOf { LightCalm }

private data class Accent(val accent: Color, val onAccent: Color, val soft: Color, val onSoft: Color)

/** Light and dark variants of each accent; contrast checked against the panels. */
private fun accentFor(theme: AccentTheme, dark: Boolean): Accent = when (theme) {
    AccentTheme.OCEAN -> if (dark) Accent(Color(0xFF7DB0FF), Color(0xFF0B1A2E), Color(0xFF1C2F47), Color(0xFFCFE2FF))
    else Accent(Color(0xFF1F6FD1), Color.White, Color(0xFFE6F0FB), Color(0xFF0C447C))
    AccentTheme.TEAL -> if (dark) Accent(Color(0xFF5ED3BE), Color(0xFF032A23), Color(0xFF12352F), Color(0xFFC7F1E8))
    else Accent(Color(0xFF0F7B6C), Color.White, Color(0xFFDDF3EE), Color(0xFF064D43))
    AccentTheme.VIOLET -> if (dark) Accent(Color(0xFFB9A6FF), Color(0xFF1E1440), Color(0xFF2A2148), Color(0xFFE4DCFF))
    else Accent(Color(0xFF6D4AE0), Color.White, Color(0xFFECE7FD), Color(0xFF3A2582))
    AccentTheme.CORAL -> if (dark) Accent(Color(0xFFFF9B7A), Color(0xFF3A1206), Color(0xFF3D2219), Color(0xFFFFDCCF))
    else Accent(Color(0xFFC94A24), Color.White, Color(0xFFFBE9E2), Color(0xFF6E2610))
    AccentTheme.GRAPHITE -> if (dark) Accent(Color(0xFFD5DEE9), Color(0xFF111820), Color(0xFF26303B), Color(0xFFE8EDF3))
    else Accent(Color(0xFF2F3A48), Color.White, Color(0xFFE7EAEE), Color(0xFF1C2430))
}

/** The swatch shown for each accent in Settings (its light-theme accent). */
fun accentSwatch(theme: AccentTheme): Color = accentFor(theme, dark = false).accent

object Calm {
    val colors: CalmColors
        @Composable @ReadOnlyComposable get() = LocalCalmColors.current

    /** Corner radii: panels are soft, controls are pills. */
    val PanelShape = RoundedCornerShape(22.dp)
    val CellShape = RoundedCornerShape(14.dp)
    val PillShape = RoundedCornerShape(50)
}

/** Inter, bundled (works offline, full Turkish character set), as a variable font. */
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

/** Use for anything that counts or ticks (ETAs, times) so digits don't jiggle. */
val TabularNumbers = "tnum"

private fun style(size: Int, weight: Int, line: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

private val CalmTypography = Typography(
    displaySmall = style(32, 600, 38, -0.8),
    headlineMedium = style(28, 600, 34, -0.6),
    headlineSmall = style(24, 600, 30, -0.4),
    titleLarge = style(20, 600, 26, -0.3),
    titleMedium = style(17, 600, 22, -0.2),
    titleSmall = style(15, 600, 20, -0.1),
    bodyLarge = style(16, 400, 22),
    bodyMedium = style(15, 400, 20),
    bodySmall = style(13, 400, 18),
    labelLarge = style(15, 500, 20),
    labelMedium = style(13, 500, 16),
    labelSmall = style(12, 500, 16),
)

@Composable
fun IzmirTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentTheme: AccentTheme = AccentTheme.OCEAN,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accent = accentFor(accentTheme, darkTheme)
    val calm = (if (darkTheme) DarkCalm else LightCalm).copy(
        accent = accent.accent,
        onAccent = accent.onAccent,
        accentSoft = accent.soft,
        onAccentSoft = accent.onSoft,
    )
    // Material components (text fields, progress, dialogs) pick up the same palette.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = calm.accent, onPrimary = calm.onAccent,
            primaryContainer = calm.accentSoft, onPrimaryContainer = calm.onAccentSoft,
            secondaryContainer = calm.accentSoft, onSecondaryContainer = calm.onAccentSoft,
            tertiary = calm.onWarningSoft, tertiaryContainer = calm.warningSoft, onTertiaryContainer = calm.onWarningSoft,
            background = calm.canvas, onBackground = calm.text,
            surface = calm.panel, onSurface = calm.text,
            surfaceVariant = calm.canvas, onSurfaceVariant = calm.textSecondary,
            surfaceContainer = calm.panel, surfaceContainerHigh = calm.panel, surfaceContainerLow = calm.panel,
            outline = calm.hairline, outlineVariant = calm.hairline,
        )
    } else {
        lightColorScheme(
            primary = calm.accent, onPrimary = calm.onAccent,
            primaryContainer = calm.accentSoft, onPrimaryContainer = calm.onAccentSoft,
            secondaryContainer = calm.accentSoft, onSecondaryContainer = calm.onAccentSoft,
            tertiary = calm.onWarningSoft, tertiaryContainer = calm.warningSoft, onTertiaryContainer = calm.onWarningSoft,
            background = calm.canvas, onBackground = calm.text,
            surface = calm.panel, onSurface = calm.text,
            surfaceVariant = calm.canvas, onSurfaceVariant = calm.textSecondary,
            surfaceContainer = calm.panel, surfaceContainerHigh = calm.panel, surfaceContainerLow = calm.panel,
            outline = calm.hairline, outlineVariant = calm.hairline,
        )
    }
    CompositionLocalProvider(LocalCalmColors provides calm) {
        MaterialTheme(
            colorScheme = scheme,
            typography = CalmTypography,
            shapes = Shapes(small = Calm.CellShape, medium = Calm.PanelShape, large = Calm.PanelShape),
            content = content,
        )
    }
}

/**
 * Fixed direction colours, deliberately not themed: they must stay distinguishable from
 * each other and from the map in both themes.
 */
object TransitColors {
    val Outbound = Color(0xFF1F6FD1)
    val Return = Color(0xFFE8710A)

    fun forDirection(direction: Direction): Color = if (direction == Direction.OUTBOUND) Outbound else Return

    /**
     * One colour per mode for stop markers and the layer list, where a stop may serve several
     * lines: metro's official blue, a tram magenta, İZBAN teal, İzdeniz blue. Buses use the accent.
     */
    fun forMode(mode: TransitMode, accent: Color): Color = when (mode) {
        TransitMode.BUS -> accent
        TransitMode.METRO -> Color(0xFF0038EF)
        TransitMode.TRAM -> Color(0xFFC2185B)
        TransitMode.IZBAN -> Color(0xFF00838F)
        TransitMode.FERRY -> Color(0xFF0097D7)
    }
}
