package com.aijec.izmirtransit.sync

import android.content.Context
import android.util.Log
import com.aijec.izmirtransit.core.data.repository.Holidays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Bayram dates beyond the ones built into [Holidays], fetched weekly from the project
 * website and kept in a small file so they apply offline.
 */
object HolidayFile {
    private const val TAG = "HolidayFile"

    private fun file(context: Context) = File(context.filesDir, "holidays.json")

    /** At start-up, off the main thread. */
    fun load(context: Context) {
        runCatching { file(context).takeIf { it.isFile }?.readText()?.let { Holidays.update(Holidays.parse(it)) } }
            .onFailure { Log.w(TAG, "Saved holidays unreadable", it) }
    }

    suspend fun refresh(context: Context, client: OkHttpClient) = withContext(Dispatchers.IO) {
        runCatching {
            val text = client.newCall(Request.Builder().url(Holidays.URL).build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                response.body?.string().orEmpty()
            }
            Holidays.update(Holidays.parse(text)) // throws on a malformed file, before it is saved
            file(context).writeText(text)
        }.onFailure { Log.w(TAG, "Holiday dates not refreshed", it) }
    }
}
