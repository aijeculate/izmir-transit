package com.aijec.izmirtransit.core.database

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

object AndroidDatabases {
    private const val PREFS = "izmir_database"
    private const val KEY_ASSET_STAMP = "asset_stamp"

    /**
     * Opens the on-device database. The copy shipped in assets (built by :tools:importer)
     * is installed the first time Room opens a connection, which happens on Room's IO
     * dispatcher, never the main thread. It is reinstalled after each app update. The
     * schema matches because the importer writes through this same Room code.
     *
     * Room's createFromAsset is not used: it is unsupported with an SQLiteDriver, and it
     * never replaces the file when an update ships newer data.
     */
    fun prepackaged(context: Context): IzmirDatabase {
        val app = context.applicationContext
        return Room.databaseBuilder<IzmirDatabase>(
            context = app,
            name = app.getDatabasePath(IzmirDatabase.FILE_NAME).absolutePath,
        )
            .setDriver(AssetInstallingDriver(app, BundledSQLiteDriver()))
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    private class AssetInstallingDriver(
        private val context: Context,
        private val delegate: SQLiteDriver,
    ) : SQLiteDriver by delegate {
        @Volatile private var checked = false

        override fun open(fileName: String): SQLiteConnection {
            if (!checked) {
                synchronized(this) {
                    if (!checked) {
                        installAssetIfNeeded(context, File(fileName))
                        checked = true
                    }
                }
            }
            return delegate.open(fileName)
        }
    }

    private fun installAssetIfNeeded(context: Context, file: File) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = installStamp(context)
        if (file.exists() && prefs.getLong(KEY_ASSET_STAMP, -1) == stamp) return

        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        context.assets.open(IzmirDatabase.FILE_NAME).use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        listOf("-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        check(temp.renameTo(file) || (file.delete() && temp.renameTo(file))) { "Could not install $file" }
        prefs.edit().putLong(KEY_ASSET_STAMP, stamp).commit()
    }

    /** Changes on every install or update of the app, i.e. whenever the asset may have. */
    private fun installStamp(context: Context): Long {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return info.lastUpdateTime
    }
}
