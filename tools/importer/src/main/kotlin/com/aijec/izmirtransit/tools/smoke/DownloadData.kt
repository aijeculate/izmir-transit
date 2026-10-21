package com.aijec.izmirtransit.tools.smoke

import com.aijec.izmirtransit.core.data.importer.EshotDownloader
import com.aijec.izmirtransit.core.data.importer.OtherModesDownloader
import com.aijec.izmirtransit.core.data.importer.RailFeed
import com.aijec.izmirtransit.core.data.live.RequestPacer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.time.Clock
import kotlin.time.measureTime

/**
 * Downloads every source the shipped database is built from into research/raw, the layout
 * :tools:importer:run reads: ESHOT CSVs and GTFS, the three rail GTFS feeds, the İZBAN
 * website timetable and the İzdeniz pier timetables. About six minutes, mostly the polite
 * one-request-a-second İZBAN and İzdeniz calls.
 *
 *   ./gradlew :tools:importer:downloadData
 */
fun main(): Unit = runBlocking {
    val root = File(System.getProperty("izmir.root") ?: ".").absoluteFile
    val raw = File(root, "research/raw").apply { mkdirs() }
    val client = OkHttpClient()
    val other = OtherModesDownloader(client, RequestPacer())
    val time = measureTime {
        println("ESHOT CSVs and GTFS…")
        EshotDownloader(client).download(raw)
        for (feed in RailFeed.entries) {
            println("${feed.name} GTFS…")
            other.downloadRail(feed, File(raw, "rail"))
        }
        println("İZBAN timetable from izban.com.tr (about four minutes)…")
        val izban = File(raw, "izban-web.part").apply { deleteRecursively() }
        other.downloadIzban(izban, Clock.systemUTC())
        File(raw, "izban-web").deleteRecursively()
        check(izban.renameTo(File(raw, "izban-web"))) { "Could not move $izban" }
        println("İzdeniz pier timetables…")
        val ferry = File(raw, "ferry.part").apply { deleteRecursively() }
        other.downloadFerry(ferry)
        File(raw, "ferry").deleteRecursively()
        check(ferry.renameTo(File(raw, "ferry"))) { "Could not move $ferry" }
    }
    println("All sources saved to $raw in $time. Build the database with ./gradlew :tools:importer:run")
}
