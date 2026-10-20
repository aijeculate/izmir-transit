package com.aijec.izmirtransit.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
    /** Map layers that are switched on; all by default. */
    val mapLayers: Set<MapLayer> = MapLayer.entries.toSet(),
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
                // Stored as the switched-off layers, so layers added later start switched on.
                mapLayers = MapLayer.entries.toSet() - prefs[HIDDEN_LAYERS].orEmpty().mapNotNull { name ->
                    runCatching { MapLayer.valueOf(name) }.getOrNull()
                }.toSet(),
            )
        }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[THEME] = mode.name }

    suspend fun setAccent(accent: AccentTheme) = store.edit { it[ACCENT] = accent.name }

    suspend fun setSearchAtBottom(bottom: Boolean) = store.edit { it[SEARCH_AT_BOTTOM] = bottom }

    suspend fun setStyle(style: UiStyle) = store.edit { it[STYLE] = style.name }

    suspend fun setShowBothDirections(both: Boolean) = store.edit { it[BOTH_DIRECTIONS] = both }

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
    }
}
