# 録音一覧画面UI刷新 設計書

## 背景・目的

`MainActivity`の録音一覧画面で、各行に「時刻ラベル＋再生／文字起こし／削除の3ボタン」を横並びに配置している。ボタンが幅を取るため、時刻ラベルの表示領域が狭く、時刻が見切れて読みにくい。

以下の4点を満たすようUIを刷新する。

1. 時刻表示を1行で見切れなく表示する
2. 録音を選択できるようにする
3. 再生／文字起こし／削除ボタンを画面最上段に集約する
4. GitHub保存済みの録音には「保存済」の表記を付ける（時刻の右側）

## 全体方針

各行のボタンを廃止し、画面上部の共通アクションバーに1セットだけ配置する。上部ボタンは「一覧でタップ選択された録音」に対して動作する。行はタップで選択状態（ハイライト）になる。

ボタンがなくなった分、行は時刻ラベルの表示に全幅を使えるようになり、「見切れ」問題は選択機構の導入と同時に解消される。

## コンポーネント設計

### 1. `activity_main.xml`（上部アクションバー）

`settings_button`の下、`recordings_list`（RecyclerView）の上に、横並びの3ボタン（`action_play` / `action_transcribe` / `action_delete`）を追加する。各ボタンは`layout_weight="1"`で等幅に配置する。初期状態は`android:enabled="false"`。

### 2. `item_recording.xml`（一覧の各行）

現状の3ボタンを削除し、以下の2要素のみにする。

- `recording_label`（時刻）: `layout_width="0dp"` `layout_weight="1"`、`maxLines="1"` `ellipsize="end"`で1行固定表示
- `saved_badge`（新規）: 時刻の右側に配置するTextView。「保存済」というテキストで、通常は`visibility="gone"`、保存済みの録音のときだけ`visible`にする

行のルート`LinearLayout`に、選択状態を表す背景（`android:background`にstate-list drawableを指定し、`android:state_activated`で色を切り替える）を設定する。`ViewHolder.itemView.isActivated`を選択状態に応じてトグルすることでハイライトを実現する。

新規drawable `bg_recording_item.xml`（selector）:
- `state_activated=true`: ハイライト色（テーマのprimary系の薄い色）
- デフォルト: 透明

### 3. `RecordingsAdapter.kt`

責務を「行の表示」と「選択状態の管理」に変更する。

```kotlin
class RecordingsAdapter(
    private var recordings: List<Recording>,
    private var savedFileNames: Set<String>,
    private val onSelectionChanged: (Recording?) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

    private var selectedRecording: Recording? = null

    fun getSelected(): Recording? = selectedRecording

    fun updateRecordings(newRecordings: List<Recording>, newSavedFileNames: Set<String>) {
        recordings = newRecordings
        savedFileNames = newSavedFileNames
        // 選択中の録音が新しい一覧になければ選択解除
        if (selectedRecording != null && selectedRecording !in newRecordings) {
            selectedRecording = null
            onSelectionChanged(null)
        }
        notifyDataSetChanged()
    }

    // onBindViewHolder内:
    // - holder.label.text = recording.recordedAt.format(...)
    // - holder.savedBadge.visibility = if (recording.file.name in savedFileNames) VISIBLE else GONE
    // - holder.itemView.isActivated = (recording == selectedRecording)
    // - holder.itemView.setOnClickListener {
    //       selectedRecording = recording
    //       notifyDataSetChanged()
    //       onSelectionChanged(recording)
    //   }
}
```

`onPlay` / `onTranscribe` / `onDelete`のコンストラクタ引数は削除する（呼び出し側の`MainActivity`が上部ボタンから直接呼ぶため不要になる）。

### 4. `SavedRecordingsStore.kt`（新規）

GitHub保存済みのファイル名を端末内のSharedPreferencesに記録する、小さな専用クラス。

```kotlin
class SavedRecordingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("saved_recordings", Context.MODE_PRIVATE)

    fun all(): Set<String> = prefs.getStringSet(KEY, emptySet()) ?: emptySet()

    fun markSaved(fileName: String) {
        val updated = all().toMutableSet().apply { add(fileName) }
        prefs.edit().putStringSet(KEY, updated).apply()
    }

    companion object {
        private const val KEY = "saved_file_names"
    }
}
```

### 5. `MainActivity.kt`

- `adapter`のコンストラクタ呼び出しを新シグネチャに合わせて変更（`onSelectionChanged = ::updateActionButtons`）
- 上部3ボタンの`OnClickListener`で`adapter.getSelected()`を取得し、nullでなければ既存の`playRecording` / `openTranscribe` / `deleteRecording`を呼ぶ
- `updateActionButtons(recording: Recording?)`: 3ボタンの`isEnabled`を`recording != null`で一括設定
- `deleteRecording`成功後・`onResume`時は`adapter.updateRecordings(repository.list(), SavedRecordingsStore(this).all())`を呼ぶ（選択解除ロジックはアダプタ内で処理される）

### 6. `TranscribeActivity.kt`

`saveToGitHub()`の保存成功パス（`finish()`の直前）で、`SavedRecordingsStore(applicationContext).markSaved(recordingFile.name)`を呼ぶ。

## 既知の制約

`SavedRecordingsStore`は端末内のみの記録であり、この機能の導入前にすでにGitHub保存済みだった録音（テスト時に保存した2026-08-06 13:51／14:07の2件）は遡って「保存済」と判定されない。過去分に対する救済措置は行わない（ユーザー承認済み）。

## テスト方針

`MediaRecorder`同様、UIの見た目・タップ操作は実機でないと最終確認できない。ロジック部分（`SavedRecordingsStore`の読み書き、`RecordingsAdapter`の選択状態遷移）はユニットテスト・Robolectricテストで検証し、見た目・タップの挙動はTask 18同様に実機での最終確認を行う。
