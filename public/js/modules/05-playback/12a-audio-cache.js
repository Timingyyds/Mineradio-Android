// 全平台音频缓存：任何平台「播过一次」的歌，下次（包括重启 App 之后）直接读本地文件秒播。
//
// 为什么需要它：
//   汽水 / 网易云 / QQ / 酷狗这些平台每次播放都要先请求一次「取播放地址」的接口，
//   汽水那套接口实测经常 5~20 秒才回一个地址，于是「点一首歌等半天」。
//   音频本身是固定的，只要把字节缓存到本地，第二次播放完全可以零请求直接起播。
//
// 设计要点：
//   1. **先查缓存再取地址**：命中就直接用本地流地址，连 /api/<平台>/song/url 都不发；
//   2. **首次播放不拖慢**：没命中时照常走原来的取址流程，播放开始后在后台悄悄下载缓存，
//      不阻塞起播、失败也不影响播放；
//   3. 缓存键用「平台 + 稳定歌曲 id」，与平台直链里的 expire/sign 无关，重启后仍然有效；
//   4. 原生侧按真实音频格式落盘（mp3/m4a/flac…），并用总容量上限做 LRU 清理。

var MR_AUDIO_CACHE_SETTINGS_KEY = 'mineradio-audio-cache-v1';
var MR_AUDIO_CACHE_PREFIX = 'mr:';
// 并发 3：下一首/下二/下三的预取任务能同时开跑（原生下载已在 bridge 线程池，
// 不再互相卡），再高就开始挤占当前歌播放流的带宽了。
var MR_AUDIO_CACHE_WARM_LIMIT = 3;

var mrAudioCacheState = {
  enabled: true,
  warming: {},      // key → true，避免同一首歌重复下载
  queue: [],
  active: 0,
  hits: 0,
  misses: 0
};
// key → 失败次数。同一首歌预取失败两次就不再折腾（直链多半已过期/被风控），
// 等它真正轮到播放时走正常取址 + 播后 warm，那个地址是新鲜的。
var mrAudioCacheWarmFailures = {};

(function mrAudioCacheLoadSettings() {
  try {
    var raw = window.localStorage && window.localStorage.getItem(MR_AUDIO_CACHE_SETTINGS_KEY);
    var data = raw ? JSON.parse(raw) : null;
    if (data && typeof data.enabled === 'boolean') mrAudioCacheState.enabled = data.enabled;
  } catch (_error) { /* 读不到就用默认：开启 */ }
})();

function mrAudioCacheSaveSettings() {
  try {
    if (window.localStorage) {
      window.localStorage.setItem(MR_AUDIO_CACHE_SETTINGS_KEY, JSON.stringify({ enabled: !!mrAudioCacheState.enabled }));
    }
  } catch (_error) { /* 忽略 */ }
}

function mrAudioCachePlugin() {
  var cap = window.Capacitor;
  return (cap && cap.Plugins && cap.Plugins.FoliaNative) || null;
}

/** 这些平台各自「稳定的歌曲 id」字段不一样，统一取出一个可做缓存键的值。 */
function mrAudioCacheSongId(provider, song) {
  song = song || {};
  if (provider === 'kugou') return song.hash || song.fileHash || song.audioHash || song.id || '';
  if (provider === 'qq') return song.mid || song.songmid || song.mediaMid || song.id || '';
  if (provider === 'bodian') return song.rid || song.id || '';
  if (provider === 'spotify') return song.spotifyId || song.id || '';
  return song.id || song.providerSongId || '';
}

function mrAudioCacheKey(provider, song) {
  var id = String(mrAudioCacheSongId(provider, song) || '').trim();
  if (!id) return '';
  return MR_AUDIO_CACHE_PREFIX + provider + ':' + id;
}

/** 这些平台不会走通用缓存：YouTube 自己有按 videoId 的缓存；Spotify 是受保护音频。 */
function mrAudioCacheProviderSupported(provider) {
  return provider === 'netease' || provider === 'qq' || provider === 'kugou' || provider === 'qishui' || provider === 'bodian';
}

function mrAudioCacheHeaders(provider) {
  var headers = {
    'User-Agent': navigator.userAgent,
    'Accept': '*/*'
  };
  if (provider === 'netease') headers.Referer = 'https://music.163.com/';
  else if (provider === 'qq') headers.Referer = 'https://y.qq.com/';
  else if (provider === 'kugou') headers.Referer = 'https://www.kugou.com/';
  else if (provider === 'qishui') headers.Referer = 'https://www.douyin.com/';
  else if (provider === 'bodian') headers.Referer = 'https://www.kuwo.cn/';
  return headers;
}

function mrAudioCacheIsLocalUrl(url) {
  return /^https?:\/\/(127\.0\.0\.1|localhost)(:|\/)/i.test(String(url || ''));
}

/** 只查本地缓存（不联网）：命中返回本地流地址，没命中返回空串。
 *  opts.quiet = true 时不计入命中/未命中统计（预取探测用，避免污染诊断数据）。 */
