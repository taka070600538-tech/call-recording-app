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
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"message\":\"").append(message).append("\",")
        sb.append("\"content\":\"").append(base64Content).append("\",")
        sb.append("\"branch\":\"").append(branch).append("\"")
        if (sha != null) {
            sb.append(",\"sha\":\"").append(sha).append("\"")
        }
        sb.append("}")
        return sb.toString()
    }
}
