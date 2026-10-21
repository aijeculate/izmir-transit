package com.aijec.izmirtransit.core.map

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.saveable.rememberSaveable
import android.graphics.Point
import android.os.SystemClock
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.aijec.izmirtransit.core.data.live.BusMotion
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.LineRoute
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.OverviewRoute
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import com.aijec.izmirtransit.core.ui.Calm
import com.aijec.izmirtransit.core.ui.TransitColors
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.android.gms.maps.model.StrokeStyle
import com.google.android.gms.maps.model.StyleSpan
import com.google.android.gms.maps.model.TextureStyle
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import com.google.android.gms.maps.model.LatLng as GLatLng

private val IZMIR_CENTER = GLatLng(38.4237, 27.1428)
private val HideGoogleTransit = MapStyleOptions(
    """[{"featureType":"transit.station","stylers":[{"visibility":"off"}]}]""",
)
private const val VEHICLE_FRAME_MILLIS = 66L

/** Zoom bands that change how stops are drawn. */
private enum class StopDetail { NONE, DOTS, BADGES }

private fun LatLng.toGoogle() = GLatLng(latitude, longitude)
private fun GLatLng.toModel() = LatLng(latitude, longitude)

/**
 * The app's Google map: the overview of every route, one selected line (both directions,
 * cased and chevroned so its direction reads), live vehicles that glide between polls,
 * stop badges, and a planned journey.
 *
 * Gestures are the map's own. [onViewportChanged] reports the visible area whenever the
 * camera settles, so the caller can load the stops in view.
 */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun TransitMap(
    contentPadding: MapPadding,
    modifier: Modifier = Modifier,
    routes: List<LineRoute> = emptyList(),
    /** The selected line's mode, for its stop pictograms. */
    routeMode: TransitMode = TransitMode.BUS,
    buses: List<LiveBus> = emptyList(),
    overview: List<OverviewRoute> = emptyList(),
    areaStops: List<Stop> = emptyList(),
    highlightedStop: Stop? = null,
    plan: MapPlan? = null,
    pin: LatLng? = null,
    myLocationEnabled: Boolean = false,
    cameraRequest: CameraRequest? = null,
    /**
     * What the routes or plan belong to (a line code, a chosen journey). The camera fits them
     * only when this changes, never when the same line is redrawn (switching direction,
     * returning from another screen): the user's own zoom and position are kept.
     */
    fitKey: Any? = null,
    /**
     * Remembers the camera across screen rebuilds for this key (the Map tab is rebuilt
     * whenever the user goes Home and back): it reopens where it was, and an already fitted
     * line isn't fitted again.
     */
    memoryKey: String? = null,
    onStopClick: (Stop) -> Unit = {},
    onMapClick: () -> Unit = {},
    onMapLongClick: (LatLng) -> Unit = {},
    onViewportChanged: (Viewport) -> Unit = {},
) {
    val colors = Calm.colors
    val density = LocalDensity.current
    val remembered = memoryKey?.let { CameraMemory.positions[it] }
    val camera = rememberCameraPositionState { position = remembered ?: CameraPosition.fromLatLngZoom(IZMIR_CENTER, 11f) }
    var loaded by remember { mutableStateOf(false) }
    // Survives navigating away and back, like the camera position itself.
    var fitted by rememberSaveable { mutableStateOf(memoryKey?.let { CameraMemory.fitted[it] }) }
    LaunchedEffect(fitted) { memoryKey?.let { CameraMemory.fitted[it] = fitted } }
    // A camera request is carried out once: coming back to the map must not jump to it again.
    var handledRequest by rememberSaveable { mutableStateOf(memoryKey?.let { CameraMemory.handledRequest[it] } ?: 0) }
    val pendingRequest = cameraRequest != null && cameraRequest.id != handledRequest
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    val currentOnViewport by rememberUpdatedState(onViewportChanged)

    val detail by remember {
        derivedStateOf {
            val zoom = camera.position.zoom
            when {
                zoom < 12.5f -> StopDetail.NONE
                zoom < 14.8f -> StopDetail.DOTS
                else -> StopDetail.BADGES
            }
        }
    }

    // Vehicles: each poll feeds the motion model, and a ~15 fps loop moves them along the route.
    val motion = remember { BusMotion() }
    val measuredRoutes = remember(routes) { routes.filter { it.shape.size >= 2 }.associate { it.direction to MeasuredPolyline(it.shape) } }
    LaunchedEffect(buses, measuredRoutes) { motion.update(buses, measuredRoutes, SystemClock.elapsedRealtime()) }

    // Marker artwork.
    val chevron = rememberChevron()
    val ringOutbound = rememberRingDot(TransitColors.Outbound)
    val ringReturn = rememberRingDot(TransitColors.Return)
    val badgeOutbound = rememberFilledBadge(routeMode, TransitColors.Outbound)
    val badgeReturn = rememberFilledBadge(routeMode, TransitColors.Return)
    val vehicleOutbound = rememberVehicleIcon(routeMode, TransitColors.Outbound)
    val vehicleReturn = rememberVehicleIcon(routeMode, TransitColors.Return)
    val areaBadges = TransitMode.entries.associateWith { mode ->
        rememberOutlinedBadge(mode, TransitColors.forMode(mode, colors.accent), colors.panel)
    }
    val highlightBadges = TransitMode.entries.associateWith { mode ->
        rememberFilledBadge(mode, TransitColors.forMode(mode, colors.accent), size = 34.dp)
    }
    val planBadges = TransitMode.entries.associateWith { mode ->
        rememberFilledBadge(mode, TransitColors.forMode(mode, colors.accent), size = 28.dp)
    }
    val originDot = rememberOriginDot(colors.accent)

    val casing = if (colors.isDark) Color(0xFF0B1220) else Color.White
    val routeWidth = with(density) { 5.dp.toPx() }
    val casingWidth = with(density) { 8.dp.toPx() }
    // The selected line is wider so its direction chevrons are big enough to read.
    val selectedWidth = with(density) { 8.dp.toPx() }
    val selectedCasingWidth = with(density) { 11.dp.toPx() }

    GoogleMap(
        modifier = modifier.onSizeChanged { mapSize = it },
        cameraPositionState = camera,
        contentPadding = with(contentPadding) { PaddingValues(start, top, end, bottom) },
        mapColorScheme = if (colors.isDark) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT,
        properties = MapProperties(
            isMyLocationEnabled = myLocationEnabled,
            minZoomPreference = 8f,
            // Google's own transit station icons duplicated our stop badges (and stole taps).
            mapStyleOptions = HideGoogleTransit,
        ),
        uiSettings = MapUiSettings(
            compassEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
            zoomControlsEnabled = false,
            indoorLevelPickerEnabled = false,
        ),
        onMapLoaded = { loaded = true },
        onMapClick = { onMapClick() },
        onMapLongClick = { onMapLongClick(it.toModel()) },
    ) {
        // --- Overview. The ~900 bus routes are drawn into tiles in the background (as polylines
        // they made every pan stutter); rail and ferry stay polylines, a few dozen of them.
        // Fainter on dark themes: a light accent (Graphite is near white) shouts on black.
        val busColor = colors.accent.copy(alpha = if (colors.isDark) 0.2f else 0.3f).toArgb()
        val busRoutes = remember(overview) { overview.filter { it.mode == TransitMode.BUS }.map { it.points } }
        val busTiles = remember(busRoutes, busColor) { if (busRoutes.isEmpty()) null else RouteTileProvider(busRoutes, busColor, widthPx = 1.6f) }
        // At street level the faint bus web reads as extra roads; the stop badges take over.
        busTiles?.let { TileOverlay(it, fadeIn = false, visible = detail != StopDetail.BADGES, zIndex = 0f) }

        val drawnOverview = remember { mutableListOf<Polyline>() }
        MapEffect(overview, colors.accent, colors.isDark) { map ->
            drawnOverview.forEach { it.remove() }
            drawnOverview.clear()
            overview.forEachIndexed { i, route ->
                if (route.points.size < 2 || route.mode == TransitMode.BUS) return@forEachIndexed
                val points = route.points.map { it.toGoogle() }
                val options = when (route.mode) {
                    TransitMode.FERRY -> PolylineOptions()
                        .color(route.color ?: busColor)
                        .width(with(density) { 2.5.dp.toPx() })
                        .pattern(listOf(Dash(with(density) { 8.dp.toPx() }), Gap(with(density) { 6.dp.toPx() })))
                        .zIndex(1f)
                    else -> PolylineOptions().color(route.color ?: busColor).width(with(density) { 4.dp.toPx() }).zIndex(2f)
                }
                drawnOverview += map.addPolyline(options.addAll(points).jointType(JointType.ROUND))
                if (i % 60 == 59) yield()
            }
        }

        // --- Selected line: return first so outbound draws on top where they share a street.
        for (direction in listOf(Direction.RETURN, Direction.OUTBOUND)) key("route", direction) {
            val route = routes.firstOrNull { it.direction == direction }
            if (route != null && route.shape.size >= 2) {
            val points = remember(route) { route.shape.map { it.toGoogle() } }
            val color = TransitColors.forDirection(direction)
            com.google.maps.android.compose.Polyline(
                points = points,
                color = casing,
                width = selectedCasingWidth,
                jointType = JointType.ROUND,
                startCap = RoundCap(),
                endCap = RoundCap(),
                zIndex = 3f,
            )
            com.google.maps.android.compose.Polyline(
                points = points,
                spans = listOf(
                    StyleSpan(
                        StrokeStyle.colorBuilder(color.toArgb())
                            .stamp(TextureStyle.newBuilder(chevron).build())
                            .build(),
                    ),
                ),
                width = selectedWidth,
                jointType = JointType.ROUND,
                startCap = RoundCap(),
                endCap = RoundCap(),
                zIndex = 4f,
            )
            }
        }

        // --- The selected line's stops: dots when zoomed out, pictogram badges close up.
        if (detail != StopDetail.NONE) {
            for (route in routes) {
                val icon = when {
                    detail == StopDetail.DOTS && route.direction == Direction.OUTBOUND -> ringOutbound
                    detail == StopDetail.DOTS -> ringReturn
                    route.direction == Direction.OUTBOUND -> badgeOutbound
                    else -> badgeReturn
                }
                for (stop in route.stops) {
                    key("line-stop", route.direction, stop.code) {
                        Marker(
                            state = rememberUpdatedMarkerState(stop.location.toGoogle()),
                            icon = icon,
                            anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                            zIndex = 5f,
                            contentDescription = stop.name,
                            onClick = { onStopClick(stop); true },
                        )
                    }
                }
            }
        }

        // --- Every stop in view (any line) when zoomed in.
        val lineStopCodes = remember(routes) { routes.flatMap { r -> r.stops.map { it.code } }.toSet() }
        for (stop in areaStops) {
            if (stop.code in lineStopCodes || stop.code == highlightedStop?.code) continue
            key("area-stop", stop.code) {
                Marker(
                    state = rememberUpdatedMarkerState(stop.location.toGoogle()),
                    icon = areaBadges.getValue(stop.mode),
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    zIndex = 4.5f,
                    contentDescription = stop.name,
                    onClick = { onStopClick(stop); true },
                )
            }
        }

        // --- Live vehicles.
        if (buses.isNotEmpty()) Vehicles(motion, vehicleOutbound, vehicleReturn)

        // --- A planned journey.
        plan?.let { journey ->
            journey.legs.forEachIndexed { index, leg -> key("plan-leg", index) {
                val points = remember(leg) { leg.points.map { it.toGoogle() } }
                if (leg.mode == null) {
                    com.google.maps.android.compose.Polyline(
                        points = points,
                        color = colors.textSecondary,
                        width = with(density) { 4.dp.toPx() },
                        pattern = listOf(Dot(), Gap(with(density) { 5.dp.toPx() })),
                        zIndex = 6f,
                    )
                } else {
                    val color = leg.color?.let(::Color) ?: TransitColors.forMode(leg.mode, colors.accent)
                    com.google.maps.android.compose.Polyline(points, color = casing, width = casingWidth, jointType = JointType.ROUND, zIndex = 6f)
                    com.google.maps.android.compose.Polyline(points, color = color, width = routeWidth, jointType = JointType.ROUND, zIndex = 7f)
                    for (stop in listOfNotNull(leg.board, leg.alight)) {
                        key("plan-stop", index, stop.code) {
                            Marker(
                                state = rememberUpdatedMarkerState(stop.location.toGoogle()),
                                icon = planBadges.getValue(leg.mode),
                                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                                zIndex = 9f,
                                contentDescription = stop.name,
                                onClick = { onStopClick(stop); true },
                            )
                        }
                    }
                }
            } }
            Marker(
                state = rememberUpdatedMarkerState(journey.origin.toGoogle()),
                icon = originDot,
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                zIndex = 10f,
            )
            Marker(state = rememberUpdatedMarkerState(journey.destination.toGoogle()), zIndex = 10f)
        }

        pin?.let { Marker(state = rememberUpdatedMarkerState(it.toGoogle()), icon = BitmapDescriptorFactory.defaultMarker(), zIndex = 10f) }

        highlightedStop?.let { stop ->
            Marker(
                state = rememberUpdatedMarkerState(stop.location.toGoogle()),
                icon = highlightBadges.getValue(stop.mode),
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                zIndex = 11f,
                contentDescription = stop.name,
            )
        }
    }

    // --- Camera.
    LaunchedEffect(loaded, routes, fitKey) {
        if (!loaded || fitKey == null) return@LaunchedEffect
        val key = "routes:$fitKey"
        val all = routes.flatMap { it.shape }
        if (all.size < 2 || fitted == key) return@LaunchedEffect
        fitted = key
        camera.animate(CameraUpdateFactory.newLatLngBounds(bounds(all), 0), 800)
    }
    LaunchedEffect(loaded, plan, fitKey) {
        val journey = plan ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        val key = "plan:$fitKey"
        if (fitted == key) return@LaunchedEffect
        fitted = key
        val all = journey.legs.flatMap { it.points } + journey.origin + journey.destination
        camera.animate(CameraUpdateFactory.newLatLngBounds(bounds(all), with(density) { 24.dp.roundToPx() }), 800)
    }
    LaunchedEffect(loaded, overview.isNotEmpty() && routes.isEmpty() && plan == null) {
        // Only on a fresh map: a remembered camera is where the user left it.
        if (loaded && remembered == null && !pendingRequest && overview.isNotEmpty() && routes.isEmpty() && plan == null && camera.position.zoom < 11.5f) {
            camera.animate(CameraUpdateFactory.newLatLngZoom(IZMIR_CENTER, 10.6f), 600)
        }
    }
    LaunchedEffect(loaded, cameraRequest) {
        val request = cameraRequest ?: return@LaunchedEffect
        if (!loaded || request.id == handledRequest) return@LaunchedEffect
        handledRequest = request.id
        memoryKey?.let { CameraMemory.handledRequest[it] = request.id }
        camera.animate(CameraUpdateFactory.newLatLngZoom(request.target.toGoogle(), request.zoom), 800)
    }
    LaunchedEffect(loaded, camera.isMoving) {
        if (!loaded || camera.isMoving) return@LaunchedEffect
        memoryKey?.let { CameraMemory.positions[it] = camera.position }
        val projection = camera.projection ?: return@LaunchedEffect
        // The whole screen, not Google's "visible region": that excludes the content padding
        // (under the search bar and the cards), and stops there vanished. A margin loads the
        // stops just off screen too, so a small pan doesn't show empty streets first.
        val marginX = (mapSize.width * 0.15f).toInt()
        val marginY = (mapSize.height * 0.15f).toInt()
        val corners = listOf(
            Point(-marginX, -marginY),
            Point(mapSize.width + marginX, -marginY),
            Point(-marginX, mapSize.height + marginY),
            Point(mapSize.width + marginX, mapSize.height + marginY),
        ).map { projection.fromScreenLocation(it) }
        currentOnViewport(
            Viewport(
                minLatitude = corners.minOf { it.latitude },
                maxLatitude = corners.maxOf { it.latitude },
                minLongitude = corners.minOf { it.longitude },
                maxLongitude = corners.maxOf { it.longitude },
                zoom = camera.position.zoom,
            ),
        )
    }
}