async function mrAudioCacheLookup(provider, song, opts) {
  if (!mrAudioCacheState.enabled) return '';
  if (!mrAudioCacheProviderSupported(provider)) return '';
  var key = mrAudioCacheKey(provider, song);
  if (!key) return '';
  var plugin = mrAudioCachePlugin();
  if (!plugin || typeof plugin.getCachedAudio !== 'function') return '';
  try {
    var hit = await plugin.getCachedAudio({ key: key });
    if (hit && hit.url) {
      if (!(opts && opts.quiet)) mrAudioCacheState.hits += 1;
      console.log('[AudioCache] 命中本地缓存，直接起播：' + key);
      return String(hit.url);
    }
  } catch (error) {
    console.warn('[AudioCache] 查缓存失败：' + (error && error.message || error));
  }
  if (!(opts && opts.quiet)) mrAudioCacheState.misses += 1;
  return '';
}

/**
 * 后台把这首歌下载成缓存文件（不阻塞当前播放）。
 * 只在「播放确实开始了」之后调用，失败就静默 —— 缓存是加分项，不能影响听歌。
 */
function mrAudioCacheWarm(provider, song, remoteUrl, opts) {
  opts = opts || {};
  if (!mrAudioCacheState.enabled) return false;
  if (!mrAudioCacheProviderSupported(provider)) return false;
  if (!remoteUrl || mrAudioCacheIsLocalUrl(remoteUrl)) return false;
  var key = mrAudioCacheKey(provider, song);
  if (!key) return false;
  if (mrAudioCacheState.warming[key]) return false;
  if ((mrAudioCacheWarmFailures[key] || 0) >= 2) return false;
  var plugin = mrAudioCachePlugin();
  if (!plugin || typeof plugin.registerCachedAudio !== 'function') return false;
  if (mrAudioCacheState.queue.length > 8) return false; // 队列太长说明网络本来就慢，别再加负担
  mrAudioCacheState.warming[key] = true;
  var job = { key: key, url: String(remoteUrl), headers: mrAudioCacheHeaders(provider) };
  // 预取任务插到队首：它们才是「下一首秒放」的关键；当前歌自己的重播 warm
  // 反正已经在播了，不急，排后面等空档。
  if (opts.priority) mrAudioCacheState.queue.unshift(job);
  else mrAudioCacheState.queue.push(job);
  mrAudioCachePump();
  return true;
}

function mrAudioCachePump() {
  while (mrAudioCacheState.active < MR_AUDIO_CACHE_WARM_LIMIT && mrAudioCacheState.queue.length) {
    var job = mrAudioCacheState.queue.shift();
    mrAudioCacheState.active += 1;
    var plugin = mrAudioCachePlugin();
    var startedAt = Date.now();
    Promise.resolve(plugin.registerCachedAudio({ key: job.key, url: job.url, headers: job.headers }))
      .then(function (result) {
        if (result && result.url) {
          console.log('[AudioCache] 已缓存 ' + job.key + '（' + (Date.now() - startedAt) + 'ms）');
        }
      })
      .catch(function (error) {
        console.warn('[AudioCache] 缓存失败 ' + job.key + '：' + (error && error.message || error));
        // 失败记账并撤销“已预取”标记：下轮预取还会再试一次（受 2 次上限保护）
        mrAudioCacheWarmFailures[job.key] = (mrAudioCacheWarmFailures[job.key] || 0) + 1;
        if (typeof mrAudioCachePrefetched !== 'undefined' && mrAudioCachePrefetched[job.key]) {
          delete mrAudioCachePrefetched[job.key];
        }
      })
      .then(function () {
        mrAudioCacheState.active -= 1;
        delete mrAudioCacheState.warming[job.key];
        mrAudioCachePump();
      });
  }
}

/**
 * 播放主流程用的入口：先查缓存，返回 { data, cached }。
 * data 为空表示没命中，调用方继续走原来的取址流程。
 */
async function mrAudioCacheResolve(provider, song) {
  var localUrl = await mrAudioCacheLookup(provider, song);
  if (!localUrl) return null;
  return {
    provider: provider,
    url: localUrl,
    level: (song && (song.playbackLevel || song.level)) || 'standard',
    cached: true,
    sourceMatch: true
  };
}

function setMrAudioCacheEnabled(enabled) {
  mrAudioCacheState.enabled = !!enabled;
  mrAudioCacheSaveSettings();
  if (typeof showToast === 'function') showToast(mrAudioCacheState.enabled ? '已开启全平台播放缓存' : '已关闭全平台播放缓存');
  return mrAudioCacheState.enabled;
}

