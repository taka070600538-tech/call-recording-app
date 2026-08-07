# 録音一覧画面への電話番号表示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** アプリ本体の録音一覧画面（`MainActivity`）の各行に、解決済みの電話番号をラベルへ追記表示する。

**Architecture:** 既存の`CallMetadataStore`（録音ファイル名→電話番号のSharedPreferencesストア）に全件読み出し用の`all()`を追加し、`MainActivity.onResume()`／削除後の再読み込み箇所でこれを読み、`RecordingsAdapter`へ渡してラベルに追記する。新規の保存処理・新しいUIビューは追加しない。

**Tech Stack:** Kotlin, Android SharedPreferences, RecyclerView, JUnit + Robolectric（既存のテストスタック）

## Global Constraints

- 表示するのは電話番号のみ（相手の名前は表示しない。名前解決はPC側`電話帳.md`のみで行う既存方針を維持）。
- 番号が未解決の場合はアプリ内では「不明」と表示せず、従来どおり日時のみを表示する。
- 一覧の更新は`onResume()`のタイミングのみ（既存の「保存済」バッジと同じカデンス）。画面を開いたままのリアルタイム更新は行わない。
- 新しいレイアウト・ビューは追加しない。既存の`recording_label`（1行、`maxLines="1"`, `ellipsize="end"`）にそのまま収める。
- 参照設計書: `docs/superpowers/specs/2026-08-07-recording-list-phone-number-design.md`

---

### Task 1: `CallMetadataStore.all()`の追加

**Files:**
- Modify: `app/src/main/java/com/taka0/callrecorder/CallMetadataStore.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/CallMetadataStoreTest.kt`

**Interfaces:**
- Consumes: 既存の`CallMetadataStore(context: Context)` / `CallMetadataStore(prefs: SharedPreferences)`コンストラクタ、既存の`save(fileName: String, phoneNumber: String?)`。
- Produces: `fun all(): Map<String, String>` — 保存済みで**かつ値がnullでない**エントリのみを、`ファイル名 -> 電話番号`のMapとして返す。`save(fileName, null)`で保存された（未解決のまま保存された）エントリは含めない。

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/com/taka0/callrecorder/CallMetadataStoreTest.kt`の末尾（クラスの`}`の直前）に追加:

```kotlin
    @Test
    fun `all returns every saved non-null entry as a map`() {
        val store = newStore()
        store.save("2026-08-06-135135.m4a", "08088004673")
        store.save("2026-08-07-101334.m4a", "08089004673")

        assertEquals(
            mapOf(
                "2026-08-06-135135.m4a" to "08088004673",
                "2026-08-07-101334.m4a" to "08089004673"
            ),
            store.all()
        )
    }

    @Test
    fun `all excludes entries saved with a null phone number`() {
        val store = newStore()
        store.save("2026-08-06-135135.m4a", "08088004673")
        store.save("2026-08-07-095611.m4a", null)

        assertEquals(mapOf("2026-08-06-135135.m4a" to "08088004673"), store.all())
    }

    @Test
    fun `all returns an empty map when nothing was saved`() {
        val store = newStore()

        assertEquals(emptyMap<String, String>(), store.all())
    }
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.taka0.callrecorder.CallMetadataStoreTest"`
Expected: FAIL（`all()`が未定義でコンパイルエラー、またはメソッドが見つからない）

- [ ] **Step 3: 最小実装を書く**

`app/src/main/java/com/taka0/callrecorder/CallMetadataStore.kt`の`get(fileName: String): String? = ...`の下に追加:

