package com.aijec.izmirtransit.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.aijec.izmirtransit.core.model.AccentTheme
import com.aijec.izmirtransit.core.model.MapLayer
import com.aijec.izmirtransit.core.model.ThemeMode
import com.aijec.izmirtransit.core.model.UiStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentTheme = AccentTheme.OCEAN,
    /** Put the map's search field at the bottom, within thumb reach. */
    val searchAtBottom: Boolean = false,
    val style: UiStyle = UiStyle.CALM,
    /** Draw both directions of a line at once (blue and orange) instead of one with a switch. */
    val showBothDirections: Boolean = false,
    /** The first-run tutorial was finished or skipped. */
    val onboardingDone: Boolean = false,
    /** Map layers that are switched on; all by default. */
    val mapLayers: Set<MapLayer> = MapLayer.entries.toSet(),
    /** Look for a new release on GitHub about once a day. */
    val autoCheckUpdates: Boolean = true,
    /** When the last automatic update check succeeded, in epoch milliseconds. */
    val lastUpdateCheck: Long = 0,
    /** A release the user chose "Later" for; its prompt is not shown again. */
    val skippedUpdate: String? = null,
)

/** User preferences, in the same DataStore file as favourites. */
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            AppSettings(
                themeMode = prefs[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                accent = prefs[ACCENT]?.let { runCatching { AccentTheme.valueOf(it) }.getOrNull() } ?: AccentTheme.OCEAN,
                searchAtBottom = prefs[SEARCH_AT_BOTTOM] ?: false,
                style = prefs[STYLE]?.let { runCatching { UiStyle.valueOf(it) }.getOrNull() } ?: UiStyle.CALM,
                showBothDirections = prefs[BOTH_DIRECTIONS] ?: false,
                onboardingDone = prefs[ONBOARDING_DONE] ?: false,
                // Stored as the switched-off layers, so layers added later start switched on.
                mapLayers = MapLayer.entries.toSet() - prefs[HIDDEN_LAYERS].orEmpty().mapNotNull { name ->
                    runCatching { MapLayer.valueOf(name) }.getOrNull()
                }.toSet(),
                autoCheckUpdates = prefs[AUTO_UPDATE_CHECK] ?: true,
                lastUpdateCheck = prefs[LAST_UPDATE_CHECK] ?: 0,
                skippedUpdate = prefs[SKIPPED_UPDATE],
            )
        }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[THEME] = mode.name }

    suspend fun setAccent(accent: AccentTheme) = store.edit { it[ACCENT] = accent.name }

    suspend fun setSearchAtBottom(bottom: Boolean) = store.edit { it[SEARCH_AT_BOTTOM] = bottom }

    suspend fun setStyle(style: UiStyle) = store.edit { it[STYLE] = style.name }

    suspend fun setShowBothDirections(both: Boolean) = store.edit { it[BOTH_DIRECTIONS] = both }

    suspend fun setOnboardingDone(done: Boolean) = store.edit { it[ONBOARDING_DONE] = done }

    suspend fun setAutoCheckUpdates(enabled: Boolean) = store.edit { it[AUTO_UPDATE_CHECK] = enabled }

    suspend fun setLastUpdateCheck(epochMillis: Long) = store.edit { it[LAST_UPDATE_CHECK] = epochMillis }

    suspend fun setSkippedUpdate(version: String) = store.edit { it[SKIPPED_UPDATE] = version }

    suspend fun setMapLayer(layer: MapLayer, visible: Boolean) = store.edit { prefs ->
        val hidden = prefs[HIDDEN_LAYERS].orEmpty()
        prefs[HIDDEN_LAYERS] = if (visible) hidden - layer.name else hidden + layer.name
    }

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent_theme")
        val SEARCH_AT_BOTTOM = booleanPreferencesKey("search_at_bottom")
        val HIDDEN_LAYERS = stringSetPreferencesKey("hidden_map_layers")
        val STYLE = stringPreferencesKey("ui_style")
        val BOTH_DIRECTIONS = booleanPreferencesKey("both_directions")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val SKIPPED_UPDATE = stringPreferencesKey("skipped_update")
    }
}
