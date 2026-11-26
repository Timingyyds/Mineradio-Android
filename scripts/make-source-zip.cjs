// scripts/make-source-zip.cjs
// 打包可分享的源码压缩包。
//
// 为什么用 Node 而不是 tar/zip 命令：
//   这台设备的 shell 里 `gzip` 无法 exec（Permission denied），
//   系统自带的 tar -z 依赖它，所以只能走 Node。
//
// 排除项：node_modules（900MB，别人只需 npm i）、构建产物、日志、gradle 缓存。

'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.resolve(__dirname, '..');
const OUT = process.env.OUT
  || '/storage/emulated/0/打包/Mineradio-Android-源码-20261025.zip';

// 要打包的顶层条目
const INCLUDE = ['public', 'android', 'scripts', 'capacitor.config.json', 'package.json', 'LICENSE', 'NOTICE.md', 'PRIVACY.md'];

// 路径中包含这些片段就跳过
const SKIP_PARTS = [
  'node_modules',
  path.join('android', 'build'),
  path.join('android', 'app', 'build'),
  path.join('android', '.gradle'),
  path.join('android', '.kotlin'),
  path.join('android', 'capacitor-cordova-android-plugins', 'build'),
  path.join('android', 'app', 'src', 'main', 'assets', 'public'),
];

/**
 * 是否跳过某个路径。
 *
 * 必须按【目录边界】判断，不能用 includes 做前缀匹配：
 * 排除项里的 'android/app/build' 会连 'android/app/build.gradle' 一起吃掉 ——
 * 那个 gradle 文件是构建必需的，打成包别人就编不了。
 */
const shouldSkip = (relative) => {
  const normalized = '/' + relative.split(path.sep).join('/');
  return SKIP_PARTS.some((part) => {
    const needle = '/' + part.split(path.sep).join('/');
    // 命中目录本身，或在其之下
    return normalized === needle || normalized.startsWith(needle + '/');
  });
};

function walk(dir, base, files) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    const relative = path.relative(base, full);
    if (shouldSkip(relative)) continue;
    if (entry.isDirectory()) {
      walk(full, base, files);
    } else if (entry.isFile()) {
      const stat = fs.statSync(full);
      // 跳过超大文件（正常源码不该有），避免把意外文件打进包
      if (stat.size > 8 * 1024 * 1024) {
        console.warn('  跳过超大文件: ' + relative + ' (' + (stat.size / 1e6).toFixed(1) + 'MB)');
        continue;
      }
      files.push({ full, relative: relative.split(path.sep).join('/') });
    }
  }
}

// ─── 最小 ZIP 写入器（store + deflate，无依赖） ───

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let i = 0; i < 256; i += 1) {
    let c = i;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[i] = c;
  }
  return table;
})();

function crc32(buffer) {
  let crc = -1;
  for (let i = 0; i < buffer.length; i += 1) {
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buffer[i]) & 0xff];
  }
  return (crc ^ -1) >>> 0;
}

function buildZip(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;

  for (const entry of entries) {
    const data = fs.readFileSync(entry.full);
    const compressed = zlib.deflateRawSync(data, { level: 9 });
    // 压缩不划算就存原始数据
    const useDeflate = compressed.length < data.length;
    const payload = useDeflate ? compressed : data;
    const method = useDeflate ? 8 : 0;
    const crc = crc32(data);
    const nameBuf = Buffer.from(entry.relative, 'utf8');

    const local = Buffer.alloc(30 + nameBuf.length);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);          // 版本
    local.writeUInt16LE(0, 6);           // 标志
    local.writeUInt16LE(method, 8);
    local.writeUInt16LE(0, 10);          // 时间
    local.writeUInt16LE(0, 12);          // 日期
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(payload.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);          // extra 长度
    nameBuf.copy(local, 30);

    locals.push(local, payload);

    const central = Buffer.alloc(46 + nameBuf.length);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);        // 创建版本
    central.writeUInt16LE(20, 6);        // 需要版本
    central.writeUInt16LE(0, 8);
    central.writeUInt16LE(method, 10);
    central.writeUInt16LE(0, 12);
    central.writeUInt16LE(0, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(payload.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt16LE(0, 30);        // extra
    central.writeUInt16LE(0, 32);        // comment
    central.writeUInt16LE(0, 34);        // 磁盘号
    central.writeUInt16LE(0, 36);        // 内部属性
    central.writeUInt32LE(0, 38);        // 外部属性
    central.writeUInt32LE(offset, 42);   // 本地头偏移
    nameBuf.copy(central, 46);

    centrals.push(central);
    offset += local.length + payload.length;
  }

  const centralBuf = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);

  return Buffer.concat([...locals, centralBuf, eocd]);
}

// ─── 入口 ───

const files = [];
for (const item of INCLUDE) {
  const full = path.join(ROOT, item);
  if (!fs.existsSync(full)) {
    console.warn('  跳过不存在的条目: ' + item);
    continue;
  }
  const stat = fs.statSync(full);
  if (stat.isDirectory()) walk(full, ROOT, files);
  else files.push({ full, relative: item });
}

console.log('打包源码：' + files.length + ' 个文件');
const zip = buildZip(files);
fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, zip);
console.log('✓ ' + OUT);
console.log('  大小: ' + (zip.length / 1024 / 1024).toFixed(2) + ' MB');
