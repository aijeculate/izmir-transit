package com.aijec.izmirtransit.core.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [
        LineEntity::class,
        StopEntity::class,
        StopLineEntity::class,
        LineStopEntity::class,
        ShapeEntity::class,
        DepartureEntity::class,
        AnnouncementEntity::class,
        ConnectionEntity::class,
        MetaEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(IzmirDatabaseConstructor::class)
abstract class IzmirDatabase : RoomDatabase() {
    abstract fun staticDataDao(): StaticDataDao
    abstract fun lineDao(): LineDao
    abstract fun stopDao(): StopDao
    abstract fun timetableDao(): TimetableDao

    companion object {
        /** File name of the database shipped in the app's assets and built by :tools:importer. */
        const val FILE_NAME = "izmir-transit.db"
    }
}

// Room's compiler generates the actual implementation for each target.
@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object IzmirDatabaseConstructor : RoomDatabaseConstructor<IzmirDatabase> {
    override fun initialize(): IzmirDatabase
}
