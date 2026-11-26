package com.example.autoreplyassistant.network

import com.example.autoreplyassistant.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ReplyGenerator(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun generateReply(
        settings: AppSettings,
        sender: String,
        incomingMessage: String,
        recentHistory: List<String>
    ): String = withContext(Dispatchers.IO) {
        require(settings.apiKey.isNotBlank()) { "API key is not configured." }
        require(settings.apiBaseUrl.startsWith("https://") || settings.apiBaseUrl.startsWith("http://")) {
            "API Base URL must be a valid HTTP or HTTPS URL."
        }
        require(settings.modelName.isNotBlank()) { "Model name is empty." }

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put(
            "content",
            settings.systemPrompt.ifBlank { "Write a helpful, concise reply to the latest message." }
        ))

        recentHistory.takeLast(5).forEach { line ->
            val role = if (line.startsWith("ME: ")) "assistant" else "user"
            val content = line.removePrefix("ME: ").removePrefix("THEM: ")
            messages.put(JSONObject().put("role", role).put("content", content))
        }

        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", "Message from $sender:\n$incomingMessage\n\nWrite only the reply message.")
        )

        val payload = JSONObject()
            .put("model", settings.modelName)
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", 300)

        val request = Request.Builder()
            .url(settings.apiBaseUrl.trim())
            .header("Authorization", "Bearer ${settings.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = responseText.take(400).ifBlank { response.message }
                throw IOException("AI endpoint returned HTTP ${response.code}: $detail")
            }
            val root = JSONObject(responseText)
            val content = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.opt("content")
            val result = when (content) {
                is String -> content
                is JSONArray -> buildString {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i)
                        if (part != null) append(part.optString("text"))
                    }
                }
                else -> ""
            }.trim()

            if (result.isBlank()) throw IOException("AI endpoint returned an empty reply.")
            result
        }
    }
}
