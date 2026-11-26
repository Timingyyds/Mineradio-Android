// src/audioNative/qishui.ts
// 汽水音乐（字节跳动车机 Luna 开放平台）在 WebView 内的实现。
//
// 桌面版把 qishui-music-api@0.2.2 放在 Electron 主进程里跑，手机版没有主进程，这里按同一个包
// （src/protocol.cjs、client.cjs、auth.cjs、catalog.cjs、library.cjs、playback.cjs）逐条复刻：
//   · 请求头 bd-ticket-guard 的 P-256 客户端证书：WebCrypto 生成密钥对，并按原实现拼 PKCS#10 CSR；
//   · 账号请求用 ticket-guard 签名（ECDSA/SHA-256，DER 编码），公开读用 client_token；
//   · 所有跨境请求经 FoliaBridge（OkHttp）发出，由它把响应头带回来。
// 会话（ticket / 客户端私钥 / 过期时间 / 账号）存在 provider 会话存储里，与桌面版的 session 仓库等价。
import md5 from './md5.js';
import { bridgeRequest } from './core.js';
import { readProviderSessionValue, writeProviderSessionValue, removeProviderSessionValue } from './sessionStore.js';
export class QishuiError extends Error {
    constructor(code, message, upstreamCode, stage, cause) {
        super(message);
        this.code = code;
        this.upstreamCode = upstreamCode;
        this.stage = stage;
        this.cause = cause;
    }
}
// ---------------------------------------------------------------------------
// 协议常量（protocol.cjs）
const UA = 'LunaOpenSDK/1.0.10';
const PREFIX = '/api/luna/v1/platform';
const CLIENT_KEY = 'aw4bbxfujo741d09';
const COMMON = {
    aid: '765213',
    app_id: '765213',
    app_name: 'byte_autoservice_lite',
    version_code: '100010',
    version_name: '1.0.10',
    channel: 'bydSong',
    sdk_aid: '753999',
    caller_name: 'luna_open_sdk',
    sdk_version_code: '100000100',
    sdk_version_name: '0.0.1',
    sdk_channel: 'Test',
    vehicle_type: 'BYD',
    device_brand: 'BYD',
    device_type: 'BYD',
    device_platform: 'android',
    resolution: '1920x1080',
    os_version: '13',
    ac: 'wifi',
};
const randomHex = (bytes) => {
    const buffer = new Uint8Array(bytes);
    crypto.getRandomValues(buffer);
    return Array.from(buffer, byte => byte.toString(16).padStart(2, '0')).join('');
};
const createDeviceId = () => {
    const buffer = new Uint8Array(8);
    crypto.getRandomValues(buffer);
    let value = 0n;
    for (const byte of buffer)
        value = (value << 8n) | BigInt(byte);
    return ((value % 9000000000000000000n) + 1000000000000000000n).toString();
};
const deviceId = () => {
    const stored = readProviderSessionValue('qishui', 'device_id');
    if (stored && /^\d{19}$/.test(stored))
        return stored;
    const created = createDeviceId();
    writeProviderSessionValue('qishui', 'device_id', created);
    return created;
};
const params = (id) => new URLSearchParams({
    ...COMMON,
    device_id: id,
    did: id,
    iid: id,
    vehicle_id: `qishui-${id}`,
    _timestamp: String(Math.floor(Date.now() / 1000)),
    _nonce: String(crypto.getRandomValues(new Uint16Array(1))[0]),
});
// ---------------------------------------------------------------------------
// DER / PKCS#10（WebCrypto 取代 node:crypto）
const utf8 = (text) => new TextEncoder().encode(text);
const concat = (...parts) => {
    const total = parts.reduce((sum, part) => sum + part.length, 0);
    const out = new Uint8Array(total);
    let offset = 0;
    for (const part of parts) {
        out.set(part, offset);
        offset += part.length;
    }
    return out;
};
const derLength = (length) => (length < 128 ? Uint8Array.of(length)
    : length < 256 ? Uint8Array.of(0x81, length)
        : Uint8Array.of(0x82, length >> 8, length & 0xff));
