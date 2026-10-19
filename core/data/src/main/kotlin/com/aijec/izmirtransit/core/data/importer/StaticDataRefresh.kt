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
            return Outcome.Updated(result.report)
        } finally {
            dir.deleteRecursively()
        }
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
