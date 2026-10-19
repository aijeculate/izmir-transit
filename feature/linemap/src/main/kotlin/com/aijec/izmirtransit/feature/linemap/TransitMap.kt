package com.aijec.izmirtransit.feature.linemap

import android.annotation.SuppressLint
import android.graphics.RectF
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.LineRoute
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.ui.TransitColors
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.geometry.LatLng as MapLatLng

// OpenFreeMap: free OpenStreetMap vector tiles, no API key. Attribution comes from the style.
private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"

private val IZMIR_CENTER = MapLatLng(38.4237, 27.1428)
private const val BUS_ANIMATION_MILLIS = 1_200

/**
 * MapLibre map showing one line: both directions' routes, their stops, and live buses that
 * glide to each new position instead of jumping. [contentPadding] keeps the route clear of
 * the cards drawn over the map.
 */
@Composable
fun TransitMap(
    routes: List<LineRoute>,
    buses: List<LiveBus>,
    contentPadding: MapPadding,
    onStopClick: (stopCode: String) -> Unit,
    modifier: Modifier = Modifier,
    userLocation: LatLng? = null,
    nearbyStops: List<Stop> = emptyList(),
    cameraRequest: Int = 0,
) {
    val currentOnStopClick by rememberUpdatedState(onStopClick)
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val mapView = remember { MapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // Where each bus marker is drawn right now; animations start from here.
    val displayed = remember { HashMap<Int, Pair<LatLng, Direction>>() }

    MapViewLifecycle(mapView)

    AndroidView(
        factory = {
            mapView.apply {
                getMapAsync { m ->
                    m.cameraPosition = CameraPosition.Builder().target(IZMIR_CENTER).zoom(11.0).build()
                    m.uiSettings.isRotateGesturesEnabled = false
                    m.uiSettings.isTiltGesturesEnabled = false
                    // OSM attribution must stay visible: lift logo and "i" above the bottom card.
                    with(density) {
                        val bottom = contentPadding.bottom.roundToPx()
                        val side = 16.dp.roundToPx()
                        m.uiSettings.setLogoMargins(side, 0, 0, bottom)
                        m.uiSettings.setAttributionMargins(side + 100.dp.roundToPx(), 0, 0, bottom)
                    }
                    // Tap a stop to open it. The hit box is 48 dp, wider than the drawn circle.
                    val half = with(density) { 24.dp.toPx() }
                    m.addOnMapClickListener { point ->
                        val screen = m.projection.toScreenLocation(point)
                        val box = RectF(screen.x - half, screen.y - half, screen.x + half, screen.y + half)
                        val layers = Direction.entries.map { "${stopSource(it)}-layer" } + "$NEARBY_SOURCE-layer"
                        val code = m.queryRenderedFeatures(box, *layers.toTypedArray())
                            .firstNotNullOfOrNull { it.getStringProperty("code") }
                        code?.let(currentOnStopClick)
                        code != null
                    }
                    map = m
                }
            }
        },
        modifier = modifier,
    )

    val styleUrl = if (dark) DARK_STYLE else LIGHT_STYLE
    LaunchedEffect(map, styleUrl) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromUri(styleUrl)) { loaded ->
            loaded.addTransitLayers()
            style = loaded
        }
    }

    LaunchedEffect(style, routes) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        for (direction in Direction.entries) {
            val route = routes.firstOrNull { it.direction == direction }
            s.source(routeSource(direction))?.setGeoJson(lineString(route?.shape.orEmpty()))
            s.source(stopSource(direction))?.setGeoJson(stopPoints(route?.stops.orEmpty()))
        }
        val all = routes.flatMap { it.shape }
        if (all.size >= 2) {
            val bounds = LatLngBounds.Builder().apply { all.forEach { include(MapLatLng(it.latitude, it.longitude)) } }.build()
            with(density) {
                m.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        bounds,
                        contentPadding.start.roundToPx(), contentPadding.top.roundToPx(),
                        contentPadding.end.roundToPx(), contentPadding.bottom.roundToPx(),
                    ),
                    800,
                )
            }
        }
    }

    LaunchedEffect(style, userLocation, nearbyStops) {
        val s = style ?: return@LaunchedEffect
        s.source(USER_SOURCE)?.setGeoJson(points(listOfNotNull(userLocation)))
        s.source(NEARBY_SOURCE)?.setGeoJson(stopPoints(nearbyStops))
    }

    LaunchedEffect(map, cameraRequest) {
        val m = map ?: return@LaunchedEffect
        val target = userLocation ?: return@LaunchedEffect
        if (cameraRequest > 0) {
            // Zoom 15.5 shows streets and every stop dot around the user.
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(MapLatLng(target.latitude, target.longitude), 15.5), 800)
        }
    }

    LaunchedEffect(style, buses) {
        val s = style ?: return@LaunchedEffect
        val start = HashMap(displayed)
        val targets = buses.associate { it.busId to (it.position to it.direction) }
        fun draw(fraction: Float) {
            displayed.clear()
            targets.forEach { (id, target) ->
                val from = start[id]?.first ?: target.first
                displayed[id] = LatLng(
                    from.latitude + (target.first.latitude - from.latitude) * fraction,
                    from.longitude + (target.first.longitude - from.longitude) * fraction,
                ) to target.second
            }
            for (direction in Direction.entries) {
                s.source(busSource(direction))?.setGeoJson(points(displayed.values.filter { it.second == direction }.map { it.first }))
            }
        }
        val moving = targets.any { (id, target) -> start[id]?.first != null && start[id]?.first != target.first }
        if (moving) {
            animate(0f, 1f, animationSpec = tween(BUS_ANIMATION_MILLIS, easing = FastOutSlowInEasing)) { value, _ -> draw(value) }
        }
        draw(1f)
    }
}

