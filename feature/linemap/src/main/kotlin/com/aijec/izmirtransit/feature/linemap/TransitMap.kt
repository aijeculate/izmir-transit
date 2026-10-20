package com.aijec.izmirtransit.feature.linemap

import android.annotation.SuppressLint
import android.graphics.RectF
import android.os.SystemClock
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aijec.izmirtransit.core.data.live.BusMotion
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import com.aijec.izmirtransit.core.model.LineRoute
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.OverviewRoute
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.ui.Calm
import com.aijec.izmirtransit.core.ui.TransitColors
import java.util.Locale
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.geometry.LatLng as MapLatLng

// OpenFreeMap: free OpenStreetMap vector tiles, no API key. Attribution comes from the style.
private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/positron"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"

private val IZMIR_CENTER = MapLatLng(38.4237, 27.1428)
/** About 15 fps: smooth for slow-moving markers, cheap on battery. */
private const val BUS_FRAME_MILLIS = 66L

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
    /**
     * All routes when no line is selected: buses faint in the accent colour, rail lines bold
     * in their own colours, ferries dashed. Empty hides the overlay.
     */
    overview: List<OverviewRoute> = emptyList(),
) {
    // A MapView must not outlive the lifecycle it is bound to. Navigation can restore this
    // screen under a new lifecycle owner while keeping the composition; reusing the old,
    // already destroyed MapView then crashed natively. Keying on the owner rebuilds the
    // view, its map and every effect together.
    key(LocalLifecycleOwner.current) {
        val currentOnStopClick by rememberUpdatedState(onStopClick)
        val context = LocalContext.current
        val dark = Calm.colors.isDark
    val accentArgb = Calm.colors.accent.toArgb()
        val density = LocalDensity.current
        val mapView = remember { MapView(context) }
        var map by remember { mutableStateOf<MapLibreMap?>(null) }
        var style by remember { mutableStateOf<Style?>(null) }
        val motion = remember { BusMotion() }
        val measuredRoutes = remember(routes) {
            routes.filter { it.shape.size >= 2 }.associate { it.direction to MeasuredPolyline(it.shape) }
        }

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
        LaunchedEffect(map, styleUrl, accentArgb) {
            val m = map ?: return@LaunchedEffect
            style = null
            m.setStyle(Style.Builder().fromUri(styleUrl)) { loaded ->
                loaded.addTransitLayers(accentArgb)
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

        LaunchedEffect(style, overview, routes.isEmpty()) {
            val s = style ?: return@LaunchedEffect
            val m = map ?: return@LaunchedEffect
            // Built off the main thread: ~900 bus shapes become one MultiLineString.
            val (busJson, railJson) = withContext(Dispatchers.Default) {
                val (bus, other) = overview.partition { it.mode == TransitMode.BUS }
                multiLineString(bus.map { it.points }) to coloredLines(other)
            }
            s.source(OVERVIEW_SOURCE)?.setGeoJson(busJson)
            s.source(OVERVIEW_RAIL_SOURCE)?.setGeoJson(railJson)
            if (overview.isNotEmpty() && routes.isEmpty()) {
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(IZMIR_CENTER, 10.6), 600)
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

        // Each poll feeds the motion model; a render loop then moves buses along their routes
        // between polls, so they glide rather than jump (see BusMotion).
        LaunchedEffect(buses, measuredRoutes) {
            motion.update(buses, measuredRoutes, SystemClock.elapsedRealtime())
        }
        LaunchedEffect(style) {
            val s = style ?: return@LaunchedEffect
            while (isActive) {
                val drawn = motion.positionsAt(SystemClock.elapsedRealtime()).values
                for (direction in Direction.entries) {
                    s.source(busSource(direction))?.setGeoJson(points(drawn.filter { it.second == direction }.map { it.first }))
                }
                delay(BUS_FRAME_MILLIS)
            }
        }
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
                // Pause and stop before destroying whatever state was reached: onDestroy frees
                // the native map, and only onStop halts the render thread. Both are safe on a
                // map that never started. TransitMap also keys the MapView on its lifecycle
                // owner, so a destroyed MapView is never started again (that crashed natively).
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }
}

private const val USER_SOURCE = "user-location"
private const val OVERVIEW_SOURCE = "overview-routes"
private const val OVERVIEW_RAIL_SOURCE = "overview-rail"
private const val NEARBY_SOURCE = "nearby-stops"

private fun routeSource(direction: Direction) = "route-${direction.name.lowercase()}"
private fun stopSource(direction: Direction) = "stops-${direction.name.lowercase()}"
private fun busSource(direction: Direction) = "buses-${direction.name.lowercase()}"

private fun Style.source(id: String): GeoJsonSource? = getSourceAs(id)

@SuppressLint("ResourceType")
private fun Style.addTransitLayers(overviewColor: Int) {
    // All lines, faint, under everything else: the Map tab's starting view.
    addSource(GeoJsonSource(OVERVIEW_SOURCE, multiLineString(emptyList())))
    addLayer(
        LineLayer("$OVERVIEW_SOURCE-layer", OVERVIEW_SOURCE).withProperties(
            PropertyFactory.lineColor(overviewColor),
            PropertyFactory.lineWidth(1.4f),
            PropertyFactory.lineOpacity(0.45f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ),
    )
    // Metro, tram and İZBAN over the buses in their official colours; ferries dashed.
    addSource(GeoJsonSource(OVERVIEW_RAIL_SOURCE, coloredLines(emptyList())))
    addLayer(
        LineLayer("$OVERVIEW_RAIL_SOURCE-ferry", OVERVIEW_RAIL_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.lineWidth(2.2f),
            PropertyFactory.lineOpacity(0.9f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        ).withFilter(Expression.eq(Expression.get("mode"), Expression.literal(TransitMode.FERRY.name))),
    )
    addLayer(
        LineLayer("$OVERVIEW_RAIL_SOURCE-rail", OVERVIEW_RAIL_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.lineWidth(3.5f),
            PropertyFactory.lineOpacity(0.95f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ).withFilter(Expression.neq(Expression.get("mode"), Expression.literal(TransitMode.FERRY.name))),
    )
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

private fun multiLineString(lines: List<List<LatLng>>): String {
    val drawable = lines.filter { it.size >= 2 }
    if (drawable.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    return buildString(drawable.sumOf { it.size } * 22) {
        append("""{"type":"Feature","properties":{},"geometry":{"type":"MultiLineString","coordinates":[""")
        drawable.forEachIndexed { i, line ->
            if (i > 0) append(',')
            append('[')
            line.forEachIndexed { j, point ->
                if (j > 0) append(',')
                append(coordinate(point))
            }
            append(']')
        }
        append("]}}")
    }
}

/** One LineString per route, carrying its mode and colour ("#RRGGBB") for the overview layers. */
private fun coloredLines(routes: List<OverviewRoute>): String =
    """{"type":"FeatureCollection","features":[${
        routes.filter { it.points.size >= 2 }.joinToString(",") { route ->
            val color = "#%06X".format(Locale.ROOT, (route.color ?: DEFAULT_ROUTE_COLOR) and 0xFFFFFF)
            """{"type":"Feature","properties":{"mode":"${route.mode.name}","color":"$color"},""" +
                """"geometry":{"type":"LineString","coordinates":[${route.points.joinToString(",", transform = ::coordinate)}]}}"""
        }
    }]}"""

private const val DEFAULT_ROUTE_COLOR = 0x5E6B7D

private fun points(points: List<LatLng>): String =
    """{"type":"FeatureCollection","features":[${
        points.joinToString(",") { """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":${coordinate(it)}}}""" }
    }]}"""
