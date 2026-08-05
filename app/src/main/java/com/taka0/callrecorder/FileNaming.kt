package com.taka0.callrecorder

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object FileNaming {
    private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

    fun recordingFileName(dateTime: LocalDateTime): String {
        return "${dateTime.format(FORMATTER)}.m4a"
    }
}
