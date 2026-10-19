package com.aijec.izmirtransit.core.model

import com.aijec.izmirtransit.core.model.geo.LatLng
import java.time.LocalDateTime

/** Every line and stop belongs to one mode, so metro, İZBAN, tram and ferries can slot in later. */
enum class TransitMode { BUS }

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

data class Announcement(
    val lineCode: String,
    val title: String,
    val startsAt: LocalDateTime?,
    val endsAt: LocalDateTime?,
) {
    fun isActiveAt(time: LocalDateTime): Boolean =
        (startsAt == null || !time.isBefore(startsAt)) && (endsAt == null || !time.isAfter(endsAt))
}
