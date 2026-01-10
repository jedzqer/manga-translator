package com.manga.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String
)

object UpdateChecker {
    private const val UPDATE_URL =
        "https://raw.githubusercontent.com/jedzqer/manga-translator/main/update.json"
    private const val TIMEOUT_MS = 15_000

    suspend fun fetchUpdateInfo(): UpdateInfo? = withContext(Dispatchers.IO) {
        val connection = (URL(UPDATE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        return@withContext try {
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                AppLogger.log("UpdateChecker", "HTTP $code: $body")
                null
            } else {
                parseUpdateInfo(body)
            }
        } catch (e: Exception) {
            AppLogger.log("UpdateChecker", "Update request failed", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseUpdateInfo(body: String): UpdateInfo? {
        return try {
            val json = JSONObject(body)
            val versionCode = json.optInt("versionCode", -1)
            val versionName = json.optString("versionName").trim()
            val apkUrl = json.optString("apkUrl").trim()
            val changelog = json.optString("changelog").trim()
            if (versionName.isBlank() || apkUrl.isBlank()) {
                AppLogger.log("UpdateChecker", "Invalid update json: $body")
                null
            } else {
                UpdateInfo(versionCode, versionName, apkUrl, changelog)
            }
        } catch (e: Exception) {
            AppLogger.log("UpdateChecker", "Parse update json failed", e)
            null
        }
    }
}
