package com.manga.translate

import android.content.Context
import androidx.core.content.edit

class CrashStateStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun wasCrashedLastRun(): Boolean {
        return prefs.getBoolean(KEY_CRASHED, false)
    }

    fun markCrashed() {
        prefs.edit() {
                putBoolean(KEY_CRASHED, true)
            }
    }

    fun clearCrashFlag() {
        prefs.edit() {
                putBoolean(KEY_CRASHED, false)
            }
    }

    companion object {
        private const val PREFS_NAME = "crash_state"
        private const val KEY_CRASHED = "crashed_last_run"
    }
}
