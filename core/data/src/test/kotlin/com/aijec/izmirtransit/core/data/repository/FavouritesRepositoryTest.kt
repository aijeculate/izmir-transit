package com.aijec.izmirtransit.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
    fun `moves favourites up and down, clamped at the ends`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "c.preferences_pb") }
        val repository = FavouritesRepository(store)
        listOf("3", "2", "1").forEach { repository.setStop(it, true) } // stored as 1, 2, 3
        repository.setLine("M1", true)

        repository.moveStop("3", -1)
        assertEquals(listOf("1", "3", "2"), repository.favourites.first().stopCodes)
        repository.moveStop("1", -1) // already first
        repository.moveStop("2", 5) // already last
        assertEquals(listOf("1", "3", "2"), repository.favourites.first().stopCodes)
        repository.moveStop("1", 2)
        assertEquals(listOf("3", "2", "1"), repository.favourites.first().stopCodes)
        repository.moveStop("missing", 1) // harmless
        repository.moveLine("M1", 1)
        assertEquals(listOf("M1"), repository.favourites.first().lineCodes)
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
