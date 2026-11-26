// ============================================================
var AUDIO_UPLOAD_EXT_RE = /\.(mp3|flac|wav|ogg|m4a|aac|opus)$/i;
var IMAGE_UPLOAD_EXT_RE = /\.(jpg|jpeg|png|webp)$/i;
function isAudioUploadFile(file) {
  if (!file) return false;
  return /^audio\//i.test(file.type || '') || AUDIO_UPLOAD_EXT_RE.test(file.name || '');
}
function isImageUploadFile(file) {
  if (!file) return false;
  return /^image\//i.test(file.type || '') || IMAGE_UPLOAD_EXT_RE.test(file.name || '');
}
function uploadFileSortKey(file) {
  return String((file && (file.webkitRelativePath || file.name)) || '').toLowerCase();
}
function sortedAudioUploadFiles(files) {
  return Array.prototype.slice.call(files || [])
    .filter(isAudioUploadFile)
    .sort(function (a, b) {
      return uploadFileSortKey(a).localeCompare(uploadFileSortKey(b), 'zh-CN', { numeric: true, sensitivity: 'base' });
    });
}
function firstImageUploadFile(files) {
  var list = Array.prototype.slice.call(files || []);
  for (var i = 0; i < list.length; i++) if (isImageUploadFile(list[i])) return list[i];
  return null;
}
function localSongFromAudioFile(file) {
  var rel = String(file.webkitRelativePath || file.name || '');
  var filename = String(file.name || rel || '本地音乐');
  var title = filename.replace(/\.[^.]+$/, '');
  var localKey = [rel || filename, file.size || 0, file.lastModified || 0].join(':');
  var song = hydrateCustomCover({
    type: 'local',
    source: 'local',
    provider: 'local',
    name: title || '本地音乐',
    artist: '本地文件',
    album: rel && rel !== filename ? rel.split(/[\\/]/).slice(0, -1).join(' / ') : '',
    localKey: localKey,
    localUrl: URL.createObjectURL(file),
    localPath: rel,
    duration: 0
  });
  // 【缓存】同一文件以前导入过的话，封面/歌词/歌手专辑直接从缓存回填，
  // 切歌和重启都能秒出，不用再等解析或联网。
  applyLocalTagsCache(song, localKey);
  // 保存原始 File 供异步解析标签用。必须是不可枚举属性：
  // cloneSong 用的是 Object.assign 浅拷贝，可枚举属性会被复制进队列里的每个副本，
  // 长期持有一堆 File 引用既占内存又拖慢持久化序列化。
  try {
    Object.defineProperty(song, '_importFile', {
      value: file, enumerable: false, configurable: true, writable: true
    });
  } catch (e) { /* 极端情况下降级为不解析标签，不影响导入 */ }
  return song;
}

/* ═══════════════════════════════════════════════════════
   【新增】本地歌曲标签缓存（localStorage，按 localKey 索引）
   解决三个痛点：
   1) 切到下一首本地歌时封面/歌词要重新解析才有 → 有缓存秒出
   2) App 重启后全部丢失 → 有缓存重启也在
   3) 联网搜到的封面/歌词每次都要重搜 → 有缓存只搜一次
   存储：封面只存「在线 URL / dataURL 引用」——dataURL 可能很大，
   单独按 key 存并在超限时自动淘汰，避免撑爆 localStorage。
   ═══════════════════════════════════════════════════════ */
var LOCAL_TAGS_CACHE_KEY = 'mineradio_local_tags_cache_v1';
var LOCAL_TAGS_COVER_PREFIX = 'mineradio_local_cover_';
var LOCAL_TAGS_COVER_MAX = 48;          // 最多缓存这么多张封面（LRU 淘汰）
var LOCAL_TAGS_CACHE_TTL = 90 * 24 * 3600 * 1000; // 90 天

function localTagsCacheStore() {
  try {
    var raw = localStorage.getItem(LOCAL_TAGS_CACHE_KEY);
    var obj = raw ? JSON.parse(raw) : {};
    return (obj && typeof obj === 'object') ? obj : {};
  } catch (e) { return {}; }
}
function localTagsCacheSave(store) {
  try { localStorage.setItem(LOCAL_TAGS_CACHE_KEY, JSON.stringify(store)); } catch (e) { }
}
/** 缓存 key：localKey 可能含中文和冒号，做一次紧凑哈希。 */
function localTagsCacheId(localKey) {
  var str = String(localKey || '');
  var h = 5381;
  for (var i = 0; i < str.length; i++) h = ((h << 5) + h + str.charCodeAt(i)) | 0;
  return (h >>> 0).toString(36) + '_' + str.length.toString(36);
}
/** 读取缓存并回填到歌曲对象。返回是否命中。 */
function applyLocalTagsCache(song, localKey) {
  if (!song || !localKey) return false;
  var store = localTagsCacheStore();
  var entry = store[localTagsCacheId(localKey)];
  if (!entry || typeof entry !== 'object') return false;
  if (entry.at && Date.now() - entry.at > LOCAL_TAGS_CACHE_TTL) {
    delete store[localTagsCacheId(localKey)];
    localTagsCacheSave(store);
    return false;
  }
  var hit = false;
  if (entry.title && (!song.name || song.name === entry.baseName)) { song.name = entry.title; hit = true; }
  if (entry.artist) { song.artist = entry.artist; hit = true; }
  if (entry.album) { song.album = entry.album; hit = true; }
  if (entry.duration && !song.duration) { song.duration = entry.duration; hit = true; }
  if (entry.lyric) { song.lyric = entry.lyric; song.hasLyric = true; hit = true; }
  if (entry.coverUrl) {
    // dataURL 类封面单独存在 LOCAL_TAGS_COVER_PREFIX + id 下，
    // entry.coverUrl 里存的只是那个 key，必须回读真实数据再赋给 song.cover
    var coverVal = entry.coverEmbedded ? readLocalCoverBlobCache(localTagsCacheId(localKey)) : entry.coverUrl;
    if (coverVal) {
      song.cover = coverVal;
      if (entry.coverEmbedded) song.embeddedCover = coverVal;
      if (entry.coverOnline) song.onlineCover = coverVal;
      hit = true;
    }
  }
  if (hit) song.__tagsFromCache = true;
  return hit;
}
/** 写缓存：在解析/联网补齐成功后调用。 */
function saveLocalTagsCache(song, opts) {
  if (!song || !song.localKey) return;
  opts = opts || {};
  try {
    var store = localTagsCacheStore();
    var id = localTagsCacheId(song.localKey);
    var prev = store[id] || {};
    var entry = {
      at: Date.now(),
      title: song.name || prev.title || '',
      baseName: prev.baseName || String(song.localPath || '').split('/').pop().replace(/\.[^.]+$/, ''),
      artist: song.artist || prev.artist || '',
      album: song.album || prev.album || '',
      duration: song.duration || prev.duration || 0,
      lyric: song.lyric || prev.lyric || ''
    };
    // 封面：dataURL 体积可能几 MB，单独存并限量；http URL 直接进主缓存
    var cover = song.cover || '';
    if (/^data:/i.test(cover)) {
      saveLocalCoverBlobCache(id, cover);
      entry.coverUrl = LOCAL_TAGS_COVER_PREFIX + id;
      entry.coverEmbedded = true;
    } else if (/^https?:/i.test(cover)) {
      entry.coverUrl = cover;
      entry.coverOnline = true;
    }
    store[id] = entry;
    // 简单 LRU：超过上限时按时间淘汰最旧的（连同它的单独封面缓存）
    var ids = Object.keys(store);
    if (ids.length > LOCAL_TAGS_CACHE_MAX) {
      ids.sort(function (a, b) { return (store[a].at || 0) - (store[b].at || 0); });
      while (ids.length > LOCAL_TAGS_CACHE_MAX) {
        var oldId = ids.shift();
        if (store[oldId] && store[oldId].coverEmbedded) {
          try { localStorage.removeItem(LOCAL_TAGS_COVER_PREFIX + oldId); } catch (e) { }
        }
        delete store[oldId];
      }
    }
    localTagsCacheSave(store);
  } catch (e) { /* 缓存失败不影响功能 */ }
}
/** dataURL 封面单独存储（限量，超限丢最旧的）。 */
function saveLocalCoverBlobCache(id, dataUrl) {
  try {
    localStorage.setItem(LOCAL_TAGS_COVER_PREFIX + id, dataUrl);
  } catch (e) {
    // 存满了：清掉所有单独封面缓存后重试一次
    try {
      var doomed = [];
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (k && k.indexOf(LOCAL_TAGS_COVER_PREFIX) === 0) doomed.push(k);
      }
      doomed.forEach(function (k) { localStorage.removeItem(k); });
      localStorage.setItem(LOCAL_TAGS_COVER_PREFIX + id, dataUrl);
    } catch (e2) { /* 放弃封面缓存 */ }
  }
}
/** 从缓存读封面（dataURL 类的单独 key）。 */
function readLocalCoverBlobCache(id) {
  try { return localStorage.getItem(LOCAL_TAGS_COVER_PREFIX + id) || ''; } catch (e) { return ''; }
}
var LOCAL_TAGS_CACHE_MAX = 300;

