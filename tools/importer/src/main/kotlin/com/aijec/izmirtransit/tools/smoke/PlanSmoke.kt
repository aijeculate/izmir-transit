package com.aijec.izmirtransit.tools.smoke

import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.data.routing.JourneyPlanner
import com.aijec.izmirtransit.core.data.routing.NetworkRepository
import com.aijec.izmirtransit.core.database.IzmirDatabase
import com.aijec.izmirtransit.core.database.JvmDatabases
import com.aijec.izmirtransit.core.model.JourneyLeg
import com.aijec.izmirtransit.core.model.ServiceDay
import com.aijec.izmirtransit.core.model.geo.LatLng
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/**
 * Plans a few real trips on build/izmir-transit.db and prints them.
 *
 *   ./gradlew :tools:importer:planSmoke
 */
fun main(): Unit = runBlocking {
    val root = File(System.getProperty("izmir.root") ?: ".").absoluteFile
    val database = JvmDatabases.file(File(root, "build/${IzmirDatabase.FILE_NAME}"))
    try {
        val (network, buildTime) = measureTimedValue { NetworkRepository(database).network(ServiceDay.WEEKDAY) }
        println("Network: ${network.stops.size} stops, built in $buildTime")
        val planner = JourneyPlanner(network)
        val trips = listOf(
            "Bornova Metro → Karşıyaka İskele" to (LatLng(38.4578, 27.2128) to LatLng(38.4553, 27.1199)),
            "Konak → Çiğli (Ata Sanayi)" to (LatLng(38.4189, 27.1287) to LatLng(38.4922, 27.0687)),
            "Buca Koop → Alsancak" to (LatLng(38.3873, 27.1745) to LatLng(38.4385, 27.1430)),
            "Narlıdere → Bayraklı" to (LatLng(38.3940, 27.0047) to LatLng(38.4622, 27.1650)),
            "Gaziemir → Menemen" to (LatLng(38.3214, 27.1300) to LatLng(38.6050, 27.0690)),
        )
        for ((name, ends) in trips) {
            val (journeys, time) = measureTimedValue { planner.plan(ends.first, ends.second, startMinute = 8 * 60) }
            println("\n== $name ($time)")
            journeys.forEach { j ->
                println("  ${clock(j.departureMinute)} → ${clock(j.arrivalMinute)} (${j.durationMinutes} min, ${j.rides.size} rides, walk ${j.walkingMeters} m)")
                j.legs.forEach { leg ->
                    when (leg) {
                        is JourneyLeg.Walk -> println("     walk ${leg.meters} m ${leg.fromStop?.name ?: "start"} → ${leg.toStop?.name ?: "end"}")
                        is JourneyLeg.Ride -> println("     ${clock(leg.startMinute)} ${leg.line.displayCode} (${leg.headsign}) ${leg.board.name} → ${leg.alight.name} ${clock(leg.endMinute)}, ${leg.stops.size - 1} stops")
                    }
                }
            }
        }
        val many = measureTime { repeat(20) { planner.plan(trips[1].second.first, trips[1].second.second, 8 * 60 + it * 7) } }
        println("\n20 plans: $many")
        val (byNine, arriveTime) = measureTimedValue { planner.planArriveBy(trips[0].second.first, trips[0].second.second, arriveBy = 9 * 60) }
        println("\n== ${trips[0].first}, arrive by 09:00 ($arriveTime)")
        for (j in byNine) {
            println("  ${clock(j.departureMinute)} → ${clock(j.arrivalMinute)} (${j.rides.size} rides: ${j.rides.joinToString { it.line.displayCode }}, walk ${j.walkingMeters} m)")
        }
    } finally {
        database.close()
    }
}

private fun clock(minute: Int) = TimetableRepository.formatClock(minute)
