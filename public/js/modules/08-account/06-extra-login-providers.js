// ============================================================
// 额外登录通道：波点音乐 / YouTube Music / Spotify
// - 三个平台都接在同一个登录窗口里（像其它平台一样是节点）
// - Spotify：PKCE OAuth，用原生「内置浏览器」打开授权页，回调里拿 code 换 token
// - YouTube Music：原生内置浏览器打开 music.youtube.com，登录完取 Cookie（SAPISID）
// - 波点音乐：官方接口出二维码，用波点 App 扫码确认
// 说明：这里只负责「登录 / 登录态 / 账号信息」，曲库与播放的接入是下一步。
// ============================================================

/*
 * 【2026-11-06 用户要求】先不显示 Spotify 登录入口。
 * 登录窗口里的 SP 节点就是这里动态注入的，去掉它就不会再出现 SP 这个登录方式。
 * 想恢复：把 'spotify' 加回数组即可（其余 SP 相关逻辑都保留着）。
 */
var EXTRA_LOGIN_PROVIDERS = ['bodian', 'ytm'];
// var EXTRA_LOGIN_PROVIDERS = ['bodian', 'ytm', 'spotify'];
var EXTRA_LOGIN_STORE_KEY = 'mineradio-extra-login-sessions-v1';

var EXTRA_LOGIN_META = {
  bodian: { short: 'BD', label: '波点音乐', sub: '波点 App 扫码', color: '#8f7bff', bg: '#0d0a1a' },
  ytm: { short: 'YT', label: 'YouTube Music', sub: '内置浏览器登录', color: '#ff5d5d', bg: '#170b0b' },
  spotify: { short: 'SP', label: 'Spotify', sub: '内置浏览器授权', color: '#1ed760', bg: '#06140a' }
};

var extraLoginBusy = false;
var extraBodianQrKey = '';
var extraBodianPollTimer = 0;

// ---------- 小工具 ----------

function extraLoginIsExtraProvider(provider) {
  return EXTRA_LOGIN_PROVIDERS.indexOf(String(provider || '')) >= 0;
}

function extraLoginSessionStore() {
  try {
    var parsed = JSON.parse(localStorage.getItem(EXTRA_LOGIN_STORE_KEY) || '{}');
    return (parsed && typeof parsed === 'object') ? parsed : {};
  } catch (e) { return {}; }
}

function extraLoginSession(provider) {
  var store = extraLoginSessionStore();
  return store && store[provider] ? store[provider] : null;
}

function extraLoginSaveSession(provider, session) {
  var store = extraLoginSessionStore();
  if (session) store[provider] = session;
  else delete store[provider];
  try { localStorage.setItem(EXTRA_LOGIN_STORE_KEY, JSON.stringify(store)); } catch (e) { /* 忽略 */ }
  extraLoginSyncNodes();
  return session;
}

function extraLoginNative() {
  try {
    var cap = window.Capacitor;
    if (!cap || typeof cap.isNativePlatform !== 'function' || !cap.isNativePlatform()) return null;
    var plugins = cap.Plugins || {};
    return plugins.FoliaNative || null;
  } catch (e) { return null; }
}

function extraLoginRandomHex(bytes) {
  var out = '';
  try {
    var buf = new Uint8Array(bytes);
    (window.crypto || {}).getRandomValues(buf);
    for (var i = 0; i < buf.length; i += 1) out += ('0' + buf[i].toString(16)).slice(-2);
    return out;
  } catch (e) {
    for (var j = 0; j < bytes * 2; j += 1) out += Math.floor(Math.random() * 16).toString(16);
    return out;
  }
}

