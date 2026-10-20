package com.aijec.izmirtransit.core.map

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.model.geo.LatLng

/** Space the cards over the map take up; routes are fitted inside what is left. */
data class MapPadding(val start: Dp = 0.dp, val top: Dp = 0.dp, val end: Dp = 0.dp, val bottom: Dp = 0.dp)

/** The visible area after the camera stops moving. */
data class Viewport(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
    val zoom: Float,
)

/** Asks the map to move; a new [id] repeats a move to the same place. */
data class CameraRequest(val id: Int, val target: LatLng, val zoom: Float = 15.5f)

/** A planned journey drawn on the map: walking legs dotted, rides in their line colour. */
data class MapPlan(
    val legs: List<PlanLegShape>,
    val origin: LatLng,
    val destination: LatLng,
)

data class PlanLegShape(
    val points: List<LatLng>,
    /** Null for a walk. */
    val mode: TransitMode?,
    /** Line colour (ARGB), or null for the accent. */
    val color: Int?,
    val board: Stop? = null,
    val alight: Stop? = null,
)

/** Where the trip planner should take the user: a stop, a place, or a pressed point. */
data class PlanDestination(val name: String?, val location: LatLng)
