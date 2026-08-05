package com.taka0.callrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RecordingRepositoryTest {

    @Test
    fun `lists m4a files sorted by recorded time descending`() {
        val dir = Files.createTempDirectory("recordings").toFile()
        dir.resolve("2026-08-05-0900.m4a").createNewFile()
        dir.resolve("2026-08-05-1430.m4a").createNewFile()
        dir.resolve("not-a-recording.txt").createNewFile()

        val recordings = RecordingRepository(dir).list()

        assertEquals(2, recordings.size)
        assertEquals("2026-08-05-1430.m4a", recordings[0].file.name)
        assertEquals("2026-08-05-0900.m4a", recordings[1].file.name)
    }

    @Test
    fun `ignores files whose name does not match the expected pattern`() {
        val dir = Files.createTempDirectory("recordings").toFile()
        dir.resolve("random.m4a").createNewFile()

        assertTrue(RecordingRepository(dir).list().isEmpty())
    }

    @Test
    fun `delete removes the file from disk`() {
        val dir = Files.createTempDirectory("recordings").toFile()
        val file = dir.resolve("2026-08-05-0900.m4a").apply { createNewFile() }
        val repository = RecordingRepository(dir)
        val recording = repository.list().first()

        val deleted = repository.delete(recording)

        assertTrue(deleted)
        assertFalse(file.exists())
    }
}