function extraLoginBase64UrlFromBytes(bytes) {
  var binary = '';
  for (var i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function extraLoginBase64Url(text) {
  var bytes = new TextEncoder().encode(String(text || ''));
  return extraLoginBase64UrlFromBytes(bytes);
}

async function extraLoginSha256Challenge(verifier) {
  var subtle = (window.crypto || {}).subtle;
  if (!subtle || typeof subtle.digest !== 'function') throw new Error('这个 WebView 不支持 PKCE（缺少 crypto.subtle）');
  var data = new TextEncoder().encode(verifier);
  var digest = await subtle.digest('SHA-256', data);
  return extraLoginBase64UrlFromBytes(new Uint8Array(digest));
}

function extraLoginToast(text) {
  if (typeof showToast === 'function') showToast(text);
}

function extraLoginText(id) {
  var el = document.getElementById(id);
  return el ? el.textContent : '';
}

function extraLoginSetText(id, text) {
  var el = document.getElementById(id);
  if (el) el.textContent = text;
}

// ---------- 节点 UI ----------

function extraLoginProviderNode(provider) {
  return document.getElementById('login-provider-' + provider);
}

function extraLoginSyncNodes() {
  for (var i = 0; i < EXTRA_LOGIN_PROVIDERS.length; i += 1) {
    var provider = EXTRA_LOGIN_PROVIDERS[i];
    var node = extraLoginProviderNode(provider);
    if (!node) continue;
    var session = extraLoginSession(provider);
    var small = node.querySelector('small');
    if (small) {
      small.textContent = session
        ? ('已登录 · ' + (session.nickname || session.name || EXTRA_LOGIN_META[provider].label))
        : EXTRA_LOGIN_META[provider].sub;
    }
    // 没有头像时别留一个空的 <img>，继续保持原来的字母 logo 风格
    var logo = node.querySelector('.provider-logo');
    if (logo) {
      var avatar = session && session.avatar ? String(session.avatar) : '';
      var img = logo.querySelector('img');
      if (avatar) {
        if (!img) {
          logo.classList.add('has-avatar');
          logo.innerHTML = '<img src="' + avatar + '" alt="">';
        } else if (img.getAttribute('src') !== avatar) {
          img.setAttribute('src', avatar);
        }
      } else if (img) {
        logo.classList.remove('has-avatar');
        logo.textContent = EXTRA_LOGIN_META[provider].short;
      }
    }
    node.classList.toggle('extra-login-connected', !!session);
  }
}

function extraLoginInjectNodes() {
  var host = document.getElementById('login-platform-tabs');
  if (!host || document.getElementById('login-provider-spotify')) return;
  EXTRA_LOGIN_PROVIDERS.forEach(function (provider) {
    var meta = EXTRA_LOGIN_META[provider];
    var btn = document.createElement('button');
    btn.id = 'login-provider-' + provider;
    // 结构、类名与原有平台节点一致：样式完全沿用登录窗口里那套
    btn.className = 'workflow-node extra-login-node';
    btn.type = 'button';
    btn.setAttribute('data-login-provider', provider);
    btn.innerHTML = '<span class="provider-logo">' + meta.short + '</span>' +
      '<b>' + meta.label + '</b><small>' + meta.sub + '</small>' +
      '<span class="flow-port out" data-login-provider-output="' + provider + '" title="拖到 MR 接入口"></span>';
    btn.addEventListener('click', function () { extraLoginSelectProvider(provider); });
    host.appendChild(btn);
  });
  extraLoginSyncNodes();
}

function extraLoginSelectProvider(provider) {
  try { setLoginProvider(provider, true); } catch (e) { /* 忽略 */ }
  try { if (typeof updateLoginProviderUi === 'function') updateLoginProviderUi(); } catch (e) { /* 忽略 */ }
  extraLoginRenderProviderUi(provider);
}

// 供 updateLoginProviderUi 调用：返回 true 表示这个平台由本模块接管渲染
function extraLoginRenderProviderUi(provider) {
  var meta = EXTRA_LOGIN_META[provider];
  if (!meta) return false;
  try { if (typeof setLoginAuthDrawerOpen === 'function') setLoginAuthDrawerOpen(true); } catch (e) { /* 忽略 */ }
  extraLoginSetText('login-modal-title', meta.label);
  var desc = document.getElementById('login-modal-desc');
  if (desc) {
    if (provider === 'spotify') desc.innerHTML = '点击「开始登录」，用 <b>内置浏览器</b> 打开 Spotify 授权页；授权完成会自动回到 MR。';
    else if (provider === 'ytm') desc.innerHTML = '点击「开始登录」，用 <b>内置浏览器</b> 打开 YouTube Music；登录完成后关闭窗口即可。';
    else desc.innerHTML = '点击「开始登录」出二维码，用 <b>波点音乐 App</b> 扫码并在手机上确认。';
  }
  var session = extraLoginSession(provider);
  var statusText = session
    ? ('已登录：' + (session.nickname || session.name || '账号已保存'))
    : (provider === 'bodian' ? '点「开始登录」生成二维码' : '点「开始登录」打开内置浏览器');
  extraLoginSetText('qr-status', statusText);

  var shell = document.getElementById('qr-shell');
  var qrImg = document.getElementById('qr-img');
  var cookiePanel = document.getElementById('qq-cookie-panel');
  var cookieToggle = document.getElementById('qq-cookie-toggle-btn');
  var neteaseCard = document.getElementById('qq-web-login-card');
  if (cookiePanel) cookiePanel.style.display = 'none';
  if (cookieToggle) cookieToggle.style.display = 'none';
  if (neteaseCard) neteaseCard.style.display = 'none';
  if (shell) {
    shell.style.display = provider === 'bodian' ? '' : 'none';
    shell.classList.remove('qq-preview', 'netease-preview', 'web-login-preview');
  }
  if (qrImg) {
    if (provider === 'bodian') { qrImg.style.display = ''; }
    else { qrImg.style.display = 'none'; qrImg.removeAttribute('src'); }
  }
  var startBtn = document.getElementById('refresh-qr-btn');
  if (startBtn) startBtn.textContent = session ? '重新登录' : '开始登录';
  try { if (typeof updateLoginNodeGraphUi === 'function') updateLoginNodeGraphUi(); } catch (e) { /* 忽略 */ }
  extraLoginSyncNodes();
  return true;
}

// 供 refreshQr 调用：返回 true 表示已接管（不会再去刷网易云二维码）
function extraLoginStartProviderLogin(provider) {
  if (!EXTRA_LOGIN_META[provider]) return false;
  extraLoginRenderProviderUi(provider);
  void extraLoginStart(provider);
  return true;
}

// 供 connectLoginMode 调用（「开始登录」按钮的第一站）
function extraLoginStartFromMode(provider, mode) {
  if (!EXTRA_LOGIN_META[provider]) return false;
  if (mode === 'cookie') {
    extraLoginToast(EXTRA_LOGIN_META[provider].label + ' 不支持 Cookie 导入，使用浏览器/扫码登录');
    return true;
  }
  extraLoginStartProviderLogin(provider);
  return true;
}

// 供 platformStatus 调用
function extraLoginStatusFor(provider) {
  var session = extraLoginSession(provider);
  return {
    provider: provider,
    loggedIn: !!session,
    nickname: session ? (session.nickname || EXTRA_LOGIN_META[provider].label) : '',
    userId: session ? (session.userId || session.uid || '') : '',
    product: session ? session.product || '' : '',
    configured: true
  };
}

// ---------- Spotify（PKCE） ----------

var SPOTIFY_LOGIN_CLIENT_ID = '65b708073fc0480ea92a077233ca87bd';
var SPOTIFY_LOGIN_REDIRECT = 'http://127.0.0.1:5588/login';
var SPOTIFY_LOGIN_SCOPES = 'user-read-private user-read-email playlist-read-private playlist-read-collaborative user-library-read';

async function extraSpotifyLogin() {
  var plugin = extraLoginNative();
  if (!plugin || typeof plugin.openSpotifyLogin !== 'function') {
    extraLoginToast('这个构建没有内置浏览器登录窗口');
    return null;
  }
  var verifier = extraLoginRandomHex(48);
  var challenge = await extraLoginSha256Challenge(verifier);
  var authorizeUrl = 'https://accounts.spotify.com/authorize?' + [
    'response_type=code',
    'client_id=' + encodeURIComponent(SPOTIFY_LOGIN_CLIENT_ID),
    'redirect_uri=' + encodeURIComponent(SPOTIFY_LOGIN_REDIRECT),
    'scope=' + encodeURIComponent(SPOTIFY_LOGIN_SCOPES),
    'code_challenge_method=S256',
    'code_challenge=' + encodeURIComponent(challenge)
  ].join('&');
  var answer = await plugin.openSpotifyLogin({ url: authorizeUrl, redirectPrefix: SPOTIFY_LOGIN_REDIRECT });
  var redirect = String((answer && answer.url) || '');
  if (!redirect) { extraLoginToast('授权窗口已关闭，未拿到回调'); return null; }
  var params = {};
  try {
    var parsed = new URL(redirect);
    parsed.searchParams.forEach(function (value, key) { params[key] = value; });
  } catch (e) { /* 忽略 */ }
  if (params.error) { extraLoginToast('Spotify 授权失败：' + params.error); return null; }
  if (!params.code) { extraLoginToast('没有拿到授权码'); return null; }

  var tokenText = [
    'grant_type=authorization_code',
    'code=' + encodeURIComponent(params.code),
    'redirect_uri=' + encodeURIComponent(SPOTIFY_LOGIN_REDIRECT),
    'client_id=' + encodeURIComponent(SPOTIFY_LOGIN_CLIENT_ID),
    'code_verifier=' + encodeURIComponent(verifier)
  ].join('&');
  var tokenRes = await fetch('https://accounts.spotify.com/api/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: tokenText
  });
  var tokenJson = await tokenRes.json().catch(function () { return null; });
  if (!tokenJson || !tokenJson.access_token) {
    extraLoginToast('Spotify 换取 token 失败');
    return null;
  }
  var session = {
    provider: 'spotify',
    accessToken: tokenJson.access_token,
    refreshToken: tokenJson.refresh_token || '',
    expiresAt: Date.now() + (Number(tokenJson.expires_in) || 3600) * 1000,
    nickname: 'Spotify',
    savedAt: Date.now()
  };
  try {
    var meRes = await fetch('https://api.spotify.com/v1/me', { headers: { Authorization: 'Bearer ' + session.accessToken } });
    var me = await meRes.json().catch(function () { return null; });
    if (me && (me.display_name || me.id)) {
      session.nickname = me.display_name || me.id;
      session.userId = me.id || '';
      session.product = me.product || '';
      var images = Array.isArray(me.images) ? me.images : [];
      if (images.length && images[0] && images[0].url) session.avatar = String(images[0].url);
    }
  } catch (e) { /* 拿不到名字不影响登录 */ }
  extraLoginSaveSession('spotify', session);
  if (window.MRRuntimeLog) window.MRRuntimeLog.push('info', ['SP 登录存档：nickname=' + (session.nickname || '(空)') + ' avatar=' + (session.avatar ? '有' : '无')], 'login');
  extraLoginToast('Spotify 已登录：' + session.nickname);
  extraLoginAfterLogin('spotify');
  return session;
}

// ---------- YouTube Music（Cookie） ----------

function extraYtmCookieHasSession(cookie) {
  return /(?:^|;\s*)SAPISID=[^;]+/.test(cookie) || /(?:^|;\s*)__Secure-3PAPISID=[^;]+/.test(cookie);
}

// 照 Folia 的 readYtmAccount：调 InnerTube 的 account/accounts_list 拿昵称/头像/频道名。
// accountName 是节点对象（runs/simpleText），必须走文本提取，否则会显示成 [object Object]。
async function extraYtmAccount(cookie) {
  var sapisid = (String(cookie || '').match(/(?:^|;\s*)(?:__Secure-3PAPISID|SAPISID)=([^;]+)/) || [])[1] || '';
  var clientVersion = (function () {
    var now = new Date();
    var stamp = now.getUTCFullYear() + ('0' + (now.getUTCMonth() + 1)).slice(-2) + ('0' + now.getUTCDate()).slice(-2);
    return '1.' + stamp + '.01.00';
  })();
  var origin = 'https://music.youtube.com';
  var headers = {
    'Content-Type': 'application/json',
    'X-YouTube-Client-Name': '67',
    'X-YouTube-Client-Version': clientVersion,
    'X-Origin': origin,
    'Origin': origin,
    'Referer': origin + '/',
    'Cookie': cookie
  };
  if (sapisid) {
    var timestamp = Math.floor(Date.now() / 1000);
    var digest = await extraLoginSha1Hex(timestamp + ' ' + sapisid + ' ' + origin);
    headers['Authorization'] = 'SAPISIDHASH ' + timestamp + '_' + digest;
    headers['X-Goog-AuthUser'] = '0';
  }
  var body = {
    context: {
      client: {
        hl: 'zh-CN', gl: 'US', clientName: 'WEB_REMIX', clientVersion: clientVersion,
        userAgent: String(navigator.userAgent || '')
      }
    }
  };
  var answer = await extraLoginHttpPost(origin + '/youtubei/v1/account/accounts_list?prettyPrint=false&alt=json', headers, JSON.stringify(body));
  var json = null;
  try { json = JSON.parse(answer.body || ''); } catch (e) { json = null; }
  if (!json) {
    if (window.MRRuntimeLog) window.MRRuntimeLog.push('warn', ['YT accounts_list 返回无法解析（HTTP ' + answer.status + '）'], 'login');
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
    if (window.MRRuntimeLog) window.MRRuntimeLog.push('warn', ['YT accounts_list 里没有账号节点（HTTP ' + answer.status + '）'], 'login');
    return null;
  }
  var account = accounts.filter(function (item) { return item.isSelected === true; })[0] || accounts[0];
  var name = extraLoginTextOf(account.accountName);
  var thumbs = (account.accountPhoto && account.accountPhoto.thumbnails) || [];
  var avatar = thumbs.length ? String(thumbs[thumbs.length - 1].url || '') : '';
  var handle = extraLoginTextOf(account.channelHandle);
  if (window.MRRuntimeLog) {
    window.MRRuntimeLog.push('info', ['YT 账号：name=' + (name || '(空)') + ' handle=' + (handle || '(空)') + ' avatar=' + (avatar ? '有' : '无') + ' 账号数=' + accounts.length], 'login');
  }
  return { name: name || handle || '', avatar: avatar, handle: handle };
}

// 原生桥 POST（JSON）
async function extraLoginHttpPost(url, headers, bodyText) {
  var bridge = window.__mrBridgeRequest;
  if (typeof bridge === 'function') {
    return await bridge({ url: url, method: 'POST', headers: headers, body: bodyText, timeoutMs: 20000, omitCookies: true });
  }
  var response = await fetch(url, { method: 'POST', headers: headers, body: bodyText });
  return { status: response.status, body: await response.text() };
}

async function extraYtmLogin() {
  var plugin = extraLoginNative();
  if (!plugin || typeof plugin.openWebLogin !== 'function') {
    extraLoginToast('这个构建没有内置浏览器登录窗口');
    return null;
  }
  var answer = await plugin.openWebLogin({ url: 'https://music.youtube.com/', successHost: 'music.youtube.com' });
  var cookie = String((answer && answer.cookie) || '');
  if (!cookie) { extraLoginToast('没有拿到登录 Cookie（可能没登录就关掉了窗口）'); return null; }
  if (!extraYtmCookieHasSession(cookie)) { extraLoginToast('Cookie 里没有 Google 登录态（SAPISID）'); return null; }
  var session = { provider: 'ytm', cookie: cookie, nickname: 'YouTube Music', savedAt: Date.now() };
  try {
    var account = await extraYtmAccount(cookie);
    if (account) {
      var accountName = extraLoginTextOf(account.name);
      if (accountName) session.nickname = accountName;
      if (account.avatar) session.avatar = extraLoginTextOf(account.avatar);
    }
  } catch (e) { /* 拿不到名字不影响登录 */ }
  extraLoginSaveSession('ytm', session);
  if (window.MRRuntimeLog) window.MRRuntimeLog.push('info', ['YT 登录存档：nickname=' + (session.nickname || '(空)') + ' avatar=' + (session.avatar ? '有' : '无') + ' cookie=' + String(session.cookie || '').length + '字符'], 'login');
  extraLoginToast('YouTube Music 已登录' + (session.nickname && session.nickname !== 'YouTube Music' ? ('：' + session.nickname) : ''));
  extraLoginAfterLogin('ytm');
  return session;
}

// ---------- 波点音乐（扫码） ----------

function extraMd5(text) {
  // 精简 MD5（RFC 1321），只用于波点接口签名
  function rotl(x, c) { return ((x << c) | (x >>> (32 - c))) >>> 0; }
  function add(a, b) { return (a + b) >>> 0; }
  var s = [
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
  ];
  var K = [];
  for (var i = 0; i < 64; i += 1) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 0x100000000) >>> 0;
  var bytes = new TextEncoder().encode(String(text || ''));
  var msg = Array.prototype.slice.call(bytes);
  var bitLen = msg.length * 8;
  msg.push(0x80);
  while (msg.length % 64 !== 56) msg.push(0);
  var lenBytes = new Uint8Array(8);
  for (var b = 0; b < 8; b += 1) {
    // JS 的位运算移位量按 32 取模，b>=4 必须用除法算高 32 位
    lenBytes[b] = b < 4 ? ((bitLen >>> (8 * b)) & 0xFF) : (Math.floor(bitLen / Math.pow(2, 8 * b)) & 0xFF);
  }
  msg = msg.concat(Array.prototype.slice.call(lenBytes));
  var a0 = 0x67452301, b0 = 0xEFCDAB89, c0 = 0x98BADCFE, d0 = 0x10325476;
  for (var off = 0; off < msg.length; off += 64) {
    var M = [];
    for (var w = 0; w < 16; w += 1) {
      M[w] = ((msg[off + w * 4]) | (msg[off + w * 4 + 1] << 8) | (msg[off + w * 4 + 2] << 16) | (msg[off + w * 4 + 3] << 24)) >>> 0;
    }
    var A = a0, B = b0, C = c0, D = d0;
    for (var i2 = 0; i2 < 64; i2 += 1) {
      var F, g;
      if (i2 < 16) { F = (B & C) | (~B & D); g = i2; }
      else if (i2 < 32) { F = (D & B) | (~D & C); g = (5 * i2 + 1) % 16; }
      else if (i2 < 48) { F = B ^ C ^ D; g = (3 * i2 + 5) % 16; }
      else { F = C ^ (B | ~D); g = (7 * i2) % 16; }
      F = add(add(add(F, A), K[i2]), M[g]);
      A = D; D = C; C = B;
      B = add(B, rotl(F, s[i2]));
    }
    a0 = add(a0, A); b0 = add(b0, B); c0 = add(c0, C); d0 = add(d0, D);
  }
  function hex(n) {
    var out = '';
    for (var i3 = 0; i3 < 4; i3 += 1) out += ('0' + ((n >>> (8 * i3)) & 0xFF).toString(16)).slice(-2);
    return out;
  }
  return hex(a0) + hex(b0) + hex(c0) + hex(d0);
}

