package com.taka0.callrecorder

import okio.Buffer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WhisperRequestBuilderTest {

    @Test
    fun `multipart body contains model field and audio file part`() {
        val tempFile = File.createTempFile("recording", ".m4a").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }

        val body = WhisperRequestBuilder.buildTranscriptionBody(tempFile)
        val buffer = Buffer()
        body.writeTo(buffer)
        val serialized = buffer.readUtf8()

        assertTrue(serialized.contains("name=\"model\""))
        assertTrue(serialized.contains("whisper-1"))
        assertTrue(serialized.contains("name=\"file\""))
        assertTrue(serialized.contains(tempFile.name))
    }
}