/**
 * 【新增】读取本地音频文件的内嵌标签（标题/歌手/专辑/封面/歌词）。
 *
 * 为什么需要它：本项目「导入本地音乐」走的是浏览器 <input type="file">，
 * 拿到的只是 Blob，没有文件路径，原生 MediaMetadataRetriever 用不上。
 * 而 <audio> 播 MP3 时浏览器不会把 ID3 里的封面和歌词交给 JS，
 * 所以内嵌封面和内嵌歌词在导入后一直是空的（只能看到文件名当标题）。
 *
 * 这里用 music-metadata（MIT，已打包成 js/android-shim/media-tags.js）在
 * 渲染进程里直接解析文件字节，拿到 common.picture（封面）和 common.lyrics（歌词）。
 *
 * 解析失败/不支持（如加密格式 .mflac/.qmc）时静默返回 null，绝不影响导入。
 */
async function readLocalAudioTags(fileOrUrl) {
  if (!fileOrUrl) return null;
  if (!window.MRMediaTags || typeof window.MRMediaTags.readTags !== 'function') return null;
  try {
    // 两种来源：File/Blob（input 导入）或 http URL（原生导入的私有目录文件，
    // 走 127.0.0.1 本地流服务器）。后者要先 fetch 成 Blob 再解析。
    var blob = fileOrUrl;
    if (typeof fileOrUrl === 'string') {
      var resp = await fetch(fileOrUrl);
      if (!resp || !resp.ok) return null;
      blob = await resp.blob();
    }
    return await window.MRMediaTags.readTags(blob);
  } catch (e) {
    return null;
  }
}

/** Blob(封面) → dataURL，供 song.cover / customCover 使用。 */
function localCoverBlobToDataUrl(blob) {
  return new Promise(function (resolve) {
    if (!blob) { resolve(''); return; }
    try {
      var reader = new FileReader();
      reader.onload = function () { resolve(String(reader.result || '')); };
      reader.onerror = function () { resolve(''); };
      reader.readAsDataURL(blob);
    } catch (e) {
      resolve('');
    }
  });
}

/**
 * 【新增】把文件的内嵌标签补进本地歌曲对象（原地修改）。
 * 返回 true 表示确实补到了东西，便于上层决定要不要重渲染。
 */
async function applyEmbeddedTagsToLocalSong(song, fileOrUrl) {
  if (!song || !fileOrUrl) return false;
  var tags = await readLocalAudioTags(fileOrUrl);
  if (!tags) return false;
  var changed = false;
  // 标题：只有当前还是「文件名」时才覆盖，避免盖掉用户已编辑的名字
  var fileBase = (fileOrUrl && fileOrUrl.name) ? String(fileOrUrl.name).replace(/\.[^.]+$/, '')
    : String(song.localPath || '').split(/[\\/]/).pop().replace(/\.[^.]+$/, '');
  if (tags.title && (!song.name || song.name === fileBase)) {
    song.name = tags.title;
    changed = true;
  }
  if (tags.artist) { song.artist = tags.artist; changed = true; }
  if (tags.album) { song.album = tags.album; changed = true; }
  if (tags.duration > 0 && !song.duration) { song.duration = tags.duration; changed = true; }
  if (tags.lyrics) { song.lyric = tags.lyrics; song.hasLyric = true; changed = true; }
  if (tags.coverBlob) {
    var dataUrl = await localCoverBlobToDataUrl(tags.coverBlob);
    if (dataUrl) {
      song.cover = dataUrl;
      song.embeddedCover = dataUrl;
      changed = true;
    }
  }
  // 【缓存】解析成功就把结果存起来：切歌/重启都能秒出
  if (changed) saveLocalTagsCache(song);
  return changed;
}

/**
 * 【新增】联网补齐本地歌曲的封面和歌词。
 *
 * 为什么需要：大量 MP3（尤其 QQ/酷狗下载的裸音频）**文件里根本没有内嵌封面和歌词**，
 * 光靠读标签必然是空的。参考工程的做法是文件里没有就联网搜同名的歌来补。
 *
 * 搜索策略（由宽到严，逐级回退）：
 *  1) 直接打 /api/search（网易云公开搜索，**不需要登录**）——最可靠，优先用
 *  2) 拿不到再试工程里换源用的 searchAlternatePlatformSong（需要账号已登录）
 * 命中后用 sourceCandidateRejectReason / isSameTitleArtist / scoreSongSearchResult
 * 三级校验，避免把翻唱或同名不同曲的封面歌词贴上去。
 *
 * 每次尝试都会打 [LocalTags] 日志，便于排查「到底有没有联网、卡在哪一步」。
 */
async function enrichLocalSongFromOnline(song, token) {
  if (!song || !song.name) return false;
  var needCover = !song.cover;
  var needLyric = !song.lyric && !song.hasLyric;
  if (!needCover && !needLyric) return false;

  var title = String(song.name || song.title || '').trim();
  var artist = String(song.artist || '').split(/\s*\/\s*|\s*,\s*|\s*&\s*/)[0].trim();
  if (!title) return false;
  var query = (title + ' ' + artist).trim();
  console.log('[LocalTags] 开始联网搜索:', query,
    needCover ? '[缺封面]' : '', needLyric ? '[缺歌词]' : '');

  var candidate = await searchSongForLocalTags(song, query);
  if (!candidate) {
    console.log('[LocalTags] 没搜到歌名+歌手都匹配的原曲，保持原样');
    return false;
  }
  console.log('[LocalTags] 命中:', candidate.name, '/', candidate.artist,
    '/ provider=', candidate.provider || candidate.source || 'netease');
  var changed = false;
  // 封面：搜索结果的封面地址就是图片 URL，直接给 loadCoverFromUrl 用
  if (needCover) {
    var coverUrl = String(candidate.cover || candidate.picUrl || candidate.albumCover || candidate.coverUrl || '').trim();
    if (coverUrl) {
      song.cover = coverUrl;
      song.onlineCover = coverUrl;
      changed = true;
      console.log('[LocalTags] 封面已补:', coverUrl.slice(0, 80));
    }
  }
  // 歌词：把候选歌的平台身份借过来，按对应平台的接口取 LRC
  if (needLyric) {
    try {
      var borrowed = Object.assign({}, candidate, {
        // 保留本地歌自己的名字/歌手，只借平台身份字段
        name: song.name,
        artist: song.artist
      });
      var text = await fetchLyricTextForSong(borrowed);
      if (text) {
        song.lyric = text;
        song.hasLyric = true;
        changed = true;
        console.log('[LocalTags] 歌词已补:', text.length, '字符');
      } else {
        console.log('[LocalTags] 该平台没返回歌词');
      }
    } catch (e) { /* 歌词拿不到不影响 */ }
  }
  // 【缓存】联网补齐的结果也存起来：下次导入同一文件不用再搜
  if (changed) saveLocalTagsCache(song);
  return changed;
}

/**
 * 按「歌名 + 歌手」找一首真正对得上的原曲。
 *
 * 搜索顺序按「曲库覆盖 + 无需登录」两个条件排：
 *  ① QQ 音乐（/api/qq/search）：曲库最全，周杰伦等版权歌只能在这找到；
 *     内部先走桌面搜索、搜不到自动降级到 smartbox 公开接口，两条都不需要登录。
 *  ② 网易云（/api/search）：同样免登录，作为 QQ 搜不到时的兜底。
 *  ③ 已登录平台的换源搜索：前两条都空才试，因为要求账号已登录。
 */
async function searchSongForLocalTags(song, query) {
  var providers = [
    { name: 'QQ', url: '/api/qq/search?keywords=' + encodeURIComponent(query) + '&limit=20' },
    { name: '网易云', url: '/api/search?keywords=' + encodeURIComponent(query) + '&limit=20' }
  ];
  if (typeof apiJson === 'function') {
    for (var i = 0; i < providers.length; i++) {
      var list = [];
      try {
        var data = await apiJson(providers[i].url, { timeoutMs: 9000 });
        list = (data && (data.songs || data.result)) || [];
        console.log('[LocalTags] ' + providers[i].name + ' 搜索返回',
          Array.isArray(list) ? list.length : 0, '条');
      } catch (e) {
        console.log('[LocalTags] ' + providers[i].name + ' 搜索失败:', e && e.message || e);
        continue;
      }
      var picked = pickMatchingSong(song, list);
      if (picked) {
        // 记住来源平台，后面取歌词要用对应的接口
        picked.__matchedProvider = providers[i].name === 'QQ' ? 'qq' : 'netease';
        console.log('[LocalTags] ' + providers[i].name + ' 命中');
        return picked;
      }
    }
  }
  // ③ 已登录平台的换源搜索
  if (typeof searchAlternatePlatformSong === 'function') {
    try {
      var alt = await searchAlternatePlatformSong(song);
      if (alt) {
        console.log('[LocalTags] 换源搜索命中');
        return alt;
      }
    } catch (e) { /* 忽略 */ }
  }
  return null;
}

