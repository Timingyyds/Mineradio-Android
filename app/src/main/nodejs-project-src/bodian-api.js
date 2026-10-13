'use strict';

// 波点音乐（kuwo 波点）协议移植自 folia bodian.js（bodian-music-api@0.1.2 的 WebView 适配版）
// API: https://bd-api.kuwo.cn   歌词: https://mlyric.kuwo.cn
// 会话: uid + token（非 cookie），持久化为 JSON 文件
// 业务码: 200=成功, 407=仅限中国大陆, 11012=需要登录, 20018=不可播放, 11027=已扫码未确认

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const zlib = require('zlib');

const BODIAN_API_ORIGIN = 'https://bd-api.kuwo.cn';
const BODIAN_LYRIC_ORIGIN = 'https://mlyric.kuwo.cn';
const BODIAN_QR_TTL_MS = 5 * 60 * 1000;
const BODIAN_QUALITIES = {
  standard: { format: 'mp3', br: '128kmp3' },
  high: { format: 'mp3', br: '320kmp3' },
  lossless: { format: 'flac', br: '2000kflac' },
};

// ---- 会话持久化 ----
let bodianSessionFile = '';
let bodianSession = null; // { uid, token, user: { id, nickname, avatarUrl } }
let bodianDeviceId = '';

function setBodianSessionFile(file) {
  bodianSessionFile = String(file || '');
  if (bodianSessionFile && !bodianDeviceId) {
    const deviceFile = bodianSessionFile.replace(/\.json$/, '') + '.device';
    try {
      const existing = fs.readFileSync(deviceFile, 'utf8').trim();
      if (/^[a-f0-9]{32}$/i.test(existing)) bodianDeviceId = existing.toLowerCase();
    } catch (_) {}
    if (!bodianDeviceId) {
      bodianDeviceId = crypto.randomBytes(16).toString('hex');
      try { fs.writeFileSync(deviceFile, bodianDeviceId); } catch (_) {}
    }
  }
  if (bodianSessionFile && !bodianSession) {
    try {
      const raw = JSON.parse(fs.readFileSync(bodianSessionFile, 'utf8'));
      if (raw && raw.uid && raw.token) bodianSession = raw;
    } catch (_) {}
  }
}

function saveBodianSession() {
  try {
    if (bodianSessionFile) {
      if (bodianSession) fs.writeFileSync(bodianSessionFile, JSON.stringify(bodianSession));
      else { try { fs.unlinkSync(bodianSessionFile); } catch (_) {} }
    }
  } catch (_) {}
}

function getBodianSession() { return bodianSession; }

function clearBodianSession() {
  bodianSession = null;
  saveBodianSession();
}

class BodianError extends Error {
  constructor(code, message, upstreamCode) {
    super(message);
    this.name = 'BodianError';
    this.code = code;
    this.upstreamCode = upstreamCode;
  }
}

// ---- 请求与签名 ----
function bodianMd5(value) {
  return crypto.createHash('md5').update(String(value || ''), 'utf8').digest('hex');
}

/** folia signQuery: seed = 'kuwotest' + query 字母数字字符排序拼接；有 body 再加 md5(body+'kuwotest')；sign = md5(seed+path) */
function signQuery(path, params, body) {
  const query = new URLSearchParams(params).toString();
  let seed = `kuwotest${Array.from(query).filter(ch => /[a-z0-9]/i.test(ch)).sort().join('')}`;
  if (body) seed += bodianMd5(`${body}kuwotest`);
  return bodianMd5(`${seed}${path}`);
}

function bodianBaseHeaders(deviceId) {
  return {
    'User-Agent': 'Dart/3.3 (dart:io)',
    plat: 'win',
    channel: 'W1',
    ver: '1.1.7',
    svrver: '13',
    'api-ver': 'application/json',
    brand: 'Windows',
    net: 'wifi',
    devid: deviceId,
    qimei36: deviceId,
    'Accept-Encoding': 'gzip',
  };
}

