package com.aijec.izmirtransit.core.model

import com.aijec.izmirtransit.core.model.geo.LatLng
import java.time.LocalDateTime

/** Every line and stop belongs to one mode, so metro, İZBAN, tram and ferries can slot in later. */
enum class TransitMode {
    BUS,
    METRO,
    TRAM,
    IZBAN,
    FERRY;

    /** Only ESHOT buses publish live positions; the rest run to timetable in the app. */
    val hasLiveVehicles: Boolean get() = this == BUS
}

/**
 * ESHOT line and stop codes are plain numbers; every other mode's carry a letter prefix
 * ("M1", "T-KONAK", "I20", "F1"). Lets callers skip live requests without a lookup.
 */
fun isBusCode(code: String): Boolean = code.isNotEmpty() && code.all { it.isDigit() }

/** Direction as the static datasets number it: YON 1 is outbound, YON 2 is return. */
enum class Direction(val datasetCode: Int) {
    OUTBOUND(1),
    RETURN(2);

    val opposite: Direction get() = if (this == OUTBOUND) RETURN else OUTBOUND

    companion object {
        fun fromDatasetCode(code: Int): Direction? = entries.firstOrNull { it.datasetCode == code }
    }
}

/** Which timetable applies. ESHOT TARIFE_ID 1/2/3 maps to these, in that order. */
enum class ServiceDay { WEEKDAY, SATURDAY, SUNDAY }

data class Line(
    val mode: TransitMode,
    val code: String,
    val name: String,
    /** Badge text: "5", "M1", "Konak", "İZBAN". */
    val displayCode: String = code,
    /** Official colour (ARGB) for rail lines; null uses the app's accent. */
    val color: Int? = null,
    val routeDescription: String,
    val notes: String,
    val origin: String,
    val destination: String,
)

data class Stop(
    val mode: TransitMode,
    val code: String,
    val name: String,
    val location: LatLng,
)

/** One direction of a line: its drawn route and its stops in travel order. */
data class LineRoute(
    val lineCode: String,
    val direction: Direction,
    val shape: List<LatLng>,
    val stops: List<Stop>,
)

data class Departure(
    val lineCode: String,
    val direction: Direction,
    val serviceDay: ServiceDay,
    /** May exceed 1440 for trips after midnight that belong to the previous service day. */
    val minutesAfterMidnight: Int,
    val wheelchairAccessible: Boolean,
    val bikeRack: Boolean,
    val electric: Boolean,
)

/**
 * Rail, sea and air connections a bus line serves (the "baglantili hatlar" dataset).
 * Codes 4 and 5 are confirmed by their lines (all airport / all Otogar lines); 1–3 follow
 * the dataset's documented order "metro, İZBAN, ferry, airport, bus terminal", and 3
 * matches its lines all ending at ferry piers (İskele).
 */
enum class ConnectionType(val code: Int) {
    METRO(1),
    IZBAN(2),
    FERRY(3),
    AIRPORT(4),
    BUS_TERMINAL(5);

    companion object {
        fun fromCode(code: Int): ConnectionType? = entries.firstOrNull { it.code == code }
    }
}

/** A timetabled departure from a station or pier (rail and ferry). */
data class ScheduledDeparture(
    val line: Line,
    val direction: Direction,
    val minutesAfterMidnight: Int,
    val headsign: String,
)

data class Announcement(
    val lineCode: String,
    val title: String,
    val startsAt: LocalDateTime?,
    val endsAt: LocalDateTime?,
) {
    fun isActiveAt(time: LocalDateTime): Boolean =
        (startsAt == null || !time.isBefore(startsAt)) && (endsAt == null || !time.isAfter(endsAt))
}

/** One simplified route for the all-lines overview, drawn in its line's colour. */
data class OverviewRoute(
    val mode: TransitMode,
    /** Official colour (ARGB), or null for buses, which use the accent. */
    val color: Int?,
    val points: List<LatLng>,
)
