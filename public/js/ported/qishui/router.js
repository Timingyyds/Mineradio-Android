// public/js/ported/qishui/router.js
// 把汽水（Luna）参考层的能力映射成 mineradio 前端期望的 /api/qishui/* 结构。
// 参考层 qishui.js 由 src/audioNative/qishui.ts 转译而来，暴露 qishuiRequest(operation, args)。

import { qishuiRequest } from './qishui.js';
import { readProviderSessionValue } from './sessionStore.js';

/*
 * 汽水账号资料快照。
 * login_status 是远程调用，冷启动弱网时要等好几秒界面才认账号；酷狗只读本地 cookie 所以秒出。
 * 这里按「当前会话」缓存上次的资料，命中立即返回，网络刷新丢到后台。
 */
const QISHUI_STATUS_CACHE_KEY = 'qishuiLoginStatusCache';
const QISHUI_STATUS_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const QISHUI_STATUS_REFRESH_MS = 2 * 60 * 1000;
// 会话里的 guardKey 每次 login_status 都会轮换，拿整串 session 当键永远对不上；
// 用稳定的 device_id 当键，登出时再清掉缓存，避免串账号。
const qishuiSessionKey = () => (
  readProviderSessionValue('qishui', 'session')
    ? String(readProviderSessionValue('qishui', 'device_id') || '')
    : ''
);
function clearQishuiStatusCache() {
  try { localStorage.removeItem(QISHUI_STATUS_CACHE_KEY); } catch (_) {}
}
function readQishuiStatusCache() {
  try {
    const raw = localStorage.getItem(QISHUI_STATUS_CACHE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch (_) {
    return null;
  }
}
function writeQishuiStatusCache(payload, key) {
  try {
    localStorage.setItem(QISHUI_STATUS_CACHE_KEY, JSON.stringify({ key, payload, at: Date.now() }));
  } catch (_) {}
}

const rec = (value) => (value && typeof value === 'object' && !Array.isArray(value) ? value : {});
const txt = (value) => (value == null ? '' : String(value));

// 汽水封面：tos-cn-v-* 原图可用 c5 模板换成小尺寸 webp，其它路径保持原样（套模板会 403）。
const QISHUI_COVER_SIZE = 480;
const sizedImage = (url) => (
  /\/tos-cn-v-[^/]+\//.test(url) && /~[^/~]+$/.test(url)
    ? url.replace(/~[^/~]+$/, `~c5_${QISHUI_COVER_SIZE}x${QISHUI_COVER_SIZE}.webp`)
    : url
);
const imageUrl = (raw) => {
  if (typeof raw === 'string') return raw.startsWith('https://') ? sizedImage(raw) : '';
  const value = rec(raw);
  const url = Array.isArray(value.urls)
    ? value.urls.find((item) => typeof item === 'string' && item.startsWith('https://'))
    : '';
  if (!url) return '';
  const resolved = value.uri && url.endsWith('/')
    ? `${url}${value.uri}${value.template_prefix ? `~${value.template_prefix}-image.image` : ''}`
    : url;
  return sizedImage(resolved);
};

const toSong = (raw) => {
  const track = rec(raw);
  const base = rec(track.base_info);
  const related = rec(track.related_info);
  const display = rec(track.display_info);
  const album = rec(related.album_link);
  const artists = Array.isArray(related.artist_links)
    ? related.artist_links
    : (base.song_maker_team && base.song_maker_team.composers) || [];
  const namedArtists = artists.filter((artist) => artist && artist.name);
  return {
    provider: 'qishui',
    source: 'qishui',
    type: 'song',
    id: txt(base.id || track.id),
    name: txt(base.name || display.name),
    artist: namedArtists.map((artist) => txt(artist.name)).join(' / '),
    artists: namedArtists.map((artist) => ({ id: txt(artist.id), name: txt(artist.name) })),
    artistId: namedArtists[0] ? txt(namedArtists[0].id) : '',
    album: txt(album.name),
    albumId: txt(album.id),
    cover: imageUrl(display.cover_url || album.cover_url),
    duration: Math.max(0, Number(base.duration_ms) || 0),
    fee: 0,
    playable: true,
  };
};

const toPlaylist = (raw) => {
  const item = rec(raw);
  const base = rec(item.base_info);
  const owner = rec(item.relation_info && item.relation_info.owner);
  const count = item.resource_cnt && item.resource_cnt.track_cnt;
  return {
    provider: 'qishui',
    source: 'qishui',
    id: txt(base.id),
    name: txt(base.title || base.name),
    cover: imageUrl(item.display_info && item.display_info.url_cover),
    trackCount: count == null ? 0 : Number(count) || 0,
    creator: txt(owner.nickname || (item.relation_info && item.relation_info.owner && item.relation_info.owner.nickname)),
  };
};

const normalizeQuality = (value) => {
  const raw = String(value || '').toLowerCase();
  if (raw === 'jymaster') return 'hires';
  if (raw === 'exhigh' || raw === 'ultra' || raw === 'high') return 'high';
  if (raw === 'lossless') return 'lossless';
  if (raw === 'hires') return 'hires';
  return 'standard';
};

const statusPayload = (user) => {
  if (!user) {
    return {
      provider: 'qishui', configured: false, loggedIn: false, webSession: false,
      nickname: '汽水音乐', avatar: '', userId: '', vipType: 0, vipLevel: 'none',
      isVip: false, isSvip: false, vipLabel: '无VIP', playbackKeyReady: false,
      playbackMode: 'recommend-match', capabilities: { search: true, playableUrl: false },
      cookieReady: false, tokenConfigured: true, publicCatalog: true, stale: false,
    };
  }
  return {
    provider: 'qishui', configured: true, loggedIn: true, webSession: true,
    nickname: txt(user.nickname || '汽水音乐'), avatar: imageUrl(user.avatar), userId: txt(user.id),
    vipType: user.isVip ? 1 : 0, vipLevel: user.isVip ? 'vip' : 'none',
    isVip: !!user.isVip, isSvip: false, vipLabel: user.isVip ? 'VIP' : '无VIP',
    // login_status 本身就带会员字段，拿到了就说明会员状态是已知的，别再挂「待同步」。
    membershipKnown: true,
    playbackKeyReady: true, playbackMode: 'recommend-match',
    capabilities: { search: true, playableUrl: true },
    cookieReady: true, tokenConfigured: true, publicCatalog: false, stale: false,
  };
};

/** 前端二维码轮询把状态映射成 waiting / scanned / verifying / expired / confirmed。 */
const qrStateToUi = (state) => {
  if (state === 'waiting') return { status: 'waiting' };
  if (state === 'scanned') return { status: 'scanned' };
  if (state === 'confirmed') return null; // 由调用方补账号状态
  if (state === 'expired') return { status: 'expired', errorCode: 2 };
  return { status: 'error', errorCode: 3 };
};

/**
 * 统一入口：与 10-api-router 的委托契约一致。
 * @param {{path:string, method:string, query:Record<string,string>, body:object}} input
 */
export async function handleQishuiRequest(input) {
  const path = String(input && input.path || '');
  const query = (input && input.query) || {};
  const body = (input && input.body) || {};
  const pick = (key) => query[key] != null ? query[key] : body[key];
  const limit = Math.max(1, Math.min(100, Number(pick('limit')) || 30));
  const offset = Math.max(0, Number(pick('offset')) || 0);

  if (path === '/api/qishui/status') {
    const sessionKey = qishuiSessionKey();
    const cached = readQishuiStatusCache();
    // 会话没变就直接给上次的资料，界面立刻显示账号；超过 2 分钟后台再校准一次。
    if (sessionKey && cached && cached.key === sessionKey && cached.payload
      && Date.now() - Number(cached.at || 0) < QISHUI_STATUS_TTL_MS) {
      if (Date.now() - Number(cached.at || 0) > QISHUI_STATUS_REFRESH_MS) {
        setTimeout(function () {
          qishuiRequest('login_status')
            .then(function (user) { writeQishuiStatusCache(statusPayload(user), sessionKey); })
            .catch(function () {});
        }, 120);
      }
      return Object.assign({}, cached.payload, { fromCache: true });
    }
    try {
      const payload = statusPayload(await qishuiRequest('login_status'));
      if (sessionKey) writeQishuiStatusCache(payload, sessionKey);
      return payload;
    } catch (err) {
      return Object.assign(statusPayload(null), { error: err && err.message, reauthRequired: /auth/i.test(String(err && err.code || '')) });
    }
  }

  if (path === '/api/qishui/login/qrcode') {
    const created = await qishuiRequest('login_qr_key');
    const image = await qishuiRequest('login_qr_create', { key: created.key });
    return { provider: 'qishui', token: created.key, qrcode: image.imageUrl };
  }

  if (path === '/api/qishui/login/check') {
    const key = pick('token') || pick('key');
    const result = await qishuiRequest('login_qr_check', { key });
    if (result && result.state === 'confirmed') {
      const payload = statusPayload(await qishuiRequest('login_status').catch(() => null));
      const cacheKey = qishuiSessionKey();
      if (cacheKey) writeQishuiStatusCache(payload, cacheKey);
      return payload;
    }
    return Object.assign({ provider: 'qishui' }, qrStateToUi(result && result.state) || { status: 'waiting' });
  }

  if (path === '/api/qishui/login/cancel') {
    await qishuiRequest('login_qr_cancel', { key: pick('token') || pick('key') });
    return { ok: true };
  }

  if (path === '/api/qishui/logout') {
    await qishuiRequest('logout');
    clearQishuiStatusCache();
    return { ok: true, provider: 'qishui', loggedIn: false };
  }

  if (path === '/api/qishui/search') {
    const keywords = String(pick('keywords') || pick('query') || '').trim();
    if (!keywords) return { provider: 'qishui', songs: [], hasMore: false, nextOffset: offset };
    const result = await qishuiRequest('search', { query: keywords, limit, offset });
    return {
      provider: 'qishui',
      songs: (result.items || []).map(toSong).filter((song) => song.id),
      hasMore: !!result.hasMore,
      nextOffset: Number(result.nextOffset) || offset,
    };
  }

  if (path === '/api/qishui/song/url') {
    const id = String(pick('id') || '');
    const audio = await qishuiRequest('audio', { id, quality: normalizeQuality(pick('quality')) });
    if (!audio || !audio.url) return { provider: 'qishui', url: '', playable: false, reason: 'url_unavailable' };
    return {
      provider: 'qishui',
      url: audio.url,
      playable: true,
      level: audio.quality || 'standard',
      quality: audio.quality || 'standard',
      preview: !!audio.preview,
      expiresAt: Number(audio.expiresAt) || 0,
    };
  }

  if (path === '/api/qishui/lyric') {
    const info = rec(await qishuiRequest('lyrics', { id: String(pick('id') || '') }).catch(() => ({})));
    const entity = rec(info.lyric_entity);
    const content = typeof entity.content === 'string' ? entity.content.replace(/\u0000/g, '') : '';
    const translations = rec(info.lang_translations);
    const translated = rec(translations['zh-CN'] || translations['ZH-HANS-CN'] || Object.values(translations)[0]);
    return {
      provider: 'qishui',
      lyric: content,
      tlyric: typeof translated.content === 'string' ? translated.content : '',
      format: entity.type === 'krc' ? 'krc' : 'lrc',
    };
  }

  if (path === '/api/qishui/feed') {
    const result = await qishuiRequest('feed', {
      scene: pick('scene'), preference: pick('preference'),
      queue: pick('queue') === 'explore' ? 'explore' : 'fm',
      first: String(pick('first') || '') === '1' || pick('first') === true,
      count: limit,
    });
    return {
      provider: 'qishui',
      songs: (result.items || []).map(toSong).filter((song) => song.id),
      hasMore: !!result.hasMore,
    };
  }

  if (path === '/api/qishui/user/playlists') {
    const result = await qishuiRequest('user_playlists', { limit, offset });
    return {
      provider: 'qishui',
      playlists: (result.items || []).map(toPlaylist).filter((pl) => pl.id && pl.name),
      total: Number(result.total) || 0,
    };
  }

  if (path === '/api/qishui/playlist/tracks') {
    const result = await qishuiRequest('playlist_tracks', {
      id: String(pick('id') || ''), ownerId: String(pick('ownerId') || pick('owner_id') || ''),
      limit, offset,
    });
    return {
      provider: 'qishui',
      tracks: (result.items || []).map(toSong).filter((song) => song.id),
      total: (result.items || []).length + (result.hasMore ? offset + limit : 0),
      hasMore: !!result.hasMore,
      nextOffset: Number(result.nextOffset) || offset,
    };
  }

  if (path === '/api/qishui/playlist/detail') {
    const result = await qishuiRequest('playlist_detail', {
      id: String(pick('id') || ''), ownerId: String(pick('ownerId') || pick('owner_id') || ''),
      limit, offset,
    });
    return { provider: 'qishui', playlist: result ? toPlaylist(result) : null };
  }

  if (path === '/api/qishui/liked/songs') {
    return { provider: 'qishui', ids: (await qishuiRequest('liked_songs')).ids || [] };
  }

  if (path === '/api/qishui/song/like') {
    const liked = String(pick('liked') ?? pick('like') ?? '') !== 'false';
    await qishuiRequest('like_song', { id: String(pick('id') || ''), liked });
    return { provider: 'qishui', ok: true, liked };
  }

  if (path === '/api/qishui/song/like/check') {
    const ids = String(pick('ids') || pick('id') || '').split(',').map((v) => v.trim()).filter(Boolean);
    if (!ids.length) return { provider: 'qishui', liked: {} };
    const current = rec(await qishuiRequest('liked_songs').catch(() => ({})));
    const likedSet = new Set((current.ids || []).map(String));
    const liked = {};
    ids.forEach((id) => { liked[id] = likedSet.has(id); });
    return { provider: 'qishui', liked };
  }

  return { provider: 'qishui', error: 'QISHUI_ENDPOINT_UNSUPPORTED', path };
}

export default handleQishuiRequest;
