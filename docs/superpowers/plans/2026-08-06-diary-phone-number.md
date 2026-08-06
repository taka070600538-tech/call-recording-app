# 通話ダイアリーへの時刻・電話番号・相手名記録 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通話終了時に相手の電話番号を`CallLog`から取得し、文字起こし保存時に日記エントリの見出しへ時刻・電話番号を書き込み、PC側同期スクリプトでその見出しを復元しつつ、ローカルの電話帳ファイルと番号が一致する場合は相手の名前も追記する。

**Architecture:** `RecordingService`が通話終了（`CALL_STATE_IDLE`）のタイミングで`CallLog`を1回照会し、結果をファイル名キーで`CallMetadataStore`（SharedPreferences）に保存する。文字起こし→GitHub保存時（`TranscribeActivity`）にこのストアから番号を読み出し、`DiaryMarkdownFormatter.entryBlock`の見出し（`## HH:mm — 番号`）に書き込む。PC側の`pull-call-recordings.ps1`は、見出し行を削除する現在の処理を「`##`のみ除去して残す」処理に変更し、続けてローカルの`電話帳.md`と番号を照合して一致すれば名前を追記する。

**Tech Stack:** Kotlin, AndroidX, Robolectric（テスト）, 既存の`.\gradlew.bat`ビルド, PowerShell 5.1

## Global Constraints

- 見出しの表示形式は`## HH:mm — 090-1234-5678`。番号不明時は`## HH:mm — 不明`（電話帳マッチ時はPC側で`HH:mm — 090-1234-5678（名前）`に変換）
- 電話番号取得は`CallLog.Calls`の直近1件照会。着信・発信を区別しない
- 番号が取得できない場合、取得処理で例外が起きた場合は必ず`null`扱いとし、録音・文字起こし・GitHub保存のいずれも失敗させない
- `CallMetadataStore`はファイル名をキーにしたSharedPreferences（`SavedRecordingsStore`と同型）
- 電話帳ファイル（`日記\通話録音\電話帳.md`）はGitHub同期対象外のローカルファイルで、PC側スクリプトのみが読む
- 番号の正規化は「数字以外を除去→先頭が`81`かつ全体11〜12桁なら先頭を`0`に置換」を、電話帳側・診断対象側の両方に同じ規則で適用する
- Robolectricテストは`@Config(sdk = [34])`を付与する（既存テストと同じ設定）
- テストは`.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.<クラス名>"`で実行する（既存タスクと同じコマンド形式）

---

### Task 1: `CallMetadataStore`（電話番号のファイル名キー保存）

**Files:**
- Create: `app/src/main/java/com/taka0/callrecorder/CallMetadataStore.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/CallMetadataStoreTest.kt`

**Interfaces:**
- Consumes: なし（`android.content.Context` / `android.content.SharedPreferences`のみ）
- Produces: `CallMetadataStore(context: Context)`、`CallMetadataStore(prefs: SharedPreferences)`、`fun save(fileName: String, phoneNumber: String?)`、`fun get(fileName: String): String?`。Task 2（`RecordingService`）とTask 4（`TranscribeActivity`）が利用する。

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/com/taka0/callrecorder/CallMetadataStoreTest.kt`:

```kotlin
package com.taka0.callrecorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallMetadataStoreTest {

    private fun newStore(): CallMetadataStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_call_metadata_${System.nanoTime()}", Context.MODE_PRIVATE)
        return CallMetadataStore(prefs)
    }

    @Test
    fun `get returns null when nothing was saved`() {
        val store = newStore()
        assertNull(store.get("2026-08-06-135135.m4a"))
    }

    @Test
    fun `save then get returns the phone number`() {
        val store = newStore()

        store.save("2026-08-06-135135.m4a", "08088004673")

        assertEquals("08088004673", store.get("2026-08-06-135135.m4a"))
    }

    @Test
    fun `save with a null phone number can be read back as null`() {
        val store = newStore()

        store.save("2026-08-06-135135.m4a", null)

        assertNull(store.get("2026-08-06-135135.m4a"))
    }

    @Test
    fun `persists across instances backed by the same preferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_call_metadata_shared", Context.MODE_PRIVATE)
        CallMetadataStore(prefs).save("2026-08-06-135135.m4a", "08088004673")

        val reloaded = CallMetadataStore(prefs)

        assertEquals("08088004673", reloaded.get("2026-08-06-135135.m4a"))
    }
}
```

