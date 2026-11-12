package com.likkho.app.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class PlainSettings(
    val darkMode: Boolean = false,
    val fullScreen: Boolean = false,
    val micFormat: String = "wav",
    val micSampleRate: Int = 44100,
    val micBitRate: Int = 128000
)

/**
 * Manages non-secret user settings per STORAGE_SPEC Section 7:
 * - "Settings jo secret nahi (dark mode, mic settings, fullscreen): DataStore plain."
 */
class PlainSettingsRepository(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        val KEY_DARK_MODE = booleanPreferencesKey("dark_mode")
        val KEY_FULL_SCREEN = booleanPreferencesKey("full_screen")
        val KEY_MIC_FORMAT = stringPreferencesKey("mic_format")
        val KEY_MIC_SAMPLE_RATE = intPreferencesKey("mic_sample_rate")
        val KEY_MIC_BIT_RATE = intPreferencesKey("mic_bit_rate")
    }

    val settingsFlow: Flow<PlainSettings> = dataStore.data.map { prefs ->
        PlainSettings(
            darkMode = prefs[KEY_DARK_MODE] ?: false,
            fullScreen = prefs[KEY_FULL_SCREEN] ?: false,
            micFormat = prefs[KEY_MIC_FORMAT] ?: "wav",
            micSampleRate = prefs[KEY_MIC_SAMPLE_RATE] ?: 44100,
            micBitRate = prefs[KEY_MIC_BIT_RATE] ?: 128000
        )
    }

    suspend fun getSettings(): PlainSettings = settingsFlow.first()

    suspend fun setDarkMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_DARK_MODE] = enabled
        }
    }

    suspend fun setFullScreen(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_FULL_SCREEN] = enabled
        }
    }

    suspend fun setMicSettings(format: String, sampleRate: Int, bitRate: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_MIC_FORMAT] = format
            prefs[KEY_MIC_SAMPLE_RATE] = sampleRate
            prefs[KEY_MIC_BIT_RATE] = bitRate
        }
    }
}
