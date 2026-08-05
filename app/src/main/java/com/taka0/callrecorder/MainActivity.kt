package com.taka0.callrecorder

import android.Manifest
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var repository: RecordingRepository
    private lateinit var adapter: RecordingsAdapter

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
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
        )
    }

    private fun deleteRecording(recording: Recording) {
        repository.delete(recording)
        adapter.updateRecordings(repository.list())
    }
}