function extraBodianDeviceId() {
  var key = 'mineradio-bodian-device-id';
  var saved = '';
  try { saved = localStorage.getItem(key) || ''; } catch (e) { saved = ''; }
  if (saved) return saved;
  var id = extraLoginRandomHex(16);
  try { localStorage.setItem(key, id); } catch (e) { /* 忽略 */ }
  return id;
}

function extraBodianSign(path, query, body) {
  var seed = 'kuwotest' + Array.from(new URLSearchParams(query).toString())
    .filter(function (ch) { return /[a-z0-9]/i.test(ch); }).sort().join('');
  if (body) seed += extraMd5(body + 'kuwotest');
  return extraMd5(seed + path);
}

async function extraBodianCall(path, options) {
  options = options || {};
  var deviceId = extraBodianDeviceId();
  var session = options.anonymous ? null : extraBodianSessionRaw();
  var query = Object.assign({}, options.params || {}, {
    uid: String((session && session.uid) || '-1'),
    token: (session && session.token) || ''
  });
  var bodyText = options.body === undefined ? '' : JSON.stringify(options.body);
  if (options.signed) {
    query.timestamp = String(Date.now());
    query.sign = extraBodianSign(path, query, bodyText);
  }
  var url = 'https://bd-api.kuwo.cn' + path;
  var search = new URLSearchParams();
  Object.keys(query).forEach(function (key) { search.set(key, String(query[key])); });
  var fullUrl = url + (search.toString() ? ('?' + search.toString()) : '');
  var headers = {
    'User-Agent': 'Dart/3.3 (dart:io)', plat: 'win', channel: 'W1', ver: '1.1.7',
    svrver: '13', 'api-ver': 'application/json', brand: 'Windows', net: 'wifi',
    devid: deviceId, qimei36: deviceId, 'Content-Type': 'application/json'
  };
  if (session) { headers.uid = String(session.uid); headers.token = session.token; }
  var response = await fetch(fullUrl, {
    method: options.method || (bodyText ? 'POST' : 'GET'),
    headers: headers,
    body: bodyText || undefined
  });
  var json = await response.json().catch(function () { return null; });
  if (!json) throw new Error('波点返回了无法解析的内容');
  if (Number(json.code) !== 200) throw new Error('波点 rejected (' + json.code + ')');
  return json;
}

