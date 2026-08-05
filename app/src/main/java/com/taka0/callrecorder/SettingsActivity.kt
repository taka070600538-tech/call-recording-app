package com.taka0.callrecorder

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var store: SecureSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = SecureSettingsStore(applicationContext)

        val openAiInput = findViewById<EditText>(R.id.openai_key_input).apply { setText(store.openAiApiKey) }
        val tokenInput = findViewById<EditText>(R.id.github_token_input).apply { setText(store.gitHubToken) }
        val repoInput = findViewById<EditText>(R.id.github_repo_input).apply { setText(store.gitHubRepo) }
        val branchInput = findViewById<EditText>(R.id.github_branch_input).apply { setText(store.gitHubBranch) }
        val folderInput = findViewById<EditText>(R.id.github_folder_input).apply { setText(store.gitHubFolder) }

        findViewById<Button>(R.id.save_settings_button).setOnClickListener {
            store.openAiApiKey = openAiInput.text.toString().trim()
            store.gitHubToken = tokenInput.text.toString().trim()
            store.gitHubRepo = repoInput.text.toString().trim()
            store.gitHubBranch = branchInput.text.toString().trim().ifBlank { "main" }
            store.gitHubFolder = folderInput.text.toString().trim().ifBlank { "diary" }
            Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
