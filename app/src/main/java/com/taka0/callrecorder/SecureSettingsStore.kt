package com.taka0.callrecorder

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureSettingsStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "call_recorder_secure_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var openAiApiKey: String
        get() = prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI_API_KEY, value).apply()

    var gitHubToken: String
        get() = prefs.getString(KEY_GITHUB_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITHUB_TOKEN, value).apply()

    var gitHubRepo: String
        get() = prefs.getString(KEY_GITHUB_REPO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITHUB_REPO, value).apply()

    var gitHubBranch: String
        get() = prefs.getString(KEY_GITHUB_BRANCH, "main") ?: "main"
        set(value) = prefs.edit().putString(KEY_GITHUB_BRANCH, value).apply()

    var gitHubFolder: String
        get() = prefs.getString(KEY_GITHUB_FOLDER, "diary") ?: "diary"
        set(value) = prefs.edit().putString(KEY_GITHUB_FOLDER, value).apply()

    fun isConfigured(): Boolean = gitHubToken.isNotBlank() && gitHubRepo.isNotBlank()

    companion object {
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_GITHUB_REPO = "github_repo"
        private const val KEY_GITHUB_BRANCH = "github_branch"
        private const val KEY_GITHUB_FOLDER = "github_folder"
    }
}