function extraBodianSessionRaw() {
  var session = extraLoginSession('bodian');
  return session ? { uid: session.uid, token: session.token } : null;
}

function extraBodianStopPoll() {
  if (extraBodianPollTimer) {
    clearInterval(extraBodianPollTimer);
    extraBodianPollTimer = 0;
  }
  extraBodianQrKey = '';
}

async function extraBodianLogin() {
  extraBodianStopPoll();
  extraLoginSetText('qr-status', '正在获取二维码…');
  var keyResponse = await extraBodianCall('/api/ucenter/login/qrCode', { signed: true, anonymous: true });
  var key = keyResponse && keyResponse.data && keyResponse.data.qrCode;
  if (!key) throw new Error('没有拿到波点二维码');
  extraBodianQrKey = key;
  var qrUrl = 'https://bodian-oia.kuwo.cn/bodian/download.html?pageName=login_pc&pt=3&id=' + encodeURIComponent(key);
  var img = document.getElementById('qr-img');
  if (img) {
    img.style.display = '';
    img.src = (window.MRQrcode && typeof window.MRQrcode.toDataUrl === 'function')
      ? window.MRQrcode.toDataUrl(qrUrl)
      : qrUrl;
  }
  extraLoginSetText('qr-status', '请用波点音乐 App 扫码并确认');
  await new Promise(function (resolve, reject) {
    var started = Date.now();
    extraBodianPollTimer = setInterval(async function () {
      if (!extraBodianQrKey) return;
      if (Date.now() - started > 5 * 60 * 1000) {
        extraBodianStopPoll();
        extraLoginSetText('qr-status', '二维码已过期，请重新生成');
        reject(new Error('expired'));
        return;
      }
      try {
        var statusResponse = await extraBodianCall('/api/ucenter/login/qrCodeStatus', {
          params: { qrCode: extraBodianQrKey }, signed: true, anonymous: true
        });
        var status = Number(statusResponse && statusResponse.data && statusResponse.data.status);
        if (status === 1) { extraLoginSetText('qr-status', '已扫码，等待在手机上确认…'); return; }
        if (status === 2) {
          extraBodianStopPoll();
          extraLoginSetText('qr-status', '二维码已失效，请重新生成');
          reject(new Error('expired'));
          return;
        }
        if (status !== 3) return;
        extraBodianStopPoll();
        var loginResponse = await extraBodianCall('/api/ucenter/users/login', {
          method: 'POST', signed: true, anonymous: true,
          body: { authType: 10, qrCode: key }
        });
        var login = loginResponse && loginResponse.data;
        var rawUser = (login && (login.userInfo || login.user)) || {};
        // 身份校验：所有出现的 id 必须一致且是合法数字，避免存进错账号
        var candidates = [login && login.id, login && login.uid, rawUser.id, rawUser.uid]
          .filter(function (v) { return v != null; })
          .map(function (v) { return String(v); });
        var uid = candidates[0] || '';
        var token = String((login && login.token) || '');
        if (!candidates.length || !/^[1-9]\d{0,19}$/.test(uid) || candidates.some(function (id) { return id !== uid; })) {
          reject(new Error('波点返回的账号身份异常')); return;
        }
        if (!token) { reject(new Error('波点没有返回登录态')); return; }
        var nickname = String(rawUser.nickname || rawUser.nickName || rawUser.userName || rawUser.name || '波点用户');
        var avatar = String(rawUser.headImg || rawUser.avatarUrl || rawUser.avatar || '');
        var session = {
          provider: 'bodian', uid: uid, token: token,
          nickname: nickname, avatar: avatar,
          user: { id: uid, nickname: nickname, avatarUrl: avatar },
          savedAt: Date.now()
        };
        if (window.__mrBodianApi && typeof window.__mrBodianApi.saveSession === 'function') {
          window.__mrBodianApi.saveSession(session);
        }
        extraLoginSaveSession('bodian', session);
        if (window.MRRuntimeLog) window.MRRuntimeLog.push('info', ['波点登录存档：nickname=' + session.nickname + ' avatar=' + (session.avatar ? '有' : '无') + ' uid=' + session.uid], 'login');
        extraLoginSetText('qr-status', '已登录：' + session.nickname);
        extraLoginToast('波点音乐已登录：' + session.nickname);
        extraLoginAfterLogin('bodian');
        resolve(session);
      } catch (e) { /* 轮询失败继续等 */ }
    }, 2000);
  });
}