```kotlin
    fun all(): Map<String, String> =
        prefs.all.mapNotNull { (fileName, value) -> (value as? String)?.let { fileName to it } }.toMap()
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.taka0.callrecorder.CallMetadataStoreTest"`
Expected: PASS（既存4件＋新規3件＝7件すべて成功）

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/CallMetadataStore.kt app/src/test/java/com/taka0/callrecorder/CallMetadataStoreTest.kt
git commit -m "feat: CallMetadataStoreに保存済み電話番号を全件取得するall()を追加"
```

---

### Task 2: `RecordingsAdapter`に電話番号表示を追加

**Files:**
- Modify: `app/src/main/java/com/taka0/callrecorder/RecordingsAdapter.kt`
- Test: `app/src/test/java/com/taka0/callrecorder/RecordingsAdapterTest.kt`

**Interfaces:**
- Consumes: Task 1の`CallMetadataStore.all(): Map<String, String>`と同じ形（呼び出し元から`Map<String, String>`として渡ってくる想定。`RecordingsAdapter`自身は`CallMetadataStore`に依存しない — 既存の`savedFileNames: Set<String>`と同じく、呼び出し元が読み出した値を渡すだけの薄いパラメータ）。
- Produces: `RecordingsAdapter`のコンストラクタと`updateRecordings()`に`phoneNumbers: Map<String, String>`パラメータが追加される（Task 3で`MainActivity`から渡す）。

**現状のコード**（変更前、全文は`app/src/main/java/com/taka0/callrecorder/RecordingsAdapter.kt`参照）:

```kotlin
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

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/com/taka0/callrecorder/RecordingsAdapterTest.kt`の既存テストは全て`RecordingsAdapter(recordings, savedFileNames) { ... }`という2引数のコンストラクタ呼び出しをしている（今回`phoneNumbers`が必須引数として増えるため、既存の全呼び出しがコンパイルエラーになる）。まず既存の全呼び出し箇所（4箇所、45-101行目付近の各`@Test`内）を`RecordingsAdapter(recordings, savedFileNames, emptyMap())`のように第3引数`emptyMap()`を追加する形に更新する。

その上で、クラス末尾（最後の`}`の直前）に新規テストを追加:

```kotlin
    @Test
    fun `label includes the phone number when one is known for that file`() {
        val a = recording("a")
        val b = recording("b")
        val adapter = RecordingsAdapter(listOf(a, b), emptySet(), mapOf(a.file.name to "08089004673")) { }
        val recyclerView = buildRecyclerView(adapter)

        val labelA = recyclerView.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<android.widget.TextView>(R.id.recording_label)
        val labelB = recyclerView.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<android.widget.TextView>(R.id.recording_label)

        assertTrue(labelA.text.toString().endsWith("08089004673"))
        assertFalse(labelB.text.toString().contains("08089004673"))
    }

    @Test
    fun `label is date-only when no phone number is known for that file`() {
        val a = recording("a")
        val adapter = RecordingsAdapter(listOf(a), emptySet(), emptyMap()) { }
        val recyclerView = buildRecyclerView(adapter)

        val labelA = recyclerView.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<android.widget.TextView>(R.id.recording_label)

        assertEquals("2026-08-06 13:51", labelA.text.toString())
    }
```

（`recording("a")`の日時は`LocalDateTime.of(2026, 8, 6, 13, 51)`固定なので、`labelFormatter`のパターン`yyyy-MM-dd HH:mm`から`"2026-08-06 13:51"`になる。既存の`recording()`ヘルパー・`buildRecyclerView()`ヘルパーはそのまま使う。）

- [ ] **Step 2: テストが失敗することを確認する**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.taka0.callrecorder.RecordingsAdapterTest"`
Expected: FAIL（コンストラクタの引数数不一致でコンパイルエラー）

- [ ] **Step 3: 最小実装を書く**

`RecordingsAdapter.kt`を以下のように変更する（`phoneNumbers`パラメータの追加、`onBindViewHolder`でのラベル組み立て変更、`updateRecordings`への引数追加）:

