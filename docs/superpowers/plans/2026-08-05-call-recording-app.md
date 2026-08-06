# 通話自動録音アプリ Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pixel向けのネイティブAndroidアプリを作り、通話を自動でスピーカーホン＋マイク録音し、選んだ録音だけWhisper APIで文字起こしして、テキストと音声をGitHub経由でObsidian Vaultに自動取り込みできるようにする。

**Architecture:** Kotlin製ネイティブAndroidアプリ（`BroadcastReceiver`で通話状態を検知→`Foreground Service`がスピーカーホンON＋`MediaRecorder`で録音）。録音一覧UIから選んだ音声だけをOpenAI Whisper APIで文字起こしし、確認・編集後にGitHub Contents APIでMarkdownテキストと音声ファイルを保存する。PC側は既存の`pull-diary.ps1`と同じ構成のスクリプトでリポジトリを自動pullし、Vault内フォルダに整理する。

**Tech Stack:** Kotlin, Android Gradle Plugin, OkHttp（HTTP通信・MockWebServerでテスト）, org.json（JSON組み立て）, androidx.security:security-crypto（APIキー/トークンの暗号化保存）, JUnit4（純粋ロジックのユニットテスト）, Robolectric（Android枠組み依存コードのJVM上テスト）, PowerShell（PC側同期スクリプト）。

## Global Constraints

- パッケージ名: `com.taka0.callrecorder`
- `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`（[設計書](../specs/2026-08-05-call-recording-app-design.md)の権限要件を満たす下限。値はAGP 9.3.0のサポート上限とインストール済みSDKに合わせて実機検証済み）
- ビルドツールチェーンは実機検証済みの組み合わせで固定する: **Gradle 9.6.1 / AGP (`com.android.application`) 9.3.0 / JDK 17言語レベル（Android StudioバンドルJDKは25、Gradle 9系のみ動作）**。AGP 9.0以降はKotlinが組み込みサポートになったため、`org.jetbrains.kotlin.android`プラグインは**使用しない**（宣言すると`Failed to apply plugin 'org.jetbrains.kotlin.android'`でビルド失敗する）。同様に`kotlinOptions {}`ブロックも書かない（`compileOptions`の`sourceCompatibility`/`targetCompatibility`のみで足りる）。
- 録音方式は**スピーカーホン自動ON＋`MediaRecorder.AudioSource.MIC`**のみ。`VOICE_CALL`音声ソースはPixelでは機能しないため使用しない（設計書「技術的前提」）。
- 文字起こしは**手動トリガーのみ**（一覧画面で選んだ録音だけ）。自動一括文字起こしは行わない。
- GitHub保存は`voice-diary-app`（`Git/voice-diary-app/app.js`）と同一のMarkdown形式・API呼び出し規約に揃える（`## HH:MM`見出し、`---\ndate: YYYY-MM-DD\n---`フロントマター、`Bearer`トークン認証、`X-GitHub-Api-Version: 2022-11-28`）。
- Google Playストアには公開しない。配布はAPKの直接インストールのみ。
- プロジェクトルート: `D:\Obsidian Vault for Claude Code\Git\call-recording-app`（既にgit初期化済み）

---

### Task 1: 開発環境確認とプロジェクト雛形作成（実施済み）

**このタスクは環境構築の一環としてすでに実行・実機検証済み。** 当初想定していたGradle 8.7 / AGP 8.5.0 / 別立てKotlinプラグインの組み合わせは、Android StudioバンドルJDK（25）でGradleデーモンが起動できず（`FAILURE: ... 25.0.2`）ビルド不能だったため、以下の組み合わせに差し替えて`assembleDebug`まで成功を確認済み。以降のタスクを実行する担当者はこの構成を前提としてよい。

**Files（すべて作成済み・ビルド成功確認済み）:**
- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle.properties`
- `local.properties`（`sdk.dir`のみ。gitignore対象）
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/taka0/callrecorder/MainActivity.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/themes.xml`
- `app/src/main/res/layout/activity_main.xml`
- `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`（Gradle 9.6.1で生成済み）

**Interfaces:**
- Produces: Gradleプロジェクトの雛形一式。以降の全タスクはこの上に`app/src/main/java/com/taka0/callrecorder/`配下へファイルを追加していく。

**実際に使った構成（担当者が同じ環境で再現する場合の手順）:**

- [x] **Step 1: SDKコマンドラインツールを導入し、build-tools・platformをインストールする**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$sdkManager = "C:\Users\taka0\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat"
& $sdkManager "platform-tools" "platforms;android-36" "build-tools;36.1.0" --sdk_root="C:\Users\taka0\AppData\Local\Android\Sdk"
```

（`sdkmanager --licenses`は対話入力がツール経由で届かず固まることがあった。その場合は`$SDK_ROOT/licenses/`配下に既知のライセンスハッシュファイル（`android-sdk-license`等）を直接置くことでも受理される。）

- [x] **Step 2: ANDROID_HOME環境変数を設定する**

```powershell
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Users\taka0\AppData\Local\Android\Sdk", "User")
[System.Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", "C:\Users\taka0\AppData\Local\Android\Sdk", "User")
```

- [x] **Step 3: ルートのGradle設定ファイルを書く（Kotlinプラグインは宣言しない）**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "call-recording-app"
include(":app")
```

`build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "9.3.0" apply false
}
```

（AGP 9.0以降はKotlinが組み込みサポートになったため、`org.jetbrains.kotlin.android`は**宣言しない**。宣言すると`Failed to apply plugin 'org.jetbrains.kotlin.android'... no longer required since AGP 9.0`でビルド失敗する。）

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
```

`local.properties`（gitignore対象、環境ごとに手動作成）:
```properties
sdk.dir=C\:\\Users\\taka0\\AppData\\Local\\Android\\Sdk
```

- [x] **Step 4: `app`モジュールのビルド設定を書く**

`app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
}