// ---------- 入口：开始登录 ----------

// 退出登录：清掉三个平台的会话 + 界面状态 + 歌单
function extraLoginClearAllSessions() {
  try {
    var store = extraLoginSessionStore();
    EXTRA_LOGIN_PROVIDERS.forEach(function (provider) { delete store[provider]; });
    localStorage.setItem(EXTRA_LOGIN_STORE_KEY, JSON.stringify(store));
  } catch (e) { /* 忽略 */ }
  try {
    if (typeof bodianLoginStatus !== 'undefined') {
      bodianLoginStatus = { provider: 'bodian', loggedIn: false, configured: false, preview: false, nickname: '波点音乐', userId: '', avatar: '', vipType: 0, vipLevel: 'none', isVip: false, isSvip: false, playbackKeyReady: false, playbackMode: 'direct' };
    }
    if (typeof bodianPlaylists !== 'undefined') bodianPlaylists = [];
    if (typeof userPlaylists !== 'undefined' && Array.isArray(userPlaylists)) {
      userPlaylists = userPlaylists.filter(function (pl) { return EXTRA_LOGIN_PROVIDERS.indexOf(pl && pl.provider) < 0; });
    }
    if (typeof playlistCatalogRevision !== 'undefined') playlistCatalogRevision += 1;
    if (typeof renderUserBtn === 'function') renderUserBtn();
    if (typeof safeRenderQueuePanel === 'function') safeRenderQueuePanel('extra-login-logout', { scrollCurrent: false });
    if (typeof safeShelfRebuild === 'function') safeShelfRebuild('extra-login-logout');
  } catch (e) { /* 忽略 */ }
  extraLoginSyncNodes();
}

