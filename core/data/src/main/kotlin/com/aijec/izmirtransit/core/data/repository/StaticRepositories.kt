package com.aijec.izmirtransit.core.data.repository

import com.aijec.izmirtransit.core.data.text.TurkishText
import com.aijec.izmirtransit.core.database.IzmirDatabase
import com.aijec.izmirtransit.core.database.StopDepartureEntity
import com.aijec.izmirtransit.core.model.Announcement
import com.aijec.izmirtransit.core.model.ConnectionType
import com.aijec.izmirtransit.core.model.Departure
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.LineRoute
import com.aijec.izmirtransit.core.model.OverviewRoute
import com.aijec.izmirtransit.core.model.ScheduledDeparture
import com.aijec.izmirtransit.core.model.ServiceDay
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.model.geo.BoundingBox
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import com.aijec.izmirtransit.core.model.geo.PolylineCodec
import com.aijec.izmirtransit.core.model.geo.distanceMeters
import com.aijec.izmirtransit.core.model.geo.simplifyPolyline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Singleton
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

// Read side of the offline data. Codes are unique across modes, so lookups need no mode.

class LineRepository @Inject constructor(private val database: IzmirDatabase) {
    private val dao get() = database.lineDao()
    private val routeCache = object : LinkedHashMap<String, Map<Direction, MeasuredPolyline>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<Direction, MeasuredPolyline>>) = size > 32
    }

    suspend fun line(code: String): Line? = dao.line(code)?.toModel()

    /** Rail, sea and air connections this line serves, in a stable order. */
    suspend fun connections(code: String): List<ConnectionType> =
        dao.connections(code).mapNotNull { ConnectionType.fromCode(it.connectionType) }.distinct().sorted()

    @Volatile private var overviewCache: List<OverviewRoute>? = null

    /**
     * Every line's route, simplified hard (25 m) for the all-lines overlay on the map:
     * a few tens of thousands of points instead of 234k, decoded once and cached.
     * Rail and ferry come last so they draw on top of the buses.
     */
    suspend fun overviewShapes(): List<OverviewRoute> = withContext(Dispatchers.Default) { overviewShapesNow() }

    private suspend fun overviewShapesNow(): List<OverviewRoute> {
        overviewCache?.let { return it }
        val lines = dao.allLines().associateBy { it.code }
        val shapes = dao.allShapes().mapNotNull { shape ->
            val line = lines[shape.lineCode] ?: return@mapNotNull null
            OverviewRoute(
                mode = TransitMode.valueOf(line.mode),
                color = line.color,
                points = simplifyPolyline(PolylineCodec.decode(shape.encodedPolyline), OVERVIEW_TOLERANCE_METERS),
            )
        }.sortedBy { it.mode != TransitMode.BUS }
        overviewCache = shapes
        return shapes
    }

    /** Call after the static data is replaced, so cached shapes are re-read. */
    fun clearCaches() {
        synchronized(routeCache) { routeCache.clear() }
        overviewCache = null
    }

    private companion object {
        const val OVERVIEW_TOLERANCE_METERS = 25.0
    }

    /** Turkish-aware: "izmir" finds "İZMİR". Line-number matches rank first. */
    suspend fun search(query: String, limit: Int = 30): List<Line> {
        val folded = TurkishText.fold(query)
        if (folded.isEmpty()) return emptyList()
        return dao.search(folded, limit).map { it.toModel() }
    }

    /** Both directions with decoded shape and ordered stops, for the line map. */
    suspend fun routes(code: String): List<LineRoute> = withContext(Dispatchers.Default) { routesNow(code) }

    private suspend fun routesNow(code: String): List<LineRoute> {
        val shapes = dao.shapes(code).associateBy { it.direction }
        return Direction.entries.mapNotNull { direction ->
            val shape = shapes[direction.datasetCode]?.let { PolylineCodec.decode(it.encodedPolyline) }.orEmpty()
            val stops = dao.stopsInOrder(code, direction.datasetCode).map { it.toModel() }
            if (shape.isEmpty() && stops.isEmpty()) null else LineRoute(code, direction, shape, stops)
        }
    }

    /** Measured shapes for snapping buses and estimating arrival; cached because polls repeat. */
    suspend fun measuredRoutes(code: String): Map<Direction, MeasuredPolyline> = withContext(Dispatchers.Default) { measuredRoutesNow(code) }

    private suspend fun measuredRoutesNow(code: String): Map<Direction, MeasuredPolyline> {
        synchronized(routeCache) { routeCache[code] }?.let { return it }
        val measured = dao.shapes(code).mapNotNull { shape ->
            Direction.fromDatasetCode(shape.direction)?.let { it to MeasuredPolyline(PolylineCodec.decode(shape.encodedPolyline)) }
        }.toMap()
        synchronized(routeCache) { routeCache[code] = measured }
        return measured
    }
}

