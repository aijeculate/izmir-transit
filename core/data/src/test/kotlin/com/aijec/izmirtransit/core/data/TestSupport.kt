package com.aijec.izmirtransit.core.data

import com.aijec.izmirtransit.core.data.live.FetchResult
import com.aijec.izmirtransit.core.data.live.RequestPacer
import com.aijec.izmirtransit.core.data.source.eshot.live.IztekApi
import com.aijec.izmirtransit.core.data.source.eshot.live.IztekLiveDataSource
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.assertIs
import kotlin.time.Duration

fun resource(path: String): String =
    checkNotNull(object {}.javaClass.getResource("/$path")) { "Missing test resource $path" }.readText(Charsets.UTF_8)

/** Serves [responses] in order from a local server and runs [block] against a real client. */
fun <T> withIztekServer(
    vararg responses: MockResponse,
    block: suspend (IztekLiveDataSource, MockWebServer) -> T,
): T {
    val server = MockWebServer()
    responses.forEach(server::enqueue)
    server.start()
    try {
        val api = IztekApi.create(IztekApi.httpClient("IzmirTransit-test"), server.url("/api/iztek/").toString())
        return runBlocking { block(IztekLiveDataSource(api, RequestPacer(Duration.ZERO)), server) }
    } finally {
        server.shutdown()
    }
}

fun json(body: String, code: Int = 200): MockResponse =
    MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

fun fixture(name: String): MockResponse = json(resource("fixtures/$name"))

fun RecordedRequest.pathOnly(): String? = path

fun <T> FetchResult<T>.success(): FetchResult.Success<T> = assertIs<FetchResult.Success<T>>(this)

fun <T> FetchResult<T>.failure(): FetchResult.Failure = assertIs<FetchResult.Failure>(this)
