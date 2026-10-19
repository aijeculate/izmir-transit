package com.aijec.izmirtransit.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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
        insertLines(data.lines)
        insertStops(data.stops)
        insertStopLines(data.stopLines)
        insertLineStops(data.lineStops)
        insertShapes(data.shapes)
        insertDepartures(data.departures)
        insertAnnouncements(data.announcements)
        insertConnections(data.connections)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLines(rows: List<LineEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStops(rows: List<StopEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStopLines(rows: List<StopLineEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLineStops(rows: List<LineStopEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertShapes(rows: List<ShapeEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDepartures(rows: List<DepartureEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAnnouncements(rows: List<AnnouncementEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertConnections(rows: List<ConnectionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMeta(rows: List<MetaEntity>)

    @Query("SELECT * FROM dataset_meta") suspend fun meta(): List<MetaEntity>
}

@Dao
interface LineDao {
    @Query("SELECT * FROM lines WHERE mode = :mode AND code = :code")
    suspend fun line(mode: String, code: String): LineEntity?

    @Query("SELECT * FROM lines WHERE mode = :mode ORDER BY CAST(code AS INTEGER), code")
    fun observeLines(mode: String): Flow<List<LineEntity>>

    /**
     * [folded] must already be folded with Turkish rules. Exact code first, then codes
     * starting with the query, then name matches.
     */
    @Query(
        """
        SELECT * FROM lines
        WHERE mode = :mode AND (code LIKE :folded || '%' OR search_text LIKE '%' || :folded || '%')
        ORDER BY (code = :folded) DESC, (code LIKE :folded || '%') DESC, CAST(code AS INTEGER), code
        LIMIT :limit
        """,
    )
    suspend fun search(mode: String, folded: String, limit: Int): List<LineEntity>

    @Query("SELECT * FROM shapes WHERE mode = :mode AND line_code = :lineCode ORDER BY direction")
    suspend fun shapes(mode: String, lineCode: String): List<ShapeEntity>

    @Query(
        """
        SELECT s.* FROM line_stops ls
        JOIN stops s ON s.mode = ls.mode AND s.code = ls.stop_code
        WHERE ls.mode = :mode AND ls.line_code = :lineCode AND ls.direction = :direction
        ORDER BY ls.sequence
        """,
    )
    suspend fun stopsInOrder(mode: String, lineCode: String, direction: Int): List<StopEntity>

    @Query("SELECT * FROM connections WHERE mode = :mode AND line_code = :lineCode")
    suspend fun connections(mode: String, lineCode: String): List<ConnectionEntity>
}

@Dao
interface StopDao {
    @Query("SELECT * FROM stops WHERE mode = :mode AND code = :code")
    suspend fun stop(mode: String, code: String): StopEntity?

    @Query(
        """
        SELECT * FROM stops
        WHERE mode = :mode AND (code = :folded OR search_text LIKE '%' || :folded || '%')
        ORDER BY (code = :folded) DESC, (search_text LIKE :folded || '%') DESC, name
        LIMIT :limit
        """,
    )
    suspend fun search(mode: String, folded: String, limit: Int): List<StopEntity>

    /** Candidate stops for "nearby"; the caller sorts by true distance. */
    @Query(
        """
        SELECT * FROM stops
        WHERE mode = :mode
          AND latitude BETWEEN :minLatitude AND :maxLatitude
          AND longitude BETWEEN :minLongitude AND :maxLongitude
        """,
    )
    suspend fun inBox(
        mode: String,
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double,
    ): List<StopEntity>

    @Query(
        """
        SELECT l.* FROM stop_lines sl
        JOIN lines l ON l.mode = sl.mode AND l.code = sl.line_code
        WHERE sl.mode = :mode AND sl.stop_code = :stopCode
        ORDER BY CAST(l.code AS INTEGER), l.code
        """,
    )
    suspend fun linesAtStop(mode: String, stopCode: String): List<LineEntity>
}

@Dao
interface TimetableDao {
    @Query(
        """
        SELECT * FROM departures
        WHERE mode = :mode AND line_code = :lineCode AND service_day = :serviceDay AND direction = :direction
        ORDER BY minutes_after_midnight
        """,
    )
    suspend fun departures(mode: String, lineCode: String, serviceDay: Int, direction: Int): List<DepartureEntity>

    @Query("SELECT * FROM announcements WHERE mode = :mode AND line_code = :lineCode ORDER BY starts_at DESC")
    suspend fun announcements(mode: String, lineCode: String): List<AnnouncementEntity>
}
