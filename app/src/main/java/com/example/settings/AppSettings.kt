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
    val KEY_AUDIO_ENABLED = booleanPreferencesKey("audio_enabled")
    val KEY_CAMERA_LENS = stringPreferencesKey("camera_lens")
    val KEY_DUAL_FORMAT_ENABLED = booleanPreferencesKey("dual_format_enabled")
    val KEY_TARGET_FPS = intPreferencesKey("target_fps")
    val KEY_ASPECT_RATIO = stringPreferencesKey("aspect_ratio")

    fun getAspectRatio(context: Context): Flow<String> {
        return context.dataStore.data.map { preferences ->
            preferences[KEY_ASPECT_RATIO] ?: "16:9"
        }
    }

    suspend fun setAspectRatio(context: Context, ratio: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ASPECT_RATIO] = ratio
        }
    }

    fun getVideoQuality(context: Context): Flow<String> {
        return context.dataStore.data.map { preferences ->
            preferences[KEY_VIDEO_QUALITY] ?: "HIGH"
        }
    }

    suspend fun setVideoQuality(context: Context, quality: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_VIDEO_QUALITY] = quality
        }
    }

    fun isAudioEnabled(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[KEY_AUDIO_ENABLED] ?: true
        }
    }

    suspend fun setAudioEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUDIO_ENABLED] = enabled
        }
    }

    fun getCameraLens(context: Context): Flow<String> {
        return context.dataStore.data.map { preferences ->
            preferences[KEY_CAMERA_LENS] ?: "BACK"
        }
    }

    suspend fun setCameraLens(context: Context, lens: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CAMERA_LENS] = lens
        }
    }

    fun isDualFormatEnabled(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[KEY_DUAL_FORMAT_ENABLED] ?: true
        }
    }

    suspend fun setDualFormatEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DUAL_FORMAT_ENABLED] = enabled
        }
    }

    fun getTargetFps(context: Context): Flow<Int> {
        return context.dataStore.data.map { preferences ->
            preferences[KEY_TARGET_FPS] ?: 30
        }
    }

    suspend fun setTargetFps(context: Context, fps: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_TARGET_FPS] = fps
        }
    }
}