const der = (tag, ...values) => {
    const value = concat(...values);
    return concat(Uint8Array.of(tag), derLength(value.length), value);
};
const derInteger = (value) => {
    let start = 0;
    while (start < value.length - 1 && value[start] === 0)
        start += 1;
    const body = value[start] < 0x80 ? value.slice(start) : concat(Uint8Array.of(0), value.slice(start));
    return der(0x02, body);
};
const hexBytes = (hex) => {
    const out = new Uint8Array(hex.length / 2);
    for (let index = 0; index < out.length; index += 1)
        out[index] = parseInt(hex.slice(index * 2, index * 2 + 2), 16);
    return out;
};
const base64 = (bytes) => {
    let binary = '';
    for (const byte of bytes)
        binary += String.fromCharCode(byte);
    return btoa(binary);
};
const fromBase64 = (text) => Uint8Array.from(atob(text), char => char.charCodeAt(0));
// node 的 crypto.sign 回 DER；WebCrypto 回 r||s 的裸签名，这里改成 DER。
const signDer = async (key, data) => {
    const raw = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, data));
    return der(0x30, derInteger(raw.slice(0, raw.length / 2)), derInteger(raw.slice(raw.length / 2)));
};
const createGuard = async () => {
    const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
    const spki = new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey));
    const pkcs8 = new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey));
    const name = der(0x30, der(0x31, der(0x30, der(0x06, Uint8Array.of(0x55, 0x04, 3)), der(0x0c, utf8('bd-ticket-guard')))), der(0x31, der(0x30, der(0x06, Uint8Array.of(0x55, 0x04, 11)), der(0x0c, utf8('')))), der(0x31, der(0x30, der(0x06, Uint8Array.of(0x55, 0x04, 10)), der(0x0c, utf8('')))));
    const info = der(0x30, derInteger(new Uint8Array([0])), name, spki, der(0xa0));
    const algorithm = der(0x30, der(0x06, hexBytes('2a8648ce3d040302')));
    const signature = await signDer(pair.privateKey, info);
    const request = der(0x30, info, algorithm, der(0x03, Uint8Array.of(0), signature));
    const body = base64(request).match(/.{1,64}/g)?.join('\n') ?? base64(request);
    const pem = `-----BEGIN CERTIFICATE REQUEST-----\n${body}\n-----END CERTIFICATE REQUEST-----\n`;
    return { guardKey: base64(pkcs8), csr: base64(utf8(pem)) };
};
const guardHeaders = async (credential, path = '/me/info/') => {
    const key = await crypto.subtle.importKey('pkcs8', fromBase64(credential.guardKey), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
    const timestamp = Math.floor(Date.now() / 1000);
    const content = `ticket=${credential.ticket}&path=${PREFIX}${path}&timestamp=${timestamp}`;
    const reqSign = base64(await signDer(key, utf8(content)));
    return {
        'access-token': credential.ticket,
        'bd-ticket-guard-version': '1',
        'bd-ticket-guard-client-cert': credential.guardCert,
        'bd-ticket-guard-client-data': base64(utf8(JSON.stringify({
            ts_sign: credential.guardTsSign,
            req_content: 'ticket,path,timestamp',
            req_sign: reqSign,
            timestamp,
        }))),
    };
};
const readGuard = (raw) => {
    if (!raw || raw.length > 65536)
        throw new QishuiError('invalid-response', 'invalid guard response');
    let data;
    try {
        data = JSON.parse(new TextDecoder().decode(fromBase64(raw)));
    }
    catch (error) {
        throw new QishuiError('invalid-response', 'invalid guard response', undefined, undefined, error);
    }
    for (const key of ['ticket', 'ts_sign', 'client_cert']) {
        const value = data?.[key];
        if (typeof value !== 'string' || !value || value.length > 16384) {
            throw new QishuiError('invalid-response', 'invalid guard response');
        }
    }
    const cert = String(data.client_cert);
    return {
        ticket: data.ticket,
        guardTsSign: data.ts_sign,
        guardCert: cert.includes('-----BEGIN') ? base64(utf8(cert)) : cert,
    };
};
const credentialExpiry = (raw) => {
    const value = Number(raw || 0);
    const expires = value > 1e12 ? value : value > 1e9 ? value * 1000 : value > 0 ? Date.now() + value * 1000 : Date.now() + 6 * 3600000;
    if (!Number.isFinite(expires) || expires <= Date.now())
        throw new QishuiError('invalid-response', 'invalid credential expiry');
    return expires;
};
let cachedSession;
const readSession = () => {
    if (cachedSession !== undefined)
        return cachedSession;
    const raw = readProviderSessionValue('qishui', 'session');
    if (!raw) {
        cachedSession = null;
        return null;
    }
    try {
        cachedSession = JSON.parse(raw);
    }
    catch {
        cachedSession = null;
    }
    return cachedSession;
};
const writeSession = (session) => {
    cachedSession = session;
    writeProviderSessionValue('qishui', 'session', JSON.stringify(session));
};
const clearSession = () => {
    cachedSession = null;
    removeProviderSessionValue('qishui', 'session');
};
// ---------------------------------------------------------------------------
// HTTP（client.cjs）
const AUTH_CODES = [28001003, 28001007, 28001008, 10007];
const checked = (data) => {
    if (!data || typeof data !== 'object' || Array.isArray(data)) {
        throw new QishuiError('invalid-response', 'Invalid Qishui response');
    }
    const code = Number(data.err_no ?? data.data?.error_code ?? 0);
    if (code) {
        throw new QishuiError(AUTH_CODES.includes(code) ? 'auth-required' : 'unavailable', 'Qishui rejected the request', String(code));
    }
    return data;
};
const headerReader = (headers) => {
    const map = new Map(Object.entries(headers ?? {}).map(([key, value]) => [key.toLowerCase(), value]));
    return { get: (name) => map.get(name.toLowerCase()) ?? null };
};
const http = async (url, options = {}) => {
    const { body, headers = {}, form = false } = options;
    let response;
    try {
        response = await bridgeRequest({
            url,
            method: body === undefined ? 'GET' : 'POST',
            headers: {
                'user-agent': UA,
                ...(body === undefined ? {} : { 'content-type': form ? 'application/x-www-form-urlencoded' : 'application/json' }),
                ...headers,
            },
            ...(body === undefined
                ? {}
                : { body: form ? new URLSearchParams(body).toString() : JSON.stringify(body) }),
            followRedirects: false,
            // 桌面版桥接明确不发送浏览器 Cookie（凭据全在请求头里）；同一域上的登录 Cookie 会让网关走另一条慢路径。
            omitCookies: true,
            timeoutMs: 25000,
        });
    }
    catch (cause) {
        throw new QishuiError('network', 'Qishui service could not be reached', undefined, undefined, cause);
    }
    if (response.status < 200 || response.status >= 300) {
        throw new QishuiError(response.status === 429 ? 'unavailable' : [401, 403].includes(response.status) ? 'auth-required' : 'network', `Qishui HTTP ${response.status}`);
    }
    let json;
    try {
        json = JSON.parse(response.body ?? '');
    }
    catch (cause) {
        throw new QishuiError('invalid-response', 'Qishui response is not JSON', undefined, undefined, cause);
    }
    return { data: checked(json), headers: headerReader(response.headers) };
};
const query = (path, extras = {}, id = deviceId()) => {
    const values = params(id);
    for (const [key, value] of Object.entries(extras))
        values.set(key, String(value));
    return `${path}?${values.toString()}`;
};
const RETRYABLE_READS = new Set([
    '/search/', '/playlist/detail/', '/media/mget/', '/media/mcheck/',
    '/artist/detail/', '/artist/tracks/',
]);
let clientTokenEntry = null;
const clientToken = () => {
    if (clientTokenEntry)
        return clientTokenEntry;
    const entry = {};
    entry.task = http(query(`https://open.qishui.com${PREFIX}/client/token/get/`, { trigger_type: 'cold_start' }))
        .then(({ data }) => {
        if (typeof data.client_token !== 'string' || !data.client_token) {
            throw new QishuiError('invalid-response', 'Client token is missing');
        }
        return data.client_token;
    })
        .catch((error) => {
        if (clientTokenEntry === entry)
            clientTokenEntry = null;
        throw error;
    });
    clientTokenEntry = entry;
    return entry;
};
const platform = async (path, body = {}, credential) => {
    const stored = readSession();
    let account = stored;
    if (credential === true) {
        if (!stored)
            throw new QishuiError('auth-required', 'Sign in to Qishui first');
        account = stored;
    }
    else if (credential) {
        account = null;
    }
    const send = async (headers) => {
        const { data } = await http(query(`https://open.douyin.com${PREFIX}${path}`, {}, account?.deviceId), {
            body: {
                ...body,
                common_params: { ...(account?.userId ? { user_id: account.userId } : {}) },
            },
            headers,
        });
        if (!data.data || typeof data.data !== 'object') {
            throw new QishuiError('invalid-response', 'Qishui data is missing');
        }
        return data.data;
    };
    if (credential && credential !== true)
        return send(await guardHeaders(credential, path));
    if (credential === true)
        return send(await guardHeaders(stored, path));
    let token = clientToken();
    for (let attempt = 0;; attempt += 1) {
        const headers = { 'access-token': await token.task };
        try {
            return await send(headers);
        }
        catch (error) {
            if (!(error instanceof QishuiError) || error.upstreamCode !== '28001008')
                throw error;
            if (clientTokenEntry === token)
                clientTokenEntry = null;
            if (attempt || !RETRYABLE_READS.has(path))
                throw error;
            token = clientToken();
        }
    }
};
// 账号写操作：必须带 ticket-guard 凭据，回答可以没有 data。
const command = async (path, body = {}) => {
    const credential = readSession();
    if (!credential)
        throw new QishuiError('auth-required', 'Sign in to Qishui first');
    const { data } = await http(query(`https://open.douyin.com${PREFIX}${path}`, {}, credential.deviceId), {
        body: { ...body, common_params: { ...(credential.userId ? { user_id: credential.userId } : {}) } },
        headers: await guardHeaders(credential, path),
    });
    return data.data && typeof data.data === 'object' ? data.data : {};
};
// ---------------------------------------------------------------------------
// 目录 / 媒体（catalog.cjs）
const arrayOf = (value, field) => {
    if (!Array.isArray(value))
        throw new QishuiError('invalid-response', `Qishui ${field} is missing`);
    return value;
};
const publicTrack = (raw) => {
    const track = raw?.track_entity || raw?.entity?.media?.track_entity || raw?.entity?.track || raw?.track || raw;
    if (!track?.base_info?.id && !track?.id) {
        throw new QishuiError('invalid-response', 'Qishui track identity is missing');
    }
    return {
        id: String(track.base_info?.id || track.id),
        base_info: track.base_info,
        related_info: track.related_info,
        display_info: track.display_info,
        commerce_info: track.commerce_info,
    };
};
const trackById = async (id) => {
    const data = await platform('/media/mget/', { media_list: [{ id, type: 'track' }], card_context: {} });
    const media = arrayOf(data.media_list, 'media list')
        .find((item) => String(item.media_id || item.track_entity?.base_info?.id) === id);
    if (!media?.track_entity)
        throw new QishuiError('not-playable', 'Qishui did not return the track');
    return media.track_entity;
};
const search = async (queryText, type, limit, offset) => {
    const items = [];
    const searchId = randomHex(16).replace(/(.{8})(.{4})(.{4})(.{4})(.{12})/, '$1-$2-$3-$4-$5');
    let cursor = offset;
    let more = false;
    while (items.length < limit) {
        const data = await platform('/search/', {
            q: queryText, cursor: String(cursor), search_id: searchId, from_search_id: searchId,
            search_method: 'input', search_type: type, search_scene: 'normal', scene_name: 'search',
            ab_param: '', last_viewed_media: {},
        });
        const groups = arrayOf(data.result_groups, 'search groups');
        const group = groups.find((value) => value.id === `${type}s`) || groups[0];
        if (!group)
            break;
        const batch = arrayOf(group.data, 'search items')
            .map((value) => value.entity?.[type])
            .filter(Boolean);
        const remaining = limit - items.length;
        items.push(...batch.slice(0, remaining));
        more = batch.length > remaining || group.has_more === true;
        if (!batch.length || !more || items.length === limit)
            break;
        const next = Number(group.next_cursor);
        if (!Number.isSafeInteger(next) || next <= cursor) {
            throw new QishuiError('invalid-response', 'Search cursor did not advance');
        }
        cursor = next;
    }
    return {
        items: type === 'track' ? items.map(publicTrack) : items,
        hasMore: more && items.length > 0,
        nextOffset: offset + items.length,
    };
};
const playlistPage = async (id, ownerId, limit, offset) => {
    let cursor = '0';
    let consumed = 0;
    const seen = new Set();
    const items = [];
    let collection;
    let hasMore = false;
    let pages = 0;
    while (items.length < limit && pages < 500) {
        pages += 1;
        seen.add(cursor);
        const data = await platform('/playlist/detail/', {
            playlist_id: id, owner_id: ownerId || '', count: 100, cursor, type: null,
        });
        collection || (collection = data.playlist);
        const batch = arrayOf(data.media_entities, 'playlist tracks');
        const start = Math.max(0, offset - consumed);
        const remaining = limit - items.length;
        items.push(...batch.slice(start, start + remaining).map(publicTrack));
        consumed += batch.length;
        const next = String(data.next_cursor ?? '');
        const upstreamMore = data.has_more === true;
        hasMore = consumed > offset + items.length || upstreamMore;
        if (upstreamMore && seen.has(next)) {
            throw new QishuiError('invalid-response', 'Playlist cursor did not advance');
        }
        if (!upstreamMore || items.length === limit)
            break;
        if (!batch.length || !next || seen.has(next)) {
            throw new QishuiError('invalid-response', 'Playlist cursor did not advance');
        }
        cursor = next;
    }
    return { items, collection, hasMore: hasMore && items.length > 0, nextOffset: offset + items.length };
};
// ---------------------------------------------------------------------------
// 账号库（library.cjs）
const MAX_BATCH = 100;
const mediaIdOf = (value) => {
    if (typeof value !== 'string' || !/^\d{1,30}$/.test(value)) {
        throw new QishuiError('invalid-response', 'Invalid Qishui media ID');
    }
    return value;
};
const trackRef = (id) => ({ id, type: 'track' });
const likedPlaylist = async () => {
    let cursor = '';
    const seen = new Set();
    for (let count = 0; count < 20; count += 1) {
        seen.add(cursor);
        const data = await platform('/me/playlist/', { count: 100, cursor }, true);
        const rows = [data.playlists, data.playlist_list].find(Array.isArray) ?? [];
        for (const row of rows) {
            const playlist = row?.playlist || row || {};
            const base = playlist.base_info || {};
            if (Number(base.type) === 1 && base.id)
                return { id: String(base.id), ownerId: String(base.owner_id || '') };
        }
        if (data.has_more !== true)
            return null;
        const next = String(data.next_cursor ?? '');
        if (!next || seen.has(next)) {
            throw new QishuiError('invalid-response', 'Account playlist cursor did not advance');
        }
        cursor = next;
    }
    throw new QishuiError('invalid-response', 'Account playlist page limit reached');
};
const likedSongs = async () => {
    const liked = await likedPlaylist();
    if (!liked)
        throw new QishuiError('invalid-response', 'Qishui liked playlist was not found');
    const ids = new Set();
    let cursor = '';
    let whole = false;
    const seen = new Set();
    for (let count = 0; count < 500; count += 1) {
        seen.add(cursor);
        const data = await platform('/playlist/detail/', {
            playlist_id: liked.id, owner_id: liked.ownerId, count: 100, cursor, type: 1,
        });
        const media = [data.media_entities, data.media_list].find(Array.isArray) ?? [];
        for (const item of media) {
            const id = publicTrack(item).id;
            if (id)
                ids.add(id);
        }
        if (data.has_more !== true) {
            whole = true;
            break;
        }
        const next = String(data.next_cursor ?? '');
        if (!next || seen.has(next)) {
            throw new QishuiError('invalid-response', 'Liked playlist cursor did not advance');
        }
        cursor = next;
    }
    return { ids: [...ids], whole, total: ids.size };
};
const recentPlayed = async (limit, offset) => {
    const items = [];
    let cursor = '';
    let consumed = 0;
    let hasMore = false;
    for (let count = 0; count < 500; count += 1) {
        const data = await platform('/me/recent-played/', {
            count: 100, cursor, media_type: 'track', scene: 'me_tab',
        }, true);
        if (!Array.isArray(data.media) && data.has_more === true) {
            throw new QishuiError('invalid-response', 'Qishui recent tracks are missing');
        }
        const rows = Array.isArray(data.media) ? data.media : [];
        for (const row of rows) {
            const media = row?.media;
            if (!media?.track_entity)
                continue;
            const track = publicTrack(media);
            const rawPlayedAt = Number(row.play_start_timestamp);
            const playedAt = rawPlayedAt > 1e12 ? rawPlayedAt : rawPlayedAt * 1000;
            if (consumed >= offset && items.length < limit) {
                items.push({ ...track, ...(Number.isFinite(playedAt) && playedAt > 0 ? { playedAt } : {}) });
            }
            consumed += 1;
        }
        const upstreamMore = data.has_more === true;
        hasMore = consumed > offset + items.length || upstreamMore;
        if (!upstreamMore || items.length >= limit)
            break;
        const next = String(data.next_cursor ?? '');
        if (!next || next === cursor) {
            throw new QishuiError('invalid-response', 'Recent-played cursor did not advance');
        }
        cursor = next;
    }
    return { items, hasMore: hasMore && items.length > 0, nextOffset: offset + items.length };
};
// 账号歌单：两个来源（自建歌单 / 收藏的混合集合），游标不是行号，按整表去重后再切片。
const userPlaylists = async (limit, offset) => {
    const items = [];
    for (const [path, field, owned] of [
        ['/me/playlist/', 'playlists', true],
        ['/me/collection/mixed/', 'mixed_collections', false],
    ]) {
        let cursor = '';
        const seen = new Set();
        for (let page = 0; page < 500; page += 1) {
            seen.add(cursor);
            const data = await platform(path, { count: 100, cursor, item_types: ['playlist'] }, true);
            const rows = data[field] ?? (path === '/me/playlist/' ? data.playlist_list : undefined);
            for (const row of arrayOf(rows, field)) {
                const playlist = row.playlist || row;
                if (playlist.base_info?.id)
                    items.push({ ...playlist, isOwned: owned });
            }
            if (!data.has_more)
                break;
            const next = String(data.next_cursor ?? '');
            if (!next || seen.has(next) || !rows.length || page === 499) {
                throw new QishuiError('invalid-response', 'Account playlist cursor did not advance');
            }
            cursor = next;
        }
    }
    const unique = [...new Map(items.map(item => [String(item.base_info.id), item])).values()];
    return {
        items: unique.slice(offset, offset + limit),
        total: unique.length,
        nextOffset: offset + Math.min(limit, Math.max(0, unique.length - offset)),
        hasMore: offset + limit < unique.length,
    };
};
// 电台 / 推荐流：队列状态按账号归零；同一队列的请求串行，避免两页互相消费对方的光标。
const feedQueues = new Map();
const feedActive = new Map();
let feedTotal = 0;
let feedGeneration = 0;
let feedPending = Promise.resolve();
export const clearQishuiFeed = () => {
    feedGeneration += 1;
    feedQueues.clear();
    feedActive.clear();
    feedTotal = 0;
};
const feedModes = async () => {
    const data = await platform('/feed/mode/');
    if (!Array.isArray(data.feed_mode_block))
        throw new QishuiError('invalid-response', 'Qishui feed modes are missing');
    const rows = data.feed_mode_block.flatMap((block) => (block.feed_mode || []).flatMap((item) => {
        const mode = item.entity?.feed_mode;
        const id = mode?.scene_mode?.scene_mode_id;
        return id == null ? [] : [{
                id: String(id),
                name: String(mode.text || ''),
                group: String(block.title || ''),
                coverUrl: mode.url_info,
            }];
    }));
    return rows;
};
const feedSongs = (options) => {
    const mine = feedGeneration;
    const run = async () => {
        if (mine !== feedGeneration)
            throw new QishuiError('unavailable', 'Qishui account changed');
        const { scene, preference, queue = 'fm', first = false, count = 20 } = options;
        if (preference != null && (typeof preference !== 'string' || !/^[a-z][a-z_]{0,31}$/.test(preference))) {
            throw new QishuiError('invalid-response', 'Invalid preference');
        }
        if (scene != null && preference)
            throw new QishuiError('invalid-response', 'Scene and preference are mutually exclusive');
        if (scene != null && (!/^\d+$/.test(String(scene)) || !Number.isSafeInteger(Number(scene)))) {
            throw new QishuiError('invalid-response', 'Invalid scene');
        }
        const key = `${queue}:${scene ?? preference ?? 'default'}`;
        if (feedQueues.size > 100)
            feedQueues.clear();
        const previous = feedQueues.get(key);
        const state = first || !previous || feedActive.get(queue) !== key
            ? { count: 0, selectedAt: Date.now() }
            : previous;
        const data = await platform('/feed/song-tab/', {
            played_media: [],
            is_first_request: state.count === 0,
            with_client_cache: state.count !== 0,
            is_did_first_request: false,
            count,
            feed_preference: {
                scene_mode_id: scene == null ? null : Number(scene),
                scene_mode_select_time: scene == null ? null : state.selectedAt,
                preference_mode: preference || null,
                total_request_count: ++feedTotal,
                preference_mode_request_count: state.count + 1,
                current_mode_request_count: state.count + 1,
            },
        });
        if (!Array.isArray(data.items))
            throw new QishuiError('invalid-response', 'Qishui feed items are missing');
        if (mine !== feedGeneration)
            throw new QishuiError('unavailable', 'Qishui account changed');
        feedActive.set(queue, key);
        state.count += 1;
        feedQueues.set(key, state);
        return {
            items: data.items.filter((item) => !item.type || item.type === 'track').map(publicTrack),
            hasMore: data.has_more === true,
        };
    };
    const result = feedPending.then(run, run);
    feedPending = result.catch(() => undefined);
    return result;
};
const checkTracks = async (ids) => {
    if (!Array.isArray(ids) || !ids.length)
        throw new QishuiError('invalid-response', 'Qishui media IDs are required');
    if (ids.length > MAX_BATCH)
        throw new QishuiError('unsupported', `Select at most ${MAX_BATCH} tracks per operation`);
    const unique = [...new Set(ids.map(mediaIdOf))];
    const data = await platform('/media/mcheck/', { media_list: unique.map(trackRef) });
    const rows = Array.isArray(data.media_status_list) ? data.media_status_list : [];
    return {
        statuses: rows.map((row) => ({
            id: String(row.id ?? ''),
            riskResult: Number(row.risk_info?.risk_result ?? -1),
            status: Number(row.risk_info?.status ?? 0),
        })),
    };
};
// ---------------------------------------------------------------------------
// 播放（playback.cjs）：只播平台给的不加密地址
const MEDIA_HOSTS = [
    '365yg.com', 'douyinvod.com', 'douyinscdn.com', 'bytefcdn.com', 'bytecdn.cn', 'byteicdn.com',
    'ibytedtos.com', 'pstatp.com', 'snssdk.com', 'bytedance.com', 'bytetos.com', 'volcvideo.com',
    'volccdn.com', 'volces.com', 'volcengineapi.com', 'douyin.com', 'qishui.com', 'zijieapi.com', 'zjcdn.com',
];
const mediaUrl = (raw) => {
    let url;
    try {
        url = new URL(String(raw));
    }
    catch {
        throw new QishuiError('invalid-response', 'Invalid Qishui media URL');
    }
    if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443')
        || !MEDIA_HOSTS.some(host => url.hostname === host || url.hostname.endsWith(`.${host}`))) {
        throw new QishuiError('invalid-response', 'Untrusted Qishui media host');
    }
    return url.href;
};
const LEVELS = {
    medium: 'standard', higher: 'high', highest: 'high', lossless: 'lossless', hi_res: 'hires',
};
const RANK = ['standard', 'high', 'lossless', 'hires'];
const rankOf = (value) => RANK.indexOf(value);
const isEncrypted = (item) => Boolean(item?.encrypt_info?.encrypt || item?.encrypt_info?.spade_a || item?.PlayAuth || Number(item?.Encrypt || 0));
const resolveAudio = async (track, requested) => {
    const player = track?.player_info;
    if (!player)
        throw new QishuiError('not-playable', 'Qishui returned no player information');
    const refused = new Set();
    const trusted = (raw) => {
        try {
            return mediaUrl(raw);
        }
        catch {
            try {
                refused.add(new URL(String(raw)).hostname);
            }
            catch {
                refused.add('invalid URL');
            }
            return null;
        }
    };
    for (const variant of ['full', 'preview']) {
        const info = player[variant]?.video_model_info;
        if (!info)
            continue;
        const candidates = [];
        let skipped = false;
        const add = (raw, quality, bitrate) => {
            const url = trusted(raw);
            if (url)
                candidates.push({ url, quality: LEVELS[String(quality)] || 'standard', bitrate: Number(bitrate || 0) });
            else
                skipped = true;
        };
        if (info.video_model) {
            let model;
            try {
                model = typeof info.video_model === 'string' ? JSON.parse(info.video_model) : info.video_model;
            }
            catch {
                throw new QishuiError('invalid-response', 'Invalid Qishui player model');
            }
            for (const item of model.video_list || []) {
                if (isEncrypted(item) || !item.main_url)
                    continue;
                add(item.main_url, item.video_meta?.quality, item.video_meta?.bitrate);
            }
        }
        if (!candidates.length && info.url_player_info) {
            const playInfo = trusted(info.url_player_info);
            if (playInfo) {
                const { data } = await http(playInfo);
                const list = data.Result?.Data?.PlayInfoList || data.data?.PlayInfoList || data.PlayInfoList;
                if (!Array.isArray(list))
                    throw new QishuiError('invalid-response', 'Qishui PlayInfoList is missing');
                for (const item of list) {
                    if (isEncrypted(item) || !item.MainPlayUrl)
                        continue;
                    add(item.MainPlayUrl, item.Quality, item.Bitrate);
                }
            }
            else {
                skipped = true;
            }
        }
        if (!candidates.length) {
            if (skipped) {
                if (refused.size)
                    console.warn(`[Qishui] Skipped untrusted media hosts: ${[...refused].join(', ')}`);
                throw new QishuiError('invalid-response', 'Untrusted Qishui media host');
            }
            continue;
        }
        if (refused.size)
            console.warn(`[Qishui] Skipped untrusted media hosts: ${[...refused].join(', ')}`);
        const allowed = candidates.filter(item => rankOf(item.quality) <= rankOf(requested));
        const source = (allowed.length ? allowed : candidates)
            .sort((a, b) => rankOf(b.quality) - rankOf(a.quality) || b.bitrate - a.bitrate)[0];
        console.info(`[Qishui] Plaintext audio issued: quality=${source.quality} preview=${variant === 'preview'}`);
        return {
            url: source.url,
            quality: source.quality,
            fetchedAt: Date.now(),
            ...(Number(info.expire_at) > Date.now() / 1000 ? { expiresAt: Number(info.expire_at) * 1000 } : {}),
            preview: variant === 'preview',
        };
    }
    throw new QishuiError('unsupported', 'Qishui returned protected audio only; decryption is disabled');
};
// ---------------------------------------------------------------------------
// 登录（auth.cjs）
const qrParams = () => ({
    aid: '1128',
    client_key: CLIENT_KEY,
    scope: 'user_info,luna.openapi.platform.interaction,luna.openapi.platform.me_tab,luna.commerce.openapi_platform.vip_info',
    state: '',
    next: '',
    jump_type: 'native',
    optional_scope_check: '',
    optional_scope_uncheck: '',
    customize_params: '{"source":"pc_auth","not_skip_confirm":"true"}',
    source_from: 'native',
    device_platform: 'android',
    app_identity: md5('com.luna.music.car'),
    signature: 'cf9024ea72d9c71813238b13ea107a3d',
});
const pending = new Map();
let generation = 0;
let refreshing = null;
const cleanPending = () => {
    const now = Date.now();
    for (const [key, value] of pending)
        if (value.expires <= now)
            pending.delete(key);
};
const withStage = async (stage, run) => {
    try {
        return await run();
    }
    catch (error) {
        const failure = error instanceof QishuiError
            ? error
            : new QishuiError('invalid-response', 'Qishui sign-in response could not be read', undefined, undefined, error);
        throw new QishuiError(failure.code, failure.message, failure.upstreamCode, failure.stage ?? stage, failure.cause);
    }
};
const getUser = (credential) => withStage('account', async () => {
    const info = await platform('/me/info/', {}, credential);
    const user = info?.my_info;
    if (typeof user?.id !== 'string' || !user.id || user.id.length > 512) {
        throw new QishuiError('invalid-response', 'Qishui did not identify the account');
    }
    return {
        id: String(user.id),
        nickname: String(user.nickname || ''),
        avatar: user.medium_avatar_url || user.avatar_url || user.thumb_avatar_url,
        isVip: user.is_vip === true,
    };
});
const ticket = (access, guard, id) => {
    if (!access?.access_code)
        throw new QishuiError('invalid-response', 'Access code is missing');
    return withStage('ticket', async () => {
        const { headers } = await http(`https://open-client.douyin.com/passport/open/get_access_ticket/?${new URLSearchParams({
            aid: '1128', client_key: CLIENT_KEY, device_platform: 'android',
        })}`, {
            body: { access_code: access.access_code, open_id: String(access.open_id || access.open_uid || '') },
            form: true,
            headers: {
                'bd-ticket-guard-version': '1',
                ...(guard.csr
                    ? { 'bd-ticket-guard-client-csr': guard.csr }
                    : guard.guardCert ? { 'bd-ticket-guard-client-cert': guard.guardCert } : {}),
            },
        });
        const server = headers.get('bd-ticket-guard-server-data');
        if (!server)
            throw new QishuiError('invalid-response', 'Qishui ticket guard data is missing');
        const opened = readGuard(server);
        return {
            deviceId: id,
            guardKey: guard.guardKey,
            ...opened,
            accessCode: access.access_code,
            openId: String(access.open_id || access.open_uid || ''),
            expiresAt: credentialExpiry(access.expires_time),
            userId: '',
            user: { id: '', nickname: '', isVip: false },
        };
    });
};
const renew = async () => {
    const old = readSession();
    if (!old) {
        console.info('[Qishui] status: no stored session');
        return null;
    }
    console.info('[Qishui] status: stored session found', {
        expiresInMin: Math.round((old.expiresAt - Date.now()) / 60000),
        needsRefresh: old.expiresAt < Date.now() + 3600000,
    });
    const revision = generation;
    const signOut = () => {
        if (revision !== generation)
            return;
        clearSession();
        console.info('[Qishui] Stored account was rejected; signed out');
    };
    try {
        let candidate = old;
        if (old.expiresAt < Date.now() + 3600000) {
            const result = await http(query(`https://open.qishui.com${PREFIX}/access/code/refresh/`, { access_code: old.accessCode }, old.deviceId));
            const access = result.data?.data || result.data;
            const next = await ticket({ ...access, open_id: access.open_id || access.open_uid || old.openId }, { guardKey: old.guardKey, guardCert: old.guardCert }, old.deviceId);
            candidate = next;
        }
        const user = await getUser(candidate);
        if (user.id !== old.userId) {
            signOut();
            throw new QishuiError('auth-required', 'Qishui account identity changed');
        }
        if (revision !== generation)
            return null;
        const session = { ...candidate, userId: user.id, user };
        writeSession(session);
        return user;
    }
    catch (error) {
        const failure = error instanceof QishuiError
            ? { code: error.code, upstreamCode: error.upstreamCode, stage: error.stage, message: error.message }
            : { code: 'unknown', message: String(error) };
        console.warn('[Qishui] status: account read failed', failure);
        if (error instanceof QishuiError && error.code === 'auth-required' && error.upstreamCode)
            signOut();
        throw error;
    }
};
const loginStatus = () => {
    if (!refreshing)
        refreshing = renew().finally(() => { refreshing = null; });
    return refreshing;
};
// ---------------------------------------------------------------------------
// 操作分发（api.cjs）
const idOf = (value) => {
    if (typeof value !== 'string' || !/^\d{1,30}$/.test(value)) {
        throw new QishuiError('invalid-response', 'Invalid Qishui media ID');
    }
    return value;
};
export const qishuiRequest = async (operation, raw = {}) => {
    const limit = Math.min(raw.limit ?? 30, 100);
    const offset = raw.offset ?? 0;
    if (!Number.isSafeInteger(limit) || limit < 1 || !Number.isSafeInteger(offset) || offset < 0) {
        throw new QishuiError('invalid-response', 'Invalid Qishui page');
    }
    const id = typeof raw.id === 'string' ? idOf(raw.id) : '';
    switch (operation) {
        case 'login_qr_key': {
            cleanPending();
            pending.clear();
            const mine = ++generation;
            const result = await http(`https://open.douyin.com/oauth/get_qrcode/?${new URLSearchParams(qrParams())}`);
            const data = result.data?.data;
            if (!data?.token || !data?.qrcode)
                throw new QishuiError('invalid-response', 'QR code is missing');
            if (mine !== generation)
                throw new QishuiError('unavailable', 'Sign-in was cancelled');
            const key = randomHex(16);
            pending.set(key, { token: data.token, qr: `data:image/png;base64,${data.qrcode}`, expires: Date.now() + 300000 });
            console.info('[Qishui] Sign-in QR created');
            return { key };
        }
        case 'login_qr_create': {
            cleanPending();
            const value = pending.get(raw.key);
            if (!value)
                throw new QishuiError('unavailable', 'QR session expired');
            return { imageUrl: value.qr };
        }
        case 'login_qr_check': {
            cleanPending();
            const value = pending.get(raw.key);
            if (!value)
                return { state: 'expired' };
            if (value.task)
                return value.task;
            const revision = generation;
            const run = async () => {
                const result = await http(`https://open.douyin.com/oauth/check_qrcode/?${new URLSearchParams({
                    ...qrParams(), token: value.token, timestamp: String(Date.now()),
                })}`);
                const data = result.data?.data;
                if (!data || !['new', 'scanned', 'confirmed', 'expired', 'refused'].includes(data.status)) {
                    throw new QishuiError('invalid-response', 'Invalid QR status');
                }
                if (data.status !== 'confirmed') {
                    if (['expired', 'refused'].includes(data.status))
                        pending.delete(raw.key);
                    if (data.status === 'refused')
                        return { state: 'error', failure: { case: 'refused' } };
                    return { state: data.status === 'new' ? 'waiting' : data.status };
                }
                const code = data.code || data.auth_code;
                if (!code)
                    throw new QishuiError('invalid-response', 'QR authorization code is missing');
                const access = await withStage('access-code', () => http(query(`https://open.qishui.com${PREFIX}/access/code/get/`, { auth_code: code })));
                const guard = await createGuard();
                const credential = await ticket(access.data?.data || access.data, guard, deviceId());
                const user = await getUser(credential);
                if (pending.get(raw.key) !== value || generation !== revision)
                    return { state: 'expired' };
                writeSession({ ...credential, userId: user.id, user });
                pending.delete(raw.key);
                clearQishuiFeed();
                console.info('[Qishui] Sign-in confirmed; account stored', { vip: user.isVip });
                return { state: 'confirmed' };
            };
            value.task = run().finally(() => { value.task = undefined; });
            return value.task;
        }
        case 'login_qr_cancel':
            pending.delete(raw.key);
            return null;
        case 'login_status':
            return await loginStatus();
        case 'logout':
            generation += 1;
            pending.clear();
            clearSession();
            clearQishuiFeed();
            console.info('[Qishui] Signed out');
            return null;
        case 'search': {
            if (typeof raw.query !== 'string')
                throw new QishuiError('invalid-response', 'Invalid Qishui search');
            if (!raw.query.trim())
                return { items: [], hasMore: false, nextOffset: offset };
            return await search(raw.query.trim(), 'track', limit, offset);
        }
        case 'search_playlists': {
            if (typeof raw.query !== 'string')
                throw new QishuiError('invalid-response', 'Invalid Qishui search');
            if (!raw.query.trim())
                return { items: [], hasMore: false, nextOffset: offset };
            return await search(raw.query.trim(), 'playlist', limit, offset);
        }
        case 'song_detail':
            return publicTrack(await trackById(id));
        case 'media_details': {
            // 批量补全：最近播放/收藏列表里有些条目只带 base_info，没有封面等展示字段，
            // 用 /media/mget/ 一次取回同一批（一次最多 100 首）。
            if (!Array.isArray(raw.ids) || !raw.ids.length) {
                throw new QishuiError('invalid-response', 'Qishui media IDs are required');
            }
            const ids = [...new Set(raw.ids.map(mediaIdOf))].slice(0, MAX_BATCH);
            const data = await platform('/media/mget/', { media_list: ids.map(trackRef), card_context: {} });
            const media = [data.media_list, data.media].find(Array.isArray) ?? [];
            return media.filter((item) => item?.track_entity).map((item) => publicTrack(item));
        }
        case 'lyrics': {
            const track = await trackById(id);
            return track.display_info?.lyric_info || {};
        }
        case 'audio': {
            if (!['standard', 'high', 'lossless', 'hires'].includes(raw.quality)) {
                throw new QishuiError('invalid-response', 'Invalid Qishui audio quality');
            }
            return await resolveAudio(await trackById(id), raw.quality);
        }
        case 'playlist_tracks':
        case 'playlist_detail': {
            const page = await playlistPage(id, typeof raw.ownerId === 'string' ? raw.ownerId : '', limit, offset);
            return operation === 'playlist_detail' ? page.collection ?? null : {
                items: page.items, hasMore: page.hasMore, nextOffset: page.nextOffset,
            };
        }
        case 'artist_detail':
            return (await platform('/artist/detail/', { artist_id: id })).artist_info;
        case 'artist_songs': {
            const data = await platform('/artist/tracks/', { artist_id: id, count: 50, cursor: '0' });
            const tracks = arrayOf(data.tracks, 'artist tracks').map(publicTrack);
            return {
                items: tracks.slice(offset, offset + limit),
                total: tracks.length,
                nextOffset: Math.min(offset + limit, tracks.length),
                hasMore: offset + limit < tracks.length,
            };
        }
        case 'liked_songs': {
            const data = await likedSongs();
            return { ids: data.ids, whole: data.whole };
        }
        case 'like_song': {
            const trackId = idOf(raw.id);
            if (typeof raw.liked !== 'boolean')
                throw new QishuiError('invalid-response', 'Invalid Qishui boolean');
            if (raw.liked) {
                await command('/media/collect/', { media: [trackRef(trackId)], scene: '', collect_action: 1 });
            }
            else {
                await command('/media/cancel/collect/', { media: [trackRef(trackId)] });
            }
            return null;
        }
        case 'playlist_add_tracks':
        case 'playlist_remove_tracks': {
            if (!Array.isArray(raw.ids) || !raw.ids.length)
                throw new QishuiError('invalid-response', 'Qishui media IDs are required');
            if (raw.ids.length > MAX_BATCH)
                throw new QishuiError('unsupported', `Select at most ${MAX_BATCH} tracks per operation`);
            const ids = [...new Set(raw.ids.map(mediaIdOf))];
            const path = operation === 'playlist_add_tracks' ? '/playlist/append/' : '/playlist/delete/';
            await command(path, { playlist_id: id, media: ids.map(trackRef) });
            return null;
        }
        case 'recent_played':
            return await recentPlayed(limit, offset);
        case 'user_playlists':
            return await userPlaylists(limit, offset);
        case 'feed_modes':
            return await feedModes();
        case 'feed':
            return await feedSongs({
                scene: raw.scene,
                preference: raw.preference,
                queue: raw.queue === 'explore' ? 'explore' : 'fm',
                first: raw.first === true,
                count: limit,
            });
        case 'check_tracks':
            return await checkTracks(raw.ids);
        case 'dislike_song': {
            const trackId = idOf(raw.id);
            if (typeof raw.disliked !== 'boolean')
                throw new QishuiError('invalid-response', 'Invalid Qishui dislike');
            await command('/dislike/', {
                item_id: trackId, author_ids: [], dislike_item_type: 'track',
                action_type: raw.disliked ? 0 : 1,
            });
            return null;
        }
        default:
            throw new QishuiError('unsupported', 'Unsupported Qishui operation');
    }
};