android {
    namespace = "com.taka0.callrecorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.taka0.callrecorder"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
```

（`kotlinOptions {}`ブロックは書かない。組み込みKotlinのDSLには存在せず、`compileOptions`のJavaVersionだけで足りる。）

- [x] **Step 5: 最小限のAndroidManifestとMainActivityを書く（権限・受信登録は Task 13 で追加する）**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="true"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="@string/app_name"
        android:theme="@style/Theme.CallRecorder">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">通話録音</string>
</resources>
```

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.CallRecorder" parent="Theme.MaterialComponents.DayNight.NoActionBar" />
</resources>
```

`app/src/main/res/layout/activity_main.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

`app/src/main/java/com/taka0/callrecorder/MainActivity.kt`:
```kotlin
package com.taka0.callrecorder

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
```

- [x] **Step 6: Gradle wrapperを生成する（Gradle 9.6.1固定。8.x系はJDK25のGradleデーモンが起動せず失敗する）**

```powershell
# ブートストラップ用に一時的にGradle 9.6.1をダウンロードして`gradle wrapper`タスクを実行する
# （settings.gradle.kts等を先に置いてから実行すること）
gradle wrapper --gradle-version 9.6.1
```

- [x] **Step 7: ビルドが通ることを確認する**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\taka0\AppData\Local\Android\Sdk"
.\gradlew.bat assembleDebug
```

実結果: `BUILD SUCCESSFUL in 1m 13s`（34 actionable tasks）。`app/build/outputs/apk/debug/app-debug.apk`が生成されることを確認済み。

- [x] **Step 8: コミット**

```bash
cd "D:/Obsidian Vault for Claude Code/Git/call-recording-app"
git add settings.gradle.kts build.gradle.kts gradle.properties app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java app/src/main/res gradlew gradlew.bat gradle .gitignore
git commit -m "chore: プロジェクト雛形を作成"
```

（`.gitignore`に`.gradle/`, `local.properties`, `*.iml`, `.idea/`, `build/` を含めること）

---

### Task 2: FileNaming（録音ファイル名生成ロジック）

**Files:**
- Create: `app/src/main/java/com/taka0/callrecorder/FileNaming.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/FileNamingTest.kt`

**Interfaces:**
- Produces: `FileNaming.recordingFileName(dateTime: java.time.LocalDateTime): String`（例: `2026-08-05-1430.m4a`）。Task 10（RecordingService）で使用。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
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
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.FileNamingTest"`
Expected: `FileNaming`が存在せずコンパイルエラーでFAIL

- [ ] **Step 3: 実装する**

```kotlin
package com.taka0.callrecorder

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object FileNaming {
    private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

    fun recordingFileName(dateTime: LocalDateTime): String {
        return "${dateTime.format(FORMATTER)}.m4a"
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.FileNamingTest"`
Expected: `BUILD SUCCESSFUL`、2 tests passed

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/FileNaming.kt app/src/test/java/com/taka0/callrecorder/FileNamingTest.kt
git commit -m "feat: 録音ファイル名生成ロジックを追加"
```

---

### Task 3: DiaryMarkdownFormatter（Markdown整形ロジック）

**Files:**
- Create: `app/src/main/java/com/taka0/callrecorder/DiaryMarkdownFormatter.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/DiaryMarkdownFormatterTest.kt`

**Interfaces:**
- Consumes: なし（純粋ロジック）
- Produces:
  - `DiaryMarkdownFormatter.diaryFilePath(folder: String, date: java.time.LocalDate): String`
  - `DiaryMarkdownFormatter.audioFilePath(folder: String, fileName: String): String`
  - `DiaryMarkdownFormatter.entryBlock(time: java.time.LocalTime, text: String, audioRelativePath: String?): String`
  - `DiaryMarkdownFormatter.newFileContent(date: java.time.LocalDate, entry: String): String`
  - `DiaryMarkdownFormatter.appendedContent(existingContent: String, entry: String): String`
  すべてTask 5（GitHubClient）・Task 16（TranscribeActivity）で使用。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
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
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.DiaryMarkdownFormatterTest"`
Expected: コンパイルエラーでFAIL

- [ ] **Step 3: 実装する**

```kotlin
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

    fun entryBlock(time: LocalTime, text: String, audioRelativePath: String?): String {
        val heading = "## ${time.format(TIME_FORMATTER)}\n\n"
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
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.DiaryMarkdownFormatterTest"`
Expected: `BUILD SUCCESSFUL`、6 tests passed

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/DiaryMarkdownFormatter.kt app/src/test/java/com/taka0/callrecorder/DiaryMarkdownFormatterTest.kt
git commit -m "feat: 日記Markdown整形ロジックを追加"
```

---

### Task 4: GitHubContentRequestBuilder（GitHub PUTボディ組み立て）

**Files:**
- Create: `app/src/main/java/com/taka0/callrecorder/GitHubContentRequestBuilder.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/GitHubContentRequestBuilderTest.kt`

**Interfaces:**
- Produces:
  - `GitHubContentRequestBuilder.encodePath(path: String): String`
  - `GitHubContentRequestBuilder.textPutBody(message: String, content: String, branch: String, sha: String?): String`（JSON文字列）
  - `GitHubContentRequestBuilder.binaryPutBody(message: String, contentBytes: ByteArray, branch: String, sha: String?): String`（JSON文字列）
  Task 5（GitHubClient）で使用。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package com.taka0.callrecorder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubContentRequestBuilderTest {

    @Test
    fun `encodes each path segment but keeps slashes`() {
        val encoded = GitHubContentRequestBuilder.encodePath("diary/2026-08-05.md")
        assertEquals("diary/2026-08-05.md", encoded)
    }

    @Test
    fun `encodes special characters within a segment`() {
        val encoded = GitHubContentRequestBuilder.encodePath("diary/日記 2026.md")
        assertFalse(encoded.contains(" "))
    }

    @Test
    fun `text put body without sha omits sha field`() {
        val body = JSONObject(GitHubContentRequestBuilder.textPutBody("msg", "hello", "main", null))
        assertEquals("msg", body.getString("message"))
        assertEquals("main", body.getString("branch"))
        assertEquals("aGVsbG8=", body.getString("content"))
        assertFalse(body.has("sha"))
    }

    @Test
    fun `text put body with sha includes sha field`() {
        val body = JSONObject(GitHubContentRequestBuilder.textPutBody("msg", "hello", "main", "abc123"))
        assertEquals("abc123", body.getString("sha"))
    }

    @Test
    fun `binary put body base64 encodes raw bytes`() {
        val body = JSONObject(GitHubContentRequestBuilder.binaryPutBody("msg", byteArrayOf(1, 2, 3), "main", null))
        assertEquals("AQID", body.getString("content"))
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.GitHubContentRequestBuilderTest"`
Expected: コンパイルエラーでFAIL

- [ ] **Step 3: 実装する**

```kotlin
package com.taka0.callrecorder

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

object GitHubContentRequestBuilder {

    fun encodePath(path: String): String {
        return path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
    }

    fun textPutBody(message: String, content: String, branch: String, sha: String?): String {
        return putBody(message, Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)), branch, sha)
    }

    fun binaryPutBody(message: String, contentBytes: ByteArray, branch: String, sha: String?): String {
        return putBody(message, Base64.getEncoder().encodeToString(contentBytes), branch, sha)
    }

    private fun putBody(message: String, base64Content: String, branch: String, sha: String?): String {
        val json = JSONObject()
        json.put("message", message)
        json.put("content", base64Content)
        json.put("branch", branch)
        if (sha != null) json.put("sha", sha)
        return json.toString()
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.GitHubContentRequestBuilderTest"`
Expected: `BUILD SUCCESSFUL`、5 tests passed

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/GitHubContentRequestBuilder.kt app/src/test/java/com/taka0/callrecorder/GitHubContentRequestBuilderTest.kt
git commit -m "feat: GitHub Contents APIリクエストボディ組み立てロジックを追加"
```

---

### Task 5: GitHubClient（GitHub Contents APIの実行、テキスト＋音声保存）

**Files:**
- Create: `app/src/main/java/com/taka0/callrecorder/GitHubClient.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/GitHubClientTest.kt`

**Interfaces:**
- Consumes: `GitHubContentRequestBuilder.encodePath/textPutBody/binaryPutBody`（Task 4）
- Produces:
  - `class GitHubClient(httpClient: OkHttpClient = OkHttpClient(), apiBaseUrl: String = "https://api.github.com")`
  - `GitHubClient.getExistingTextFile(repo: String, branch: String, path: String, token: String): Pair<String, String>?`（`sha to 本文`、無ければ`null`）
  - `GitHubClient.putTextFile(repo: String, branch: String, path: String, content: String, message: String, sha: String?, token: String)`
  - `GitHubClient.putBinaryFile(repo: String, branch: String, path: String, contentBytes: ByteArray, message: String, sha: String?, token: String)`
  - `class GitHubClient.GitHubException(message: String) : Exception(message)`
  Task 16（TranscribeActivity）で使用。

- [ ] **Step 1: 失敗するテストを書く（MockWebServerで擬似GitHub APIを立てる）**

```kotlin
package com.taka0.callrecorder

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GitHubClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GitHubClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GitHubClient(apiBaseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `returns null when file does not exist yet`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = client.getExistingTextFile("me/repo", "main", "diary/2026-08-05.md", "token123")
        assertNull(result)
    }

    @Test
    fun `returns sha and decoded content when file exists`() {
        val body = JSONObject()
            .put("sha", "abc123")
            .put("content", "5pel44Gr")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))

        val result = client.getExistingTextFile("me/repo", "main", "diary/2026-08-05.md", "token123")

        assertEquals("abc123", result!!.first)
        assertEquals("先に", result.second)
    }

    @Test
    fun `putTextFile sends PUT request with authorization header`() {
        server.enqueue(MockResponse().setResponseCode(200))

        client.putTextFile("me/repo", "main", "diary/2026-08-05.md", "本文", "msg", null, "token123")

        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("Bearer token123", recorded.getHeader("Authorization"))
    }

    @Test(expected = GitHubClient.GitHubException::class)
    fun `putTextFile throws when response is not successful`() {
        server.enqueue(MockResponse().setResponseCode(422))
        client.putTextFile("me/repo", "main", "diary/2026-08-05.md", "本文", "msg", null, "token123")
    }

    @Test
    fun `putBinaryFile sends base64 encoded bytes as PUT body`() {
        server.enqueue(MockResponse().setResponseCode(201))

        client.putBinaryFile("me/repo", "main", "diary/audio/2026-08-05-1430.m4a", byteArrayOf(1, 2, 3), "msg", null, "token123")

        val recorded = server.takeRequest()
        val sentBody = JSONObject(recorded.body.readUtf8())
        assertEquals("AQID", sentBody.getString("content"))
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.GitHubClientTest"`
Expected: `GitHubClient`が存在せずコンパイルエラーでFAIL

- [ ] **Step 3: 実装する**

```kotlin
package com.taka0.callrecorder

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64

class GitHubClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val apiBaseUrl: String = "https://api.github.com"
) {
    class GitHubException(message: String) : Exception(message)

    fun getExistingTextFile(repo: String, branch: String, path: String, token: String): Pair<String, String>? {
        val url = "$apiBaseUrl/repos/$repo/contents/${GitHubContentRequestBuilder.encodePath(path)}?ref=$branch"
        httpClient.newCall(authorizedRequest(url, token).get().build()).execute().use { response ->
            return when (response.code) {
                200 -> {
                    val json = JSONObject(response.body!!.string())
                    val sha = json.getString("sha")
                    val decoded = String(Base64.getMimeDecoder().decode(json.getString("content")), Charsets.UTF_8)
                    sha to decoded
                }
                404 -> null
                401 -> throw GitHubException("トークンが無効です")
                else -> throw GitHubException("リポジトリの確認に失敗しました（${response.code}）")
            }
        }
    }

    fun putTextFile(repo: String, branch: String, path: String, content: String, message: String, sha: String?, token: String) {
        val url = "$apiBaseUrl/repos/$repo/contents/${GitHubContentRequestBuilder.encodePath(path)}"
        putRequest(url, GitHubContentRequestBuilder.textPutBody(message, content, branch, sha), token)
    }

    fun putBinaryFile(repo: String, branch: String, path: String, contentBytes: ByteArray, message: String, sha: String?, token: String) {
        val url = "$apiBaseUrl/repos/$repo/contents/${GitHubContentRequestBuilder.encodePath(path)}"
        putRequest(url, GitHubContentRequestBuilder.binaryPutBody(message, contentBytes, branch, sha), token)
    }

    private fun authorizedRequest(url: String, token: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer $token")
    }

    private fun putRequest(url: String, jsonBody: String, token: String) {
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        val request = authorizedRequest(url, token).put(requestBody).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw GitHubException("保存に失敗しました（${response.code}）")
            }
        }
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.GitHubClientTest"`
Expected: `BUILD SUCCESSFUL`、5 tests passed

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/GitHubClient.kt app/src/test/java/com/taka0/callrecorder/GitHubClientTest.kt
git commit -m "feat: GitHub Contents APIクライアントを追加"
```

---

### Task 6: WhisperRequestBuilder（マルチパートボディ組み立て）

**Files:**
- Create: `app/src/main/java/com/taka0/callrecorder/WhisperRequestBuilder.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/WhisperRequestBuilderTest.kt`

**Interfaces:**
- Produces: `WhisperRequestBuilder.buildTranscriptionBody(audioFile: java.io.File, model: String = "whisper-1"): okhttp3.MultipartBody`
  Task 7（WhisperClient）で使用。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package com.taka0.callrecorder

import okio.Buffer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WhisperRequestBuilderTest {

    @Test
    fun `multipart body contains model field and audio file part`() {
        val tempFile = File.createTempFile("recording", ".m4a").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }

        val body = WhisperRequestBuilder.buildTranscriptionBody(tempFile)
        val buffer = Buffer()
        body.writeTo(buffer)
        val serialized = buffer.readUtf8()

        assertTrue(serialized.contains("name=\"model\""))
        assertTrue(serialized.contains("whisper-1"))
        assertTrue(serialized.contains("name=\"file\""))
        assertTrue(serialized.contains(tempFile.name))
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.WhisperRequestBuilderTest"`
Expected: コンパイルエラーでFAIL

- [ ] **Step 3: 実装する**

```kotlin
package com.taka0.callrecorder

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

object WhisperRequestBuilder {
    fun buildTranscriptionBody(audioFile: File, model: String = "whisper-1"): MultipartBody {
        return MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/m4a".toMediaType())
            )
            .build()
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.WhisperRequestBuilderTest"`
Expected: `BUILD SUCCESSFUL`、1 test passed

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/WhisperRequestBuilder.kt app/src/test/java/com/taka0/callrecorder/WhisperRequestBuilderTest.kt
git commit -m "feat: Whisper文字起こしリクエストのマルチパートボディ組み立てを追加"
```

---

### Task 7: WhisperClient（Whisper APIの実行）

**Files:**
- Create: `app/src/main/java/com/taka0/callrecorder/WhisperClient.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/WhisperClientTest.kt`

**Interfaces:**
- Consumes: `WhisperRequestBuilder.buildTranscriptionBody`（Task 6）
- Produces:
  - `class WhisperClient(httpClient: OkHttpClient = OkHttpClient(), apiBaseUrl: String = "https://api.openai.com/v1")`
  - `WhisperClient.transcribe(audioFile: java.io.File, apiKey: String): String`
  - `class WhisperClient.WhisperException(message: String) : Exception(message)`
  Task 16（TranscribeActivity）で使用。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package com.taka0.callrecorder

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class WhisperClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: WhisperClient
    private lateinit var audioFile: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WhisperClient(apiBaseUrl = server.url("/").toString().trimEnd('/'))
        audioFile = File.createTempFile("recording", ".m4a").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `returns transcribed text on success`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(JSONObject().put("text", "こんにちは").toString()))

        val text = client.transcribe(audioFile, "sk-test")

        assertEquals("こんにちは", text)
        assertEquals("Bearer sk-test", server.takeRequest().getHeader("Authorization"))
    }

    @Test(expected = WhisperClient.WhisperException::class)
    fun `throws when response is not successful`() {
        server.enqueue(MockResponse().setResponseCode(401))
        client.transcribe(audioFile, "sk-test")
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.WhisperClientTest"`
Expected: `WhisperClient`が存在せずコンパイルエラーでFAIL

- [ ] **Step 3: 実装する**

```kotlin
package com.taka0.callrecorder

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

class WhisperClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val apiBaseUrl: String = "https://api.openai.com/v1"
) {
    class WhisperException(message: String) : Exception(message)

    fun transcribe(audioFile: File, apiKey: String): String {
        val request = Request.Builder()
            .url("$apiBaseUrl/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(WhisperRequestBuilder.buildTranscriptionBody(audioFile))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyString = response.body!!.string()
            if (!response.isSuccessful) {
                throw WhisperException("文字起こしに失敗しました（${response.code}）")
            }
            return JSONObject(bodyString).getString("text")
        }
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.WhisperClientTest"`
Expected: `BUILD SUCCESSFUL`、2 tests passed

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/WhisperClient.kt app/src/test/java/com/taka0/callrecorder/WhisperClientTest.kt
git commit -m "feat: Whisper APIクライアントを追加"
```

---

### Task 8: SecureSettingsStore（APIキー・トークンの暗号化保存）

**Files:**
- Create: `app/src/main/java/com/taka0/callrecorder/SecureSettingsStore.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/SecureSettingsStoreTest.kt`

**Interfaces:**
- Produces: `class SecureSettingsStore(context: android.content.Context)` に以下のプロパティ:
  - `var openAiApiKey: String`
  - `var gitHubToken: String`
  - `var gitHubRepo: String`
  - `var gitHubBranch: String`（既定値 `"main"`）
  - `var gitHubFolder: String`（既定値 `"diary"`）
  - `fun isConfigured(): Boolean`
  Task 15（SettingsActivity）・Task 16（TranscribeActivity）で使用。

- [ ] **Step 1: 失敗するテストを書く（Robolectricでアプリコンテキストを使う）**

```kotlin
package com.taka0.callrecorder

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureSettingsStoreTest {

    @Test
    fun `defaults branch to main and folder to diary`() {
        val store = SecureSettingsStore(ApplicationProvider.getApplicationContext())
        assertEquals("main", store.gitHubBranch)
        assertEquals("diary", store.gitHubFolder)
    }

    @Test
    fun `persists values across instances backed by the same context`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SecureSettingsStore(context).apply {
            openAiApiKey = "sk-test"
            gitHubToken = "ghp-test"
            gitHubRepo = "me/call-recording-app"
        }

        val reloaded = SecureSettingsStore(context)
        assertEquals("sk-test", reloaded.openAiApiKey)
        assertEquals("ghp-test", reloaded.gitHubToken)
        assertEquals("me/call-recording-app", reloaded.gitHubRepo)
    }

    @Test
    fun `is not configured until token and repo are set`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SecureSettingsStore(context)
        assertFalse(store.isConfigured())

        store.gitHubToken = "ghp-test"
        store.gitHubRepo = "me/call-recording-app"
        assertTrue(store.isConfigured())
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.SecureSettingsStoreTest"`
Expected: `SecureSettingsStore`が存在せずコンパイルエラーでFAIL

- [ ] **Step 3: 実装する**

```kotlin
package com.taka0.callrecorder

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureSettingsStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "call_recorder_secure_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var openAiApiKey: String
        get() = prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI_API_KEY, value).apply()

    var gitHubToken: String
        get() = prefs.getString(KEY_GITHUB_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITHUB_TOKEN, value).apply()

    var gitHubRepo: String
        get() = prefs.getString(KEY_GITHUB_REPO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITHUB_REPO, value).apply()

    var gitHubBranch: String
        get() = prefs.getString(KEY_GITHUB_BRANCH, "main") ?: "main"
        set(value) = prefs.edit().putString(KEY_GITHUB_BRANCH, value).apply()

    var gitHubFolder: String
        get() = prefs.getString(KEY_GITHUB_FOLDER, "diary") ?: "diary"
        set(value) = prefs.edit().putString(KEY_GITHUB_FOLDER, value).apply()

    fun isConfigured(): Boolean = gitHubToken.isNotBlank() && gitHubRepo.isNotBlank()

    companion object {
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_GITHUB_REPO = "github_repo"
        private const val KEY_GITHUB_BRANCH = "github_branch"
        private const val KEY_GITHUB_FOLDER = "github_folder"
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.SecureSettingsStoreTest"`
Expected: `BUILD SUCCESSFUL`、3 tests passed

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/SecureSettingsStore.kt app/src/test/java/com/taka0/callrecorder/SecureSettingsStoreTest.kt
git commit -m "feat: APIキー・トークンの暗号化保存ストアを追加"
```

---

### Task 9: AudioRecorder抽象化とMediaRecorder実装

**Files:**
- Create: `app/src/main/java/com/taka0/callrecorder/AudioRecorder.kt`
- Create: `app/src/main/java/com/taka0/callrecorder/MediaRecorderAudioRecorder.kt`

**Interfaces:**
- Produces:
  - `interface AudioRecorder { fun start(outputFile: java.io.File); fun stop() }`
  - `class MediaRecorderAudioRecorder(context: android.content.Context) : AudioRecorder`
  Task 10（RecordingService）で使用。`AudioRecorder`はテスト用フェイク（Task 10のテストで実装）に差し替え可能にするためのインターフェース。

実機の`MediaRecorder`は自動テスト不可（マイクハードウェアが必要）なので、このタスクにユニットテストは含めない。正しさはTask 10のRobolectricテスト（フェイク経由の呼び出し確認）とTask 18の実機確認で担保する。

- [ ] **Step 1: インターフェースを定義する**

```kotlin
package com.taka0.callrecorder

import java.io.File

interface AudioRecorder {
    fun start(outputFile: File)
    fun stop()
}
```

- [ ] **Step 2: MediaRecorderベースの実装を書く**

```kotlin
package com.taka0.callrecorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class MediaRecorderAudioRecorder(private val context: Context) : AudioRecorder {
    private var recorder: MediaRecorder? = null

    override fun start(outputFile: File) {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setOutputFile(outputFile.absolutePath)
        r.prepare()
        r.start()
        recorder = r
    }

    override fun stop() {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null
    }
}
```

- [ ] **Step 3: `.\gradlew.bat :app:compileDebugKotlin` でコンパイルが通ることを確認する**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/AudioRecorder.kt app/src/main/java/com/taka0/callrecorder/MediaRecorderAudioRecorder.kt
git commit -m "feat: AudioRecorder抽象化とMediaRecorder実装を追加"
```

---

### Task 10: RecordingService（フォアグラウンドサービスによる自動録音制御）

**Files:**
- Create: `app/src/main/java/com/taka0/callrecorder/RecordingService.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/RecordingServiceTest.kt`

**Interfaces:**
- Consumes: `AudioRecorder`（Task 9）、`FileNaming.recordingFileName`（Task 2）
- Produces:
  - `class RecordingService : android.app.Service()`
  - `RecordingService.ACTION_START = "com.taka0.callrecorder.action.START"`
  - `RecordingService.ACTION_STOP = "com.taka0.callrecorder.action.STOP"`
  - `RecordingService.setAudioRecorderForTest(recorder: AudioRecorder)`（テスト用差し替え口）
  Task 11（CallStateReceiver）から`Intent`で起動される。

- [ ] **Step 1: 失敗するテストを書く（Robolectricでサービスをビルドし、フェイクAudioRecorderで検証する）**

```kotlin
package com.taka0.callrecorder

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

class FakeAudioRecorder : AudioRecorder {
    var startedFile: File? = null
    var stopped = false

    override fun start(outputFile: File) {
        startedFile = outputFile
    }

    override fun stop() {
        stopped = true
    }
}

@RunWith(RobolectricTestRunner::class)
class RecordingServiceTest {

    @Test
    fun `ACTION_START begins recording to a file named by FileNaming`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val fake = FakeAudioRecorder()
        service.setAudioRecorderForTest(fake)

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START), 0, 1)

        assertNotNull(fake.startedFile)
        assertTrue(fake.startedFile!!.name.endsWith(".m4a"))
    }

    @Test
    fun `ACTION_STOP stops the audio recorder`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val fake = FakeAudioRecorder()
        service.setAudioRecorderForTest(fake)
        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START), 0, 1)

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 2)

        assertTrue(fake.stopped)
    }

    @Test
    fun `enables speakerphone when recording starts`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.setAudioRecorderForTest(FakeAudioRecorder())

        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), RecordingService::class.java).setAction(RecordingService.ACTION_START), 0, 1)

        val audioManager = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        assertTrue(audioManager.isSpeakerphoneOn)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.RecordingServiceTest"`
Expected: `RecordingService`が存在せずコンパイルエラーでFAIL

- [ ] **Step 3: 実装する**

```kotlin
package com.taka0.callrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import androidx.core.app.NotificationCompat
import java.io.File
import java.time.LocalDateTime

class RecordingService : Service() {

    private lateinit var audioRecorder: AudioRecorder

    override fun onCreate() {
        super.onCreate()
        audioRecorder = MediaRecorderAudioRecorder(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }

        if (StatFs(dir.path).availableBytes < MIN_FREE_BYTES_TO_RECORD) {
            notifyLowStorageAndStop()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = true

        val file = File(dir, FileNaming.recordingFileName(LocalDateTime.now()))
        try {
            audioRecorder.start(file)
        } catch (e: Exception) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        try {
            audioRecorder.stop()
        } catch (e: Exception) {
            // recorder was never successfully started; nothing to stop
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notifyLowStorageAndStop() {
        val channelId = "call_recording"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "通話録音", NotificationManager.IMPORTANCE_DEFAULT)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("空き容量が不足しています")
            .setContentText("通話を録音できませんでした")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val channelId = "call_recording"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "通話録音", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("通話を録音中")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .build()
    }

    fun setAudioRecorderForTest(recorder: AudioRecorder) {
        audioRecorder = recorder
    }

    companion object {
        const val ACTION_START = "com.taka0.callrecorder.action.START"
        const val ACTION_STOP = "com.taka0.callrecorder.action.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_FREE_BYTES_TO_RECORD = 50L * 1024 * 1024 // 50MB
    }
}
```

（設計書の「端末のストレージ残量が少ない場合、録音開始前に警告する」「エラーハンドリング」要件に対応: 空き容量50MB未満なら録音せず通知のみ出す。また`audioRecorder.start()`が例外を投げてもサービスがクラッシュせず安全に終了するようにする。）

- [ ] **Step 4: テストが通ることを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.RecordingServiceTest"`
Expected: `BUILD SUCCESSFUL`、3 tests passed

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/RecordingService.kt app/src/test/java/com/taka0/callrecorder/RecordingServiceTest.kt
git commit -m "feat: 通話録音フォアグラウンドサービスを追加"
```

---

### Task 11: CallStateReceiver（通話状態検知）

**Files:**
- Create: `app/src/main/java/com/taka0/callrecorder/CallStateReceiver.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/CallStateReceiverTest.kt`

**Interfaces:**
- Consumes: `RecordingService.ACTION_START` / `RecordingService.ACTION_STOP`（Task 10）
- Produces: `class CallStateReceiver : android.content.BroadcastReceiver()`。Task 13でAndroidManifestに登録する。

- [ ] **Step 1: 失敗するテストを書く（RobolectricでシャドウのstartedServiceを検証する）**

```kotlin
package com.taka0.callrecorder

import android.content.Intent
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CallStateReceiverTest {

    @Test
    fun `OFFHOOK starts the recording service with ACTION_START`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowApp = shadowOf(context as android.app.Application)
        val receiver = CallStateReceiver()

        val intent = Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            .putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK)
        receiver.onReceive(context, intent)

        val started = shadowApp.nextStartedService
        assertEquals(RecordingService.ACTION_START, started.action)
        assertEquals(RecordingService::class.java.name, started.component!!.className)
    }

    @Test
    fun `IDLE starts the recording service with ACTION_STOP`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowApp = shadowOf(context as android.app.Application)
        val receiver = CallStateReceiver()

        val intent = Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            .putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_IDLE)
        receiver.onReceive(context, intent)

        assertEquals(RecordingService.ACTION_STOP, shadowApp.nextStartedService.action)
    }

    @Test
    fun `RINGING does not start any service`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadowApp = shadowOf(context as android.app.Application)
        val receiver = CallStateReceiver()

        val intent = Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            .putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_RINGING)
        receiver.onReceive(context, intent)

        assertNull(shadowApp.nextStartedService)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.CallStateReceiverTest"`
