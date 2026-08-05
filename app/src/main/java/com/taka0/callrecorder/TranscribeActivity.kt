package com.taka0.callrecorder

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class TranscribeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    companion object {
        const val EXTRA_RECORDING_PATH = "recording_path"
    }
}