data class MapPadding(val start: Dp = 0.dp, val top: Dp = 0.dp, val end: Dp = 0.dp, val bottom: Dp = 0.dp)

@Composable
private fun MapViewLifecycle(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var created = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> { mapView.onCreate(null); created = true }
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> Unit // handled in onDispose
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (created) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
                mapView.onDestroy()
            }
        }
    }
}

private const val USER_SOURCE = "user-location"
private const val NEARBY_SOURCE = "nearby-stops"

private fun routeSource(direction: Direction) = "route-${direction.name.lowercase()}"
private fun stopSource(direction: Direction) = "stops-${direction.name.lowercase()}"
private fun busSource(direction: Direction) = "buses-${direction.name.lowercase()}"

private fun Style.source(id: String): GeoJsonSource? = getSourceAs(id)

@SuppressLint("ResourceType")
private fun Style.addTransitLayers() {
    // Nearby stops (around the user, any line) sit under the selected line's layers.
    addSource(GeoJsonSource(NEARBY_SOURCE, points(emptyList())))
    addLayer(
        CircleLayer("$NEARBY_SOURCE-layer", NEARBY_SOURCE).withProperties(
            PropertyFactory.circleRadius(5f),
            PropertyFactory.circleColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeColor(android.graphics.Color.DKGRAY),
            PropertyFactory.circleStrokeWidth(2f),
        ).apply { minZoom = 13f },
    )
    // Return first so outbound draws on top where the two routes share a street.
    val order = listOf(Direction.RETURN, Direction.OUTBOUND)
    for (direction in order) {
        val color = TransitColors.forDirection(direction).toArgb()
        addSource(GeoJsonSource(routeSource(direction), lineString(emptyList())))
        addLayer(
            LineLayer("${routeSource(direction)}-layer", routeSource(direction)).withProperties(
                PropertyFactory.lineColor(color),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            ),
        )
    }
    for (direction in order) {
        val color = TransitColors.forDirection(direction).toArgb()
        addSource(GeoJsonSource(stopSource(direction), points(emptyList())))
        addLayer(
            CircleLayer("${stopSource(direction)}-layer", stopSource(direction)).withProperties(
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleColor(android.graphics.Color.WHITE),
                PropertyFactory.circleStrokeColor(color),
                PropertyFactory.circleStrokeWidth(2f),
            ).apply { minZoom = 12.5f },
        )
    }
    for (direction in order) {
        val color = TransitColors.forDirection(direction).toArgb()
        addSource(GeoJsonSource(busSource(direction), points(emptyList())))
        addLayer(
            CircleLayer("${busSource(direction)}-layer", busSource(direction)).withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor(color),
                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }
    addSource(GeoJsonSource(USER_SOURCE, points(emptyList())))
    addLayer(
        CircleLayer("$USER_SOURCE-halo", USER_SOURCE).withProperties(
            PropertyFactory.circleRadius(16f),
            PropertyFactory.circleColor(android.graphics.Color.rgb(26, 115, 232)),
            PropertyFactory.circleOpacity(0.2f),
        ),
    )
    addLayer(
        CircleLayer("$USER_SOURCE-layer", USER_SOURCE).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(android.graphics.Color.rgb(26, 115, 232)),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}

// GeoJSON is written by hand: tiny, and independent of MapLibre's GeoJSON classes.
// Kotlin's Double.toString is locale-independent, so Turkish locale can't inject commas.

private fun coordinate(point: LatLng) = "[${point.longitude},${point.latitude}]"

private fun lineString(points: List<LatLng>): String =
    if (points.size < 2) """{"type":"FeatureCollection","features":[]}"""
    else """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${points.joinToString(",", transform = ::coordinate)}]}}"""

private fun stopPoints(stops: List<Stop>): String =
    """{"type":"FeatureCollection","features":[${
        stops.joinToString(",") {
            """{"type":"Feature","properties":{"code":"${it.code.filter(Char::isLetterOrDigit)}"},"geometry":{"type":"Point","coordinates":${coordinate(it.location)}}}"""
        }
    }]}"""

private fun points(points: List<LatLng>): String =
    """{"type":"FeatureCollection","features":[${
        points.joinToString(",") { """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":${coordinate(it)}}}""" }
    }]}"""
