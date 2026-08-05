package com.taka0.callrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDateTime

class FileNamingTest {
    @Test
    fun `formats date and time into a m4a filename`() {
        val dateTime = LocalDateTime.of(2026, 8, 5, 14, 30, 12)
        assertEquals("2026-08-05-143012.m4a", FileNaming.recordingFileName(dateTime))
    }

    @Test
    fun `pads single digit month day hour minute second`() {
        val dateTime = LocalDateTime.of(2026, 1, 2, 3, 4, 5)
        assertEquals("2026-01-02-030405.m4a", FileNaming.recordingFileName(dateTime))
    }

    @Test
    fun `two calls in the same minute get distinct filenames`() {
        val first = FileNaming.recordingFileName(LocalDateTime.of(2026, 8, 5, 14, 30, 10))
        val second = FileNaming.recordingFileName(LocalDateTime.of(2026, 8, 5, 14, 30, 55))

        assertNotEquals(first, second)
    }
}
