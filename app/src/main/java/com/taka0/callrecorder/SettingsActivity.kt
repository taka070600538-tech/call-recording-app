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
        WindowInsetsUtil.applySystemBarPadding(findViewById(R.id.settings_root))
        store = SecureSettingsStore(applicationContext)

        // 秘密情報は実値を表示しない。設定済みならヒントだけ変える(空欄保存=既存値保持)。
        val openAiInput = findViewById<EditText>(R.id.openai_key_input).apply {
            if (store.openAiApiKey.isNotBlank()) hint = "OpenAI APIキー：設定済み（変更時のみ入力）"
        }
        val tokenInput = findViewById<EditText>(R.id.github_token_input).apply {
            if (store.gitHubToken.isNotBlank()) hint = "トークン：設定済み（変更時のみ入力）"
        }
        val repoInput = findViewById<EditText>(R.id.github_repo_input).apply { setText(store.gitHubRepo) }
        val branchInput = findViewById<EditText>(R.id.github_branch_input).apply { setText(store.gitHubBranch) }
        val folderInput = findViewById<EditText>(R.id.github_folder_input).apply { setText(store.gitHubFolder) }

        findViewById<Button>(R.id.save_settings_button).setOnClickListener {
            store.openAiApiKey = resolveSecretInput(openAiInput.text.toString(), store.openAiApiKey)
            store.gitHubToken = resolveSecretInput(tokenInput.text.toString(), store.gitHubToken)
            store.gitHubRepo = repoInput.text.toString().trim()
            store.gitHubBranch = branchInput.text.toString().trim().ifBlank { "main" }
            store.gitHubFolder = folderInput.text.toString().trim().ifBlank { "diary" }
            Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
