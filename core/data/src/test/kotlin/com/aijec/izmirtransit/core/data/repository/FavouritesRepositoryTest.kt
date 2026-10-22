package com.aijec.izmirtransit.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FavouritesRepositoryTest {
    private val dir: File = Files.createTempDirectory("favourites").toFile().apply { deleteOnExit() }

    @Test
    fun `starts empty, adds newest first, removes, and ignores duplicates`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "a.preferences_pb") }
        val repository = FavouritesRepository(store)
        assertTrue(repository.favourites.first().isEmpty)

        repository.setStop("10007", true)
        repository.setStop("10102", true)
        repository.setStop("10007", true) // re-adding moves it to the front, no duplicate
        repository.setLine("202", true)
        assertEquals(listOf("10007", "10102"), repository.favourites.first().stopCodes)
        assertEquals(listOf("202"), repository.favourites.first().lineCodes)

        repository.setStop("10102", false)
        repository.setLine("5", false) // removing a non-favourite is harmless
        val favourites = repository.favourites.first()
        assertEquals(listOf("10007"), favourites.stopCodes)
        assertTrue(favourites.hasLine("202"))
        assertTrue(!favourites.hasStop("10102"))
    }

    @Test
    fun `moves stops and lines in one list, clamped at the ends`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "c.preferences_pb") }
        val repository = FavouritesRepository(store)
        repository.setLine("M1", true)
        listOf("2", "1").forEach { repository.setStop(it, true) } // newest first: s1, s2, M1
        val m1 = FavouriteItem.LineItem("M1")
        val s1 = FavouriteItem.StopItem("1")
        val s2 = FavouriteItem.StopItem("2")
        assertEquals(listOf(s1, s2, m1), repository.favourites.first().items)

        repository.move(m1, -2) // a line above the stops
        assertEquals(listOf(m1, s1, s2), repository.favourites.first().items)
        repository.move(m1, -1) // already first
        repository.move(s2, 5) // already last
        assertEquals(listOf(m1, s1, s2), repository.favourites.first().items)
        repository.move(s1, 1)
        assertEquals(listOf(m1, s2, s1), repository.favourites.first().items)
        repository.move(FavouriteItem.StopItem("missing"), 1) // harmless
        assertEquals(listOf("2", "1"), repository.favourites.first().stopCodes)
    }

    @Test
    fun `reads the old separate lists as stops then lines`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "d.preferences_pb") }
        store.edit {
            it[stringPreferencesKey("favourite_stops")] = "10007,10102"
            it[stringPreferencesKey("favourite_lines")] = "202"
        }
        val repository = FavouritesRepository(store)
        assertEquals(
            listOf(FavouriteItem.StopItem("10007"), FavouriteItem.StopItem("10102"), FavouriteItem.LineItem("202")),
            repository.favourites.first().items,
        )
        repository.setLine("5", true)
        assertEquals(listOf("5", "202"), repository.favourites.first().lineCodes)
        assertEquals(listOf("10007", "10102"), repository.favourites.first().stopCodes)
    }

    @Test
    fun `favourites persist across store instances`() = runTest {
        val file = File(dir, "b.preferences_pb")
        // DataStore allows one active instance per file, so close the first before reopening.
        val firstJob = Job()
        val first = FavouritesRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(coroutineContext + firstJob)) { file })
        first.setLine("446", true)
        firstJob.cancelAndJoin()

        val second = FavouritesRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
        assertEquals(listOf("446"), second.favourites.first().lineCodes)
    }
}