data class NearbyStop(val stop: Stop, val distanceMeters: Double)

class StopRepository @Inject constructor(private val database: IzmirDatabase) {
    companion object {
        /** Hilal's Metro and İZBAN stations are 285 m apart; 350 m catches every real interchange. */
        const val TRANSFER_RADIUS_METERS = 350.0
    }

    private val dao get() = database.stopDao()

    suspend fun stop(code: String): Stop? = dao.stop(code)?.toModel()

    suspend fun search(query: String, limit: Int = 30): List<Stop> {
        val folded = TurkishText.fold(query)
        if (folded.isEmpty()) return emptyList()
        return dao.search(folded, limit).map { it.toModel() }
    }

    /**
     * Rail and ferry stations a short walk from [stop] on another mode: the interchanges,
     * such as Hilal and Halkapınar (Metro and İZBAN under one name) or a pier next to a tram
     * stop. Bus stops list the stations next to them too.
     */
    suspend fun transfers(stop: Stop, radiusMeters: Double = TRANSFER_RADIUS_METERS): List<NearbyStop> {
        val box = BoundingBox.around(stop.location, radiusMeters)
        val rows = dao.inBox(box.minLatitude, box.maxLatitude, box.minLongitude, box.maxLongitude)
        return withContext(Dispatchers.Default) {
            rows.map { it.toModel() }
                .filter { it.mode != TransitMode.BUS && it.mode != stop.mode }
                .map { NearbyStop(it, distanceMeters(stop.location, it.location)) }
                .filter { it.distanceMeters <= radiusMeters }
                .sortedBy { it.distanceMeters }
                // Tram platforms come in pairs ("(Deniz)", "(Kara)"): one per mode and name is enough.
                .distinctBy { it.stop.mode to it.stop.name.substringBefore(" (") }
        }
    }

    /** Stops inside a map viewport, nearest the middle first, at most [limit]. */
    suspend fun inArea(minLatitude: Double, maxLatitude: Double, minLongitude: Double, maxLongitude: Double, limit: Int): List<Stop> {
        val center = LatLng((minLatitude + maxLatitude) / 2, (minLongitude + maxLongitude) / 2)
        val rows = dao.inBox(minLatitude, maxLatitude, minLongitude, maxLongitude)
        return withContext(Dispatchers.Default) {
            rows.map { it.toModel() }.sortedBy { distanceMeters(center, it.location) }.take(limit)
        }
    }

    /** Computed offline from the stops table; no network needed. */
    suspend fun nearby(center: LatLng, radiusMeters: Double = 600.0, limit: Int = 30): List<NearbyStop> {
        val box = BoundingBox.around(center, radiusMeters)
        return dao.inBox(box.minLatitude, box.maxLatitude, box.minLongitude, box.maxLongitude)
            .map { it.toModel() }
            .map { NearbyStop(it, distanceMeters(center, it.location)) }
            .filter { it.distanceMeters <= radiusMeters }
            .sortedBy { it.distanceMeters }
            .take(limit)
    }

    suspend fun linesAtStop(code: String): List<Line> = dao.linesAtStop(code).map { it.toModel() }

    /**
     * The next timetabled departures (rail and ferry) from a station or pier, from
     * [minute] on [serviceDay]. Empty for bus stops, which use live arrivals.
     */
    suspend fun scheduledDepartures(code: String, serviceDay: ServiceDay, minute: Int, limit: Int = 40): List<ScheduledDeparture> =
        toScheduled(code, dao.scheduledDepartures(code, serviceDay.ordinal, minute, limit))

    /**
     * The last timetabled departure before [minute] for each line and direction, within
     * [windowMinutes]: "the train that should have just left", newest first. Trains often run
     * late, so the board shows these as estimates.
     */
    suspend fun recentDepartures(code: String, serviceDay: ServiceDay, minute: Int, windowMinutes: Int = 20): List<ScheduledDeparture> {
        val rows = dao.departuresBetween(code, serviceDay.ordinal, minute - windowMinutes, minute)
            .distinctBy { it.lineCode to it.direction }
        return toScheduled(code, rows)
    }

    private suspend fun toScheduled(code: String, rows: List<StopDepartureEntity>): List<ScheduledDeparture> {
        if (rows.isEmpty()) return emptyList()
        val lines = dao.linesAtStop(code).associate { it.code to it.toModel() }
        return rows.mapNotNull { row ->
            val line = lines[row.lineCode] ?: database.lineDao().line(row.lineCode)?.toModel() ?: return@mapNotNull null
            ScheduledDeparture(line, Direction.fromDatasetCode(row.direction) ?: Direction.OUTBOUND, row.minutesAfterMidnight, row.headsign)
        }
    }
}

