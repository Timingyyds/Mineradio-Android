// 在线歌词源（跨平台歌词兜底 + 手动搜索）
//
// 要解决的问题：
//   汽水音乐这类平台经常「接口通了但歌词是空的」，界面上就只剩一行占位
//   「歌名 - 歌手」——看起来像软件坏了，其实别的平台有词。
//
// 这里的做法和 Folia 一样：当前平台的歌词不可用时，自动拿「歌名 + 歌手」去
// 别的平台搜，按顺序（默认 QQ → 网易云 → 酷狗 → 汽水 → 波点）找一份能用的歌词，
// 直接套用到当前歌曲上，并写进歌词缓存（缓存按「当前这首歌」记，所以与平台无关，
// 换哪个平台放同一首歌都能秒出）。
//
// 另外给「歌词源」设置里加了一组入口：
//   · 自动跨平台：开关（默认开）
//   · 优先 QQ / 优先网易云：切换自动匹配的顺序
//   · 搜索歌词…：手动搜索任意平台的歌词，点「应用」就套用并缓存
//
// 所有网络请求都走已有的 apiJson（和播放、搜索同一套路由），不额外引依赖。

var LYRIC_ONLINE_SETTINGS_KEY = 'mineradio-lyric-online-source-v2';
var LYRIC_ONLINE_APPLIED_KEY = 'mineradio-lyric-online-applied-v1';
var LYRIC_ONLINE_DEFAULT_ORDER = ['qq', 'netease', 'kugou', 'qishui', 'bodian'];
var LYRIC_ONLINE_SEARCH_LIMIT = 6;
var LYRIC_ONLINE_MISS_TTL = 10 * 60 * 1000;

