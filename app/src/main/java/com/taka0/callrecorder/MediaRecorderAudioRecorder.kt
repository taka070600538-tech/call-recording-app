package com.taka0.callrecorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class MediaRecorderAudioRecorder(private val context: Context) : AudioRecorder {
    private var recorder: MediaRecorder? = null

    override fun start(outputFile: File) {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            // AudioSource.MIC is silenced by Android during an active call for this app,
            // even with an active accessibility service (confirmed empirically). VOICE_RECOGNITION
            // is subject to different audio-policy privacy rules and is being tried as the last
            // remaining hypothesis for why other call-recording apps can capture real audio here.
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // 16kHz mono @32kbps: Whisper's native input format, and small enough that a long
            // call stays well under Whisper's 25MB upload limit.
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(outputFile.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            // Never leave a configured-but-unstarted recorder holding the microphone.
            r.release()
            throw e
        }
        recorder = r
    }

    override fun stop() {
        // MediaRecorder.stop() throws RuntimeException("stop failed") for very short recordings.
        // release() must run regardless, otherwise the microphone stays held by this process.
        try {
            recorder?.stop()
        } finally {
            recorder?.release()
            recorder = null
        }
    }
}