class TimetableRepository @Inject constructor(private val database: IzmirDatabase) {
    private val dao get() = database.timetableDao()

    suspend fun departures(lineCode: String, serviceDay: ServiceDay, direction: Direction): List<Departure> =
        dao.departures(lineCode, serviceDay.ordinal, direction.datasetCode).map { it.toModel() }

    /**
     * A rail or ferry line's timetable at one station: what the station board shows, for the
     * whole day. Rail trips start at different stations, so "from the first stop" alone
     * would miss trains (an İZBAN train can start at Menemen).
     */
    suspend fun departuresAt(stopCode: String, lineCode: String, serviceDay: ServiceDay, direction: Direction): List<Departure> =
        database.stopDao().lineDeparturesAt(stopCode, lineCode, direction.datasetCode, serviceDay.ordinal)
            .map { it.minutesAfterMidnight }
            .distinct()
            .map { Departure(lineCode, direction, serviceDay, it, wheelchairAccessible = false, bikeRack = false, electric = false) }

    suspend fun activeAnnouncements(lineCode: String, now: LocalDateTime): List<Announcement> =
        dao.announcements(lineCode).map { it.toModel() }.filter { it.isActiveAt(now) }

    /** Active announcements for any of [lineCodes], e.g. every line serving a stop. */
    suspend fun activeAnnouncementsForLines(lineCodes: List<String>, now: LocalDateTime): List<Announcement> =
        if (lineCodes.isEmpty()) emptyList()
        else dao.announcementsForLines(lineCodes).map { it.toModel() }.filter { it.isActiveAt(now) }

    /** Which timetable applies right now, and the current minute on that timetable's clock. */
    data class ServiceMoment(val serviceDay: ServiceDay, val minutesAfterMidnight: Int)

    companion object {
        /**
         * The service day rolls over at 04:00, not midnight: at 00:30 on a Tuesday the buses
         * still running are Monday's late trips (stored as minute 1440+).
         */
        const val SERVICE_DAY_START_HOUR = 4

        /** Timetables and announcements are in İzmir local time, whatever the phone's zone. */
        val IZMIR_ZONE: ZoneId = ZoneId.of("Europe/Istanbul")

        fun localNow(clock: Clock): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), IZMIR_ZONE)

        /** Public holidays are not modelled yet; they run the Sunday timetable. */
        fun serviceDayFor(date: LocalDate): ServiceDay = when (date.dayOfWeek) {
            DayOfWeek.SATURDAY -> ServiceDay.SATURDAY
            DayOfWeek.SUNDAY -> ServiceDay.SUNDAY
            else -> ServiceDay.WEEKDAY
        }

        fun serviceMoment(now: LocalDateTime): ServiceMoment {
            val minute = now.hour * 60 + now.minute
            return if (now.hour < SERVICE_DAY_START_HOUR) {
                ServiceMoment(serviceDayFor(now.toLocalDate().minusDays(1)), minute + 24 * 60)
            } else {
                ServiceMoment(serviceDayFor(now.toLocalDate()), minute)
            }
        }

        /** Index of the first departure at or after [minute], or -1 when the day's service is over. */
        fun nextDepartureIndex(departures: List<Departure>, minute: Int): Int =
            departures.indexOfFirst { it.minutesAfterMidnight >= minute }

        /** The first departure at or after [time]; departures are sorted by minute. */
        fun nextDeparture(departures: List<Departure>, time: LocalTime): Departure? =
            departures.getOrNull(nextDepartureIndex(departures, time.hour * 60 + time.minute))

        /** 1445 → "00:05". */
        fun formatClock(minutesAfterMidnight: Int): String =
            "%02d:%02d".format(Locale.ROOT, (minutesAfterMidnight / 60) % 24, minutesAfterMidnight % 60)
    }
}

/**
 * The line shown on the Map tab, kept app-wide so switching tabs doesn't lose it. Null means
 * the all-lines overview. Lines opened from Home or a stop are separate screens and don't
 * change this.
 */
@Singleton
class MapSelection @Inject constructor() {
    private val _line = MutableStateFlow<String?>(null)
    val line: StateFlow<String?> = _line.asStateFlow()

    fun select(code: String?) {
        _line.value = code
    }

    private val _focusStop = MutableStateFlow<String?>(null)

    /** A stop to show on the Map tab ("Show on map"), handled once by the map. */
    val focusStop: StateFlow<String?> = _focusStop.asStateFlow()

    fun showStop(code: String) {
        _focusStop.value = code
    }

    fun consumeFocus() {
        _focusStop.value = null
    }
}
