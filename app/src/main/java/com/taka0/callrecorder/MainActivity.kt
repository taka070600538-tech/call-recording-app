package com.taka0.callrecorder

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var repository: RecordingRepository
    private lateinit var adapter: RecordingsAdapter
    private lateinit var savedRecordingsStore: SavedRecordingsStore

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updatePermissionWarning()
            requestIgnoreBatteryOptimizations()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowInsetsUtil.applySystemBarPadding(findViewById(R.id.main_root))

        val recordingsDir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        repository = RecordingRepository(recordingsDir)
        savedRecordingsStore = SavedRecordingsStore(this)

        adapter = RecordingsAdapter(
            recordings = repository.list(),
            savedFileNames = savedRecordingsStore.all(),
            onSelectionChanged = ::updateActionButtons
        )

        findViewById<RecyclerView>(R.id.recordings_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<android.widget.Button>(R.id.action_play).setOnClickListener {
            adapter.getSelected()?.let(::playRecording)
        }
        findViewById<android.widget.Button>(R.id.action_transcribe).setOnClickListener {
            adapter.getSelected()?.let(::openTranscribe)
        }
        findViewById<android.widget.Button>(R.id.action_delete).setOnClickListener {
            adapter.getSelected()?.let(::deleteRecording)
        }

        findViewById<android.widget.Button>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<android.widget.Button>(R.id.open_accessibility_settings_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<android.widget.Button>(R.id.open_overlay_settings_button).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        adapter.updateRecordings(repository.list(), savedRecordingsStore.all())
        // Also refresh here so the warnings disappear when the user grants the permission/
        // enables the accessibility service from system settings and comes back to the app.
        updatePermissionWarning()
        updateAccessibilityWarning()
        updateOverlayWarning()
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasRecordingPermissions(): Boolean {
        return listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun updatePermissionWarning() {
        findViewById<TextView>(R.id.permission_warning)?.visibility =
            if (hasRecordingPermissions()) View.GONE else View.VISIBLE
    }

    /**
     * Android 12+ denies microphone access to a background-started foreground service unless the
     * app holds an active accessibility service; call detection and RecordingService start/stop
     * run from inside CallRecorderAccessibilityService for this reason. There is no programmatic
     * way to enable the service for the user; they must do it once from system Settings.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = getSystemService(AccessibilityManager::class.java) ?: return false
        return accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name == CallRecorderAccessibilityService::class.java.name
            }
    }

    private fun updateAccessibilityWarning() {
        findViewById<View>(R.id.accessibility_warning_row).visibility =
            if (isAccessibilityServiceEnabled()) View.GONE else View.VISIBLE
    }

    private fun updateOverlayWarning() {
        findViewById<View>(R.id.overlay_warning_row).visibility =
            if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
    }

    /**
     * Android 12+ restricts starting a microphone foreground service from a background broadcast
     * receiver. Being exempt from battery optimization gives the app the background-start
     * allowlist window it needs when a call comes in while the app is not in the foreground.
     */
    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // Some devices/ROMs do not expose this settings screen; recording still works when
            // the app happens to be allowed to start in the background.
        }
    }

    private fun updateActionButtons(recording: Recording?) {
        val enabled = recording != null
        findViewById<android.widget.Button>(R.id.action_play).isEnabled = enabled
        findViewById<android.widget.Button>(R.id.action_transcribe).isEnabled = enabled
        findViewById<android.widget.Button>(R.id.action_delete).isEnabled = enabled
    }

    private fun playRecording(recording: Recording) {
        val player = MediaPlayer()
        try {
            player.setDataSource(recording.file.absolutePath)
            player.prepare()
            player.setOnCompletionListener { it.release() }
            player.start()
        } catch (e: Exception) {
            player.release()
            android.widget.Toast.makeText(this, "再生できませんでした", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTranscribe(recording: Recording) {
        startActivity(
            Intent(this, TranscribeActivity::class.java)
                .putExtra(TranscribeActivity.EXTRA_RECORDING_PATH, recording.file.absolutePath)
                .putExtra(TranscribeActivity.EXTRA_RECORDED_AT, recording.recordedAt.toString())
        )
    }

    private fun deleteRecording(recording: Recording) {
        AlertDialog.Builder(this)
            .setTitle("削除しますか？")
            .setMessage("この録音を削除します。元に戻せません。")
            .setPositiveButton("削除") { _, _ ->
                repository.delete(recording)
                adapter.updateRecordings(repository.list(), savedRecordingsStore.all())
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