- [ ] **Step 2: テストを実行して失敗を確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.CallMetadataStoreTest"`
Expected: `CallMetadataStore`クラスが存在せずコンパイルエラーになる

- [ ] **Step 3: 実装する**

`app/src/main/java/com/taka0/callrecorder/CallMetadataStore.kt`:

```kotlin
package com.taka0.callrecorder

import android.content.Context
import android.content.SharedPreferences

class CallMetadataStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences("call_recorder_call_metadata", Context.MODE_PRIVATE)
    )

    fun save(fileName: String, phoneNumber: String?) {
        prefs.edit().putString(fileName, phoneNumber).apply()
    }

    fun get(fileName: String): String? = prefs.getString(fileName, null)
}
```

- [ ] **Step 4: テストを実行して成功を確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.CallMetadataStoreTest"`
Expected: `PASS`（4件すべて）

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/CallMetadataStore.kt app/src/test/java/com/taka0/callrecorder/CallMetadataStoreTest.kt
git commit -m "feat: 録音ファイル名に紐づく電話番号を記録するCallMetadataStoreを追加"
```

---

### Task 2: 通話終了時の電話番号取得（`CallLogLookup`・`RecordingService`・権限）

**Files:**
- Create: `app/src/main/java/com/taka0/callrecorder/CallLogLookup.kt`
- Create: `app/src/main/java/com/taka0/callrecorder/SystemCallLogLookup.kt`
- Modify: `app/src/main/java/com/taka0/callrecorder/RecordingService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/taka0/callrecorder/MainActivity.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/RecordingServiceTest.kt`

**Interfaces:**
- Consumes: Task 1の`CallMetadataStore(context: Context)` / `fun save(fileName: String, phoneNumber: String?)`
- Produces: `CallLogLookup`インターフェース（`fun mostRecentNumber(): String?`）。Task 6以降では利用しない（Android側で完結する）。

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/com/taka0/callrecorder/RecordingServiceTest.kt`の`FakeAudioRecorder`クラスの下に、次の`FakeCallLogLookup`を追加する。

```kotlin
class FakeCallLogLookup(private val numbers: MutableList<String?>) : CallLogLookup {
    var callCount = 0

    override fun mostRecentNumber(): String? {
        callCount++
        return if (numbers.isNotEmpty()) numbers.removeAt(0) else null
    }
}
```

続けて、`RecordingServiceTest`クラス内に次のテストを追加する。

```kotlin
    @Test
    fun `ACTION_STOP saves the phone number from CallLogLookup to CallMetadataStore`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.setAudioRecorderForTest(FakeAudioRecorder())
        service.setCallLogLookupForTest(FakeCallLogLookup(mutableListOf("08088004673")))
        val startIntent = Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START)
        service.onStartCommand(startIntent, 0, 1)
        val fileName = service.getCurrentFileNameForTest()

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 2)

        val store = CallMetadataStore(ApplicationProvider.getApplicationContext())
        assertEquals("08088004673", store.get(fileName!!))
    }

    @Test
    fun `ACTION_STOP retries once and saves null when CallLogLookup returns null twice`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.setAudioRecorderForTest(FakeAudioRecorder())
        val lookup = FakeCallLogLookup(mutableListOf(null, null))
        service.setCallLogLookupForTest(lookup)
        val startIntent = Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START)
        service.onStartCommand(startIntent, 0, 1)
        val fileName = service.getCurrentFileNameForTest()

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 2)

        assertEquals(2, lookup.callCount)
        val store = CallMetadataStore(ApplicationProvider.getApplicationContext())
        assertNull(store.get(fileName!!))
    }

    @Test
    fun `a stray ACTION_STOP without a prior start does not touch CallMetadataStore`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.setAudioRecorderForTest(FakeAudioRecorder())
        service.setCallLogLookupForTest(FakeCallLogLookup(mutableListOf("08088004673")))

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 1)

        val store = CallMetadataStore(ApplicationProvider.getApplicationContext())
        assertNull(store.get("nonexistent.m4a"))
    }
```

`assertNull`は既存のimportにすでに含まれているため、追加のimportは不要。

- [ ] **Step 2: テストを実行して失敗を確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.RecordingServiceTest"`
Expected: `CallLogLookup`・`setCallLogLookupForTest`・`getCurrentFileNameForTest`が存在せずコンパイルエラーになる

- [ ] **Step 3: `CallLogLookup`インターフェースを実装する**

`app/src/main/java/com/taka0/callrecorder/CallLogLookup.kt`（新規）:

