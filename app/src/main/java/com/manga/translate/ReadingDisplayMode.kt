package com.manga.translate

import androidx.annotation.StringRes

enum class ReadingDisplayMode(
    val prefValue: String,
    @StringRes val labelRes: Int
) {
    FIT_WIDTH("fit_width", R.string.reading_display_fit_width),
    FIT_HEIGHT("fit_height", R.string.reading_display_fit_height);

    companion object {
        fun fromPref(value: String?): ReadingDisplayMode {
            return entries.firstOrNull { it.prefValue == value } ?: FIT_WIDTH
        }
    }
}
