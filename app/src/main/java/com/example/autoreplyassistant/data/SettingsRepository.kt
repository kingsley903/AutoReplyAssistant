package com.example.autoreplyassistant.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "assistant_settings")

data class AppSettings(
    val autoReplyEnabled: Boolean = false,
    val autoPickCallsEnabled: Boolean = false,
    val apiBaseUrl: String = "https://openrouter.ai/api/v1/chat/completions",
    val apiKey: String = "",
    val modelName: String = "google/gemini-2.5-flash",
    val systemPrompt: String = "You are a helpful assistant. Write concise, natural replies. Do not claim to be the account owner. If a message needs personal knowledge, ask a brief clarification instead of inventing facts.",
    val replyDelayMs: Long = 2500L,
    val ignoredSendersCsv: String = ""
)

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    private object Keys {
        val autoReplyEnabled = booleanPreferencesKey("auto_reply_enabled")
        val autoPickCallsEnabled = booleanPreferencesKey("auto_pick_calls_enabled")
        val apiBaseUrl = stringPreferencesKey("api_base_url")
        val apiKey = stringPreferencesKey("api_key")
        val modelName = stringPreferencesKey("model_name")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val replyDelayMs = longPreferencesKey("reply_delay_ms")
        val ignoredSendersCsv = stringPreferencesKey("ignored_senders_csv")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            autoReplyEnabled = p[Keys.autoReplyEnabled] ?: false,
            autoPickCallsEnabled = p[Keys.autoPickCallsEnabled] ?: false,
            apiBaseUrl = p[Keys.apiBaseUrl] ?: AppSettings().apiBaseUrl,
            apiKey = p[Keys.apiKey] ?: "",
            modelName = p[Keys.modelName] ?: AppSettings().modelName,
            systemPrompt = p[Keys.systemPrompt] ?: AppSettings().systemPrompt,
            replyDelayMs = p[Keys.replyDelayMs] ?: 2500L,
            ignoredSendersCsv = p[Keys.ignoredSendersCsv] ?: ""
        )
    }

    suspend fun save(settings: AppSettings) {
        dataStore.edit { p ->
            p[Keys.autoReplyEnabled] = settings.autoReplyEnabled
            p[Keys.autoPickCallsEnabled] = settings.autoPickCallsEnabled
            p[Keys.apiBaseUrl] = settings.apiBaseUrl.trim()
            p[Keys.apiKey] = settings.apiKey.trim()
            p[Keys.modelName] = settings.modelName.trim()
            p[Keys.systemPrompt] = settings.systemPrompt
            p[Keys.replyDelayMs] = settings.replyDelayMs.coerceIn(0L, 60_000L)
            p[Keys.ignoredSendersCsv] = settings.ignoredSendersCsv
        }
    }

    suspend fun updateAutoReply(enabled: Boolean) {
        dataStore.edit { it[Keys.autoReplyEnabled] = enabled }
    }

    suspend fun updateAutoPickCalls(enabled: Boolean) {
        dataStore.edit { it[Keys.autoPickCallsEnabled] = enabled }
    }
}
