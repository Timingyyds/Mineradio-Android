// public/js/ported/ref/qq-mobile-qr.js
// QQ 音乐「客户端扫码」通道（一码三用）：二维码由 musicu 的 CreateQRCode 生成，
// 扫码/确认事件由 mu.y.qq.com 的 MQTT 推送，全程不跳网页。
//
// 协议来自参考实现（qqMqtt.ts + QQMusicApi login.py）：
//   1. POST musicu  music.login.LoginServer / CreateQRCode  → {qrcode(图片 dataURI), qrcodeID}
//   2. wss://mu.y.qq.com/ws/handshake  MQTT 3.1.1：
//        CONNECT 属性 tmeAppID=qqmusic / business=management / hashTag=qrcodeID /
//        clientTag=management.user / userID=qrcodeID
//        订阅 management.qrcode_login/<qrcodeID>（属性 authorization=tmelogin / pubsub=unicast）
//   3. 推送消息 type = scanned / canceled / timeout / loginFailed / cookies
//   4. 收到 cookies 里的 qqmusic_uin + qqmusic_key 后，
//      POST musicu music.login.LoginServer / Login {musicid, qrCodeID, token} 换正式凭证

const MUSICU_URL = 'https://u.y.qq.com/cgi-bin/musicu.fcg';
const MQTT_HOST = 'mu.y.qq.com';
const MQTT_PATH = '/ws/handshake';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36';

const enc = new TextEncoder();

const varint = (n) => {
  const out = [];
  let value = n;
  do {
    let byte = value % 128;
    value = Math.floor(value / 128);
    if (value > 0) byte |= 0x80;
    out.push(byte);
  } while (value > 0);
  return out;
};
const u16 = (n) => [(n >> 8) & 0xff, n & 0xff];
const utf8 = (s) => {
  const bytes = Array.from(enc.encode(String(s)));
  return [...u16(bytes.length), ...bytes];
};
const flat = (arrs) => arrs.reduce((a, b) => a.concat(b), []);

const buildProps = (auth, pairs) => {
  const chunks = [];
  if (auth) chunks.push([0x15], utf8(auth));
  for (const [key, value] of pairs) chunks.push([0x26], utf8(key), utf8(value));
  const body = flat(chunks);
  return [...varint(body.length), ...body];
};

function buildConnect(clientId, qrcodeId) {
  const body = flat([
    utf8('MQTT'), [0x05, 0x02], [0, 45],
    buildProps('pass', [
      ['tmeAppID', 'qqmusic'],
      ['business', 'management'],
      ['hashTag', qrcodeId],
      ['clientTag', 'management.user'],
      ['userID', qrcodeId],
    ]),
    utf8(clientId),
  ]);
  return new Uint8Array([0x10, ...varint(body.length), ...body]);
}

function buildSubscribe(qrcodeId) {
  const body = flat([
    [0, 1],
    buildProps(null, [['authorization', 'tmelogin'], ['pubsub', 'unicast']]),
    utf8(`management.qrcode_login/${qrcodeId}`),
    [0x00],
  ]);
  return new Uint8Array([0x82, ...varint(body.length), ...body]);
}

function readVarint(buf, at) {
  let value = 0;
  let mult = 1;
  let i = at;
  while (i < buf.length && i - at < 4) {
    const byte = buf[i];
    value += (byte & 0x7f) * mult;
    i += 1;
    if ((byte & 0x80) === 0) return { value, bytes: i - at };
    mult *= 128;
  }
  return null;
}

function readStr(buf, at) {
  const len = (buf[at] << 8) | buf[at + 1];
  return { value: new TextDecoder().decode(buf.subarray(at + 2, at + 2 + len)), next: at + 2 + len };
}

