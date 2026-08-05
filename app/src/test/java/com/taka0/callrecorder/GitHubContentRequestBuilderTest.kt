package com.taka0.callrecorder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubContentRequestBuilderTest {

    @Test
    fun `encodes each path segment but keeps slashes`() {
        val encoded = GitHubContentRequestBuilder.encodePath("diary/2026-08-05.md")
        assertEquals("diary/2026-08-05.md", encoded)
    }

    @Test
    fun `encodes special characters within a segment`() {
        val encoded = GitHubContentRequestBuilder.encodePath("diary/日記 2026.md")
        assertFalse(encoded.contains(" "))
    }

    @Test
    fun `text put body without sha omits sha field`() {
        val body = JSONObject(GitHubContentRequestBuilder.textPutBody("msg", "hello", "main", null))
        assertEquals("msg", body.getString("message"))
        assertEquals("main", body.getString("branch"))
        assertEquals("aGVsbG8=", body.getString("content"))
        assertFalse(body.has("sha"))
    }

    @Test
    fun `text put body with sha includes sha field`() {
        val body = JSONObject(GitHubContentRequestBuilder.textPutBody("msg", "hello", "main", "abc123"))
        assertEquals("abc123", body.getString("sha"))
    }

    @Test
    fun `binary put body base64 encodes raw bytes`() {
        val body = JSONObject(GitHubContentRequestBuilder.binaryPutBody("msg", byteArrayOf(1, 2, 3), "main", null))
        assertEquals("AQID", body.getString("content"))
    }
}
