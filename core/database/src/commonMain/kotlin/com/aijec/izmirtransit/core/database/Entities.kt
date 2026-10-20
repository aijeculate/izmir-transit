package com.aijec.izmirtransit.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Every table is keyed by `mode` first so other transit modes can share the schema.
// Codes are TEXT because metro and tram lines are not numeric ("M1", "T2").

@Entity(tableName = "lines", primaryKeys = ["mode", "code"])
data class LineEntity(
    val mode: String,
    /** Unique across modes: bus lines are numbers, others carry a prefix (e.g. "M1", "T-KONAK"). */
    val code: String,
    /** What the badge shows: "5", "M1", "Konak", "İZBAN", "Vapur". */
    @ColumnInfo(name = "display_code") val displayCode: String,
    /** Official line colour as ARGB, or null to use the app's accent (buses). */
    val color: Int?,
    val name: String,
    /** [name], code and endpoints folded with Turkish rules, for search. */
    @ColumnInfo(name = "search_text") val searchText: String,
    @ColumnInfo(name = "route_description") val routeDescription: String,
    val notes: String,
    val origin: String,
    val destination: String,
)

@Entity(
    tableName = "stops",
    primaryKeys = ["mode", "code"],
    indices = [Index("latitude", "longitude")],
)
data class StopEntity(
    val mode: String,
    val code: String,
    val name: String,
    @ColumnInfo(name = "search_text") val searchText: String,
    val latitude: Double,
    val longitude: Double,
)

/** Which lines serve a stop, from DURAKTAN_GECEN_HATLAR. Unordered. */
@Entity(
    tableName = "stop_lines",
    primaryKeys = ["mode", "stop_code", "line_code"],
    indices = [Index("mode", "line_code")],
)
data class StopLineEntity(
    val mode: String,
    @ColumnInfo(name = "stop_code") val stopCode: String,
    @ColumnInfo(name = "line_code") val lineCode: String,
)

/** Stops of one line direction in travel order. */
@Entity(
    tableName = "line_stops",
    primaryKeys = ["mode", "line_code", "direction", "sequence"],
    indices = [Index("mode", "stop_code")],
)
data class LineStopEntity(
    val mode: String,
    @ColumnInfo(name = "line_code") val lineCode: String,
    /** Dataset code: 1 outbound, 2 return. */
    val direction: Int,
    val sequence: Int,
    @ColumnInfo(name = "stop_code") val stopCode: String,
    /** Where the order came from: [SOURCE_GTFS] or [SOURCE_SHAPE]. */
    val source: String,
) {
    companion object {
        const val SOURCE_GTFS = "gtfs"
        const val SOURCE_SHAPE = "shape"
    }
}

@Entity(tableName = "shapes", primaryKeys = ["mode", "line_code", "direction"])
data class ShapeEntity(
    val mode: String,
    @ColumnInfo(name = "line_code") val lineCode: String,
    val direction: Int,
    /** Google encoded polyline, 1e-5 precision. */
    @ColumnInfo(name = "encoded_polyline") val encodedPolyline: String,
    @ColumnInfo(name = "length_meters") val lengthMeters: Double,
)

@Entity(
    tableName = "departures",
    primaryKeys = ["mode", "line_code", "service_day", "direction", "sequence"],
)
data class DepartureEntity(
    val mode: String,
    @ColumnInfo(name = "line_code") val lineCode: String,
    /** ServiceDay ordinal: 0 weekday, 1 Saturday, 2 Sunday. */
    @ColumnInfo(name = "service_day") val serviceDay: Int,
    val direction: Int,
    val sequence: Int,
    @ColumnInfo(name = "minutes_after_midnight") val minutesAfterMidnight: Int,
    @ColumnInfo(name = "wheelchair_accessible") val wheelchairAccessible: Boolean,
    @ColumnInfo(name = "bike_rack") val bikeRack: Boolean,
    val electric: Boolean,
)

@Entity(tableName = "announcements", indices = [Index("mode", "line_code")])
data class AnnouncementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    @ColumnInfo(name = "line_code") val lineCode: String,
    val title: String,
    /** ISO-8601 local date-time in Europe/Istanbul, e.g. 2026-10-02T00:00. */
    @ColumnInfo(name = "starts_at") val startsAt: String?,
    @ColumnInfo(name = "ends_at") val endsAt: String?,
)

/** A line that serves a metro, İZBAN, ferry, airport or bus-terminal connection point. */
@Entity(tableName = "connections", primaryKeys = ["mode", "line_code", "connection_type"])
data class ConnectionEntity(
    val mode: String,
    @ColumnInfo(name = "line_code") val lineCode: String,
    /** BAGLANTI_TIP_ID; meaning of 1–5 still to be confirmed. */
    @ColumnInfo(name = "connection_type") val connectionType: Int,
    @ColumnInfo(name = "outbound_hours") val outboundHours: String,
    @ColumnInfo(name = "return_hours") val returnHours: String,
)

/**
 * A scheduled departure from a stop: metro, tram and İZBAN from GTFS stop_times, ferries
 * from the İzdeniz timetable. Buses use live arrivals instead.
 */
@Entity(
    tableName = "stop_departures",
    indices = [Index("stop_code", "service_day", "minutes_after_midnight")],
)
data class StopDepartureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    @ColumnInfo(name = "stop_code") val stopCode: String,
    @ColumnInfo(name = "line_code") val lineCode: String,
    val direction: Int,
    @ColumnInfo(name = "service_day") val serviceDay: Int,
    /** May exceed 1440 for trips after midnight, as in GTFS ("24:40:00"). */
    @ColumnInfo(name = "minutes_after_midnight") val minutesAfterMidnight: Int,
    /** Where it's going: the GTFS headsign or the ferry's destination piers. */
    val headsign: String,
)

/** Key-value facts about the imported data, e.g. when it was built and from what. */
@Entity(tableName = "dataset_meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
