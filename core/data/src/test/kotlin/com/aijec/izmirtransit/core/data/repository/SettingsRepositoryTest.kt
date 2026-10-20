package com.aijec.izmirtransit.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aijec.izmirtransit.core.model.AccentTheme
import com.aijec.izmirtransit.core.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRepositoryTest {
    private val dir: File = Files.createTempDirectory("settings").toFile().apply { deleteOnExit() }

    @Test
    fun `defaults, updates, and unknown stored values fall back to defaults`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "s.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(AppSettings(), repository.settings.first())

        repository.setThemeMode(ThemeMode.DARK)
        repository.setAccent(AccentTheme.TEAL)
        repository.setSearchAtBottom(true)
        assertEquals(AppSettings(ThemeMode.DARK, AccentTheme.TEAL, searchAtBottom = true), repository.settings.first())

        // A value written by a future version this one doesn't know.
        store.edit { it[stringPreferencesKey("accent_theme")] = "NEON" }
        assertEquals(AccentTheme.OCEAN, repository.settings.first().accent)
    }
}
