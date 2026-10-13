'use strict';

// ====================================================================
// QR 扫码登录 providers（移植自 folia Android 的直连协议实现）
//  - QQ 音乐: ptqrshow -> ptqrlogin -> check_sig -> oauth2.0 authorize -> musicu QQLogin
//  - 酷狗: login-user.kugou.com /v2/qrcode -> /v2/get_userinfo_qrcode (长轮询)
// 通用二维码出图: /api/qrcode 由 server.js 提供（qrcode 包）
// ====================================================================

const crypto = require('crypto');
const http = require('http');
const https = require('https');

// 调试钩子：测试台/排查时注入 console.log 即可看到全链路数据
let _debug = null;
function setDebugLogger(fn) { _debug = typeof fn === 'function' ? fn : null; }
function dbg(label, data) {
  if (_debug) {
    try { _debug(label, data); } catch (_) {}
  }
}

const UA_CHROME = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

// ------------------------------------------------------------------
// 原始 HTTP 助手（手动重定向 + Set-Cookie 收集）
// ------------------------------------------------------------------
function rawRequest(targetUrl, opts) {
  opts = opts || {};
  return new Promise((resolve, reject) => {
    let u;
    try { u = new URL(targetUrl); } catch (e) { reject(e); return; }
    const lib = u.protocol === 'https:' ? https : http;
    const req = lib.request(u, {
      method: opts.method || 'GET',
      headers: opts.headers || {},
    }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => {
        resolve({
          status: response.statusCode || 0,
          headers: response.headers || {},
          location: response.headers.location || '',
          setCookies: response.headers['set-cookie'] || [],
          body: Buffer.concat(chunks),
        });
      });
    });
    req.setTimeout(Math.max(500, Number(opts.timeoutMs) || 12000), () => req.destroy(new Error('请求超时')));
    req.on('error', reject);
    if (opts.body) req.write(opts.body);
    req.end();
  });
}

function parseSetCookiePairs(setCookies) {
  const pairs = [];
  (Array.isArray(setCookies) ? setCookies : []).forEach((raw) => {
    const pair = String(raw || '').split(';')[0].trim();
    const eq = pair.indexOf('=');
    if (eq > 0 && pair.slice(eq + 1)) pairs.push(pair);
  });
  return pairs;
}

function cookiePairsToHeader(pairs) {
  return (pairs || []).filter(Boolean).join('; ');
}

function mergeCookiePairs(map, pairs) {
  (pairs || []).forEach((pair) => {
    const eq = String(pair).indexOf('=');
    if (eq <= 0) return;
    const name = pair.slice(0, eq).trim();
    if (name) map.set(name, pair.trim());
  });
  return map;
}

function headerFromCookieMap(map) {
  return Array.from(map.values()).join('; ');
}

function cookiePairsToObject(pairs) {
  const obj = {};
  (pairs || []).forEach((pair) => {
    const eq = String(pair).indexOf('=');
    if (eq <= 0) return;
    obj[pair.slice(0, eq).trim()] = pair.slice(eq + 1).trim();
  });
  return obj;
}

// ==================================================================
// QQ 音乐扫码登录
// ==================================================================
const QQ_PT_APPID = '716027609';
const QQ_PT_DAID = '383';
const QQ_PT_AID = '100497308';
const QQ_PT_U1 = 'https://graph.qq.com/oauth2.0/login_jump';
const QQ_AUTHORIZE_REDIRECT = 'https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/';

function qqHash33(qrsig) {
  let e = 0;
  const t = String(qrsig || '');
  for (let n = 0, o = t.length; n < o; n += 1) e += (e << 5) + t.charCodeAt(n);
  return 2147483647 & e;
}

function qqGetGtk(pSkey) {
  const str = String(pSkey || '');
  let hash = 5381;
  for (let i = 0, len = str.length; i < len; i += 1) hash += (hash << 5) + str.charCodeAt(i);
  return hash & 2147483647;
}

function qqGetGuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 3) | 8).toString(16);
  }).toUpperCase();
}

