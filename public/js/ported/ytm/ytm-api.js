// public/js/ported/ytm/ytm-api.js
// YouTube Music 音源（InnerTube 直连，参考 folia 的 ytm.ts/ytmHttp.ts）。
//   · 搜索：music.youtube.com/youtubei/v1/search（WEB_REMIX 客户端）
//   · 取流：/youtubei/v1/player 轮询多个客户端（visionOS/iOS/ANDROID_VR/TV/WEB），
//     取到 googlevideo 直链后用原生 registerDownloadedAudio 落成本地流（消掉 IP 绑定与 Range 问题）
//   · 账号：会话是登录模块存的 ytm Cookie，账号类请求带 SAPISIDHASH
// 对外暴露 window.__mrYtmRouter 给 /api/ytm/* 用。
(function (root) {
  'use strict';

  var YTM_ORIGIN = 'https://music.youtube.com';
  var INNERTUBE = YTM_ORIGIN + '/youtubei/v1';
  var STORE_KEY = 'mineradio-extra-login-sessions-v1';
  var CLIENT_NAME = '67';

  function todayVersion() {
    var now = new Date();
    var stamp = now.getUTCFullYear() + pad2(now.getUTCMonth() + 1) + pad2(now.getUTCDate());
    return '1.' + stamp + '.01.00';
  }
  function pad2(n) { return n < 10 ? '0' + n : String(n); }
  var CLIENT_VERSION = todayVersion();

  function log(level, message) {
    try {
      if (root.MRRuntimeLog && typeof root.MRRuntimeLog.push === 'function') root.MRRuntimeLog.push(level, [message], 'ytm');
      else console.log('[YTM] ' + message);
    } catch (e) { /* 忽略 */ }
  }

  function readStore() {
    try { return JSON.parse(localStorage.getItem(STORE_KEY) || '{}') || {}; } catch (e) { return {}; }
  }
  function session() {
    var s = readStore().ytm;
    if (!s || !s.cookie) return null;
    return s;
  }
  function saveSession(next) {
    try {
      var store = readStore();
      if (!store || typeof store !== 'object') store = {};
      if (next) store.ytm = next; else delete store.ytm;
      localStorage.setItem(STORE_KEY, JSON.stringify(store));
    } catch (e) { /* 忽略 */ }
  }
  function cookieValue(cookie, name) {
    var m = String(cookie || '').match(new RegExp('(?:^|;\\s*)' + name + '=([^;]+)'));
    return m ? m[1] : '';
  }

  function bridgeRequest(options) {
    var bridge = root.__mrBridgeRequest;
    if (typeof bridge === 'function') return bridge(options);
    return null;
  }

  async function sha1Hex(text) {
    var subtle = (root.crypto || {}).subtle;
    if (!subtle || typeof subtle.digest !== 'function') return '';
    var digest = await subtle.digest('SHA-1', new TextEncoder().encode(text));
    var bytes = new Uint8Array(digest);
    var out = '';
    for (var i = 0; i < bytes.length; i += 1) out += ('0' + bytes[i].toString(16)).slice(-2);
    return out;
  }

  async function authHeaders(withAuth) {
    var s = session();
    var headers = {};
    if (!s) return headers;
    headers['Cookie'] = s.cookie;
    if (!withAuth) return headers;
    var sapisid = cookieValue(s.cookie, 'SAPISID') || cookieValue(s.cookie, '__Secure-3PAPISID');
    if (!sapisid) return headers;
    var origin = YTM_ORIGIN;
    var timestamp = Math.floor(Date.now() / 1000);
    var hash = await sha1Hex(timestamp + ' ' + sapisid + ' ' + origin);
    if (!hash) return headers;
    headers['Authorization'] = 'SAPISIDHASH ' + timestamp + '_' + hash;
    headers['X-Origin'] = origin;
    return headers;
  }

  var visitorCache = '';

  async function ensureVisitorData() {
    if (visitorCache) return visitorCache;
    try {
      var bridge = root.__mrBridgeRequest;
      var headers = { 'User-Agent': navigator.userAgent, 'Accept': 'text/html' };
      var text = '';
      if (typeof bridge === 'function') {
        var answer = await bridge({ url: YTM_ORIGIN + '/', method: 'GET', headers: headers, timeoutMs: 15000 });
        text = String(answer && answer.body || '');
      } else {
        var response = await root.fetch(YTM_ORIGIN + '/', { headers: headers });
        text = await response.text();
      }
      var m = /"VISITOR_DATA":"([^"]+)"/.exec(text);
      if (m && m[1]) {
        visitorCache = m[1];
        log('info', 'visitorData 已取到');
      }
    } catch (error) {
      log('warn', 'visitorData 获取失败：' + (error && error.message || error));
    }
    return visitorCache;
  }

  function clientContext() {
    var ctx = {
      client: {
        hl: 'zh-CN',
        gl: 'US',
        clientName: 'WEB_REMIX',
        clientVersion: CLIENT_VERSION,
        osName: 'Android',
        osVersion: '14',
        userAgent: navigator.userAgent
      }
    };
    if (visitorCache) ctx.client.visitorData = visitorCache;
    return ctx;
  }

  async function innertube(path, body, options) {
    options = options || {};
    var url = INNERTUBE + path + '?prettyPrint=false&alt=json';
    if (!visitorCache && !options.skipVisitor) await ensureVisitorData();
    var headers = Object.assign({
      'Content-Type': 'application/json',
      'X-YouTube-Client-Name': CLIENT_NAME,
      'X-YouTube-Client-Version': CLIENT_VERSION,
      'Origin': YTM_ORIGIN,
      'Referer': YTM_ORIGIN + '/',
      'Accept': '*/*'
    }, (visitorCache ? { 'X-Goog-Visitor-Id': visitorCache } : {}), options.extraHeaders || {}, await authHeaders(!!options.auth));
    var payload = JSON.stringify(Object.assign({ context: options.context || clientContext() }, body || {}));
    var bridge = bridgeRequest({ url: url, method: 'POST', headers: headers, body: payload, timeoutMs: options.timeoutMs || 20000 });
    var text = '';
    var status = 0;
    if (bridge) {
      var answer = await bridge;
      status = answer && answer.status || 0;
      text = String(answer && answer.body || '');
    } else {
      var response = await root.fetch(url, { method: 'POST', headers: headers, body: payload });
      status = response.status;
      text = await response.text();
    }
    var json = null;
    try { json = JSON.parse(text); } catch (e) { json = null; }
    if (!json) throw new Error('YouTube 返回无法解析（HTTP ' + status + '）');
    return json;
  }

  // ---------- 数据映射 ----------

  function runsText(node) {
    try {
      return (node.runs || []).map(function (run) { return String(run.text || ''); }).join('');
    } catch (e) { return ''; }
  }

  function parseDurationMs(text) {
    var m = String(text || '').match(/^(\d+):(\d{1,2})(?::(\d{1,2}))?$/);
    if (!m) return 0;
    if (m[3] != null) return ((Number(m[1]) * 3600) + (Number(m[2]) * 60) + Number(m[3])) * 1000;
    return (Number(m[1]) * 60 + Number(m[2])) * 1000;
  }

  function toSong(row) {
    if (!row || typeof row !== 'object') return null;
    var videoId = row.videoId || '';
    var name = row.title || '';
    if (!videoId || !name) return null;
    var artists = (row.artists || []).filter(function (a) { return a && a.name; });
    var cover = row.cover || '';
    return {
      provider: 'ytm', source: 'ytm', type: 'ytm',
      id: videoId, mediaId: videoId,
      name: name, title: name,
      artist: artists.map(function (a) { return a.name; }).join(' / '),
      artists: artists,
      album: row.album && row.album.name || '',
      albumId: row.album && row.album.id || '',
      cover: cover, pic: cover,
      duration: Math.max(0, Number(row.durationMs) || 0),
      playable: true,
      fee: 0
    };
  }

  function findAll(rootNode, key) {
    var out = [];
    (function walk(value) {
      if (!value || typeof value !== 'object') return;
      if (Array.isArray(value)) { value.forEach(walk); return; }
      Object.keys(value).forEach(function (name) {
        if (name === key) out.push(value[name]);
        walk(value[name]);
      });
    })(rootNode);
    return out;
  }

  function lastThumb(list) {
    var arr = Array.isArray(list) ? list : [];
    var last = arr[arr.length - 1];
    return last && last.url ? String(last.url) : '';
  }

  function coverOf(item) {
    if (!item || typeof item !== 'object') return '';
    var thumb = item.thumbnail && item.thumbnail.musicThumbnailRenderer && item.thumbnail.musicThumbnailRenderer.thumbnail;
    var thumb2 = item.thumbnailRenderer && item.thumbnailRenderer.musicThumbnailRenderer && item.thumbnailRenderer.musicThumbnailRenderer.thumbnail;
    return lastThumb(thumb && thumb.thumbnails) || lastThumb(thumb2 && thumb2.thumbnails) || '';
  }

  function videoIdOf(item) {
    if (!item || typeof item !== 'object') return '';
    return String(
      (item.playlistItemData && item.playlistItemData.videoId)
      || (item.navigationEndpoint && item.navigationEndpoint.watchEndpoint && item.navigationEndpoint.watchEndpoint.videoId)
      || (item.overlay && item.overlay.playButtonRenderer && item.overlay.playButtonRenderer.playNavigationEndpoint && item.overlay.playButtonRenderer.playNavigationEndpoint.watchEndpoint && item.overlay.playButtonRenderer.playNavigationEndpoint.watchEndpoint.videoId)
      || (item.overlay && item.overlay.musicItemThumbnailOverlayRenderer && item.overlay.musicItemThumbnailOverlayRenderer.content && item.overlay.musicItemThumbnailOverlayRenderer.content.musicPlayButtonRenderer && item.overlay.musicItemThumbnailOverlayRenderer.content.musicPlayButtonRenderer.playNavigationEndpoint && item.overlay.musicItemThumbnailOverlayRenderer.content.musicPlayButtonRenderer.playNavigationEndpoint.watchEndpoint && item.overlay.musicItemThumbnailOverlayRenderer.content.musicPlayButtonRenderer.playNavigationEndpoint.watchEndpoint.videoId)
      || ''
    );
  }

  function splitParts(text) {
    return String(text || '').split('•').map(function (part) { return part.trim(); }).filter(Boolean);
  }

  // 列表行：歌名 + 歌手/专辑/时长
  function songOfRow(row) {
    var id = videoIdOf(row);
    if (!id) return null;
    var title = '';
    try { title = runsText(row.flexColumns[0].musicResponsiveListItemFlexColumnRenderer.text); } catch (e) { title = ''; }
    if (!title) return null;
    var subtitle = '';
    try { subtitle = runsText(row.flexColumns[1].musicResponsiveListItemFlexColumnRenderer.text); } catch (e) { subtitle = ''; }
    var parts = splitParts(subtitle);
    var last = parts[parts.length - 1] || '';
    var hasDuration = /^\d+(?::\d{1,2})+$/.test(last);
    var meta = hasDuration ? parts.slice(0, -1) : parts;
    var artistText = meta[0] || '';
    var albumText = meta[1] || '';
    return toSong({
      videoId: id,
      title: title,
      artists: artistText ? artistText.split(/[、,&/]/).map(function (name) { return { id: '', name: name.trim() }; }).filter(function (a) { return a.name; }) : [],
      album: { id: '', name: albumText },
      cover: coverOf(row),
      durationMs: hasDuration ? parseDurationMs(last) : 0
    });
  }

  // 卡片：标题 + 副标题（可能是歌，也可能是歌单/专辑）
  function cardOf(card) {
    if (!card || typeof card !== 'object') return null;
    var title = '';
    try {
      title = runsText(card.title && card.title.runs ? card.title : { runs: [{ text: (card.title && card.title.simpleText) || '' }] });
    } catch (e) { title = ''; }
    if (!title) return null;
    var subtitle = '';
    try {
      subtitle = runsText(card.subtitle && card.subtitle.runs ? card.subtitle : { runs: [{ text: (card.subtitle && card.subtitle.simpleText) || '' }] });
    } catch (e) { subtitle = ''; }
    var browseId = '';
    try {
      browseId = String(card.navigationEndpoint && card.navigationEndpoint.browseEndpoint && card.navigationEndpoint.browseEndpoint.browseId || '');
    } catch (e) { browseId = ''; }
    var watchId = videoIdOf(card);
    if (watchId) {
      var parts = splitParts(subtitle);
      var last = parts[parts.length - 1] || '';
      var hasDuration = /^\d+(?::\d{1,2})+$/.test(last);
      var meta = hasDuration ? parts.slice(0, -1) : parts;
      return {
        kind: 'song',
        song: toSong({
          videoId: watchId,
          title: title,
          artists: meta[0] ? meta[0].split(/[、,&/]/).map(function (name) { return { id: '', name: name.trim() }; }).filter(function (a) { return a.name; }) : [],
          album: { id: '', name: meta[1] || '' },
          cover: coverOf(card),
          durationMs: hasDuration ? parseDurationMs(last) : 0
        })
      };
    }
    if (browseId && /^MPRE|^MPLA|^MPSP|^MPAT|^FEmusic|^VL/.test(browseId)) {
      // 副标题里常带「N 首 / N songs」，能读就读，读不到等点进去加载后回写
      var countMatch = /(\d+)\s*(?:首|songs?|tracks?)/i.exec(subtitle) || /(\d+)\s*(?:首|songs?)/i.exec(String(card.itemCount || ''));
      return {
        kind: 'collection',
        collection: {
          provider: 'ytm', source: 'ytm', id: browseId, name: title,
          cover: coverOf(card),
          trackCount: countMatch ? Number(countMatch[1]) || 0 : 0,
          sourceType: browseId.indexOf('MPRE') === 0 ? 6 : 4
        }
      };
    }
    return null;
  }

  // 页面里所有能变成歌曲的条目（行列都认）
  function collectSongs(json) {
    var songs = [];
    var seen = Object.create(null);
    findAll(json, 'musicResponsiveListItemRenderer').forEach(function (row) {
      var song = songOfRow(row);
      if (song && !seen[song.id]) { seen[song.id] = true; songs.push(song); }
    });
    findAll(json, 'musicTwoRowItemRenderer').forEach(function (card) {
      var entry = cardOf(card);
      if (entry && entry.kind === 'song' && entry.song && !seen[entry.song.id]) {
        seen[entry.song.id] = true;
        songs.push(entry.song);
      }
    });
    return songs;
  }

  function collectCollections(json) {
    var items = [];
    var seen = Object.create(null);
    findAll(json, 'musicTwoRowItemRenderer').forEach(function (card) {
      var entry = cardOf(card);
      if (entry && entry.kind === 'collection' && !seen[entry.collection.id]) {
        seen[entry.collection.id] = true;
        items.push(entry.collection);
      }
    });
    return items;
  }

  // ---------- 搜索 ----------

  async function search(params) {
    var query = String(params.keywords || params.query || '').trim();
    var limit = Math.max(1, Math.min(Number(params.limit) || 20, 50));
    if (!query) return { provider: 'ytm', songs: [], hasMore: false };
    var json = await innertube('/search', { query: query, params: 'EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D' }, { timeoutMs: 20000 });
    var unique = collectSongs(json);
    log('info', 'search "' + query + '" → ' + unique.length + ' 首');
    return { provider: 'ytm', keyword: query, songs: unique.slice(0, limit), hasMore: false, nextOffset: unique.length };
  }

  // ---------- 取流 ----------

  var PLAYER_CLIENTS = {
    androidTestsuite: function () {
      var ua = 'com.google.android.youtube/1.9 (Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002) gzip';
      return { name: '30', version: '1.9', ua: ua, context: { hl: 'zh-CN', gl: 'US', clientName: 'ANDROID_TESTSUITE', clientVersion: '1.9', osName: 'Android', osVersion: '15', deviceMake: 'Google', deviceModel: 'Pixel 9 Pro', androidSdkVersion: 35, userAgent: ua } };
    },
    visionos: function () {
      var ua = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15';
      return { name: '101', version: '0.1', ua: ua, context: { hl: 'zh-CN', gl: 'US', clientName: 'VISIONOS', clientVersion: '0.1', osName: 'visionOS', osVersion: '1.3.21O771', deviceMake: 'Apple', deviceModel: 'RealityDevice14,1', userAgent: ua } };
    },
    ios: function () {
      var ua = 'com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)';
      return { name: '5', version: '20.10.4', ua: ua, context: { hl: 'zh-CN', gl: 'US', clientName: 'IOS', clientVersion: '20.10.4', deviceMake: 'Apple', deviceModel: 'iPhone16,2', osName: 'iPhone', osVersion: '18.3.2.22D82', userAgent: ua } };
    },
    androidVr: function () {
      var ua = 'com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; en_US; Quest 3 Build/SQ3A.220605.009.A1) gzip';
      return { name: '28', version: '1.60.19', ua: ua, context: { hl: 'zh-CN', gl: 'US', clientName: 'ANDROID_VR', clientVersion: '1.60.19', deviceMake: 'Oculus', deviceModel: 'Quest 3', osName: 'Android', osVersion: '12', androidSdkVersion: 32, userAgent: ua } };
    },
    tv: function () {
      var ua = 'Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.1.0-qa (unlike Gecko) Starboard/13';
      return { name: '7', version: '7.20250219.16.00', ua: ua, context: { hl: 'zh-CN', gl: 'US', clientName: 'TVHTML5', clientVersion: '7.20250219.16.00', userAgent: ua } };
    },
    web: function () {
      return { name: CLIENT_NAME, version: CLIENT_VERSION, ua: navigator.userAgent, context: clientContext().client };
    }
  };

  async function playerOnce(videoId, profile, poToken) {
    var sts = await ensureSignatureTimestamp();
    var client = PLAYER_CLIENTS[profile]();
    if (!visitorCache) await ensureVisitorData();
    if (visitorCache && client.context) client.context.visitorData = visitorCache;
    var body = {
      videoId: videoId,
      racyCheckOk: true,
      contentCheckOk: true,
      playbackContext: { contentPlaybackContext: { vis: 0, splay: false, lactMilliseconds: '-1', signatureTimestamp: sts } },
      context: { client: client.context }
    };
    // 有 poToken 就带上：googlevideo 只给带合法 pot 的地址发缓存外的字节
    if (poToken) body.serviceIntegrityDimensions = { poToken: poToken };
    var json = await innertube('/player', body, {
      context: body.context,
      extraHeaders: {
        'X-YouTube-Client-Name': client.name,
        'X-YouTube-Client-Version': client.version,
        'User-Agent': client.ua,
        'Origin': YTM_ORIGIN,
        'Referer': YTM_ORIGIN + '/'
      },
      timeoutMs: 20000
    });
    var status = json.playability_status || json.playabilityStatus || {};
    var streaming = json.streamingData || json.streaming_data || {};
    var formats = (streaming.adaptiveFormats || streaming.adaptive_formats || []).concat(streaming.formats || []);
    var audioFormats = formats.filter(function (format) {
      return String(format.mimeType || format.mime_type || '').indexOf('audio') === 0;
    }).sort(function (a, b) {
      var directA = typeof a.url === 'string' && a.url ? 1 : 0;
      var directB = typeof b.url === 'string' && b.url ? 1 : 0;
      if (directB !== directA) return directB - directA;
      // 先选 mp4（m4a），WebView 的音频栈对 mp4 兼容性最好；webm/opus 放第二位
      var mp4A = /audio\/mp4/i.test(String(a.mimeType || a.mime_type || '')) ? 1 : 0;
      var mp4B = /audio\/mp4/i.test(String(b.mimeType || b.mime_type || '')) ? 1 : 0;
      return mp4B - mp4A || Number(b.bitrate || 0) - Number(a.bitrate || 0);
    }).filter(function (format) { return typeof format.url === 'string' && format.url; });
    if (!audioFormats.length) {
      throw new Error('客户端 ' + profile + ' 没有直链（' + (status.status || 'unknown') + '）');
    }
    // 每个客户端给多个候选格式：某个 itag 被 403 时还能换下一个
    return audioFormats.slice(0, 3).map(function (audio) {
      return {
        url: String(audio.url), itag: audio.itag, bitrate: Number(audio.bitrate) || 0,
        mimeType: String(audio.mimeType || audio.mime_type || 'audio/mp4'), profile: profile,
        userAgent: client.ua || navigator.userAgent
      };
    });
  }

  // ---------- 本地缓存索引（离线可播的钥匙） ----------
  // 原生只按 key 把音频存成文件；JS 这边再记一份「哪些歌已经缓存好了」。
  // 有了它，点歌时可以直接命中本地文件、**一次网络请求都不发** ——
  // 不开 VPN、甚至飞行模式下，听过的歌照样能放。
  var YTM_CACHE_INDEX_KEY = 'mineradio-ytm-audio-cache-v1';

  function cacheIndexLoad() {
    try {
      var raw = root.localStorage && root.localStorage.getItem(YTM_CACHE_INDEX_KEY);
      var data = raw ? JSON.parse(raw) : null;
      return data && typeof data === 'object' ? data : Object.create(null);
    } catch (error) {
      return Object.create(null);
    }
  }

  var ytmCacheIndex = cacheIndexLoad();

  function cacheIndexSave() {
    try {
      if (root.localStorage) root.localStorage.setItem(YTM_CACHE_INDEX_KEY, JSON.stringify(ytmCacheIndex));
    } catch (error) { /* 存储满了就少一次秒开，不影响播放 */ }
  }

  function cacheIndexMark(videoId) {
    if (!videoId) return;
    ytmCacheIndex[videoId] = Date.now();
    cacheIndexSave();
  }

  function cacheIndexDrop(videoId) {
    if (videoId && ytmCacheIndex[videoId]) {
      delete ytmCacheIndex[videoId];
      cacheIndexSave();
    }
  }

  var ytmNativePlugin = undefined;
  function nativePlugin() {
    if (ytmNativePlugin !== undefined) return ytmNativePlugin;
    var cap = root.Capacitor;
    ytmNativePlugin = (cap && cap.Plugins && cap.Plugins.FoliaNative) || null;
    return ytmNativePlugin;
  }

  /** 只查本地缓存、不联网：命中返回本地流地址，没命中返回空串。 */
  async function cachedAudioUrl(videoId) {
    var plugin = nativePlugin();
    if (!plugin || typeof plugin.getCachedAudio !== 'function') return '';
    try {
      var hit = await plugin.getCachedAudio({ key: 'ytm:' + videoId });
      return hit && hit.url ? String(hit.url) : '';
    } catch (error) {
      return '';
    }
  }

  async function audioStream(videoId) {
    var lastError = null;
    var lastDirect = null;
    var blocked = 0;

    // 0) 先查本地缓存：听过就直接秒开，不发任何网络请求
    try {
      var startedAt = Date.now();
      var hitUrl = await cachedAudioUrl(videoId);
      if (hitUrl) {
        cacheIndexMark(videoId);
        log('info', '缓存命中（' + (Date.now() - startedAt) + 'ms，跳过取址）');
        return { url: hitUrl, profile: 'cache', itag: 0, mimeType: 'audio/mp4' };
      }
      cacheIndexDrop(videoId);
    } catch (cacheError) {
      log('warn', '缓存查询失败：' + (cacheError && cacheError.message || cacheError));
    }

    // 1) 先按 Folia 的顺序轮换客户端（visionos 通常直接给可整首下载的直链，
    //    前提是 signatureTimestamp 要取 player 里的真值，写死会被判客户端过旧）
    var profiles = ['visionos', 'ios', 'androidTestsuite', 'androidVr', 'tv', 'web'];
    for (var i = 0; i < profiles.length; i += 1) {
      var candidates = null;
      try {
        candidates = await playerOnce(videoId, profiles[i]);
      } catch (error) {
        lastError = error;
        log('warn', '客户端 ' + profiles[i] + ' 取址失败：' + (error && error.message || error));
        continue;
      }
      for (var c = 0; c < candidates.length; c += 1) {
        var direct = candidates[c];
        lastDirect = direct;
        try {
          var local = await downloadToLocal(direct, 'ytm:' + videoId);
          if (local) {
            log('info', '播放就绪（' + profiles[i] + ' itag ' + direct.itag + '）');
            return { url: local, profile: profiles[i], itag: direct.itag, mimeType: direct.mimeType };
          }
        } catch (error) {
          lastError = error;
          if (/403/.test(String(error && error.message))) blocked += 1;
          log('warn', profiles[i] + ' itag ' + direct.itag + ' 下载失败：' + (error && error.message || error));
        }
      }
    }

    // 2) 客户端都不行时才铸 poToken 走 WEB_REMIX
    var pot = '';
    try {
      if (root.__mrYtmPot && typeof root.__mrYtmPot.mint === 'function') pot = await root.__mrYtmPot.mint(videoId);
    } catch (error) {
      log('warn', 'poToken 获取异常：' + (error && error.message || error));
    }
    if (pot) {
      try {
        var potCandidates = await playerOnce(videoId, 'web', pot);
        for (var pc = 0; pc < potCandidates.length; pc += 1) {
          try {
            var potLocal = await downloadToLocal(potCandidates[pc], 'ytm:' + videoId);
            if (potLocal) {
              log('info', '播放就绪（pot 路线 itag ' + potCandidates[pc].itag + '）');
              return { url: potLocal, profile: 'web-pot', itag: potCandidates[pc].itag, mimeType: potCandidates[pc].mimeType };
            }
          } catch (potError) {
            log('warn', 'pot 路线 itag ' + potCandidates[pc].itag + ' 下载失败：' + (potError && potError.message || potError));
          }
        }
      } catch (error) {
        log('warn', 'pot 路线取址失败：' + (error && error.message || error));
      }
    }

    // 3) 最后才用 youtubei 解签（它自带 n 处理，但请求重、容易超时）
    try {
      var decoded = await youtubeiAudio(videoId);
      var decodedLocal = await downloadToLocal(decoded, 'ytm:' + videoId);
      if (decodedLocal) return { url: decodedLocal, profile: 'youtubei', itag: decoded.itag, mimeType: decoded.mimeType };
    } catch (error) {
      log('warn', 'youtubei 解签取流失败：' + (error && error.message || error));
    }

    if (lastDirect && blocked === 0) {
      log('warn', '所有客户端本地化失败，回退直连地址');
      return { url: lastDirect.url, profile: lastDirect.profile, itag: lastDirect.itag, mimeType: lastDirect.mimeType };
    }
    var blockedError = new Error('YouTube 拒绝提供音频字节（googlevideo 403）。请换一个代理节点后重试。');
    blockedError.code = 'blocked';
    throw (blocked >= 2 ? blockedError : (lastError || blockedError));
  }

  async function downloadToLocal(direct, cacheKey) {
    var cap = root.Capacitor;
    var plugin = cap && cap.Plugins && cap.Plugins.FoliaNative;
    if (!plugin) return direct.url;
    // 优先按稳定 key（ytm:videoId）缓存：同一首歌第二次直接秒开，重启也有效
    if (cacheKey && typeof plugin.registerCachedAudio === 'function') {
      var cacheStarted = Date.now();
      try {
        var cached = await plugin.registerCachedAudio({
          key: cacheKey,
          url: direct.url,
          headers: {
            'User-Agent': direct.userAgent || navigator.userAgent,
            'Accept': '*/*',
            'Range': 'bytes=0-33554431',
            'Referer': 'https://music.youtube.com/',
            'Origin': 'https://music.youtube.com'
          }
        });
        if (cached && cached.url) {
          var cost = Date.now() - cacheStarted;
          // 记进索引：下次点这首歌（哪怕断网/关 VPN）直接走本地文件。
          if (String(cacheKey).indexOf('ytm:') === 0) cacheIndexMark(String(cacheKey).slice(4));
          log(cost < 800 ? 'info' : 'debug', (cost < 800 ? '缓存命中（' + cost + 'ms）' : '已下载并缓存（' + cost + 'ms）') + ' key=' + cacheKey);
          return String(cached.url);
        }
      } catch (error) {
        log('warn', '缓存下载失败：' + (error && error.message || error));
      }
    }
    if (typeof plugin.registerDownloadedAudio === 'function') {
      try {
        var answer = await plugin.registerDownloadedAudio({
          url: direct.url,
          headers: {
            'User-Agent': direct.userAgent || navigator.userAgent,
            'Accept': '*/*',
            // googlevideo 对「开放式 Range（bytes=0-）」和「不带 Range」都直接回 403，
            // 只有带上界的 Range 才给 206。给一个足够大的上界，整首一次拿完。
            'Range': 'bytes=0-33554431',
            'Referer': 'https://music.youtube.com/',
            'Origin': 'https://music.youtube.com'
          }
        });
        if (answer && answer.url) {
          if (cacheKey && String(cacheKey).indexOf('ytm:') === 0) cacheIndexMark(String(cacheKey).slice(4));
          log('info', '音频已下载到本地流：' + answer.url);
          return String(answer.url);
        }
      } catch (error) {
        log('warn', '本地下载失败：' + (error && error.message || error));
      }
    }
    if (typeof plugin.registerRemoteAudio === 'function') {
      try {
        var proxied = await plugin.registerRemoteAudio({ url: direct.url });
        if (proxied && proxied.url) return String(proxied.url);
      } catch (error) { /* 忽略 */ }
    }
    return '';
  }

  // ---------- youtubei.js 解签取流 ----------
  // 裸 InnerTube 拿到的直链里 `n` 参数没解，googlevideo 只发缓存里约 1MB，之后全 403。
  // youtubei.js 的 player.decipher 会把 sig/n 都解开，这里就用它（和 Folia 同一条路子）。

  var ytFetchShim = function (input, init) {
    init = init || {};
    var url = typeof input === 'string' ? input : (input && input.url) || String(input);
    // youtubei.js 会先 new Request(...) 再 fetch(request, {body, headers})，method 在 Request 对象里，
    // 只读 init.method 会把 POST 当 GET 发出去（player 一律 405）。
    var method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
    var headers = {};
    try {
      new Headers(init.headers || {}).forEach(function (value, key) { headers[key] = value; });
    } catch (e) { headers = Object.assign({}, init.headers || {}); }
    var bridge = root.__mrBridgeRequest;
    if (typeof bridge !== 'function') return root.fetch(input, init);
    var body = init.body == null ? undefined : String(init.body);
    return bridge({ url: url, method: method, headers: headers, body: body, timeoutMs: 60000, binary: false })
      .then(function (answer) {
        var status = Number(answer && answer.status) || 0;
        var text = String(answer && answer.body || '');
        return new Response(text, { status: status, headers: { 'content-type': (answer && answer.headers && (answer.headers['content-type'] || answer.headers['Content-Type'])) || 'application/json' } });
      });
  };

  var youtubeiPromise = null;
  async function loadYoutubei() {
    if (youtubeiPromise) return youtubeiPromise;
    youtubeiPromise = (async function () {
      var mod = await import('/js/ported/ytm/vendor/youtubei.browser.js');
      var Platform = mod.Platform;
      if (Platform && Platform.shim) {
        Platform.shim.eval = async function (data) {
          var source = String((data && data.output) || '');
          if (!source) throw new Error('player script is empty');
          var run = new Function(source);
          return run();
        };
      }
      var yt = await mod.Innertube.create({ generate_session_locally: true, fetch: ytFetchShim });
      log('info', 'youtubei.js 已就绪');
      return { mod: mod, yt: yt };
    })();
    try {
      return await youtubeiPromise;
    } catch (error) {
      youtubeiPromise = null;
      throw error;
    }
  }

  // pot 必须由「引用它的那个会话」拼进地址：youtubei 的 Player 在解签后会自动 append pot。
  async function youtubeiAudioWithPot(videoId, pot) {
    var loaded = await loadYoutubei();
    var yt = loaded.yt;
    if (pot) {
      try {
        yt = await loaded.mod.Innertube.create({ generate_session_locally: true, fetch: ytFetchShim, po_token: pot });
      } catch (error) {
        log('warn', '带 pot 的会话创建失败：' + (error && error.message || error));
        yt = loaded.yt;
      }
    }
    var info = await yt.getInfo(videoId);
    var format = null;
    try { format = info.chooseFormat({ type: 'audio', quality: 'best' }); } catch (error) { format = null; }
    if (!format) throw new Error('youtubei 没有可用的音频格式');
    var stream = null;
    try { stream = await format.decipher(yt.session.player); } catch (error) { stream = format.url; }
    if (stream && typeof stream !== 'string') stream = String(stream);
    var url = typeof stream === 'string' ? stream : '';
    if (!url) throw new Error('youtubei 解出的地址为空');
    log('info', 'youtubei 解签成功（pot ' + (pot ? '带' : '无') + '，' + (format.mime_type || 'audio') + '，pot参数=' + (/[?&]pot=/.test(url) ? '有' : '无') + '）');
    return {
      url: url, itag: format.itag, bitrate: Number(format.bitrate) || 0,
      mimeType: String(format.mime_type || format.mimeType || 'audio/mp4'),
      profile: pot ? 'youtubei-pot' : 'youtubei', userAgent: navigator.userAgent
    };
  }

  async function youtubeiAudio(videoId) {
    var loaded = await loadYoutubei();
    var yt = loaded.yt;
    var info = null;
    try {
      info = await yt.getInfo(videoId, { client: 'IOS' });
    } catch (error) {
      log('warn', 'youtubei getInfo(IOS) 失败：' + (error && error.message || error));
      info = await yt.getInfo(videoId);
    }
    var format = null;
    try {
      format = info.chooseFormat({ type: 'audio', quality: 'best' });
    } catch (error) {
      format = null;
    }
    if (!format) throw new Error('youtubei 没有可用的音频格式');
    var stream = null;
    try {
      // 新版 youtubei 的 decipher 返回 Promise，必须 await，否则拿到的是 "[object Promise]"
      stream = await format.decipher(yt.session.player);
    } catch (error) {
      log('warn', 'decipher 失败，退回原始 url：' + (error && error.message || error));
      stream = format.url;
    }
    if (stream && typeof stream !== 'string') stream = String(stream);
    var url = typeof stream === 'string' ? stream : '';
    if (!url) throw new Error('youtubei 解出的地址为空');
    log('info', 'youtubei 解签成功（' + (format.mime_type || format.mimeType || 'audio') + '）');
    return {
      url: url,
      itag: format.itag,
      bitrate: Number(format.bitrate) || 0,
      mimeType: String(format.mime_type || format.mimeType || 'audio/mp4'),
      profile: 'youtubei',
      userAgent: navigator.userAgent
    };
  }

  var signatureTimestamp = 0;
  async function ensureSignatureTimestamp() {
    if (signatureTimestamp) return signatureTimestamp;
    try {
      var loaded = await loadYoutubei();
      var player = loaded.yt && loaded.yt.session && loaded.yt.session.player;
      var sts = Number(player && player.signature_timestamp);
      if (sts > 0) {
        signatureTimestamp = sts;
        log('info', 'signatureTimestamp = ' + sts);
        return sts;
      }
    } catch (error) {
      log('warn', 'sts 读取失败：' + (error && error.message || error));
    }
    signatureTimestamp = 20745;
    return signatureTimestamp;
  }

  var streamCache = Object.create(null);

  async function songUrl(id, quality) {
    var videoId = String(id || '').trim();
    if (!/^[A-Za-z0-9_-]{6,20}$/.test(videoId)) {
      return { provider: 'ytm', url: '', playable: false, reason: 'invalid_id', message: '缺少 YouTube 视频 id' };
    }
    var cached = streamCache[videoId];
    if (cached && Date.now() - cached.at < 8 * 60 * 1000) {
      return { provider: 'ytm', url: cached.url, playable: true, level: quality || 'high', quality: quality || 'high', loggedIn: !!session() };
    }
    // 【离线可播】先问原生要本地缓存文件：命中就立刻返回，全程零网络请求。
    // 这样「缓存过的歌」不开 VPN、断网都能听，而不用先去取一次播放地址再失败。
    var localCachedUrl = await cachedAudioUrl(videoId);
    if (localCachedUrl) {
      cacheIndexMark(videoId);
      streamCache[videoId] = { at: Date.now(), url: localCachedUrl };
      log('info', '离线缓存直出（未联网）id=' + videoId);
      return {
        provider: 'ytm', url: localCachedUrl, playable: true,
        level: quality || 'high', quality: quality || 'high', cached: true, loggedIn: !!session()
      };
    }
    cacheIndexDrop(videoId);
    // 没缓存又没有网：直接告诉播放器「这首歌没缓存」，别再去等 40 秒超时。
    if (root.navigator && root.navigator.onLine === false) {
      return {
        provider: 'ytm', url: '', playable: false, reason: 'offline',
        message: '当前离线，这首歌还没有缓存到本地'
      };
    }
    try {
      var stream = await audioStream(videoId);
      streamCache[videoId] = { at: Date.now(), url: stream.url };
      return {
        provider: 'ytm', url: stream.url, playable: true,
        level: quality || 'high', quality: quality || 'high',
        itag: stream.itag, loggedIn: !!session()
      };
    } catch (error) {
      var reason = error && error.code === 'blocked' ? 'blocked' : (/403/.test(String(error && error.message)) ? 'blocked' : 'unavailable');
      return {
        provider: 'ytm', url: '', playable: false, reason: reason,
        message: error && error.message ? String(error.message) : 'YouTube 取流失败'
      };
    }
  }

  // ---------- 歌词 / 账号 ----------

  async function lyric(id) {
    // YouTube 的歌词要过 timedtext + 签名，先返回空，避免歌词面板卡住
    return { provider: 'ytm', lyric: '', tlyric: '', format: 'lrc' };
  }

  function ytmText(value) {
    if (value == null) return '';
    if (typeof value === 'string') return value.trim();
    if (typeof value === 'object') {
      if (typeof value.simpleText === 'string') return value.simpleText.trim();
      if (Array.isArray(value.runs)) return value.runs.map(function (r) { return String((r && r.text) || ''); }).join('').trim();
      if (typeof value.text === 'string') return value.text.trim();
    }
    return String(value);
  }

  function statusPayload() {
    var s = session();
    try { log('info', 'status：' + (s ? ('已登录 nickname=' + (s.nickname || '(空)') + ' avatar=' + (s.avatar ? '有' : '无') + ' cookie=' + String(s.cookie || '').length + '字符') : '未登录')); } catch (e) { /* 忽略 */ }
    if (!s) {
      return {
        provider: 'ytm', configured: false, loggedIn: false, nickname: 'YouTube Music', avatar: '',
        userId: '', vipType: 0, vipLevel: 'none', isVip: false, isSvip: false,
        playbackKeyReady: false, playbackMode: 'direct',
        capabilities: { search: true, playableUrl: true }, cookieReady: false, tokenConfigured: false
      };
    }
    return {
      provider: 'ytm', configured: true, loggedIn: true,
      nickname: ytmText(s.nickname) || 'YouTube Music', avatar: ytmText(s.avatar), userId: s.channelId || '',
      vipType: 0, vipLevel: 'none', isVip: false, isSvip: false,
      playbackKeyReady: true, playbackMode: 'direct',
      capabilities: { search: true, playableUrl: true }, cookieReady: true, tokenConfigured: true
    };
  }

  // 照 Folia readYtmAccount：accounts_list 里取昵称/头像/频道名
  async function readAccount() {
    var s = session();
    if (!s) return null;
    var sapisid = cookieValue(s.cookie, 'SAPISID') || cookieValue(s.cookie, '__Secure-3PAPISID');
    var headers = {
      'Content-Type': 'application/json',
      'X-YouTube-Client-Name': CLIENT_NAME,
      'X-YouTube-Client-Version': CLIENT_VERSION,
      'X-Origin': YTM_ORIGIN,
      'Origin': YTM_ORIGIN,
      'Referer': YTM_ORIGIN + '/',
      'Cookie': s.cookie
    };
    if (sapisid) {
      var timestamp = Math.floor(Date.now() / 1000);
      var hash = await sha1Hex(timestamp + ' ' + sapisid + ' ' + YTM_ORIGIN);
      if (hash) {
        headers['Authorization'] = 'SAPISIDHASH ' + timestamp + '_' + hash;
        headers['X-Goog-AuthUser'] = '0';
      }
    }
    var payload = JSON.stringify({ context: clientContext() });
    var answer = typeof root.__mrBridgeRequest === 'function'
      ? await root.__mrBridgeRequest({ url: INNERTUBE + '/account/accounts_list?prettyPrint=false&alt=json', method: 'POST', headers: headers, body: payload, timeoutMs: 15000, omitCookies: true })
      : { status: 0, body: '' };
    var json = null;
    try { json = JSON.parse(answer.body || ''); } catch (e) { json = null; }
    if (!json) {
      log('warn', 'accounts_list 无法解析（HTTP ' + (answer && answer.status) + '）：' + String(answer && answer.body || '').slice(0, 200));
      return null;
    }
    var accounts = [];
    (function walk(node) {
      if (!node || typeof node !== 'object') return;
      if (Array.isArray(node)) { node.forEach(walk); return; }
      if (node.accountName && (node.accountPhoto || node.channelHandle)) accounts.push(node);
      Object.keys(node).forEach(function (key) { walk(node[key]); });
    })(json);
    if (!accounts.length) {
      log('warn', 'accounts_list 里没有账号节点');
      return null;
    }
    var account = accounts.filter(function (item) { return item.isSelected === true; })[0] || accounts[0];
    var name = ytmText(account.accountName);
    var thumbs = (account.accountPhoto && account.accountPhoto.thumbnails) || [];
    var avatar = thumbs.length ? String(thumbs[thumbs.length - 1].url || '') : '';
    var handle = ytmText(account.channelHandle);
    log('info', 'accounts_list → name=' + (name || '(空)') + ' handle=' + (handle || '(空)') + ' 账号数=' + accounts.length);
    if (!name && !handle && !avatar) return null;
    var next = Object.assign({}, s, {
      nickname: name || handle || s.nickname || 'YouTube Music',
      avatar: avatar || s.avatar || '',
      channelHandle: handle || s.channelHandle || ''
    });
    saveSession(next);
    return { nickname: next.nickname, avatar: next.avatar, handle: handle };
  }

  async function userPlaylists() {
    if (!session()) return { provider: 'ytm', playlists: [], loggedIn: false, message: '需要先登录 YouTube Music' };
    // 账号库：FEmusic_liked_playlists
    var json = await innertube('/browse', { browseId: 'FEmusic_liked_playlists' }, { auth: true });
    var playlists = collectCollections(json);
    if (playlists.length) {
      log('info', 'user/playlists → ' + playlists.length + ' 个：' + playlists.slice(0, 5).map(function (pl) { return pl.name; }).join('、'));
      return { provider: 'ytm', loggedIn: true, playlists: playlists, total: playlists.length };
    }
    (function walk(node) {
      if (!node || typeof node !== 'object') return;
      if (Array.isArray(node)) { node.forEach(walk); return; }
      var grid = node.gridRenderer || node.musicShelfRenderer;
      if (grid) { /* 继续 */ }
      var twoRow = node.musicTwoRowItemRenderer;
      if (twoRow) {
        try {
          var title = runsText(twoRow.title.runs || twoRow.title.simpleText ? (twoRow.title.runs || [{ text: twoRow.title.simpleText }]) : []);
          var browseId = twoRow.navigationEndpoint && twoRow.navigationEndpoint.browseEndpoint && twoRow.navigationEndpoint.browseEndpoint.browseId;
          if (browseId && title) {
            var thumbs = twoRow.thumbnailRenderer && twoRow.thumbnailRenderer.musicThumbnailRenderer
              && twoRow.thumbnailRenderer.musicThumbnailRenderer.thumbnail && twoRow.thumbnailRenderer.musicThumbnailRenderer.thumbnail.thumbnails || [];
            playlists.push({
              provider: 'ytm', source: 'ytm', id: browseId, name: title,
              cover: thumbs.length ? String(thumbs[thumbs.length - 1].url || '') : '',
              trackCount: 0, sourceType: 4
            });
          }
        } catch (e) { /* 忽略 */ }
      }
      Object.keys(node).forEach(function (key) { walk(node[key]); });
    })(json);
    return { provider: 'ytm', loggedIn: true, playlists: playlists, total: playlists.length };
  }

  async function playlistTracks(params) {
    var id = String(params.id || '').trim();
    if (!id) return { provider: 'ytm', songs: [], tracks: [], error: 'MISSING_PLAYLIST_ID' };
    var json = await innertube('/browse', { browseId: id }, { auth: true });
    var songs = collectSongs(json);
    log('info', '歌单 ' + id + ' → ' + songs.length + ' 首');
    return { provider: 'ytm', id: id, songs: songs, tracks: songs, hasMore: false, nextOffset: songs.length };
  }

  // YouTube 的「电台」= 以某首歌/某个 mix 为种子调 /next，返回 playlistPanelRenderer 队列
  function songsFromPlaylistPanel(json) {
    var songs = [];
    var seen = Object.create(null);
    findAll(json, 'playlistPanelVideoRenderer').forEach(function (panel) {
      if (!panel || !panel.videoId) return;
      var id = String(panel.videoId);
      if (seen[id]) return;
      var title = runsText(panel.title || {});
      if (!title) return;
      var byline = runsText(panel.longBylineText || panel.shortBylineText || {});
      var parts = splitParts(byline);
      var artistText = parts[0] || '';
      var lengthText = runsText(panel.lengthText || {});
      var thumbs = (panel.thumbnail && panel.thumbnail.thumbnails) || [];
      seen[id] = true;
      songs.push(toSong({
        videoId: id,
        title: title,
        artists: artistText ? artistText.split(/[、,&/]/).map(function (name) { return { id: '', name: name.trim() }; }).filter(function (a) { return a.name; }) : [],
        album: { id: '', name: parts[1] || '' },
        cover: thumbs.length ? String(thumbs[thumbs.length - 1].url || '') : '',
        durationMs: parseDurationMs(lengthText)
      }));
    });
    return songs;
  }

  function onlySongs(list) {
    var seen = Object.create(null);
    var out = [];
    (list || []).forEach(function (song) {
      if (!song || !song.id) return;
      // 只要真正的单曲（videoId 固定 11 位）；歌单 id（VL…/MPRE…）一律不要
      if (!/^[A-Za-z0-9_-]{11}$/.test(String(song.id))) return;
      if (seen[song.id]) return;
      seen[song.id] = true;
      out.push(song);
    });
    return out;
  }

  async function radioFromSeed(seedVideoId, limit) {
    var attempts = [
      { name: 'browse-RDAMVM', run: function () { return innertube('/browse', { browseId: 'RDAMVM' + seedVideoId }, { auth: true, timeoutMs: 25000 }); } },
      { name: 'next-playlist', run: function () { return innertube('/next', { videoId: seedVideoId, playlistId: 'RDAMVM' + seedVideoId, isAudioOnly: true }, { auth: true, timeoutMs: 25000 }); } },
      { name: 'next-video', run: function () { return innertube('/next', { videoId: seedVideoId, isAudioOnly: true }, { auth: true, timeoutMs: 25000 }); } }
    ];
    var best = [];
    for (var i = 0; i < attempts.length; i += 1) {
      try {
        var json = await attempts[i].run();
        var songs = onlySongs(songsFromPlaylistPanel(json).concat(collectSongs(json)));
        log('info', '电台尝试 ' + attempts[i].name + ' → ' + songs.length + ' 首');
        if (songs.length > best.length) best = songs;
        if (best.length >= Math.min(limit, 8)) break;
      } catch (error) {
        log('warn', '电台尝试 ' + attempts[i].name + ' 失败：' + (error && error.message || error));
      }
    }
    log('info', '电台（种子 ' + seedVideoId + '）→ ' + best.length + ' 首');
    return best.slice(0, limit);
  }

  async function feed(params) {
    var limit = Math.max(1, Math.min(Number(params.limit) || 12, 50));
    if (!session()) return { provider: 'ytm', songs: [], hasMore: false };
    // 1) 先拿一个种子：喜欢的第一首 → 没有就搜一下热门
    var seed = '';
    try {
      var liked = await likedSongs({ limit: 5, offset: 0 });
      var likedSongsOnly = onlySongs(liked.songs || []);
      if (likedSongsOnly.length) seed = likedSongsOnly[0].id;
    } catch (e) { /* 忽略 */ }
    if (!seed) {
      try {
        var hot = await search({ keywords: '热门音乐', limit: 5 });
        var hotSongs = onlySongs(hot.songs || []);
        if (hotSongs.length) seed = hotSongs[0].id;
      } catch (e) { /* 忽略 */ }
    }
    if (seed) {
      try {
        var radio = await radioFromSeed(seed, limit);
        if (radio.length) return { provider: 'ytm', songs: radio, hasMore: false, seed: seed };
      } catch (error) {
        log('warn', '电台取流失败：' + (error && error.message || error));
      }
    }
    // 2) 兜底：首页推荐
    try {
      var json = await innertube('/browse', { browseId: 'FEmusic_home' }, { auth: true, timeoutMs: 25000 });
      var songs = onlySongs(collectSongs(json));
      log('info', '首页推荐 → ' + songs.length + ' 首');
      return { provider: 'ytm', songs: songs.slice(0, limit), hasMore: false };
    } catch (error) {
      log('warn', '首页推荐失败：' + (error && error.message || error));
      return { provider: 'ytm', songs: [], hasMore: false, error: String(error && error.message || error) };
    }
  }

  async function feedLegacy(params) {
    if (!session()) return { provider: 'ytm', songs: [] };
    var json = await innertube('/browse', { browseId: 'FEmusic_home' }, { auth: true });
    var limit = Math.max(1, Math.min(Number(params.limit) || 12, 50));
    var songs = collectSongs(json);
    log('info', 'feed → ' + songs.length + ' 首');
    return { provider: 'ytm', songs: songs.slice(0, limit), hasMore: false };
  }

  async function likeCheck(params) {
    var ids = String(params.ids || params.id || '').split(',').map(function (v) { return v.trim(); }).filter(Boolean);
    var result = { provider: 'ytm', liked: {}, complete: true };
    ids.forEach(function (id) { result.liked[id] = false; });
    return result;
  }

  async function like(params) {
    throw new Error('YouTube Music 点赞暂未开放');
  }

  function logout() {
    saveSession(null);
    return { provider: 'ytm', ok: true, loggedIn: false };
  }

  async function router(input) {
    var path = String((input && input.path) || '');
    var query = (input && input.query) || {};
    var body = (input && input.body) || {};
    var pick = function (key) { return query[key] != null ? query[key] : body[key]; };
    if (path === '/api/ytm/status') return statusPayload();
    if (path === '/api/ytm/profile') {
      var account = await readAccount();
      return account ? Object.assign({ provider: 'ytm', ok: true }, account) : { provider: 'ytm', ok: false, error: '读不到账号信息' };
    }
    if (path === '/api/ytm/logout') return logout();
    if (path === '/api/ytm/search') return search({ keywords: pick('keywords') || pick('query') || pick('q'), limit: pick('limit') });
    if (path === '/api/ytm/song/url') return songUrl(String(pick('id') || ''), pick('quality'));
    if (path === '/api/ytm/lyric') return lyric(String(pick('id') || ''));
    if (path === '/api/ytm/user/playlists') return userPlaylists();
    if (path === '/api/ytm/playlist/tracks') return playlistTracks({ id: pick('id') });
    if (path === '/api/ytm/feed') return feed({ limit: pick('limit') });
    if (path === '/api/ytm/song/like/check') return likeCheck({ ids: pick('ids'), id: pick('id') });
    if (path === '/api/ytm/song/like') return like({ id: pick('id') });
    throw new Error('未知的 YouTube 接口：' + path);
  }

  function reportError(path, err) {
    var message = (err && err.message) ? String(err.message) : String(err);
    log('error', String(path).replace('/api/ytm/', '') + ' 失败：' + message);
    try { if (typeof root.showToast === 'function') root.showToast('YouTube ' + String(path).replace('/api/ytm/', '') + ' 失败：' + message); } catch (e) { /* 忽略 */ }
    return { provider: 'ytm', ok: false, error: message, message: message };
  }

  async function routerSafe(input) {
    try { return await router(input); } catch (err) { return reportError(String((input && input.path) || ''), err); }
  }

  root.__mrYtmRouter = routerSafe;
  root.__mrYtmApi = { router: routerSafe, session: session, saveSession: saveSession, status: statusPayload, readAccount: readAccount };
})(typeof window !== 'undefined' ? window : globalThis);