Expected: `CallStateReceiver`が存在せずコンパイルエラーでFAIL

- [ ] **Step 3: 実装する**

```kotlin
package com.taka0.callrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val action = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> RecordingService.ACTION_START
            TelephonyManager.EXTRA_STATE_IDLE -> RecordingService.ACTION_STOP
            else -> return
        }

        val serviceIntent = Intent(context, RecordingService::class.java).setAction(action)
        context.startForegroundService(serviceIntent)
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.CallStateReceiverTest"`
Expected: `BUILD SUCCESSFUL`、3 tests passed

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/CallStateReceiver.kt app/src/test/java/com/taka0/callrecorder/CallStateReceiverTest.kt
git commit -m "feat: 通話状態検知レシーバーを追加"
```

**既知の制約:** 発信時は相手が応答する前（呼び出し中）から`OFFHOOK`になるため、厳密には「相手が出る前」から録音が始まる場合がある。個人用メモ用途では許容し、対応は行わない（YAGNI）。

---

### Task 12: RecordingRepository（端末内録音ファイルの一覧・削除）

**Files:**
- Create: `app/src/main/java/com/taka0/callrecorder/RecordingRepository.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/RecordingRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `data class Recording(val file: java.io.File, val recordedAt: java.time.LocalDateTime)`
  - `class RecordingRepository(recordingsDir: java.io.File)`
  - `RecordingRepository.list(): List<Recording>`（`recordedAt`降順）
  - `RecordingRepository.delete(recording: Recording): Boolean`
  Task 14（MainActivity）・Task 16（TranscribeActivity）で使用。