function bodianHttpRequest(targetUrl, opts) {
  opts = opts || {};
  return new Promise((resolve, reject) => {
    const u = new URL(targetUrl);
    const lib = u.protocol === 'https:' ? https : http;
    const req = lib.request(u, {
      method: opts.method || 'GET',
      headers: opts.headers || {},
      timeout: opts.timeoutMs || 15000,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        let buf = Buffer.concat(chunks);
        const encoding = String(res.headers['content-encoding'] || '').toLowerCase();
        try {
          if (encoding.includes('gzip')) buf = zlib.gunzipSync(buf);
          else if (encoding.includes('deflate')) buf = zlib.inflateSync(buf);
          else if (encoding.includes('br')) buf = zlib.brotliDecompressSync(buf);
        } catch (_) {}
        resolve({ status: res.statusCode, headers: res.headers, body: buf.toString('utf8') });
      });
    });
    req.on('error', reject);
    req.on('timeout', () => req.destroy(new Error('timeout')));
    if (opts.body) req.write(opts.body);
    req.end();
  });
}

/**
 * 波点 API 统一调用（对应 folia client.call）。
 * opts: { params, body, method, signed, anonymous, lyric, timeoutMs }
 */
async function bodianCall(path, opts) {
  opts = opts || {};
  if (!String(path).startsWith('/') || String(path).includes('..')) {
    throw new BodianError('unsupported', 'Invalid Bodian path');
  }
  const session = opts.anonymous ? null : bodianSession;
  const query = Object.assign({}, opts.params || {}, {
    uid: String((session && session.uid) || '-1'),
    token: (session && session.token) || '',
  });
  const bodyText = opts.body === undefined || opts.body == null ? '' : JSON.stringify(opts.body);
  if (opts.signed) {
    query.timestamp = String(Date.now());
    query.sign = signQuery(path, query, bodyText);
  }
  const url = new URL(path, opts.lyric ? BODIAN_LYRIC_ORIGIN : BODIAN_API_ORIGIN);
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null) url.searchParams.set(key, String(value));
  });
  const headers = Object.assign({}, bodianBaseHeaders(bodianDeviceId), {
    'Content-Type': 'application/json',
  });
  if (session) {
    headers.uid = String(session.uid);
    headers.token = session.token;
  }
  const resp = await bodianHttpRequest(url.toString(), {
    method: opts.method || 'GET',
    headers,
    body: bodyText || undefined,
    timeoutMs: opts.timeoutMs,
  });
  if (resp.status === 401) throw new BodianError('auth-required', '波点需要登录', 401);
  if (resp.status !== 200) throw new BodianError('network', `波点 HTTP ${resp.status}`, resp.status);
  let payload = null;
  try { payload = JSON.parse(resp.body); } catch (_) {}
  if (!payload || Number(payload.code) !== 200) {
    const code = Number(payload && payload.code);
    if (code === 407) {
      const message = (payload && (payload.msg || payload.message)) || '';
      throw new BodianError('region-restricted',
        typeof message === 'string' && message.trim() ? message.trim() : '由于版权保护，该资源仅限中国大陆地区使用', code);
    }
    throw new BodianError(
      code === 11012 ? 'auth-required' : code === 20018 ? 'not-playable' : 'invalid-response',
      `波点拒绝了请求 (code ${payload ? payload.code : 'missing'})`,
      payload ? payload.code : undefined,
    );
  }
  return payload;
}

// ---- 数据映射 ----
function normalizeBodianSong(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const id = String(raw.id ?? raw.musicId ?? raw.rid ?? '');
  if (!/^\d{1,20}$/.test(id) || /^0+$/.test(id)) return null;
  const name = String(raw.name ?? raw.musicName ?? raw.songName ?? '').trim();
  if (!name) return null;
  const artists = Array.isArray(raw.artists) ? raw.artists : [];
  let artist = String(raw.artist ?? '').trim();
  let artistId = String(raw.artistId ?? '');
  if (!artist && artists.length) {
    artist = artists.map(item => String(item.name ?? '')).filter(Boolean).join(' / ');
    artistId = String((artists[0] && artists[0].id) ?? '');
  }
  const albumId = String(raw.albumId ?? '');
  const albumName = String(raw.album ?? raw.albumName ?? '').trim();
  const pic = String(raw.albumPic ?? raw.albumPic120 ?? raw.pic ?? raw.img ?? raw.cover ?? raw.artistPic ?? '');
  const durationRaw = Number(raw.duration ?? 0) || 0;
  const payInfo = (raw.payInfo && typeof raw.payInfo === 'object') ? raw.payInfo : {};
  const feeType = (payInfo.feeType && typeof payInfo.feeType === 'object') ? payInfo.feeType : {};
  // listen_fragment=1 表示仅可试听片段（refrain_start/end 为试听区间毫秒）
  const listenFragment = Number(payInfo.listen_fragment) === 1;
  const vipFlag = String(feeType.vip ?? '') === '1' || String(feeType.song ?? '') === '1';
  const cannotPlay = Number(payInfo.cannotOnlinePlay) === 1;
  return {
    provider: 'bodian',
    source: 'bodian',
    type: 'bodian',
    id,
    mediaId: id,
    name,
    title: name,
    artist,
    artistId,
    album: albumName,
    albumId,
    pic,
    cover: pic,
    duration: durationRaw,
    vipRequired: listenFragment || vipFlag,
    trialOnly: listenFragment,
    playable: !cannotPlay,
    freeSign: '',
    raw,
  };
}