```kotlin
package com.taka0.callrecorder

interface CallLogLookup {
    /** 直近の発着信の電話番号。取得できない場合はnull。 */
    fun mostRecentNumber(): String?
}
```

`app/src/main/java/com/taka0/callrecorder/SystemCallLogLookup.kt`（新規）:

```kotlin
package com.taka0.callrecorder

import android.content.Context
import android.provider.CallLog

class SystemCallLogLookup(private val context: Context) : CallLogLookup {
    override fun mostRecentNumber(): String? {
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
```

- [ ] **Step 4: `RecordingService`を配線する**

`app/src/main/java/com/taka0/callrecorder/RecordingService.kt`の`private var isRecording = false`の下にフィールドを追加:

```kotlin
    private lateinit var callLogLookup: CallLogLookup
    private var currentFile: File? = null
```

`onCreate()`を次のように変更:

変更前:
```kotlin
    override fun onCreate() {
        super.onCreate()
        audioRecorder = MediaRecorderAudioRecorder(applicationContext)
    }
```

変更後:
```kotlin
    override fun onCreate() {
        super.onCreate()
        audioRecorder = MediaRecorderAudioRecorder(applicationContext)
        callLogLookup = SystemCallLogLookup(applicationContext)
    }
```

`startRecording()`内、`val file = File(dir, FileNaming.recordingFileName(LocalDateTime.now()))`の直後に追加:

```kotlin
        currentFile = file
```

`stopRecording()`を次のように変更:

変更前:
```kotlin
        isRecording = false
        try {
            audioRecorder.stop()
        } catch (e: Exception) {
            // MediaRecorder.stop() throws for very short recordings; the recorder is released anyway
        }
        RecordingOverlay.hide(this)
```

変更後:
```kotlin
        isRecording = false
        try {
            audioRecorder.stop()
        } catch (e: Exception) {
            // MediaRecorder.stop() throws for very short recordings; the recorder is released anyway
        }
        savePhoneNumberForCurrentFile()
        RecordingOverlay.hide(this)
```

`stopRecording()`関数の直後に新しい関数を追加:

```kotlin
    private fun savePhoneNumberForCurrentFile() {
        val file = currentFile ?: return
        val phoneNumber = try {
            callLogLookup.mostRecentNumber() ?: run {
                // CallLogへの書き込みとの競合を吸収するための短い再試行
                Thread.sleep(300)
                callLogLookup.mostRecentNumber()
            }
        } catch (e: Exception) {
            null
        }
        CallMetadataStore(applicationContext).save(file.name, phoneNumber)
    }
```

`setAudioRecorderForTest`関数の下にテスト用の差し替え口を追加:

```kotlin
    fun setCallLogLookupForTest(lookup: CallLogLookup) {
        callLogLookup = lookup
    }

    fun getCurrentFileNameForTest(): String? = currentFile?.name
```

- [ ] **Step 5: 権限を追加する**

`app/src/main/AndroidManifest.xml`の`READ_PHONE_STATE`の下に追加:

```xml
    <uses-permission android:name="android.permission.READ_CALL_LOG" />
```

`app/src/main/java/com/taka0/callrecorder/MainActivity.kt`の`requestRequiredPermissions()`内:

変更前:
```kotlin
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
```

変更後:
```kotlin
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
```

`hasRecordingPermissions()`内:

変更前:
```kotlin
        return listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
```

変更後:
```kotlin
        return listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
```

- [ ] **Step 6: テストを実行して成功を確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.RecordingServiceTest"`
Expected: `PASS`（既存6件＋新規3件の合計9件）

