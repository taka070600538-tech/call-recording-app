package com.taka0.callrecorder

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class WhisperClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: WhisperClient
    private lateinit var audioFile: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WhisperClient(apiBaseUrl = server.url("/").toString().trimEnd('/'))
        audioFile = File.createTempFile("recording", ".m4a").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `returns transcribed text on success`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(JSONObject().put("text", "こんにちは").toString()))

        val text = client.transcribe(audioFile, "sk-test")

        assertEquals("こんにちは", text)
        assertEquals("Bearer sk-test", server.takeRequest().getHeader("Authorization"))
    }

    @Test(expected = WhisperClient.WhisperException::class)
    fun `throws when response is not successful`() {
        server.enqueue(MockResponse().setResponseCode(401))
        client.transcribe(audioFile, "sk-test")
    }
}
