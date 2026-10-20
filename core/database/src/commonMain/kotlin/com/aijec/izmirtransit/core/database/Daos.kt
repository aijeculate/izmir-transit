package com.aijec.izmirtransit.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

// Codes are unique across modes (bus stops and lines are plain numbers; rail and ferry
// codes carry a prefix such as "M12" or "F1"), so read queries look codes up directly.
// `mode` is only needed to replace one mode's data on import.

/** Everything one import writes, replaced in a single transaction. */
data class StaticDataSet(
    val mode: String,
    val lines: List<LineEntity>,
    val stops: List<StopEntity>,
    val stopLines: List<StopLineEntity>,
    val lineStops: List<LineStopEntity>,
    val shapes: List<ShapeEntity>,
    val departures: List<DepartureEntity>,
    val announcements: List<AnnouncementEntity>,
    val connections: List<ConnectionEntity>,
    val meta: List<MetaEntity>,
    val stopDepartures: List<StopDepartureEntity> = emptyList(),
)

@Dao
interface StaticDataDao {
    /** Swaps one mode's data atomically, so readers never see a half-imported state. */
    @Transaction
    suspend fun replaceMode(data: StaticDataSet) {
        deleteLines(data.mode)
        deleteStops(data.mode)
        deleteStopLines(data.mode)
        deleteLineStops(data.mode)
        deleteShapes(data.mode)
        deleteDepartures(data.mode)
        deleteAnnouncements(data.mode)
        deleteConnections(data.mode)
        deleteStopDepartures(data.mode)
        insertLines(data.lines)
        insertStops(data.stops)
        insertStopLines(data.stopLines)
        insertLineStops(data.lineStops)
        insertShapes(data.shapes)
        insertDepartures(data.departures)
        insertAnnouncements(data.announcements)
        insertConnections(data.connections)
        insertStopDepartures(data.stopDepartures)
        insertMeta(data.meta)
    }