- [ ] **Step 7: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/CallLogLookup.kt app/src/main/java/com/taka0/callrecorder/SystemCallLogLookup.kt app/src/main/java/com/taka0/callrecorder/RecordingService.kt app/src/main/AndroidManifest.xml app/src/main/java/com/taka0/callrecorder/MainActivity.kt app/src/test/java/com/taka0/callrecorder/RecordingServiceTest.kt
git commit -m "feat: 通話終了時にCallLogから電話番号を取得してCallMetadataStoreへ記録"
```

---

### Task 3: `DiaryMarkdownFormatter`の見出しに電話番号を追加

**Files:**
- Modify: `app/src/main/java/com/taka0/callrecorder/DiaryMarkdownFormatter.kt`
- Modify: `app/src/test/java/com/taka0/callrecorder/DiaryMarkdownFormatterTest.kt`

**Interfaces:**
- Consumes: なし
- Produces: `fun entryBlock(time: LocalTime, phoneNumber: String?, text: String, audioRelativePath: String?): String`（Task 4が利用する。既存の`entryBlock(time, text, audioRelativePath)`は削除しシグネチャを変更する）

- [ ] **Step 1: 失敗するテストに書き換える**

`app/src/test/java/com/taka0/callrecorder/DiaryMarkdownFormatterTest.kt`の既存2テストを次のように置き換える。

変更前:
```kotlin
    @Test
    fun `entry block includes time heading text and audio link`() {
        val block = DiaryMarkdownFormatter.entryBlock(
            LocalTime.of(14, 30), "テスト通話の内容", "audio/2026-08-05-1430.m4a"
        )
        assertEquals("## 14:30\n\nテスト通話の内容\n\n[音声を再生](audio/2026-08-05-1430.m4a)\n", block)
    }

    @Test
    fun `entry block without audio link`() {
        val block = DiaryMarkdownFormatter.entryBlock(LocalTime.of(9, 5), "メモ", null)
        assertEquals("## 09:05\n\nメモ\n", block)
    }
```

変更後:
```kotlin
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
```

- [ ] **Step 2: テストを実行して失敗を確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.DiaryMarkdownFormatterTest"`
Expected: 引数の数が一致せずコンパイルエラー、または見出しの文言不一致でFAILする

- [ ] **Step 3: `entryBlock`を実装する**

`app/src/main/java/com/taka0/callrecorder/DiaryMarkdownFormatter.kt`の`entryBlock`関数を次のように置き換える。

変更前:
```kotlin
    fun entryBlock(time: LocalTime, text: String, audioRelativePath: String?): String {
        val heading = "## ${time.format(TIME_FORMATTER)}\n\n"
        return if (audioRelativePath != null) {
            "$heading$text\n\n[音声を再生]($audioRelativePath)\n"
        } else {
            "$heading$text\n"
        }
    }
```

変更後:
```kotlin
    fun entryBlock(time: LocalTime, phoneNumber: String?, text: String, audioRelativePath: String?): String {
        val heading = "## ${time.format(TIME_FORMATTER)} — ${phoneNumber ?: "不明"}\n\n"
        return if (audioRelativePath != null) {
            "$heading$text\n\n[音声を再生]($audioRelativePath)\n"
        } else {
            "$heading$text\n"
        }
    }
```

- [ ] **Step 4: テストを実行して成功を確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.DiaryMarkdownFormatterTest"`
Expected: `PASS`（既存3件＋新規3件の合計6件）

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/DiaryMarkdownFormatter.kt app/src/test/java/com/taka0/callrecorder/DiaryMarkdownFormatterTest.kt
git commit -m "feat: 日記エントリの見出しに電話番号（不明時はプレースホルダ）を追加"
```

---

### Task 4: `TranscribeActivity`で保存時にCallMetadataStoreから番号を渡す

**Files:**
- Modify: `app/src/main/java/com/taka0/callrecorder/TranscribeActivity.kt`

**Interfaces:**
- Consumes: Task 1の`CallMetadataStore(context: Context)` / `fun get(fileName: String): String?`、Task 3の`DiaryMarkdownFormatter.entryBlock(time, phoneNumber, text, audioRelativePath)`
- Produces: なし

`TranscribeActivity`は既存コードにも自動テストが存在しない（`GitHubClient`・`WhisperClient`自体は個別にテスト済み）。本タスクも同じ方針を踏襲し、自動テストは追加せずTask 7の実機確認で検証する。

- [ ] **Step 1: `saveTranscriptAndAudio`を変更する**

`app/src/main/java/com/taka0/callrecorder/TranscribeActivity.kt`の`saveTranscriptAndAudio`関数内、`entryBlock`呼び出し部分を次のように変更する。

変更前:
```kotlin
        val entry = DiaryMarkdownFormatter.entryBlock(time, text, "audio/${recordingFile.name}")
```

変更後:
```kotlin
        val phoneNumber = CallMetadataStore(applicationContext).get(recordingFile.name)
        val entry = DiaryMarkdownFormatter.entryBlock(time, phoneNumber, text, "audio/${recordingFile.name}")
```

- [ ] **Step 2: 全体のビルドが通ることを確認する**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/TranscribeActivity.kt
git commit -m "feat: GitHub保存時にCallMetadataStoreから電話番号を読み出して日記に記録"
```

---

### Task 5: `pull-call-recordings.ps1` — 時刻見出しの復元

