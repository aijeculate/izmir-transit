package com.aijec.izmirtransit.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.distanceMeters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject

@Serializable
data class SavedPlace(val name: String, val latitude: Double, val longitude: Double) {
    val location: LatLng get() = LatLng(latitude, longitude)

    companion object {
        fun of(name: String, location: LatLng) = SavedPlace(name, location.latitude, location.longitude)
    }
}

enum class SavedKind { HOME, WORK }

data class SavedPlaces(
    val home: SavedPlace? = null,
    val work: SavedPlace? = null,
    /** Places searched for in the trip planner, newest first. */
    val recents: List<SavedPlace> = emptyList(),
) {
    operator fun get(kind: SavedKind): SavedPlace? = if (kind == SavedKind.HOME) home else work
}

/** Home, work and recent trip-planner places, kept on the phone only. */
class SavedPlacesRepository @Inject constructor(private val store: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }

    val places: Flow<SavedPlaces> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            SavedPlaces(
                home = prefs[HOME]?.let(::decodeOne),
                work = prefs[WORK]?.let(::decodeOne),
                recents = prefs[RECENTS]?.let { runCatching { json.decodeFromString(LIST, it) }.getOrNull() }.orEmpty(),
            )
        }
        .distinctUntilChanged()

    suspend fun set(kind: SavedKind, place: SavedPlace?) = store.edit { prefs ->
        val key = if (kind == SavedKind.HOME) HOME else WORK
        if (place == null) prefs.remove(key) else prefs[key] = json.encodeToString(SavedPlace.serializer(), place)
    }

    /** Puts a place first in the recents; the same place (within 50 m) appears once. */
    suspend fun addRecent(place: SavedPlace) = store.edit { prefs ->
        val current = prefs[RECENTS]?.let { runCatching { json.decodeFromString(LIST, it) }.getOrNull() }.orEmpty()
        val updated = (listOf(place) + current.filterNot { distanceMeters(it.location, place.location) < SAME_PLACE_METERS })
            .take(MAX_RECENTS)
        prefs[RECENTS] = json.encodeToString(LIST, updated)
    }

    suspend fun clearRecents() = store.edit { it.remove(RECENTS) }

    private fun decodeOne(text: String): SavedPlace? = runCatching { json.decodeFromString(SavedPlace.serializer(), text) }.getOrNull()

    private companion object {
        val HOME = stringPreferencesKey("place_home")
        val WORK = stringPreferencesKey("place_work")
        val RECENTS = stringPreferencesKey("place_recents")
        val LIST = ListSerializer(SavedPlace.serializer())
        const val MAX_RECENTS = 8
        const val SAME_PLACE_METERS = 50.0
    }
}