    @Query("DELETE FROM lines WHERE mode = :mode") suspend fun deleteLines(mode: String)
    @Query("DELETE FROM stops WHERE mode = :mode") suspend fun deleteStops(mode: String)
    @Query("DELETE FROM stop_lines WHERE mode = :mode") suspend fun deleteStopLines(mode: String)
    @Query("DELETE FROM line_stops WHERE mode = :mode") suspend fun deleteLineStops(mode: String)
    @Query("DELETE FROM shapes WHERE mode = :mode") suspend fun deleteShapes(mode: String)
    @Query("DELETE FROM departures WHERE mode = :mode") suspend fun deleteDepartures(mode: String)
    @Query("DELETE FROM announcements WHERE mode = :mode") suspend fun deleteAnnouncements(mode: String)
    @Query("DELETE FROM connections WHERE mode = :mode") suspend fun deleteConnections(mode: String)
    @Query("DELETE FROM stop_departures WHERE mode = :mode") suspend fun deleteStopDepartures(mode: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLines(rows: List<LineEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStops(rows: List<StopEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStopLines(rows: List<StopLineEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLineStops(rows: List<LineStopEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertShapes(rows: List<ShapeEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDepartures(rows: List<DepartureEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAnnouncements(rows: List<AnnouncementEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertConnections(rows: List<ConnectionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStopDepartures(rows: List<StopDepartureEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMeta(rows: List<MetaEntity>)

    @Query("SELECT * FROM dataset_meta") suspend fun meta(): List<MetaEntity>
}

@Dao
interface LineDao {
    @Query("SELECT * FROM lines WHERE code = :code")
    suspend fun line(code: String): LineEntity?

    @Query("SELECT * FROM lines WHERE mode = :mode ORDER BY CAST(code AS INTEGER), code")
    fun observeLines(mode: String): Flow<List<LineEntity>>

    /**
     * [folded] must already be folded with Turkish rules. Exact code first, then codes
     * starting with the query, then name matches; rail and ferry before buses on ties.
     */
    @Query(
        """
        SELECT * FROM lines
        WHERE display_code LIKE :folded || '%' OR code LIKE :folded || '%' OR search_text LIKE '%' || :folded || '%'
        ORDER BY (lower(display_code) = :folded OR code = :folded) DESC,
                 (lower(display_code) LIKE :folded || '%' OR code LIKE :folded || '%') DESC,
                 (mode = 'BUS'), CAST(code AS INTEGER), code
        LIMIT :limit
        """,
    )
    suspend fun search(folded: String, limit: Int): List<LineEntity>

    @Query("SELECT * FROM shapes")
    suspend fun allShapes(): List<ShapeEntity>

    @Query("SELECT * FROM shapes WHERE line_code = :lineCode ORDER BY direction")
    suspend fun shapes(lineCode: String): List<ShapeEntity>

    @Query(
        """
        SELECT s.* FROM line_stops ls
        JOIN stops s ON s.code = ls.stop_code
        WHERE ls.line_code = :lineCode AND ls.direction = :direction
        ORDER BY ls.sequence
        """,
    )
    suspend fun stopsInOrder(lineCode: String, direction: Int): List<StopEntity>

    @Query("SELECT * FROM connections WHERE line_code = :lineCode")
    suspend fun connections(lineCode: String): List<ConnectionEntity>

    @Query("SELECT * FROM lines")
    suspend fun allLines(): List<LineEntity>
}

@Dao
interface StopDao {
    @Query("SELECT * FROM stops WHERE code = :code")
    suspend fun stop(code: String): StopEntity?

    @Query(
        """
        SELECT * FROM stops
        WHERE code = :folded OR lower(code) = :folded OR search_text LIKE '%' || :folded || '%'
        ORDER BY (code = :folded OR lower(code) = :folded) DESC, (search_text LIKE :folded || '%') DESC, (mode = 'BUS'), name
        LIMIT :limit
        """,
    )
    suspend fun search(folded: String, limit: Int): List<StopEntity>

    /** Candidate stops for "nearby"; the caller sorts by true distance. */
    @Query(
        """
        SELECT * FROM stops
        WHERE latitude BETWEEN :minLatitude AND :maxLatitude
          AND longitude BETWEEN :minLongitude AND :maxLongitude
        """,
    )
    suspend fun inBox(minLatitude: Double, maxLatitude: Double, minLongitude: Double, maxLongitude: Double): List<StopEntity>

    @Query(
        """
        SELECT l.* FROM stop_lines sl
        JOIN lines l ON l.code = sl.line_code
        WHERE sl.stop_code = :stopCode
        ORDER BY (l.mode = 'BUS'), CAST(l.code AS INTEGER), l.code
        """,
    )
    suspend fun linesAtStop(stopCode: String): List<LineEntity>

    /** Scheduled departures (rail and ferry) from a stop on one service day, in time order. */
    @Query(
        """
        SELECT * FROM stop_departures
        WHERE stop_code = :stopCode AND service_day = :serviceDay AND minutes_after_midnight >= :fromMinute
        ORDER BY minutes_after_midnight
        LIMIT :limit
        """,
    )
    suspend fun scheduledDepartures(stopCode: String, serviceDay: Int, fromMinute: Int, limit: Int): List<StopDepartureEntity>

    /** One line's whole-day timetable at one station, in one direction. */
    @Query(
        """
        SELECT * FROM stop_departures
        WHERE stop_code = :stopCode AND line_code = :lineCode AND direction = :direction AND service_day = :serviceDay
        ORDER BY minutes_after_midnight
        """,
    )
    suspend fun lineDeparturesAt(stopCode: String, lineCode: String, direction: Int, serviceDay: Int): List<StopDepartureEntity>
}

@Dao
interface TimetableDao {
    @Query(
        """
        SELECT * FROM departures
        WHERE line_code = :lineCode AND service_day = :serviceDay AND direction = :direction
        ORDER BY minutes_after_midnight
        """,
    )
    suspend fun departures(lineCode: String, serviceDay: Int, direction: Int): List<DepartureEntity>

    @Query("SELECT * FROM announcements WHERE line_code = :lineCode ORDER BY starts_at DESC")
    suspend fun announcements(lineCode: String): List<AnnouncementEntity>

    @Query("SELECT * FROM announcements WHERE line_code IN (:lineCodes) ORDER BY starts_at DESC")
    suspend fun announcementsForLines(lineCodes: List<String>): List<AnnouncementEntity>
}
