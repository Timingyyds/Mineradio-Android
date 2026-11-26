'use strict';

var FOLIA_LYRIC_PRESETS = [
  { id: 'sonnet', name: '商籁', en: 'Sonnet' },
  { id: 'tempera', name: '凝彩', en: 'Tempera' },
  { id: 'lumiere', name: '绘光', en: 'Lumiere' },
  { id: 'hanabi', name: '花火', en: 'Hanabi' },
  { id: 'machi', name: '町景', en: 'Machi' },
  { id: 'nebbia', name: '雾笺', en: 'Nebbia' },
  { id: 'classic', name: '流光', en: 'Luminous' },
  { id: 'cadenza', name: '心象', en: 'Mindscape' },
  { id: 'partita', name: '云阶', en: 'Partita' },
  { id: 'fume', name: '浮名', en: 'Fume' },
  { id: 'tilt', name: '倾诉', en: 'Tilt' },
  { id: 'claddagh', name: '回环', en: 'Claddagh' },
  { id: 'monet', name: '莫奈', en: 'Monet' },
  { id: 'pendolo', name: '时计', en: 'Pendolo' },
  { id: 'cappella', name: '群唱', en: 'Cappella' },
  { id: 'diorama', name: '镜台', en: 'Diorama' },
  { id: 'still', name: '静止', en: 'Still' },
  { id: 'random', name: '随机', en: 'Random' }
];

var FOLIA_LYRIC_STORAGE_KEY = 'mineradio-folia-lyric-preset';
// 只有用户亲手点过 Folia 预设才写这个标记；冷启动恢复必须以此为前提，
// 否则默认进入应该保持「emily专辑封面」预设、Folia 关闭。
var FOLIA_LYRIC_USER_CHOICE_KEY = 'mineradio-folia-lyric-user-choice-v1';
var FOLIA_BACKGROUND_MODE_KEY = 'mineradio-folia-background-mode-v2';
var FOLIA_MOTION_MODE_KEY = 'mineradio-folia-motion-mode';
var FOLIA_BACKGROUND_MODES = ['common', 'latent', 'monet', 'nomand', 'sora', 'url'];
var FOLIA_BACKGROUND_LABELS = { none: '原背景', common: '通用', latent: '隐现', monet: '莫奈', nomand: '漫游', sora: '空', url: '嵌入' };
var FOLIA_MOTION_MODES = ['standard', 'chaotic', 'still'];
var FOLIA_MOTION_LABELS = { standard: '标准', chaotic: '混乱', still: '静止' };
var FOLIA_FRAME_SRC = 'folia-visualizer/visualizer-embed.html';

var foliaLyricState = {
  index: -1,
  activeId: '',
  isRandom: false,
  built: false
};

// 歌词兜底：Folia 是 iframe 画的，只有它真的画出来（ready 且拿到歌词行）才藏掉内置歌词。
// 否则切预设后内嵌页没起来 / 没歌词，就会出现「切换预设后歌词消失」。
var foliaLyricPendingStageHide = false;
var foliaLyricReadyGuardTimer = 0;

var foliaFrameState = {
  ready: false,
  bound: false,

  tickTimer: 0,
  lastSignature: '',
  audioBuf: null,
  backgroundMode: 'none',
  motionMode: 'standard'
};