function normalizeBodianList(rawList) {
  const list = Array.isArray(rawList) ? rawList : [];
  return list.map(normalizeBodianSong).filter(Boolean);
}

// ---- 登录（扫码）----
const bodianQrSessions = new Map(); // key -> { expiresAt }

function bodianCurrentQr(key) {
  const session = bodianQrSessions.get(key);
  if (!session || session.expiresAt <= Date.now()) {
    bodianQrSessions.delete(key);
    return null;
  }
  return session;
}

async function bodianLoginQrKey() {
  for (const key of Array.from(bodianQrSessions.keys())) bodianCurrentQr(key);
  if (bodianQrSessions.size >= 5) throw new BodianError('unavailable', '波点待登录二维码过多');
  const payload = await bodianCall('/api/ucenter/login/qrCode', { anonymous: true, signed: true });
  const qrCode = payload && payload.data && typeof payload.data.qrCode === 'string' ? payload.data.qrCode : '';
  if (!qrCode) throw new BodianError('invalid-response', '波点二维码 key 缺失');
  bodianQrSessions.set(qrCode, { expiresAt: Date.now() + BODIAN_QR_TTL_MS });
  return { key: qrCode };
}

function bodianLoginQrUrl(key) {
  return `https://bodian-oia.kuwo.cn/bodian/download.html?pageName=login_pc&pt=3&id=${encodeURIComponent(key)}`;
}

async function bodianLoginQrCheck(key, buildQrImage) {
  key = String(key || '').trim();
  if (!bodianCurrentQr(key)) return { status: 'expired', code: 800, message: '二维码已过期，请刷新' };
  const payload = await bodianCall('/api/ucenter/login/qrCodeStatus', {
    params: { qrCode: key }, anonymous: true, signed: true,
  });
  if (!bodianCurrentQr(key)) return { status: 'expired', code: 800, message: '二维码已过期，请刷新' };
  const data = (payload && payload.data) || {};
  const status = Number(data.status);
  if (status === 1) return { status: 'wait', code: 801, message: '请用波点音乐 App 扫码' };
  if (status === 2) {
    bodianQrSessions.delete(key);
    return { status: 'expired', code: 800, message: '二维码已过期，请刷新' };
  }
  if (status !== 3) {
    throw new BodianError('invalid-response', '未知的波点二维码状态');
  }
  // status 3：可能是“已扫码未确认”(11027) 或已确认可登录
  let login = null;
  try {
    login = (await bodianCall('/api/ucenter/users/login', {
      method: 'POST',
      body: { authType: 10, qrCode: key },
      signed: true,
      anonymous: true,
    })).data;
  } catch (error) {
    if (error && error.upstreamCode === 11027) {
      return { status: 'scanned', code: 802, message: '已扫码，请在手机上确认' };
    }
    throw error;
  }
  if (!bodianCurrentQr(key)) return { status: 'expired', code: 800, message: '二维码已过期，请刷新' };
  const values = [login && login.id, login && login.uid, login && login.userInfo && login.userInfo.id, login && login.userInfo && login.userInfo.uid]
    .filter(value => value != null);
  const ids = values.map(value => String(value));
  if (!ids.length || !/^[1-9]\d{0,19}$/.test(ids[0]) || ids.some(id => id !== ids[0])) {
    throw new BodianError('invalid-response', '波点返回的账号身份不一致');
  }
  const rawUser = (login && (login.userInfo || login.user)) || {};
  const user = {
    id: ids[0],
    nickname: String(rawUser.nickname || ''),
    avatarUrl: String(rawUser.headImg || rawUser.avatarUrl || ''),
  };
  if (typeof login.token === 'string' && login.token.trim()) {
    bodianSession = { uid: ids[0], token: login.token, user };
    saveBodianSession();
    bodianQrSessions.clear();
    return {
      status: 'confirmed', code: 803, message: '登录成功',
      uid: ids[0], token: login.token,
      nickname: user.nickname, avatar: user.avatarUrl, user,
    };
  }
  throw new BodianError('invalid-response', '未知的波点二维码状态');
}