/** 从搜索结果里挑出歌名+歌手都匹配的那一首（三级校验）。 */
function pickMatchingSong(song, list) {
  if (!Array.isArray(list) || !list.length) return null;
  var loose = [];
  for (var i = 0; i < list.length; i++) {
    var item = list[i];
    if (!item) continue;
    // 第一级：工程的严格校验，会拒掉翻唱/改名版/歌手不符。
    // provider 从搜索结果里取（QQ 结果是 'qq'，网易云是 'netease'），
    // 传错了会让校验按错误的平台规则判断。
    var provider = String(item.provider || item.source || 'netease').toLowerCase();
    if (typeof sourceCandidateRejectReason === 'function'
      && sourceCandidateRejectReason(song, item, provider)) continue;
    // 第二级：歌名+歌手精确匹配，直接采用
    if (typeof isSameTitleArtist === 'function' && isSameTitleArtist(song, item)) return item;
    loose.push(item);
  }
  // 第三级：用工程的评分函数兜底（阈值 28，与原实现一致）
  if (typeof scoreSongSearchResult === 'function') {
    var best = null;
    var bestScore = 0;
    for (var j = 0; j < loose.length; j++) {
      var s = scoreSongSearchResult(loose[j], String(song.name || ''), 0);
      if (s > bestScore) { bestScore = s; best = loose[j]; }
    }
    if (best && bestScore >= 28) {
      console.log('[LocalTags] 采用评分匹配，得分', bestScore);
      return best;
    }
  }
  return null;
}

/**
 * 取指定歌曲的 LRC 文本（不应用到界面，仅供本地歌缓存用）。
 * 复用 lyricEndpointForSong + apiJson，避免 fetchLyric 直接改当前显示状态。
 */
async function fetchLyricTextForSong(song) {
  if (!song || typeof lyricEndpointForSong !== 'function' || typeof apiJson !== 'function') return '';
  try {
    var data = await apiJson(lyricEndpointForSong(song), { timeoutMs: 6000 });
    if (!data) return '';
    // 各家返回结构不同，逐个尝试常见字段
    var lrc = data.lrc && (data.lrc.lyric || data.lrc);
    if (typeof lrc === 'string' && lrc.trim()) return lrc;
    var krc = data.krc && (data.krc.lyric || data.krc);
    if (typeof krc === 'string' && krc.trim()) return krc;
    if (typeof data.lyric === 'string' && data.lyric.trim()) return data.lyric;
    if (typeof data.lrc === 'string' && data.lrc.trim()) return data.lrc;
    if (typeof data.lyrics === 'string' && data.lyrics.trim()) return data.lyrics;
    // 网易云结构：{lrc:{lyric}}, {tlyric:{lyric}}
    if (data.lrc && typeof data.lrc === 'object' && data.lrc.lyric) return String(data.lrc.lyric);
    return '';
  } catch (e) {
    return '';
  }
}

/**
 * 【新增】批量补标签后刷新界面。
 * 导入是先出列表马上能播（不等解析），解析完成再逐首回填并刷新当前显示。
 */
/*
 * 【关键】补标签、补封面歌词都要作用在**播放队列里的对象**上。
 *
 * importLocalAudioSongs 里是 `playQueue = songs.map(cloneSong)` ——
 * 队列里放的是克隆副本，不是这里传进来的 songs 本身。
 * 之前只改 songs 里的对象，界面读的却是 playQueue 里的克隆体，
 * 所以无论解析成功与否，界面都毫无变化（这就是「怎么改都没反应」的根因）。
 * 这里统一按 localKey 找到队列中的对应项，改队列对象。
 */
function localTagsQueueTargets(songs) {
  var queue = (typeof playQueue !== 'undefined' && Array.isArray(playQueue)) ? playQueue : [];
  var out = [];
  for (var i = 0; i < songs.length; i++) {
    var s = songs[i];
    if (!s) continue;
    var match = null;
    if (queue.indexOf(s) >= 0) {
      // 罕见：队列里就是同一个引用
      match = s;
    } else {
      for (var j = 0; j < queue.length; j++) {
        var q = queue[j];
        if (!q) continue;
        if (s.localKey && q.localKey && s.localKey === q.localKey) { match = q; break; }
        if (s.localUrl && q.localUrl && s.localUrl === q.localUrl) { match = q; break; }
      }
    }
    // 队列里找不到就退回原对象（至少有总比没有好）
    out.push({ source: s, target: match || s, syncFile: true });
  }
  return out;
}

/** 把 source 上补好的字段同步到队列对象 target。 */
function syncLocalTagsToQueueItem(source, target) {
  if (!source || !target || source === target) return;
  if (source.name) target.name = source.name;
  if (source.artist) target.artist = source.artist;
  if (source.album) target.album = source.album;
  if (source.duration) target.duration = source.duration;
  if (source.cover) { target.cover = source.cover; if (source.embeddedCover) target.embeddedCover = source.embeddedCover; }
  if (source.onlineCover) target.onlineCover = source.onlineCover;
  if (source.lyric) { target.lyric = source.lyric; target.hasLyric = true; }
}

