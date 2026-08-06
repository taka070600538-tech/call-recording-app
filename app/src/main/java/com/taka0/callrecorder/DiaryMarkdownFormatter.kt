package com.taka0.callrecorder

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object DiaryMarkdownFormatter {
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun diaryFilePath(folder: String, date: LocalDate): String {
        return "$folder/${date.format(DATE_FORMATTER)}.md"
    }

    fun audioFilePath(folder: String, fileName: String): String {
        return "$folder/audio/$fileName"
    }

    fun entryBlock(time: LocalTime, phoneNumber: String?, text: String, audioRelativePath: String?): String {
        val heading = "## ${time.format(TIME_FORMATTER)} — ${phoneNumber ?: "不明"}\n\n"
        return if (audioRelativePath != null) {
            "$heading$text\n\n[音声を再生]($audioRelativePath)\n"
        } else {
            "$heading$text\n"
        }
    }

    fun newFileContent(date: LocalDate, entry: String): String {
        return "---\ndate: ${date.format(DATE_FORMATTER)}\n---\n\n$entry"
    }

    fun appendedContent(existingContent: String, entry: String): String {
        return existingContent.trimEnd() + "\n\n" + entry
    }
}