// 退出单个平台
function extraLoginClearSession(provider) {
  extraLoginSaveSession(provider, null);
  if (provider === 'bodian') {
    try {
      if (typeof bodianLoginStatus !== 'undefined') {
        bodianLoginStatus = Object.assign({}, bodianLoginStatus, { loggedIn: false, configured: false, nickname: '波点音乐', userId: '', avatar: '', playbackKeyReady: false });
      }
      if (typeof bodianPlaylists !== 'undefined') bodianPlaylists = [];
      if (typeof userPlaylists !== 'undefined' && Array.isArray(userPlaylists)) {
        userPlaylists = userPlaylists.filter(function (pl) { return pl && pl.provider !== 'bodian'; });
      }
      if (typeof playlistCatalogRevision !== 'undefined') playlistCatalogRevision += 1;
      if (typeof renderUserBtn === 'function') renderUserBtn();
      if (typeof safeShelfRebuild === 'function') safeShelfRebuild('bodian-logout');
    } catch (e) { /* 忽略 */ }
  }
  extraLoginSyncNodes();
}

// 登录后把状态/歌单/推荐都刷一遍，否则界面要等下一次轮询才认账号
function extraLoginAfterLogin(provider) {
  try {
    if (provider === 'bodian' && typeof refreshBodianLoginStatus === 'function') {
      // 校验登录态是否真能用（token 失效时首页/歌单会是空的）
      apiJson('/api/bodian/user/playlists?verify=' + Date.now()).then(function (r) {
        if (r && r.error) extraLoginToast('波点会话校验失败：' + r.error);
      }).catch(function (e) { extraLoginToast('波点会话校验失败：' + (e && e.message || e)); });
      refreshBodianLoginStatus().then(function () {
        if (typeof refreshUserPlaylists === 'function') refreshUserPlaylists(true);
        if (typeof hasAnyPlatformLogin === 'function' && hasAnyPlatformLogin()) {
          if (typeof loadHomeDiscover === 'function') loadHomeDiscover(true);
        }
      }).catch(function () { /* 忽略 */ });
    } else if (provider === 'spotify' && typeof refreshSpotifyLoginStatus === 'function') {
      refreshSpotifyLoginStatus().catch(function () {});
    } else if (provider === 'ytm') {
      if (typeof refreshYtmLoginStatus === 'function') {
        refreshYtmLoginStatus().then(function () {
          if (typeof refreshUserPlaylists === 'function') refreshUserPlaylists(true);
        }).catch(function () { /* 忽略 */ });
      } else if (typeof renderUserBtn === 'function') {
        renderUserBtn();
      }
    }
  } catch (e) { /* 忽略 */ }
  if (typeof extraLoginSyncNodes === 'function') extraLoginSyncNodes();
}