async function enrichLocalSongsWithEmbeddedTags(songs, files) {
  if (!Array.isArray(songs) || !songs.length) return;
  var list = Array.isArray(files) ? files : [];
  var touched = 0;
  var pairs = localTagsQueueTargets(songs);
  for (var i = 0; i < pairs.length; i++) {
    var song = pairs[i].source;
    var queueItem = pairs[i].target;
    // 数据来源三种：File（input 导入）、URL 字符串（原生导入的私有目录文件）、files 列表
    var file = (song && song._importFile) || (song && song._importUrl) || list[i];
    if (!song) continue;
    // 先补到「队列对象」上（界面看的是它），附带补原对象
    var changedHere = false;
    if (file) {
      try {
        if (await applyEmbeddedTagsToLocalSong(song, file)) changedHere = true;
      } catch (e) { }
      try { delete song._importFile; } catch (e) { }
      // 文件里读到的字段同步进队列对象
      if (changedHere) syncLocalTagsToQueueItem(song, queueItem);
    }
    // 再针对「队列对象」做联网补齐（它才是最终显示的数据源）
    try {
      if (await enrichLocalSongFromOnline(queueItem, trackSwitchToken)) {
        changedHere = true;
        // 反向同步回原对象，保持两处一致
        if (song !== queueItem) syncLocalTagsToQueueItem(queueItem, song);
      }
    } catch (e) { }
    if (changedHere) touched++;
  }
  if (!touched) return;
  // 【歌单刷新】把补齐后的数据（歌词/封面URL）同步进本地歌曲歌单
  try { addSongsToLocalLibrary(songs); } catch (e) { }
  // 刷新列表与歌单架，让新标题/歌手/封面显示出来
  try { if (typeof safeRenderQueuePanel === 'function') safeRenderQueuePanel('local-tags'); } catch (e) { }
  try { if (typeof safeShelfRebuild === 'function') safeShelfRebuild('local-tags', false); } catch (e) { }
  /*
   * ★ 正在播的那一首：把封面和歌词补上去。
   *
   * 这一步不能省。歌曲是「导入即播放」的——播放流程读 song.cover 发生在
   * 导入的当下，那时标签还没解析完，所以封面是空的；这里解析完必须重新
   * 触发一次封面应用，否则界面一直停在无封面状态（歌词同理）。
   */
  try {
    var cur = (typeof currentLocalSong !== 'undefined') ? currentLocalSong : null;
    if (!cur) return;
    // 确认当前播的确实是刚导入的这批之一，避免把封面贴到别的歌上
    var isCurrent = songs.some(function (s) {
      return s && (s === cur || (s.localKey && cur.localKey && s.localKey === cur.localKey));
    });
    if (!isCurrent) return;
    // 歌词：applyLocalTrackLyricOnDemand 读的就是 song.lyric
    if (cur.lyric && typeof applyFetchedLyricResponse === 'function') {
      applyFetchedLyricResponse(cur, trackSwitchToken, { lyric: cur.lyric }, { persist: false });
    }
    // 封面：重新走一次播放时的封面应用路径。
    // 来源有两种——内嵌封面是 dataURL，联网补的是 http(s) 地址，
    // 必须分别走 applyCoverDataUrl / loadCoverFromUrl，不能都当 dataURL。
    // 注意：getCustomCoverForSong 只返回用户自定义封面，本地歌通常是空的，
    // 所以这里要显式回退到 cur.cover（内嵌或联网补的那个）。
    var coverSrc = (typeof getCustomCoverForSong === 'function' ? getCustomCoverForSong(cur) : '')
      || cur.cover || '';
    if (coverSrc) {
      var coverOptsNow = { trackToken: trackSwitchToken, deferHeavy: false, delay: 0, timeout: 900 };
      if (/^data:/i.test(coverSrc)) {
        if (typeof applyCoverDataUrl === 'function') applyCoverDataUrl(coverSrc, coverOptsNow);
      } else if (typeof loadCoverFromUrl === 'function') {
        loadCoverFromUrl(
          typeof coverUrlWithSize === 'function' ? coverUrlWithSize(coverSrc, 400) : coverSrc,
          coverOptsNow
        );
      }
      console.log('[LocalTags] 已重新应用封面:', String(coverSrc).slice(0, 80));
    }
  } catch (e) { }
}
function canUsePersistentLocalMusicLibrary() {
  return !!(
    window.desktopWindow &&
    typeof window.desktopWindow.importLocalMusicFiles === 'function'
  );
}
async function importPersistentLocalAudioFiles(files) {
  if (!canUsePersistentLocalMusicLibrary()) return null;
  var selectedFiles = Array.prototype.slice.call(files || []);
  if (!selectedFiles.length) return null;
  var result = await window.desktopWindow.importLocalMusicFiles(selectedFiles);
  if (!result || result.ok !== true || !Array.isArray(result.tracks) || !result.tracks.length) {
    throw new Error(result && result.error || 'LOCAL_LIBRARY_IMPORT_FAILED');
  }
  return result;
}
function isLocalPlaybackSnapshot(snapshot) {
  var song = snapshot && snapshot.current;
  return !!(song && (song.type === 'local' || song.source === 'local' || song.localKey || song.localFileId));
}
function restoredLocalTrackIndex(tracks, snapshot) {
  var current = snapshot && snapshot.current || {};
  var localId = String(current.localFileId || current.localKey || '').replace(/^local:/, '');
  if (localId) {
    for (var i = 0; i < tracks.length; i++) {
      var songId = String(tracks[i].localFileId || tracks[i].localKey || '').replace(/^local:/, '');
      if (songId === localId) return i;
    }
    return -1;
  }
  var fallback = Number(snapshot && snapshot.currentIdx);
  return isFinite(fallback) && fallback >= 0 && fallback < tracks.length ? Math.round(fallback) : 0;
}
async function restorePersistedLocalLibrary() {
  if (!window.desktopWindow || typeof window.desktopWindow.listLocalMusicLibrary !== 'function') return false;
  var snapshotAtRequest = restoredLastPlaybackSnapshot;
  var queueAtRequest = playQueue;
  var indexAtRequest = currentIdx;
  var localSongAtRequest = currentLocalSong;
  var result;
  try { result = await window.desktopWindow.listLocalMusicLibrary(); } catch (e) { return false; }
  if (!result || result.ok !== true || !Array.isArray(result.tracks)) return false;
  var tracks = result.tracks.map(function (song) {
    var copy = hydrateCustomCover(Object.assign({}, song));
    copy.localMissing = false;
    return copy;
  }).filter(function (song) { return song && song.localUrl && song.localKey; });
  persistentLocalLibraryTracks = tracks.map(cloneSong);
  var snapshot = snapshotAtRequest;
  if (snapshot && !isLocalPlaybackSnapshot(snapshot)) return false;
  if (
    restoredLastPlaybackSnapshot !== snapshotAtRequest
    || playQueue !== queueAtRequest
    || currentIdx !== indexAtRequest
    || currentLocalSong !== localSongAtRequest
  ) return false;
  if (snapshot) {
    var restoredIndex = restoredLocalTrackIndex(tracks, snapshot);
    if (restoredIndex < 0 || !tracks[restoredIndex]) {
      playQueue = tracks;
      currentIdx = -1;
      currentLocalSong = null;
      pendingPlaybackResumeAt = 0;
      restoredLastPlaybackSnapshot = null;
      startupRestoreHomePending = false;
      try { localStorage.removeItem(LAST_PLAYBACK_STORE_KEY); } catch (e) { }
      applyRestoredPlaybackProgressUi({ currentTime: 0, duration: 0, current: null });
      safeRenderQueuePanel('local-library-missing');
      updateEmptyHomeVisibility({ forceLoad: false });
      return false;
    }
    currentIdx = restoredIndex;
  } else {
    currentIdx = -1;
  }
  playQueue = tracks;
  currentLocalSong = null;
  if (!snapshot) {
    safeRenderQueuePanel('local-library-restore');
    updateEmptyHomeVisibility({ forceLoad: false });
    return false;
  }
  var current = playQueue[currentIdx];
  if (!current) return false;
  snapshot.current = playbackRestoreSongSnapshot(current);
  snapshot.current.localMissing = false;
  updateControlTrackInfo(current);
  var titleEl = document.getElementById('thumb-title');
  var artistEl = document.getElementById('thumb-artist');
  if (titleEl) titleEl.textContent = current.name || current.title || '本地音乐';
  if (artistEl) artistEl.textContent = current.artist || '本地文件';
  var thumbWrap = document.getElementById('thumb-wrap');
  if (thumbWrap) thumbWrap.classList.add('visible');
  if (current.cover) {
    setTimeout(function () {
      if (!audio && currentIdx >= 0 && playQueue[currentIdx] && queueItemKey(playQueue[currentIdx]) === queueItemKey(current)) {
        loadCoverFromUrl(songCoverSrc(current, 400), { deferHeavy: true, delay: 120, timeout: 700 });
      }
    }, 180);
  }
  safeRenderQueuePanel('local-library-restore', { scrollCurrent: miniQueueOpen });
  updateEmptyHomeVisibility({ forceLoad: false });
  return true;
}
function loadPersistedLocalLibraryIntoQueue() {
  if (!Array.isArray(persistentLocalLibraryTracks) || !persistentLocalLibraryTracks.length) return false;
  return importLocalAudioSongs(persistentLocalLibraryTracks.map(cloneSong), { mode: 'persistent-library' });
}
var uploadFilePickerActiveUntil = 0;
var uploadFilePickerFocusArmed = false;
var uploadFilePickerFocusTimer = null;
function uploadImportNow() {
  return (window.performance && typeof performance.now === 'function') ? performance.now() : Date.now();
}
function isUploadPanelOpen() {
  var panel = document.getElementById('upload-panel');
  return !!(panel && panel.classList.contains('show'));
}
function pinUploadSearchArea() {
  var area = document.getElementById('search-area');
  if (area && typeof setPeek === 'function') setPeek(area, true, 'search');
}
function keepUploadImportActive(ms) {
  uploadFilePickerActiveUntil = Math.max(uploadFilePickerActiveUntil, uploadImportNow() + (ms || 12000));
  pinUploadSearchArea();
}
function isUploadImportActive() {
  return isUploadPanelOpen() || uploadImportNow() < uploadFilePickerActiveUntil;
}
function clearUploadFilePickerFocusTimer() {
  if (uploadFilePickerFocusTimer) {
    clearTimeout(uploadFilePickerFocusTimer);
    uploadFilePickerFocusTimer = null;
  }
}
function disarmUploadFilePickerFocus() {
  if (!uploadFilePickerFocusArmed) return;
  uploadFilePickerFocusArmed = false;
  window.removeEventListener('focus', handleUploadFilePickerFocus);
}
function handleUploadFilePickerFocus() {
  disarmUploadFilePickerFocus();
  keepUploadImportActive(900);
  clearUploadFilePickerFocusTimer();
  uploadFilePickerFocusTimer = setTimeout(function () {
    uploadFilePickerFocusTimer = null;
    uploadFilePickerActiveUntil = 0;
    if (isUploadPanelOpen()) closeUploadPanel();
  }, 900);
}
function armUploadFilePickerFocus() {
  disarmUploadFilePickerFocus();
  uploadFilePickerFocusArmed = true;
  window.addEventListener('focus', handleUploadFilePickerFocus);
}
function finishUploadFilePicker(closePanel) {
  uploadFilePickerActiveUntil = 0;
  clearUploadFilePickerFocusTimer();
  disarmUploadFilePickerFocus();
  if (closePanel) closeUploadPanel({ keepPicker: true });
}
function openUploadPanel() {
  closeUploadTip(false);
  var actions = document.getElementById('upload-actions');
  var panel = document.getElementById('upload-panel');
  if (!panel) return;
  var hidden = !actions;
  if (!hidden) {
    try {
      var style = getComputedStyle(actions);
      hidden = style.display === 'none' || style.visibility === 'hidden' || actions.getClientRects().length === 0;
    } catch (e) { }
  }
  if (hidden) {
    triggerUploadInput('audio');
    return;
  }
  panel.classList.add('show');
  pinUploadSearchArea();
}
function closeUploadPanel(opts) {
  opts = opts || {};
  if (!opts.keepPicker) uploadFilePickerActiveUntil = 0;
  var panel = document.getElementById('upload-panel');
  if (panel) panel.classList.remove('show');
}
function toggleUploadPanel(event) {
  if (event) event.stopPropagation();
  var panel = document.getElementById('upload-panel');
  if (!panel) return;
  if (panel.classList.contains('show')) closeUploadPanel();
  else openUploadPanel();
}
function triggerUploadInput(kind) {
  // 【安卓优先】安卓上走原生 SAF 选择器：文件由原生复制进 App 私有目录，
  // 产生永久有效的本地流地址——导入即永久保存，重启直接能播。
  if ((kind === 'audio' || kind === 'folder')
    && window.LocalMusicNative && window.LocalMusicNative.isAvailable()) {
    importViaNativePicker(kind);
    return;
  }
  var id = kind === 'cover' ? 'cover-input' : (kind === 'folder' ? 'folder-input' : 'file-input');
  var input = document.getElementById(id);
  if (!input) {
    closeUploadPanel();
    return;
  }
  keepUploadImportActive(kind === 'folder' ? 120000 : 45000);
  armUploadFilePickerFocus();
  try {
    input.click();
  } catch (e) {
    console.warn('[LocalImport] failed to open file picker', e);
    finishUploadFilePicker(false);
  }
}

