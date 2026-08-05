package com.taka0.callrecorder

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

class WhisperClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
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
}
