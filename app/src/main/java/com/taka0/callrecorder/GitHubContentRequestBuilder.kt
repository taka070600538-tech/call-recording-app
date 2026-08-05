package com.taka0.callrecorder

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

object GitHubContentRequestBuilder {

    fun encodePath(path: String): String {
        return path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
    }

    fun textPutBody(message: String, content: String, branch: String, sha: String?): String {
        return putBody(message, Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)), branch, sha)
    }

    fun binaryPutBody(message: String, contentBytes: ByteArray, branch: String, sha: String?): String {
        return putBody(message, Base64.getEncoder().encodeToString(contentBytes), branch, sha)
    }

    private fun putBody(message: String, base64Content: String, branch: String, sha: String?): String {
        val json = JSONObject()
        json.put("message", message)
        json.put("content", base64Content)
        json.put("branch", branch)
        if (sha != null) {
            json.put("sha", sha)
        }
        return json.toString()
    }
}