// ── 预取：提前把队列下三首缓存到本地，切歌时零请求直接本地起播 ──────────
//
// 用户诉求：「提前下载缓存播放列表后的歌曲 3 首（含封面、歌词、音乐），下一首秒放」。
// 链路：播放成功后 3s → resolveAlbumGaplessPlaybackData 提前取下一首地址
//      → mrAudioCacheWarm(priority) 插到队首排进后台下载队列（并发 3）
//      → 同时 Image() 预载封面（WebView HTTP 缓存）、歌词由 00-lyrics-fetch-parse
//        的批量预取写入持久缓存
//      → 切到该歌时 playQueueAt 里的 mrAudioCacheResolve 命中本地 → 秒起播。
// 历史（2026-11-12）：预取“没效果”的根因是原生 registerCachedAudio 在 UI 主线程
// 同步下载整首歌 —— 任务全部串行卡死还拖垮 bridge；原生已改到 bridge 线程池，
// 这里的延迟也从 8s 提前到 3s、预取任务插队首。
var MR_AUDIO_PREFETCH_LOOKAHEAD = 3;
var mrAudioCachePrefetchToken = 0;
var mrAudioCachePrefetched = {}; // key → true，本次会话已预取过（避免反复取址）
var mrAudioCacheCoverPrefetched = {}; // 封面 URL → true，本次会话已预载过

/** 封面预载：走 WebView 的 HTTP 磁盘缓存，切歌时 <img> 同 URL 直接零延迟出图。 */
function mrAudioCachePrefetchCover(song) {
  var url = song && (song.customCover || song.cover || song.picUrl || song.albumCover || song.coverUrl) || '';
  url = String(url || '');
  if (!/^https?:/i.test(url)) return;
  if (mrAudioCacheCoverPrefetched[url]) return;
  mrAudioCacheCoverPrefetched[url] = true;
  try {
    var img = new Image();
    img.onload = function () { };
    img.onerror = function () { delete mrAudioCacheCoverPrefetched[url]; };
    img.src = url;
  } catch (_error) { /* 预载失败无所谓，切歌时正常加载 */ }
}

function mrAudioCacheQueueNextSongs(reason) {
  if (!mrAudioCacheState.enabled) return;
  if (typeof playQueue === 'undefined' || !Array.isArray(playQueue) || !playQueue.length) return;
  if (typeof currentIdx !== 'number' || currentIdx < 0) return;
  var token = ++mrAudioCachePrefetchToken;
  // 当前这首歌自己的取址和 warm 要先跑，别跟它们抢网络 —— 缓一会儿再动手；
  // 期间切了歌（token 变化）就作废，以新歌的位置重新算「下三首」。
  var delay = reason === 'play-started' ? 3000 : 1200;
  setTimeout(function () {
    if (token !== mrAudioCachePrefetchToken) return;
    if (typeof currentIdx !== 'number' || currentIdx < 0) return;
    if (typeof playQueue === 'undefined' || !Array.isArray(playQueue) || !playQueue.length) return;
    for (var step = 1; step <= MR_AUDIO_PREFETCH_LOOKAHEAD; step += 1) {
      var nextIdx = (currentIdx + step) % playQueue.length;
      var nextSong = playQueue[nextIdx];
      if (!nextSong) continue;
      // 封面预载与平台无关（本地歌也预载），音频预取只管五个在线平台
      mrAudioCachePrefetchCover(nextSong);
      if (nextSong.type === 'local' || nextSong.source === 'local' || nextSong.localUrl) continue;
      mrAudioCachePrefetchOne(nextSong, reason + ':' + step);
    }
  }, delay);
}

async function mrAudioCachePrefetchOne(song, reason) {
  try {
    if (!song || song.type === 'local' || song.source === 'local' || song.localUrl) return;
    var provider = typeof normalizePlaybackProvider === 'function' && typeof songProviderKey === 'function'
      ? normalizePlaybackProvider(songProviderKey(song))
      : (song.provider || '');
    // ytm / spotify 各有自己的链路（ytm 有 32MB 预下载，spotify 受保护），通用预取只管这五个平台
    if (!mrAudioCacheProviderSupported(provider)) return;
    var key = mrAudioCacheKey(provider, song);
    if (!key || mrAudioCacheState.warming[key] || mrAudioCachePrefetched[key]) return;
    // 播过一次就有缓存了，不用再取址（quiet：预取探测不污染命中统计）
    var cached = await mrAudioCacheLookup(provider, song, { quiet: true });
    if (cached) {
      mrAudioCachePrefetched[key] = true;
      return;
    }
    // 提前取播放地址（resolveAlbumGaplessPlaybackData 是平台无关的取址函数，所有平台通用）
    var data = typeof resolveAlbumGaplessPlaybackData === 'function'
      ? await resolveAlbumGaplessPlaybackData(song)
      : null;
    if (!data || !data.url) return;
    mrAudioCachePrefetched[key] = true;
    if (mrAudioCacheWarm(provider, song, data.url, { priority: true })) {
      console.log('[AudioCache] 已预取 ' + key + '（' + reason + '），切到时直接本地起播');
    }
  } catch (error) {
    console.warn('[AudioCache] 预取失败（不影响播放）：' + (error && error.message || error));
  }
}

function mrAudioCacheStats() {
  return {
    enabled: mrAudioCacheState.enabled,
    hits: mrAudioCacheState.hits,
    misses: mrAudioCacheState.misses,
    pending: mrAudioCacheState.queue.length + mrAudioCacheState.active
  };
}