```kotlin
class RecordingsAdapter(
    private var recordings: List<Recording>,
    private var savedFileNames: Set<String>,
    private var phoneNumbers: Map<String, String>,
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
        val baseLabel = recording.recordedAt.format(labelFormatter)
        val phoneNumber = phoneNumbers[recording.file.name]
        holder.label.text = if (phoneNumber != null) "$baseLabel ・ $phoneNumber" else baseLabel
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

    fun updateRecordings(newRecordings: List<Recording>, newSavedFileNames: Set<String>, newPhoneNumbers: Map<String, String>) {
        recordings = newRecordings
        savedFileNames = newSavedFileNames
        phoneNumbers = newPhoneNumbers
        if (selectedRecording != null && selectedRecording !in newRecordings) {
            selectedRecording = null
            onSelectionChanged(null)
        }
        notifyDataSetChanged()
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.taka0.callrecorder.RecordingsAdapterTest"`
Expected: PASS（既存4件＋新規2件＝6件すべて成功）

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/RecordingsAdapter.kt app/src/test/java/com/taka0/callrecorder/RecordingsAdapterTest.kt
git commit -m "feat: RecordingsAdapterの一覧ラベルに解決済み電話番号を追記表示"
```

---

### Task 3: `MainActivity`から`CallMetadataStore`を配線し、実機で確認する

**Files:**
- Modify: `app/src/main/java/com/taka0/callrecorder/MainActivity.kt:26-49,83-91,195-205`

**Interfaces:**
- Consumes: Task 1の`CallMetadataStore(context: Context).all(): Map<String, String>`、Task 2の`RecordingsAdapter`新シグネチャ（`phoneNumbers: Map<String, String>`が第3位置引数、`onSelectionChanged`は名前付き引数のまま）。
- Produces: なし（末端のUI配線。後続タスクはない）。

- [ ] **Step 1: `CallMetadataStore`のフィールドを追加する**

`MainActivity.kt`の26-28行目を以下に変更:

```kotlin
    private lateinit var repository: RecordingRepository
    private lateinit var adapter: RecordingsAdapter
    private lateinit var savedRecordingsStore: SavedRecordingsStore
    private lateinit var callMetadataStore: CallMetadataStore
```

- [ ] **Step 2: `onCreate()`内でインスタンス化し、アダプタ生成に渡す**

42-49行目を以下に変更:

```kotlin
        repository = RecordingRepository(recordingsDir)
        savedRecordingsStore = SavedRecordingsStore(this)
        callMetadataStore = CallMetadataStore(this)

        adapter = RecordingsAdapter(
            recordings = repository.list(),
            savedFileNames = savedRecordingsStore.all(),
            phoneNumbers = callMetadataStore.all(),
            onSelectionChanged = ::updateActionButtons
        )
```

- [ ] **Step 3: `onResume()`の再読み込みに`phoneNumbers`を渡す**

85行目を以下に変更:

```kotlin
        adapter.updateRecordings(repository.list(), savedRecordingsStore.all(), callMetadataStore.all())
```

- [ ] **Step 4: 削除後の再読み込みにも`phoneNumbers`を渡す**

201行目（`deleteRecording()`内）を以下に変更:

```kotlin
                adapter.updateRecordings(repository.list(), savedRecordingsStore.all(), callMetadataStore.all())
```

- [ ] **Step 5: 単体テストをビルド・実行して全体が壊れていないことを確認する**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --rerun-tasks`
Expected: BUILD SUCCESSFUL、全テスト（Task 1で+3件、Task 2で+2件、既存66件と合わせて71件）がPASS

- [ ] **Step 6: デバッグAPKをビルドする**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDebug --rerun-tasks`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 実機にインストールする**

```bash
export PATH="$PATH:/c/Users/taka0/AppData/Local/Android/Sdk/platform-tools"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 8: 実機で目視確認する**

アプリを開き、録音一覧画面で、すでに電話番号が解決済みの録音（このセッション中に`08089004673`で複数件確認済み）の行が「日時 ・ 08089004673」の形式で表示されていること、番号未解決の録音（テストデータの古いエントリなど）が日時のみで表示されていることを確認する。一覧を一度離れて（例: 設定画面を開いて戻る、またはアプリを再起動して）`onResume()`が再度走ることも確認する。

- [ ] **Step 9: コミット**

```bash
git add app/src/main/java/com/taka0/callrecorder/MainActivity.kt
git commit -m "feat: MainActivityの録音一覧にCallMetadataStoreの電話番号を配線"
```