function parsePublish(packet, bodyStart) {
  let cursor = readStr(packet, bodyStart).next;
  const qos = (packet[0] >> 1) & 0x03;
  if (qos > 0) cursor += 2;
  const plen = readVarint(packet, cursor);
  if (!plen) return { type: '', payload: null };
  let c = cursor + plen.bytes;
  const end = c + plen.value;
  let type = '';
  while (c < end) {
    const id = packet[c];
    c += 1;
    if (id === 0x26) {
      const k = readStr(packet, c);
      const v = readStr(packet, k.next);
      c = v.next;
      if (k.value === 'type') type = v.value;
    } else if ([0x1c, 0x1f, 0x03, 0x08, 0x12, 0x15, 0x1a].includes(id)) {
      c = readStr(packet, c).next;
    } else if ([0x13, 0x21, 0x22, 0x23].includes(id)) {
      c += 2;
    } else if ([0x02, 0x11, 0x18, 0x27].includes(id)) {
      c += 4;
    } else if ([0x01, 0x17, 0x19].includes(id)) {
      c += 1;
    } else break;
  }
  const raw = new TextDecoder().decode(packet.subarray(end)).trim();
  let payload = null;
  try { payload = raw ? JSON.parse(raw) : null; } catch (_) { payload = null; }
  return { type, payload };
}

function parseConnack(packet, bodyStart) {
  const reason = packet[bodyStart + 1] ?? 0xff;
  let c = bodyStart + 2;
  let reference = '';
  const plen = readVarint(packet, c);
  if (plen) {
    const end = c + plen.bytes + plen.value;
    c += plen.bytes;
    while (c < end) {
      const id = packet[c];
      c += 1;
      if (id === 0x1c) {
        const v = readStr(packet, c);
        reference = v.value;
        c = v.next;
      } else if (id === 0x26) {
        const k = readStr(packet, c);
        const v = readStr(packet, k.next);
        c = v.next;
      } else if ([0x03, 0x08, 0x12, 0x15, 0x1a, 0x1f].includes(id)) {
        c = readStr(packet, c).next;
      } else if ([0x13, 0x21, 0x22, 0x23].includes(id)) {
        c += 2;
      } else if ([0x02, 0x11, 0x18, 0x27].includes(id)) {
        c += 4;
      } else if ([0x01, 0x17, 0x19].includes(id)) {
        c += 1;
      } else break;
    }
  }
  return { reason, reference };
}

function redirectPath(path, reference) {
  const parts = path.split('/');
  parts[parts.length - 1] = reference;
  return parts.join('/');
}

/** 每个 qrcodeID 的 MQTT 会话状态。 */
const sessions = new Map();

async function musicu(payload) {
  const resp = await fetch(`${MUSICU_URL}?format=json`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json;charset=UTF-8',
      Referer: 'https://y.qq.com/',
      'User-Agent': UA,
    },
    body: JSON.stringify(payload),
  });
  return resp.json();
}

