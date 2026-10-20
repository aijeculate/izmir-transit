package com.aijec.izmirtransit.core.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.ui.modeGlyph
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.rememberComposeBitmapDescriptor

// Marker artwork is drawn with Compose and turned into bitmaps once per look (the keys).
// Stops are rounded badges with the mode's pictogram rather than bare dots, so a bus stop,
// a tram stop and a pier read differently at a glance.

fun glyphFor(mode: TransitMode): ImageVector = modeGlyph(mode)

/** A filled badge: the stops of the selected line, and boarding points of a plan. */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
internal fun rememberFilledBadge(mode: TransitMode, color: Color, size: Dp = 24.dp): BitmapDescriptor =
    rememberComposeBitmapDescriptor(mode, color, size) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 3.5f))
                .background(Color.White)
                .padding(1.5.dp)
                .clip(RoundedCornerShape(size / 4f))
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyphFor(mode), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
        }
    }

/** An outlined badge on the map's panel colour: any stop in view, not tied to a line. */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
internal fun rememberOutlinedBadge(mode: TransitMode, color: Color, panel: Color, size: Dp = 22.dp): BitmapDescriptor =
    rememberComposeBitmapDescriptor(mode, color, panel, size) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 3.5f))
                .background(panel)
                .border(1.5.dp, color, RoundedCornerShape(size / 3.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyphFor(mode), contentDescription = null, tint = color, modifier = Modifier.size(size * 0.6f))
        }
    }

/** A small ringed disc: the selected line's stops when zoomed out, where badges would crowd. */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
internal fun rememberRingDot(color: Color, size: Dp = 11.dp): BitmapDescriptor =
    rememberComposeBitmapDescriptor(color, size) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White)
                .border(2.5.dp, color, CircleShape),
        )
    }

/** A live vehicle: a disc in its direction colour with the mode pictogram, ringed in white. */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
internal fun rememberVehicleIcon(mode: TransitMode, color: Color, size: Dp = 30.dp): BitmapDescriptor =
    rememberComposeBitmapDescriptor("vehicle", mode, color, size) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White)
                .padding(2.5.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyphFor(mode), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        }
    }

/** A white chevron stamped along a route line to show its direction of travel. */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
internal fun rememberChevron(size: Dp = 12.dp): BitmapDescriptor =
    rememberComposeBitmapDescriptor("chevron", size) {
        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(size))
    }

/** Start of a planned journey: a white-ringed accent disc. */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
internal fun rememberOriginDot(color: Color, size: Dp = 16.dp): BitmapDescriptor =
    rememberComposeBitmapDescriptor("origin", color, size) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White)
                .padding(3.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
