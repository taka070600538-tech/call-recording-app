package com.taka0.callrecorder

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

data class Recording(val file: File, val recordedAt: LocalDateTime)

class RecordingRepository(private val recordingsDir: File) {

    fun list(): List<Recording> {
        val files = recordingsDir.listFiles { f -> f.extension == "m4a" } ?: emptyArray()
        return files
            .mapNotNull { f -> parseRecordedAt(f.nameWithoutExtension)?.let { Recording(f, it) } }
            .sortedByDescending { it.recordedAt }
    }

    fun delete(recording: Recording): Boolean = recording.file.delete()

    private fun parseRecordedAt(nameWithoutExtension: String): LocalDateTime? {
        return try {
            // Same formatter that produced the name, so the two can never drift apart.
            LocalDateTime.parse(nameWithoutExtension, FileNaming.FORMATTER)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
