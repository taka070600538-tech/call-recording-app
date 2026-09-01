import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  stripFrontmatter,
  rewriteAudioLinks,
  buildDaySection,
  upsertSection,
  datesToTranscribe,
  listDiaryDates,
} from '../tools/transcribe.mjs';

const SOURCE = `---
date: 2026-08-10
---

## 11:56 — 08089004673

通話の文字起こし本文

[音声を再生](audio/2026-08-10-115624.m4a)
`;

test('stripFrontmatter: 先頭のフロントマターを取り除く', () => {
  assert.equal(stripFrontmatter('---\ndate: 2026-08-10\n---\n本文\n'), '本文\n');
});

test('stripFrontmatter: フロントマターが無ければそのまま', () => {
  assert.equal(stripFrontmatter('## 11:56\n\n本文\n'), '## 11:56\n\n本文\n');
});

test('rewriteAudioLinks: 音声リンクを01_原油起点の相対パスへ書き換える', () => {
  assert.equal(
    rewriteAudioLinks('[音声を再生](audio/2026-08-10-115624.m4a)'),
    '[音声を再生](../Git/call-recording-app/diary/audio/2026-08-10-115624.m4a)'
  );
});

test('rewriteAudioLinks: 書き換え済み・外部URLには触れない', () => {
  const already = '[音声を再生](../Git/call-recording-app/diary/audio/a.m4a)';
  assert.equal(rewriteAudioLinks(already), already);
  const url = '[参考](https://example.com/audio/a.m4a)';
  assert.equal(rewriteAudioLinks(url), url);
});

test('buildDaySection: フロントマター除去と音声リンク書き換えを両方行う', () => {
  assert.equal(
    buildDaySection(SOURCE),
    '## 11:56 — 08089004673\n\n通話の文字起こし本文\n\n[音声を再生](../Git/call-recording-app/diary/audio/2026-08-10-115624.m4a)'
  );
});

test('buildDaySection: 中身が空ならnull', () => {
  assert.equal(buildDaySection('---\ndate: 2026-08-10\n---\n\n   \n'), null);
});

test('buildDaySection: CRLFの録音日記でも改行はLFへ正規化される', () => {
  const crlf = SOURCE.replace(/\n/g, '\r\n');
  assert.equal(buildDaySection(crlf), buildDaySection(SOURCE));
  assert.ok(!buildDaySection(crlf).includes('\r'));
});

test('CRLF録音日記をCRLF日記へ転記しても2回目で差分が出ない(冪等)', () => {
  const section = buildDaySection(SOURCE.replace(/\n/g, '\r\n'));
  const once = upsertSection('前文\r\n', section);
  assert.ok(!once.includes('\r\r'));
  assert.equal(upsertSection(once, section), once);
});

test('upsertSection: マーカーが無ければ末尾に追記', () => {
  const out = upsertSection('既存の本文\n', 'セクション');
  assert.equal(out, '既存の本文\n\n<!-- 通話録音:start -->\nセクション\n<!-- 通話録音:end -->\n');
});

test('upsertSection: 既存マーカー区間だけを置換し他は触らない', () => {
  const before = '前文\n\n<!-- 通話録音:start -->\n古い内容\n<!-- 通話録音:end -->\n後文\n';
  const out = upsertSection(before, '新しい内容');
  assert.equal(out, '前文\n\n<!-- 通話録音:start -->\n新しい内容\n<!-- 通話録音:end -->\n後文\n');
});

test('upsertSection: 2回適用しても結果が変わらない(冪等)', () => {
  const once = upsertSection('既存の本文\n', 'セクション');
  assert.equal(upsertSection(once, 'セクション'), once);
});

test('upsertSection: CRLFの日記では改行スタイルを保つ', () => {
  const out = upsertSection('既存の本文\r\n', 'セクション');
  assert.equal(out, '既存の本文\r\n\r\n<!-- 通話録音:start -->\r\nセクション\r\n<!-- 通話録音:end -->\r\n');
});

test('listDiaryDates: 日付形式のファイルだけを拾う', () => {
  assert.deepEqual(
    listDiaryDates(['2026-08-10.md', '2026-08-11.md', 'audio', 'README.md']),
    ['2026-08-10', '2026-08-11']
  );
});

test('datesToTranscribe: 当日を除いた日付昇順(重複除去)', () => {
  assert.deepEqual(
    datesToTranscribe(['2026-08-11', '2026-08-10', '2026-08-10', '2026-08-12'], '2026-08-12'),
    ['2026-08-10', '2026-08-11']
  );
});
