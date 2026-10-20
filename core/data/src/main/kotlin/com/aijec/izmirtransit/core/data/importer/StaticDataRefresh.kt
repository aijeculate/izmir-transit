package com.aijec.izmirtransit.core.data.importer

import com.aijec.izmirtransit.core.database.IzmirDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Downloads the ESHOT open-data files into a directory under their portal names, the
 * layout [EshotSources.fromDirectory] expects. The six CSVs are required; the GTFS zip is
 * optional (without it, stop order comes from route shapes).
 */
class EshotDownloader(
    client: OkHttpClient,
    private val csvBaseUrl: String = CSV_BASE_URL,
    private val gtfsUrl: String = GTFS_URL,
) {
    // Large files: the live API's 10 s call timeout would cut the 18 MB GTFS zip short.
    private val client = client.newBuilder()
        .callTimeout(5, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun download(targetDir: File) = withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        CSV_FILES.forEach { name -> fetch(csvBaseUrl + name, File(targetDir, name)) }
        try {
            fetch(gtfsUrl, File(targetDir, GTFS_FILE))
        } catch (e: IOException) {
            log.warning("GTFS download failed, continuing without it: ${e.message}")
        }
    }

    private fun fetch(url: String, target: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty body for $url")
            val temp = File(target.parentFile, target.name + ".part")
            temp.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (temp.length() == 0L) throw IOException("Empty file from $url")
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("Could not write $target")
            }
        }
    }

    companion object {
        private val log = Logger.getLogger(EshotDownloader::class.java.name)
        const val CSV_BASE_URL = "https://openfiles.izmir.bel.tr/211488/docs/"
        const val GTFS_URL = "https://www.eshot.gov.tr/gtfs/bus-eshot-gtfs.zip"
        const val GTFS_FILE = "bus-eshot-gtfs.zip"
        val CSV_FILES = listOf(
            "eshot-otobus-duraklari.csv",
            "eshot-otobus-hatlari.csv",
            "eshot-otobus-hat-guzergahlari.csv",
            "eshot-otobus-hareketsaatleri.csv",
            "eshot-otobus-hat-duyurulari.csv",
            "eshot-otobus-baglantili-hatlar.csv",
        )
    }
}

/**
 * The weekly background refresh: download, import, sanity-check, then swap the data in a
 * single transaction. A truncated or broken download is rejected and the existing data is
 * kept, so a bad file on the portal can never empty the app.
 */
class StaticDataRefresher(
    private val downloader: EshotDownloader,
    private val database: IzmirDatabase,
    private val workDir: File,
    private val clock: Clock,
    private val minimum: MinimumCounts = MinimumCounts(),
    /** Metro, tram, İZBAN and ferries; each is refreshed on its own after the buses. */
    private val otherModes: OtherModesDownloader? = null,
) {
    /** Far below today's numbers (446 lines, 11,945 stops, 892 shapes, 63.7k departures). */
    data class MinimumCounts(val lines: Int = 300, val stops: Int = 8_000, val shapes: Int = 300, val departures: Int = 20_000)

    sealed interface Outcome {
        data class Updated(val report: ImportReport) : Outcome
        data class Rejected(val reason: String) : Outcome
    }

    /** @throws IOException when a download fails; the caller should retry later. */
    suspend fun refresh(): Outcome {
        val dir = File(workDir, "eshot-download")
        dir.deleteRecursively()
        try {
            downloader.download(dir)
            val result = withContext(Dispatchers.Default) {
                EshotImporter.build(EshotSources.fromDirectory(dir, File(dir, EshotDownloader.GTFS_FILE)), clock.instant())
            }
            validate(result)?.let { return Outcome.Rejected(it) }
            EshotImporter.importInto(database, result)
            val notes = otherModes?.let { refreshOtherModes(it) }.orEmpty()
            return Outcome.Updated(result.report.copy(warnings = result.report.warnings + notes))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Each feed is downloaded, built and checked separately, so a broken İZBAN feed can't
     * hold back the metro. Returns one note per mode for the report.
     */
    private suspend fun refreshOtherModes(other: OtherModesDownloader): List<String> {
        val notes = ArrayList<String>()
        val dir = File(workDir, "other-download")
        dir.deleteRecursively()
        try {
            for (feed in RailFeed.entries) {
                notes += runCatching {
                    val zip = other.downloadRail(feed, dir)
                    val result = withContext(Dispatchers.Default) { RailImporter.build(feed, zip, clock.instant()) }
                    if (result.data.stops.size < 10 || result.data.stopDepartures.size < 1_000) "${feed.name}: rejected, too little data"
                    else {
                        database.staticDataDao().replaceMode(result.data)
                        "${feed.name}: updated"
                    }
                }.getOrElse { "${feed.name}: kept old data (${it.message})" }
            }
            notes += runCatching {
                val ferryDir = File(dir, "ferry")
                other.downloadFerry(ferryDir)
                val result = withContext(Dispatchers.Default) { FerryImporter.build(ferryDir, clock.instant()) }
                if (result.data.stops.size < 5 || result.data.stopDepartures.size < 200) "FERRY: rejected, too little data"
                else {
                    database.staticDataDao().replaceMode(result.data)
                    "FERRY: updated"
                }
            }.getOrElse { "FERRY: kept old data (${it.message})" }
        } finally {
            dir.deleteRecursively()
        }
        return notes
    }

    private fun validate(result: ImportResult): String? {
        val data = result.data
        return when {
            data.lines.size < minimum.lines -> "only ${data.lines.size} lines"
            data.stops.size < minimum.stops -> "only ${data.stops.size} stops"
            data.shapes.size < minimum.shapes -> "only ${data.shapes.size} shapes"
            data.departures.size < minimum.departures -> "only ${data.departures.size} departures"
            else -> null
        }
    }
}

/**
 * Downloads the rail GTFS feeds and the İzdeniz pier timetables. The İzdeniz API shares
 * the İztek rate limit, so its calls go through the app-wide [pacer].
 */
class OtherModesDownloader(
    client: OkHttpClient,
    private val pacer: com.aijec.izmirtransit.core.data.live.RequestPacer,
    private val izdenizBaseUrl: String = IZDENIZ_BASE_URL,
) {
    private val client = client.newBuilder()
        .callTimeout(2, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun downloadRail(feed: RailFeed, dir: File): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        File(dir, feed.fileName).also { fetch(feed.url, it) }
    }

    /** Writes the layout [FerryImporter.build] reads: the pier list plus one file per pier and day. */
    suspend fun downloadFerry(dir: File) {
        withContext(Dispatchers.IO) { dir.mkdirs() }
        val piersFile = File(dir, FerryImporter.PIERS_FILE)
        pacer.paced { withContext(Dispatchers.IO) { fetch(izdenizBaseUrl + "iskeleler", piersFile) } }
        val piers = FerryImporter.parsePiers(piersFile.readText()).filter { it.active }
        for (pier in piers) for (dayId in FerryImporter.DAYS.values) {
            val target = File(dir, FerryImporter.pierFile(pier.id, dayId))
            pacer.paced { withContext(Dispatchers.IO) { fetch(izdenizBaseUrl + "iskelesefersaatleri/${pier.id}/$dayId", target) } }
        }
    }

    private fun fetch(url: String, target: File) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val bytes = response.body?.bytes() ?: throw IOException("Empty body for $url")
            if (bytes.isEmpty()) throw IOException("Empty file from $url")
            target.writeBytes(bytes)
        }
    }

    companion object {
        const val IZDENIZ_BASE_URL = "https://openapi.izmir.bel.tr/api/izdeniz/"
    }
}