async function bodianLoginStatus() {
  if (!bodianSession) return { provider: 'bodian', loggedIn: false, nickname: '波点音乐' };
  const user = bodianSession.user || {};
  if (user.id !== bodianSession.uid) {
    clearBodianSession();
    return { provider: 'bodian', loggedIn: false, nickname: '波点音乐' };
  }
  return {
    provider: 'bodian',
    loggedIn: true,
    userId: bodianSession.uid,
    nickname: user.nickname || ('波点 ' + bodianSession.uid),
    avatar: user.avatarUrl || '',
    vipType: 0,
    vipLevel: 'none',
    isVip: false,
    isSvip: false,
    vipLabel: '无VIP',
  };
}

// ---- 搜索 ----
async function bodianSearch(query, limit, offset) {
  query = String(query || '').trim();
  if (!query || query.length > 500) throw new BodianError('invalid-response', '无效的搜索关键词');
  limit = Math.max(1, Math.min(Number(limit) || 30, 100));
  offset = Math.max(0, Number(offset) || 0);
  const pn = Math.floor(offset / limit);
  const rn = limit;
  const payload = await bodianCall('/api/search/music/list', {
    params: { keyword: query, correct: 1, pn, rn },
  });
  const data = (payload && payload.data) || {};
  const rawList = Array.isArray(data.resultList) ? data.resultList : [];
  const songs = normalizeBodianList(rawList);
  return {
    provider: 'bodian',
    keyword: query,
    offset,
    limit,
    songs,
    hasMore: rawList.length > 0 && (data.total != null ? (pn + 1) * limit < Number(data.total) : rawList.length === limit),
    nextOffset: offset + songs.length,
  };
}

// ---- 播放 ----
function validateBodianAudioUrl(value) {
  try {
    const url = new URL(String(value || ''));
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error();
    return url.toString();
  } catch (_) {
    throw new BodianError('invalid-response', '波点返回了无效的音频地址');
  }
}

