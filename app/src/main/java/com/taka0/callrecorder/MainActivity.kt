package com.taka0.callrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
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

        adapter = RecordingsAdapter(
            recordings = repository.list(),
            onPlay = ::playRecording,
            onTranscribe = ::openTranscribe,
            onDelete = ::deleteRecording
        )

        findViewById<RecyclerView>(R.id.recordings_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<android.widget.Button>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        adapter.updateRecordings(repository.list())
        // Also refresh here so the warning disappears when the user grants the permissions from
        // system settings and comes back to the app.
        updatePermissionWarning()
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
                adapter.updateRecordings(repository.list())
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
