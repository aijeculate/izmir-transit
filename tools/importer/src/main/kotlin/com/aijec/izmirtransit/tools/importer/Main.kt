package com.aijec.izmirtransit.tools.importer

import com.aijec.izmirtransit.core.data.importer.EshotImporter
import com.aijec.izmirtransit.core.data.importer.EshotSources
import com.aijec.izmirtransit.core.data.importer.FerryImporter
import com.aijec.izmirtransit.core.data.importer.ImportResult
import com.aijec.izmirtransit.core.data.importer.RailFeed
import com.aijec.izmirtransit.core.data.importer.RailImporter
import com.aijec.izmirtransit.core.database.IzmirDatabase
import com.aijec.izmirtransit.core.database.JvmDatabases
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/**
 * Builds the database shipped in the app's assets from the downloaded open-data files.
 *
 *   ./gradlew :tools:importer:run --args="--raw research/raw --gtfs research/raw/bus-eshot-gtfs.zip --out build/izmir-transit.db"
 *
 * Rail feeds are read from `<raw>/rail/<feed>.zip` and ferries from `<raw>/ferry/`; a
 * missing source is skipped with a warning.
 *
 * Writing through Room itself guarantees the schema and identity hash match what the app
 * expects from `createFromAsset`.
 */
fun main(args: Array<String>) {
    val options = args.toList().chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val root = File(System.getProperty("izmir.root") ?: ".").absoluteFile
    fun path(value: String) = File(value).let { if (it.isAbsolute) it else File(root, value) }

    val rawDir = path(options["raw"] ?: "research/raw")
    val gtfs = options["gtfs"]?.let(::path) ?: File(rawDir, "bus-eshot-gtfs.zip")
    val out = path(options["out"] ?: "build/${IzmirDatabase.FILE_NAME}")

    if (!rawDir.isDirectory) {
        System.err.println("Raw data directory not found: $rawDir")
        exitProcess(1)
    }

    val (results, parseTime) = measureTimedValue {
        buildList<ImportResult> {
            add(EshotImporter.build(EshotSources.fromDirectory(rawDir, gtfs)))
            for (feed in RailFeed.entries) {
                val zip = File(rawDir, "rail/${feed.fileName}")
                if (zip.exists()) add(RailImporter.build(feed, zip)) else println("WARNING: ${feed.name} skipped, $zip not found")
            }
            val ferryDir = File(rawDir, "ferry")
            if (File(ferryDir, FerryImporter.PIERS_FILE).exists()) add(FerryImporter.build(ferryDir))
            else println("WARNING: ferries skipped, $ferryDir has no ${FerryImporter.PIERS_FILE}")
        }
    }
    println("Parsed in $parseTime")
    results.forEach { println("== ${it.data.mode}\n${it.report}") }

    out.parentFile?.mkdirs()
    listOf(out, File("${out.path}-journal"), File("${out.path}-wal"), File("${out.path}-shm")).forEach { it.delete() }
    val writeTime = measureTime {
        runBlocking {
            val database = JvmDatabases.file(out)
            try {
                results.forEach { database.staticDataDao().replaceMode(it.data) }
                JvmDatabases.vacuum(database)
            } finally {
                database.close()
            }
        }
    }
    println("Wrote ${out.path} (%.1f MB) in $writeTime".format(out.length() / 1_048_576.0))
}