async function bodianSongUrl(id, quality) {
  const mediaId = String(id || '').trim();
  if (!/^\d{1,20}$/.test(mediaId) || /^0+$/.test(mediaId)) {
    return { provider: 'bodian', url: '', playable: false, error: 'MISSING_ID', message: '缺少波点歌曲 id' };
  }
  quality = quality === 'hires' ? 'lossless' : (quality === 'exhigh' ? 'high' : (quality || 'high'));
  if (!BODIAN_QUALITIES[quality]) quality = 'high';
  const freeSign = '';
  let right = null;
  try {
    right = (await bodianCall('/api/play/music/v2/checkRight', {
      params: { musicId: mediaId, freeSign },
      signed: true,
    })).data;
  } catch (error) {
    return {
      provider: 'bodian', url: '', playable: false, error: 'BODIAN_CHECK_RIGHT_FAILED',
      reason: error.code === 'auth-required' ? 'login_required' : (error.code === 'region-restricted' ? 'region_restricted' : 'unavailable'),
      message: error.message,
    };
  }
  // status 3 = 仅有试听权限：直接返回平台试听片段（前端会显示试听横幅）
  if (Number(right && right.status) === 3) {
    const preview = right.audition;
    if (!preview || !Number.isFinite(Number(preview.start)) || !Number.isFinite(Number(preview.end))) {
      return { provider: 'bodian', url: '', playable: false, reason: 'not_playable', message: '波点试听不可用' };
    }
    try {
      return {
        provider: 'bodian',
        url: validateBodianAudioUrl(preview.https || preview.url),
        playable: true,
        trial: true,
        trialInfo: { startTime: Number(preview.start), endTime: Number(preview.end) },
        level: 'standard',
        quality: '标准(试听)',
        loggedIn: !!bodianSession,
        message: '非会员账号播放试听片段',
      };
    } catch (error) {
      return { provider: 'bodian', url: '', playable: false, reason: 'not_playable', message: error.message };
    }
  }
  if (Number(right && right.status) === 7) {
    return {
      provider: 'bodian', url: '', playable: false,
      reason: bodianSession ? 'vip_required' : 'login_required',
      message: bodianSession ? '波点歌曲需要额外播放权限' : '波点需要先登录',
    };
  }
  if (right === null || right === undefined || right.status === undefined) {
    return {
      provider: 'bodian', url: '', playable: false,
      reason: bodianSession ? 'not_playable' : 'login_required',
      message: '波点播放权限不可用',
    };
  }
  const format = BODIAN_QUALITIES[quality];
  const body = { devId: bodianDeviceId, musicId: Number(mediaId), format: format.format, br: format.br, freeSign };
  let audio = null;
  try {
    audio = (await bodianCall('/api/play/music/v2/audioUrl', {
      params: Object.assign({}, body, { musicId: mediaId }),
      signed: true,
      method: 'GET',
    })).data;
  } catch (error) {
    return {
      provider: 'bodian', url: '', playable: false, error: 'BODIAN_AUDIO_FAILED',
      reason: error.code === 'auth-required' ? 'login_required' : (error.code === 'region-restricted' ? 'region_restricted' : 'unavailable'),
      message: error.message,
    };
  }
  const audioUrl = audio && (audio.audioHttpsUrl || audio.audioUrl);
  if (!audioUrl) {
    return { provider: 'bodian', url: '', playable: false, reason: 'not_playable', message: '波点未返回播放地址' };
  }
  try {
    return {
      provider: 'bodian',
      url: validateBodianAudioUrl(audioUrl),
      playable: true,
      trial: false,
      level: quality,
      quality: quality === 'lossless' ? '无损 FLAC' : (quality === 'high' ? '320k MP3' : '128k MP3'),
      loggedIn: !!bodianSession,
    };
  } catch (error) {
    return { provider: 'bodian', url: '', playable: false, reason: 'not_playable', message: error.message };
  }
}

// ---- 歌词 ----
function decodeBodianWordTiming(content) {
  const tag = content.match(/\[kuwo:([0-7]+)(?:\]\[.*)?\]/);
  const factors = tag ? parseInt(tag[1], 8) : 11;
  const startFactor = Math.trunc(factors / 10);
  const durationFactor = factors % 10;
  if (!startFactor || !durationFactor) return null;
  return content.replace(/\[kuwo:[^\]]*\]/g, '').replace(/<(-?\d+),(-?\d+)>/g, (_match, left, right) => {
    const a = Number(left);
    const b = Number(right);
    const start = Math.trunc(Math.abs((a + b) / (2 * startFactor)));
    const duration = Math.trunc(Math.abs((a - b) / (2 * durationFactor)));
    return `<${start},${duration}>`;
  });
}

function decodeBodianLyrics(content) {
  return {
    mainText: content.replace(/<-?\d+,-?\d+>/g, '').replace(/\[kuwo:[^\]]*\]/g, ''),
    wordByWordText: /<-?\d+,-?\d+>/.test(content) ? decodeBodianWordTiming(content) : null,
  };
}

async function bodianLyric(id) {
  const mediaId = String(id || '').trim();
  if (!/^\d{1,20}$/.test(mediaId)) return { provider: 'bodian', lyric: '', message: '缺少歌曲 id' };
  const query = `type=lyric&req=2&lrcx=1&rid=${mediaId}&songname=&artist=&corp=kuwo&fromchannel=bodian`;
  const result = await bodianCall('/mobi.s', {
    lyric: true,
    params: { f: 'bodian', q: Buffer.from(query, 'utf8').toString('base64') },
  });
  const content = result && result.data && typeof result.data.content === 'string' ? result.data.content : '';
  if (!content) return { provider: 'bodian', lyric: '' };
  const decoded = decodeBodianLyrics(Buffer.from(content, 'base64').toString('utf8'));
  return {
    provider: 'bodian',
    lyric: decoded.mainText || '',
    wordByWordLyric: decoded.wordByWordText || '',
  };
}

