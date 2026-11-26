// public/js/android-shim/45-builtin-playlists.js
// ───────────────────────────────────────────────────────────────
// 安卓「Mineradio 内置歌单」本机实现（localStorage，纯前端，零后端）。
//
// 为什么需要它：
//   public/js/modules/06-lyrics/00-built-in-playlists.js 原来只会调
//   window.desktopWindow.{list,create,read,add,remove,rename,delete}BuiltInPlaylist*，
//   那是 Electron 桌面版才有的 Node 接口。安卓壳里按设计【故意不设】
//   window.desktopWindow（见 40-wallpaper-bridge.js 顶部注释：整个 App 把它的存在
//   当作「这是桌面端」的判据），所以 builtInPlaylistApiAvailable() 永远 false，
//   点「+ 新建内置」只会弹「当前环境无法保存内置歌单」。
//
// 契约：与桌面版**逐字段一致**，全部是 Promise：
//   listBuiltInPlaylists()                → {ok, playlists}
//   createBuiltInPlaylist(name)           → {ok, playlists, playlist}
//   readBuiltInPlaylist(id, {limit,offset})→ {ok, playlist, tracks, total, hasMore, nextOffset}
//   addBuiltInPlaylistTrack(id, track)    → {ok, playlists, duplicate?}
//   removeBuiltInPlaylistTrack(id, index) → {ok, playlists, removed}
//   renameBuiltInPlaylist(id, name)       → {ok, playlists, playlist}
//   deleteBuiltInPlaylist(id)             → {ok, playlists, removed}
// 错误码（与 00-built-in-playlists.js 的 builtInPlaylistErrorMessage 对齐）：
//   BUILT_IN_PLAYLIST_LIMIT_REACHED / BUILT_IN_PLAYLIST_TRACK_LIMIT_REACHED /
//   BUILT_IN_PLAYLIST_INDEX_TOO_LARGE / BUILT_IN_PLAYLIST_TRACK_INVALID /
//   BUILT_IN_PLAYLIST_NOT_FOUND（另有 NAME_REQUIRED / READ_FAILED 走兜底文案）
//
// ★ 本文件【绝不】创建 window.desktopWindow，也不设 isDesktop：
//   那是端游判据，误判会在手机上冒出桌面标题栏、并把 #fx-panel 切成桌面布局。
//   业务层通过 builtInPlaylistApi() 在「有 desktopWindow → 桌面 / 否则 → 本对象」里切。
//
// 存储键：mineradio-built-in-playlists-v1
// 全局：window.__mrBuiltInPlaylists  +  window.__mrBuiltInPlaylistBridge()

