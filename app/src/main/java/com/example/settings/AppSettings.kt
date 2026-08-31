package com.example.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object AppSettings {
    val KEY_VIDEO_QUALITY = stringPreferencesKey("video_quality")
    val KEY_CAMERA_LENS = intPreferencesKey("camera_lens")
    val KEY_AUDIO_ENABLED = booleanPreferencesKey("audio_enabled")
    val KEY_FLASH_MODE = intPreferencesKey("flash_mode")

    fun getVideoQuality(context: Context): Flow<String> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_VIDEO_QUALITY] ?: "HIGH" // ULTRA, HIGH, MEDIUM, LOW
        }
    }

    suspend fun setVideoQuality(context: Context, quality: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VIDEO_QUALITY] = quality
        }
    }

    fun getCameraLens(context: Context): Flow<Int> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_CAMERA_LENS] ?: androidx.camera.core.CameraSelector.LENS_FACING_BACK
        }
    }

    suspend fun setCameraLens(context: Context, lens: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CAMERA_LENS] = lens
        }
    }

    fun isAudioEnabled(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_AUDIO_ENABLED] ?: true
        }
    }

    suspend fun setAudioEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUDIO_ENABLED] = enabled
        }
    }
}
