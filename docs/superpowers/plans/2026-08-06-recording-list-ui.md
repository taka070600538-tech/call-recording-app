# 録音一覧画面UI刷新 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 録音一覧画面（`MainActivity`）で、時刻が見切れる問題を解消し、行タップで録音を選択できるようにし、再生／文字起こし／削除ボタンを画面上部に集約し、GitHub保存済みの録音には「保存済」バッジを表示する。

**Architecture:** 各行のボタンを廃止し、画面上部の共通アクションバーに1セットだけ配置する。上部ボタンは`RecordingsAdapter`が保持する「選択中の録音」に対して動作する。GitHub保存済みかどうかはSharedPreferencesベースの新規`SavedRecordingsStore`で端末内に記録する。

**Tech Stack:** Kotlin, AndroidX RecyclerView, Robolectric（テスト）, 既存の`.\gradlew.bat`ビルド

## Global Constraints

- 保存済み判定はGitHubへの問い合わせではなく端末内SharedPreferences（`SavedRecordingsStore`）で行う。この機能導入前に保存済みだった録音は遡ってバッジが付かない（ユーザー承認済みの既知の制約）
- 選択方式は「行タップでハイライト」。ラジオボタン等の追加UIは使わない
- 「保存済」バッジは時刻ラベルの右側に表示する
- テストは`.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.<クラス名>"`で実行する（既存タスクと同じコマンド形式）
- Robolectricテストは`@Config(sdk = [34])`を付与する（既存テストと同じ設定）
- ボタン・ラベルの文言は既存コードと同じくstrings.xmlを介さず`android:text`にハードコードする（既存の`item_recording.xml`・`activity_main.xml`の方式を踏襲）

---

### Task 1: SavedRecordingsStore（保存済みファイル名の記録）

**Files:**
- Create: `app/src/main/java/com/taka0/callrecorder/SavedRecordingsStore.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/SavedRecordingsStoreTest.kt`