function openMqtt(qrcodeId, state) {
  let path = MQTT_PATH;
  let reconnectCount = 0;
  const attempt = (round) => {
    if (sessions.get(qrcodeId) !== state) return;
    state.attempts = (state.attempts || 0) + 1;
    if (state.attempts > 8) { state.error = 'mqtt 重连次数过多'; return; }
    const ws = new WebSocket(`wss://${MQTT_HOST}${path}`, 'mqtt');
    ws.binaryType = 'arraybuffer';
    let buf = new Uint8Array(0);
    let subscribed = false;
    let settled = false;
    let pingTimer = null;
    const clearPing = () => { if (pingTimer) { clearInterval(pingTimer); pingTimer = null; } };
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      try { ws.close(); } catch (_) { }
      state.error = 'mqtt timeout';
    }, 15000);
    ws.onopen = () => {
      // 连上就算可用：CONNACK 之前的短暂窗口不该让界面退回「正在连接」。
      state.connected = true;
      try { ws.send(buildConnect(`${Date.now()}${Math.floor(Math.random() * 9000) + 1000}`, qrcodeId)); } catch (_) { }
    };
    ws.onmessage = (event) => {
      const chunk = new Uint8Array(event.data);
      buf = new Uint8Array([...Array.from(buf), ...Array.from(chunk)]);
      let cursor = 0;
      while (cursor < buf.length) {
        const r = readVarint(buf, cursor + 1);
        if (!r) break;
        const end = cursor + 1 + r.bytes + r.value;
        if (end > buf.length) break;
        const packet = buf.subarray(cursor, end);
        cursor = end;
        const kind = packet[0] >> 4;
        if (kind === 2 && !settled) {
          const { reason, reference } = parseConnack(packet, 1 + r.bytes);
          settled = true;
          clearTimeout(timer);
          if (reason === 0) {
            try { ws.send(buildSubscribe(qrcodeId)); subscribed = true; } catch (_) { }
            state.ws = ws;
            state.connected = true;
            // MQTT keep-alive 45s：必须定期 PINGREQ，否则服务端会直接断链。
            clearPing();
            pingTimer = setInterval(() => {
              try { ws.send(new Uint8Array([0xc0, 0x00])); } catch (_) { clearPing(); }
            }, 20000);
          } else if ((reason === 0x9c || reason === 0x9d) && reference && round < 3) {
            try { ws.close(); } catch (_) { }
            path = redirectPath(path, reference);
            state.connected = false;
            attempt(round + 1);
          } else {
            try { ws.close(); } catch (_) { }
            state.error = `mqtt connack 0x${reason.toString(16)}`;
          }
        } else if (kind === 3 && subscribed) {
          let parsed = { type: '', payload: null };
          try { parsed = parsePublish(packet, 1 + r.bytes); } catch (_) { }
          handleEvent(qrcodeId, state, parsed.type, parsed.payload);
        }
      }
      buf = buf.subarray(cursor);
    };
    ws.onerror = () => { state.error = state.error || 'mqtt ws error'; };
    ws.onclose = () => {
      clearPing();
      if (state.ws === ws) { state.ws = null; state.connected = false; }
      // 还在等扫码时断线就自动重连：否则界面会一直停在「正在连接扫码服务」。
      if (sessions.get(qrcodeId) === state
        && (state.stage === 'waiting' || state.stage === 'scanned')) {
        reconnectCount += 1;
        if (reconnectCount <= 5) setTimeout(() => attempt(0), 1200);
      }
    };
  };
  attempt(0);
}

function handleEvent(qrcodeId, state, type, payload) {
  if (type === 'scanned') { state.stage = 'scanned'; return; }
  if (type === 'canceled') { state.stage = 'canceled'; closeSession(qrcodeId); return; }
  if (type === 'timeout') { state.stage = 'expired'; closeSession(qrcodeId); return; }
  if (type === 'loginFailed') { state.stage = 'failed'; state.error = 'QQ 登录失败'; closeSession(qrcodeId); return; }
  if (type !== 'cookies') return;
  const cookies = (payload && payload.cookies) || {};
  const uin = cookies.qqmusic_uin && cookies.qqmusic_uin.value;
  const key = cookies.qqmusic_key && cookies.qqmusic_key.value;
  if (uin && key) {
    state.stage = 'cookies';
    state.uin = String(uin);
    state.key = String(key);
  }
}

function closeSession(qrcodeId) {
  const state = sessions.get(qrcodeId);
  if (state && state.ws) {
    try { state.ws.close(); } catch (_) { }
  }
  if (state) state.ws = null;
}

/** 取二维码：CreateQRCode 直接返回图片 dataURI 与 qrcodeID，同时后台连上 MQTT 等扫码事件。 */
export async function qqMobileCreateQr() {
  const json = await musicu({
    comm: { ct: 23, cv: 0 },
    req: {
      module: 'music.login.LoginServer',
      method: 'CreateQRCode',
      param: { tmeAppID: 'qqmusic', ct: 11, cv: 14090008 },
    },
  });
  const data = json && json.req && json.req.data;
  const img = String((data && data.qrcode) || '');
  const qrcodeId = String((data && data.qrcodeID) || '');
  if (!img || !qrcodeId) {
    const code = json && json.req && json.req.code;
    throw new Error(`QQ 二维码创建失败(code=${code})`);
  }
  for (const [id, old] of sessions) {
    if (Date.now() - old.at > 5 * 60 * 1000) {
      closeSession(id);
      sessions.delete(id);
    }
  }
  const state = { at: Date.now(), stage: 'waiting', ws: null, connected: false, error: '', uin: '', key: '' };
  sessions.set(qrcodeId, state);
  openMqtt(qrcodeId, state);
  return { provider: 'qq', channel: 'qqmusic', qrcodeId, qrimg: img, img };
}

