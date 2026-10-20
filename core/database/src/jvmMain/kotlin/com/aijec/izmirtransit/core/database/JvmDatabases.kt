package com.aijec.izmirtransit.core.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Desktop builders, used by the importer tool and by tests. */
object JvmDatabases {
    fun file(file: File): IzmirDatabase =
        Room.databaseBuilder<IzmirDatabase>(name = file.absolutePath)
            .configure()
            // No WAL side files: the importer output must be one self-contained file.
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()

    fun inMemory(): IzmirDatabase =
        Room.inMemoryDatabaseBuilder<IzmirDatabase>().configure().build()

    /** Compacts the file; run once after an import, before shipping it. */
    suspend fun vacuum(database: IzmirDatabase) {
        database.useWriterConnection { connection -> connection.usePrepared("VACUUM") { it.step() } }
    }

    private fun RoomDatabase.Builder<IzmirDatabase>.configure() =
        setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).fallbackToDestructiveMigration(dropAllTables = true)
}