async function extraLoginStart(provider) {
  if (extraLoginBusy) return null;
  extraLoginBusy = true;
  try {
    var session = null;
    if (provider === 'spotify') session = await extraSpotifyLogin();
    else if (provider === 'ytm') session = await extraYtmLogin();
    else if (provider === 'bodian') session = await extraBodianLogin();
    if (session) {
      extraLoginSyncNodes();
      extraLoginRenderProviderUi(provider);
      if (typeof markLoginWorkflowConnected === 'function') {
        try { markLoginWorkflowConnected(provider); } catch (e) { /* 忽略 */ }
      }
    }
    return session;
  } catch (e) {
    extraLoginToast((EXTRA_LOGIN_META[provider] || {}).label + ' 登录失败：' + (e && e.message ? e.message : e));
    return null;
  } finally {
    extraLoginBusy = false;
  }
}

function extraLogoutProvider(provider) {
  if (!extraLoginIsExtraProvider(provider)) return false;
  extraLoginStopPoll();
  extraLoginSaveSession(provider, null);
  if (typeof markLoginWorkflowConnected === 'function') { /* 连接态由状态推导 */ }
  extraLoginToast((EXTRA_LOGIN_META[provider] || {}).label + ' 已退出');
  return true;
}

function extraLoginBoot() {
  if (!document.getElementById('login-platform-tabs')) return;
  // 连线和顺序逻辑也认得这三个平台
  try {
    if (typeof LOGIN_WORKFLOW_PROVIDERS !== 'undefined' && Array.isArray(LOGIN_WORKFLOW_PROVIDERS)) {
      EXTRA_LOGIN_PROVIDERS.forEach(function (provider) {
        if (LOGIN_WORKFLOW_PROVIDERS.indexOf(provider) < 0) LOGIN_WORKFLOW_PROVIDERS.push(provider);
      });
    }
  } catch (e) { /* 忽略 */ }
  extraLoginInjectNodes();
  extraLoginSyncNodes();
}

setTimeout(extraLoginBoot, 0);