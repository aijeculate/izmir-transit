package com.aijec.izmirtransit.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aijec.izmirtransit.core.model.AccentTheme
import com.aijec.izmirtransit.core.model.ThemeMode
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
            )
        }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[THEME] = mode.name }

    suspend fun setAccent(accent: AccentTheme) = store.edit { it[ACCENT] = accent.name }

    suspend fun setSearchAtBottom(bottom: Boolean) = store.edit { it[SEARCH_AT_BOTTOM] = bottom }

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent_theme")
        val SEARCH_AT_BOTTOM = booleanPreferencesKey("search_at_bottom")
    }
}
