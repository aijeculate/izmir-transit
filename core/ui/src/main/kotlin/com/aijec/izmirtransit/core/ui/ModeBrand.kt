package com.aijec.izmirtransit.core.ui

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aijec.izmirtransit.core.model.TransitMode

/**
 * Each operator's two colours, taken from its logo, so a mode is recognisable at a glance
 * anywhere in the app: ESHOT red and white, Metro İzmir blue and red, Tram İzmir green and
 * blue, İZBAN blue and white, İzdeniz navy and sky blue. Only the colours are used, never
 * the logos themselves.
 */
data class ModeBrand(val primary: Color, val secondary: Color, val onPrimary: Color = Color.White)

object ModeBrands {
    val Bus = ModeBrand(primary = Color(0xFFD71920), secondary = Color.White)
    val Metro = ModeBrand(primary = Color(0xFF2A52A3), secondary = Color(0xFFE3262E))
    val Tram = ModeBrand(primary = Color(0xFF3DA548), secondary = Color(0xFF233F95))
    val Izban = ModeBrand(primary = Color(0xFF0B5BA8), secondary = Color.White)
    val Ferry = ModeBrand(primary = Color(0xFF1D3C8F), secondary = Color(0xFF29A9E0))

    fun of(mode: TransitMode): ModeBrand = when (mode) {
        TransitMode.BUS -> Bus
        TransitMode.METRO -> Metro
        TransitMode.TRAM -> Tram
        TransitMode.IZBAN -> Izban
        TransitMode.FERRY -> Ferry
    }
}

/** The brand colour as text or an outline on the panel: lightened in dark mode to stay legible. */
@Composable
fun ModeBrand.ink(): Color = if (Calm.colors.isDark) lerp(primary, Color.White, 0.42f) else primary

/** The mode's pictogram. */
fun modeGlyph(mode: TransitMode): ImageVector = when (mode) {
    TransitMode.BUS -> Icons.Filled.DirectionsBus
    TransitMode.METRO -> Icons.Filled.Subway
    TransitMode.TRAM -> Icons.Filled.Tram
    TransitMode.IZBAN -> Icons.Filled.Train
    TransitMode.FERRY -> Icons.Filled.DirectionsBoat
}

/**
 * A rounded tile in the operator's main colour with its pictogram, and the second colour as
 * a stripe along the bottom, echoing the two bands of the logos.
 */
@Composable
fun ModeIcon(mode: TransitMode, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val brand = ModeBrands.of(mode)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(brand.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            modeGlyph(mode),
            contentDescription = null,
            tint = brand.onPrimary,
            modifier = Modifier.size(size * 0.56f).offset(y = -size * 0.07f),
        )
        // Inside the tile, not on its edge: a white stripe must not melt into a white panel.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = size * 0.16f, end = size * 0.16f, bottom = size * 0.1f)) {
            Box(Modifier.fillMaxWidth().height(size * 0.07f).clip(RoundedCornerShape(50)).background(brand.secondary))
        }
    }
}
