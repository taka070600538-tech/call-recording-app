package com.taka0.callrecorder

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GitHubClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GitHubClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GitHubClient(apiBaseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `returns null when file does not exist yet`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = client.getExistingTextFile("me/repo", "main", "diary/2026-08-05.md", "token123")
        assertNull(result)
    }

    @Test
    fun `returns sha and decoded content when file exists`() {
        val body = JSONObject()
            .put("sha", "abc123")
            .put("content", "5pel44Gr")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))

        val result = client.getExistingTextFile("me/repo", "main", "diary/2026-08-05.md", "token123")

        assertEquals("abc123", result!!.first)
        assertEquals("日に", result.second)
    }

    @Test
    fun `putTextFile sends PUT request with authorization header`() {
        server.enqueue(MockResponse().setResponseCode(200))

        client.putTextFile("me/repo", "main", "diary/2026-08-05.md", "本文", "msg", null, "token123")

        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("Bearer token123", recorded.getHeader("Authorization"))
    }

    @Test(expected = GitHubClient.GitHubException::class)
    fun `putTextFile throws when response is not successful`() {
        server.enqueue(MockResponse().setResponseCode(422))
        client.putTextFile("me/repo", "main", "diary/2026-08-05.md", "本文", "msg", null, "token123")
    }

    @Test
    fun `putBinaryFile sends base64 encoded bytes as PUT body`() {
        server.enqueue(MockResponse().setResponseCode(201))

        client.putBinaryFile("me/repo", "main", "diary/audio/2026-08-05-1430.m4a", byteArrayOf(1, 2, 3), "msg", null, "token123")

        val recorded = server.takeRequest()
        val sentBody = JSONObject(recorded.body.readUtf8())
        assertEquals("AQID", sentBody.getString("content"))
    }
}
