// public/js/ported/bodian/bodian-api.js
// 波点音乐（酷我波点）协议：移植自 folia bodian-api.js（bodian-music-api@0.1.2 的 WebView 版）。
// API  https://bd-api.kuwo.cn   歌词 https://mlyric.kuwo.cn
// 会话 uid + token，持久化在 localStorage 的 mineradio-extra-login-sessions-v1.bodian
// 业务码 200=成功 407=仅限中国大陆 11012=需要登录 20018=不可播放 11027=已扫码未确认 -101=登录失效
(function (root) {
  'use strict';

  var API_ORIGIN = 'https://bd-api.kuwo.cn';
  var LYRIC_ORIGIN = 'https://mlyric.kuwo.cn';
  var STORE_KEY = 'mineradio-extra-login-sessions-v1';
  var DEVICE_KEY = 'mineradio-bodian-device-id';
  var QUALITIES = {
    standard: { format: 'mp3', br: '128kmp3' },
    high: { format: 'mp3', br: '320kmp3' },
    lossless: { format: 'flac', br: '2000kflac' }
  };

  // ---------- 工具 ----------

  function md5(text) {
    function rotl(x, c) { return ((x << c) | (x >>> (32 - c))) >>> 0; }
    function add(a, b) { return (a + b) >>> 0; }
    var s = [7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
      5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
      4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
      6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21];
    var K = [];
    for (var i = 0; i < 64; i += 1) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 0x100000000) >>> 0;
    var bytes = new TextEncoder().encode(String(text || ''));
    var msg = Array.prototype.slice.call(bytes);
    var bitLen = msg.length * 8;
    msg.push(0x80);
    while (msg.length % 64 !== 56) msg.push(0);
    var lenBytes = [];
    for (var b = 0; b < 8; b += 1) {
      lenBytes.push(b < 4 ? ((bitLen >>> (8 * b)) & 0xFF) : (Math.floor(bitLen / Math.pow(2, 8 * b)) & 0xFF));
    }
    msg = msg.concat(lenBytes);
    var a0 = 0x67452301, b0 = 0xEFCDAB89, c0 = 0x98BADCFE, d0 = 0x10325476;
    for (var off = 0; off < msg.length; off += 64) {
      var M = [];
      for (var w = 0; w < 16; w += 1) {
        M[w] = ((msg[off + w * 4]) | (msg[off + w * 4 + 1] << 8) | (msg[off + w * 4 + 2] << 16) | (msg[off + w * 4 + 3] << 24)) >>> 0;
      }
      var A = a0, B = b0, C = c0, D = d0;
      for (var k = 0; k < 64; k += 1) {
        var F, g;
        if (k < 16) { F = (B & C) | (~B & D); g = k; }
        else if (k < 32) { F = (D & B) | (~D & C); g = (5 * k + 1) % 16; }
        else if (k < 48) { F = B ^ C ^ D; g = (3 * k + 5) % 16; }
        else { F = C ^ (B | ~D); g = (7 * k) % 16; }
        F = add(add(add(F, A), K[k]), M[g]);
        A = D; D = C; C = B;
        B = add(B, rotl(F, s[k]));
      }
      a0 = add(a0, A); b0 = add(b0, B); c0 = add(c0, C); d0 = add(d0, D);
    }
    function hex(n) {
      var out = '';
      for (var i2 = 0; i2 < 4; i2 += 1) out += ('0' + ((n >>> (8 * i2)) & 0xFF).toString(16)).slice(-2);
      return out;
    }
    return hex(a0) + hex(b0) + hex(c0) + hex(d0);
  }

  function randomHex(bytes) {
    var out = '';
    try {
      var buf = new Uint8Array(bytes);
      (root.crypto || {}).getRandomValues(buf);
      for (var i = 0; i < buf.length; i += 1) out += ('0' + buf[i].toString(16)).slice(-2);
      return out;
    } catch (e) {
      for (var j = 0; j < bytes * 2; j += 1) out += Math.floor(Math.random() * 16).toString(16);
      return out;
    }
  }

  function deviceId() {
    var saved = '';
    try { saved = localStorage.getItem(DEVICE_KEY) || ''; } catch (e) { saved = ''; }
    if (/^[a-f0-9]{32}$/i.test(saved)) return saved.toLowerCase();
    var id = randomHex(16);
    try { localStorage.setItem(DEVICE_KEY, id); } catch (e) { /* 忽略 */ }
    return id;
  }

  function readStore() {
    try { return JSON.parse(localStorage.getItem(STORE_KEY) || '{}') || {}; } catch (e) { return {}; }
  }
  function bodianSession() {
    var s = readStore().bodian;
    if (!s || !s.uid || !s.token) return null;
    return s;
  }
  function saveBodianSession(next) {
    try {
      var store = readStore();
      if (!store || typeof store !== 'object') store = {};
      if (next) store.bodian = next; else delete store.bodian;
      localStorage.setItem(STORE_KEY, JSON.stringify(store));
    } catch (e) { /* 忽略 */ }
  }
  function sessionUser(session) {
    session = session || {};
    return session.user || { id: String(session.uid || ''), nickname: String(session.nickname || ''), avatarUrl: String(session.avatar || '') };
  }

  // 签名：seed = 'kuwotest' + query 里字母数字排序拼接；有 body 再拼 md5(body+'kuwotest')；sign = md5(seed+path)
  function signQuery(path, params, body) {
    var query = new URLSearchParams(params).toString();
    var seed = 'kuwotest' + Array.from(query).filter(function (ch) { return /[a-z0-9]/i.test(ch); }).sort().join('');
    if (body) seed += md5(body + 'kuwotest');
    return md5(seed + path);
  }

  function baseHeaders() {
    var dev = deviceId();
    return {
      'User-Agent': 'Dart/3.3 (dart:io)', plat: 'win', channel: 'W1', ver: '1.1.7',
      svrver: '13', 'api-ver': 'application/json', brand: 'Windows', net: 'wifi',
      // 注意：不要自己带 Accept-Encoding: gzip —— OkHttp 只有在它自己加这个头时才会透明解压，
      // 我们自己写了它就会把 gzip 原始字节返回给 JS，JSON 解析失败就变成 "code missing"。
      devid: dev, qimei36: dev, 'Content-Type': 'application/json'
    };
  }

  function logLine(level, message) {
    try {
      if (root.MRRuntimeLog && typeof root.MRRuntimeLog.push === 'function') {
        root.MRRuntimeLog.push(level, [message], 'bodian');
      }
    } catch (e) { /* 忽略 */ }
    try {
      if (!(root.MRRuntimeLog && typeof root.MRRuntimeLog.push === 'function')) {
        console[level === 'error' ? 'error' : (level === 'warn' ? 'warn' : 'log')]('[Bodian] ' + message);
      }
    } catch (e) { /* 忽略 */ }
  }

  function BodianError(code, message, upstreamCode) {
    var err = new Error(message);
    err.name = 'BodianError';
    err.code = code;
    err.upstreamCode = upstreamCode;
    return err;
  }

  async function httpRequest(url, options) {
    options = options || {};
    var headers = options.headers || {};
    var bridge = root.__mrBridgeRequest;
    if (typeof bridge === 'function') {
      var answer = await bridge({
        url: url, method: options.method || 'GET', headers: headers,
        body: options.body, timeoutMs: options.timeoutMs || 15000, omitCookies: true
      });
      return { status: answer && answer.status, body: String(answer && answer.body || '') };
    }
    var response = await root.fetch(url, { method: options.method || 'GET', headers: headers, body: options.body });
    return { status: response.status, body: await response.text() };
  }

  async function call(path, options) {
    options = options || {};
    if (!String(path).startsWith('/') || String(path).includes('..')) throw BodianError('unsupported', 'Invalid Bodian path');
    var session = options.anonymous ? null : bodianSession();
    var query = Object.assign({}, options.params || {}, {
      uid: String((session && session.uid) || '-1'),
      token: (session && session.token) || ''
    });
    var bodyText = options.body === undefined || options.body === null ? '' : JSON.stringify(options.body);
    if (options.signed) {
      query.timestamp = String(Date.now());
      // 波点取流是 GET + query 签名；只有写接口（POST）才把 body 算进签名
      query.sign = signQuery(path, query, String(options.method || 'GET').toUpperCase() === 'POST' ? bodyText : '');
    }
    var search = new URLSearchParams();
    Object.keys(query).forEach(function (key) {
      if (query[key] !== undefined && query[key] !== null) search.set(key, String(query[key]));
    });
    var url = (options.lyric ? LYRIC_ORIGIN : API_ORIGIN) + path + (search.toString() ? ('?' + search.toString()) : '');
    var headers = baseHeaders();
    if (session) { headers.uid = String(session.uid); headers.token = session.token; }
    var startedAt = Date.now();
    var response;
    try {
      response = await httpRequest(url, {
        method: options.method || 'GET', headers: headers, body: bodyText || undefined, timeoutMs: options.timeoutMs
      });
    } catch (networkError) {
      logLine('error', (options.method || 'GET') + ' ' + path + ' ✗ ' + (networkError && networkError.message || networkError));
      throw networkError;
    }
    logLine('debug', (options.method || 'GET') + ' ' + path + ' → HTTP ' + response.status + ' (' + (Date.now() - startedAt) + 'ms)');
    if (response.status === 401) throw BodianError('auth-required', '波点需要登录', 401);
    if (response.status !== 200) throw BodianError('network', '波点 HTTP ' + response.status, response.status);
    var payload = null;
    try { payload = JSON.parse(response.body); } catch (e) { payload = null; }
    if (!payload) {
      logLine('error', path + ' 返回的不是 JSON，前 300 字符：' + String(response.body || '').slice(0, 300));
      throw BodianError('invalid-response', '波点返回内容无法解析（可能是压缩或网关错误）');
    }
    if (Number(payload.code) !== 200) {
      logLine('warn', path + ' 业务码 ' + payload.code + ' msg=' + (payload.msg || payload.message || ''));
      var code = Number(payload && payload.code);
      if (code === 407) {
        var message = (payload && (payload.msg || payload.message)) || '';
        throw BodianError('region-restricted', message && String(message).trim() ? String(message).trim() : '由于版权保护，该资源仅限中国大陆地区使用', code);
      }
      if (code === 11012 || code === -101) throw BodianError('auth-required', '波点登录已失效，请重新登录', code);
      if (code === 20018) throw BodianError('not-playable', '没有解锁付费歌曲（需要会员/购买）', code);
      if (code === 11027) throw BodianError('pending', '已扫码，请在手机上确认', code);
      throw BodianError('invalid-response', '波点拒绝了请求 (code ' + (payload ? payload.code : 'missing') + (payload && payload.msg ? '：' + payload.msg : '') + ')', code);
    }
    return payload;
  }

  // ---------- 数据映射 ----------

  function normalizeSong(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var id = String(raw.id != null ? raw.id : (raw.musicId != null ? raw.musicId : (raw.rid != null ? raw.rid : '')));
    if (!/^\d{1,20}$/.test(id) || /^0+$/.test(id)) return null;
    var name = String(raw.name != null ? raw.name : (raw.musicName != null ? raw.musicName : (raw.songName || ''))).trim();
    if (!name) return null;
    var artists = Array.isArray(raw.artists) ? raw.artists : [];
    var artistName = String(raw.artist || '').trim();
    var artistId = String(raw.artistId || '');
    if (!artistName && artists.length) {
      artistName = artists.map(function (item) { return String(item && item.name || ''); }).filter(Boolean).join(' / ');
      artistId = String((artists[0] && artists[0].id) || '');
    }
    var pic = String(raw.albumPic || raw.albumPic120 || raw.pic || raw.img || raw.cover || '');
    var durationSec = Number(raw.duration) || 0;
    var payInfo = (raw.payInfo && typeof raw.payInfo === 'object') ? raw.payInfo : {};
    var feeType = (payInfo.feeType && typeof payInfo.feeType === 'object') ? payInfo.feeType : {};
    var listenFragment = Number(payInfo.listen_fragment) === 1;
    var vipFlag = String(feeType.vip || '') === '1' || String(feeType.song || '') === '1';
    var cannotPlay = Number(payInfo.cannotOnlinePlay) === 1;
    return {
      provider: 'bodian', source: 'bodian', type: 'bodian',
      id: id, mediaId: id, name: name, title: name,
      artist: artistName, artistId: artistId,
      artists: artists.length ? artists.map(function (a) { return { id: String(a && a.id || ''), name: String(a && a.name || '') }; }).filter(function (a) { return a.name; }) : (artistName ? [{ id: artistId, name: artistName }] : []),
      album: String(raw.album || raw.albumName || ''), albumId: String(raw.albumId || ''),
      cover: pic, pic: pic,
      // MR 的时长按毫秒处理（qishui 也是毫秒）
      duration: Math.max(0, Math.round(durationSec * 1000)),
      vipRequired: listenFragment || vipFlag,
      trialOnly: listenFragment,
      playable: !cannotPlay,
      freeSign: ''
    };
  }

  function normalizeList(rawList) {
    var list = Array.isArray(rawList) ? rawList : [];
    var out = [];
    for (var i = 0; i < list.length; i += 1) {
      var song = normalizeSong(list[i]);
      if (song) out.push(song);
    }
    return out;
  }

  // ---------- 搜索 ----------

  async function search(params) {
    var query = String(params.keywords || params.query || '').trim();
    var limit = Math.max(1, Math.min(Number(params.limit) || 30, 100));
    var offset = Math.max(0, Number(params.offset) || 0);
    if (!query || query.length > 500) return { provider: 'bodian', songs: [], hasMore: false, nextOffset: offset };
    var pn = Math.floor(offset / limit);
    var payload = await call('/api/search/music/list', { params: { keyword: query, correct: 1, pn: pn, rn: limit } });
    var data = (payload && payload.data) || {};
    var rawList = Array.isArray(data.resultList) ? data.resultList : [];
    var songs = normalizeList(rawList);
    var total = Number(data.total);
    var knownTotal = Number.isFinite(total) && total >= 0;
    var pageEnd = (pn + 1) * limit;
    var hasMore = knownTotal ? pageEnd < total : rawList.length === limit;
    return {
      provider: 'bodian', keyword: query, offset: offset, limit: limit,
      songs: songs, hasMore: hasMore, nextOffset: hasMore ? pageEnd : offset + songs.length
    };
  }

  // ---------- 播放 ----------

  // 波点 CDN 不下发 CORS 头，而播放器用的是 crossOrigin="anonymous" 的 <audio>，
  // 直连会报 "no supported source"。原生 LocalAudioServer 专门为此把 kuwo 音频转到
  // http://127.0.0.1:<port>/remote-audio/<token>（同源 + Range 转发），这里调它。
  async function proxyAudioUrl(url) {
    var raw = String(url || '').trim();
    if (!/^https?:\/\//i.test(raw)) return raw;
    try {
      var cap = root.Capacitor;
      if (!cap || typeof cap.isNativePlatform !== 'function' || !cap.isNativePlatform()) return raw;
      var plugin = (cap.Plugins || {}).FoliaNative;
      if (!plugin || typeof plugin.registerRemoteAudio !== 'function') return raw;
      var answer = await plugin.registerRemoteAudio({ url: raw });
      if (answer && answer.url) {
        logLine('info', '音频转本地代理：' + answer.url);
        return String(answer.url);
      }
    } catch (error) {
      logLine('warn', '本地音频代理失败，退回直连：' + (error && error.message || error));
    }
    return raw;
  }

  function validateAudioUrl(value) {
    try {
      var url = new URL(String(value || ''));
      if (['http:', 'https:'].indexOf(url.protocol) < 0 || url.username || url.password) throw new Error();
      return url.toString().replace(/^http:\/\//, 'https://');
    } catch (e) {
      throw BodianError('invalid-response', '波点返回了无效的音频地址');
    }
  }

  async function songUrlRaw(id, quality) {
    var mediaId = String(id || '').trim();
    if (!/^\d{1,20}$/.test(mediaId) || /^0+$/.test(mediaId)) {
      return { provider: 'bodian', url: '', playable: false, error: 'MISSING_ID', message: '缺少波点歌曲 id' };
    }
    quality = quality === 'hires' ? 'lossless' : (quality === 'exhigh' ? 'high' : (quality || 'high'));
    if (!QUALITIES[quality]) quality = 'high';
    var freeSign = '';
    var right = null;
    try {
      right = (await call('/api/play/music/v2/checkRight', { params: { musicId: mediaId, freeSign: freeSign }, signed: true })).data;
    } catch (error) {
      return {
        provider: 'bodian', url: '', playable: false, error: 'BODIAN_CHECK_RIGHT_FAILED',
        reason: error.code === 'auth-required' ? 'login_required' : (error.code === 'region-restricted' ? 'region_restricted' : 'unavailable'),
        message: error.message
      };
    }
    // status 3 = 只有试听权限，直接给试听片段
    if (Number(right && right.status) === 3) {
      var preview = right.audition;
      if (!preview || !isFinite(Number(preview.start)) || !isFinite(Number(preview.end))) {
        return { provider: 'bodian', url: '', playable: false, reason: 'not_playable', message: '波点试听不可用' };
      }
      try {
        return {
          provider: 'bodian', url: await proxyAudioUrl(validateAudioUrl(preview.https || preview.url)), playable: true,
          trial: true, trialInfo: { startTime: Number(preview.start), endTime: Number(preview.end) },
          level: 'standard', quality: '标准(试听)', loggedIn: !!bodianSession(),
          message: '非会员账号播放试听片段'
        };
      } catch (error) {
        return { provider: 'bodian', url: '', playable: false, reason: 'not_playable', message: error.message };
      }
    }
    if (Number(right && right.status) === 7) {
      return {
        provider: 'bodian', url: '', playable: false,
        reason: bodianSession() ? 'vip_required' : 'login_required',
        message: bodianSession() ? '波点歌曲需要额外播放权限' : '波点需要先登录'
      };
    }
    if (!right || right.status === undefined) {
      return {
        provider: 'bodian', url: '', playable: false,
        reason: bodianSession() ? 'not_playable' : 'login_required',
        message: '波点播放权限不可用'
      };
    }
    var format = QUALITIES[quality];
    var body = { devId: deviceId(), musicId: Number(mediaId), format: format.format, br: format.br, freeSign: freeSign };
    var audio = null;
    try {
      audio = (await call('/api/play/music/v2/audioUrl', {
        params: Object.assign({}, body, { musicId: mediaId }), signed: true, method: 'GET'
      })).data;
    } catch (error) {
      return {
        provider: 'bodian', url: '', playable: false, error: 'BODIAN_AUDIO_FAILED',
        reason: error.code === 'auth-required' ? 'login_required' : (error.code === 'region-restricted' ? 'region_restricted' : 'unavailable'),
        message: error.message
      };
    }
    var audioUrl = audio && (audio.audioHttpsUrl || audio.audioUrl);
    if (!audioUrl) return { provider: 'bodian', url: '', playable: false, reason: 'not_playable', message: '波点未返回播放地址' };
    try {
      return {
        provider: 'bodian', url: await proxyAudioUrl(validateAudioUrl(audioUrl)), playable: true, trial: false,
        level: quality,
        quality: quality === 'lossless' ? '无损 FLAC' : (quality === 'high' ? '320k MP3' : '128k MP3'),
        loggedIn: !!bodianSession()
      };
    } catch (error) {
      return { provider: 'bodian', url: '', playable: false, reason: 'not_playable', message: error.message };
    }
  }

  async function songUrl(id, quality) {
    var result = await songUrlRaw(id, quality);
    if (result.playable) return result;
    // 登录态失效：清掉脏会话，按匿名再试一次（至少能拿试听）
    if (result.reason === 'login_required' && bodianSession()) {
      saveBodianSession(null);
      return songUrlRaw(id, quality);
    }
    return result;
  }

  // ---------- 歌词 ----------

  // 波点歌词是 lrcx：<start,dur> 的逐字标记 + [kuwo:xx] 头，需要先解码
  function decodeWordTiming(content) {
    var tag = content.match(/\[kuwo:([0-7]+)(?:\]\[.*)?\]/);
    var factors = tag ? parseInt(tag[1], 8) : 11;
    var startFactor = Math.trunc(factors / 10);
    var durationFactor = factors % 10;
    if (!startFactor || !durationFactor) return null;
    return content.replace(/\[kuwo:[^\]]*\]/g, '').replace(/<(-?\d+),(-?\d+)>/g, function (_m, left, right) {
      var a = Number(left), b = Number(right);
      var start = Math.trunc(Math.abs((a + b) / (2 * startFactor)));
      var duration = Math.trunc(Math.abs((a - b) / (2 * durationFactor)));
      return '<' + start + ',' + duration + '>';
    });
  }

  function decodeLyrics(content) {
    return {
      mainText: content.replace(/<-?\d+,-?\d+>/g, '').replace(/\[kuwo:[^\]]*\]/g, ''),
      wordByWordText: /<-?\d+,-?\d+>/.test(content) ? decodeWordTiming(content) : null
    };
  }

  async function lyric(id) {
    var mediaId = String(id || '').trim();
    if (!/^\d{1,20}$/.test(mediaId)) return { provider: 'bodian', lyric: '', message: '缺少歌曲 id' };
    var query = 'type=lyric&req=2&lrcx=1&rid=' + mediaId + '&songname=&artist=&corp=kuwo&fromchannel=bodian';
    var result = await call('/mobi.s', {
      lyric: true,
      params: { f: 'bodian', q: btoa(query) }
    });
    var content = result && result.data && typeof result.data.content === 'string' ? result.data.content : '';
    if (!content) return { provider: 'bodian', lyric: '' };
    var raw = '';
    try {
      var binary = atob(content);
      var bytes = new Uint8Array(binary.length);
      for (var i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
      raw = new TextDecoder('utf-8').decode(bytes);
    } catch (e) { raw = ''; }
    var decoded = decodeLyrics(raw);
    return {
      provider: 'bodian',
      lyric: decoded.mainText || '',
      wordByWordLyric: decoded.wordByWordText || '',
      tlyric: ''
    };
  }

  // ---------- 账号 / 歌单 ----------

  function statusPayload() {
    var session = bodianSession();
    if (!session) {
      return {
        provider: 'bodian', configured: false, loggedIn: false, nickname: '波点音乐', avatar: '',
        userId: '', vipType: 0, vipLevel: 'none', isVip: false, isSvip: false, vipLabel: '无VIP',
        playbackKeyReady: false, playbackMode: 'direct',
        capabilities: { search: true, playableUrl: true }, cookieReady: false, tokenConfigured: false
      };
    }
    var user = sessionUser(session);
    if (user.id && String(user.id) !== String(session.uid)) {
      saveBodianSession(null);
      return {
        provider: 'bodian', configured: false, loggedIn: false, nickname: '波点音乐', avatar: '',
        userId: '', vipType: 0, vipLevel: 'none', isVip: false, isSvip: false,
        playbackKeyReady: false, playbackMode: 'direct', capabilities: { search: true, playableUrl: true },
        cookieReady: false, tokenConfigured: false
      };
    }
    return {
      provider: 'bodian', configured: true, loggedIn: true,
      nickname: user.nickname || ('波点 ' + session.uid),
      avatar: user.avatarUrl || '', userId: String(session.uid),
      vipType: 0, vipLevel: 'none', isVip: false, isSvip: false, vipLabel: '无VIP',
      playbackKeyReady: true, playbackMode: 'direct',
      capabilities: { search: true, playableUrl: true }, cookieReady: true, tokenConfigured: true
    };
  }

  async function userPlaylists() {
    var session = bodianSession();
    if (!session) return { provider: 'bodian', loggedIn: false, playlists: [], message: '波点需要先登录' };
    var uid = String(session.uid);
    var user = { userId: uid, fromUid: uid };
    var owned = null, liked = null;
    try {
      var results = await Promise.all([
        call('/api/service/playlist/userCreate', { params: user }).catch(function (e) { if (e.code === 'auth-required') throw e; return null; }),
        call('/api/service/playlist/fond', { params: user }).catch(function (e) { if (e.code === 'auth-required') throw e; return null; })
      ]);
      owned = results[0]; liked = results[1];
    } catch (err) {
      if (err && err.code === 'auth-required') {
        saveBodianSession(null);
        return { provider: 'bodian', loggedIn: false, playlists: [], error: '波点登录已失效，请重新登录' };
      }
      throw err;
    }
    function mapPlaylist(item, fallbackSource) {
      if (!item || typeof item !== 'object') return null;
      var id = String(item.id != null ? item.id : (item.playListId != null ? item.playListId : (item.playlistId != null ? item.playlistId : '')));
      if (!id) return null;
      var pic = String(item.img || item.pic || item.cover || '');
      return {
        provider: 'bodian', source: 'bodian', id: id,
        name: String(item.name || item.title || '波点歌单'),
        cover: pic, pic: pic,
        trackCount: Number(item.total || item.musicCount || item.songCount || 0) || 0,
        creator: String(item.creatorName || ''),
        sourceType: Number(item.sourceType != null ? item.sourceType : fallbackSource),
        liked: fallbackSource === 5
      };
    }
    var playlists = [];
    var ownedList = (owned && owned.data && owned.data.playLists) || [];
    for (var i = 0; i < ownedList.length; i += 1) {
      var mapped = mapPlaylist(ownedList[i], 4);
      if (mapped) playlists.push(mapped);
    }
    var likedData = (liked && liked.data) || {};
    if (likedData && likedData.id) {
      var likedMapped = mapPlaylist(likedData, 5);
      if (likedMapped) playlists.push(likedMapped);
    }
    logLine('info', 'user/playlists → ' + playlists.length + ' 个'
      + (playlists.length ? ('：' + playlists.map(function (pl) { return pl.name + '(' + pl.id + '/src' + pl.sourceType + ')'; }).join('、') ) : '（空）'));
    return {
      provider: 'bodian', loggedIn: true, userId: uid,
      nickname: sessionUser(session).nickname, playlists: playlists, total: playlists.length
    };
  }

  async function playlistTracks(params) {
    var listId = String(params.id || '').trim();
    if (!/^\d{1,20}$/.test(listId)) {
      return { provider: 'bodian', tracks: [], songs: [], total: 0, error: 'MISSING_PLAYLIST_ID' };
    }
    var limit = Math.max(1, Math.min(Number(params.limit) || 50, 100));
    var offset = Math.max(0, Number(params.offset) || 0);
    var preferred = Number(params.source) === 5 ? 5 : (Number(params.source) === 4 ? 4 : null);
    var order = preferred ? [preferred, preferred === 4 ? 5 : 4] : [4, 5];
    var lastData = null, lastError = null;
    for (var i = 0; i < order.length; i += 1) {
      try {
        var pn = Math.floor(offset / limit) + 1;
        var payload = await call('/api/service/playlist/' + listId + '/musicList', {
          params: { source: order[i], pn: pn, rn: limit }
        });
        var data = (payload && payload.data) || {};
        var rawList = Array.isArray(data.list) ? data.list : (Array.isArray(data.resultList) ? data.resultList : []);
        lastData = { source: order[i], list: rawList, total: Number(data.total) || rawList.length };
        if (rawList.length) break;
      } catch (error) {
        lastError = error;
      }
    }
    if (!lastData && lastError) {
      if (lastError.code === 'auth-required') {
        saveBodianSession(null);
        return { provider: 'bodian', tracks: [], songs: [], error: '波点登录已失效，请重新登录' };
      }
      throw lastError;
    }
    var songs = normalizeList(lastData ? lastData.list : []);
    return {
      provider: 'bodian', id: listId,
      songs: songs, tracks: songs,
      total: lastData ? lastData.total : songs.length,
      hasMore: songs.length === limit,
      nextOffset: offset + songs.length,
      source: lastData ? lastData.source : 4
    };
  }

  var likedListCache = { at: 0, ids: null, fondId: '' };

  async function likedSongs(params) {
    var session = bodianSession();
    if (!session) return { provider: 'bodian', songs: [], hasMore: false };
    var limit = Math.max(1, Math.min(Number(params.limit) || 50, 100));
    var offset = Math.max(0, Number(params.offset) || 0);
    var fond = await call('/api/service/playlist/fond', { params: { userId: String(session.uid) } });
    var liked = (fond && fond.data) || {};
    if (!liked.id) return { provider: 'bodian', songs: [], hasMore: false, nextOffset: offset };
    return playlistTracks({ id: liked.id, limit: limit, offset: offset, source: 5 });
  }

  async function feed(params) {
    var limit = Math.max(1, Math.min(Number(params.limit) || 12, 50));
    var payload = await call('/api/service/music/recommendList', { params: {} });
    var data = payload && payload.data;
    var list = Array.isArray(data) ? data
      : (Array.isArray(data && data.list) ? data.list
        : (Array.isArray(data && data.musicList) ? data.musicList
          : (Array.isArray(data && data.resultList) ? data.resultList : [])));
    return { provider: 'bodian', songs: normalizeList(list).slice(0, limit), hasMore: false };
  }

  async function likedIdSet(force) {
    var now = Date.now();
    if (!force && likedListCache.ids && now - likedListCache.at < 60000) return likedListCache.ids;
    var set = Object.create(null);
    var first = await likedSongs({ limit: 100, offset: 0 });
    (first.songs || []).forEach(function (song) { set[String(song.id)] = true; });
    if (first.hasMore) {
      var second = await likedSongs({ limit: 100, offset: 100 });
      (second.songs || []).forEach(function (song) { set[String(song.id)] = true; });
    }
    likedListCache = { at: now, ids: set, fondId: '' };
    return set;
  }

  async function likeCheck(params) {
    var ids = String(params.ids || params.id || '').split(',').map(function (v) { return String(v).trim(); }).filter(Boolean);
    var result = { provider: 'bodian', liked: {}, complete: true };
    var session = bodianSession();
    if (!ids.length || !session) {
      ids.forEach(function (id) { result.liked[id] = false; });
      return result;
    }
    try {
      var set = await likedIdSet(false);
      ids.forEach(function (id) { result.liked[id] = !!set[id]; });
    } catch (e) {
      ids.forEach(function (id) { if (result.liked[id] == null) result.liked[id] = false; });
      result.complete = false;
    }
    return result;
  }

  async function like(params) {
    var session = bodianSession();
    if (!session) throw BodianError('auth-required', '需要先登录波点音乐');
    var id = Number(params.id);
    if (!Number.isSafeInteger(id)) throw BodianError('invalid-response', '无效的歌曲 id');
    var desired = params.like === true;
    var fond = await call('/api/service/playlist/fond', { params: { userId: String(session.uid) } });
    var liked = (fond && fond.data) || {};
    if (!liked.id) throw BodianError('invalid-response', '找不到「我喜欢」歌单');
    await call('/api/service/playlist/music' + (desired ? '' : '/delete'), {
      method: 'POST', signed: true,
      body: { playListId: Number(liked.id), musicIdList: [id] }
    });
    likedListCache = { at: 0, ids: null, fondId: '' };
    return { provider: 'bodian', ok: true, liked: desired };
  }

  function logout() {
    saveBodianSession(null);
    return { provider: 'bodian', ok: true, loggedIn: false };
  }

  // ---------- 路由 ----------

  async function router(input) {
    var path = String((input && input.path) || '');
    var query = (input && input.query) || {};
    var body = (input && input.body) || {};
    var pick = function (key) { return query[key] != null ? query[key] : body[key]; };
    if (path === '/api/bodian/status') return statusPayload();
    if (path === '/api/bodian/logout') return logout();
    if (path === '/api/bodian/search') return search({ keywords: pick('keywords') || pick('query') || pick('q'), limit: pick('limit'), offset: pick('offset') });
    if (path === '/api/bodian/song/url') return songUrl(String(pick('id') || ''), pick('quality'));
    if (path === '/api/bodian/lyric') return lyric(String(pick('id') || ''));
    if (path === '/api/bodian/user/playlists') return userPlaylists();
    if (path === '/api/bodian/playlist/tracks') return playlistTracks({ id: pick('id'), limit: pick('limit'), offset: pick('offset'), source: pick('source') });
    if (path === '/api/bodian/playlist/detail') return playlistTracks({ id: pick('id'), limit: 1, offset: 0 });
    if (path === '/api/bodian/liked/songs') return likedSongs({ limit: pick('limit'), offset: pick('offset') });
    if (path === '/api/bodian/feed') return feed({ limit: pick('limit') });
    if (path === '/api/bodian/song/like/check') return likeCheck({ ids: pick('ids'), id: pick('id') });
    if (path === '/api/bodian/song/like') {
      var desiredLike = pick('like');
      if (desiredLike == null) desiredLike = pick('liked');
      return like({ id: pick('id'), like: desiredLike === true || desiredLike === 'true' || desiredLike === '1' });
    }
    throw new Error('未知的波点接口：' + path);
  }

  function reportError(path, err) {
    var message = (err && err.message) ? String(err.message) : String(err);
    try { console.warn('[Bodian]', path, message); } catch (e) { /* 忽略 */ }
    try {
      if (typeof root.showToast === 'function') root.showToast('波点 ' + String(path).replace('/api/bodian/', '') + ' 失败：' + message);
    } catch (e) { /* 忽略 */ }
    return { provider: 'bodian', ok: false, error: message, message: message };
  }

  async function routerSafe(input) {
    try { return await router(input); } catch (err) { return reportError(String((input && input.path) || ''), err); }
  }

  root.__mrBodianRouter = routerSafe;
  root.__mrBodianApi = {
    router: routerSafe, md5: md5, signQuery: signQuery, session: bodianSession,
    saveSession: saveBodianSession, normalizeSong: normalizeSong, statusPayload: statusPayload
  };
})(typeof window !== 'undefined' ? window : globalThis);
