package com.claude.webtoontranslator.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "wt_settings")

class SettingsDataStore(private val context: Context) {

    companion object {
        private val KEY_OPACITY = floatPreferencesKey("overlay_opacity")
        private val KEY_FONT_SCALE = floatPreferencesKey("overlay_font_scale")
        private val KEY_ALWAYS_ON_TOP = booleanPreferencesKey("always_on_top")
        private val KEY_AUTO_HIDE_SECONDS = intPreferencesKey("auto_hide_seconds")
        private val KEY_MODELS_DOWNLOADED = booleanPreferencesKey("models_downloaded")
        private val KEY_TRANSLATION_MODE = stringPreferencesKey("translation_mode")
        private val KEY_PERFORMANCE_MODE = stringPreferencesKey("performance_mode")
        private val KEY_ONLINE_TARGET_LANGUAGE = stringPreferencesKey("online_target_language")
        private val KEY_ONLINE_PROVIDER = stringPreferencesKey("online_provider")
        private val KEY_GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")

        // Scan area settings
        private val KEY_SCAN_MODE = stringPreferencesKey("scan_mode")
        private val KEY_AREA_LEFT = intPreferencesKey("area_left")
        private val KEY_AREA_TOP = intPreferencesKey("area_top")
        private val KEY_AREA_RIGHT = intPreferencesKey("area_right")
        private val KEY_AREA_BOTTOM = intPreferencesKey("area_bottom")
        private val KEY_HAS_SAVED_AREA = booleanPreferencesKey("has_saved_area")
    }

    val opacity: Flow<Float> =
        context.dataStore.data.map { it[KEY_OPACITY] ?: 0.92f }

    val fontScale: Flow<Float> =
        context.dataStore.data.map { it[KEY_FONT_SCALE] ?: 1.0f }

    val alwaysOnTop: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_ALWAYS_ON_TOP] ?: true }

    val autoHideSeconds: Flow<Int> =
        context.dataStore.data.map { it[KEY_AUTO_HIDE_SECONDS] ?: 5 }

    val modelsDownloaded: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_MODELS_DOWNLOADED] ?: false }

    /**
     * "offline" or "online"
     */
    val translationMode: Flow<String> =
        context.dataStore.data.map { it[KEY_TRANSLATION_MODE] ?: "offline" }

    /** fast, balanced, or quality */
    val performanceMode: Flow<String> =
        context.dataStore.data.map { it[KEY_PERFORMANCE_MODE] ?: "balanced" }

    /**
     * Online target language.
     * Examples: en, tl, fr, es
     */
    val onlineTargetLanguage: Flow<String> =
        context.dataStore.data.map { it[KEY_ONLINE_TARGET_LANGUAGE] ?: "en" }

    /** "mymemory" or "gemini" */
    val onlineProvider: Flow<String> =
        context.dataStore.data.map { it[KEY_ONLINE_PROVIDER] ?: "mymemory" }

    val geminiApiKey: Flow<String> =
        context.dataStore.data.map { SecureApiKeyStore.decrypt(it[KEY_GEMINI_API_KEY] ?: "") }

    /**
     * Scan mode:
     * whole_screen
     * select_area
     * last_selected_area
     */
    val scanMode: Flow<String> =
        context.dataStore.data.map { it[KEY_SCAN_MODE] ?: "whole_screen" }

    val areaLeft: Flow<Int> =
        context.dataStore.data.map { it[KEY_AREA_LEFT] ?: 0 }

    val areaTop: Flow<Int> =
        context.dataStore.data.map { it[KEY_AREA_TOP] ?: 0 }

    val areaRight: Flow<Int> =
        context.dataStore.data.map { it[KEY_AREA_RIGHT] ?: 0 }

    val areaBottom: Flow<Int> =
        context.dataStore.data.map { it[KEY_AREA_BOTTOM] ?: 0 }

    val hasSavedArea: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_HAS_SAVED_AREA] ?: false }

    suspend fun setOpacity(value: Float) {
        context.dataStore.edit {
            it[KEY_OPACITY] = value.coerceIn(0f, 1f)
        }
    }

    suspend fun setFontScale(value: Float) {
        context.dataStore.edit {
            it[KEY_FONT_SCALE] = value.coerceIn(0.5f, 3.0f)
        }
    }

    suspend fun setAlwaysOnTop(value: Boolean) {
        context.dataStore.edit {
            it[KEY_ALWAYS_ON_TOP] = value
        }
    }

    suspend fun setAutoHideSeconds(value: Int) {
        context.dataStore.edit {
            it[KEY_AUTO_HIDE_SECONDS] = value.coerceAtLeast(0)
        }
    }

    suspend fun setModelsDownloaded(value: Boolean) {
        context.dataStore.edit {
            it[KEY_MODELS_DOWNLOADED] = value
        }
    }

    suspend fun setTranslationMode(value: String) {
        context.dataStore.edit {
            it[KEY_TRANSLATION_MODE] = value
        }
    }

    suspend fun setPerformanceMode(value: String) {
        context.dataStore.edit {
            it[KEY_PERFORMANCE_MODE] = value.lowercase().let {
                if (it in setOf("fast", "balanced", "quality")) it else "balanced"
            }
        }
    }

     suspend fun setOnlineTargetLanguage(value: String) {
        context.dataStore.edit {
            it[KEY_ONLINE_TARGET_LANGUAGE] = value
        }
    }

    suspend fun setOnlineProvider(value: String) {
        context.dataStore.edit {
            it[KEY_ONLINE_PROVIDER] = value
        }
    }

    suspend fun migrateGeminiApiKeyIfNeeded() {
        context.dataStore.edit { preferences ->
            val stored = preferences[KEY_GEMINI_API_KEY] ?: return@edit
            if (stored.isNotBlank() && !stored.startsWith("v1:")) {
                val encrypted = SecureApiKeyStore.encrypt(stored)
                if (encrypted.isNotBlank()) {
                    preferences[KEY_GEMINI_API_KEY] = encrypted
                }
            }
        }
    }

    suspend fun setGeminiApiKey(value: String) {
        context.dataStore.edit {
            val trimmed = value.trim()
            if (trimmed.isBlank()) {
                it.remove(KEY_GEMINI_API_KEY)
            } else {
                val encrypted = SecureApiKeyStore.encrypt(trimmed)
                if (encrypted.isBlank()) {
                    it.remove(KEY_GEMINI_API_KEY)
                } else {
                    it[KEY_GEMINI_API_KEY] = encrypted
                }
            }
        }
    }

    suspend fun setScanMode(value: String) {
        context.dataStore.edit {
            it[KEY_SCAN_MODE] = value
        }
    }

    suspend fun saveScanArea(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        context.dataStore.edit {
            it[KEY_AREA_LEFT] = left
            it[KEY_AREA_TOP] = top
            it[KEY_AREA_RIGHT] = right
            it[KEY_AREA_BOTTOM] = bottom
            it[KEY_HAS_SAVED_AREA] = true
            it[KEY_SCAN_MODE] = "last_selected_area"
        }
    }

    suspend fun clearSavedScanArea() {
        context.dataStore.edit {
            it[KEY_AREA_LEFT] = 0
            it[KEY_AREA_TOP] = 0
            it[KEY_AREA_RIGHT] = 0
            it[KEY_AREA_BOTTOM] = 0
            it[KEY_HAS_SAVED_AREA] = false
        }
    }
}
