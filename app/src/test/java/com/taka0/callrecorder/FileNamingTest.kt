package com.taka0.callrecorder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class FileNamingTest {
    @Test
    fun `formats date and time into a m4a filename`() {
        val dateTime = LocalDateTime.of(2026, 8, 5, 14, 30)
        assertEquals("2026-08-05-1430.m4a", FileNaming.recordingFileName(dateTime))
    }

    @Test
    fun `pads single digit month day hour minute`() {
        val dateTime = LocalDateTime.of(2026, 1, 2, 3, 4)
        assertEquals("2026-01-02-0304.m4a", FileNaming.recordingFileName(dateTime))
    }
}
