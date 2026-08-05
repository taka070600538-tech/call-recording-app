package com.taka0.callrecorder

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object FileNaming {
    /**
     * Seconds are part of the name so that two calls started within the same minute
     * (e.g. a dropped call and an immediate redial) cannot overwrite each other.
     * [RecordingRepository] parses filenames back with this same formatter.
     */
    val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

    fun recordingFileName(dateTime: LocalDateTime): String {
        return "${dateTime.format(FORMATTER)}.m4a"
    }
}
