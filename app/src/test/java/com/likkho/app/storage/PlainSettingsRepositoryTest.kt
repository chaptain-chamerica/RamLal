package com.likkho.app.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlainSettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var settingsRepository: PlainSettingsRepository

    @Before
    fun setUp() {
        val testFile = tempFolder.newFile("test_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { testFile }
        )
        settingsRepository = PlainSettingsRepository(dataStore)
    }

    @Test
    fun defaultSettings_returnsCorrectDefaults() = runTest(testDispatcher) {
        val settings = settingsRepository.getSettings()
        assertFalse(settings.darkMode)
        assertFalse(settings.fullScreen)
        assertEquals("wav", settings.micFormat)
        assertEquals(44100, settings.micSampleRate)
        assertEquals(128000, settings.micBitRate)
    }

    @Test
    fun updatesAndReadsSettings_successfully() = runTest(testDispatcher) {
        settingsRepository.setDarkMode(true)
        settingsRepository.setFullScreen(true)
        settingsRepository.setMicSettings(format = "aac", sampleRate = 48000, bitRate = 192000)

        val updated = settingsRepository.getSettings()
        assertTrue(updated.darkMode)
        assertTrue(updated.fullScreen)
        assertEquals("aac", updated.micFormat)
        assertEquals(48000, updated.micSampleRate)
        assertEquals(192000, updated.micBitRate)
    }
}