- [ ] **Step 1: 失敗するテストを書く（一時ディレクトリを使用）**

```kotlin
package com.taka0.callrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RecordingRepositoryTest {

    @Test
    fun `lists m4a files sorted by recorded time descending`() {
        val dir = Files.createTempDirectory("recordings").toFile()
        dir.resolve("2026-08-05-0900.m4a").createNewFile()
        dir.resolve("2026-08-05-1430.m4a").createNewFile()
        dir.resolve("not-a-recording.txt").createNewFile()

        val recordings = RecordingRepository(dir).list()

        assertEquals(2, recordings.size)
        assertEquals("2026-08-05-1430.m4a", recordings[0].file.name)
        assertEquals("2026-08-05-0900.m4a", recordings[1].file.name)
    }

    @Test
    fun `ignores files whose name does not match the expected pattern`() {
        val dir = Files.createTempDirectory("recordings").toFile()
        dir.resolve("random.m4a").createNewFile()

        assertTrue(RecordingRepository(dir).list().isEmpty())
    }

    @Test
    fun `delete removes the file from disk`() {
        val dir = Files.createTempDirectory("recordings").toFile()
        val file = dir.resolve("2026-08-05-0900.m4a").apply { createNewFile() }
        val repository = RecordingRepository(dir)
        val recording = repository.list().first()

        val deleted = repository.delete(recording)

        assertTrue(deleted)
        assertFalse(file.exists())
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.RecordingRepositoryTest"`
Expected: `RecordingRepository`が存在せずコンパイルエラーでFAIL