**Interfaces:**
- Consumes: なし（`android.content.Context` / `android.content.SharedPreferences`のみ）
- Produces: `SavedRecordingsStore(prefs: SharedPreferences)`、`SavedRecordingsStore(context: Context)`、`fun all(): Set<String>`、`fun markSaved(fileName: String)`。Task 2（`RecordingsAdapter`のsavedFileNames判定）とTask 3・4（`MainActivity`・`TranscribeActivity`からの呼び出し）が利用する。

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/com/taka0/callrecorder/SavedRecordingsStoreTest.kt`:

```kotlin
package com.taka0.callrecorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedRecordingsStoreTest {

    private fun newStore(): SavedRecordingsStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_saved_recordings_${System.nanoTime()}", Context.MODE_PRIVATE)
        return SavedRecordingsStore(prefs)
    }

    @Test
    fun `all is empty by default`() {
        val store = newStore()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `markSaved adds the file name to all`() {
        val store = newStore()

        store.markSaved("2026-08-06-135135.m4a")

        assertEquals(setOf("2026-08-06-135135.m4a"), store.all())
    }

    @Test
    fun `markSaved accumulates multiple file names`() {
        val store = newStore()

        store.markSaved("a.m4a")
        store.markSaved("b.m4a")

        assertEquals(setOf("a.m4a", "b.m4a"), store.all())
    }

    @Test
    fun `persists across instances backed by the same preferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_saved_recordings_shared", Context.MODE_PRIVATE)
        SavedRecordingsStore(prefs).markSaved("2026-08-06-135135.m4a")

        val reloaded = SavedRecordingsStore(prefs)

        assertEquals(setOf("2026-08-06-135135.m4a"), reloaded.all())
    }
}
```

- [ ] **Step 2: テストを実行して失敗を確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.SavedRecordingsStoreTest"`
Expected: `SavedRecordingsStore`クラスが存在せずコンパイルエラーになる

- [ ] **Step 3: 実装する**

`app/src/main/java/com/taka0/callrecorder/SavedRecordingsStore.kt`:

```kotlin
package com.taka0.callrecorder

import android.content.Context
import android.content.SharedPreferences

class SavedRecordingsStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences("call_recorder_saved_recordings", Context.MODE_PRIVATE)
    )

    fun all(): Set<String> = prefs.getStringSet(KEY_SAVED_FILE_NAMES, emptySet()) ?: emptySet()

    fun markSaved(fileName: String) {
        val updated = all().toMutableSet().apply { add(fileName) }
        prefs.edit().putStringSet(KEY_SAVED_FILE_NAMES, updated).apply()
    }

    companion object {
        private const val KEY_SAVED_FILE_NAMES = "saved_file_names"
    }
}
```

- [ ] **Step 4: テストを実行して成功を確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.SavedRecordingsStoreTest"`
Expected: `PASS`（4件すべて）

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/SavedRecordingsStore.kt app/src/test/java/com/taka0/callrecorder/SavedRecordingsStoreTest.kt
git commit -m "feat: GitHub保存済みファイル名を記録するSavedRecordingsStoreを追加"
```

---

### Task 2: 一覧行の選択ハイライト・保存済バッジ・RecordingsAdapter刷新

**Files:**
- Modify: `app/src/main/res/layout/item_recording.xml`
- Create: `app/src/main/res/drawable/bg_recording_item.xml`
- Modify: `app/src/main/java/com/taka0/callrecorder/RecordingsAdapter.kt`
- Modify: `app/src/main/java/com/taka0/callrecorder/MainActivity.kt`（コンパイルを保つための最小限の呼び出し側修正。上部アクションバーの配線はTask 3で行う）
- Test: `app/src/test/java/com/taka0/callrecorder/RecordingsAdapterTest.kt`

**Interfaces:**
- Consumes: Task 1の`SavedRecordingsStore`（`MainActivity`側での利用）
- Produces: `RecordingsAdapter(recordings: List<Recording>, savedFileNames: Set<String>, onSelectionChanged: (Recording?) -> Unit)`、`fun getSelected(): Recording?`、`fun updateRecordings(newRecordings: List<Recording>, newSavedFileNames: Set<String>)`。Task 3（`MainActivity`の上部ボタン配線）が利用する。

- [ ] **Step 1: レイアウトとdrawableを先に用意する**

`app/src/main/res/drawable/bg_recording_item.xml`（新規）:

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_activated="true" android:drawable="?attr/colorControlHighlight" />
    <item android:drawable="@android:color/transparent" />
</selector>
```

`app/src/main/res/layout/item_recording.xml`（全置き換え）:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:background="@drawable/bg_recording_item"
    android:clickable="true"
    android:focusable="true"
    android:padding="16dp">

    <TextView
        android:id="@+id/recording_label"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:maxLines="1"
        android:ellipsize="end"
        android:textSize="16sp" />

    <TextView
        android:id="@+id/saved_badge"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="8dp"
        android:layout_gravity="center_vertical"
        android:text="保存済"
        android:textSize="14sp"
        android:visibility="gone" />
</LinearLayout>
```

- [ ] **Step 2: 失敗するテストを書く**

`app/src/test/java/com/taka0/callrecorder/RecordingsAdapterTest.kt`:

```kotlin
package com.taka0.callrecorder

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingsAdapterTest {

    private fun themedContext(): Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_CallRecorder)

    private fun recording(nameSeed: String): Recording {
        val file = File.createTempFile(nameSeed, ".m4a").apply { deleteOnExit() }
        return Recording(file, LocalDateTime.of(2026, 8, 6, 13, 51))
    }

    private fun buildRecyclerView(adapter: RecordingsAdapter): RecyclerView {
        val context = themedContext()
        val recyclerView = RecyclerView(context)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(0, 0, 1080, 2000)
        return recyclerView
    }

    @Test
    fun `tapping a row selects it and notifies the callback`() {
        val a = recording("a")
        val b = recording("b")
        var selected: Recording? = null
        val adapter = RecordingsAdapter(listOf(a, b), emptySet()) { selected = it }
        val recyclerView = buildRecyclerView(adapter)

        recyclerView.findViewHolderForAdapterPosition(1)!!.itemView.performClick()

        assertEquals(b, selected)
        assertEquals(b, adapter.getSelected())
        assertTrue(recyclerView.findViewHolderForAdapterPosition(1)!!.itemView.isActivated)
        assertFalse(recyclerView.findViewHolderForAdapterPosition(0)!!.itemView.isActivated)
    }

    @Test
    fun `updateRecordings clears selection when the selected recording is removed`() {
        val a = recording("a")
        val b = recording("b")
        var selected: Recording? = null
        val adapter = RecordingsAdapter(listOf(a, b), emptySet()) { selected = it }
        buildRecyclerView(adapter).findViewHolderForAdapterPosition(0)!!.itemView.performClick()
        assertEquals(a, adapter.getSelected())

        adapter.updateRecordings(listOf(b), emptySet())

        assertNull(adapter.getSelected())
        assertNull(selected)
    }

    @Test
    fun `updateRecordings keeps selection when the selected recording is still present`() {
        val a = recording("a")
        val b = recording("b")
        val adapter = RecordingsAdapter(listOf(a, b), emptySet()) { }
        buildRecyclerView(adapter).findViewHolderForAdapterPosition(0)!!.itemView.performClick()

        adapter.updateRecordings(listOf(a, b), emptySet())

        assertEquals(a, adapter.getSelected())
    }

    @Test
    fun `saved badge is visible only for file names in savedFileNames`() {
        val a = recording("a")
        val b = recording("b")
        val adapter = RecordingsAdapter(listOf(a, b), setOf(a.file.name)) { }
        val recyclerView = buildRecyclerView(adapter)

        val badgeA = recyclerView.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(R.id.saved_badge)
        val badgeB = recyclerView.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<View>(R.id.saved_badge)

        assertEquals(View.VISIBLE, badgeA.visibility)
        assertEquals(View.GONE, badgeB.visibility)
    }
}
```

- [ ] **Step 3: テストを実行して失敗を確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.RecordingsAdapterTest"`
Expected: `RecordingsAdapter`の現行コンストラクタ（`onPlay`/`onTranscribe`/`onDelete`引数）と一致せずコンパイルエラーになる

