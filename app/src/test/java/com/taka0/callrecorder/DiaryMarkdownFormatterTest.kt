package com.taka0.callrecorder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class DiaryMarkdownFormatterTest {

    @Test
    fun `builds diary file path from folder and date`() {
        val path = DiaryMarkdownFormatter.diaryFilePath("diary", LocalDate.of(2026, 8, 5))
        assertEquals("diary/2026-08-05.md", path)
    }

    @Test
    fun `builds audio file path under audio subfolder`() {
        val path = DiaryMarkdownFormatter.audioFilePath("diary", "2026-08-05-1430.m4a")
        assertEquals("diary/audio/2026-08-05-1430.m4a", path)
    }

    @Test
    fun `entry block includes time heading, phone number, text and audio link`() {
        val block = DiaryMarkdownFormatter.entryBlock(
            LocalTime.of(14, 30), "08088004673", "テスト通話の内容", "audio/2026-08-05-1430.m4a"
        )
        assertEquals("## 14:30 — 08088004673\n\nテスト通話の内容\n\n[音声を再生](audio/2026-08-05-1430.m4a)\n", block)
    }

    @Test
    fun `entry block without audio link`() {
        val block = DiaryMarkdownFormatter.entryBlock(LocalTime.of(9, 5), "08088004673", "メモ", null)
        assertEquals("## 09:05 — 08088004673\n\nメモ\n", block)
    }

    @Test
    fun `entry block shows unknown placeholder when phone number is null`() {
        val block = DiaryMarkdownFormatter.entryBlock(LocalTime.of(9, 5), null, "メモ", null)
        assertEquals("## 09:05 — 不明\n\nメモ\n", block)
    }

    @Test
    fun `new file content adds frontmatter before entry`() {
        val content = DiaryMarkdownFormatter.newFileContent(LocalDate.of(2026, 8, 5), "## 14:30\n\nメモ\n")
        assertEquals("---\ndate: 2026-08-05\n---\n\n## 14:30\n\nメモ\n", content)
    }

    @Test
    fun `appended content trims trailing whitespace and adds blank line separator`() {
        val result = DiaryMarkdownFormatter.appendedContent("---\ndate: 2026-08-05\n---\n\n## 09:00\n\n朝の内容\n\n", "## 14:30\n\n午後の内容\n")
        assertEquals("---\ndate: 2026-08-05\n---\n\n## 09:00\n\n朝の内容\n\n## 14:30\n\n午後の内容\n", result)
    }

    @Test
    fun `time heading prefix formats HHmm with markdown heading`() {
        val prefix = DiaryMarkdownFormatter.timeHeadingPrefix(LocalTime.of(9, 5))
        assertEquals("## 09:05", prefix)
    }

    @Test
    fun `patch replaces the unknown heading for the matching time with the phone number`() {
        val content = "---\ndate: 2026-08-06\n---\n\n## 09:05 — 不明\n\nメモ\n"

        val patched = DiaryMarkdownFormatter.patchUnknownPhoneNumber(content, LocalTime.of(9, 5), "08088004673")

        assertEquals("---\ndate: 2026-08-06\n---\n\n## 09:05 — 08088004673\n\nメモ\n", patched)
    }

    @Test
    fun `patch returns null when no matching unknown heading exists`() {
        val content = "---\ndate: 2026-08-06\n---\n\n## 09:05 — 08099998888\n\nメモ\n"

        val patched = DiaryMarkdownFormatter.patchUnknownPhoneNumber(content, LocalTime.of(9, 5), "08088004673")

        assertEquals(null, patched)
    }

    @Test
    fun `patch only replaces the entry for the matching time, leaving other unknown entries untouched`() {
        val content = "---\ndate: 2026-08-06\n---\n\n## 09:05 — 不明\n\nメモA\n\n## 10:00 — 不明\n\nメモB\n"

        val patched = DiaryMarkdownFormatter.patchUnknownPhoneNumber(content, LocalTime.of(9, 5), "08088004673")

        assertEquals("---\ndate: 2026-08-06\n---\n\n## 09:05 — 08088004673\n\nメモA\n\n## 10:00 — 不明\n\nメモB\n", patched)
    }
}