- [ ] **Step 3: 実装する**

```kotlin
package com.taka0.callrecorder

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
            LocalDateTime.parse(nameWithoutExtension, FORMATTER)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    companion object {
        private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.RecordingRepositoryTest"`
Expected: `BUILD SUCCESSFUL`、3 tests passed

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/RecordingRepository.kt app/src/test/java/com/taka0/callrecorder/RecordingRepositoryTest.kt
git commit -m "feat: 録音ファイルの一覧・削除リポジトリを追加"
```

---

### Task 13: AndroidManifestの権限・コンポーネント登録を最終化する

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `RecordingService`（Task 10）、`CallStateReceiver`（Task 11）

- [ ] **Step 1: 権限とコンポーネント登録を追加する**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="@string/app_name"
        android:theme="@style/Theme.CallRecorder">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity android:name=".SettingsActivity" android:exported="false" />
        <activity android:name=".TranscribeActivity" android:exported="false" />

        <service
            android:name=".RecordingService"
            android:exported="false"
            android:foregroundServiceType="microphone" />

        <receiver
            android:name=".CallStateReceiver"
            android:exported="true"
            android:permission="android.permission.READ_PHONE_STATE">
            <intent-filter>
                <action android:name="android.intent.action.PHONE_STATE" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

（`SettingsActivity`・`TranscribeActivity`はTask 15・16でまだ実体を作っていないため、この時点ではビルドが通らない。Step 2で一時的な空実装を置く。）

- [ ] **Step 2: まだ存在しないActivityの最小空実装を置き、ビルドを通す**

`app/src/main/java/com/taka0/callrecorder/SettingsActivity.kt`（Task 15で本実装に置き換える）:
```kotlin
package com.taka0.callrecorder

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
```

`app/src/main/java/com/taka0/callrecorder/TranscribeActivity.kt`（Task 16で本実装に置き換える）:
```kotlin
package com.taka0.callrecorder

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class TranscribeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
```

- [ ] **Step 3: ビルドが通ることを確認する**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: コミット**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/taka0/callrecorder/SettingsActivity.kt app/src/main/java/com/taka0/callrecorder/TranscribeActivity.kt
git commit -m "feat: 権限とコンポーネントをAndroidManifestに登録"
```

