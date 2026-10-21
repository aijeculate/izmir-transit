package com.aijec.izmirtransit.core.data.update

import com.aijec.izmirtransit.core.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/** The installed app's version name, e.g. "1.0.1". */
data class AppVersion(val name: String)

/** A newer release on GitHub; [downloadUrl] is its APK, or the release page if it has none. */
data class AppUpdate(val version: String, val downloadUrl: String, val pageUrl: String)

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val update: AppUpdate) : UpdateCheck
    data object Failed : UpdateCheck
}

/**
 * Looks for a newer release of the app on GitHub, the only place it is published. One
 * unauthenticated call to the releases API; nothing about the phone is sent. Automatic checks
 * run at most once a day and can be switched off in Settings.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val client: OkHttpClient,
    private val current: AppVersion,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _available = MutableStateFlow<AppUpdate?>(null)

    /** The newest release found so far, if it is newer than this app. */
    val available: StateFlow<AppUpdate?> = _available.asStateFlow()

    val currentVersion: String get() = current.name

    /** The automatic check: skipped when switched off or when the last one was under a day ago. */
    suspend fun checkIfDue() {
        val prefs = settings.settings.first()
        if (!prefs.autoCheckUpdates) return
        val now = clock.millis()
        if (now - prefs.lastUpdateCheck < CHECK_INTERVAL.toMillis()) return
        if (check() != UpdateCheck.Failed) settings.setLastUpdateCheck(now)
    }

    suspend fun check(): UpdateCheck = try {
        val release = latestRelease()
        val version = release.tagName.removePrefix("v")
        if (isNewer(version, current.name)) {
            val apk = release.assets.firstOrNull { it.name == APK_NAME }?.downloadUrl
            val update = AppUpdate(version, apk ?: release.pageUrl, release.pageUrl)
            _available.value = update
            UpdateCheck.Available(update)
        } else {
            _available.value = null
            UpdateCheck.UpToDate
        }
    } catch (e: IOException) {
        UpdateCheck.Failed
    } catch (e: IllegalArgumentException) { // malformed JSON
        UpdateCheck.Failed
    }

    private suspend fun latestRelease(): Release = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub ${response.code}")
            json.decodeFromString(Release.serializer(), response.body?.string() ?: throw IOException("Empty body"))
        }
    }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val pageUrl: String,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(val name: String, @SerialName("browser_download_url") val downloadUrl: String)

    companion object {
        const val REPOSITORY_URL = "https://github.com/aijeculate/izmir-transit"
        private const val LATEST_RELEASE = "https://api.github.com/repos/aijeculate/izmir-transit/releases/latest"
        private const val APK_NAME = "izmir-transit.apk"
        private val CHECK_INTERVAL = Duration.ofDays(1)

        /** Compares dotted versions numerically ("1.0.10" > "1.0.9"); a missing part counts as 0. */
        fun isNewer(candidate: String, installed: String): Boolean {
            fun parts(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
