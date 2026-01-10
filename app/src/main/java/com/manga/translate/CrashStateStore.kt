package com.manga.translate

import android.content.Context

class CrashStateStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun wasCrashedLastRun(): Boolean {
        return prefs.getBoolean(KEY_CRASHED, false)
    }

    fun markCrashed() {
        prefs.edit()
            .putBoolean(KEY_CRASHED, true)
            .apply()
    }

    fun clearCrashFlag() {
        prefs.edit()
            .putBoolean(KEY_CRASHED, false)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "crash_state"
        private const val KEY_CRASHED = "crashed_last_run"
    }
}