/** GET ptqrshow -> { img(base64 dataURL), qrsig, ptqrtoken } */
async function qqGetLoginQr() {
  const u = new URL('https://ssl.ptlogin2.qq.com/ptqrshow');
  u.searchParams.set('appid', QQ_PT_APPID);
  u.searchParams.set('e', '2');
  u.searchParams.set('l', 'M');
  u.searchParams.set('s', '3');
  u.searchParams.set('d', '72');
  u.searchParams.set('v', '4');
  u.searchParams.set('t', String(Math.random()));
  u.searchParams.set('daid', QQ_PT_DAID);
  u.searchParams.set('pt_3rd_aid', QQ_PT_AID);
  u.searchParams.set('u1', QQ_PT_U1);

  const resp = await rawRequest(u.toString(), {
    headers: {
      'User-Agent': UA_CHROME,
      Referer: 'https://xui.ptlogin2.qq.com/',
      Accept: 'image/avif,image/webp,image/apng,image/*,*/*;q=0.8',
    },
  });
  if (resp.status !== 200) throw new Error('QQ 登录二维码获取失败 (HTTP ' + resp.status + ')');
  const qrsig = parseSetCookiePairs(resp.setCookies)
    .find((pair) => pair.startsWith('qrsig='));
  if (!qrsig) throw new Error('QQ 登录二维码会话获取失败（qrsig 缺失）');
  const sig = qrsig.slice('qrsig='.length);
  return {
    img: 'data:image/png;base64,' + resp.body.toString('base64'),
    qrsig: sig,
    ptqrtoken: String(qqHash33(sig)),
  };
}

/** 换票阶段失败重试计数：连续失败到上限才转终态错误 */
const QQ_TICKET_EXCHANGE_MAX_FAILURES = 3;
const qqTicketExchangeFailures = new Map();
function countTicketExchangeFailure(qrsig) {
  const key = String(qrsig || '').slice(0, 64);
  const attempts = (qqTicketExchangeFailures.get(key) || 0) + 1;
  qqTicketExchangeFailures.delete(key);
  qqTicketExchangeFailures.set(key, attempts);
  if (qqTicketExchangeFailures.size > 32) qqTicketExchangeFailures.delete(qqTicketExchangeFailures.keys().next().value);
  return attempts;
}

function qqTicketExchangeFailure(qrsig, message) {
  const attempts = countTicketExchangeFailure(qrsig);
  if (attempts <= QQ_TICKET_EXCHANGE_MAX_FAILURES) {
    return { status: 'wait', code: 801, retryable: true, message, failureReason: message, ticketExchangeAttempts: attempts };
  }
  return { status: 'error', code: 0, retryable: false, message, failureReason: message, ticketExchangeAttempts: attempts };
}

/**
 * 轮询 QQ 扫码状态。
 * 返回 { status: 'wait'|'scanned'|'expired'|'ok'|'error', code, cookie?, uin?, nickname?, message? }
 */