- [ ] **Step 4: RecordingsAdapterを実装する**

`app/src/main/java/com/taka0/callrecorder/RecordingsAdapter.kt`（全置き換え）:

```kotlin
package com.taka0.callrecorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.time.format.DateTimeFormatter

class RecordingsAdapter(
    private var recordings: List<Recording>,
    private var savedFileNames: Set<String>,
    private val onSelectionChanged: (Recording?) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

    private val labelFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private var selectedRecording: Recording? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.recording_label)
        val savedBadge: TextView = view.findViewById(R.id.saved_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recording = recordings[position]
        holder.label.text = recording.recordedAt.format(labelFormatter)
        holder.savedBadge.visibility = if (recording.file.name in savedFileNames) View.VISIBLE else View.GONE
        holder.itemView.isActivated = recording == selectedRecording
        holder.itemView.setOnClickListener {
            selectedRecording = recording
            notifyDataSetChanged()
            onSelectionChanged(recording)
        }
    }

    override fun getItemCount(): Int = recordings.size

    fun getSelected(): Recording? = selectedRecording

    fun updateRecordings(newRecordings: List<Recording>, newSavedFileNames: Set<String>) {
        recordings = newRecordings
        savedFileNames = newSavedFileNames
        if (selectedRecording != null && selectedRecording !in newRecordings) {
            selectedRecording = null
            onSelectionChanged(null)
        }
        notifyDataSetChanged()
    }
}
```

- [ ] **Step 5: MainActivityの呼び出し箇所をコンパイルが通るように最小修正する**

`app/src/main/java/com/taka0/callrecorder/MainActivity.kt`の`onCreate`内、アダプタ生成箇所を次のように置き換える（上部ボタンの配線はTask 3で行うため、ここでは仮のno-opコールバックを渡す）。

変更前:

