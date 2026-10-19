package com.aijec.izmirtransit.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

/** Starred lines and stops, newest first. */
data class Favourites(val lineCodes: List<String> = emptyList(), val stopCodes: List<String> = emptyList()) {
    fun hasLine(code: String) = code in lineCodes
    fun hasStop(code: String) = code in stopCodes
    val isEmpty: Boolean get() = lineCodes.isEmpty() && stopCodes.isEmpty()
}

/**
 * Favourites live in DataStore on the device. The [DataStore] is provided by the app
 * (Android) or by tests (a temp file on the JVM).
 */
class FavouritesRepository @Inject constructor(private val store: DataStore<Preferences>) {
    val favourites: Flow<Favourites> = store.data
        // An unreadable file must not take the home screen down; start empty instead.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { Favourites(decode(it[LINES]), decode(it[STOPS])) }
        .distinctUntilChanged()

    suspend fun setLine(code: String, favourite: Boolean) = update(LINES, code, favourite)

    suspend fun setStop(code: String, favourite: Boolean) = update(STOPS, code, favourite)

    private suspend fun update(key: Preferences.Key<String>, code: String, favourite: Boolean) {
        store.edit { prefs ->
            val current = decode(prefs[key]).filter { it != code }
            prefs[key] = encode(if (favourite) listOf(code) + current else current)
        }
    }

    private companion object {
        val LINES = stringPreferencesKey("favourite_lines")
        val STOPS = stringPreferencesKey("favourite_stops")

        // Codes are digits (letters later, e.g. "M1"), never commas.
        fun decode(value: String?): List<String> = value?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        fun encode(codes: List<String>): String = codes.joinToString(",")
    }
}
