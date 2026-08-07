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
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.time.LocalDateTime

class RecordingService : Service() {

    private lateinit var audioRecorder: AudioRecorder
    private var isRecording = false
    private lateinit var callLogLookup: CallLogLookup
    private var currentFile: File? = null
    private var recordingStartEpochMillis: Long = 0L
    private var backgroundExecutor: (Runnable) -> Unit = { Thread(it).start() }
    private var secureSettingsStoreOverride: SecureSettingsStore? = null
    private var gitHubClientOverride: GitHubClient? = null

    override fun onCreate() {
        super.onCreate()
        audioRecorder = MediaRecorderAudioRecorder(applicationContext)
        callLogLookup = SystemCallLogLookup(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CallStateReceiver always launches this service with startForegroundService(), so the
        // system requires a matching startForeground() within a few seconds on EVERY start —
        // including the ACTION_STOP path of a missed/rejected call and the low-storage path.
        // Failing to promote the service there triggers a ForegroundServiceDidNotStartInTime crash.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: SecurityException) {
            // Android 14+ rejects a "microphone" type foreground service when RECORD_AUDIO
            // is not granted. Stop cleanly instead of crashing.
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        // During call waiting the phone state goes OFFHOOK -> RINGING -> OFFHOOK, which delivers a
        // second ACTION_START. Ignore it so the in-flight recording is not abandoned mid-file.
        if (isRecording) return

        val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }

        if (StatFs(dir.path).availableBytes < MIN_FREE_BYTES_TO_RECORD) {
            notifyLowStorageAndStop()
            return
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = true

        // A background-started foreground service with no visible surface appears to receive
        // silent microphone audio during an active call on this device; an active overlay avoids
        // that. See RecordingOverlay's doc comment for details.
        RecordingOverlay.show(this)

        val file = File(dir, FileNaming.recordingFileName(LocalDateTime.now()))
        currentFile = file
        recordingStartEpochMillis = System.currentTimeMillis()
        try {
            audioRecorder.start(file)
            isRecording = true
        } catch (e: Exception) {
            // MediaRecorder may have already created a zero-length/corrupt file; remove it so it
            // does not show up in the recordings list.
            file.delete()
            RecordingOverlay.hide(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        // Stray STOP (missed/rejected call, or a duplicate IDLE broadcast): nothing was ever
        // started on this instance, so just tear the foreground state down again.
        if (!isRecording) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        isRecording = false
        try {
            audioRecorder.stop()
        } catch (e: Exception) {
            // MediaRecorder.stop() throws for very short recordings; the recorder is released anyway
        }
        savePhoneNumberForCurrentFile()
        RecordingOverlay.hide(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun savePhoneNumberForCurrentFile() {
        val file = currentFile ?: return
        val appContext = applicationContext
        val lookup = callLogLookup
        // Captured here (rather than read from inside the Runnable) for the same lifecycle-safety
        // reason as appContext/file/lookup above: the Runnable must not touch the Service's own
        // fields once it may be running after the Service instance has been torn down.
        val settingsOverrideForPatch = secureSettingsStoreOverride
        val gitHubClientOverrideForPatch = gitHubClientOverride
        // 録音開始より前のCallLog行は別の通話のものである可能性があるため下限を設ける。
        // 着信は応答時刻(CALL_STATE_OFFHOOK)で録音が始まるが、CallLogのdate列には呼び出し開始
        // 時刻が入るため、呼び出し継続時間を見込んだ余裕を持たせる。
        // 上限は主にクロックスキュー対策であり、録音開始より後に始まった別の通話の行を除外する
        // ことを主眼にしているが、録音開始直後（CALL_LOG_CLOCK_SKEW_MARGIN_MS以内）にキャッチホン
        // 等で別の着信が鳴った場合、その行までは排除しきれない（CallLog.Calls.DATEは着信の場合
        // RINGING開始時刻で書かれるため）。
        val afterEpochMillis = recordingStartEpochMillis - CALL_LOG_RING_MARGIN_MS
        val beforeEpochMillis = recordingStartEpochMillis + CALL_LOG_CLOCK_SKEW_MARGIN_MS
        backgroundExecutor(Runnable {
            // This Runnable may run well after the Service instance is destroyed (stopSelf() runs
            // right after this method returns), so it must never touch the Service's own fields —
            // only the locally-captured vals above (appContext, file, lookup, afterEpochMillis,
            // beforeEpochMillis) are safe to use here. It also runs on a bare Thread with no
            // Android component supervising it, so any uncaught exception here would crash the
            // whole app process; everything in this body must therefore be swallowed and never
            // allowed to escape the Runnable.
            try {
                var number = lookup.mostRecentNumber(afterEpochMillis, beforeEpochMillis)
                var attempts = 0
                // CallLogへの書き込みは実機で数秒〜15秒程度かかることがある。LIMIT句をsortOrderに
                // 含めるとプロバイダによっては例外を投げ再試行が常に失敗する不具合が過去にあった
                // （修正済み、SystemCallLogLookup.kt参照）。
                // メインスレッドをブロックしないよう、この再試行は必ずバックグラウンドスレッドで実行する。
                while (number == null && attempts < MAX_CALL_LOG_RETRIES) {
                    Thread.sleep(CALL_LOG_RETRY_DELAY_MS)
                    number = lookup.mostRecentNumber(afterEpochMillis, beforeEpochMillis)
                    attempts++
                }
                if (number != null) {
                    Log.i("RecordingService", "phone number resolved for ${file.name} after $attempts retries")
                } else {
                    Log.i("RecordingService", "phone number unresolved for ${file.name} after $attempts retries")
                }
                CallMetadataStore(appContext).save(file.name, number)
                if (number != null) {
                    patchDiaryIfAlreadySaved(appContext, file, number, settingsOverrideForPatch, gitHubClientOverrideForPatch)
                }
            } catch (e: Exception) {
                Log.w("RecordingService", "phone number resolution failed", e)
            }
        })
    }

    /**
     * すでにGitHubへ保存済みの録音であれば、当時「不明」だった見出しを解決した番号で上書きする。
     * GitHub未設定・該当する「不明」見出しが見つからない場合は何もしない。呼び出し元のtry/catchで
     * ラップされているため、ここでは例外処理を行わない。
     *
     * ローカルの[SavedRecordingsStore]には依存しない：TranscribeActivityが番号解決前に日記を
     * 「不明」でPUTしてから[SavedRecordingsStore.markSaved]を呼ぶまでの間にこの関数が走ると、
     * まだmarkSaved()されていないため誤って何もしなくなってしまうレースがあった。GitHub上に該当
     * する「不明」見出しが実際に存在するかどうかで判定すれば十分であり、それ自体が正しさの根拠になる。
     */
    private fun patchDiaryIfAlreadySaved(
        appContext: Context,
        file: File,
        phoneNumber: String,
        settingsOverride: SecureSettingsStore?,
        gitHubClientOverride: GitHubClient?
    ) {
        val settings = settingsOverride ?: SecureSettingsStore(appContext)
        if (!settings.isConfigured()) return

        val recordedAt = LocalDateTime.parse(file.nameWithoutExtension, FileNaming.FORMATTER)
        val client = gitHubClientOverride ?: GitHubClient()
        val diaryPath = DiaryMarkdownFormatter.diaryFilePath(settings.gitHubFolder, recordedAt.toLocalDate())
        val existing = client.getExistingTextFile(settings.gitHubRepo, settings.gitHubBranch, diaryPath, settings.gitHubToken)
            ?: return
        val patched = DiaryMarkdownFormatter.patchUnknownPhoneNumber(existing.second, recordedAt.toLocalTime(), phoneNumber)
            ?: return

        client.putTextFile(
            repo = settings.gitHubRepo,
            branch = settings.gitHubBranch,
            path = diaryPath,
            content = patched,
            message = "diary: ${recordedAt.toLocalDate()} ${recordedAt.toLocalTime()} 電話番号を追記",
            sha = existing.first,
            token = settings.gitHubToken
        )
    }

    private fun notifyLowStorageAndStop() {
        // Drop the ongoing "recording" notification first so the warning below is not removed
        // together with the foreground state when the service stops.
        stopForeground(STOP_FOREGROUND_REMOVE)

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
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(LOW_STORAGE_NOTIFICATION_ID, notification)
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

    fun setSecureSettingsStoreForTest(store: SecureSettingsStore) {
        secureSettingsStoreOverride = store
    }

    fun setGitHubClientForTest(client: GitHubClient) {
        gitHubClientOverride = client
    }

    fun setCallLogLookupForTest(lookup: CallLogLookup) {
        callLogLookup = lookup
    }

    fun setBackgroundExecutorForTest(executor: (Runnable) -> Unit) {
        backgroundExecutor = executor
    }

    fun getCurrentFileNameForTest(): String? = currentFile?.name

    companion object {
        const val ACTION_START = "com.taka0.callrecorder.action.START"
        const val ACTION_STOP = "com.taka0.callrecorder.action.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val LOW_STORAGE_NOTIFICATION_ID = 1002
        private const val MIN_FREE_BYTES_TO_RECORD = 50L * 1024 * 1024 // 50MB
        private const val MAX_CALL_LOG_RETRIES = 60
        private const val CALL_LOG_RETRY_DELAY_MS = 2000L
        private const val CALL_LOG_RING_MARGIN_MS = 90_000L
        private const val CALL_LOG_CLOCK_SKEW_MARGIN_MS = 10_000L
    }
}
