package com.taka0.callrecorder

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

class GitHubClient(
    // OkHttp's 10s default write timeout is not enough to PUT a multi-MB base64 audio body
    // over mobile data.
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val apiBaseUrl: String = "https://api.github.com"
) {
    class GitHubException(message: String) : Exception(message)

    fun getExistingTextFile(repo: String, branch: String, path: String, token: String): Pair<String, String>? {
        val url = "$apiBaseUrl/repos/$repo/contents/${GitHubContentRequestBuilder.encodePath(path)}?ref=$branch"
        httpClient.newCall(authorizedRequest(url, token).get().build()).execute().use { response ->
            return when (response.code) {
                200 -> {
                    val json = JSONObject(response.body!!.string())
                    val sha = json.getString("sha")
                    val decoded = String(Base64.getMimeDecoder().decode(json.getString("content")), Charsets.UTF_8)
                    sha to decoded
                }
                404 -> null
                401 -> throw GitHubException("トークンが無効です")
                else -> throw GitHubException("リポジトリの確認に失敗しました（${response.code}）")
            }
        }
    }

    /**
     * Looks up the current `sha` of a file at [path] without decoding its content as text.
     * Safe to use for binary files (unlike [getExistingTextFile], which UTF-8 decodes the content).
     * Returns null when the file does not exist yet.
     */
    fun getExistingSha(repo: String, branch: String, path: String, token: String): String? {
        val url = "$apiBaseUrl/repos/$repo/contents/${GitHubContentRequestBuilder.encodePath(path)}?ref=$branch"
        httpClient.newCall(authorizedRequest(url, token).get().build()).execute().use { response ->
            return when (response.code) {
                200 -> JSONObject(response.body!!.string()).getString("sha")
                404 -> null
                401 -> throw GitHubException("トークンが無効です")
                else -> throw GitHubException("リポジトリの確認に失敗しました（${response.code}）")
            }
        }
    }

    fun putTextFile(repo: String, branch: String, path: String, content: String, message: String, sha: String?, token: String) {
        val url = "$apiBaseUrl/repos/$repo/contents/${GitHubContentRequestBuilder.encodePath(path)}"
        putRequest(url, GitHubContentRequestBuilder.textPutBody(message, content, branch, sha), token)
    }

    fun putBinaryFile(repo: String, branch: String, path: String, contentBytes: ByteArray, message: String, sha: String?, token: String) {
        val url = "$apiBaseUrl/repos/$repo/contents/${GitHubContentRequestBuilder.encodePath(path)}"
        putRequest(url, GitHubContentRequestBuilder.binaryPutBody(message, contentBytes, branch, sha), token)
    }

    private fun authorizedRequest(url: String, token: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer $token")
    }

    private fun putRequest(url: String, jsonBody: String, token: String) {
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        val request = authorizedRequest(url, token).put(requestBody).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw GitHubException("保存に失敗しました（${response.code}）")
            }
        }
    }

    companion object {
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }
}