**Files:**
- Modify: `日記/pull-call-recordings.ps1`

**Interfaces:**
- Consumes: なし
- Produces: なし

このスクリプトには自動テストが存在しない（既存の方針を踏襲）。実際のPowerShellコマンドで置換結果を確認しながら変更する。

- [ ] **Step 1: 現在の置換動作を確認する**

Run:
```powershell
"## 13:51 — 08088004673`n`nこんにちは`n" -replace '(?m)^##\s*\d{1,2}:\d{2}\s*$', ''
```
Expected: 見出し行が消えず残る（新形式は現在の正規表現にマッチしないため）。次のコマンドで旧形式が消えることを確認する:
```powershell
"## 13:51`n`nこんにちは`n" -replace '(?m)^##\s*\d{1,2}:\d{2}\s*$', ''
```
Expected: 空行のみが残り、時刻が消える（現状の不具合の再現）

- [ ] **Step 2: 新しい置換を試して期待どおりか確認する**

Run:
```powershell
"## 13:51 — 08088004673`n`nこんにちは`n" -replace '(?m)^##\s*(\d{1,2}:\d{2}.*)$', '$1'
```
Expected: `13:51 — 08088004673`

```powershell
"## 13:51`n`nこんにちは`n" -replace '(?m)^##\s*(\d{1,2}:\d{2}.*)$', '$1'
```
Expected: `13:51`（旧形式でも時刻が残る）

- [ ] **Step 3: スクリプトを変更する**

`日記/pull-call-recordings.ps1`の該当行を変更する。

変更前:
```powershell
        # remove "## HH:MM" time headings, keep the text under them
        $body = $body -replace '(?m)^##\s*\d{1,2}:\d{2}\s*$', ''
```

変更後:
```powershell
        # keep the "## HH:MM" / "## HH:MM — number" heading, but drop the markdown "##" prefix
        $body = $body -replace '(?m)^##\s*(\d{1,2}:\d{2}.*)$', '$1'
```

- [ ] **Step 4: 既存の日記データに対して実行し、想定どおりの出力になることを確認する**

Run:
```powershell
powershell -File "D:\Obsidian Vault for Claude Code\日記\pull-call-recordings.ps1"
```
Expected: `[$timestamp] OK: ...`がログに追記される。`日記\通話録音\2026-08-06.md`を開き、各エントリの先頭に`HH:mm`（電話番号は次のTask 6まではまだ現れない。既存の録音は電話番号未記録のため引き続き時刻のみ）が付与されていることを目視確認する

- [ ] **Step 5: コミット**

```bash
git add "日記/pull-call-recordings.ps1"
git commit -m "fix: PC同期スクリプトで時刻見出しを削除せず復元する"
```

---

### Task 6: `pull-call-recordings.ps1` — 電話帳との照合・名前の追記

**Files:**
- Modify: `日記/pull-call-recordings.ps1`

**Interfaces:**
- Consumes: Task 5で変更済みの見出し形式（`HH:mm — 番号`）
- Produces: なし

- [ ] **Step 1: 番号正規化・電話帳読み込みロジックを試作して動作を確認する**

Run:
```powershell
function Normalize-PhoneNumber([string]$number) {
    $digits = ($number -replace '[^\d]', '')
    if ($digits.Length -ge 11 -and $digits.Length -le 12 -and $digits.StartsWith('81')) {
        $digits = '0' + $digits.Substring(2)
    }
    return $digits
}
Normalize-PhoneNumber("080-8900-4673")
Normalize-PhoneNumber("+818088004673")
```
Expected: 両方とも`08088004673`

- [ ] **Step 2: 電話帳ファイルの解析を試作して動作を確認する**

Run:
```powershell
$phoneBook = @{}
Get-Content "D:\Obsidian Vault for Claude Code\日記\通話録音\電話帳.md" | ForEach-Object {
    if ($_ -match '^([\d\-]+)\s+(.+)$') {
        $phoneBook[(Normalize-PhoneNumber($Matches[1]))] = $Matches[2].Trim()
    }
}
$phoneBook
```
Expected: `08088004673 -> 瀬戸口 祥子`が1件だけ出力される

- [ ] **Step 3: スクリプトに組み込む**

`日記/pull-call-recordings.ps1`の先頭（`$repoPath = ...`の前）に関数を追加:

```powershell
function Normalize-PhoneNumber([string]$number) {
    $digits = ($number -replace '[^\d]', '')
    if ($digits.Length -ge 11 -and $digits.Length -le 12 -and $digits.StartsWith('81')) {
        $digits = '0' + $digits.Substring(2)
    }
    return $digits
}

