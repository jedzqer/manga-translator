package com.manga.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LlmClient(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private val promptConfig: LlmPromptConfig by lazy { loadPromptConfig() }

    fun isConfigured(): Boolean {
        return settingsStore.load().isValid()
    }

    suspend fun translate(text: String, glossary: Map<String, String>): LlmTranslationResult? =
        withContext(Dispatchers.IO) {
        val settings = settingsStore.load()
        if (!settings.isValid()) return@withContext null
        val endpoint = buildEndpoint(settings.apiUrl)
        val payload = buildPayload(text, glossary, settings.modelName)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
        }
        return@withContext try {
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                AppLogger.log("LlmClient", "HTTP $code: $body")
                null
            } else {
                parseResponse(body)
            }
        } catch (e: Exception) {
            AppLogger.log("LlmClient", "Request failed", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun buildEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return when {
            trimmed.endsWith("/v1/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    private fun buildPayload(text: String, glossary: Map<String, String>, modelName: String): JSONObject {
        val config = promptConfig
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", config.systemPrompt)
        )
        for (message in config.exampleMessages) {
            messages.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.content)
            )
        }
        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", config.userPromptPrefix + buildUserPayload(text, glossary))
        )
        return JSONObject()
            .put("model", modelName)
            .put("temperature", 0.3)
            .put("messages", messages)
    }

    private fun parseResponse(body: String): LlmTranslationResult? {
        return try {
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices") ?: return null
            val first = choices.optJSONObject(0) ?: return null
            val message = first.optJSONObject("message") ?: return null
            val content = message.optString("content")?.trim()?.ifBlank { null } ?: return null
            parseTranslationContent(content)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTranslationContent(content: String): LlmTranslationResult? {
        return try {
            val json = JSONObject(content)
            val translation = json.optString("translation")?.trim().orEmpty()
            if (translation.isBlank()) {
                AppLogger.log("LlmClient", "Missing translation field in response")
                return null
            }
            val glossary = mutableMapOf<String, String>()
            val glossaryJson = json.optJSONObject("glossary_used")
            if (glossaryJson != null) {
                for (key in glossaryJson.keys()) {
                    val value = glossaryJson.optString(key).trim()
                    if (key.isNotBlank() && value.isNotBlank()) {
                        glossary[key] = value
                    }
                }
            }
            LlmTranslationResult(translation, glossary)
        } catch (e: Exception) {
            LlmTranslationResult(content, emptyMap())
        }
    }

    private fun loadPromptConfig(): LlmPromptConfig {
        val json = JSONObject(readAsset(PROMPT_CONFIG_ASSET))
        val systemPrompt = json.optString("system_prompt")
        val userPromptPrefix = json.optString("user_prompt_prefix")
        val examplesJson = json.optJSONArray("example_messages") ?: JSONArray()
        val examples = ArrayList<PromptMessage>(examplesJson.length())
        for (i in 0 until examplesJson.length()) {
            val messageObj = examplesJson.optJSONObject(i) ?: continue
            val role = messageObj.optString("role")
            val content = messageObj.optString("content")
            if (role.isNotBlank() && content.isNotBlank()) {
                examples.add(PromptMessage(role, content))
            }
        }
        return LlmPromptConfig(systemPrompt, userPromptPrefix, examples)
    }

    private fun readAsset(name: String): String {
        return appContext.assets.open(name).bufferedReader().use { it.readText() }
    }

    private fun buildUserPayload(text: String, glossary: Map<String, String>): String {
        val glossaryJson = JSONObject()
        for ((key, value) in glossary) {
            glossaryJson.put(key, value)
        }
        return JSONObject()
            .put("text", text)
            .put("glossary", glossaryJson)
            .toString()
    }

    companion object {
        private const val TIMEOUT_MS = 30_000
        private const val PROMPT_CONFIG_ASSET = "llm_prompts.json"
    }
}

data class LlmTranslationResult(
    val translation: String,
    val glossaryUsed: Map<String, String>
)

private data class LlmPromptConfig(
    val systemPrompt: String,
    val userPromptPrefix: String,
    val exampleMessages: List<PromptMessage>
)

private data class PromptMessage(
    val role: String,
    val content: String
)