// ---- 用户歌单 ----
async function bodianUserPlaylists() {
  if (!bodianSession) return { provider: 'bodian', loggedIn: false, playlists: [], message: '波点需要先登录' };
  const uid = bodianSession.uid;
  const user = { userId: uid, fromUid: uid };
  const [owned, liked] = await Promise.all([
    bodianCall('/api/service/playlist/userCreate', { params: user }),
    bodianCall('/api/service/playlist/fond', { params: user }),
  ]);
  const mapPlaylist = (item, fallbackSource) => {
    if (!item || typeof item !== 'object') return null;
    const id = String(item.id ?? item.playListId ?? item.playlistId ?? '');
    if (!id) return null;
    const pic = String(item.img ?? item.pic ?? item.cover ?? '');
    return {
      provider: 'bodian',
      id,
      name: String(item.name ?? item.title ?? '波点歌单'),
      cover: pic,
      pic,
      trackCount: Number(item.total ?? item.musicCount ?? item.songCount ?? 0) || 0,
      source: Number(item.sourceType ?? fallbackSource ?? 4),
      liked: fallbackSource === 5,
      raw: item,
    };
  };
  const playlists = [];
  const ownedList = ((owned && owned.data && owned.data.playLists) || []);
  for (const item of ownedList) {
    const mapped = mapPlaylist(item, 4);
    if (mapped) playlists.push(mapped);
  }
  const likedData = (liked && liked.data) || {};
  if (likedData && likedData.id) {
    const mapped = mapPlaylist(likedData, 5);
    if (mapped) playlists.push(mapped);
  }
  return { provider: 'bodian', loggedIn: true, userId: uid, nickname: bodianSession.user && bodianSession.user.nickname, playlists };
}

async function bodianPlaylistTracks(id, limit, offset, preferredSource) {
  const listId = String(id || '').trim();
  if (!/^\d{1,20}$/.test(listId)) {
    return { provider: 'bodian', tracks: [], total: 0, error: 'MISSING_PLAYLIST_ID' };
  }
  limit = Math.max(1, Math.min(Number(limit) || 50, 100));
  offset = Math.max(0, Number(offset) || 0);
  // 波点歌单分两种目录：source=4 自建/默认收藏，source=5 收藏(我喜欢)。前端歌单对象会带 source 提示。
  const preferred = Number(preferredSource) === 5 ? 5 : (Number(preferredSource) === 4 ? 4 : null);
  const order = preferred ? [preferred, ...(preferred === 4 ? [5] : [4])] : [4, 5];
  let lastData = null;
  let lastError = null;
  for (const source of order) {
    try {
      const pn = Math.floor(offset / limit) + 1;
      const payload = await bodianCall(`/api/service/playlist/${listId}/musicList`, {
        params: { source, pn, rn: limit },
      });
      const data = (payload && payload.data) || {};
      const rawList = Array.isArray(data.list) ? data.list : (Array.isArray(data.resultList) ? data.resultList : []);
      lastData = { source, list: rawList, total: Number(data.total) || rawList.length };
      if (rawList.length) break;
    } catch (error) {
      lastError = error;
      continue;
    }
  }
  if (!lastData && lastError) throw lastError;
  const list = lastData ? lastData.list : [];
  const songs = normalizeBodianList(list);
  return {
    provider: 'bodian',
    id: listId,
    tracks: songs,
    total: lastData ? lastData.total : songs.length,
    hasMore: songs.length === limit,
    nextOffset: offset + songs.length,
  };
}

module.exports = {
  setBodianSessionFile,
  getBodianSession,
  clearBodianSession,
  bodianLoginQrKey,
  bodianLoginQrUrl,
  bodianLoginQrCheck,
  bodianLoginStatus,
  bodianSearch,
  bodianSongUrl,
  bodianLyric,
  bodianUserPlaylists,
  bodianPlaylistTracks,
  normalizeBodianSong,
  BodianError,
};
