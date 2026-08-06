package com.taka0.callrecorder

import android.content.Context
import android.content.SharedPreferences

class SavedRecordingsStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences("call_recorder_saved_recordings", Context.MODE_PRIVATE)
    )

    fun all(): Set<String> = prefs.getStringSet(KEY_SAVED_FILE_NAMES, emptySet()) ?: emptySet()

    fun markSaved(fileName: String) {
        val updated = all().toMutableSet().apply { add(fileName) }
        prefs.edit().putStringSet(KEY_SAVED_FILE_NAMES, updated).apply()
    }

    companion object {
        private const val KEY_SAVED_FILE_NAMES = "saved_file_names"
    }
}
