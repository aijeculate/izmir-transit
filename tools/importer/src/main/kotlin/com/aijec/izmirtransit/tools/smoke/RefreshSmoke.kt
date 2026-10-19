package com.aijec.izmirtransit.tools.smoke

import com.aijec.izmirtransit.core.data.importer.EshotDownloader
import com.aijec.izmirtransit.core.data.importer.StaticDataRefresher
import com.aijec.izmirtransit.core.database.JvmDatabases
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.time.Clock
import kotlin.time.measureTimedValue

/**
 * Runs the app's weekly refresh exactly as the worker does (download, import, sanity
 * check, swap) against the real portal, into a throwaway database.
 *
 *   ./gradlew :tools:importer:refreshSmoke
 */
fun main(): Unit = runBlocking {
    val workDir = Files.createTempDirectory("refresh-smoke").toFile()
    val database = JvmDatabases.inMemory()
    try {
        val refresher = StaticDataRefresher(EshotDownloader(OkHttpClient()), database, workDir, Clock.systemUTC())
        val (outcome, time) = measureTimedValue { refresher.refresh() }
        println("Refresh finished in $time")
        when (outcome) {
            is StaticDataRefresher.Outcome.Updated -> println("UPDATED\n${outcome.report}")
            is StaticDataRefresher.Outcome.Rejected -> println("REJECTED: ${outcome.reason}")
        }
        println("Leftover files in work dir: ${workDir.listFiles().orEmpty().size}")
    } finally {
        database.close()
        workDir.deleteRecursively()
    }
}
