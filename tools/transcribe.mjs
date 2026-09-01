// diary/YYYY-MM-DD.md(アプリがGitHub経由で届ける録音日記)を読み、Obsidianデイリーノートに転記する。
// マーカー区間を冪等にupsertするため、再実行のたびに最新内容へ自己修復される。
// 音声リンクは diary/ 起点の "audio/xxx.m4a" のままだと01_原油から辿れないため、
// 01_原油 起点の相対パスへ書き換えてから転記する。
// 日本語パスはこのファイル(UTF-8)内に持つ(.ps1に書くと文字化けするため)。
import { readFileSync, writeFileSync, existsSync, readdirSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const START = '<!-- 通話録音:start -->';
const END = '<!-- 通話録音:end -->';
const DEFAULT_SOURCE_DIR = String.raw`D:\Obsidian Vault for Claude Code\Git\call-recording-app\diary`;
const DEFAULT_DIARY_DIR = String.raw`D:\Obsidian Vault for Claude Code\01_原油`;
// 01_原油\YYYY-MM-DD.md から見た音声フォルダの相対パス。
const AUDIO_PREFIX = '../Git/call-recording-app/diary/audio/';
const DATE_FILE = /^(\d{4}-\d{2}-\d{2})\.md$/;

export function todayString(now = new Date()) {
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

// 先頭のYAMLフロントマターを取り除く(無ければそのまま返す)。
export function stripFrontmatter(text) {
  const m = /^---\r?\n[\s\S]*?\r?\n---\r?\n?/.exec(text);
  return m ? text.slice(m[0].length) : text;
}

// diary/ 起点の音声リンクを 01_原油 起点の相対パスへ書き換える。
// 既に書き換え済みのもの・外部URLには触れない。
export function rewriteAudioLinks(text) {
  return text.replace(/\]\(audio\/([^)]+)\)/g, (_all, name) => `](${AUDIO_PREFIX}${name})`);
}

// 1日分の転記本文を作る。中身が空ならnull。
// 改行はLFへ正規化する。CRLFのまま返すとupsertSectionのeol変換で\rが二重になり、
// 実行のたびに差分が出て冪等でなくなるため。
export function buildDaySection(sourceText) {
  const body = rewriteAudioLinks(stripFrontmatter(sourceText)).replace(/\r\n/g, '\n').trim();
  return body === '' ? null : body;
}

// contentの改行スタイルを保ちながら、マーカー区間を冪等に置換(無ければ末尾に追記)する。
// 日記本文の他の部分には一切触れない。
export function upsertSection(content, section) {
  const eol = content.includes('\r\n') ? '\r\n' : '\n';
  const block = `${START}${eol}${section.replaceAll('\n', eol)}${eol}${END}${eol}`;
  const startIdx = content.indexOf(START);
  const endIdx = content.indexOf(END);
  if (startIdx !== -1 && endIdx !== -1) {
    return content.slice(0, startIdx) + block + content.slice(endIdx + END.length).replace(/^\r?\n/, '');
  }
  if (content === '') return block;
  const sep = content.endsWith(eol) ? eol : eol + eol;
  return content + sep + block;
}

// 転記対象の日付(当日は録音が増え続けるため除外)。昇順で返す。
export function datesToTranscribe(dates, today) {
  return [...new Set(dates)].filter((d) => d < today).sort();
}

// sourceDir内の日付ファイル名(YYYY-MM-DD.md)から日付一覧を取り出す。
export function listDiaryDates(fileNames) {
  return fileNames.map((n) => DATE_FILE.exec(n)).filter(Boolean).map((m) => m[1]);
}

// diaryDir配下の各日付ファイルへ、sourceDirの録音日記をupsertする。
// action: 'created'(新規ファイル) / 'updated'(内容変更あり) / 'unchanged'(差分なし) / 'error'
export function runTranscription({ sourceDir, diaryDir, today }) {
  const dates = listDiaryDates(readdirSync(sourceDir));
  const results = [];
  for (const date of datesToTranscribe(dates, today)) {
    const path = join(diaryDir, `スマホ - ${date}.md`);
    try {
      const section = buildDaySection(readFileSync(join(sourceDir, `${date}.md`), 'utf8'));
      if (!section) continue;
      const existing = existsSync(path) ? readFileSync(path, 'utf8') : '';
      const next = upsertSection(existing, section);
      if (existing === next) {
        results.push({ date, action: 'unchanged' });
      } else {
        writeFileSync(path, next, 'utf8');
        results.push({ date, action: existing === '' ? 'created' : 'updated' });
      }
    } catch (err) {
      results.push({ date, action: 'error', message: err.message });
    }
  }
  return results;
}

function main() {
  const sourceDir = process.argv[2] || DEFAULT_SOURCE_DIR;
  const diaryDir = process.argv[3] || DEFAULT_DIARY_DIR;
  if (!existsSync(sourceDir)) {
    console.log('録音日記フォルダがまだありません。スキップします');
    return;
  }
  mkdirSync(diaryDir, { recursive: true });
  const results = runTranscription({ sourceDir, diaryDir, today: todayString() });
  for (const r of results) {
    console.log(r.action === 'error' ? `${r.date}: ERROR (${r.message})` : `${r.date}: ${r.action}`);
  }
  if (results.length === 0) console.log('転記対象なし');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
