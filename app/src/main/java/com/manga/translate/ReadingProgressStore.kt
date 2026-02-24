package com.manga.translate

import android.content.Context
import androidx.core.content.edit
import java.io.File

class ReadingProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(folder: File): Int {
        return prefs.getInt(keyFor(folder), 0)
    }

    fun save(folder: File, index: Int) {
        prefs.edit() {
                putInt(keyFor(folder), index)
            }
    }

    private fun keyFor(folder: File): String {
        return folder.absolutePath
    }

    companion object {
        private const val PREFS_NAME = "reading_progress"
    }
}