/** HTML 转义（这个文件自己用；别的模块的 esc 是局部函数，不能跨文件用）。 */
function lyricOnlineEsc(value) {
  return String(value == null ? '' : value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

var lyricOnlineState = {
  enabled: false,   // 默认不自动匹配：没歌词时先提示，用户点一下再匹配
  promptedKey: '',  // 已经提示过的歌，避免同一首反复弹
  order: [],
  busy: false,
  searching: false,
  results: [],
  searchToken: 0,
  missCache: {},
  applied: {},
  searchQuery: ''
};

(function lyricOnlineLoadSettings() {
  try {
    var raw = window.localStorage && window.localStorage.getItem(LYRIC_ONLINE_SETTINGS_KEY);
    var data = raw ? JSON.parse(raw) : null;
    if (data && typeof data === 'object') {
      if (typeof data.enabled === 'boolean') lyricOnlineState.enabled = data.enabled;
      if (typeof data.promptedKey === 'string') lyricOnlineState.promptedKey = '';
      if (Array.isArray(data.order) && data.order.length) {
        lyricOnlineState.order = data.order.filter(function (item) {
          return LYRIC_ONLINE_DEFAULT_ORDER.indexOf(item) >= 0;
        });
      }
    }
  } catch (_error) { /* 读不到设置就用默认值 */ }
  if (!lyricOnlineState.order.length) lyricOnlineState.order = LYRIC_ONLINE_DEFAULT_ORDER.slice();
  try {
    var appliedRaw = window.localStorage && window.localStorage.getItem(LYRIC_ONLINE_APPLIED_KEY);
    var applied = appliedRaw ? JSON.parse(appliedRaw) : null;
    if (applied && typeof applied === 'object') lyricOnlineState.applied = applied;
  } catch (_error) { /* 忽略 */ }
})();

function lyricOnlineSaveSettings() {
  try {
    if (!window.localStorage) return;
    window.localStorage.setItem(LYRIC_ONLINE_SETTINGS_KEY, JSON.stringify({
      enabled: !!lyricOnlineState.enabled,
      order: lyricOnlineState.order.slice()
    }));
  } catch (_error) { /* 存储满就只在本次生效 */ }
}

function lyricOnlineProviderLabel(provider) {
  var labels = { qq: 'QQ 音乐', netease: '网易云', kugou: '酷狗', qishui: '汽水', bodian: '波点', ytm: 'YouTube Music' };
  if (typeof sourceLabel === 'function') {
    try {
      var label = sourceLabel(provider);
      if (label && label !== '在线音乐') return label;
    } catch (_error) { /* 用下面的表 */ }
  }
  return labels[provider] || provider;
}

// 搜索用的关键词：歌名去掉括号里的 (feat. / remix / live) 噪音，再拼第一个歌手
function lyricOnlineQueryText(song) {
  song = song || {};
  var title = String(song.name || song.title || '').trim();
  var artist = String(song.artist || song.singer || song.singerName || '').split(/\s*\/\s*|\s*,\s*|&/)[0].trim();
  title = title.replace(/\s*[\(（\[【].*?[\)）\]】]\s*/g, ' ').replace(/\s+/g, ' ').trim();
  return [title, artist].filter(Boolean).join(' ');
}

function lyricOnlineSearchEndpoint(provider, query, limit) {
  var size = Math.max(1, Math.min(Number(limit) || LYRIC_ONLINE_SEARCH_LIMIT, 20));
  var prefix = {
    qq: '/api/qq/search?keywords=',
    kugou: '/api/kugou/search?keywords=',
    qishui: '/api/qishui/search?keywords=',
    bodian: '/api/bodian/search?keywords=',
    netease: '/api/search?keywords='
  }[provider];
  if (!prefix) return '';
  return prefix + encodeURIComponent(query) + '&limit=' + size;
}

/** 去某个平台搜同名歌，返回候选数组（每项带 provider，方便后面拼歌词接口地址）。 */
async function lyricOnlineSearchProvider(provider, query, limit) {
  var url = lyricOnlineSearchEndpoint(provider, query, limit);
  if (!url) return [];
  var data = await apiJson(url, { timeoutMs: 6500 });
  var raw = data && (data.songs || data.tracks || data.items || data.result || data.data || data.list);
  if (!Array.isArray(raw)) return [];
  var out = [];
  for (var i = 0; i < raw.length; i += 1) {
    var item = raw[i];
    if (!item || typeof item !== 'object') continue;
    try {
      if (!item.provider) item.provider = provider;
      if (!item.source) item.source = provider;
    } catch (_error) { /* 冻结对象就跳过打标 */ }
    if (!(item.id || item.mid || item.songmid || item.hash || item.rid)) continue;
    out.push(item);
  }
  return out;
}

function lyricOnlineCandidateScore(song, candidate) {
  if (typeof scoreSongSearchResult === 'function') {
    try {
      return Number(scoreSongSearchResult(candidate, lyricOnlineQueryText(song), 0)) || 0;
    } catch (_error) { /* 走下面的兜底 */ }
  }
  return 0;
}

/** 挑一条最像的：先看「同名 + 同歌手」，没有就按打分的最高分（太低不要，避免套错词）。 */
function lyricOnlinePickBest(song, list) {
  list = Array.isArray(list) ? list : [];
  if (!list.length) return null;
  for (var i = 0; i < list.length; i += 1) {
    if (typeof isSameTitleArtist === 'function') {
      try {
        if (isSameTitleArtist(song, list[i])) return list[i];
      } catch (_error) { /* 继续 */ }
    }
  }
  var best = null;
  var bestScore = -Infinity;
  for (var j = 0; j < list.length; j += 1) {
    var score = lyricOnlineCandidateScore(song, list[j]);
    if (score > bestScore) { bestScore = score; best = list[j]; }
  }
  return bestScore >= 24 ? best : null;
}

/** 取某个候选歌曲的歌词，解析成可用状态；拿不到可用歌词返回 null。 */
async function lyricOnlineFetchCandidate(candidate) {
  if (!candidate || typeof lyricEndpointForSong !== 'function') return null;
  var endpoint = '';
  try {
    endpoint = lyricEndpointForSong(candidate);
  } catch (_error) {
    return null;
  }
  if (!endpoint) return null;
  var response = await apiJson(endpoint, { timeoutMs: 8000 });
  var merged = mergeInlineLyricResponseForSong(candidate, response || {});
  var state = parseLyricResponseToOriginalState(candidate, merged);
  if (!state || !state.usableLyric) return null;
  return { merged: merged, state: state, endpoint: endpoint, candidate: candidate };
}

function lyricOnlineSongKey(song) {
  try {
    return persistentLyricCacheKey(song || {});
  } catch (_error) {
    return '';
  }
}

function lyricOnlineRemember(song, candidate) {
  var key = lyricOnlineSongKey(song);
  if (!key) return;
  lyricOnlineState.applied[key] = {
    provider: (candidate && (candidate.provider || candidate.source)) || '',
    id: candidate && (candidate.id || candidate.mid || candidate.hash || candidate.rid || '') || '',
    at: Date.now()
  };
  try {
    if (window.localStorage) window.localStorage.setItem(LYRIC_ONLINE_APPLIED_KEY, JSON.stringify(lyricOnlineState.applied));
  } catch (_error) { /* 忽略 */ }
}

function lyricOnlineAppliedProvider(song) {
  var key = lyricOnlineSongKey(song);
  if (!key) return '';
  var item = lyricOnlineState.applied[key];
  return (item && item.provider) || '';
}

/** 把某平台的歌词套到当前歌曲上，并写进歌词缓存（缓存与平台无关，换平台也通用）。 */
function lyricOnlineApplyPayload(song, candidate, merged, token) {
  if (!merged) return false;
  if (token != null && token !== trackSwitchToken) return false;
  var state = applyFetchedLyricResponse(song, token, merged, { persist: true });
  if (!state || !state.usableLyric) return false;
  lyricOnlineRemember(song, candidate);
  return true;
}

/**
 * 自动兜底：当前平台歌词不可用时被 fetchLyric 调用。
 * 延迟一点再发请求，避免和播放器起播、封面加载抢带宽。
 */
function scheduleLyricOnlineFallback(song, token, attempt) {
  if (!lyricOnlineState.enabled) return false;
  if (!song || typeof song !== 'object') return false;
  if (token != null && token !== trackSwitchToken) return false;
  // 只跳过播客（节目不是单曲，按歌名搜会搜到莫名其妙的东西）。
  // 其它情况——汽水/YouTube/酷狗/本地文件……只要当前没有可用歌词，一律走这套兜底。
  if (song.type === 'podcast') return false;
  if (!String(song.name || song.title || '').trim()) return false;
  var key = lyricOnlineSongKey(song);
  if (!key) return false;
  var missAt = lyricOnlineState.missCache[key] || 0;
  if (missAt && Date.now() - missAt < LYRIC_ONLINE_MISS_TTL) return false;
  setTimeout(function () {
    if (token != null && token !== trackSwitchToken) return;
    if (hasUsableLyricLines(originalLyricsState && originalLyricsState.lines)) return;
    if (lyricOnlineState.enabled) {
      // 用户自己打开了「无歌词时自动匹配」才真的去搜
      lyricOnlineAutoFetch(song, token);
      return;
    }
    // 默认行为：不偷偷去搜，只在控制栏上方闪一个黄色提示，点一下再匹配
    lyricOnlineShowHint(song);
  }, 460);
  return true;
}

async function lyricOnlineAutoFetch(song, token) {
  if (lyricOnlineState.busy) return false;
  song = song || (typeof currentLyricSong === 'function' ? currentLyricSong() : null);
  if (!song) return false;
  var query = lyricOnlineQueryText(song);
  if (!query) return false;
  var key = lyricOnlineSongKey(song);
  lyricOnlineState.busy = true;
  try {
    var order = lyricOnlineState.order.slice();
    /*
     * 各平台**并行**搜索，再按设置里的优先级取用。
     * 串行的话 QQ 搜索实测要 8 秒，轮到网易云时早就过时了；并行把等待压到最慢那一家。
     */
    var searches = order.map(function (provider) {
      return lyricOnlineSearchProvider(provider, query, LYRIC_ONLINE_SEARCH_LIMIT).catch(function (error) {
        console.warn('[LyricOnline] 搜索失败 ' + provider + '：' + (error && error.message || error));
        return [];
      });
    });
    var lists = await Promise.all(searches);
    if (token != null && token !== trackSwitchToken) return false;
    for (var i = 0; i < order.length; i += 1) {
      var provider = order[i];
      var best = lyricOnlinePickBest(song, lists[i]);
      if (!best) continue;
      var payload = null;
      try {
        payload = await lyricOnlineFetchCandidate(best);
      } catch (error) {
        console.warn('[LyricOnline] 取歌词失败 ' + provider + '：' + (error && error.message || error));
      }
      if (token != null && token !== trackSwitchToken) return false;
      if (!payload) continue;
      if (lyricOnlineApplyPayload(song, best, payload.merged, token)) {
        lyricOnlineHideHint();
        console.log('[LyricOnline] 已自动匹配 ' + lyricOnlineProviderLabel(provider) + ' 歌词：' + (best.name || ''));
        if (typeof showToast === 'function') showToast('已自动匹配' + lyricOnlineProviderLabel(provider) + '歌词');
        return true;
      }
    }
    if (key) lyricOnlineState.missCache[key] = Date.now();
    console.log('[LyricOnline] 各平台都没找到可用歌词：' + query);
    return false;
  } finally {
    lyricOnlineState.busy = false;
  }
}

// ---------- 「暂无歌词」黄色闪烁提示 ----------

function lyricOnlineHintEl() {
  return document.getElementById('lyric-online-hint');
}

function lyricOnlineShowHint(song) {
  var key = lyricOnlineSongKey(song || (typeof currentLyricSong === 'function' ? currentLyricSong() : null));
  if (!key) return false;
  if (lyricOnlineState.promptedKey === key && !lyricOnlineState.enabled) {
    // 同一首歌只提示一次；换歌会重置
    var existing = lyricOnlineHintEl();
    if (existing && !existing.hidden) return true;
  }
  lyricOnlineState.promptedKey = key;
  var el = lyricOnlineHintEl();
  if (!el) return false;
  el.hidden = false;
  console.log('[LyricOnline] 本首没有可用歌词，已提示用户手动匹配：' + (song && (song.name || song.title) || ''));
  return true;
}

function lyricOnlineHideHint() {
  var el = lyricOnlineHintEl();
  if (el) el.hidden = true;
}

/** 打开歌词校准弹层（点一下提示就走这里），里面有「自动匹配歌词」。 */
function lyricOnlineOpenTimingPanel() {
  var root = document.getElementById('lyric-timing-control');
  if (!root) return;
  if (typeof document !== 'undefined' && document.body) document.body.classList.add('controls-visible');
  root.classList.add('pinned-open');
  if (typeof clearLyricTimingPopoverClose === 'function') clearLyricTimingPopoverClose();
  if (typeof updateLyricTimingOffsetUi === 'function') updateLyricTimingOffsetUi();
  if (typeof suppressLyricTimingSiblingPanels === 'function') suppressLyricTimingSiblingPanels(true);
  // 点面板外面就收起
  setTimeout(function () {
    document.addEventListener('pointerdown', function closePinned(event) {
      if (root.contains(event.target)) return;
      root.classList.remove('pinned-open');
      if (typeof suppressLyricTimingSiblingPanels === 'function') suppressLyricTimingSiblingPanels(false);
      document.removeEventListener('pointerdown', closePinned, true);
    }, true);
  }, 50);
}

function lyricOnlineHintClick() {
  lyricOnlineHideHint();
  lyricOnlineOpenTimingPanel();
}

// ---------- 设置面板上的入口 ----------

function lyricOnlineOrderPrimary() {
  return lyricOnlineState.order[0] || 'qq';
}

function renderLyricOnlineControls() {
  var autoBtn = document.getElementById('lyric-online-auto-btn');
  if (autoBtn) {
    autoBtn.classList.toggle('active', !!lyricOnlineState.enabled);
    autoBtn.textContent = lyricOnlineState.enabled ? '无歌词时自动匹配 · 开' : '无歌词时自动匹配 · 关';
  }
  var orderBtn = document.getElementById('lyric-online-order-btn');
  if (orderBtn) {
    var primary = lyricOnlineOrderPrimary();
    orderBtn.textContent = '优先' + lyricOnlineProviderLabel(primary);
    orderBtn.title = '当前顺序：' + lyricOnlineState.order.map(lyricOnlineProviderLabel).join(' → ');
  }
  var nowBtn = document.getElementById('lyric-online-now-btn');
  if (nowBtn) nowBtn.disabled = !!lyricOnlineState.busy;
}

function toggleLyricOnlineFallback() {
  lyricOnlineState.enabled = !lyricOnlineState.enabled;
  lyricOnlineSaveSettings();
  renderLyricOnlineControls();
  if (typeof showToast === 'function') {
    showToast(lyricOnlineState.enabled ? '已开启跨平台歌词兜底' : '已关闭跨平台歌词兜底');
  }
  // 刚打开时，如果当前这首歌还是占位歌词，立刻补一次
  if (lyricOnlineState.enabled) {
    var song = typeof currentLyricSong === 'function' ? currentLyricSong() : null;
    if (song && !hasUsableLyricLines(originalLyricsState && originalLyricsState.lines)) lyricOnlineAutoFetch(song, trackSwitchToken);
  }
}

function cycleLyricOnlineOrder() {
  var order = lyricOnlineState.order;
  if (order.length < 2) return;
  // 把当前优先的平台挪到末尾，下一个顶上（QQ ⇄ 网易云 来回切最常用）
  var first = order.shift();
  order.push(first);
  lyricOnlineSaveSettings();
  renderLyricOnlineControls();
  if (typeof showToast === 'function') showToast('在线歌词优先：' + lyricOnlineProviderLabel(order[0]));
}

/** 点「自动搜索」：立刻给当前这首歌跨平台找一次歌词。 */
async function lyricOnlineAutoNow() {
  var song = typeof currentLyricSong === 'function' ? currentLyricSong() : null;
  if (!song) return;
  if (lyricOnlineState.busy) return;
  if (typeof showToast === 'function') showToast('正在各平台搜索歌词…');
  renderLyricOnlineControls();
  var ok = await lyricOnlineAutoFetch(song, trackSwitchToken);
  renderLyricOnlineControls();
  if (!ok && typeof showToast === 'function') showToast('各平台都没有找到可用歌词');
}

// ---------- 手动搜索弹窗 ----------

function ensureLyricOnlineModal() {
  var mask = document.getElementById('lyric-online-modal');
  if (mask) return mask;
  mask = document.createElement('div');
  mask.id = 'lyric-online-modal';
  mask.className = 'lyric-online-mask';
  mask.innerHTML =
    '<div class="lyric-online-panel" role="dialog" aria-label="在线歌词搜索">' +
    '<div class="lyric-online-head"><strong>在线歌词搜索</strong>' +
    '<button type="button" class="lyric-online-close" onclick="closeLyricOnlineSearch()">×</button></div>' +
    '<div class="lyric-online-search-row">' +
    '<input id="lyric-online-input" type="text" placeholder="歌名 歌手" autocomplete="off">' +
    '<button type="button" class="lyric-online-btn" onclick="lyricOnlineSearchNow()">搜索</button>' +
    '</div>' +
    '<div class="lyric-online-status" id="lyric-online-status"></div>' +
    '<div class="lyric-online-results" id="lyric-online-results"></div>' +
    '</div>';
  mask.addEventListener('click', function (event) {
    if (event.target === mask) closeLyricOnlineSearch();
  });
  document.body.appendChild(mask);
  var input = mask.querySelector('#lyric-online-input');
  if (input) {
    input.addEventListener('keydown', function (event) {
      if (event.key === 'Enter') {
        event.preventDefault();
        lyricOnlineSearchNow();
      }
    });
  }
  return mask;
}

function openLyricOnlineSearch() {
  var mask = ensureLyricOnlineModal();
  var song = typeof currentLyricSong === 'function' ? currentLyricSong() : null;
  var input = mask.querySelector('#lyric-online-input');
  if (input) {
    var query = lyricOnlineQueryText(song);
    input.value = query || '';
  }
  mask.classList.add('show');
  updateLyricOnlineStatus('可搜：' + lyricOnlineState.order.map(lyricOnlineProviderLabel).join(' / '));
  lyricOnlineState.results = [];
  renderLyricOnlineResults();
  if (input && input.value) lyricOnlineSearchNow();
}

function closeLyricOnlineSearch() {
  var mask = document.getElementById('lyric-online-modal');
  if (mask) mask.classList.remove('show');
}

function updateLyricOnlineStatus(text, isError) {
  var el = document.getElementById('lyric-online-status');
  if (!el) return;
  el.textContent = text || '';
  el.classList.toggle('error', !!isError);
}

async function lyricOnlineSearchNow() {
  var mask = ensureLyricOnlineModal();
  var input = mask.querySelector('#lyric-online-input');
  var query = String(input && input.value || '').trim();
  if (!query) {
    updateLyricOnlineStatus('先输入歌名或歌手', true);
    return;
  }
  if (lyricOnlineState.searching) return;
  lyricOnlineState.searching = true;
  lyricOnlineState.searchQuery = query;
  var token = ++lyricOnlineState.searchToken;
  lyricOnlineState.results = [];
  renderLyricOnlineResults();
  updateLyricOnlineStatus('正在各平台搜索…');
  var providers = lyricOnlineState.order.slice();
  // 同样并行搜：手动搜索也要快，不然点一下要等十几秒
  var lists = await Promise.all(providers.map(function (provider) {
    return lyricOnlineSearchProvider(provider, query, LYRIC_ONLINE_SEARCH_LIMIT).catch(function () { return []; });
  }));
  if (token !== lyricOnlineState.searchToken) return;
  var currentSong = typeof currentLyricSong === 'function' ? currentLyricSong() : null;
  for (var i = 0; i < providers.length; i += 1) {
    var provider = providers[i];
    var list = Array.isArray(lists[i]) ? lists[i] : [];
    if (!list.length) continue;
    var same = currentSong ? lyricOnlinePickBest(currentSong, list) : null;
    // 同名的排前面，其余按顺序跟在后面
    var ordered = same ? [same].concat(list.filter(function (item) { return item !== same; })) : list;
    lyricOnlineState.results = lyricOnlineState.results.concat(ordered.map(function (item) {
      return { provider: provider, song: item };
    }));
  }
  renderLyricOnlineResults();
  lyricOnlineState.searching = false;
  updateLyricOnlineStatus(lyricOnlineState.results.length
    ? '找到 ' + lyricOnlineState.results.length + ' 条，点「应用」套用并缓存'
    : '没有搜到结果，换个关键词试试', !lyricOnlineState.results.length);
}

function lyricOnlineResultSubtitle(item) {
  var song = (item && item.song) || {};
  var artist = String(song.artist || song.singer || song.singerName || '').trim();
  var album = String(song.album || '').trim();
  return [artist, album].filter(Boolean).join(' · ') || '未知歌手';
}

function renderLyricOnlineResults() {
  var box = document.getElementById('lyric-online-results');
  if (!box) return;
  var results = lyricOnlineState.results;
  if (!results.length) {
    box.innerHTML = '';
    return;
  }
  var html = [];
  for (var i = 0; i < results.length; i += 1) {
    var item = results[i];
    var song = item.song || {};
    html.push(
      '<div class="lyric-online-result-row" data-lyric-online-index="' + i + '">' +
      '<div class="lyric-online-row-copy"><strong>' + lyricOnlineEsc(song.name || song.title || '未知歌曲') + '</strong>' +
      '<small>' + lyricOnlineEsc(lyricOnlineResultSubtitle(item)) + '</small></div>' +
      '<span class="lyric-online-tag">' + lyricOnlineEsc(lyricOnlineProviderLabel(item.provider)) + '</span>' +
      '<button type="button" class="lyric-online-btn" onclick="applyLyricOnlineResult(' + i + ')">应用</button>' +
      '</div>'
    );
  }
  box.innerHTML = html.join('');
}

async function applyLyricOnlineResult(index) {
  var item = lyricOnlineState.results[index];
  if (!item) return;
  var song = typeof currentLyricSong === 'function' ? currentLyricSong() : null;
  if (!song) {
    updateLyricOnlineStatus('当前没有播放的歌曲', true);
    return;
  }
  updateLyricOnlineStatus('正在读取「' + (item.song.name || '') + '」的歌词…');
  var payload = null;
  try {
    payload = await lyricOnlineFetchCandidate(item.song);
  } catch (error) {
    payload = null;
  }
  if (!payload) {
    updateLyricOnlineStatus('这个版本没有可用歌词，换一条试试', true);
    return;
  }
  if (!lyricOnlineApplyPayload(song, item.song, payload.merged, trackSwitchToken)) {
    updateLyricOnlineStatus('套用失败（这首歌可能已经切走了）', true);
    return;
  }
  updateLyricOnlineStatus('已应用并缓存' + lyricOnlineProviderLabel(item.provider) + '歌词');
  if (typeof showToast === 'function') showToast('已应用' + lyricOnlineProviderLabel(item.provider) + '歌词');
  setTimeout(closeLyricOnlineSearch, 520);
}

document.addEventListener('DOMContentLoaded', function () {
  renderLyricOnlineControls();
});
setTimeout(renderLyricOnlineControls, 1200);