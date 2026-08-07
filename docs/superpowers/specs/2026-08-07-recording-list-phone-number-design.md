# 録音一覧画面への電話番号表示 設計書

## 背景・目的

`docs/superpowers/specs/2026-08-06-diary-phone-number-design.md`で実装した機能により、通話終了時に電話番号を取得し`CallMetadataStore`（端末内SharedPreferences、録音ファイル名がキー）へ保存する仕組みはすでに存在する。現状これはGitHub上の日記エントリにのみ反映され、アプリ本体（`MainActivity`）の録音一覧画面には表示されていない。

録音一覧画面でも、解決済みの電話番号をひと目で確認できるようにする。

## スコープ

- 表示するのは電話番号のみ（相手の名前は表示しない。名前解決はPC側の`電話帳.md`＋`pull-call-recordings.ps1`でのみ行う既存方針を維持する）。
- 番号が未解決（バックグラウンド解決中、または解決に失敗）の場合は、従来どおり日時のみを表示する。「不明」という文言はアプリ内では使わない（日記側の「不明」は確定した記録としての表示だが、アプリ内はまだ解決中の可能性がある一時的な状態のため、意味合いを混同しないようにする）。

## 設計

### データソース

新規の保存処理は不要。既存の`CallMetadataStore(context).get(fileName): String?`をそのまま読み出す。

### 表示場所・形式

`RecordingsAdapter`の各行ラベル（`recording_label`）に、日時に続けて電話番号を追記する。

```
2026-08-07 10:41 ・ 08089004673
```

番号が`null`の場合は、現状どおり日時のみ（`2026-08-07 10:41`）。

新しいビュー（別行・別TextView）は追加しない。既存の1行レイアウト（`item_recording.xml`の`recording_label`、`maxLines="1"`, `ellipsize="end"`）にそのまま収める。

### 更新タイミング

`MainActivity.onResume()`で一覧を再読み込みする箇所（`adapter.updateRecordings(repository.list(), savedRecordingsStore.all())`、既存2箇所: 83-86行目付近と201行目付近）で、`CallMetadataStore(applicationContext).all()`相当の値も同時に読み出して渡す。既存の「保存済」バッジ（`savedFileNames: Set<String>`）と全く同じ更新カデンスに揃える（画面を開いたままでのリアルタイム更新は行わない。バックグラウンド解決が完了した後、次に一覧画面へ戻ってきたタイミングで反映される）。

### `CallMetadataStore`への追加

現状`get(fileName): String?`のみ公開されている。一覧全体を1回で読み出すための`all(): Map<String, String>`相当のメソッドを追加する（`SavedRecordingsStore.all(): Set<String>`と同じパターン）。ただし`CallMetadataStore.save()`は`phoneNumber: String?`を許容し`null`も保存しうるため、`all()`は値が`null`でないエントリのみを返す（未解決のまま保存されたエントリを不要にMapへ含めない）。

### `RecordingsAdapter`変更

```kotlin
class RecordingsAdapter(
    private var recordings: List<Recording>,
    private var savedFileNames: Set<String>,
    private var phoneNumbers: Map<String, String>,
    private val onSelectionChanged: (Recording?) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {
    ...
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recording = recordings[position]
        val baseLabel = recording.recordedAt.format(labelFormatter)
        val phoneNumber = phoneNumbers[recording.file.name]
        holder.label.text = if (phoneNumber != null) "$baseLabel ・ $phoneNumber" else baseLabel
        ...
    }

    fun updateRecordings(newRecordings: List<Recording>, newSavedFileNames: Set<String>, newPhoneNumbers: Map<String, String>) {
        recordings = newRecordings
        savedFileNames = newSavedFileNames
        phoneNumbers = newPhoneNumbers
        ...
    }
}
```

`MainActivity`のコンストラクタ呼び出し・`updateRecordings()`呼び出し（既存2箇所）を新シグネチャに合わせて更新する。

## エラーハンドリング

既存の`CallMetadataStore`/`SharedPreferences`の挙動をそのまま利用するため、新たなエラーハンドリングは不要。

## テスト方針

- `CallMetadataStoreTest`に`all()`の追加（保存済みの非nullエントリのみ返す、nullエントリは含まない）。
- `RecordingsAdapterTest`（Robolectric）に、番号ありの行はラベルに追記表示され、番号なしの行は日時のみのままであることを検証するテストケースを追加する。

## 既知の制約

- バックグラウンド解決中（通話終了直後、最大2分程度）は一覧に番号が出ない。これは意図した挙動（既存のバッジ更新カデンスと同じ）。