```kotlin
        adapter = RecordingsAdapter(
            recordings = repository.list(),
            onPlay = ::playRecording,
            onTranscribe = ::openTranscribe,
            onDelete = ::deleteRecording
        )
```

変更後:

```kotlin
        adapter = RecordingsAdapter(
            recordings = repository.list(),
            savedFileNames = SavedRecordingsStore(this).all(),
            onSelectionChanged = { }
        )
```

`onResume`内の`adapter.updateRecordings(repository.list())`を次に置き換える:

```kotlin
        adapter.updateRecordings(repository.list(), SavedRecordingsStore(this).all())
```

`deleteRecording`内の`adapter.updateRecordings(repository.list())`（`AlertDialog`の`setPositiveButton`コールバック内）も同様に次に置き換える:

```kotlin
                repository.delete(recording)
                adapter.updateRecordings(repository.list(), SavedRecordingsStore(this).all())
```

- [ ] **Step 6: テストを実行して成功を確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.RecordingsAdapterTest"`
Expected: `PASS`（4件すべて）

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.MainActivityTest"`
Expected: `PASS`（既存の1件。コンパイルが崩れていないことの確認）

- [ ] **Step 7: コミット**

```bash
git add app/src/main/res/layout/item_recording.xml app/src/main/res/drawable/bg_recording_item.xml app/src/main/java/com/taka0/callrecorder/RecordingsAdapter.kt app/src/main/java/com/taka0/callrecorder/MainActivity.kt app/src/test/java/com/taka0/callrecorder/RecordingsAdapterTest.kt
git commit -m "feat: 録音一覧の行選択ハイライトと保存済バッジを追加"
```

---

### Task 3: 画面上部アクションバー（再生／文字起こし／削除の集約）

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/taka0/callrecorder/MainActivity.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/MainActivityTest.kt`

**Interfaces:**
- Consumes: Task 2の`RecordingsAdapter.getSelected()` / `onSelectionChanged`
- Produces: なし（末端のUI配線）

- [ ] **Step 1: レイアウトに上部アクションバーを追加する**

`app/src/main/res/layout/activity_main.xml`の`settings_button`と`recordings_list`の間に、次の`LinearLayout`を追加する。

```xml
    <LinearLayout
        android:id="@+id/action_bar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <Button
            android:id="@+id/action_play"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:enabled="false"
            android:text="再生" />

        <Button
            android:id="@+id/action_transcribe"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:enabled="false"
            android:text="文字起こし" />

        <Button
            android:id="@+id/action_delete"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:enabled="false"
            android:text="削除" />
    </LinearLayout>
```

- [ ] **Step 2: 失敗するテストを書く**

`app/src/test/java/com/taka0/callrecorder/MainActivityTest.kt`に次のテストを追加する（既存の`launches and shows an empty recordings list`テストはそのまま残す）。

```kotlin
    @Test
    fun `action buttons start disabled and become enabled after selecting a recording`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val recordingsDir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        File(recordingsDir, "2026-08-06-135135.m4a").createNewFile()

        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().get()

        val playButton = activity.findViewById<Button>(R.id.action_play)
        val transcribeButton = activity.findViewById<Button>(R.id.action_transcribe)
        val deleteButton = activity.findViewById<Button>(R.id.action_delete)
        assertFalse(playButton.isEnabled)
        assertFalse(transcribeButton.isEnabled)
        assertFalse(deleteButton.isEnabled)

        val recyclerView = activity.findViewById<RecyclerView>(R.id.recordings_list)
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(0, 0, 1080, 2000)
        recyclerView.findViewHolderForAdapterPosition(0)!!.itemView.performClick()

        assertTrue(playButton.isEnabled)
        assertTrue(transcribeButton.isEnabled)
        assertTrue(deleteButton.isEnabled)
    }
```

ファイル冒頭のimportに次を追加する（`assertTrue`・`RecyclerView`は既存のimportをそのまま使うため重複させない）:

```kotlin
import android.view.View
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import java.io.File
```

- [ ] **Step 3: テストを実行して失敗を確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.MainActivityTest"`
Expected: `action buttons start disabled...`が`isEnabled`の初期値不一致、またはコールバック未配線によるボタンの有効化漏れでFAILする

