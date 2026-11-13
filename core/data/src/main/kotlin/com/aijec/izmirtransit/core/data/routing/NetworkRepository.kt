package com.aijec.izmirtransit.core.data.routing

import com.aijec.izmirtransit.core.data.repository.toModel
import com.aijec.izmirtransit.core.database.IzmirDatabase
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.ServiceDay
import com.aijec.izmirtransit.core.model.geo.PolylineCodec
import com.aijec.izmirtransit.core.model.ringStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Builds the trip planner's network for a service day from the offline database and keeps
 * the latest one: building reads every stop, route and departure (about a second).
 */
@Singleton
class NetworkRepository @Inject constructor(private val database: IzmirDatabase) {
    private val mutex = Mutex()
    private var cached: Pair<ServiceDay, TransitNetwork>? = null

    suspend fun network(day: ServiceDay): TransitNetwork = mutex.withLock {
        cached?.takeIf { it.first == day }?.second ?: build(day).also { cached = day to it }
    }

    /** Call after the static data is replaced. */
    suspend fun clear() = mutex.withLock { cached = null }

    private suspend fun build(day: ServiceDay): TransitNetwork = withContext(Dispatchers.Default) {
        // Everything here is CPU work on tens of thousands of rows; never on the main thread.
        val lineDao = database.lineDao()
        val stops = database.stopDao().allStops().map { it.toModel() }
        val lines = lineDao.allLines().associate { it.code to it.toModel() }
        val lineStops = lineDao.allLineStops().groupBy { it.lineCode to it.direction }
        val shapes = lineDao.allShapes().associateBy { it.lineCode to it.direction }
        val departures = database.timetableDao().departuresOn(day.ordinal).groupBy { it.lineCode to it.direction }
        val stopDepartures = database.stopDao().stopDeparturesOn(day.ordinal).groupBy { it.lineCode to it.direction }
        // Ring lines are timed from one end only; the way back is the same trips continuing,
        // reaching its first stop after the outward half's running time.
        val ringReturns = buildMap {
            for (line in lines.values) {
                val start = line.ringStart(Direction.entries.filter { departures[line.code to it.datasetCode].orEmpty().isNotEmpty() }) ?: continue
                val length = shapes[line.code to start.datasetCode]?.lengthMeters ?: continue
                val running = (length / TransitNetwork.BUS_METERS_PER_MINUTE).roundToInt()
                put(line.code to start.opposite.datasetCode, departures.getValue(line.code to start.datasetCode).map { it.minutesAfterMidnight + running })
            }
        }
        run {
            val inputs = lineStops.mapNotNull { (key, rows) ->
                val line = lines[key.first] ?: return@mapNotNull null
                val direction = Direction.fromDatasetCode(key.second) ?: return@mapNotNull null
                val scheduled = stopDepartures[key].orEmpty()
                PatternInput(
                    line = line,
                    direction = direction,
                    stopCodes = rows.sortedBy { it.sequence }.map { it.stopCode },
                    shape = shapes[key]?.let { PolylineCodec.decode(it.encodedPolyline) }.orEmpty(),
                    originDepartures = when {
                        scheduled.isNotEmpty() -> emptyList()
                        departures[key].isNullOrEmpty() -> ringReturns[key].orEmpty()
                        else -> departures.getValue(key).map { it.minutesAfterMidnight }
                    },
                    stopDepartures = scheduled.groupBy({ it.stopCode }, { it.minutesAfterMidnight to it.headsign }),
                )
            }
            TransitNetwork.build(stops, inputs)
        }
    }
}
