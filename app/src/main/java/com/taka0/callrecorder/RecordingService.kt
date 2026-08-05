package com.taka0.callrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import androidx.core.app.NotificationCompat
import java.io.File
import java.time.LocalDateTime

class RecordingService : Service() {

    private lateinit var audioRecorder: AudioRecorder

    override fun onCreate() {
        super.onCreate()
        audioRecorder = MediaRecorderAudioRecorder(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }

        if (StatFs(dir.path).availableBytes < MIN_FREE_BYTES_TO_RECORD) {
            notifyLowStorageAndStop()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = true

        val file = File(dir, FileNaming.recordingFileName(LocalDateTime.now()))
        try {
            audioRecorder.start(file)
        } catch (e: Exception) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        try {
            audioRecorder.stop()
        } catch (e: Exception) {
            // recorder was never successfully started; nothing to stop
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notifyLowStorageAndStop() {
        val channelId = "call_recording"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "通話録音", NotificationManager.IMPORTANCE_DEFAULT)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("空き容量が不足しています")
            .setContentText("通話を録音できませんでした")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val channelId = "call_recording"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "通話録音", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("通話を録音中")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .build()
    }

    fun setAudioRecorderForTest(recorder: AudioRecorder) {
        audioRecorder = recorder
    }

    companion object {
        const val ACTION_START = "com.taka0.callrecorder.action.START"
        const val ACTION_STOP = "com.taka0.callrecorder.action.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_FREE_BYTES_TO_RECORD = 50L * 1024 * 1024 // 50MB
    }
}