---

### Task 14: MainActivity・録音一覧UI

**Files:**
- Modify: `app/src/main/java/com/taka0/callrecorder/MainActivity.kt`
- Create: `app/src/main/java/com/taka0/callrecorder/RecordingsAdapter.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/layout/item_recording.xml`
- Modify: `app/src/main/java/com/taka0/callrecorder/TranscribeActivity.kt`（`EXTRA_RECORDING_PATH`定数のみ先行追加。本実装はTask 16）

**Interfaces:**
- Consumes: `RecordingRepository`（Task 12）、`Recording`（Task 12）
- Produces: `MainActivity`起動時にランタイム権限（`RECORD_AUDIO`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS`）を要求し、`RecordingRepository.list()`をRecyclerViewに表示する。「文字起こし」ボタン押下で`TranscribeActivity`へ録音ファイルパスを渡して遷移する（Task 16で受け取る）。

このタスクはUIの見た目・実機操作の確認が主目的であり、自動テストは行わない（Robolectricでの`Activity`起動確認のみ行う）。

- [ ] **Step 1: 一覧アイテムのレイアウトを書く**

`app/src/main/res/layout/item_recording.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="16dp">

    <TextView
        android:id="@+id/recording_label"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textSize="16sp" />

    <Button
        android:id="@+id/play_button"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="再生" />

    <Button
        android:id="@+id/transcribe_button"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="文字起こし" />

    <Button
        android:id="@+id/delete_button"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="削除" />
</LinearLayout>
```

- [ ] **Step 2: メイン画面のレイアウトを書く**

`app/src/main/res/layout/activity_main.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <Button
        android:id="@+id/settings_button"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="設定" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recordings_list"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />
</LinearLayout>
```

- [ ] **Step 3: RecordingsAdapterを書く**

```kotlin
package com.taka0.callrecorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.time.format.DateTimeFormatter

class RecordingsAdapter(
    private var recordings: List<Recording>,
    private val onPlay: (Recording) -> Unit,
    private val onTranscribe: (Recording) -> Unit,
    private val onDelete: (Recording) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

    private val labelFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.recording_label)
        val playButton: Button = view.findViewById(R.id.play_button)
        val transcribeButton: Button = view.findViewById(R.id.transcribe_button)
        val deleteButton: Button = view.findViewById(R.id.delete_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recording = recordings[position]
        holder.label.text = recording.recordedAt.format(labelFormatter)
        holder.playButton.setOnClickListener { onPlay(recording) }
        holder.transcribeButton.setOnClickListener { onTranscribe(recording) }
        holder.deleteButton.setOnClickListener { onDelete(recording) }
    }

    override fun getItemCount(): Int = recordings.size

    fun updateRecordings(newRecordings: List<Recording>) {
        recordings = newRecordings
        notifyDataSetChanged()
    }
}
```

- [ ] **Step 4: MainActivityを実装する**

```kotlin
package com.taka0.callrecorder

import android.Manifest
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var repository: RecordingRepository
    private lateinit var adapter: RecordingsAdapter

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val recordingsDir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        repository = RecordingRepository(recordingsDir)

        adapter = RecordingsAdapter(
            recordings = repository.list(),
            onPlay = ::playRecording,
            onTranscribe = ::openTranscribe,
            onDelete = ::deleteRecording
        )

        findViewById<RecyclerView>(R.id.recordings_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<android.widget.Button>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        adapter.updateRecordings(repository.list())
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun playRecording(recording: Recording) {
        MediaPlayer().apply {
            setDataSource(recording.file.absolutePath)
            prepare()
            start()
        }
    }

    private fun openTranscribe(recording: Recording) {
        startActivity(
            Intent(this, TranscribeActivity::class.java)
                .putExtra(TranscribeActivity.EXTRA_RECORDING_PATH, recording.file.absolutePath)
        )
    }

    private fun deleteRecording(recording: Recording) {
        repository.delete(recording)
        adapter.updateRecordings(repository.list())
    }
}
```

- [ ] **Step 5: `TranscribeActivity`スタブに`EXTRA_RECORDING_PATH`定数を追加する（本実装はTask 16で行う）**

`app/src/main/java/com/taka0/callrecorder/TranscribeActivity.kt`（Task 13で作った空実装を書き換える）:
```kotlin
package com.taka0.callrecorder

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class TranscribeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    companion object {
        const val EXTRA_RECORDING_PATH = "recording_path"
    }
}
```

- [ ] **Step 6: ビルドが通ることを確認する**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/MainActivity.kt app/src/main/java/com/taka0/callrecorder/RecordingsAdapter.kt app/src/main/res/layout/activity_main.xml app/src/main/res/layout/item_recording.xml app/src/main/java/com/taka0/callrecorder/TranscribeActivity.kt
git commit -m "feat: 録音一覧画面を追加"
```

---

### Task 15: SettingsActivity（APIキー・GitHub設定画面）

**Files:**
- Modify: `app/src/main/java/com/taka0/callrecorder/SettingsActivity.kt`
- Create: `app/src/main/res/layout/activity_settings.xml`

**Interfaces:**
- Consumes: `SecureSettingsStore`（Task 8）

- [ ] **Step 1: レイアウトを書く**

`app/src/main/res/layout/activity_settings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <EditText
        android:id="@+id/openai_key_input"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="OpenAI APIキー" />

    <EditText
        android:id="@+id/github_token_input"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="GitHub Personal Access Token" />

    <EditText
        android:id="@+id/github_repo_input"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="リポジトリ（例: user/call-recording-app）" />

    <EditText
        android:id="@+id/github_branch_input"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="ブランチ（既定: main）" />

    <EditText
        android:id="@+id/github_folder_input"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="保存フォルダ（既定: diary）" />

    <Button
        android:id="@+id/save_settings_button"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="保存" />
</LinearLayout>
```

- [ ] **Step 2: SettingsActivityを実装する**

```kotlin
package com.taka0.callrecorder

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var store: SecureSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = SecureSettingsStore(applicationContext)

        val openAiInput = findViewById<EditText>(R.id.openai_key_input).apply { setText(store.openAiApiKey) }
        val tokenInput = findViewById<EditText>(R.id.github_token_input).apply { setText(store.gitHubToken) }
        val repoInput = findViewById<EditText>(R.id.github_repo_input).apply { setText(store.gitHubRepo) }
        val branchInput = findViewById<EditText>(R.id.github_branch_input).apply { setText(store.gitHubBranch) }
        val folderInput = findViewById<EditText>(R.id.github_folder_input).apply { setText(store.gitHubFolder) }

        findViewById<Button>(R.id.save_settings_button).setOnClickListener {
            store.openAiApiKey = openAiInput.text.toString().trim()
            store.gitHubToken = tokenInput.text.toString().trim()
            store.gitHubRepo = repoInput.text.toString().trim()
            store.gitHubBranch = branchInput.text.toString().trim().ifBlank { "main" }
            store.gitHubFolder = folderInput.text.toString().trim().ifBlank { "diary" }
            Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
```

