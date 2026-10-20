package com.aijec.izmirtransit.tools.smoke

import com.aijec.izmirtransit.core.data.importer.OtherModesDownloader
import com.aijec.izmirtransit.core.data.live.RequestPacer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.time.Clock
import kotlin.time.measureTime

/**
 * Saves the current İZBAN timetable from izban.com.tr into research/raw/izban-web, where
 * :tools:importer:run picks it up. About four minutes (one request a second).
 *
 *   ./gradlew :tools:importer:downloadIzban
 */
fun main(): Unit = runBlocking {
    val root = File(System.getProperty("izmir.root") ?: ".").absoluteFile
    val target = File(root, "research/raw/izban-web")
    val temp = File(root, "research/raw/izban-web.part").apply { deleteRecursively() }
    val time = measureTime { OtherModesDownloader(OkHttpClient(), RequestPacer()).downloadIzban(temp, Clock.systemUTC()) }
    target.deleteRecursively()
    check(temp.renameTo(target)) { "Could not move $temp to $target" }
    println("Saved ${target.listFiles().orEmpty().size} files to $target in $time")
}
