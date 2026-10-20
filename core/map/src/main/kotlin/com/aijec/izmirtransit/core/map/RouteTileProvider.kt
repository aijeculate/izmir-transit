package com.aijec.izmirtransit.core.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/**
 * Draws many routes (the whole bus network) into map tiles on the map's background
 * threads. Hundreds of separate polylines made every pan stutter; tiles are drawn once per
 * area and zoom, cached by the map, and panning only moves pictures.
 */
internal class RouteTileProvider(
    routes: List<List<LatLng>>,
    private val color: Int,
    /** Line width in tile pixels at a 256-pixel tile. */
    private val widthPx: Float,
) : TileProvider {
    /** Each route in Web Mercator world units (0..1), with its bounding box. */
    private class WorldRoute(val xs: DoubleArray, val ys: DoubleArray, val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

    private val world: List<WorldRoute> = routes.filter { it.size >= 2 }.map { points ->
        val xs = DoubleArray(points.size) { (points[it].longitude + 180.0) / 360.0 }
        val ys = DoubleArray(points.size) { mercatorY(points[it].latitude) }
        WorldRoute(xs, ys, xs.min(), ys.min(), xs.max(), ys.max())
    }

    override fun getTile(x: Int, y: Int, zoom: Int): Tile {
        val scale = (1 shl zoom).toDouble()
        val left = x / scale
        val top = y / scale
        val span = 1.0 / scale
        val margin = span * 0.02
        val visible = world.filter { r ->
            r.maxX >= left - margin && r.minX <= left + span + margin && r.maxY >= top - margin && r.minY <= top + span + margin
        }
        if (visible.isEmpty()) return TileProvider.NO_TILE

        val bitmap = Bitmap.createBitmap(TILE_PIXELS, TILE_PIXELS, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            this.color = this@RouteTileProvider.color
            strokeWidth = widthPx * TILE_PIXELS / 256f
        }
        val pixelsPerWorld = TILE_PIXELS * scale
        val path = Path()
        for (route in visible) {
            path.rewind()
            for (i in route.xs.indices) {
                val px = ((route.xs[i] - left) * pixelsPerWorld).toFloat()
                val py = ((route.ys[i] - top) * pixelsPerWorld).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, paint)
        }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        return Tile(TILE_PIXELS, TILE_PIXELS, bytes)
    }

    private companion object {
        /** 512-pixel tiles stay sharp on high-density screens. */
        const val TILE_PIXELS = 512

        fun mercatorY(latitude: Double): Double {
            val radians = latitude * PI / 180.0
            return (1.0 - ln(tan(radians) + 1.0 / kotlin.math.cos(radians)) / PI) / 2.0
        }
    }
}