- [ ] **Step 3: ビルドが通ることを確認する**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/SettingsActivity.kt app/src/main/res/layout/activity_settings.xml
git commit -m "feat: 設定画面を追加"
```

---

### Task 16: TranscribeActivity（文字起こし→確認・編集→GitHub保存）

**Files:**
- Modify: `app/src/main/java/com/taka0/callrecorder/TranscribeActivity.kt`
- Create: `app/src/main/res/layout/activity_transcribe.xml`

**Interfaces:**
- Consumes: `WhisperClient`（Task 7）、`GitHubClient`（Task 5）、`DiaryMarkdownFormatter`（Task 3）、`SecureSettingsStore`（Task 8）
- Produces: `TranscribeActivity.EXTRA_RECORDING_PATH`（`Intent`の`extra`キー）。Task 14の`MainActivity`から渡される。

- [ ] **Step 1: レイアウトを書く**

`app/src/main/res/layout/activity_transcribe.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:id="@+id/status_text"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="文字起こし中…" />

    <EditText
        android:id="@+id/transcript_input"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:gravity="top"
        android:inputType="textMultiLine" />

    <Button
        android:id="@+id/save_to_github_button"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:enabled="false"
        android:text="GitHubに保存" />
</LinearLayout>
```

- [ ] **Step 2: TranscribeActivityを実装する**

```kotlin
package com.taka0.callrecorder

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

class TranscribeActivity : AppCompatActivity() {

    private lateinit var recordingFile: File
    private lateinit var store: SecureSettingsStore
    private val whisperClient = WhisperClient()
    private val gitHubClient = GitHubClient()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcribe)
        store = SecureSettingsStore(applicationContext)

        val path = intent.getStringExtra(EXTRA_RECORDING_PATH)
        if (path == null) {
            finish()
            return
        }
        recordingFile = File(path)

        transcribe()

        findViewById<Button>(R.id.save_to_github_button).setOnClickListener { saveToGitHub() }
    }

    private fun transcribe() {
        val statusText = findViewById<TextView>(R.id.status_text)
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    whisperClient.transcribe(recordingFile, store.openAiApiKey)
                }
                findViewById<EditText>(R.id.transcript_input).setText(text)
                findViewById<Button>(R.id.save_to_github_button).isEnabled = true
                statusText.text = "文字起こし結果を確認・編集してください"
            } catch (e: WhisperClient.WhisperException) {
                statusText.text = "文字起こしに失敗しました: ${e.message}"
            }
        }
    }

    private fun saveToGitHub() {
        if (!store.isConfigured()) {
            Toast.makeText(this, "先に設定でGitHubトークンとリポジトリを入力してください", Toast.LENGTH_LONG).show()
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            return
        }

        val text = findViewById<EditText>(R.id.transcript_input).text.toString().trim()
        if (text.isEmpty()) return

        val statusText = findViewById<TextView>(R.id.status_text)
        statusText.text = "GitHubに保存しています…"

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val now = java.time.LocalDateTime.now()
                    saveTranscriptAndAudio(now.toLocalDate(), now.toLocalTime(), text)
                }
                statusText.text = "保存しました"
                Toast.makeText(this@TranscribeActivity, "保存しました", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: GitHubClient.GitHubException) {
                statusText.text = "保存に失敗しました: ${e.message}"
            }
        }
    }

    private fun saveTranscriptAndAudio(date: LocalDate, time: LocalTime, text: String) {
        val repo = store.gitHubRepo
        val branch = store.gitHubBranch
        val folder = store.gitHubFolder
        val token = store.gitHubToken

        val audioPath = DiaryMarkdownFormatter.audioFilePath(folder, recordingFile.name)
        gitHubClient.putBinaryFile(
            repo = repo,
            branch = branch,
            path = audioPath,
            contentBytes = recordingFile.readBytes(),
            message = "audio: ${recordingFile.name}",
            sha = null,
            token = token
        )

        val entry = DiaryMarkdownFormatter.entryBlock(time, text, "audio/${recordingFile.name}")
        val diaryPath = DiaryMarkdownFormatter.diaryFilePath(folder, date)
        val existing = gitHubClient.getExistingTextFile(repo, branch, diaryPath, token)

        val newContent = if (existing != null) {
            DiaryMarkdownFormatter.appendedContent(existing.second, entry)
        } else {
            DiaryMarkdownFormatter.newFileContent(date, entry)
        }

        gitHubClient.putTextFile(
            repo = repo,
            branch = branch,
            path = diaryPath,
            content = newContent,
            message = "diary: $date ${time}",
            sha = existing?.first,
            token = token
        )
    }

    companion object {
        const val EXTRA_RECORDING_PATH = "recording_path"
    }
}
```

- [ ] **Step 3: コルーチンの依存を追加する**

`app/build.gradle.kts`の`dependencies`ブロックに追加:
```kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```

- [ ] **Step 4: ビルドが通ることを確認する**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/TranscribeActivity.kt app/src/main/res/layout/activity_transcribe.xml app/build.gradle.kts
git commit -m "feat: 文字起こし・GitHub保存画面を追加"
```

---

### Task 17: PC側自動同期スクリプトとスケジュールタスク登録

**Files:**
- Create: `日記/pull-call-recordings.ps1`（Vault内、既存の`日記/pull-diary.ps1`と同じ場所）

**Interfaces:**
- Consumes: `Git/call-recording-app`リポジトリの`diary/*.md`・`diary/audio/*`（Task 16でアプリがpushする内容）

- [ ] **Step 1: 既存の`pull-diary.ps1`と同じ構成で同期スクリプトを書く**

`日記/pull-call-recordings.ps1`:
```powershell
$repoPath   = "D:\Obsidian Vault for Claude Code\Git\call-recording-app"
$diaryPath  = Join-Path $repoPath "diary"
$outputPath = "D:\Obsidian Vault for Claude Code\日記\通話録音"
$logPath    = Join-Path $PSScriptRoot "pull-call-recordings.log"
$timestamp  = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

if (-not (Test-Path $repoPath)) {
    Add-Content -Path $logPath -Value "[$timestamp] FAILED: repo folder not found ($repoPath)"
    exit 1
}

Set-Location $repoPath
git pull origin main *> $null

if ($LASTEXITCODE -ne 0) {
    Add-Content -Path $logPath -Value "[$timestamp] FAILED (git exit $LASTEXITCODE)"
    exit 1
}

$head = git log -1 --format="%h %s" 2>$null

if (-not (Test-Path $outputPath)) {
    New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$converted = 0

if (Test-Path $diaryPath) {
    Get-ChildItem -Path $diaryPath -Filter "*.md" | ForEach-Object {
        $raw = [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8)

        # remove YAML frontmatter block
        $body = $raw -replace '(?s)^---.*?---\s*', ''

        # remove "## HH:MM" time headings, keep the text under them
        $body = $body -replace '(?m)^##\s*\d{1,2}:\d{2}\s*$', ''

        # collapse runs of blank lines and trim
        $body = $body -replace '(\r?\n){3,}', "`n`n"
        $body = $body.Trim()

        $destFile = Join-Path $outputPath $_.Name
        [System.IO.File]::WriteAllText($destFile, $body, $utf8NoBom)
        $converted++
    }
}

$audioSourcePath = Join-Path $diaryPath "audio"
$audioOutputPath = Join-Path $outputPath "audio"
if (Test-Path $audioSourcePath) {
    if (-not (Test-Path $audioOutputPath)) {
        New-Item -ItemType Directory -Path $audioOutputPath -Force | Out-Null
    }
    Copy-Item -Path (Join-Path $audioSourcePath "*") -Destination $audioOutputPath -Force
}

Add-Content -Path $logPath -Value "[$timestamp] OK: $head (transcribed $converted file(s))"
```

（`voice-diary-app`用の見出し除去処理は今回のMarkdown整形とも一致するためそのまま流用。音声ファイルのコピーのみ追加。）

- [ ] **Step 2: 既存の`VoiceDiaryGitPull`タスクと同じ設定でWindowsタスクスケジューラに登録する**

```powershell
$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "D:\Obsidian Vault for Claude Code\日記\pull-call-recordings.ps1"'
$trigger = New-ScheduledTaskTrigger -Daily -At "07:05"
Register-ScheduledTask -TaskName "CallRecordingGitPull" -Action $action -Trigger $trigger -Description "通話録音アプリのGitHubリポジトリを毎日pullしてVaultに整理する"
```

（`VoiceDiaryGitPull`が07:00開始のため、5分ずらして07:05に設定し同時実行を避ける）