async function exchangeMobile(qrcodeId, uin, key) {
  const param = { musicid: Number(uin), qrCodeID: qrcodeId, token: key };
  // QQ 音乐的 Login 接口对 comm 很挑：只给 tmeLoginType 会被判未登录（code=1000）。
  // 这里按「完整客户端 comm → 最小 comm → req_0 命名」三种形状依次试，命中哪个用哪个。
  const shapes = [
    { comm: { ct: 11, cv: 14090008, v: 14090008, tmeAppID: 'qqmusic', tmeLoginType: 6 }, key: 'req' },
    { comm: { tmeLoginType: 6 }, key: 'req' },
    { comm: { ct: 24, cv: 0 }, key: 'req_0' },
  ];
  let lastCode = '';
  let lastMsg = '';
  for (const shape of shapes) {
    const payload = {};
    payload[shape.key] = { module: 'music.login.LoginServer', method: 'Login', param };
    payload.comm = shape.comm;
    let json = null;
    try {
      json = await musicu(payload);
    } catch (_) {
      continue;
    }
    const block = json && (json.req || json.req_0);
    const data = block && block.data;
    if (data && data.musickey && data.musicid) {
      const musicid = String(data.musicid || uin);
      const loginType = Number(data.loginType || 2);
      const wx = loginType === 1;
      const pairs = [
        `login_type=${wx ? 2 : 1}`,
        `uin=${wx ? `o${musicid}` : musicid}`,
        `qqmusic_uin=${wx ? `o${musicid}` : musicid}`,
        `qm_keyst=${data.musickey}`,
        `qqmusic_key=${data.musickey}`,
        `tmeLoginType=${loginType}`,
        `str_musicid=${musicid}`,
      ];
      return {
        cookie: pairs.join('; '),
        nickname: String(data.nick || data.nickname || ''),
        avatar: String(data.logo || ''),
        userId: musicid,
        loginType,
      };
    }
    lastCode = String((block && block.code) ?? '');
    lastMsg = String((block && (block.msg || block.message)) || '');
  }
  throw new Error(`QQ 凭证换取失败(code=${lastCode || '?'}${lastMsg ? ' ' + lastMsg : ''})`);
}

/** 轮询：MQTT 是推送式的，这里只读会话状态；收到 cookies 就换正式凭证。 */
export async function qqMobileCheckQr(qrcodeId) {
  const state = sessions.get(String(qrcodeId || ''));
  if (!state) {
    return { provider: 'qq', code: 800, status: 'expired', message: '二维码已过期' };
  }
  if (state.stage === 'success' && state.result) {
    return Object.assign({ provider: 'qq', code: 0, status: 'ok', loggedIn: true, message: '登录成功' }, state.result);
  }
  if (state.stage === 'cookies' && !state.exchanging) {
    state.exchanging = true;
    try {
      const result = await exchangeMobile(String(qrcodeId), state.uin, state.key);
      state.result = result;
      state.stage = 'success';
      closeSession(String(qrcodeId));
      sessions.delete(String(qrcodeId));
      return Object.assign({ provider: 'qq', code: 0, status: 'ok', loggedIn: true, message: '登录成功' }, result);
    } catch (error) {
      state.exchanging = false;
      return {
        provider: 'qq', code: 801, status: 'wait',
        message: error && error.message ? error.message : 'QQ 凭证换取失败',
      };
    }
  }
  if (state.stage === 'scanned') {
    return { provider: 'qq', code: 802, status: 'scanned', message: '已扫码，请在手机确认' };
  }
  if (state.stage === 'canceled') {
    return { provider: 'qq', code: 803, status: 'cancelled', message: '已取消' };
  }
  if (state.stage === 'expired') {
    return { provider: 'qq', code: 800, status: 'expired', message: '二维码已过期' };
  }
  if (state.stage === 'failed') {
    return { provider: 'qq', code: 801, status: 'wait', message: state.error || 'QQ 登录失败' };
  }
  return {
    provider: 'qq', code: 801, status: 'wait',
    message: state.connected ? '等待扫码' : '正在连接扫码服务…',
  };
}