- [ ] **Step 4: MainActivityを配線する**

`app/src/main/java/com/taka0/callrecorder/MainActivity.kt`を次のように変更する。

`repository`/`adapter`宣言の下に`savedRecordingsStore`を追加:

```kotlin
    private lateinit var repository: RecordingRepository
    private lateinit var adapter: RecordingsAdapter
    private lateinit var savedRecordingsStore: SavedRecordingsStore
```

`onCreate`内、`repository = RecordingRepository(recordingsDir)`の直後に追加:

```kotlin
        savedRecordingsStore = SavedRecordingsStore(this)
```

Task 2で仮置きしたアダプタ生成を次に置き換える:

```kotlin
        adapter = RecordingsAdapter(
            recordings = repository.list(),
            savedFileNames = savedRecordingsStore.all(),
            onSelectionChanged = ::updateActionButtons
        )
```

`findViewById<RecyclerView>(R.id.recordings_list).apply { ... }`の直後に追加:

```kotlin
        findViewById<android.widget.Button>(R.id.action_play).setOnClickListener {
            adapter.getSelected()?.let(::playRecording)
        }
        findViewById<android.widget.Button>(R.id.action_transcribe).setOnClickListener {
            adapter.getSelected()?.let(::openTranscribe)
        }
        findViewById<android.widget.Button>(R.id.action_delete).setOnClickListener {
            adapter.getSelected()?.let(::deleteRecording)
        }
```

`playRecording`関数の直前に新しい関数を追加:

```kotlin
    private fun updateActionButtons(recording: Recording?) {
        val enabled = recording != null
        findViewById<android.widget.Button>(R.id.action_play).isEnabled = enabled
        findViewById<android.widget.Button>(R.id.action_transcribe).isEnabled = enabled
        findViewById<android.widget.Button>(R.id.action_delete).isEnabled = enabled
    }
```

Task 2で`SavedRecordingsStore(this).all()`と直書きした2箇所（`onResume`と`deleteRecording`）を、フィールド`savedRecordingsStore`を使う形に置き換える:

```kotlin
        adapter.updateRecordings(repository.list(), savedRecordingsStore.all())
```

- [ ] **Step 5: テストを実行して成功を確認する**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.taka0.callrecorder.MainActivityTest"`
Expected: `PASS`（2件とも）

- [ ] **Step 6: コミット**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/java/com/taka0/callrecorder/MainActivity.kt app/src/test/java/com/taka0/callrecorder/MainActivityTest.kt
git commit -m "feat: 再生・文字起こし・削除ボタンを画面上部に集約"
```

---

### Task 4: TranscribeActivityで保存成功時にSavedRecordingsStoreへ記録する

**Files:**
- Modify: `app/src/main/java/com/taka0/callrecorder/TranscribeActivity.kt`

**Interfaces:**
- Consumes: Task 1の`SavedRecordingsStore(context: Context)` / `fun markSaved(fileName: String)`
- Produces: なし

`TranscribeActivity`は`WhisperClient`・`GitHubClient`を直接インスタンス化しておりモック差し替えの仕組みがなく、既存コードにもこのクラスの自動テストは存在しない（`GitHubClient`・`WhisperClient`自体は個別にテスト済み）。本タスクも同じ方針を踏襲し、自動テストは追加せずTask 5の実機確認で検証する。

- [ ] **Step 1: 保存成功時にmarkSavedを呼ぶ**

`app/src/main/java/com/taka0/callrecorder/TranscribeActivity.kt`の`saveToGitHub()`内、`saveTranscriptAndAudio(...)`呼び出しの直後（`statusText.text = "保存しました"`の直前）に1行追加する。

変更前:

```kotlin
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Transcription is manual and can happen long after the call, so the diary
                    // entry must be filed under the recording's time, not the save time.
                    saveTranscriptAndAudio(recordedAt.toLocalDate(), recordedAt.toLocalTime(), text)
                }
                statusText.text = "保存しました"
