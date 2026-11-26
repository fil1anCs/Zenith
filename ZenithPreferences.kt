package com.zenith.player.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "zenith_settings")

class ZenithPreferences(private val context: Context) {
    val settings = context.dataStore.data.map { p ->
        PlayerSettings(
            theme = p[stringPreferencesKey("theme")] ?: "midnight",
            accent = p[stringPreferencesKey("accent")] ?: "silver",
            gapless = p[booleanPreferencesKey("gapless")] ?: true,
            normalize = p[booleanPreferencesKey("normalize")] ?: false,
            volume = p[floatPreferencesKey("volume")] ?: 0.8f,
            syncEnabled = p[booleanPreferencesKey("sync_enabled")] ?: true
        )
    }
    suspend fun setSyncEnabled(enabled: Boolean) { context.dataStore.edit { it[booleanPreferencesKey("sync_enabled")] = enabled } }
    suspend fun setVolume(value: Float) { context.dataStore.edit { it[floatPreferencesKey("volume")] = value.coerceIn(0f, 1f) } }
}

data class PlayerSettings(
    val theme: String,
    val accent: String,
    val gapless: Boolean,
    val normalize: Boolean,
    val volume: Float,
    val syncEnabled: Boolean
)
