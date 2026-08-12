package com.taka0.callrecorder

// 秘密情報の入力欄は保存済みの実値を表示しないため、
// 空欄・空白のみの保存は「変更しない」を意味し、既存値を保持する。
fun resolveSecretInput(input: String, existing: String): String {
    val trimmed = input.trim()
    return if (trimmed.isEmpty()) existing else trimmed
}
