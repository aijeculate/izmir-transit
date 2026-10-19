package com.aijec.izmirtransit.core.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.aijec.izmirtransit.core.model.Direction

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    secondary = Color(0xFF00696D),
    tertiary = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    secondary = Color(0xFF4CD9DE),
    tertiary = Color(0xFFF2B8B5),
)

/** Material 3 with dynamic colour on Android 12+, light and dark. */
@Composable
fun IzmirTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/**
 * Fixed direction colours, deliberately not dynamic: they must stay distinguishable from
 * each other and from the map in both themes.
 */
object TransitColors {
    val Outbound = Color(0xFF1A73E8)
    val Return = Color(0xFFE8710A)

    fun forDirection(direction: Direction): Color = if (direction == Direction.OUTBOUND) Outbound else Return
}