function Get-PhoneBook([string]$path) {
    $phoneBook = @{}
    if (Test-Path $path) {
        Get-Content $path | ForEach-Object {
            if ($_ -match '^([\d\-]+)\s+(.+)$') {
                $phoneBook[(Normalize-PhoneNumber($Matches[1]))] = $Matches[2].Trim()
            }
        }
    }
    return $phoneBook
}
```

`$outputPath`の定義より後、`$converted = 0`の前に追加:

```powershell
$phoneBook = Get-PhoneBook (Join-Path $outputPath "電話帳.md")
```

`Get-ChildItem -Path $diaryPath -Filter "*.md"`の`ForEach-Object`ブロック内、時刻見出しの置換の直後（`# collapse runs of blank lines`より前）に追加:

```powershell
        # append the caller's name from the phone book when the number matches
        $body = [regex]::Replace($body, '(?m)^(\d{1,2}:\d{2}) — (\S+)$', {
            param($match)
            $number = $match.Groups[2].Value
            $normalized = Normalize-PhoneNumber($number)
            if ($phoneBook.ContainsKey($normalized)) {
                "$($match.Groups[1].Value) — $number（$($phoneBook[$normalized])）"
            } else {
                $match.Value
            }
        })
```

- [ ] **Step 4: 既存の日記データに対して実行し、想定どおりの出力になることを確認する**

Run:
```powershell
powershell -File "D:\Obsidian Vault for Claude Code\日記\pull-call-recordings.ps1"
```
Expected: ログにOKが追記される。既存の録音（電話帳未登録の番号、または番号未記録の過去分）は名前が付かないことを確認する。Task 7で実際に`080-8900-4673`から着信・発信して名前付き表示になることを確認する。

- [ ] **Step 5: コミット**

```bash
git add "日記/pull-call-recordings.ps1"
git commit -m "feat: PC同期スクリプトで電話帳と照合し相手の名前を追記"
```

---

### Task 7: 実機動作確認（手動）

**Files:**
- なし（既存ファイルのビルド・実機検証のみ）

**Interfaces:**
- Consumes: Task 1〜6で作成した全コンポーネント

- [ ] **Step 1: デバッグAPKをビルドし実機にインストールする**

Run: `.\gradlew.bat :app:assembleDebug` に続けて `adb install -r "app/build/outputs/apk/debug/app-debug.apk"`
Expected: `BUILD SUCCESSFUL` / `Success`

- [ ] **Step 2: アプリを起動し、`READ_CALL_LOG`の権限ダイアログが表示されて許可できることを確認する**

  `RECORD_AUDIO`・`READ_PHONE_STATE`と並んで`READ_CALL_LOG`の許可を求められることを確認し、許可する。

- [ ] **Step 3: 電話帳に登録済みの番号（`080-8900-4673`）と通話し、録音・文字起こし・GitHub保存までを行う**

  着信または発信で通話し、録音一覧から選択して文字起こし→GitHub保存を実行する。

- [ ] **Step 4: GitHub上の日記データに時刻と電話番号が記録されていることを確認する**

  `diary/YYYY-MM-DD.md`の該当エントリの見出しが`## HH:mm — 08088004673`（またはハイフン付きの実際の番号表記）になっていることを確認する。

- [ ] **Step 5: 電話帳に登録されていない番号でも通話し、見出しが「不明」にならず番号がそのまま記録されることを確認する**

  非通知でない別の番号と通話し、`## HH:mm — <番号>`の形式で番号が記録されることを確認する（本Stepは電話帳マッチの有無に関わらず番号取得自体が機能することの確認）。

- [ ] **Step 6: PC側で同期し、最終ファイルの内容を確認する**

Run:
```powershell
powershell -File "D:\Obsidian Vault for Claude Code\日記\pull-call-recordings.ps1"
```
Expected: `日記\通話録音\YYYY-MM-DD.md`で、Step 3の通話が`HH:mm — 080-8900-4673（瀬戸口 祥子）`のように名前付きで、Step 5の通話が`HH:mm — <番号>`（名前なし）で表示されることを確認する。

- [ ] **Step 7: 動作確認の結果を本ファイルの末尾に追記し、コミットする**

```bash
git add docs/superpowers/plans/2026-08-06-diary-phone-number.md
git commit -m "docs: 通話ダイアリー時刻・電話番号・相手名記録の実機動作確認結果を記録"
```