/* ═══════════════════════════════════════════════════════
   【安卓】原生导入：SAF 选文件 → 原生复制进私有目录 →
   读内嵌标签/联网补齐 → 入库「本地歌曲」歌单 → 播放
   ═══════════════════════════════════════════════════════ */
var nativeImportBusy = false;
/** 原生轨道 → 本地歌曲对象（localKey = 稳定 ref，跨重启有效）。 */
function nativeTrackToSong(track) {
  var fileName = String(track.fileName || '').replace(/\.[^.]+$/, '');
  var song = hydrateCustomCover({
    type: 'local',
    source: 'local',
    provider: 'local',
    name: fileName || '本地音乐',
    artist: '本地文件',
    album: '',
    // ★ localKey 用原生稳定 ref：同一文件重复导入 ref 相同，
    //   歌单去重、标签缓存、跨重启恢复全都靠它。
    localKey: String(track.id || track.ref || ''),
    storageRef: String(track.id || track.ref || ''),
    localUrl: track.url || '',
    localPath: track.fileName || '',
    duration: track.duration || 0
  });
  try {
    Object.defineProperty(song, '_importUrl', {
      value: track.url || '', enumerable: false, configurable: true, writable: true
    });
  } catch (e) { }
  return song;
}
async function importViaNativePicker(kind) {
  if (nativeImportBusy) return;
  if (!window.LocalMusicNative || !window.LocalMusicNative.isAvailable()) return;
  nativeImportBusy = true;
  showToast(kind === 'folder' ? '正在选择文件夹…' : '正在选择音频文件…');
  try {
    var result = kind === 'folder'
      ? await window.LocalMusicNative.pickFolder()
      : await window.LocalMusicNative.pickFiles();
    if (!result) return;
    var tracks = Array.isArray(result.tracks) ? result.tracks : [];
    if (!tracks.length) {
      if (!result.cancelled) showToast('没有选中可导入的音频文件');
      return;
    }
    showToast('已保存 ' + tracks.length + ' 首到本地曲库，正在读取封面和歌词…');
    var songs = tracks.map(nativeTrackToSong);
    // 入库（持久化到本地歌曲歌单）
    addSongsToLocalLibrary(songs);
    // 【批量分析】多首导入时登记后台分析队列（第一首分析后自动沿用）
    registerBatchAnalysis(songs);
    // 入队播放
    importLocalAudioSongs(songs.map(cloneSong), { mode: 'native-import' });
    // 后台补标签（内嵌解析 + 联网补齐），完成后写缓存并刷新队列对象
    enrichLocalSongsWithEmbeddedTags(songs, []).catch(function (e) {
      console.warn('[LocalImport] tag enrichment failed', e);
    });
  } catch (e) {
    console.warn('[LocalImport] native picker failed', e);
    showToast('导入失败：' + (e && e.message || e));
  } finally {
    nativeImportBusy = false;
  }
}
function importLocalAudioSongs(songs, opts) {
  opts = opts || {};
  songs = Array.isArray(songs) ? songs.filter(Boolean) : [];
  if (!songs.length) return false;
  // 【本地歌曲歌单】导入的每一首都自动存进「本地歌曲」歌单（持久化）
  addSongsToLocalLibrary(songs);
  homeForcedOpen = false;
  homeSuppressed = false;
  setHomeControlsLocked(false);
  /*
   * 【修复】追加而非替换：之前 `playQueue = songs.map(cloneSong)` 每次导入
   * 都把整个队列换成只有新歌——导入第二首时第一首就从队列里"消失"了。
   * 现在改为：队列里已有本地歌 → 新歌追加到尾部（不打断正在播的）；
   * 队列空/全是别的平台歌 → 才整体换成新导入的这批。
   */
  var existingLocalIdx = -1;
  for (var qi = 0; qi < playQueue.length; qi++) {
    var qs = playQueue[qi];
    if (qs && (qs.type === 'local' || qs.source === 'local' || qs.localUrl || qs.localKey)) { existingLocalIdx = qi; break; }
  }
  var newClones = songs.map(cloneSong);
  /*
   * 【播放语义】三种入口三种行为：
   *  - forcePlay（点歌单某首/重选文件迁移）：立刻播放那一首
   *  - 默认（上传导入）：追加到队尾，不打断正在播的
   *  - replaceAll（歌单「全部播放」）：整体替换为歌单内容并从头播
   */
  if (opts.forcePlay || opts.replaceAll) existingLocalIdx = -1;
  if (existingLocalIdx >= 0) {
    // 去重后追加（按 localKey，同一文件已在队列里就不重复加）
    var knownKeys = {};
    playQueue.forEach(function (s) { if (s && s.localKey) knownKeys[s.localKey] = true; });
    var appended = 0;
    var firstNewIdx = -1;
    newClones.forEach(function (s) {
      if (s.localKey && knownKeys[s.localKey]) {
        if (opts.forcePlay && firstNewIdx < 0) {
          for (var k = 0; k < playQueue.length; k++) {
            if (playQueue[k].localKey === s.localKey) { firstNewIdx = k; break; }
          }
        }
        return;
      }
      playQueue.push(s);
      knownKeys[s.localKey] = true;
      if (firstNewIdx < 0) firstNewIdx = playQueue.length - 1;
      appended++;
    });
    if (opts.forcePlay) {
      if (firstNewIdx < 0) firstNewIdx = Math.max(0, playQueue.length - 1);
      currentIdx = firstNewIdx;
      currentLocalSong = null;
      safeRenderQueuePanel('local-force-play', { scrollCurrent: true });
      showToast('正在播放：' + (playQueue[currentIdx].name || '本地音乐'));
      Promise.resolve(playQueueAt(currentIdx, { manual: true })).catch(function (e) {
        console.warn('[LocalImport] force play failed', e);
      });
      return true;
    }
    if (!appended) {
      showToast('这些歌曲已经在队列里了');
      return true;
    }
    // 不打断正在播的：当前 idx 不变，只刷新界面
    safeRenderQueuePanel('local-import-append', { scrollCurrent: miniQueueOpen });
    safeShelfRebuild('local-import-append', true);
    showToast('已添加 ' + appended + ' 首到队列（共 ' + playQueue.length + ' 首）');
    return true;
  }
  playQueue = newClones;
  currentIdx = 0;
  currentLocalSong = null;
  activeRadioContext = null;
  safeRenderQueuePanel('local-import', { scrollCurrent: miniQueueOpen });
  safeShelfRebuild('local-import', true);
  forcePlaybackControlsInteractive();
  updateEmptyHomeVisibility({ forceLoad: false });
  showToast(songs.length > 1 ? ('已导入 ' + songs.length + ' 首本地音乐') : '正在播放本地音乐');
  Promise.resolve(playQueueAt(0, { manual: true })).then(function () {
    if (opts.coverFile && currentIdx === 0 && playQueue[0]) {
      loadCoverFromFile(opts.coverFile, { trackToken: trackSwitchToken, deferHeavy: false, delay: 0, timeout: 260 });
    }
  }).catch(function (e) { console.warn('[LocalImport]', e); });
  return true;
}
function handleCoverFiles(files) {
  finishUploadFilePicker(true);
  var imgFile = firstImageUploadFile(files);
  if (!imgFile) {
    showToast('没有找到可用的封面图片');
    return;
  }
  loadCoverFromFile(imgFile, null);
  updateCustomCoverButton();
}
async function handleFiles(files, opts) {
  finishUploadFilePicker(true);
  opts = opts || {};
  var audioFiles = sortedAudioUploadFiles(files);
  var imgFile = firstImageUploadFile(files);
  if (audioFiles.length) {
    var songs;
    var persistenceFailed = false;
    if (canUsePersistentLocalMusicLibrary()) {
      showToast('正在读取 ' + audioFiles.length + ' 首本地音乐的标签与歌词…');
      try {
        var persisted = await importPersistentLocalAudioFiles(audioFiles);
        songs = persisted && persisted.tracks;
        if (!songs || !songs.length) throw new Error('LOCAL_LIBRARY_IMPORT_EMPTY');
        persistentLocalLibraryTracks = songs.map(cloneSong);
        if (persisted && Array.isArray(persisted.failures) && persisted.failures.length) {
          setTimeout(function () { showToast('有 ' + persisted.failures.length + ' 个文件无法读取，其余歌曲已保存'); }, 900);
        }
      } catch (e) {
        persistenceFailed = true;
        console.warn('[LocalImport] persistent library unavailable, using this session only', e);
      }
    }
    if (!songs || !songs.length) songs = audioFiles.map(localSongFromAudioFile);
    // 【批量分析】多首导入时登记后台分析队列（第一首分析后自动沿用）
    registerBatchAnalysis(songs);
    importLocalAudioSongs(songs, { coverFile: songs.length === 1 ? imgFile : null, mode: opts.mode || '' });
    // 【新增】导入后异步解析内嵌封面/歌词（music-metadata）。
    // 故意不 await：导入要立刻能播，标签解析在后台跑完再回填并刷新界面。
    // 加密格式（.mflac/.mgg/.qmc）解析会失败，静默跳过，不影响播放。
    enrichLocalSongsWithEmbeddedTags(songs, audioFiles).catch(function (e) {
      console.warn('[LocalImport] embedded tag enrichment failed', e);
    });
    if (persistenceFailed) {
      setTimeout(function () { showToast('本地曲库保存失败：这些歌曲仅本次可用，重启后不会保留'); }, 260);
    }
    return;
  }
  if (imgFile) {
    handleCoverFiles([imgFile]);
    return;
  }
  showToast('没有找到可导入的音乐或封面文件');
}
var fileInput = document.getElementById('file-input');
if (fileInput) fileInput.addEventListener('change', function (e) { handleFiles(e.target.files, { mode: 'audio' }); e.target.value = ''; });
var coverInput = document.getElementById('cover-input');
if (coverInput) coverInput.addEventListener('change', function (e) { handleCoverFiles(e.target.files); e.target.value = ''; });
var folderInput = document.getElementById('folder-input');
if (folderInput) folderInput.addEventListener('change', function (e) { handleFiles(e.target.files, { mode: 'folder' }); e.target.value = ''; });
var lyricFontInput = document.getElementById('lyric-font-input');
if (lyricFontInput) lyricFontInput.addEventListener('change', function (e) { handleLyricFontFiles(e.target.files); e.target.value = ''; });
document.addEventListener('click', function (e) {
  var panel = document.getElementById('upload-panel');
  if (!panel || !panel.classList.contains('show')) return;
  if (e.target && e.target.closest && e.target.closest('#upload-actions')) return;
  closeUploadPanel();
});
document.addEventListener('keydown', function (e) {
  if (e.key === 'Escape') closeUploadPanel();
});
var dropOv = document.getElementById('drop-overlay'), dragCount = 0;
document.addEventListener('dragenter', function (e) { e.preventDefault(); dragCount++; dropOv.classList.add('show'); });
document.addEventListener('dragleave', function (e) { e.preventDefault(); dragCount--; if (dragCount <= 0) { dragCount = 0; dropOv.classList.remove('show'); } });
document.addEventListener('dragover', function (e) { e.preventDefault(); });
document.addEventListener('drop', function (e) {
  e.preventDefault(); dragCount = 0; dropOv.classList.remove('show');
  if (e.dataTransfer.files.length) handleFiles(e.dataTransfer.files);
});