async function qqCheckLoginQr(params) {
  const qrsig = String((params && params.qrsig) || '').trim();
  let ptqrtoken = String((params && params.ptqrtoken) || '').trim();
  if (!qrsig) return { status: 'expired', code: 65, message: '参数错误：缺少 qrsig' };
  if (!ptqrtoken) ptqrtoken = String(qqHash33(qrsig));

  const cookieMap = new Map();
  cookieMap.set('qrsig', 'qrsig=' + qrsig);

  const pollUrl = new URL('https://ssl.ptlogin2.qq.com/ptqrlogin');
  pollUrl.searchParams.set('u1', QQ_PT_U1);
  pollUrl.searchParams.set('ptqrtoken', ptqrtoken);
  pollUrl.searchParams.set('ptredirect', '0');
  pollUrl.searchParams.set('h', '1');
  pollUrl.searchParams.set('t', '1');
  pollUrl.searchParams.set('g', '1');
  pollUrl.searchParams.set('from_ui', '1');
  pollUrl.searchParams.set('ptlang', '2052');
  pollUrl.searchParams.set('action', '0-0-' + Date.now());
  pollUrl.searchParams.set('js_ver', '23111510');
  pollUrl.searchParams.set('js_type', '1');
  pollUrl.searchParams.set('login_sig', '');
  pollUrl.searchParams.set('pt_uistyle', '40');
  pollUrl.searchParams.set('aid', QQ_PT_APPID);
  pollUrl.searchParams.set('daid', QQ_PT_DAID);
  pollUrl.searchParams.set('pt_3rd_aid', QQ_PT_AID);

  let data = '';
  try {
    const resp = await rawRequest(pollUrl.toString(), {
      headers: {
        'User-Agent': UA_CHROME,
        Referer: 'https://xui.ptlogin2.qq.com/',
        Accept: '*/*',
        Cookie: 'qrsig=' + qrsig,
      },
    });
    data = resp.body.toString('utf8');
    mergeCookiePairs(cookieMap, parseSetCookiePairs(resp.setCookies));
  } catch (err) {
    return { status: 'wait', code: 66, message: '登录检查失败，将继续重试', error: err.message };
  }

  const expired = /已失效|已过期/.test(data) && !/未失效/.test(data);
  const scanned = /二维码认证中|扫描成功|已扫描/.test(data);
  const waiting = /二维码未失效|等待扫码|未失效/.test(data);
  const success = /登录成功|登陆成功/.test(data);

  if (!success) {
    dbg('qq:poll:no-success', { bodyHead: data.slice(0, 300) });
    if (expired) return { status: 'expired', code: 65, message: '二维码已失效，请刷新' };
    if (scanned) return { status: 'scanned', code: 67, message: '已扫码，请在手机确认' };
    return { status: 'wait', code: 66, message: '请用手机 QQ 扫码' };
  }

  // ---- 换票流程（check_sig -> authorize -> musicu） ----
  // ptuiCB('0','0','https://...','0','登录成功！','nick') —— URL 是第 3 个引号字段，
  // 必须用 [^']* 严格截断到第一个闭引号（贪婪匹配会把后面的中文字段吞进 URL，
  // 导致 Referer 头含非 ASCII 被 Node http 拒绝：Invalid character in header content）。
  const quotedUrlMatch = data.match(/'(https?:\/\/[^']+)'/);
  const bareUrlMatch = data.match(/https?:\/\/(?:ptlogin2|ssl\.ptlogin2)[^\s'",]+/);
  const rawCheckSigUrl = quotedUrlMatch && quotedUrlMatch[1]
    ? quotedUrlMatch[1]
    : (bareUrlMatch && bareUrlMatch[0]) || '';
  if (!rawCheckSigUrl) return qqTicketExchangeFailure(qrsig, '登录检查失败：未拿到 checkSigUrl');
  // 双保险：URL 只保留到合法字符为止（剥离任何混入的引号/逗号/非 ASCII）
  const checkSigUrl = rawCheckSigUrl.split(/[',;]/)[0].replace(/[^\x21-\x7e]/g, '');
  dbg('qq:check-sig-url', checkSigUrl);
  const checkSigUin = (() => {
    try { return (new URL(checkSigUrl).searchParams.get('uin') || '').replace(/\D/g, ''); } catch (_) { return ''; }
  })();

  // 1) check_sig（手动跟一跳收集 p_skey）
  try {
    let resp = await rawRequest(checkSigUrl, {
      redirectManual: true,
      headers: {
        'User-Agent': UA_CHROME,
        Referer: 'https://xui.ptlogin2.qq.com/',
        Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        Cookie: headerFromCookieMap(cookieMap),
      },
    }, 12000);
    mergeCookiePairs(cookieMap, parseSetCookiePairs(resp.setCookies));
    if (resp.status >= 300 && resp.status < 400 && resp.location) {
      const target = new URL(resp.location, checkSigUrl).toString();
      const followed = await rawRequest(target, {
        headers: {
          'User-Agent': UA_CHROME,
          Referer: checkSigUrl,
          Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
          Cookie: headerFromCookieMap(cookieMap),
        },
      }, 12000);
      mergeCookiePairs(cookieMap, parseSetCookiePairs(followed.setCookies));
    }
  } catch (err) {
    return qqTicketExchangeFailure(qrsig, 'check_sig 请求失败: ' + err.message);
  }

  const cookieObject = cookiePairsToObject(Array.from(cookieMap.values()));
  dbg('qq:after-check-sig', {
    status: undefined,
    cookieNames: Object.keys(cookieObject),
    hasPSkey: !!cookieObject.p_skey,
    hasUin: !!cookieObject.uin,
    hasSkey: !!cookieObject.skey,
  });
  const pSkey = cookieObject.p_skey || '';
  if (!pSkey) return qqTicketExchangeFailure(qrsig, '登录检查失败：缺少 p_skey');
  const gtk = qqGetGtk(pSkey);

  // 2) oauth2.0 authorize（手动追跳直到 Location 带 code）
  const form = new URLSearchParams();
  form.append('response_type', 'code');
  form.append('client_id', QQ_PT_AID);
  form.append('redirect_uri', QQ_AUTHORIZE_REDIRECT);
  form.append('scope', 'get_user_info,get_app_friends');
  form.append('state', 'state');
  form.append('switch', '');
  form.append('from_ptlogin', '1');
  form.append('src', '1');
  form.append('update_auth', '1');
  form.append('openapi', '1010_1030');
  form.append('g_tk', String(gtk));
  form.append('auth_time', String(Date.now()));
  form.append('ui', qqGetGuid());

  let location = '';
  try {
    let resp = await rawRequest('https://graph.qq.com/oauth2.0/authorize', {
      method: 'POST',
      headers: {
        'User-Agent': UA_CHROME,
        Referer: 'https://graph.qq.com/',
        Origin: 'https://graph.qq.com',
        'Content-Type': 'application/x-www-form-urlencoded',
        Cookie: headerFromCookieMap(cookieMap),
      },
      body: form.toString(),
    }, 12000);
    mergeCookiePairs(cookieMap, parseSetCookiePairs(resp.setCookies));
    location = resp.location;
    let hops = 0;
    while (resp.status >= 300 && resp.status < 400 && location && !/[?&]code=/.test(String(location)) && hops < 4) {
      hops += 1;
      const nextUrl = new URL(String(location), 'https://graph.qq.com/').toString();
      const hop = await rawRequest(nextUrl, {
        headers: {
          'User-Agent': UA_CHROME,
          Referer: 'https://graph.qq.com/',
          Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
          Cookie: headerFromCookieMap(cookieMap),
        },
      }, 12000);
      mergeCookiePairs(cookieMap, parseSetCookiePairs(hop.setCookies));
      resp = hop;
      location = hop.location;
      if (!/[?&]code=/.test(String(location))) {
        try {
          const bodyText = hop.body.toString('utf8');
          const bodyCode = bodyText.match(/[?&]code=([^&"'\s]+)/);
          if (bodyCode && bodyCode[1]) location = 'x?code=' + bodyCode[1];
        } catch (_) {}
      }
    }
  } catch (err) {
    return qqTicketExchangeFailure(qrsig, '授权请求失败: ' + err.message);
  }

  const authorizeOk = location && /[?&]code=/.test(String(location));
  dbg('qq:authorize-result', { location: String(location).slice(0, 200), hasCode: authorizeOk });
  if (!authorizeOk) return qqTicketExchangeFailure(qrsig, '授权响应异常，未返回 code');
  const code = decodeURIComponent(String(location).match(/[?&]code=([^&]+)/)[1]);

  // 3) musicu QQLogin 换 qm_keyst
  const fcgBody = JSON.stringify({
    comm: { g_tk: gtk, platform: 'yqq', ct: 24, cv: 0 },
    req: { module: 'QQConnectLogin.LoginServer', method: 'QQLogin', param: { code } },
  });
  try {
    const loginRes = await rawRequest('https://u.y.qq.com/cgi-bin/musicu.fcg', {
      method: 'POST',
      headers: {
        'User-Agent': UA_CHROME,
        Referer: 'https://y.qq.com/',
        'Content-Type': 'application/x-www-form-urlencoded',
        Origin: 'https://y.qq.com',
        Cookie: headerFromCookieMap(cookieMap),
      },
      body: fcgBody,
    }, 12000);
    mergeCookiePairs(cookieMap, parseSetCookiePairs(loginRes.setCookies));
    try {
      const json = JSON.parse(loginRes.body.toString('utf8'));
      dbg('qq:musicu-response', {
        status: loginRes.status,
        code: json && json.code,
        reqCode: json && json.req && json.req.code,
        dataKeys: json && json.req && json.req.data ? Object.keys(json.req.data) : null,
        hasMusickey: !!(json && json.req && json.req.data && json.req.data.musickey),
        setCookieNames: parseSetCookiePairs(loginRes.setCookies).map((p) => p.split('=')[0]),
      });
      const dataNode = json && json.req && json.req.data;
      if (dataNode && typeof dataNode === 'object') {
        if (dataNode.musickey) cookieMap.set('qm_keyst', 'qm_keyst=' + dataNode.musickey);
        if (dataNode.qqmusic_key) cookieMap.set('qqmusic_key', 'qqmusic_key=' + dataNode.qqmusic_key);
        const musicId = String(dataNode.musicid || dataNode.uin || '').replace(/\D/g, '');
        if (musicId && !cookieMap.has('uin') && !cookieMap.has('p_uin')) cookieMap.set('uin', 'uin=o' + musicId);
        if (musicId && !cookieMap.has('str_musicid')) cookieMap.set('str_musicid', 'str_musicid=' + musicId);
      }
    } catch (_) {}
  } catch (err) {
    return qqTicketExchangeFailure(qrsig, 'QQLogin 失败: ' + err.message);
  }

  cookieMap.delete('tmeLoginType');
  cookieMap.set('login_type', 'login_type=1');
  if (checkSigUin) {
    cookieMap.set('uin', 'uin=o' + checkSigUin);
    if (!cookieMap.has('qqmusic_uin')) cookieMap.set('qqmusic_uin', 'qqmusic_uin=o' + checkSigUin);
  }

  const finalObject = cookiePairsToObject(Array.from(cookieMap.values()));
  dbg('qq:final-session', {
    cookieNames: Object.keys(finalObject),
    uin: finalObject.uin,
    hasQmKeyst: !!(finalObject.qm_keyst || finalObject.qqmusic_key),
    loginType: finalObject.login_type,
  });
  if (!finalObject.qm_keyst && !finalObject.qqmusic_key) {
    return qqTicketExchangeFailure(qrsig, '登录成功但未拿到 qm_keyst');
  }
  qqTicketExchangeFailures.delete(String(qrsig).slice(0, 64));

  return {
    status: 'ok',
    code: 0,
    cookie: headerFromCookieMap(cookieMap),
    uin: checkSigUin || String(finalObject.uin || '').replace(/\D/g, ''),
    nickname: finalObject.nick || finalObject.nickname || '',
    message: '登录成功',
  };
}

// ==================================================================
// 酷狗扫码登录（web 签名直连 login-user.kugou.com）
// ==================================================================
const KG_WEB_SIGN_SALT = 'NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt';
const KG_QR_KEY_APPID = 1001;
const KG_ANDROID_APPID = 3116;
const KG_LITE_CLIENTVER = 11440;
const KG_SRCAPPID = 2919;
const KG_QR_CHECK_TIMEOUT_MS = 45000;

function kgMd5(raw) {
  return crypto.createHash('md5').update(raw).digest('hex');
}

function kgSignWebParams(params) {
  const paramsString = Object.keys(params).sort().map((key) => `${key}=${params[key]}`).join('');
  return kgMd5(`${KG_WEB_SIGN_SALT}${paramsString}${KG_WEB_SIGN_SALT}`);
}

function kgCreateMid() {
  return crypto.createHash('md5').update(String(Date.now()) + Math.random()).digest('hex');
}

async function kgFetchWebSignedGet(baseURL, urlPath, extraParams, timeoutMs) {
  const mid = kgCreateMid();
  const clienttime = Math.floor(Date.now() / 1000);
  const params = Object.assign({
    dfid: '-',
    mid,
    uuid: mid,
    clientver: KG_LITE_CLIENTVER,
    clienttime,
  }, extraParams || {});
  params.signature = kgSignWebParams(params);
  const qs = Object.keys(params)
    .map((key) => `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`)
    .join('&');
  const url = `${String(baseURL).replace(/\/$/, '')}${urlPath}?${qs}`;
  const resp = await rawRequest(url, {
    headers: {
      'User-Agent': UA_CHROME,
      Accept: 'application/json, text/plain, */*',
      Referer: 'https://www.kugou.com/',
    },
    timeoutMs: timeoutMs || 12000,
  });
  return JSON.parse(resp.body.toString('utf8'));
}

/** GET /v2/qrcode -> { key, qrimg(dataURL) } */
async function kgGetLoginQrKey(buildQrImage) {
  const body = await kgFetchWebSignedGet('https://login-user.kugou.com', '/v2/qrcode', {
    appid: KG_QR_KEY_APPID,
    type: 1,
    plat: 4,
    qrcode_txt: `https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=${KG_ANDROID_APPID}&`,
    srcappid: KG_SRCAPPID,
  });
  const key = String(
    (body && body.data && (body.data.qrcode || body.data.qrcode_key || body.data.key))
    || (body && (body.qrcode || body.key))
    || '',
  ).trim();
  if (!key) throw new Error('酷狗二维码 key 获取失败');
  const url = `https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=${encodeURIComponent(key)}`;
  let qrimg = '';
  try { qrimg = typeof buildQrImage === 'function' ? await buildQrImage(url) : ''; } catch (_) {}
  return { key, url, qrimg };
}

/**
 * 长轮询扫码状态。
 * 返回 { status: 'wait'|'scanned'|'expired'|'ok'|'error', code, cookie?, userId?, nickname? }
 */
const kgQrCheckTimeouts = new Map();
async function kgCheckLoginQr(key) {
  key = String(key || '').trim();
  if (!key) return { status: 'expired', code: 0, message: '缺少二维码 key' };
  let body;
  try {
    body = await kgFetchWebSignedGet('https://login-user.kugou.com', '/v2/get_userinfo_qrcode', {
      plat: 4,
      appid: KG_ANDROID_APPID,
      srcappid: KG_SRCAPPID,
      qrcode: key,
    }, KG_QR_CHECK_TIMEOUT_MS);
    kgQrCheckTimeouts.delete(key);
  } catch (error) {
    // 长轮询超时按「等待扫码」处理，容忍连续 3 次以内
    const attempts = (kgQrCheckTimeouts.get(key) || 0) + 1;
    kgQrCheckTimeouts.set(key, attempts);
    if (attempts < 3) return { status: 'wait', code: 1, message: '请用酷狗 App 扫码', timedOut: true };
    kgQrCheckTimeouts.delete(key);
    throw error;
  }
  const data = (body && body.data) || {};
  const status = Number(data.status != null ? data.status : body && body.status) || 0;
  if (status === 0) return { status: 'expired', code: 0, message: '二维码已过期，请刷新' };
  if (status === 1) return { status: 'wait', code: 1, message: '请用酷狗 App 扫码' };
  if (status === 2) return { status: 'scanned', code: 2, message: '已扫码，请在手机确认' };
  if (status !== 4) return { status: 'wait', code: status, message: '等待扫码' };

  const userId = String(data.userid || data.user_id || data.KugooID || '').trim();
  const token = String(data.token || data.t || '').trim();
  if (!userId || !token) {
    return { status: 'error', code: 4, message: '扫码成功但未返回 token，请刷新二维码重试' };
  }
  const nickname = String(data.nickname || data.nick_name || data.username || '').replace(/<[^>]+>/g, '');
  const pairs = [
    `KuGoo=KugooID=${userId}&t=${token}&userid=${userId}&confirm_flag=1`,
    `userid=${userId}`,
    `KugooID=${userId}`,
    `token=${token}`,
    `t=${token}`,
  ];
  if (nickname) pairs.push('NickName=' + encodeURIComponent(nickname));
  if (data.vip_token) pairs.push('vip_token=' + String(data.vip_token).trim());
  if (data.vip_type) pairs.push('vip_type=' + String(data.vip_type).trim());
  return {
    status: 'ok',
    code: 4,
    cookie: pairs.join('; '),
    userId,
    token,
    nickname,
    message: '登录成功',
  };
}

module.exports = {
  rawRequest,
  parseSetCookiePairs,
  setDebugLogger,
  qqGetLoginQr,
  qqCheckLoginQr,
  kgGetLoginQrKey,
  kgCheckLoginQr,
};
