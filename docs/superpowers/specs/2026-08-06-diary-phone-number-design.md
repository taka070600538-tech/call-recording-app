# 通話ダイアリーへの時刻・電話番号記録 設計書

## 背景・目的

最終的にローカルPC側に同期される通話文字起こしファイル（`日記\通話録音\YYYY-MM-DD.md`）には、現在、時刻も電話番号も記録されていない。GitHub上の日記データ（`diary/YYYY-MM-DD.md`）には`## HH:mm`の時刻見出しは残っているが、PC同期スクリプト（`pull-call-recordings.ps1`）がこの見出し行を正規表現で完全に削除しているため、最終ファイルには本文とオーディオリンクしか残らない。

以下を満たすようにする。

1. 通話の文字起こしエントリに時刻を記録する（新規分だけでなく、次回同期時点で既存分の時刻も復活させる）
2. 通話相手の電話番号を記録する（着信・発信どちらも対象。非通知・不明番号の場合は「不明」と表示）

## 全体方針

電話番号の取得は、通話終了（`CALL_STATE_IDLE`）のタイミングで`CallLog.Calls`（発着信履歴）を1回照会する方式にする。着信・発信を同じコードパスで扱えるため、着信のみリアルタイム取得＋発信は別経路、という二重実装を避けられる。

取得した番号は録音ファイル名をキーにして端末内のSharedPreferences（`CallMetadataStore`）に保存し、文字起こし→GitHub保存のタイミングでこのストアから読み出して日記エントリの見出しに書き込む。

PC同期スクリプトは、見出し行を削除する現在の処理を「`##`のみ除去して時刻（＋あれば電話番号）の行として残す」処理に変更する。これにより、この機能導入前に書かれた既存の`## HH:mm`のみの見出しも、次回同期時から時刻が表示されるようになる（電話番号は導入前の録音には記録されていないため付与されない。既知の制約として受け入れる）。

## コンポーネント設計

### 1. 権限追加

`AndroidManifest.xml`に`android.permission.READ_CALL_LOG`を追加する。`MainActivity`の`requestRequiredPermissions()`・`hasRecordingPermissions()`の権限リストに`READ_PHONE_STATE`と同様に加える。

### 2. `CallLogLookup`（新規インターフェース）

```kotlin
interface CallLogLookup {
    /** 直近の発着信の電話番号。取得できない場合はnull。 */
    fun mostRecentNumber(): String?
}
```

実装（`SystemCallLogLookup`）は`CallLog.Calls.CONTENT_URI`を`DATE DESC`で1件だけ問い合わせ、`NUMBER`列を返す。`SecurityException`（権限未許可）やエントリが存在しない場合は`null`を返す。`AudioRecorder`と同じパターンで、`RecordingService`にテスト用の差し替え口（`setCallLogLookupForTest`）を用意する。

### 3. `CallMetadataStore`（新規）

`SavedRecordingsStore`と同型のSharedPreferencesラッパー。

```kotlin
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

### 4. `RecordingService.kt`変更

- `startRecording()`で作成した`file`をインスタンスフィールド（`currentFile: File?`）として保持する。
- `stopRecording()`内、`audioRecorder.stop()`の後に電話番号を取得する処理を追加する。

```kotlin
val phoneNumber = callLogLookup.mostRecentNumber()
    ?: run {
        Thread.sleep(300) // CallLogへの書き込みとの競合を吸収するための短い再試行
        callLogLookup.mostRecentNumber()
    }
currentFile?.let { CallMetadataStore(applicationContext).save(it.name, phoneNumber) }
```

- `CallLogLookup`の呼び出しは例外を投げないこと（`SystemCallLogLookup`内で吸収）を前提とする。万一の例外はここでもcatchしてnull扱いにし、録音の停止処理自体は失敗させない。

### 5. `DiaryMarkdownFormatter.kt`変更

`entryBlock`に`phoneNumber: String?`パラメータを追加する。

```kotlin
fun entryBlock(time: LocalTime, phoneNumber: String?, text: String, audioRelativePath: String?): String {
    val heading = "## ${time.format(TIME_FORMATTER)} — ${phoneNumber ?: "不明"}\n\n"
    ...
}
```

既存のテスト・呼び出し側（`TranscribeActivity`）はシグネチャ変更に合わせて更新する。

### 6. `TranscribeActivity.kt`変更

`saveTranscriptAndAudio()`内で`entryBlock`を呼ぶ直前に、`CallMetadataStore(applicationContext).get(recordingFile.name)`で番号を取得し、渡す。

### 7. `pull-call-recordings.ps1`変更

現在の「見出し行を削除する」置換

```powershell
$body = $body -replace '(?m)^##\s*\d{1,2}:\d{2}\s*$', ''
```

を、「`##`のみ除去して残りをそのまま残す」置換に変更する。

```powershell
$body = $body -replace '(?m)^##\s*(\d{1,2}:\d{2}.*)$', '$1'
```

これにより、新形式（`## HH:mm — 090-1234-5678`）は`HH:mm — 090-1234-5678`に、旧形式（`## HH:mm`のみ）は`HH:mm`になる。

## エラーハンドリング

- `READ_CALL_LOG`権限が無い、`CallLog`照会が例外を投げる、対象エントリが見つからない — いずれも`null`として扱い、最終的に見出しは「不明」表示になる。
- 電話番号取得の失敗・遅延は、録音の保存・文字起こし・GitHub保存という主要フローを止めない（例外を上位に伝播させない）。

## 既知の制約

- この機能の導入前に録音された通話は`CallMetadataStore`にエントリが無いため、後から文字起こし・保存しても電話番号は「不明」になる（過去分への遡及対応は行わない）。
- 同一分内に複数の通話が短時間で行われた場合、`CallLog`の直近1件が必ずしも対象の録音と一致しない可能性がある（既知のレアケースとして受容する）。

## テスト方針

- `CallMetadataStoreTest`：`SavedRecordingsStoreTest`と同型（保存・取得・永続化）。
- `RecordingServiceTest`に追加：Fakeの`CallLogLookup`を注入し、`stopRecording()`時に`CallMetadataStore`へ正しく保存されること／lookupが両方`null`を返した場合も例外にならず`null`が保存されることを確認する。
- `DiaryMarkdownFormatterTest`：`entryBlock`の新シグネチャで、番号ありのケースと`null`（「不明」表示になる）ケースを追加する。
- `pull-call-recordings.ps1`は既存どおり自動テストを持たない方針を踏襲し、実機・実データでの動作確認で最終検証する。