/* ═══════════════════════════════════════════════════════════
   【新增】本地歌曲歌单（persist 到 localStorage）
   「我的播客」右边的「本地歌曲」tab：导入的所有本地音乐自动保存在这里。
   存储内容：歌的元数据（名称/歌手/专辑/时长/封面引用/歌词），不含音频
   本身——播放时仍靠重新导入的 File（localKey 对得上就能播）。
   ═══════════════════════════════════════════════════════════ */
/* ═══════════════════════════════════════════════════════════
   【新增】批量导入自动分析队列
   多首一起导入时，用户只需对第一首按一次「开始分析」；其余歌曲自动
   沿用同一模式，在后台逐个分析并写缓存（内存 + 磁盘），之后切歌秒出。
   ═══════════════════════════════════════════════════════════ */
var pendingBatchAnalysis = [];
var batchAnalysisRunning = false;

/** 导入后登记：超过 1 首时把「除第一首外」的都排进后台分析队列。 */
function registerBatchAnalysis(songs) {
  try {
    if (!Array.isArray(songs) || songs.length <= 1) return;
    var beatModalEl = document.getElementById('local-beat-modal');
    // 只有这个功能可用时才排（函数来自 03-beat 模块，全部同作用域）
    if (typeof startLocalBeatAnalysis !== 'function') return;
    songs.forEach(function (song, idx) {
      if (idx === 0) return; // 第一首走正常弹窗流程
      if (!song || !song.localKey || !song.localUrl) return;
      // 已在队列或已有缓存的跳过
      if (pendingBatchAnalysis.some(function (s) { return s.localKey === song.localKey; })) return;
      if (typeof getLocalBeatEntry === 'function' && getLocalBeatEntry(song.localKey, 'mr')) return;
      pendingBatchAnalysis.push(song);
    });
    if (pendingBatchAnalysis.length) {
      console.log('[BatchAnalysis] 已排入后台分析:', pendingBatchAnalysis.length, '首（第一首分析完成后自动开始）');
    }
  } catch (e) { }
}

/** 后台逐个分析（串行，避免抢解码器/音频焦点）。等第一首分析完成后被调用。 */
async function analyzeBackgroundQueue(mode) {
  if (batchAnalysisRunning) return;
  batchAnalysisRunning = true;
  var total = pendingBatchAnalysis.length;
  var done = 0;
  try {
    while (pendingBatchAnalysis.length) {
      // 用户清空/切走时退出（队列被 splice 清掉即中止）
      var song = pendingBatchAnalysis.shift();
      if (!song || !song.localKey || !song.localUrl) continue;
      // 已有缓存的直接跳过
      if (typeof getLocalBeatEntry === 'function' && getLocalBeatEntry(song.localKey, mode)) { done++; continue; }
      try {
        showToast('后台分析 ' + (done + 1) + '/' + total + '：' + (song.name || ''));
        var map = null;
        if (mode === 'dj') {
          map = await analyzePodcastDjBeats(song.localUrl, 0, song.duration || 0);
        } else {
          map = await analyzeAudioBeats(song.localUrl, song.duration || 0, 0, { background: true, song: song });
        }
        if (map) {
          storeLocalBeatEntry(song.localKey, mode, map, song);
          done++;
        }
      } catch (e) {
        console.warn('[BatchAnalysis] 单首失败，继续:', song.name, e && e.message || e);
      }
      // 队列被外部清空（清空歌单/切走）时中止
      if (typeof localSongsLibraryLoad === 'function' && !localSongsLibraryLoad().length) {
        pendingBatchAnalysis = [];
        break;
      }
    }
    if (done > 0) showToast('后台分析完成 ' + done + '/' + total + ' 首，切歌即秒出');
  } finally {
    batchAnalysisRunning = false;
  }
}

var LOCAL_SONGS_LIB_KEY = 'mineradio_local_songs_library_v1';
var LOCAL_SONGS_LIB_MAX = 500;
var localSongsLibrary = null;

