package com.taka0.callrecorder

import android.content.Context
import android.content.SharedPreferences

class CallMetadataStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences("call_recorder_call_metadata", Context.MODE_PRIVATE)
    )

    fun save(fileName: String, phoneNumber: String?) {
        prefs.edit().putString(fileName, phoneNumber).apply()
    }

    fun get(fileName: String): String? = prefs.getString(fileName, null)
}
