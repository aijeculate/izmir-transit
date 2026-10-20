package com.aijec.izmirtransit.core.data.repository

import com.aijec.izmirtransit.core.data.text.TurkishText
import com.aijec.izmirtransit.core.database.IzmirDatabase
import com.aijec.izmirtransit.core.model.Announcement
import com.aijec.izmirtransit.core.model.ConnectionType
import com.aijec.izmirtransit.core.model.Departure
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.LineRoute
import com.aijec.izmirtransit.core.model.ServiceDay
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.model.geo.BoundingBox
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import com.aijec.izmirtransit.core.model.geo.PolylineCodec
import com.aijec.izmirtransit.core.model.geo.distanceMeters
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

// Read side of the offline data. Only ESHOT buses exist so far; the `mode` parameter
// defaults keep call sites short until more modes arrive.

private val BUS = TransitMode.BUS.name

class LineRepository @Inject constructor(private val database: IzmirDatabase) {
    private val dao get() = database.lineDao()
    private val routeCache = object : LinkedHashMap<String, Map<Direction, MeasuredPolyline>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<Direction, MeasuredPolyline>>) = size > 32
    }

    suspend fun line(code: String): Line? = dao.line(BUS, code)?.toModel()

    /** Rail, sea and air connections this line serves, in a stable order. */
    suspend fun connections(code: String): List<ConnectionType> =
        dao.connections(BUS, code).mapNotNull { ConnectionType.fromCode(it.connectionType) }.distinct().sorted()

    /** Call after the static data is replaced, so cached shapes are re-read. */
    fun clearCaches() = synchronized(routeCache) { routeCache.clear() }

    /** Turkish-aware: "izmir" finds "İZMİR". Line-number matches rank first. */
    suspend fun search(query: String, limit: Int = 30): List<Line> {
        val folded = TurkishText.fold(query)
        if (folded.isEmpty()) return emptyList()
        return dao.search(BUS, folded, limit).map { it.toModel() }
    }

    /** Both directions with decoded shape and ordered stops, for the line map. */
    suspend fun routes(code: String): List<LineRoute> {
        val shapes = dao.shapes(BUS, code).associateBy { it.direction }
        return Direction.entries.mapNotNull { direction ->
            val shape = shapes[direction.datasetCode]?.let { PolylineCodec.decode(it.encodedPolyline) }.orEmpty()
            val stops = dao.stopsInOrder(BUS, code, direction.datasetCode).map { it.toModel() }
            if (shape.isEmpty() && stops.isEmpty()) null else LineRoute(code, direction, shape, stops)
        }
    }

    /** Measured shapes for snapping buses and estimating arrival; cached because polls repeat. */
    suspend fun measuredRoutes(code: String): Map<Direction, MeasuredPolyline> {
        synchronized(routeCache) { routeCache[code] }?.let { return it }
        val measured = dao.shapes(BUS, code).mapNotNull { shape ->
            Direction.fromDatasetCode(shape.direction)?.let { it to MeasuredPolyline(PolylineCodec.decode(shape.encodedPolyline)) }
        }.toMap()
        synchronized(routeCache) { routeCache[code] = measured }
        return measured
    }
}

data class NearbyStop(val stop: Stop, val distanceMeters: Double)

class StopRepository @Inject constructor(private val database: IzmirDatabase) {
    private val dao get() = database.stopDao()

    suspend fun stop(code: String): Stop? = dao.stop(BUS, code)?.toModel()

    suspend fun search(query: String, limit: Int = 30): List<Stop> {
        val folded = TurkishText.fold(query)
        if (folded.isEmpty()) return emptyList()
        return dao.search(BUS, folded, limit).map { it.toModel() }
    }

    /** Computed offline from the stops table; no network needed. */
    suspend fun nearby(center: LatLng, radiusMeters: Double = 600.0, limit: Int = 30): List<NearbyStop> {
        val box = BoundingBox.around(center, radiusMeters)
        return dao.inBox(BUS, box.minLatitude, box.maxLatitude, box.minLongitude, box.maxLongitude)
            .map { it.toModel() }
            .map { NearbyStop(it, distanceMeters(center, it.location)) }
            .filter { it.distanceMeters <= radiusMeters }
            .sortedBy { it.distanceMeters }
            .take(limit)
    }

    suspend fun linesAtStop(code: String): List<Line> = dao.linesAtStop(BUS, code).map { it.toModel() }
}

class TimetableRepository @Inject constructor(private val database: IzmirDatabase) {
    private val dao get() = database.timetableDao()

    suspend fun departures(lineCode: String, serviceDay: ServiceDay, direction: Direction): List<Departure> =
        dao.departures(BUS, lineCode, serviceDay.ordinal, direction.datasetCode).map { it.toModel() }

    suspend fun activeAnnouncements(lineCode: String, now: LocalDateTime): List<Announcement> =
        dao.announcements(BUS, lineCode).map { it.toModel() }.filter { it.isActiveAt(now) }

    /** Active announcements for any of [lineCodes], e.g. every line serving a stop. */
    suspend fun activeAnnouncementsForLines(lineCodes: List<String>, now: LocalDateTime): List<Announcement> =
        if (lineCodes.isEmpty()) emptyList()
        else dao.announcementsForLines(BUS, lineCodes).map { it.toModel() }.filter { it.isActiveAt(now) }

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
