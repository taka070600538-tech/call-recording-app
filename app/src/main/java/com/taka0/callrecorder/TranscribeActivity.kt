package com.taka0.callrecorder

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException

class TranscribeActivity : AppCompatActivity() {

    private lateinit var recordingFile: File
    private lateinit var recordedAt: LocalDateTime
    private lateinit var store: SecureSettingsStore
    private val whisperClient = WhisperClient()
    private val gitHubClient = GitHubClient()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcribe)
        store = SecureSettingsStore(applicationContext)

        val path = intent.getStringExtra(EXTRA_RECORDING_PATH)
        if (path == null) {
            finish()
            return
        }
        recordingFile = File(path)
        recordedAt = parseRecordedAt(intent.getStringExtra(EXTRA_RECORDED_AT))

        transcribe()

        findViewById<Button>(R.id.save_to_github_button).setOnClickListener { saveToGitHub() }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun transcribe() {
        val statusText = findViewById<TextView>(R.id.status_text)
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    whisperClient.transcribe(recordingFile, store.openAiApiKey)
                }
                findViewById<EditText>(R.id.transcript_input).setText(text)
                findViewById<Button>(R.id.save_to_github_button).isEnabled = true
                statusText.text = "文字起こし結果を確認・編集してください"
            } catch (e: WhisperClient.WhisperException) {
                statusText.text = "文字起こしに失敗しました: ${e.message}"
            } catch (e: IOException) {
                statusText.text = "文字起こしに失敗しました: ${e.message}"
            }
        }
    }

    private fun saveToGitHub() {
        if (!store.isConfigured()) {
            Toast.makeText(this, "先に設定でGitHubトークンとリポジトリを入力してください", Toast.LENGTH_LONG).show()
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            return
        }

        val text = findViewById<EditText>(R.id.transcript_input).text.toString().trim()
        if (text.isEmpty()) return

        val statusText = findViewById<TextView>(R.id.status_text)
        statusText.text = "GitHubに保存しています…"

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Transcription is manual and can happen long after the call, so the diary
                    // entry must be filed under the recording's time, not the save time.
                    saveTranscriptAndAudio(recordedAt.toLocalDate(), recordedAt.toLocalTime(), text)
                }
                statusText.text = "保存しました"
                Toast.makeText(this@TranscribeActivity, "保存しました", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: GitHubClient.GitHubException) {
                statusText.text = "保存に失敗しました: ${e.message}"
            } catch (e: IOException) {
                statusText.text = "保存に失敗しました: ${e.message}"
            }
        }
    }

    private fun saveTranscriptAndAudio(date: LocalDate, time: LocalTime, text: String) {
        val repo = store.gitHubRepo
        val branch = store.gitHubBranch
        val folder = store.gitHubFolder
        val token = store.gitHubToken

        val audioPath = DiaryMarkdownFormatter.audioFilePath(folder, recordingFile.name)
        val existingAudioSha = gitHubClient.getExistingSha(repo, branch, audioPath, token)
        gitHubClient.putBinaryFile(
            repo = repo,
            branch = branch,
            path = audioPath,
            contentBytes = recordingFile.readBytes(),
            message = "audio: ${recordingFile.name}",
            sha = existingAudioSha,
            token = token
        )

        val entry = DiaryMarkdownFormatter.entryBlock(time, text, "audio/${recordingFile.name}")
        val diaryPath = DiaryMarkdownFormatter.diaryFilePath(folder, date)
        val existing = gitHubClient.getExistingTextFile(repo, branch, diaryPath, token)

        val newContent = if (existing != null) {
            DiaryMarkdownFormatter.appendedContent(existing.second, entry)
        } else {
            DiaryMarkdownFormatter.newFileContent(date, entry)
        }

        gitHubClient.putTextFile(
            repo = repo,
            branch = branch,
            path = diaryPath,
            content = newContent,
            message = "diary: $date ${time}",
            sha = existing?.first,
            token = token
        )
    }

    /** Falls back to "now" only if the caller somehow omitted the timestamp extra. */
    private fun parseRecordedAt(isoValue: String?): LocalDateTime {
        if (isoValue == null) return LocalDateTime.now()
        return try {
            LocalDateTime.parse(isoValue)
        } catch (e: DateTimeParseException) {
            LocalDateTime.now()
        }
    }

    companion object {
        const val EXTRA_RECORDING_PATH = "recording_path"
        const val EXTRA_RECORDED_AT = "recorded_at"
    }
}