/**
 * Moving vehicles, in their own composable: their positions change ~15 times a second, and
 * only these markers should recompose for it, not every stop on the map.
 */
@Composable
@GoogleMapComposable
private fun Vehicles(motion: BusMotion, outbound: BitmapDescriptor, inbound: BitmapDescriptor) {
    var vehicles by remember { mutableStateOf<Map<Int, Pair<LatLng, Direction>>>(emptyMap()) }
    LaunchedEffect(motion) {
        while (isActive) {
            vehicles = motion.positionsAt(SystemClock.elapsedRealtime())
            delay(VEHICLE_FRAME_MILLIS)
        }
    }
    for ((id, position) in vehicles) {
        key(id) {
            val state = remember { MarkerState(position.first.toGoogle()) }
            state.position = position.first.toGoogle()
            Marker(
                state = state,
                icon = if (position.second == Direction.OUTBOUND) outbound else inbound,
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                zIndex = 8f,
            )
        }
    }
}

/** Camera positions kept for the life of the app process, by [TransitMap]'s memoryKey. */
private object CameraMemory {
    val positions = HashMap<String, CameraPosition>()
    val fitted = HashMap<String, String?>()
    val handledRequest = HashMap<String, Int>()
}

private fun bounds(points: List<LatLng>): LatLngBounds =
    LatLngBounds.builder().apply { points.forEach { include(it.toGoogle()) } }.build()
