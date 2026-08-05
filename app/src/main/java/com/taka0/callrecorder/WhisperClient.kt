package com.taka0.callrecorder

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class WhisperClient(
    // OkHttp's 10s default read/write timeouts are far too short here: uploading a call recording
    // over mobile data and waiting for Whisper to transcribe it takes tens of seconds or more.
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val apiBaseUrl: String = "https://api.openai.com/v1"
) {
    class WhisperException(message: String) : Exception(message)

    fun transcribe(audioFile: File, apiKey: String): String {
        val request = Request.Builder()
            .url("$apiBaseUrl/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(WhisperRequestBuilder.buildTranscriptionBody(audioFile))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyString = response.body!!.string()
            if (!response.isSuccessful) {
                throw WhisperException("文字起こしに失敗しました（${response.code}）")
            }
            return JSONObject(bodyString).getString("text")
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