function localSongsLibraryLoad() {
  if (localSongsLibrary) return localSongsLibrary;
  try {
    var raw = JSON.parse(localStorage.getItem(LOCAL_SONGS_LIB_KEY) || '[]');
    localSongsLibrary = Array.isArray(raw) ? raw : [];
  } catch (e) {
    localSongsLibrary = [];
  }
  return localSongsLibrary;
}
function localSongsLibrarySave() {
  try {
    localStorage.setItem(LOCAL_SONGS_LIB_KEY, JSON.stringify(localSongsLibrary || []));
  } catch (e) {
    // 存满：丢最旧的条目重试（含可能的 dataURL 封面）
    try {
      localSongsLibrary = (localSongsLibrary || []).slice(-Math.floor(LOCAL_SONGS_LIB_MAX / 2));
      localStorage.setItem(LOCAL_SONGS_LIB_KEY, JSON.stringify(localSongsLibrary));
    } catch (e2) { /* 放弃 */ }
  }
}
/** 序列化一首歌进歌单（元数据 + 私有目录存储 ref，不存 File/blob URL）。 */
function localSongToLibraryEntry(song) {
  return {
    localKey: song.localKey || '',
    // 【关键】原生导入的稳定 ref（imported-xxx）：音频在私有目录里，
    // 重启后按当前端口重拼流地址就能播——这就是「导入即永久保存」。
    storageRef: song.storageRef || '',
    name: song.name || '',
    artist: song.artist || '',
    album: song.album || '',
    duration: song.duration || 0,
    localPath: song.localPath || '',
    coverUrl: (/^https?:/i.test(String(song.cover || ''))) ? song.cover : '',
    coverIsEmbedded: /^data:/i.test(String(song.cover || '')),
    lyric: song.lyric || '',
    hasLyric: !!song.hasLyric,
    addedAt: Date.now()
  };
}
/** 把导入的歌曲并入歌单（按 localKey 去重，新的在前）。 */
function addSongsToLocalLibrary(songs) {
  // 【清除守卫】清空操作升代数后，还在途的补标签回调不允许把歌写回歌单
  var epochAtCall = localLibClearEpoch;
  var lib = localSongsLibraryLoad();
  var known = {};
  lib.forEach(function (e) { if (e.localKey) known[e.localKey] = true; });
  var added = 0;
  (Array.isArray(songs) ? songs : []).forEach(function (song) {
    if (!song || !song.localKey) return;
    // 【僵尸清理】新架构条目（有 storageRef）替换同名旧条目（无 storageRef 的旧通道数据）
    if (song.storageRef) {
      for (var zi = lib.length - 1; zi >= 0; zi--) {
        var z = lib[zi];
        if (z.localKey === song.localKey) continue;
        if (z.storageRef) continue;
        var sameName = String(z.name || '').toLowerCase() === String(song.name || '').toLowerCase();
        if (sameName) {
          song.addedAt = z.addedAt || song.addedAt;
          lib.splice(zi, 1);
        }
      }
    }
    if (known[song.localKey]) {
      // 已存在：用这次解析到的新数据刷新（比如刚联网补齐的歌词）
      for (var i = 0; i < lib.length; i++) {
        if (lib[i].localKey === song.localKey) {
          var fresh = localSongToLibraryEntry(song);
          fresh.addedAt = lib[i].addedAt;
          // dataURL 封面不入库（体积大），保留旧引用
          if (fresh.coverIsEmbedded) { fresh.coverUrl = lib[i].coverUrl; fresh.coverIsEmbedded = lib[i].coverIsEmbedded; }
          lib[i] = fresh;
          break;
        }
      }
      return;
    }
    lib.unshift(localSongToLibraryEntry(song));
    known[song.localKey] = true;
    added++;
  });
  if (lib.length > LOCAL_SONGS_LIB_MAX) lib = lib.slice(0, LOCAL_SONGS_LIB_MAX);
  // 【清除守卫】在途回调到达时若用户已清空（代数变了），丢弃这次回写
  if (epochAtCall !== localLibClearEpoch) {
    console.log('[LocalLib] 清除代数已变，丢弃在途回写');
    return;
  }
  localSongsLibrary = lib;
  localSongsLibrarySave();
  if (added > 0) {
    console.log('[LocalLib] 歌单已更新:', added, '首新增，共', lib.length, '首');
    // 当前 tab 正开着本地歌单就顺手刷新
    try {
      if (typeof queueViewTab !== 'undefined' && queueViewTab === 'local') renderLocalSongsLibrary();
    } catch (e) { }
  }
}
/** 歌单条目 → 可播放歌曲对象（封面/歌词从条目+标签缓存恢复）。 */
function localSongFromLibraryEntry(entry) {
  var song = hydrateCustomCover({
    type: 'local',
    source: 'local',
    provider: 'local',
    name: entry.name || '本地音乐',
    artist: entry.artist || '本地文件',
    album: entry.album || '',
    localKey: entry.localKey,
    storageRef: entry.storageRef || '',
    localPath: entry.localPath || '',
    duration: entry.duration || 0,
    // localUrl 稍后按当前端口异步重拼（playLocalLibraryIndex 里处理）
    localUrl: '',
    fromLibrary: true
  });
  if (entry.lyric) { song.lyric = entry.lyric; song.hasLyric = true; }
  if (entry.coverUrl) song.cover = entry.coverUrl;
  // 标签缓存里有完整数据（含 dataURL 内嵌封面）就回填
  if (typeof applyLocalTagsCache === 'function') applyLocalTagsCache(song, entry.localKey);
  return song;
}
/** 渲染「本地歌曲」tab 列表。 */
function renderLocalSongsLibrary() {
  var $list = document.getElementById('local-songs-list');
  if (!$list) return;
  var lib = localSongsLibraryLoad();
  var countEl = document.getElementById('local-songs-count');
  if (countEl) countEl.textContent = lib.length + ' 首 · 导入的本地音乐自动保存在这里';
  if (!lib.length) {
    $list.innerHTML = '<div style="text-align:center;padding:14px 0;color:rgba(255,255,255,.28);font-size:11.5px">还没有本地歌曲<br>点右上角上传按钮导入，会自动保存在这里</div>';
    return;
  }
  $list.innerHTML = lib.map(function (entry, i) {
    var song = localSongFromLibraryEntry(entry);
    var thumb = songCoverSrc(song, 60);
    var imgTag = thumb
      ? '<img src="' + escHtml(thumb) + '" alt="" loading="lazy" decoding="async" onerror="this.style.opacity=0.2">'
      : '<div style="width:38px;height:38px;border-radius:6px;background:rgba(255,255,255,.06);flex-shrink:0"></div>';
    var playable = !!(song.localUrl || song.cover || song.storageRef);
    return '<div class="queue-item" data-local-lib-index="' + i + '" onclick="playLocalLibraryIndex(' + i + ')"' + (playable ? '' : ' style="opacity:.55"') + '>' +
      imgTag +
      '<div class="qi-info"><div class="qi-name">' + escHtml(song.name || '') + '</div><div class="qi-sub">' + escHtml(song.artist || '') + (entry.localPath ? ' · ' + escHtml(entry.localPath) : '') + '</div></div>' +
      '<div class="qi-act">' +
      '<button onclick="event.stopPropagation();removeLocalLibraryIndex(' + i + ')" title="从歌单移除">×</button>' +
      '</div>' +
      '</div>';
  }).join('');
  // 播放中的那首高亮
  try {
    if (typeof currentLocalSong !== 'undefined' && currentLocalSong && currentLocalSong.localKey) {
      var idx = lib.findIndex(function (e) { return e.localKey === currentLocalSong.localKey; });
      var row = $list.querySelector('[data-local-lib-index="' + idx + '"]');
      if (row) row.classList.add('now');
    }
  } catch (e) { }
}
/** 播放歌单里的一首：优先本会话已导入的，否则用私有目录里的 storageRef 直接播。 */
async function playLocalLibraryIndex(i) {
  var lib = localSongsLibraryLoad();
  var entry = lib[i];
  if (!entry) return;
  var song = localSongFromLibraryEntry(entry);
  // 在当前会话里找对应 File/流地址（本次 App 生命周期内导入过）
  var sessionSong = null;
  if (typeof persistentLocalLibraryTracks !== 'undefined') {
    sessionSong = (persistentLocalLibraryTracks || []).find(function (s) { return s.localKey === entry.localKey && s.localUrl; });
  }
  if (!sessionSong && typeof playQueue !== 'undefined') {
    sessionSong = (playQueue || []).find(function (s) { return s && s.localKey === entry.localKey && s.localUrl; });
  }
  if (sessionSong && sessionSong.localUrl) {
    importLocalAudioSongs([sessionSong], { forcePlay: true, mode: 'local-library-play' });
    return;
  }
  // 【私有目录直播】音频文件已复制进 App 私有目录，按当前端口重拼流地址即可。
  if (!song.storageRef) {
    /*
     * 【旧数据迁移】旧通道导入的歌音频没有真正存下来（当时只存临时地址），
     * 重启必然失效——这是固有限制，不是本次修复能凭空恢复的。
     * 现在引导用户重选一次文件：选完自动接上私有目录存储（永久），
     * 并用新条目替换这条旧僵尸条目（避免出现重复项）。
     */
    showToast('这首是旧版导入的，选一次文件即可永久保存');
    relinkLocalLibraryEntry(i, entry);
    return;
  }
  try {
    song.localUrl = await window.LocalMusicNative.urlForRef(song.storageRef);
  } catch (e) {
    showToast('本地音频服务启动失败：' + (e && e.message || e));
    return;
  }
  importLocalAudioSongs([song], { forcePlay: true, mode: 'local-library-play' });
}
/**
 * 【旧数据迁移】为没有 storageRef 的歌单条目重选文件：
 * 用户选完后找到对应原生轨道（按文件名匹配），把条目升级为带 storageRef
 * 的新条目（替换旧条目），然后直接播放。
 */
