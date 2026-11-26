// scripts/bundle-media-tags.mjs
// 把 music-metadata 打包成浏览器可直接 <script> 加载的单文件。
//
// 为什么需要：安卓版「导入本地音乐」走的是 <input type="file">，
// 拿到的只有 Blob 没有路径，原生 MediaMetadataRetriever 用不上；
// 而 <audio> 播 MP3 时浏览器不会把 ID3 里的封面和歌词交给 JS，
// 结果内嵌封面/内嵌歌词导入后一直是空的。
// music-metadata（MIT）能在渲染进程里直接解析文件字节解决这个问题。
//
// 产物：public/js/android-shim/media-tags.js（window.MRMediaTags）
//
// 用法：node scripts/bundle-media-tags.mjs
// 依赖：本机需有 esbuild（含 android-arm64 平台二进制）。

import { build } from 'esbuild';
import { mkdirSync, writeFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const OUT = join(ROOT, 'public', 'js', 'android-shim', 'media-tags.js');

/** 入口源码：只暴露 readTags 一个能力。 */
const ENTRY = `
import { parseBlob } from 'music-metadata';

function normalize(meta) {
  const common = (meta && meta.common) || {};
  const format = (meta && meta.format) || {};
  const picture = Array.isArray(common.picture) && common.picture.length ? common.picture[0] : null;
  let coverBlob = null;
  if (picture && picture.data) {
    try { coverBlob = new Blob([picture.data], { type: picture.format || 'image/jpeg' }); } catch (e) { coverBlob = null; }
  }
  let lyrics = '';
  if (Array.isArray(common.lyrics) && common.lyrics.length) {
    const texts = common.lyrics.map(function (entry) {
      if (!entry) return '';
      if (typeof entry === 'string') return entry;
      if (typeof entry.text === 'string') return entry.text;
      if (typeof entry.syncText === 'string') return entry.syncText;
      return '';
    }).map(function (t) { return String(t || '').trim(); }).filter(Boolean);
    const timed = texts.filter(function (t) { return /\\[\\d{1,3}:\\d{1,2}/.test(t); });
    lyrics = (timed.length ? timed : texts)[0] || '';
  }
  return {
    title: common.title || '',
    artist: common.artist || (Array.isArray(common.artists) ? common.artists.join(' / ') : '') || '',
    album: common.album || '',
    duration: typeof format.duration === 'number' ? Math.round(format.duration * 1000) : 0,
    bitrate: typeof format.bitrate === 'number' ? format.bitrate : 0,
    codec: format.codec || '',
    container: format.container || '',
    lyrics: lyrics,
    coverBlob: coverBlob,
    coverMime: picture ? (picture.format || 'image/jpeg') : ''
  };
}

async function readTags(file) {
  try {
    const meta = await parseBlob(file, { duration: true, skipCovers: false });
    return normalize(meta);
  } catch (e) { return null; }
}

export { readTags };
`;

async function main() {
  const tmpEntry = join(ROOT, 'scripts', '.media-tags-entry.mjs');
  writeFileSync(tmpEntry, ENTRY, 'utf8');
  mkdirSync(dirname(OUT), { recursive: true });
  await build({
    entryPoints: [tmpEntry],
    bundle: true,
    format: 'iife',
    globalName: 'MRMediaTags',
    target: ['es2020'],
    platform: 'browser',
    minify: true,
    outfile: OUT,
    define: { 'process.env.NODE_ENV': '"production"' },
    footer: { js: 'window.MRMediaTags = MRMediaTags;' }
  });
  console.log('✓ 音频标签解析器已打包:', OUT);
  console.log('  大小:', (statSync(OUT).size / 1024).toFixed(1), 'KB');
}

main().catch(function (e) {
  console.error('打包失败:', e.message);
  process.exit(1);
});
