package com.aijec.izmirtransit.tools.smoke

import com.aijec.izmirtransit.core.data.live.EtaEstimator
import com.aijec.izmirtransit.core.data.live.GhostFilter
import com.aijec.izmirtransit.core.data.live.RequestPacer
import com.aijec.izmirtransit.core.data.repository.LiveBusRepository
import com.aijec.izmirtransit.core.data.repository.LineRepository
import com.aijec.izmirtransit.core.data.repository.StopRepository
import com.aijec.izmirtransit.core.data.source.eshot.live.IztekApi
import com.aijec.izmirtransit.core.data.source.eshot.live.IztekLiveDataSource
import com.aijec.izmirtransit.core.database.IzmirDatabase
import com.aijec.izmirtransit.core.database.JvmDatabases
import com.aijec.izmirtransit.core.model.LiveStatus
import com.aijec.izmirtransit.core.model.geo.distanceMeters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Clock

/**
 * One real poll of a line and a stop through the full repository stack, against the
 * database built by the importer. Costs two API requests.
 *
 *   ./gradlew :tools:importer:liveSmoke --args="--line 446 --stop 10007"
 */
fun main(args: Array<String>): Unit = runBlocking<Unit> {
    val options = args.toList().chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val root = File(System.getProperty("izmir.root") ?: ".").absoluteFile
    val database = JvmDatabases.file(File(root, options["db"] ?: "build/${IzmirDatabase.FILE_NAME}"))
    try {
        val lines = LineRepository(database)
        val stops = StopRepository(database)
        val api = IztekApi.create(IztekApi.httpClient("IzmirTransit/0.1 (data-layer smoke test)"))
        val live = LiveBusRepository(
            IztekLiveDataSource(api, RequestPacer()), lines, stops, GhostFilter(), EtaEstimator(), Clock.systemUTC(),
        )

        val lineCode = options["line"] ?: "446"
        val line = lines.line(lineCode)
        println("Line $lineCode: ${line?.name}")
        lines.routes(lineCode).forEach { println("  ${it.direction}: ${it.shape.size} shape points, ${it.stops.size} stops, first ${it.stops.firstOrNull()?.name}") }
        val lineSnapshot = live.observeLine(lineCode).first { it.status != LiveStatus.LOADING }
        println("  live: ${lineSnapshot.status} ${lineSnapshot.lastError ?: ""} ${lineSnapshot.data?.size ?: 0} buses")
        lineSnapshot.data?.forEach { println("    bus ${it.busId} ${it.direction} at ${it.position}") }

        val stopCode = options["stop"] ?: "10007"
        println("Stop $stopCode: ${stops.stop(stopCode)?.name}, lines ${stops.linesAtStop(stopCode).joinToString { it.code }}")
        val stopSnapshot = live.observeStop(stopCode).first { it.status != LiveStatus.LOADING }
        println("  live: ${stopSnapshot.status} ${stopSnapshot.lastError ?: ""} ${stopSnapshot.data?.size ?: 0} buses")
        val stopLocation = stops.stop(stopCode)?.location
        stopSnapshot.data?.forEach { bus ->
            println("    line ${bus.lineCode} bus ${bus.busId} ${bus.direction}: ${bus.stopsRemaining} stops, ~${bus.eta.minutes} min (${bus.eta.basis})")
            // Diagnostics: metres still to travel to the stop along each direction's shape.
            if (stopLocation != null) {
                val straight = distanceMeters(bus.position, stopLocation).toInt()
                val along = lines.measuredRoutes(bus.lineCode).map { (direction, route) ->
                    val b = route.project(bus.position)
                    val s = route.project(stopLocation)
                    "$direction=${if (b == null || s == null) "?" else "${(s.distanceAlongMeters - b.distanceAlongMeters).toInt()}m (off ${b.distanceFromLineMeters.toInt()}/${s.distanceFromLineMeters.toInt()}m)"}"
                }
                println("      straight ${straight}m; ahead along shape: ${along.joinToString()}")
            }
        }
    } finally {
        database.close()
    }
}
