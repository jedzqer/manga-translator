package com.manga.translate

import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OcrStore {
    fun load(imageFile: File): PageOcrResult? {
        val jsonFile = ocrFileFor(imageFile)
        if (!jsonFile.exists()) return null
        return try {
            val json = JSONObject(jsonFile.readText())
            val bubblesJson = json.optJSONArray("bubbles") ?: JSONArray()
            val bubbles = ArrayList<OcrBubble>(bubblesJson.length())
            for (i in 0 until bubblesJson.length()) {
                val item = bubblesJson.optJSONObject(i) ?: continue
                val id = if (item.has("id")) item.optInt("id") else i
                val rect = RectF(
                    item.optDouble("left").toFloat(),
                    item.optDouble("top").toFloat(),
                    item.optDouble("right").toFloat(),
                    item.optDouble("bottom").toFloat()
                )
                val text = item.optString("text", "")
                bubbles.add(OcrBubble(id, rect, text))
            }
            PageOcrResult(
                imageFile = imageFile,
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                bubbles = bubbles
            )
        } catch (e: Exception) {
            AppLogger.log("OcrStore", "Failed to load ${jsonFile.name}", e)
            null
        }
    }

    fun save(imageFile: File, result: PageOcrResult): File {
        val jsonFile = ocrFileFor(imageFile)
        val json = JSONObject()
            .put("image", result.imageFile.name)
            .put("width", result.width)
            .put("height", result.height)
        val bubbles = JSONArray()
        for (bubble in result.bubbles) {
            val item = JSONObject()
                .put("id", bubble.id)
                .put("left", bubble.rect.left)
                .put("top", bubble.rect.top)
                .put("right", bubble.rect.right)
                .put("bottom", bubble.rect.bottom)
                .put("text", bubble.text)
            bubbles.put(item)
        }
        json.put("bubbles", bubbles)
        jsonFile.writeText(json.toString())
        return jsonFile
    }

    fun ocrFileFor(imageFile: File): File {
        val name = imageFile.nameWithoutExtension + ".ocr.json"
        return File(imageFile.parentFile, name)
    }
}
