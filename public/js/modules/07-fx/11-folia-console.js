// ============================================================
// Folia 控制台（居中面板）
// - 把「Folia 预设 / 背景 / 动画」从右侧贴边控制台搬进居中弹层（类似在线壁纸面板）
// - 随机池：自由选择「随机」会抽到哪些预设，可全选、可换一个、可每首歌换
// - 各预设的调参在 13-folia-preset-tuning.js 里渲染（町景的场景/时段/天气也在其中）
// ============================================================

var FOLIA_RANDOM_POOL_KEY = 'mineradio-folia-random-pool-v1';
var FOLIA_RANDOM_PER_SONG_KEY = 'mineradio-folia-random-per-song-v1';
var foliaConsoleBuilt = false;

function foliaConsoleEscape(text) {
  if (typeof foliaLyricEscape === 'function') return foliaLyricEscape(text);
  return String(text == null ? '' : text).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

function foliaConsolePresetIds() {
  var out = [];
  if (typeof FOLIA_LYRIC_PRESETS === 'undefined' || !FOLIA_LYRIC_PRESETS) return out;
  for (var i = 0; i < FOLIA_LYRIC_PRESETS.length; i += 1) {
    var id = FOLIA_LYRIC_PRESETS[i] && FOLIA_LYRIC_PRESETS[i].id;
    if (id && id !== 'random') out.push(id);
  }
  return out;
}

function foliaRandomPool() {
  var all = foliaConsolePresetIds();
  var saved = null;
  try { saved = JSON.parse(localStorage.getItem(FOLIA_RANDOM_POOL_KEY) || 'null'); } catch (e) { saved = null; }
  if (!Array.isArray(saved)) return all;
  var picked = [];
  for (var i = 0; i < saved.length; i += 1) {
    if (all.indexOf(saved[i]) >= 0 && picked.indexOf(saved[i]) < 0) picked.push(saved[i]);
  }
  return picked.length ? picked : all;
}

function foliaRandomSavePool(pool) {
  var all = foliaConsolePresetIds();
  var picked = [];
  for (var i = 0; i < pool.length; i += 1) {
    if (all.indexOf(pool[i]) >= 0 && picked.indexOf(pool[i]) < 0) picked.push(pool[i]);
  }
  if (!picked.length) picked = all;
  try { localStorage.setItem(FOLIA_RANDOM_POOL_KEY, JSON.stringify(picked)); } catch (e) { /* 忽略 */ }
  foliaConsoleSyncRandom();
  return picked;
}

function foliaRandomTogglePreset(id) {
  var pool = foliaRandomPool();
  var index = pool.indexOf(id);
  if (index >= 0) {
    if (pool.length <= 1) return foliaRandomPool();
    pool.splice(index, 1);
  } else {
    pool.push(id);
  }
  return foliaRandomSavePool(pool);
}

function foliaRandomPerSong() {
  try { return localStorage.getItem(FOLIA_RANDOM_PER_SONG_KEY) === '1'; } catch (e) { return false; }
}

function foliaRandomSetPerSong(on) {
  try { localStorage.setItem(FOLIA_RANDOM_PER_SONG_KEY, on ? '1' : '0'); } catch (e) { /* 忽略 */ }
  foliaConsoleSyncRandom();
}

// 从随机池里抽一个（尽量避开 exceptId）
function foliaRandomPick(exceptId) {
  var pool = foliaRandomPool();
  if (!pool.length) pool = foliaConsolePresetIds();
  if (!pool.length) return '';
  var candidates = pool.filter(function (id) { return id !== exceptId; });
  if (!candidates.length) candidates = pool;
  return candidates[Math.floor(Math.random() * candidates.length)];
}

// 供 10-folia-lyrics-preset.js 调用：随机预设真正选中的可视化器
function foliaRandomResolveMode() {
  return foliaRandomPick('');
}

// 换一个（随机模式下再抽一次）
function foliaRandomReroll(opts) {
  opts = opts || {};
  if (typeof foliaLyricState === 'undefined' || !foliaLyricState) return false;
  var current = foliaLyricState.activeId;
  var next = foliaRandomPick(opts.perSong ? current : '');
  if (!next || next === current) return false;
  foliaLyricState.activeId = next;
  foliaFrameState.lastSignature = '';
  if (typeof foliaLyricEnsureFrame === 'function') foliaLyricEnsureFrame();
  if (typeof foliaLyricSendConfig === 'function') foliaLyricSendConfig();
  if (typeof foliaLyricStartTicker === 'function') foliaLyricStartTicker();
  var layer = document.getElementById('folia-lyrics-layer');
  if (layer) {
    layer.classList.add('active');
    layer.setAttribute('data-folia-style', next);
  }
  document.body.classList.add('folia-stage-active');
  if (typeof foliaLyricHideStageLyrics === 'function') foliaLyricHideStageLyrics();
  if (!opts.silent && typeof showToast === 'function') {
    var label = next;
    for (var i = 0; i < FOLIA_LYRIC_PRESETS.length; i += 1) {
      if (FOLIA_LYRIC_PRESETS[i].id === next) { label = FOLIA_LYRIC_PRESETS[i].name; break; }
    }
    showToast('随机到：' + label);
  }
  foliaConsoleSyncEntry();
  foliaConsoleSyncRandom();
  if (typeof foliaTuningRender === 'function') foliaTuningRender();
  return true;
}

// 每首歌换一次（由 ticker 在签名变化时调用）
function foliaRandomOnSongChange() {
  if (typeof foliaLyricState === 'undefined' || !foliaLyricState) return false;
  if (foliaLyricState.index < 0 || !foliaLyricState.isRandom) return false;
  if (!foliaRandomPerSong()) return false;
  return foliaRandomReroll({ perSong: true, silent: true });
}

function foliaConsoleBuildRandomGrid() {
  var grid = document.getElementById('folia-random-pool-grid');
  if (!grid) return;
  var pool = foliaRandomPool();
  var html = '';
  for (var i = 0; i < FOLIA_LYRIC_PRESETS.length; i += 1) {
    var preset = FOLIA_LYRIC_PRESETS[i];
    if (!preset || preset.id === 'random') continue;
    html += '<button type="button" class="folia-chip' + (pool.indexOf(preset.id) >= 0 ? ' active' : '') +
      '" data-folia-pool="' + preset.id + '">' + foliaConsoleEscape(preset.name) + '</button>';
  }
  grid.innerHTML = html;
}

function foliaConsoleSyncRandom() {
  var section = document.getElementById('folia-random-section');
  var active = typeof foliaLyricState !== 'undefined' && foliaLyricState && foliaLyricState.index >= 0 && foliaLyricState.isRandom;
  if (section) section.hidden = !active;
  if (!active) return;
  foliaConsoleBuildRandomGrid();
  var pool = foliaRandomPool();
  var total = foliaConsolePresetIds().length;
  var count = document.getElementById('folia-random-count');
  if (count) count.textContent = '已选 ' + pool.length + '/' + total;
  var perSong = document.getElementById('folia-random-persong');
  if (perSong) perSong.classList.toggle('active', foliaRandomPerSong());
  var currentName = document.getElementById('folia-random-current');
  if (currentName) {
    var label = '';
    for (var i = 0; i < FOLIA_LYRIC_PRESETS.length; i += 1) {
      if (FOLIA_LYRIC_PRESETS[i].id === foliaLyricState.activeId) { label = FOLIA_LYRIC_PRESETS[i].name; break; }
    }
    currentName.textContent = label ? ('本次：' + label) : '';
  }
}

function foliaConsoleOnRandomClick(event) {
  var target = event.target && event.target.closest ? event.target.closest('[data-folia-pool]') : null;
  if (!target) return;
  foliaRandomTogglePreset(target.getAttribute('data-folia-pool'));
}

function foliaRandomSelectAll() {
  foliaRandomSavePool(foliaConsolePresetIds());
}

function foliaConsoleActivePresetLabel() {
  var index = (typeof foliaLyricState !== 'undefined' && foliaLyricState) ? foliaLyricState.index : -1;
  if (typeof FOLIA_LYRIC_PRESETS === 'undefined' || index < 0) return '未启用';
  var preset = FOLIA_LYRIC_PRESETS[index];
  if (!preset) return '未启用';
  if (preset.id === 'random') {
    var current = '';
    for (var i = 0; i < FOLIA_LYRIC_PRESETS.length; i += 1) {
      if (FOLIA_LYRIC_PRESETS[i].id === foliaLyricState.activeId) { current = FOLIA_LYRIC_PRESETS[i].name; break; }
    }
    return current ? ('随机 · ' + current) : '随机';
  }
  return preset.name;
}

function foliaConsoleSyncEntry() {
  var title = document.getElementById('fx-folia-entry-title');
  var sub = document.getElementById('fx-folia-entry-sub');
  var label = foliaConsoleActivePresetLabel();
  if (title) title.textContent = label === '未启用' ? 'Folia 控制台' : 'Folia · ' + label;
  if (sub) {
    var isRandom = typeof foliaLyricState !== 'undefined' && foliaLyricState && foliaLyricState.isRandom;
    sub.textContent = isRandom
      ? ('随机池 ' + foliaRandomPool().length + ' 个 · 可换一个 / 每首歌换')
      : '18 个歌词动画 · 背景 / 动效 / 预设调参';
  }
}

function foliaConsoleEnsureBuilt() {
  if (foliaConsoleBuilt) return;
  var modal = document.getElementById('folia-console-modal');
  if (!modal) return;
  if (typeof foliaLyricBuildGrid === 'function' && typeof foliaLyricState !== 'undefined' && !foliaLyricState.built) {
    foliaLyricBuildGrid();
  }
  modal.addEventListener('click', foliaConsoleOnRandomClick);
  foliaConsoleBuilt = true;
}

function foliaConsoleSyncAll() {
  if (!foliaConsoleBuilt) return;
  if (typeof foliaLyricRefreshCards === 'function') foliaLyricRefreshCards();
  if (typeof foliaLyricUpdateModeChips === 'function') foliaLyricUpdateModeChips();
  foliaConsoleSyncRandom();
  foliaConsoleSyncEntry();
  if (typeof foliaTuningRender === 'function') foliaTuningRender();
}

function openFoliaConsole() {
  var modal = document.getElementById('folia-console-modal');
  if (!modal) return;
  foliaConsoleEnsureBuilt();
  foliaConsoleSyncAll();
  modal.classList.add('show');
  document.body.classList.add('folia-console-open');
  if (typeof closeFxConsolePopovers === 'function') closeFxConsolePopovers();
}

function closeFoliaConsole() {
  var modal = document.getElementById('folia-console-modal');
  if (modal) modal.classList.remove('show');
  document.body.classList.remove('folia-console-open');
}

function toggleFoliaConsole() {
  var modal = document.getElementById('folia-console-modal');
  if (modal && modal.classList.contains('show')) closeFoliaConsole();
  else openFoliaConsole();
}

// 预设切换后由 10-folia-lyrics-preset.js 调用
function foliaConsoleOnPresetChanged() {
  foliaConsoleSyncAll();
}

// 内嵌页 ready 后补推一次调参（内嵌页是启动时读存档的，父层改动要同步进去）
function foliaConsoleOnFrameReady() {
  foliaConsoleSyncAll();
  if (typeof foliaTuningPushAll === 'function') foliaTuningPushAll();
}

setTimeout(function () {
  foliaConsoleEnsureBuilt();
  foliaConsoleSyncAll();
}, 0);