function foliaLyricEscape(s) {
  if (s == null) return '';
  return String(s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

function foliaLyricBuildGrid() {
  var grid = document.getElementById('folia-preset-grid');
  if (!grid) return;
  var html = '';
  for (var i = 0; i < FOLIA_LYRIC_PRESETS.length; i += 1) {
    var p = FOLIA_LYRIC_PRESETS[i];
    html += '<button type="button" class="folia-preset-card' +
      (i === foliaLyricState.index ? ' active' : '') +
      '" data-folia-preset="' + i + '" onclick="setFoliaLyricPreset(' + i + ')">' +
      '<span class="folia-preset-name">' + foliaLyricEscape(p.name) + '</span>' +
      '<span class="folia-preset-en">' + foliaLyricEscape(p.en) + '</span>' +
      '</button>';
  }
  grid.innerHTML = html;
  foliaLyricState.built = true;
}

function foliaLyricRefreshCards() {
  var cards = document.querySelectorAll('[data-folia-preset]');
  for (var i = 0; i < cards.length; i += 1) {
    var idx = Number(cards[i].getAttribute('data-folia-preset'));
    cards[i].classList.toggle('active', idx === foliaLyricState.index);
  }
}

function foliaLyricSavedIndex() {
  var raw = null;
  try { raw = localStorage.getItem(FOLIA_LYRIC_STORAGE_KEY); } catch (e) { return -1; }
  // 注意：没存过时 getItem 返回 null，Number(null) === 0，
  // 以前会被当成「已保存下标 0」→ 每次冷启动都把 Folia 预设（商籁）一起打开。
  if (raw === null || String(raw).trim() === '') return -1;
  var saved = Number(raw);
  if (!isFinite(saved) || saved < 0 || saved >= FOLIA_LYRIC_PRESETS.length) return -1;
  return Math.round(saved);
}

function foliaLyricUserChosen() {
  try { return localStorage.getItem(FOLIA_LYRIC_USER_CHOICE_KEY) === '1'; } catch (e) { return false; }
}

function foliaLyricMarkUserChoice() {
  try { localStorage.setItem(FOLIA_LYRIC_USER_CHOICE_KEY, '1'); } catch (e) { /* 忽略 */ }
}

function foliaLyricEnsureFrame() {
  var frame = document.getElementById('folia-lyrics-frame');
  if (!frame) return null;
  if (!foliaFrameState.bound) {
    foliaFrameState.bound = true;
    window.addEventListener('message', foliaLyricOnFrameMessage);
  }

  if (!frame.getAttribute('src')) frame.src = FOLIA_FRAME_SRC;
  return frame;
}

function foliaLyricOnFrameMessage(event) {
  var frame = document.getElementById('folia-lyrics-frame');
  if (!frame || event.source !== frame.contentWindow) return;
  var data = event.data;
  if (!data || typeof data !== 'object') return;
  if (data.type === 'folia-ready') {
    foliaFrameState.ready = true;
    foliaFrameState.lastSignature = '';
    foliaLyricSendConfig();
    foliaLyricSendModes();
    clearTimeout(foliaLyricReadyGuardTimer);
    foliaLyricApplyStageLyricsVisibility();
    if (typeof foliaConsoleOnFrameReady === 'function') foliaConsoleOnFrameReady();
  }
}

function foliaLyricPlaying() {
  if (typeof playing !== 'undefined') return !!playing;
  if (typeof audio !== 'undefined' && audio) return !audio.paused && !audio.ended;
  return false;
}

function foliaLyricCurrentTime() {
  // ★ 这里必须回传「原始音频时间」：歌词校准 offset 已经在 foliaLyricBuildLines 里烘进行时间了。
  //   以前这里回传 adjusted（t+offset），而 iframe 里是「本地时钟 + 0.22s 同步死区」——
  //   宿主 folia-tick 推来的时间与本地时钟差 <0.22s 时永远不被采纳，
  //   ±0.1s 步进的歌词校准会被死区整个吞掉，用户怎么调 Folia 歌词都不动。
  //   改为 raw 时间 + 行时间平移，校准立即生效（详见 foliaLyricBuildLines 注释）。
  return (typeof audio !== 'undefined' && audio && isFinite(Number(audio.currentTime))) ? Number(audio.currentTime) : 0;
}

function foliaLyricTimingOffset() {
  try {
    if (typeof getActiveLyricTimingOffsetSeconds === 'function') {
      var offset = Number(getActiveLyricTimingOffsetSeconds());
      if (isFinite(offset)) return offset;
    }
  } catch (e) { /* 忽略 */ }
  return 0;
}

function foliaLyricCoverUrl() {
  try {
    if (typeof currentCoverSong !== 'function') return null;
    var song = currentCoverSong();
    if (!song) return null;
    var url = song.cover || song.pic || '';
    if (url && typeof coverUrlWithSize === 'function') return coverUrlWithSize(url, 640);
    return url || null;
  } catch (e) {
    return null;
  }
}

function foliaLyricSeed() {
  try {
    if (typeof currentCoverSong !== 'function') return 'mineradio';
    var song = currentCoverSong();
    if (!song) return 'mineradio';
    return song.id || song.mid || song.name || 'mineradio';
  } catch (e) {
    return 'mineradio';
  }
}

function foliaLyricTheme() {
  var pal = (typeof stageLyrics !== 'undefined' && stageLyrics && stageLyrics.palette) ? stageLyrics.palette : null;
  return {
    name: 'Mineradio',
    backgroundColor: '#05080f',
    primaryColor: (pal && pal.primary) || '#d6f8ff',
    accentColor: (pal && pal.secondary) || '#9cffdf',
    secondaryColor: (pal && pal.secondary) || '#9cffdf',
    fontStyle: 'sans',
    animationIntensity: 'normal'
  };
}

/**
 * 把 YRC 逐字时间（words: {text, t, d, c0, c1}，秒 + 原文索引）映射成 iframe 需要的
 * 逐字符 {text, startTime, endTime} 序列。iframe 端按 words[].startTime/endTime 做逐字
 * 发光（active/passed/waiting），时间准才对得上音频 —— 以前这里直接把字符均匀切分，
 * 拖音/快口的真实节奏全丢了，表现就是「发光进度和唱的节奏对不上」。
 * 字符按原文 code-unit 索引（c0/c1 的语义）切分，保证与 words 的覆盖区间严格对齐。
 */
function foliaLyricWordTimings(text, srcWords, start, end, offset) {
  offset = Number(offset) || 0;
  var wordCount = srcWords && srcWords.length ? srcWords.length : 0;
  if (!wordCount) return null;
  var out = [];
  var cursor = 0;
  for (var c = 0; c < text.length; c += 1) {
    var w = null;
    for (var k = cursor; k < wordCount; k += 1) {
      if (c >= srcWords[k].c0 && c < srcWords[k].c1) { w = srcWords[k]; cursor = k; break; }
    }
    if (!w) {
      // 字符不在任何 word 覆盖内（空格/标点间隙）→ 顺延到前面最近的 word
      for (var k2 = cursor; k2 >= 0; k2 -= 1) {
        if (srcWords[k2].c1 <= c) { w = srcWords[k2]; break; }
      }
      if (!w) w = srcWords[0];
    }
    // word 的 t 也是绝对音频时间，必须和行起点一样平移 -offset，否则校准时行先动、字不动
    var ws = Number(w.t);
    if (!isFinite(ws)) ws = start + offset;
    ws = Math.max(0, ws - offset);
    var wd = Number(w.d);
    if (!(wd > 0)) wd = Math.max(0.06, (end - start) / Math.max(1, wordCount));
    var wc = Math.max(1, w.c1 - w.c0);
    var idx = Math.max(0, Math.min(wc - 1, c - w.c0));
    var cs = ws + wd * (idx / wc);
    var ce = ws + wd * ((idx + 1) / wc);
    out.push({
      text: text.charAt(c),
      startTime: Math.max(start, Math.min(end, cs)),
      endTime: Math.max(start, Math.min(end, ce))
    });
  }
  return out;
}

function foliaLyricBuildLines() {
  var lines = (typeof lyricsLines !== 'undefined' && Array.isArray(lyricsLines)) ? lyricsLines : [];
  // ★ 歌词校准 offset 直接烘进行时间：iframe 里是「本地时钟 + 0.22s 同步死区」，
  //   宿主 folia-tick 推的时间与本地时钟差 <0.22s 时永远不被采纳，
  //   ±0.1s 步进的校准会被整个吞掉。把行时间平移 -offset（offset 正值 = 歌词提前），
  //   iframe 本地时钟照旧走音频原始时间，校准立即生效。
  var offset = foliaLyricTimingOffset();
  var out = [];
  for (var i = 0; i < lines.length; i += 1) {
    var text = String((lines[i] && lines[i].text) || '');
    if (!text.trim()) continue;
    var start = Math.max(0, (Number(lines[i].t) || 0) - offset);
    var nextT = 0;
    for (var j = i + 1; j < lines.length; j += 1) {
      if (String((lines[j] && lines[j].text) || '').trim()) {
        nextT = Math.max(0, (Number(lines[j].t) || 0) - offset);
        break;
      }
    }
    // 行尾优先用真实行时长（YRC 行自带 duration；LRC 行由解析层推断 = 下一行起点）。
    // 以前一律拖到下一行起点，长间奏被均摊到每个字上 ——
    // 表现就是「间奏里歌词还在慢慢滚、唱声早就停了」，逐字越唱越歪。
    var ownDur = Number(lines[i] && lines[i].duration) || 0;
    var end = ownDur > 0.05 ? (start + ownDur) : (nextT > start ? nextT : start + 4);
    if (nextT > start && end > nextT) end = nextT;
    if (end <= start) end = start + Math.max(0.4, ownDur || 0.4);
    var srcWords = (lines[i] && Array.isArray(lines[i].words)) ? lines[i].words : [];
    var words = foliaLyricWordTimings(text, srcWords, start, end, offset);
    if (!words) {
      // 没有 YRC 逐字时间（纯 LRC）→ 退回字符均匀切分
      var chars = Array.from ? Array.from(text) : text.split('');
      var step = (end - start) / Math.max(1, chars.length);
      words = [];
      for (var c = 0; c < chars.length; c += 1) {
        words.push({
          text: chars[c],
          startTime: start + c * step,
          endTime: start + (c + 1) * step
        });
      }
    }
    out.push({
      startTime: start,
      endTime: end,
      fullText: text,
      words: words,
      id: 'mineradio-line-' + i,
      translation: (lines[i] && lines[i].translation) || undefined
    });
  }
  return out;
}

function foliaLyricSignature() {
  var lines = (typeof lyricsLines !== 'undefined' && Array.isArray(lyricsLines)) ? lyricsLines : [];
  var first = lines.length && lines[0] ? Number(lines[0].t) || 0 : 0;
  var last = lines.length ? Number(lines[lines.length - 1].t) || 0 : 0;
  // 校准 offset 变化必须触发 config 重发（行时间烘了 offset）
  return foliaLyricSeed() + '|' + lines.length + '|' + first + '|' + last + '|' + foliaLyricTimingOffset() + '|' + (foliaLyricCoverUrl() || '');
}

function foliaLyricAudioSnapshot() {
  var out = { power: 0, bass: 0, lowMid: 0, mid: 0, vocal: 0, treble: 0 };
  /*
   * 【暂停时不要「抽搐」】没在播放（或根本没歌）时直接给 0 频谱。
   * 以前暂停后仍然把 analyser 里最后一帧（或抖动值）送进内嵌动画，
   * 动画就会一直按着这点残余能量抖 —— 用户看到的就是「歌没放，动画自己在那抽搐」。
   */
  if (!foliaLyricPlaying()) return out;
  var node = null;
  try {
    if (typeof analyser !== 'undefined' && analyser) node = analyser;
    else if (typeof beatAnalyser !== 'undefined' && beatAnalyser) node = beatAnalyser;
  } catch (e) { node = null; }
  if (node && node.getByteFrequencyData) {
    var count = Number(node.frequencyBinCount) || 0;
    if (count > 0) {
      if (!foliaFrameState.audioBuf || foliaFrameState.audioBuf.length !== count) {
        foliaFrameState.audioBuf = new Uint8Array(count);
      }
      node.getByteFrequencyData(foliaFrameState.audioBuf);
      var data = foliaFrameState.audioBuf;
      var ranges = [
        [0, Math.floor(count * 0.05)],
        [Math.floor(count * 0.05), Math.floor(count * 0.15)],
        [Math.floor(count * 0.15), Math.floor(count * 0.35)],
        [Math.floor(count * 0.35), Math.floor(count * 0.6)],
        [Math.floor(count * 0.6), count]
      ];
      var values = [];
      for (var r = 0; r < ranges.length; r += 1) {
        var from = ranges[r][0];
        var to = ranges[r][1];
        var sum = 0;
        var n = 0;
        for (var k = from; k < to && k < count; k += 1) { sum += data[k]; n += 1; }
        values.push(n ? sum / n / 255 : 0);
      }
      out.bass = values[0];
      out.lowMid = values[1];
      out.mid = values[2];
      out.vocal = values[3];
      out.treble = values[4];
      out.power = (values[0] + values[1] + values[2] + values[3] + values[4]) / 5;
    }
  }
  if (!out.power && typeof beatOnsetFlag !== 'undefined' && beatOnsetFlag) {
    out.power = 0.82;
    out.bass = 0.9;
    out.lowMid = 0.78;
    out.mid = 0.7;
    out.vocal = 0.62;
    out.treble = 0.55;
  }
  return out;
}

function foliaLyricBuildConfig() {
  var duration = 0;
  try {
    if (typeof audio !== 'undefined' && audio && isFinite(Number(audio.duration))) duration = Number(audio.duration);
  } catch (e) { duration = 0; }
  return {
    type: 'folia-config',
    mode: foliaLyricState.activeId,
    lines: foliaLyricBuildLines(),
    theme: foliaLyricTheme(),
    duration: duration,
    coverUrl: foliaLyricCoverUrl(),
    seed: foliaLyricSeed(),
    time: foliaLyricCurrentTime(),
    playing: foliaLyricPlaying(),
    backgroundMode: foliaFrameState.backgroundMode,
    motionMode: foliaFrameState.motionMode
  };
}

function foliaLyricSendConfig() {
  if (foliaLyricState.index < 0) return;
  var frame = document.getElementById('folia-lyrics-frame');
  if (!frame || !frame.contentWindow || !foliaFrameState.ready) return;
  foliaFrameState.lastSignature = foliaLyricSignature();
  frame.contentWindow.postMessage(foliaLyricBuildConfig(), '*');
}

// 默认背景 = 列表第一个（通用）。以前默认是「原背景」，对应按钮没选中，
// 看起来像“选了 Folia 却没选背景”，这里统一成第一个背景。
var FOLIA_DEFAULT_BACKGROUND_MODE = 'common';
// 只有用户亲手点过「原背景」才算明确选择；旧版本“再点一次背景 = 原背景”留下的
// 脏数据没有这个标记，会被当成“还没选”，自动补上第一个背景。
var FOLIA_BG_EXPLICIT_NONE_KEY = 'mineradio-folia-bg-explicit-none-v1';

function foliaBackgroundExplicitNone() {
  try { return localStorage.getItem(FOLIA_BG_EXPLICIT_NONE_KEY) === '1'; } catch (e) { return false; }
}

function foliaLyricSavedBackgroundMode() {
  try {
    var mode = localStorage.getItem(FOLIA_BACKGROUND_MODE_KEY);
    if (mode === 'none') return foliaBackgroundExplicitNone() ? 'none' : FOLIA_DEFAULT_BACKGROUND_MODE;
    if (FOLIA_BACKGROUND_MODES.indexOf(mode) >= 0) return mode;
    return FOLIA_DEFAULT_BACKGROUND_MODE;
  } catch (e) {
    return FOLIA_DEFAULT_BACKGROUND_MODE;
  }
}

// 打开 Folia 动画时保证一定有一个背景被选中（除非用户明确选了原背景）
function foliaEnsureBackgroundSelected() {
  var bg = foliaFrameState.backgroundMode;
  if (bg && bg !== 'none') return bg;
  if (foliaBackgroundExplicitNone()) return 'none';
  foliaFrameState.backgroundMode = FOLIA_DEFAULT_BACKGROUND_MODE;
  try { localStorage.setItem(FOLIA_BACKGROUND_MODE_KEY, FOLIA_DEFAULT_BACKGROUND_MODE); } catch (e) { /* 忽略 */ }
  if (typeof foliaLyricUpdateModeChips === 'function') foliaLyricUpdateModeChips();
  return FOLIA_DEFAULT_BACKGROUND_MODE;
}

function foliaLyricSavedMotionMode() {
  try {
    var mode = localStorage.getItem(FOLIA_MOTION_MODE_KEY);
    return FOLIA_MOTION_MODES.indexOf(mode) >= 0 ? mode : 'standard';
  } catch (e) {
    return 'standard';
  }
}

// ------------------------------------------------------------
// 何时该把内置歌词藏起来交给 Folia 画
// 只有「Folia 开着 + 内嵌页就绪 + 真的有歌词行」三条都满足才藏；
// 其它情况一律照常显示内置歌词，避免出现「切了预设后整屏没有歌词」。
// ------------------------------------------------------------
var foliaStageLinesCache = { at: 0, value: false };
var foliaStageHealTimer = 0;

function isFoliaLyricActive() {
  return !!(typeof foliaLyricState !== 'undefined' && foliaLyricState && foliaLyricState.index >= 0);
}

function foliaShouldHideStageLyrics() {
  if (!isFoliaLyricActive()) return false;
  if (typeof foliaFrameState === 'undefined' || !foliaFrameState || !foliaFrameState.ready) return false;
  var now = Date.now();
  if (now - foliaStageLinesCache.at > 300) {
    foliaStageLinesCache.at = now;
    foliaStageLinesCache.value = foliaLyricHasLyricLines();
  }
  return foliaStageLinesCache.value;
}

// 自愈：内置歌词被藏起来之后如果 Folia 又没了（关掉、切走、内嵌页崩了），
// 就把它放回来，免得歌词“莫名其妙消失”。
function foliaHealStageLyrics() {
  if (typeof stageLyrics === 'undefined' || !stageLyrics) return;
  if (foliaShouldHideStageLyrics()) return;
  var current = stageLyrics.current;
  if (current && current.visible === false) current.visible = true;
}

function foliaStartStageLyricsHealer() {
  if (foliaStageHealTimer) return;
  foliaStageHealTimer = setInterval(foliaHealStageLyrics, 1200);
}

function foliaLyricUpdateModeChips() {
  var backgrounds = document.querySelectorAll('[data-folia-bg]');
  for (var i = 0; i < backgrounds.length; i += 1) {
    backgrounds[i].classList.toggle('active', backgrounds[i].getAttribute('data-folia-bg') === foliaFrameState.backgroundMode);
  }
  var motions = document.querySelectorAll('[data-folia-motion]');
  for (var j = 0; j < motions.length; j += 1) {
    motions[j].classList.toggle('active', motions[j].getAttribute('data-folia-motion') === foliaFrameState.motionMode);
  }
}

function foliaLyricSendModes() {
  if (foliaLyricState.index < 0) return;
  var frame = document.getElementById('folia-lyrics-frame');
  if (!frame || !frame.contentWindow || !foliaFrameState.ready) return;
  frame.contentWindow.postMessage({ type: 'folia-background-mode', mode: foliaFrameState.backgroundMode }, '*');
  frame.contentWindow.postMessage({ type: 'folia-motion-mode', mode: foliaFrameState.motionMode }, '*');
}

function setFoliaBackgroundMode(mode) {
  if (mode !== 'none' && FOLIA_BACKGROUND_MODES.indexOf(mode) < 0) return;
  // 「原背景」有独立按钮；其它背景点同一个则保持，不再隐式切成原背景
  var next = mode;
  foliaFrameState.backgroundMode = next;
  try {
    localStorage.setItem(FOLIA_BACKGROUND_MODE_KEY, next);
    if (next === 'none') localStorage.setItem(FOLIA_BG_EXPLICIT_NONE_KEY, '1');
    else localStorage.removeItem(FOLIA_BG_EXPLICIT_NONE_KEY);
  } catch (e) { /* 忽略 */ }
  foliaLyricUpdateModeChips();
  if (foliaLyricState.index < 0) return;
  foliaLyricEnsureFrame();
  foliaLyricSendModes();
  if (typeof showToast === 'function') showToast('Folia 背景：' + FOLIA_BACKGROUND_LABELS[next]);
}

function setFoliaMotionMode(mode) {
  if (FOLIA_MOTION_MODES.indexOf(mode) < 0) return;
  foliaFrameState.motionMode = mode;
  try { localStorage.setItem(FOLIA_MOTION_MODE_KEY, mode); } catch (e) { /* 忽略 */ }
  foliaLyricUpdateModeChips();
  if (foliaLyricState.index < 0) return;
  foliaLyricEnsureFrame();
  foliaLyricSendModes();
  if (typeof showToast === 'function') showToast('Folia 动画：' + FOLIA_MOTION_LABELS[mode]);
}

function foliaLyricSendTick() {
  if (foliaLyricState.index < 0) return;
  // 3D 主循环在 Folia 盖屏时是空转的，不会再去恢复音频上下文；
  // 这里用 100ms 的 ticker 兜一下，避免暂停音乐很久后恢复时频谱迟迟不回来。
  if (foliaLyricPlaying()) {
    try {
      if (typeof audioCtx !== 'undefined' && audioCtx && audioCtx.state === 'suspended'
        && typeof resumeAudioAnalysis === 'function') {
        resumeAudioAnalysis();
      }
    } catch (e) { /* 忽略 */ }
  }
  var frame = document.getElementById('folia-lyrics-frame');
  if (!frame || !frame.contentWindow || !foliaFrameState.ready) return;
  var signature = foliaLyricSignature();
  if (signature !== foliaFrameState.lastSignature) {
    if (typeof foliaRandomOnSongChange === 'function' && foliaRandomOnSongChange()) {
      foliaLyricSendConfig();
      foliaLyricApplyStageLyricsVisibility();
      return;
    }
    foliaLyricSendConfig();
    foliaLyricApplyStageLyricsVisibility();
    return;
  }
  frame.contentWindow.postMessage({
    type: 'folia-tick',
    time: foliaLyricCurrentTime(),
    playing: foliaLyricPlaying(),
    audio: foliaLyricAudioSnapshot()
  }, '*');
}

function foliaLyricStartTicker() {
  if (foliaFrameState.tickTimer) return;
  foliaFrameState.tickTimer = setInterval(foliaLyricSendTick, 100);
}

function foliaLyricStopTicker() {
  if (foliaFrameState.tickTimer) {
    clearInterval(foliaFrameState.tickTimer);
    foliaFrameState.tickTimer = 0;
  }
}

function foliaLyricHideStageLyrics() {
  if (typeof stageLyrics === 'undefined' || !stageLyrics) return;
  if (stageLyrics.current) stageLyrics.current.visible = false;
  if (stageLyrics.outgoing && stageLyrics.outgoing.length) {
    for (var i = 0; i < stageLyrics.outgoing.length; i += 1) {
      if (stageLyrics.outgoing[i]) stageLyrics.outgoing[i].visible = false;
    }
  }
}

function foliaLyricRestoreStageLyrics() {
  if (typeof stageLyrics === 'undefined' || !stageLyrics) return;
  if (stageLyrics.current) stageLyrics.current.visible = true;
}

// 内嵌页有歌词可画，才把内置歌词藏起来；否则保持内置歌词显示
function foliaLyricHasLyricLines() {
  var lines = (typeof lyricsLines !== 'undefined' && Array.isArray(lyricsLines)) ? lyricsLines : [];
  for (var i = 0; i < lines.length; i += 1) {
    if (String((lines[i] && lines[i].text) || '').trim()) return true;
  }
  return false;
}

function foliaLyricApplyStageLyricsVisibility() {
  if (foliaLyricState.index < 0) {
    foliaLyricPendingStageHide = false;
    foliaLyricRestoreStageLyrics();
    return;
  }
  var frame = document.getElementById('folia-lyrics-frame');
  var ready = !!(frame && foliaFrameState.ready);
  var hasLines = foliaLyricHasLyricLines();
  if (ready && hasLines) {
    foliaLyricPendingStageHide = false;
    foliaLyricHideStageLyrics();
    return;
  }
  // 内嵌页还没起来或没有歌词：先让内置歌词顶上，避免整屏没有歌词
  foliaLyricRestoreStageLyrics();
}

function foliaLyricArmReadyGuard() {
  clearTimeout(foliaLyricReadyGuardTimer);
  foliaLyricReadyGuardTimer = setTimeout(function () {
    if (foliaLyricState.index < 0) return;
    if (!foliaFrameState.ready) {
      foliaLyricRestoreStageLyrics();
      if (typeof showToast === 'function') showToast('Folia 画面还没加载好，已先用内置歌词');
      return;
    }
    foliaLyricApplyStageLyricsVisibility();
  }, 4000);
}

function setFoliaLyricPreset(index, opts) {
  opts = opts || {};
  var i = Number(index);
  if (!isFinite(i)) return;
  i = Math.round(i);
  var preset = FOLIA_LYRIC_PRESETS[i];
  if (!preset) return;
  if (!opts.silent) foliaLyricMarkUserChoice();

  var target = preset;
  var isRandom = preset.id === 'random';
  if (isRandom) {
    // 随机池：用户在控制台里勾选的预设；再点一次「随机」= 重新抽一个，不关闭
    var poolIds = [];
    if (typeof foliaRandomPool === 'function') {
      poolIds = foliaRandomPool();
    }
    if (!poolIds.length) {
      for (var k = 0; k < FOLIA_LYRIC_PRESETS.length; k += 1) {
        if (FOLIA_LYRIC_PRESETS[k].id !== 'random') poolIds.push(FOLIA_LYRIC_PRESETS[k].id);
      }
    }
    var previousId = foliaLyricState.activeId;
    var candidates = poolIds.filter(function (id) { return id !== previousId; });
    if (!candidates.length) candidates = poolIds;
    var pickedId = candidates[Math.floor(Math.random() * candidates.length)];
    for (var j = 0; j < FOLIA_LYRIC_PRESETS.length; j += 1) {
      if (FOLIA_LYRIC_PRESETS[j].id === pickedId) { target = FOLIA_LYRIC_PRESETS[j]; break; }
    }
  } else if (i === foliaLyricState.index && !opts.force) {
    clearFoliaLyricPreset();
    return;
  }

  foliaLyricState.index = i;
  foliaLyricState.activeId = target.id;
  foliaLyricState.isRandom = isRandom;
  try { localStorage.setItem(FOLIA_LYRIC_STORAGE_KEY, String(i)); } catch (e) { /* 忽略 */ }

  var layer = document.getElementById('folia-lyrics-layer');
  if (layer) {
    layer.classList.add('active');
    layer.setAttribute('data-folia-style', target.id);
  }
  document.body.classList.add('folia-stage-active');
  foliaEnsureBackgroundSelected();
  foliaLyricEnsureFrame();
  foliaFrameState.lastSignature = '';
  foliaLyricRefreshCards();
  foliaLyricPendingStageHide = true;
  foliaLyricApplyStageLyricsVisibility();
  foliaLyricArmReadyGuard();
  foliaLyricStartTicker();
  foliaLyricSendConfig();
  if (!opts.silent && typeof showToast === 'function') {
    showToast(isRandom ? ('Folia 随机到：' + target.name) : ('Folia 歌词动画：' + target.name));
  }
  foliaStartStageLyricsHealer();
  if (typeof foliaConsoleOnPresetChanged === 'function') foliaConsoleOnPresetChanged();
}

function clearFoliaLyricPreset() {
  foliaLyricState.index = -1;
  foliaLyricState.activeId = '';
  foliaLyricState.isRandom = false;
  foliaFrameState.lastSignature = '';
  try { localStorage.removeItem(FOLIA_LYRIC_STORAGE_KEY); } catch (e) { /* 忽略 */ }
  var layer = document.getElementById('folia-lyrics-layer');
  if (layer) {
    layer.classList.remove('active');
    layer.removeAttribute('data-folia-style');
  }
  document.body.classList.remove('folia-stage-active');
  foliaLyricPendingStageHide = false;
  clearTimeout(foliaLyricReadyGuardTimer);
  foliaLyricStopTicker();
  foliaLyricRestoreStageLyrics();
  foliaLyricRefreshCards();
  foliaHealStageLyrics();
  if (typeof foliaConsoleOnPresetChanged === 'function') foliaConsoleOnPresetChanged();
}

function initFoliaLyricPresets() {
  if (!document.getElementById('folia-preset-grid')) return;
  if (!foliaLyricState.built) foliaLyricBuildGrid();
  foliaFrameState.backgroundMode = foliaLyricSavedBackgroundMode();
  foliaFrameState.motionMode = foliaLyricSavedMotionMode();
  foliaLyricUpdateModeChips();
  var saved = foliaLyricSavedIndex();
  // 恢复前必须确认是用户亲手选过；旧版本遗留的脏值（含 null 被当成 0 写下的）
  // 一律清掉，保证默认进入就是「emily专辑封面」，Folia 不跟着开。
  if (saved >= 0 && !foliaLyricUserChosen()) {
    try { localStorage.removeItem(FOLIA_LYRIC_STORAGE_KEY); } catch (e) { /* 忽略 */ }
    saved = -1;
  }
  if (saved >= 0 && foliaLyricState.index < 0) {
    setFoliaLyricPreset(saved, { silent: true, force: true });
  }
  foliaStartStageLyricsHealer();
}

setTimeout(initFoliaLyricPresets, 0);