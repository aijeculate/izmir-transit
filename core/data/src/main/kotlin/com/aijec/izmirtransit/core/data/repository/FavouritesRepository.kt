package com.aijec.izmirtransit.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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

/** One starred stop or line. */
sealed interface FavouriteItem {
    val code: String

    data class StopItem(override val code: String) : FavouriteItem
    data class LineItem(override val code: String) : FavouriteItem
}

/** Starred stops and lines in one list, in the user's order: newest first unless reordered. */
data class Favourites(val items: List<FavouriteItem> = emptyList()) {
    val lineCodes: List<String> get() = items.filterIsInstance<FavouriteItem.LineItem>().map { it.code }
    val stopCodes: List<String> get() = items.filterIsInstance<FavouriteItem.StopItem>().map { it.code }
    fun hasLine(code: String) = FavouriteItem.LineItem(code) in items
    fun hasStop(code: String) = FavouriteItem.StopItem(code) in items
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * Favourites live in DataStore on the device. The [DataStore] is provided by the app
 * (Android) or by tests (a temp file on the JVM).
 *
 * Stored as one ordered list ("s:10007,l:202") so lines can sit above stops. Versions up to
 * 1.0.3 kept stops and lines in two lists; those are read once as stops then lines.
 */
class FavouritesRepository @Inject constructor(private val store: DataStore<Preferences>) {
    val favourites: Flow<Favourites> = store.data
        // An unreadable file must not take the home screen down; start empty instead.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { Favourites(read(it)) }
        .distinctUntilChanged()

    suspend fun setLine(code: String, favourite: Boolean) = update(FavouriteItem.LineItem(code), favourite)

    suspend fun setStop(code: String, favourite: Boolean) = update(FavouriteItem.StopItem(code), favourite)

    /** Moves a favourite [by] places in the combined list (negative is up), stopping at either end. */
    suspend fun move(item: FavouriteItem, by: Int) {
        store.edit { prefs ->
            val items = read(prefs).toMutableList()
            val from = items.indexOf(item)
            if (from < 0) return@edit
            items.add((from + by).coerceIn(0, items.size - 1), items.removeAt(from))
            write(prefs, items)
        }
    }

    private suspend fun update(item: FavouriteItem, favourite: Boolean) {
        store.edit { prefs ->
            val current = read(prefs).filter { it != item }
            write(prefs, if (favourite) listOf(item) + current else current)
        }
    }

    private companion object {
        val ORDER = stringPreferencesKey("favourites_order")
        val LINES = stringPreferencesKey("favourite_lines")
        val STOPS = stringPreferencesKey("favourite_stops")

        fun read(prefs: Preferences): List<FavouriteItem> {
            prefs[ORDER]?.let { order -> return decode(order).mapNotNull(::parse).distinct() }
            return decode(prefs[STOPS]).map { FavouriteItem.StopItem(it) } + decode(prefs[LINES]).map { FavouriteItem.LineItem(it) }
        }

        fun write(prefs: MutablePreferences, items: List<FavouriteItem>) {
            prefs[ORDER] = items.joinToString(",") { if (it is FavouriteItem.StopItem) "s:${it.code}" else "l:${it.code}" }
            prefs.remove(STOPS)
            prefs.remove(LINES)
        }

        fun parse(token: String): FavouriteItem? = when {
            token.startsWith("s:") -> FavouriteItem.StopItem(token.drop(2))
            token.startsWith("l:") -> FavouriteItem.LineItem(token.drop(2))
            else -> null
        }

        // Codes are digits or letters, dashes and digits ("M1", "T-KONAK"), never commas or colons.
        fun decode(value: String?): List<String> = value?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    }
}