(function (root, factory) {
  'use strict';
  var api;
  try {
    api = factory(root);
  } catch (e) {
    if (root && typeof root.console !== 'undefined' && root.console.warn) {
      root.console.warn('[MRBuiltInPlaylists] 初始化失败', e);
    }
    return;
  }
  if (!api) return;
  root.__mrBuiltInPlaylists = api;
  root.__mrBuiltInPlaylistBridge = function () { return api; };
  if (root.console && root.console.log) {
    root.console.log('[MRBuiltInPlaylists] 安卓本机内置歌单已就绪（storageKey=' + api.storageKey + ')');
  }

  // ─── 启动时序补偿 ────────────────────────────────────────────
  // 本文件是 <script src> 单独加载的，排在 js/index-loader.js 之后；而 index-loader
  // 是把模块 load 完【立刻同步插 <script> 执行】的，所以 10-shell/05-startup-bindings.js
  // 里那句 `refreshBuiltInPlaylists(false)` 早在它之前就跑过了。那一次拿到的是
  // builtInPlaylistApi() === null（本该 false），结果是启动后侧栏「我的歌单」里
  // 内置歌单要等用户手动刷新才出现。
  // 这里在挂载后补一次刷新，把磁盘上的歌单立刻灌进 UI 快照。
  // 只补一次，并且只在「从来没有成功刷过」的情况下补（已经刷过就别重复打）。
  function scheduleCatchUpRefresh() {
    var attempts = 0;
    function attempt() {
      attempts += 1;
      if (typeof root.refreshBuiltInPlaylists !== 'function') {
        if (attempts < 40) { setTimeout(attempt, 100); }
        return;
      }
      // 模块里已经有数据（启动刷新成功了）→ 不需要补
      if (root.builtInPlaylists && root.builtInPlaylists.length) return;
      try {
        Promise.resolve(root.refreshBuiltInPlaylists(true)).catch(function () { });
      } catch (e) { /* 忽略 */ }
      if (root.console && root.console.debug) {
        root.console.debug('[MRBuiltInPlaylists] 启动补刷新已触发');
      }
    }
    attempt();
  }
  try {
    // 只在真实页面里补偿（纯 Node / 无 DOM 环境下不注册任何定时器）
    if (root.document) {
      if (root.document.readyState === 'loading') {
        root.document.addEventListener('DOMContentLoaded', function () { setTimeout(scheduleCatchUpRefresh, 0); });
      } else {
        setTimeout(scheduleCatchUpRefresh, 0);
      }
    }
  } catch (e) { /* 忽略 */ }
})(typeof window !== 'undefined' ? window : this, function (root) {
  'use strict';

  // ─── 常量 ─────────────────────────────────────────────────────
  var STORAGE_KEY = 'mineradio-built-in-playlists-v1';
  var STORE_VERSION = 1;
  var MAX_PLAYLISTS = 50;               // 内置歌单数量上限
  var MAX_TRACKS_PER_PLAYLIST = 2000;    // 单个内置歌单曲目上限
  var MAX_STORE_BYTES = 4 * 1024 * 1024;  // localStorage 单键软上限（超了报存储上限）
  var DEFAULT_PAGE_LIMIT = 100;
  var MAX_PAGE_LIMIT = 500;
  var NAME_MAX_LENGTH = 40;
  var DEFAULT_NAME = '我的歌单';
  var MAX_TEXT_STRING = 4096;          // 普通字段字符串截断长度
  var MAX_COVER_STRING = 200000;       // 封面（可能是 data URL）允许更长
  var COVER_FIELD_RE = /^(cover|coverurl|pic|picurl|albumcover|image|avatar|photo|icon)$/i;
  // 单曲上可能存放封面的字段（配额兜底时按这个列表剔除内联大图）
  var COVER_KEY_LIST = ['cover', 'coverUrl', 'pic', 'picUrl', 'albumCover', 'image', 'avatar', 'photo', 'icon'];
  var SKIP_KEYS = {
    lyric: 1, lyrics: 1, lrc: 1, lyrictext: 1,
    raw: 1, rawdata: 1, rawsong: 1, rawtracks: 1,
    payload: 1, apipayload: 1, response: 1,
    songdata: 1, songdetail: 1, detail: 1,
    tracks: 1, playlists: 1, playlist: 1,
    related: 1, recommend: 1, comments: 1, hotcomments: 1,
    programs: 1, episodes: 1,
    waveform: 1, beatmap: 1, beatmaps: 1, beats: 1, peaks: 1,
    buffer: 1, arraybuffer: 1, blob: 1, node: 1,
    desktopwindow: 1
  };

  // 无 localStorage（极少数 WebView 关闭了存储）时的内存兜底，至少本次会话可用。
  var memoryState = null;
  var idSeq = 0;

  // ─── 小工具 ───────────────────────────────────────────────────
  function isArray(value) {
    return Object.prototype.toString.call(value) === '[object Array]';
  }

  function extend(target, source) {
    for (var key in source) {
      if (Object.prototype.hasOwnProperty.call(source, key)) target[key] = source[key];
    }
    return target;
  }

  function safeString(value, fallback) {
    if (value == null) return fallback == null ? '' : fallback;
    return String(value);
  }

  function cloneState(state) {
    try {
      return JSON.parse(JSON.stringify(state));
    } catch (e) {
      return { version: STORE_VERSION, updatedAt: 0, playlists: [] };
    }
  }

  function emptyState() {
    return { version: STORE_VERSION, updatedAt: 0, playlists: [] };
  }

  // ─── 存储层 ───────────────────────────────────────────────────
  function storage() {
    try {
      var ls = root.localStorage;
      if (ls && typeof ls.getItem === 'function' && typeof ls.setItem === 'function') return ls;
    } catch (e) { /* 隐私模式/被禁用 */ }
    return null;
  }

  function normalizeName(name) {
    var text = safeString(name).replace(/\s+/g, ' ').trim();
    if (text.length > NAME_MAX_LENGTH) text = text.slice(0, NAME_MAX_LENGTH);
    return text;
  }

  function normalizeState(parsed) {
    var state = emptyState();
    if (!parsed || !isArray(parsed.playlists)) return state;
    state.updatedAt = Number(parsed.updatedAt) || 0;
    var seenIds = {};
    for (var i = 0; i < parsed.playlists.length && state.playlists.length < MAX_PLAYLISTS; i += 1) {
      var raw = parsed.playlists[i];
      if (!raw || typeof raw !== 'object') continue;
      var id = safeString(raw.id).trim();
      var name = normalizeName(raw.name) || DEFAULT_NAME;
      if (!id) id = 'mr-legacy-' + i;
      if (seenIds[id]) continue;
      seenIds[id] = true;
      var tracks = [];
      var rawTracks = isArray(raw.tracks) ? raw.tracks : [];
      for (var j = 0; j < rawTracks.length && tracks.length < MAX_TRACKS_PER_PLAYLIST; j += 1) {
        var clean = sanitizeTrack(rawTracks[j]);
        if (clean) tracks.push(clean);
      }
      state.playlists.push({
        id: id,
        name: name,
        cover: safeString(raw.cover) || firstTrackCover(tracks),
        createdAt: Number(raw.createdAt) || 0,
        updatedAt: Number(raw.updatedAt) || 0,
        tracks: tracks
      });
    }
    return state;
  }

  /**
   * 读取当前状态。永不抛错：没有数据 → 空数组；JSON 坏了 → 空数组（不误报）。
   */
  function readState() {
    var ls = storage();
    if (!ls) {
      if (!memoryState) memoryState = emptyState();
      return cloneState(memoryState);
    }
    var raw = '';
    try {
      raw = ls.getItem(STORAGE_KEY) || '';
    } catch (e) {
      raw = '';
    }
    if (!raw) return emptyState();
    var parsed = null;
    try {
      parsed = JSON.parse(raw);
    } catch (e) {
      parsed = null;
    }
    if (!parsed) return emptyState();
    return normalizeState(parsed);
  }

  /**
   * 写入当前状态。返回 true/false；false 表示超出存储上限（调用方回
   * BUILT_IN_PLAYLIST_INDEX_TOO_LARGE =「内置歌单数据已达到存储上限」）。
   *
   * ★ 配额兜底：真写不进去时，先「瘦身」重试一次 —— 把 data: 内联封面
   *   （一首内嵌封面动辄几百 KB，是撑爆 localStorage 的主因）剔掉，
   *   换成可选的外链封面。瘦身后仍然写不进去才判存储上限失败。
   */
  function writeState(state) {
    state.version = STORE_VERSION;
    state.updatedAt = Date.now();
    var json = '';
    try {
      json = JSON.stringify(state);
    } catch (e) {
      return false;
    }
    if (typeof json !== 'string' || !json) return false;

    var ls = storage();
    if (!ls) {
      memoryState = cloneState(state);
      return json.length <= MAX_STORE_BYTES;
    }
    if (tryWrite(ls, json)) return true;

    // ── 瘦身重试：剔除内联大封面 ──
    var slim = slimInlineCovers(state);
    if (!slim.changed) return false;
    var slimJson = '';
    try {
      slimJson = JSON.stringify(slim.state);
    } catch (e) {
      return false;
    }
    if (!slimJson || slimJson.length > MAX_STORE_BYTES) return false;
    if (!tryWrite(ls, slimJson)) return false;
    // 写成功 → 把瘦身后的状态回灌给调用方，保证返回值里的封面和落盘一致
    state.playlists = slim.state.playlists;
    return true;
  }

  function tryWrite(ls, json) {
    try {
      ls.setItem(STORAGE_KEY, json);
      return true;
    } catch (e) {
      return false;   // QuotaExceededError 等
    }
  }

  function isInlineCover(value) {
    var text = safeString(value).trim();
    return /^data:image\//i.test(text) || text.length > 8192;
  }

  /**
   * 返回一份「剔除内联封面」的新状态。内联封面换成外链（若 track 上另有
   * onlineCover/coverUrl 外链）或直接删掉；字段名不变，详情页照常取 cover。
   */
  function slimInlineCovers(state) {
    var changed = false;
    var fresh = cloneState(state);
    for (var i = 0; i < fresh.playlists.length; i += 1) {
      var playlist = fresh.playlists[i];
      if (isInlineCover(playlist.cover)) {
        playlist.cover = '';
        changed = true;
      }
      for (var j = 0; j < playlist.tracks.length; j += 1) {
        var track = playlist.tracks[j];
        if (!track || typeof track !== 'object') continue;
        for (var k = 0; k < COVER_KEY_LIST.length; k += 1) {
          var key = COVER_KEY_LIST[k];
          if (!isInlineCover(track[key])) continue;
          changed = true;
          var online = findOnlineCover(track);
          track[key] = online || '';
        }
        // 瘦身后封面全空 → 删掉字段，字段名不变（详情页按 cover 取，空串等价于无封面）
        for (var m = 0; m < COVER_KEY_LIST.length; m += 1) {
          if (track[COVER_KEY_LIST[m]] === '') delete track[COVER_KEY_LIST[m]];
        }
      }
      if (!safeString(playlist.cover)) playlist.cover = firstTrackCover(playlist.tracks);
    }
    return { changed: changed, state: fresh };
  }

  function findOnlineCover(track) {
    var candidates = ['onlineCover', 'coverUrl', 'picUrl', 'albumCover', 'remoteCover', 'coverRemote'];
    for (var i = 0; i < candidates.length; i += 1) {
      var value = safeString(track[candidates[i]]).trim();
      if (value && /^https?:\/\//i.test(value)) return value;
    }
    return '';
  }

  // ─── 快照 / 字段清洗 ──────────────────────────────────────────
  function firstTrackCover(tracks) {
    for (var i = 0; i < (tracks || []).length; i += 1) {
      var song = tracks[i];
      var cover = safeString(song && (song.cover || song.coverUrl || song.picUrl || song.albumCover || song.pic)).trim();
      if (cover) return cover;
    }
    return '';
  }

  function playlistSnapshot(playlist) {
    var tracks = playlist && isArray(playlist.tracks) ? playlist.tracks : [];
    var cover = safeString(playlist && playlist.cover) || firstTrackCover(tracks);
    return {
      id: safeString(playlist && playlist.id),
      name: safeString(playlist && playlist.name) || DEFAULT_NAME,
      trackCount: tracks.length,
      cover: cover,
      coverUrl: cover,
      creator: 'Mineradio',
      provider: 'mineradio',
      source: 'mineradio',
      builtin: true,
      shelfPane: 'mine',
      subscribed: false,
      createdAt: Number(playlist && playlist.createdAt) || 0,
      updatedAt: Number(playlist && playlist.updatedAt) || 0
    };
  }

  function playlistsSnapshot(state) {
    var list = [];
    state = state || emptyState();
    for (var i = 0; i < state.playlists.length; i += 1) {
      list.push(playlistSnapshot(state.playlists[i]));
    }
    return list;
  }

  function copyTrack(track) {
    try {
      return JSON.parse(JSON.stringify(track));
    } catch (e) {
      return extend({}, track);
    }
  }

  /**
   * 递归清洗一个要落盘的值：剥掉歌词/大数组等巨大字段，截断超长字符串，
   * 其余原样保留（字段名不变，详情页能直接播）。
   */
  function cleanValue(value, key, depth) {
    if (value == null) return null;
    var type = typeof value;
    if (type === 'string') {
      var limit = COVER_FIELD_RE.test(safeString(key)) ? MAX_COVER_STRING : MAX_TEXT_STRING;
      return value.length > limit ? value.slice(0, limit) : value;
    }
    if (type === 'number') return isFinite(value) ? value : null;
    if (type === 'boolean') return value;
    if (type === 'function' || type === 'symbol' || type === 'undefined') return null;
    if (depth >= 3) return null;
    if (isArray(value)) {
      var arr = [];
      for (var i = 0; i < value.length && arr.length < 64; i += 1) {
        var item = cleanValue(value[i], key, depth + 1);
        if (item != null) arr.push(item);
      }
      return arr;
    }
    var out = {};
    var keys = Object.keys(value);
    for (var k = 0; k < keys.length && k < 64; k += 1) {
      var childKey = keys[k];
      if (SKIP_KEYS[safeString(childKey).toLowerCase()]) continue;
      var child = cleanValue(value[childKey], childKey, depth + 1);
      if (child != null) out[childKey] = child;
    }
    return out;
  }

  // 判定「有可保存的音源标识」用的字段（不含歌名兜底）。
  var IDENTITY_FIELDS = [
    'id', 'songId', 'mid', 'songmid', 'mediaMid', 'media_mid', 'qqId',
    'hash', 'fileHash', 'audioHash', 'resHash', 'hqHash', 'sqHash', 'mixSongId',
    'localKey', 'localFileId', 'storageRef',
    'programId', 'radioId',
    'spotifyId', 'spotifyUri', 'uri', 'url'
  ];

  var KNOWN_PROVIDERS = { netease: 1, qq: 1, kugou: 1, qishui: 1, spotify: 1, bodian: 1, ytm: 1, local: 1, podcast: 1, mineradio: 1 };

  // 每家的「主键」优先级：先按平台自己的唯一键，再退到通用 id。
  var PROVIDER_KEY_FIELDS = {
    qq: ['mid', 'songmid', 'mediaMid', 'media_mid', 'qqId', 'id'],
    kugou: ['hash', 'fileHash', 'audioHash', 'resHash', 'hqHash', 'sqHash', 'albumAudioId', 'mixSongId', 'id'],
    spotify: ['spotifyId', 'spotifyUri', 'uri', 'id'],
    local: ['localKey', 'localFileId', 'storageRef', 'id'],
    podcast: ['programId', 'radioId', 'id'],
    mineradio: ['id', 'mid', 'hash', 'localKey', 'localFileId', 'storageRef', 'spotifyId', 'uri', 'url']
  };

  function guessProvider(track) {
    var explicit = safeString(track && (track.provider || track.source || track.type)).toLowerCase();
    if (explicit && KNOWN_PROVIDERS[explicit]) return explicit;
    if (track.spotifyId || track.spotifyUri || track.spotifyUrl) return 'spotify';
    if (track.localKey || track.localFileId) return 'local';
    if (track.programId) return 'podcast';
    if (track.hash || track.fileHash || track.audioHash) return 'kugou';
    if (track.mid || track.songmid || track.qqId) return 'qq';
    return 'mineradio';
  }

  function trackIdentity(track) {
    if (!track || typeof track !== 'object') return '';
    var provider = guessProvider(track);
    var fields = PROVIDER_KEY_FIELDS[provider] || IDENTITY_FIELDS;
    for (var i = 0; i < fields.length; i += 1) {
      var value = track[fields[i]];
      if (value == null || value === '') continue;
      if (typeof value === 'object') continue;
      if (typeof value === 'boolean') continue;
      return provider + ':' + fields[i] + ':' + safeString(value);
    }
    // 兜底：平台没给主键，但给了别的可识别字段
    for (var j = 0; j < IDENTITY_FIELDS.length; j += 1) {
      var other = track[IDENTITY_FIELDS[j]];
      if (other == null || other === '') continue;
      if (typeof other === 'object' || typeof other === 'boolean') continue;
      return provider + ':' + IDENTITY_FIELDS[j] + ':' + safeString(other);
    }
    return '';
  }

  function trackDedupeKey(track) {
    var identity = trackIdentity(track);
    if (identity) return identity;
    return 'meta:' + safeString(track && (track.name || track.title)).trim() + '|' + safeString(track && track.artist).trim();
  }

  function sanitizeTrack(track) {
    if (!track || typeof track !== 'object') return null;
    var cleaned = cleanValue(track, 'track', 0);
    if (!cleaned || typeof cleaned !== 'object' || isArray(cleaned)) return null;
    if (!trackIdentity(cleaned)) return null;
    return cleaned;
  }

  // ─── 业务小工具 ───────────────────────────────────────────────
  function findPlaylist(state, id) {
    id = safeString(id).trim();
    if (!id) return null;
    for (var i = 0; i < state.playlists.length; i += 1) {
      if (state.playlists[i].id === id) return state.playlists[i];
    }
    return null;
  }

  function nextPlaylistId(state) {
    idSeq += 1;
    var stamp = Date.now().toString(36);
    var rand = Math.floor(Math.random() * 1679616).toString(36);
    var id = '';
    do {
      idSeq += 1;
      id = 'mr-' + stamp + '-' + idSeq.toString(36) + '-' + rand;
    } while (findPlaylist(state, id));
    return id;
  }

  function currentStamp() {
    return Date.now();
  }

  function pageLimit(options) {
    var limit = Number(options && options.limit);
    if (!isFinite(limit) || limit <= 0) limit = DEFAULT_PAGE_LIMIT;
    if (limit > MAX_PAGE_LIMIT) limit = MAX_PAGE_LIMIT;
    return Math.floor(limit);
  }

  function pageOffset(options) {
    var offset = Number(options && options.offset);
    if (!isFinite(offset) || offset < 0) offset = 0;
    return Math.floor(offset);
  }

  function fail(error, extra) {
    var result = { ok: false, error: error, playlists: [] };
    if (extra) extend(result, extra);
    return result;
  }

  function readFailed() {
    return { ok: false, error: 'BUILT_IN_PLAYLIST_READ_FAILED' };
  }

  // ─── 对外 API（全部返回 Promise，与桌面版一致）─────────────────
  function listBuiltInPlaylists() {
    return Promise.resolve().then(function () {
      return { ok: true, playlists: playlistsSnapshot(readState()) };
    });
  }

  function createBuiltInPlaylist(name) {
    return Promise.resolve().then(function () {
      var state = readState();
      var cleanName = normalizeName(name);
      if (!cleanName) {
        return fail('BUILT_IN_PLAYLIST_NAME_REQUIRED', { playlists: playlistsSnapshot(state) });
      }
      if (state.playlists.length >= MAX_PLAYLISTS) {
        return fail('BUILT_IN_PLAYLIST_LIMIT_REACHED', { playlists: playlistsSnapshot(state) });
      }
      var stamp = currentStamp();
      var playlist = {
        id: nextPlaylistId(state),
        name: cleanName,
        cover: '',
        createdAt: stamp,
        updatedAt: stamp,
        tracks: []
      };
      state.playlists.unshift(playlist);
      if (!writeState(state)) {
        return fail('BUILT_IN_PLAYLIST_INDEX_TOO_LARGE', { playlists: playlistsSnapshot(readState()) });
      }
      return {
        ok: true,
        playlists: playlistsSnapshot(state),
        playlist: playlistSnapshot(playlist)
      };
    });
  }

  function readBuiltInPlaylist(id, options) {
    return Promise.resolve().then(function () {
      var state = readState();
      var playlist = findPlaylist(state, id);
      if (!playlist) {
        return {
          ok: false,
          error: 'BUILT_IN_PLAYLIST_NOT_FOUND',
          message: '内置歌单已不存在',
          playlist: null,
          tracks: [],
          total: 0,
          hasMore: false,
          nextOffset: 0
        };
      }
      var tracks = isArray(playlist.tracks) ? playlist.tracks : [];
      var total = tracks.length;
      var offset = pageOffset(options);
      var limit = pageLimit(options);
      if (offset > total) offset = total;
      var page = tracks.slice(offset, offset + limit);
      var nextOffset = offset + page.length;
      return {
        ok: true,
        playlist: playlistSnapshot(playlist),
        tracks: page.map(copyTrack),
        total: total,
        hasMore: nextOffset < total,
        nextOffset: nextOffset,
        offset: offset,
        limit: limit
      };
    });
  }

  function addBuiltInPlaylistTrack(id, track) {
    return Promise.resolve().then(function () {
      var state = readState();
      var playlist = findPlaylist(state, id);
      if (!playlist) {
        return fail('BUILT_IN_PLAYLIST_NOT_FOUND', { playlists: playlistsSnapshot(state) });
      }
      var clean = sanitizeTrack(track);
      if (!clean) {
        return fail('BUILT_IN_PLAYLIST_TRACK_INVALID', { playlists: playlistsSnapshot(state) });
      }
      var key = trackDedupeKey(clean);
      var tracks = playlist.tracks || (playlist.tracks = []);
      for (var i = 0; i < tracks.length; i += 1) {
        if (trackDedupeKey(tracks[i]) === key) {
          return { ok: true, duplicate: true, playlists: playlistsSnapshot(state), track: copyTrack(clean) };
        }
      }
      if (tracks.length >= MAX_TRACKS_PER_PLAYLIST) {
        return fail('BUILT_IN_PLAYLIST_TRACK_LIMIT_REACHED', { playlists: playlistsSnapshot(state) });
      }
      tracks.push(clean);
      playlist.updatedAt = currentStamp();
      if (!safeString(playlist.cover)) playlist.cover = firstTrackCover(tracks);
      if (!writeState(state)) {
        return fail('BUILT_IN_PLAYLIST_INDEX_TOO_LARGE', { playlists: playlistsSnapshot(readState()) });
      }
      return { ok: true, playlists: playlistsSnapshot(state), track: copyTrack(clean) };
    });
  }

  function removeBuiltInPlaylistTrack(id, index) {
    return Promise.resolve().then(function () {
      var state = readState();
      var playlist = findPlaylist(state, id);
      if (!playlist) {
        return fail('BUILT_IN_PLAYLIST_NOT_FOUND', { playlists: playlistsSnapshot(state) });
      }
      var tracks = playlist.tracks || (playlist.tracks = []);
      var at = Math.floor(Number(index));
      if (!isFinite(at) || at < 0 || at >= tracks.length) {
        return { ok: true, removed: false, playlists: playlistsSnapshot(state) };
      }
      tracks.splice(at, 1);
      playlist.updatedAt = currentStamp();
      playlist.cover = firstTrackCover(tracks);
      if (!writeState(state)) {
        return fail('BUILT_IN_PLAYLIST_INDEX_TOO_LARGE', { playlists: playlistsSnapshot(readState()) });
      }
      return { ok: true, removed: true, playlists: playlistsSnapshot(state) };
    });
  }

  function renameBuiltInPlaylist(id, name) {
    return Promise.resolve().then(function () {
      var state = readState();
      var playlist = findPlaylist(state, id);
      if (!playlist) {
        return fail('BUILT_IN_PLAYLIST_NOT_FOUND', { playlists: playlistsSnapshot(state) });
      }
      var cleanName = normalizeName(name);
      if (!cleanName) {
        return fail('BUILT_IN_PLAYLIST_NAME_REQUIRED', { playlists: playlistsSnapshot(state) });
      }
      playlist.name = cleanName;
      playlist.updatedAt = currentStamp();
      if (!writeState(state)) {
        return fail('BUILT_IN_PLAYLIST_INDEX_TOO_LARGE', { playlists: playlistsSnapshot(readState()) });
      }
      return { ok: true, playlists: playlistsSnapshot(state), playlist: playlistSnapshot(playlist) };
    });
  }

  function deleteBuiltInPlaylist(id) {
    return Promise.resolve().then(function () {
      var state = readState();
      var playlist = findPlaylist(state, id);
      if (!playlist) {
        return { ok: true, removed: false, playlists: playlistsSnapshot(state) };
      }
      var next = [];
      for (var i = 0; i < state.playlists.length; i += 1) {
        if (state.playlists[i].id !== playlist.id) next.push(state.playlists[i]);
      }
      state.playlists = next;
      if (!writeState(state)) {
        return fail('BUILT_IN_PLAYLIST_INDEX_TOO_LARGE', { playlists: playlistsSnapshot(readState()) });
      }
      return { ok: true, removed: true, playlists: playlistsSnapshot(state) };
    });
  }

  // ─── 调试辅助（业务不使用）───────────────────────────────────
  function debug() {
    var state = readState();
    var summary = [];
    for (var i = 0; i < state.playlists.length; i += 1) {
      summary.push({
        id: state.playlists[i].id,
        name: state.playlists[i].name,
        tracks: state.playlists[i].tracks.length,
        cover: safeString(state.playlists[i].cover).slice(0, 60)
      });
    }
    return { storageKey: STORAGE_KEY, hasLocalStorage: !!storage(), updatedAt: state.updatedAt, playlists: summary };
  }

  function clearAll() {
    return Promise.resolve().then(function () {
      var ls = storage();
      try {
        if (ls) ls.removeItem(STORAGE_KEY);
      } catch (e) { /* 忽略 */ }
      memoryState = emptyState();
      return { ok: true, playlists: [] };
    });
  }

  return {
    storageKey: STORAGE_KEY,
    storageVersion: STORE_VERSION,
    limits: { playlists: MAX_PLAYLISTS, tracksPerPlaylist: MAX_TRACKS_PER_PLAYLIST, page: MAX_PAGE_LIMIT },
    listBuiltInPlaylists: listBuiltInPlaylists,
    createBuiltInPlaylist: createBuiltInPlaylist,
    readBuiltInPlaylist: readBuiltInPlaylist,
    addBuiltInPlaylistTrack: addBuiltInPlaylistTrack,
    removeBuiltInPlaylistTrack: removeBuiltInPlaylistTrack,
    renameBuiltInPlaylist: renameBuiltInPlaylist,
    deleteBuiltInPlaylist: deleteBuiltInPlaylist,
    debug: debug,
    clearAll: clearAll
  };
});