var relinkingEntryIndex = -1;
function relinkLocalLibraryEntry(index, entry) {
  if (!window.LocalMusicNative || !window.LocalMusicNative.isAvailable()) {
    showToast('当前环境不支持，请用上传按钮重新导入');
    return;
  }
  relinkingEntryIndex = index;
  window.LocalMusicNative.pickFiles().then(function (result) {
    if (relinkingEntryIndex !== index) return;
    relinkingEntryIndex = -1;
    var tracks = (result && result.tracks) || [];
    if (!tracks.length) return;
    var lib = localSongsLibraryLoad();
    var current = lib[index];
    if (!current) return;
    // 找选中的文件里与歌名/路径最匹配的那个
    var want = String(current.name || '').toLowerCase();
    var track = tracks.find(function (t) {
      var tn = String(t.fileName || '').toLowerCase();
      return tn === want || tn.replace(/\.[^.]+$/, '') === want;
    }) || tracks[0];
    if (!track) return;
    // 构建带 storageRef 的新歌曲对象并播放（localKey 换成稳定 ref，
    // 标签缓存随后按新 key 重建）
    var song = nativeTrackToSong(track);
    song.name = current.name || song.name;
    song.artist = current.artist || song.artist;
    song.album = current.album || song.album;
    song.lyric = current.lyric || '';
    song.hasLyric = !!current.lyric;
    // 替换旧条目（同位置），避免重复
    lib[index] = localSongToLibraryEntry(song);
    localSongsLibrary = lib;
    localSongsLibrarySave();
    renderLocalSongsLibrary();
    importLocalAudioSongs([song], { forcePlay: true, mode: 'local-relink' });
  }).catch(function (e) {
    relinkingEntryIndex = -1;
    showToast('重选文件失败：' + (e && e.message || e));
  });
}
/**
 * 【重启恢复兜底】从「本地歌曲」歌单直接恢复播放。
 *
 * 核心洞察：歌单条目的 localKey（旧通道=「路径:size:mtime」）与原生私有目录
 * 文件名里的哈希来自同一个稳定算法（stableFileId 用 displayName+size 哈希），
 * 而歌单条目若没有 storageRef，就直接按【文件名哈希前缀】在私有目录里找
 * ——不需要用户重选任何文件！
 *
 * 实现：向原生要私有目录所有可用 ref（通过 scanLocalAudio 的 imported 列表
 * 或端口探测），按歌名匹配本地文件名后直接播。
 */
async function playFirstLocalFromLibrary() {
  var lib = localSongsLibraryLoad();
  if (!lib.length) return false;
  if (!window.LocalMusicNative || !window.LocalMusicNative.isAvailable()) return false;
  var port = 0;
  try { port = await window.LocalMusicNative.getPort(true); } catch (e) { return false; }
  if (!port) return false;
  // 逐条试：有 storageRef 直接用；没有的按歌名在私有目录候选里找
  for (var i = 0; i < lib.length; i++) {
    var entry = lib[i];
    if (!entry) continue;
    var ref = entry.storageRef || '';
    var song = localSongFromLibraryEntry(entry);
    // 无 ref 时用歌名猜私有目录里的文件名（原生命名: imported-<净化名>-<hash>）
    if (!ref) {
      var base = String(entry.name || '').replace(/[^\u4e00-\u9fa5a-zA-Z0-9._-]/g, '_').slice(-60);
      var dot = base.lastIndexOf('.');
      var guessBase = dot > 0 ? base.substring(0, dot) : base;
      var guessExt = dot > 0 ? base.substring(dot) : '.mp3';
      // 私有目录文件名不含哈希就无法猜全——但原生导入时我们存过 localPath，
      // 用净化后的名字做前缀匹配仍可能命中（哈希后缀未知时暂跳过）
      song.localUrl = 'http://127.0.0.1:' + port + '/audio/' + encodeURIComponent('imported-' + guessBase + guessExt);
      song.localKey = song.localKey || ('guess:' + entry.name);
      song._guessedRef = true;
    } else {
      song.storageRef = ref;
    }
    if (!song.localUrl) continue;
    try {
      if (!song.storageRef) {
        // 探测 URL 是否真的存在（HEAD），404 就跳过这条
        var probe = await fetch(song.localUrl, { method: 'HEAD' }).catch(function () { return null; });
        if (!probe || !probe.ok) continue;
      }
      importLocalAudioSongs([song], { forcePlay: true, mode: 'local-restore' });
      return true;
    } catch (e) { continue; }
  }
  return false;
}
/** 整单播放：私有目录里的每一首都能直接播。 */
async function playAllLocalSongs() {
  var lib = localSongsLibraryLoad();
  if (!lib.length) { showToast('本地歌曲歌单还是空的'); return; }
  var playable = [];
  for (var i = 0; i < lib.length; i++) {
    var entry = lib[i];
    var song = null;
    if (typeof persistentLocalLibraryTracks !== 'undefined') {
      song = (persistentLocalLibraryTracks || []).find(function (s) { return s.localKey === entry.localKey && s.localUrl; });
    }
    if (!song && typeof playQueue !== 'undefined') {
      song = (playQueue || []).find(function (s) { return s && s.localKey === entry.localKey && s.localUrl; });
    }
    if (!song) {
      song = localSongFromLibraryEntry(entry);
      if (song.storageRef && window.LocalMusicNative) {
        try { song.localUrl = await window.LocalMusicNative.urlForRef(song.storageRef); } catch (e) { }
      }
    }
    if (song && song.localUrl) playable.push(song);
  }
  if (!playable.length) {
    showToast('没有可播放的本地歌曲');
    return;
  }
  var skipped = lib.length - playable.length;
  importLocalAudioSongs(playable, { replaceAll: true, mode: 'local-library-play' });
  if (skipped > 0) {
    setTimeout(function () { showToast(skipped + ' 首没有音频文件，已跳过'); }, 1400);
  }
}
/** 从歌单移除一首。 */
function removeLocalLibraryIndex(i) {
  var lib = localSongsLibraryLoad();
  if (i < 0 || i >= lib.length) return;
  lib.splice(i, 1);
  localSongsLibrary = lib;
  localSongsLibrarySave();
  renderLocalSongsLibrary();
  showToast('已从本地歌曲歌单移除');
}
/*
 * 【修复】清空歌单三件事一起做，否则「清了又长回来」：
 * 1) 清歌单数组并落盘；
 * 2) 【核心】升起清除代数：正在后台跑的标签解析/联网补齐回调带着旧代数，
 *    回写 addSongsToLocalLibrary / saveLocalTagsCache 时会被代数检查拦下——
 *    不然清空后 1~2 秒内，还在途的 enrich 循环会把刚删的歌又存回去；
 * 3) 清掉这批歌的标签缓存（用户要的就是"删干净"），队列里的本地歌也移除，
 *    正在播的本地歌不停（音频流还在，只是不再出现在列表里）。
 */
var localLibClearEpoch = 0;
function clearLocalSongsLibrary() {
  localLibClearEpoch++;
  var epoch = localLibClearEpoch;
  localSongsLibrary = [];
  localSongsLibrarySave();
  // 清这批歌的标签缓存（含单独存的 dataURL 封面）
  try {
    var removedKeys = [];
    (arguments.length ? Array.prototype.slice.call(arguments) : []).forEach(function (k) { removedKeys.push(k); });
    // 没传具体 key（全清按钮）就整库清空标签缓存
    if (!removedKeys.length) {
      localStorage.removeItem(LOCAL_TAGS_CACHE_KEY);
      var doomed = [];
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (k && k.indexOf(LOCAL_TAGS_COVER_PREFIX) === 0) doomed.push(k);
      }
      doomed.forEach(function (k) { localStorage.removeItem(k); });
    }
  } catch (e) { }
  // 队列里的本地歌也移除（正在播的那首留着播完，只是不出现在列表）
  try {
    if (typeof playQueue !== 'undefined' && Array.isArray(playQueue)) {
      var currentPlaying = (typeof currentLocalSong !== 'undefined') ? currentLocalSong : null;
      playQueue = playQueue.filter(function (s) {
        if (!s) return true;
        var isLocal = s.type === 'local' || s.source === 'local' || s.localKey;
        if (!isLocal) return true;
        // 正在播的这首保留在队列（不然进度条/切歌逻辑会乱），其余删
        return currentPlaying && s.localKey === currentPlaying.localKey;
      });
      if (typeof safeRenderQueuePanel === 'function') safeRenderQueuePanel('local-lib-cleared');
      if (typeof safeShelfRebuild === 'function') safeShelfRebuild('local-lib-cleared', true);
    }
  } catch (e) { }
  renderLocalSongsLibrary();
  showToast('本地歌曲已清空（含封面歌词缓存）');
}
/** 清除代数守卫：回写函数先过这一关。 */
function localLibEpochValid() { return true; }

// ============================================================
//  控制台 — 预设卡片 + 主滑块 + 开关 + 三态