- [ ] **Step 3: タスクが登録されたことを確認する**

Run: `Get-ScheduledTask -TaskName "CallRecordingGitPull" | Select-Object TaskName, State`
Expected: `CallRecordingGitPull` / `Ready`

- [ ] **Step 4: コミット**

```bash
cd "D:/Obsidian Vault for Claude Code"
git -C "Git/call-recording-app" status
```

（`日記/pull-call-recordings.ps1`はVaultルート側のファイルであり、`vault-git-projects-convention`によりVault自体はgit管理しないためコミット不要。ファイルの作成のみで完了とする。）

---

### Task 18: 実機ビルド・インストール・実際の通話での動作確認（手動）

**Files:**
- なし（既存ファイルのビルド・実機検証のみ）

**Interfaces:**
- Consumes: Task 1〜17で作成した全コンポーネント

このタスクはPixel実機がないと検証できないため、手動で行う。

- [x] **Step 1: リリース用ではなくデバッグAPKをビルドする**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `app/build/outputs/apk/debug/app-debug.apk` が生成される

- [x] **Step 2: USBデバッグを有効にしたPixelをPCに接続し、ADB経由でインストールする**

```bash
adb install -r "app/build/outputs/apk/debug/app-debug.apk"
```

Expected: `Success`

- [x] **Step 3: アプリを起動し、権限（マイク・電話の状態・通知）をすべて許可する**

- [x] **Step 4: 設定画面でOpenAI APIキー・GitHub Personal Access Token・リポジトリ（`<ユーザー名>/call-recording-app`）を入力して保存する**

- [x] **Step 5: 実際に発信または着信し、通話中にスピーカーホンが自動でONになること、通話終了後に録音一覧に新しいファイルが増えることを確認する**

  スピーカーホンの自動ONは実機では機能しないことを確認した（電話アプリ側に上書きされる。詳細は下記の実機検証記録、および設計書の「実機検証で判明した追加の制約」を参照）。録音一覧へのファイル追加自体は正常に動作する。

  以下の条件は今回のセッションでは未検証（今後、実際の日常利用の中で確認する）:

  - [ ] アプリを最近使ったアプリ（Recents）からスワイプで消した状態で着信し、録音されること
  - [ ] 画面ロック状態で着信し、録音されること
  - [ ] 着信を拒否した場合／不在着信の場合に、アプリがクラッシュせず（ForegroundServiceDidNotStartInTime等）、空の録音ファイルも作られないこと
  - [ ] バッテリー最適化の除外を許可していない場合に、バックグラウンドからの録音がどう振る舞うかも確認する（機種依存）

- [x] **Step 6: 録音を再生し、自分の声と相手の声の両方が聞き取れる音質かを確認する（聞き取りにくい場合は通話音量を上げる運用でカバーする）**

  手動でスピーカーホンをONにした状態で、自分・相手の声とも問題なく聞き取れる音質であることを確認した。詳細は下記の実機検証記録を参照。

- [ ] **Step 7: 「文字起こし」ボタンを押し、Whisperでのテキスト化→編集→GitHub保存が最後まで通り、GitHub上の`diary/YYYY-MM-DD.md`と`diary/audio/`にファイルが増えていることを確認する**

- [ ] **Step 8: 翌朝（または`Start-ScheduledTask -TaskName "CallRecordingGitPull"`で手動実行）、`日記/通話録音`フォルダにテキストと音声がpullされていることを確認する**

- [x] **Step 9: 動作確認の結果を`docs/superpowers/plans/2026-08-05-call-recording-app.md`の末尾に追記し、コミットする**

```bash
git add docs/superpowers/plans/2026-08-05-call-recording-app.md
git commit -m "docs: 実機動作確認結果を記録"
```

---

## 実機検証記録（2026-08-06、Pixel 9a / Android 16）

Task 18のStep 1〜6を実施。当初計画（`AudioSource.MIC` + `BroadcastReceiver`による通話検知）では**録音ファイルは作られるが中身が完全な無音**という問題に直面し、原因切り分けと対応に多くの試行錯誤を要した。時系列で記録する。

### 発生した問題と対応

1. **設定・文字起こし画面のボタンがタップできない** — targetSdk 35+のedge-to-edge強制描画で、ステータスバー下に隠れていた。`WindowInsetsUtil`を追加し、各画面のルートに システムバー分のpaddingを適用して解決（コミット`7dc31ec`）。

2. **録音ファイルが作られない（`RecordingService`は起動するが録音されない）** — logcatに`Foreground service started from background can not have location/camera/microphone access`。`BroadcastReceiver`（`uidState: RCVR`）からの起動では、Android 12+の制限でマイクへの実アクセスが拒否されると判明。有効なアクセシビリティサービスを持つと回避できるとされているため、何もしない`CallRecorderAccessibilityService`を追加（コミット`69d8d72`）。→ サービス起動はできるようになったが、録音ファイルは相変わらず無音（-91dB、ほぼ完全なデジタル無音）。

3. **スピーカーホンが自動でONにならない** — `AudioManager.isSpeakerphoneOn = true`を呼んでも、通話開始直後に電話アプリ側の音声ルーティングに上書きされ、OFFに戻ることをログで確認。

4. **無音の原因調査** — 実機に別途インストールされていた市販の通話録音アプリ（Cube Call Recorder）が同じ端末上で実際に音声を録音できていることを確認（ffmpegの`volumedetect`で比較：CCRは-30dB前後の実音声、当アプリは-91dBの無音）。CCRの権限一覧を比較し、`SYSTEM_ALERT_WINDOW`（他のアプリの上に重ねて表示）の有無が差分と仮説を立てたが、実装・検証した結果、無音化の直接の原因ではないと判明（オーバーレイ表示は成功していたが、依然としてマイクアクセス拒否の警告ログが出た）。

5. **通話検知をアクセシビリティサービス内に移す** — CCRのログでは録音開始時の呼び出し元プロセスが`uidState: BFGS`（常時バインドされたForeground Service）だったのに対し、当アプリは`uidState: RCVR`だった。通話状態の検知（`TelephonyManager`監視）と`RecordingService`の起動を、`BroadcastReceiver`（`CallStateReceiver`、削除）から`CallRecorderAccessibilityService`内に移動（コミット`5c3b65c`）。→ 「マイクアクセス拒否」の警告ログは消えたが、録音は依然として無音（-inf dB、全サンプルゼロ）。

6. **`AudioSource`を`VOICE_RECOGNITION`に変更** — 上記5までの対応でもマイクアクセスの許可自体は得られるようになったが、実際に渡される音声データが常にゼロだったことから、`AudioSource.MIC`自体が通話中は意図的に無音化されている（盗聴防止のためとみられるOS側の仕様）という仮説に至った。`AudioSource.VOICE_RECOGNITION`に変更したところ、実際の音声（自分の声、-54dB前後）が録音されることを確認。手動でスピーカーホンをONにした状態では、相手の声も含めて問題なく聞き取れる音質になることを実機で確認した（ユーザー本人による最終確認済み）。

### 最終的な結論・現在の実装

- 通話検知・録音制御は`CallRecorderAccessibilityService`内で行う（`CallStateReceiver`は削除）。
- 録音の音声ソースは`AudioSource.MIC`ではなく`AudioSource.VOICE_RECOGNITION`を使用する。
- スピーカーホンの自動ONはコード上維持しているが、確実に機能する保証はない。**相手の声が聞き取りにくい場合は、通話中に手動でスピーカーホンをONにする運用でカバーする**（市販アプリでも同様の制約があることを確認済みであり、本アプリ固有の欠陥ではない）。
- `RecordingOverlay`（`SYSTEM_ALERT_WINDOW`によるオーバーレイ表示）は無音化の直接の解決策ではなかったが、副作用もないため実装は残している。

### 未実施

Task 18のStep 7（文字起こし→GitHub保存）・Step 8（PC側自動同期の確認）は次回のセッションで実施する。
