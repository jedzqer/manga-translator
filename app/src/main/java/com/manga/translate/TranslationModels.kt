package com.manga.translate

import android.graphics.RectF

enum class BubbleSource(val jsonValue: String) {
    BUBBLE_DETECTOR("bubble_detector"),
    TEXT_DETECTOR("text_detector"),
    MANUAL("manual"),
    UNKNOWN("unknown");

    companion object {
        fun fromJson(value: String?): BubbleSource {
            return entries.firstOrNull { it.jsonValue.equals(value, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

data class BubbleTranslation(
    val id: Int,
    val rect: RectF,
    val text: String,
    val source: BubbleSource = BubbleSource.UNKNOWN
)

data class TranslationResult(
    val imageName: String,
    val width: Int,
    val height: Int,
    val bubbles: List<BubbleTranslation>
)
