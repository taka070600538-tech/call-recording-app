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

    fun timeHeadingPrefix(time: LocalTime): String = "## ${time.format(TIME_FORMATTER)}"

    /**
     * 指定した時刻の「不明」見出しを電話番号に置き換える。該当する見出しが本文中に無ければnullを返す。
     */
    fun patchUnknownPhoneNumber(content: String, time: LocalTime, phoneNumber: String): String? {
        val unknownHeading = "${timeHeadingPrefix(time)} — 不明"
        if (!content.contains(unknownHeading)) return null
        val resolvedHeading = "${timeHeadingPrefix(time)} — $phoneNumber"
        return content.replaceFirst(unknownHeading, resolvedHeading)
    }

    fun entryBlock(time: LocalTime, phoneNumber: String?, text: String, audioRelativePath: String?): String {
        val heading = "${timeHeadingPrefix(time)} — ${phoneNumber ?: "不明"}\n\n"
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