```

変更後:

```kotlin
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Transcription is manual and can happen long after the call, so the diary
                    // entry must be filed under the recording's time, not the save time.
                    saveTranscriptAndAudio(recordedAt.toLocalDate(), recordedAt.toLocalTime(), text)
                }
                SavedRecordingsStore(applicationContext).markSaved(recordingFile.name)
                statusText.text = "保存しました"
```

- [ ] **Step 2: 全体のビルドが通ることを確認する**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/TranscribeActivity.kt
git commit -m "feat: GitHub保存成功時にSavedRecordingsStoreへ記録"
```

---

### Task 5: 実機動作確認（手動）

**Files:**
- なし（既存ファイルのビルド・実機検証のみ）

**Interfaces:**
- Consumes: Task 1〜4で作成した全コンポーネント

- [ ] **Step 1: デバッグAPKをビルドし実機にインストールする**

Run: `.\gradlew.bat :app:assembleDebug` に続けて `adb install -r "app/build/outputs/apk/debug/app-debug.apk"`
Expected: `BUILD SUCCESSFUL` / `Success`

- [x] **Step 2: 一覧画面で時刻が1行で見切れずに表示されることを確認する**

  ボタンが各行から上部アクションバーに移動したことで、時刻ラベルが行の全幅を使えるようになり、見切れなく1行で表示されることを確認した。

- [x] **Step 3: 行をタップして選択状態（ハイライト）になり、画面上部の再生・文字起こし・削除ボタンが有効化されることを確認する**

  タップした行がグレーの背景でハイライトされ、上部の3ボタンが同時に有効化（紫色の活性表示）されることを確認した。

- [x] **Step 4: 別の行をタップすると、選択ハイライトが新しい行に移ることを確認する**

  最初に選択した行のハイライトが消え、新しくタップした行にハイライトが移ることを確認した。

- [x] **Step 5: すでにGitHub保存済みの録音（2026-08-06 13:51・14:07の2件）に「保存済」バッジが付いていないことを確認する（既知の制約どおりであることの確認）**

  両方の録音とも「保存済」バッジが表示されないことを確認した。設計どおりの既知の制約（この機能の導入前に保存済みだった録音は遡って判定できない）。

- [x] **Step 6: 未保存の録音を選択して「文字起こし」→GitHub保存まで完了させ、一覧画面に戻った際にその録音の時刻の右側に「保存済」バッジが表示されることを確認する**

  2026-08-06 13:28の録音で文字起こし→GitHub保存を実行し、一覧画面に戻ると同じ録音の時刻の右側に「保存済」バッジが表示されることを確認した。選択状態（ハイライト）も維持されていた。

- [x] **Step 7: 録音を選択して「削除」ボタンを押し、確認ダイアログで削除すると、一覧から消えると同時に上部ボタンが再び無効化されることを確認する**

  Step 6で保存した録音を選択し「削除」ボタン→確認ダイアログ→「削除」の操作で、一覧から即座に消え、同時に上部3ボタンが非活性状態に戻ることを確認した。

- [x] **Step 8: 動作確認の結果を本ファイルの末尾に追記し、コミットする**

```bash
git add docs/superpowers/plans/2026-08-06-recording-list-ui.md
git commit -m "docs: 録音一覧UI刷新の実機動作確認結果を記録"
```

---

## 実機動作確認記録（2026-08-06、Pixel 9a / Android 16）

Task 5のStep 1〜7をすべて実機で確認し、いずれも計画どおりに動作した。ADB経由でのUI階層取得（`uiautomator dump`）とタップ操作により検証した。

デバッグAPKのビルド・インストール後、`GITHUBに保存`ボタンの座標を画面のスケール表示から目測した際、`TranscribeActivity`の`transcript_input`（EditText）が画面のほとんどの高さを占めるレイアウトであることに気づかず誤タップしてキーボードが開く事象があったが、`uiautomator dump`で正確な`bounds`を取得することで解決した。UI自体の不具合ではない。
