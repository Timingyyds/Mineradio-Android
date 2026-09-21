'use strict';
// Mineradio Android: qishui-auth-v6 纯 HTTP 版本(不依赖 Electron)
// 使用 Node.js https 模块直接请求汽水 Passport API
// 安全签名(a_bogus/msToken)在 Android 端无法生成,但尝试直接请求获取二维码

const crypto = require('crypto');
const https = require('https');
const os = require('os');
const QRCode = require('qrcode');

const API_BASE = 'https://api.qishui.com';
const AID = '386088';
const APP_VERSION = '3.5.2';
const SDK_VERSION = '2.4.13';
const VERIFY_SDK_VERSION = '1.0.29';
const SECURE_SDK_VERSION = '3.3.5';
const BDMS_VERSION = '1.0.0.41';
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) SodaMusic/3.2.1 Chrome/136.0.7103.59 ' +
  'Electron/36.4.0-rs.22.release.main.1 TTElectron/36.4.0-rs.22.release.main.1 Safari/537.36';

let getConfig = null;
let updateConfig = null;

function configure(hooks) {
  getConfig = hooks && hooks.getConfig;
  updateConfig = hooks && hooks.updateConfig;
}

function randomDigits(length, firstMax) {
  firstMax = firstMax || 9;
  var value = String(Math.floor(Math.random() * (firstMax - 1 + 1)) + 1);
  while (value.length < length) value += String(Math.floor(Math.random() * 10));
  return value;
}

function ensureIdentity() {
  if (typeof getConfig !== 'function' || typeof updateConfig !== 'function') {
    throw new Error('Qishui V6 auth runtime is not configured');
  }
  var config = getConfig();
  var patch = {};
  if (!config.deviceId) patch.deviceId = randomDigits(16, 8);
  if (!config.installId) patch.installId = randomDigits(15, 8);
  if (!config.verifyPortraitId) {
    // crypto.randomUUID 在 Node 18+ 可用,降级方案
    if (typeof crypto.randomUUID === 'function') {
      patch.verifyPortraitId = crypto.randomUUID() + '.login';
    } else {
      patch.verifyPortraitId = crypto.randomBytes(16).toString('hex') + '.login';
    }
  }
  if (!config.computerName) patch.computerName = os.hostname() || 'Android-Device';
  if (Object.keys(patch).length) updateConfig(patch);
  var current = getConfig();
  return {
    deviceId: String(current.deviceId),
    installId: String(current.installId),
    verifyPortraitId: String(current.verifyPortraitId),
    computerName: String(current.computerName || 'Android-Device'),
  };
}

function delay(ms) {
  return new Promise(function (resolve) { setTimeout(resolve, ms); });
}

function officialScanUrl(indexUrl, computerName) {
  var source = new URL(String(indexUrl || ''));
  var token = source.searchParams.get('token');
  if (!token) throw new Error('qrcode_index_url 缺少 token');
  var target = new URL('https://bff-pc.qishui.com/light/invoke/scan_login');
  target.searchParams.set('token', token);
  target.searchParams.set('os', 'Windows');
  target.searchParams.set('computer_name', computerName || 'Windows-PC');
  return target.toString().replace(/\+/g, '%20');
}

function commonParams(identity, msToken) {
  return {
    passport_jssdk_version: SDK_VERSION,
    passport_jssdk_type: 'normal',
    is_from_ttaccountsdk: '1',
    aid: AID,
    language: 'zh',
    account_sdk_source: 'web',
    p_js_v: SDK_VERSION,
    p_js_t: 'pro',
    p_zt: SECURE_SDK_VERSION,
    p_ver: VERIFY_SDK_VERSION,
    request_host: 'app%3A%2F%2Fresources',
    p_bd: BDMS_VERSION,
    biz_trace_id: crypto.randomBytes(4).toString('hex'),
    is_new_login: '1',
    is_from_iesaccountsaas: '1',
    device_id: identity.deviceId,
    install_id: identity.installId,
    did: identity.deviceId,
    iid: identity.installId,
    device_platform: 'PC',
    version_code: APP_VERSION,
    msToken: msToken || '',
  };
}

function requestHeaders(identity, bizTraceId) {
  var traceId = crypto.randomBytes(16).toString('hex');
  return {
    'Accept': 'application/json, text/javascript',
    'User-Agent': UA,
    'x-tt-passport-verify-portrait': identity.verifyPortraitId,
    'x-tt-passport-trace-id': bizTraceId,
    'x-tt-trace-id': '00-' + traceId + '-' + traceId.slice(0, 16) + '-01',
  };
}

// 纯 HTTPS 请求(不经过 BrowserWindow)
function httpRequest(method, url, headers, body, timeout) {
  return new Promise(function (resolve, reject) {
    var urlObj = new URL(url);
    var options = {
      method: method,
      hostname: urlObj.hostname,
      port: urlObj.port || 443,
      path: urlObj.pathname + urlObj.search,
      headers: headers || {},
    };
    var req = https.request(options, function (res) {
      var data = '';
      res.setEncoding('utf8');
      res.on('data', function (chunk) { data += chunk; });
      res.on('end', function () {
        resolve({ status: res.statusCode || 0, body: data, headers: res.headers });
      });
    });
    req.on('error', reject);
    req.setTimeout(timeout || 30000, function () {
      req.destroy(new Error('请求超时'));
    });
    if (body) req.write(body);
    req.end();
  });
}

// 生成一个随机的 msToken(无法生成真实的安全签名,用随机值尝试)
function generateMsToken() {
  return crypto.randomBytes(32).toString('base64').replace(/[^a-zA-Z0-9]/g, '').slice(0, 64);
}

async function getQrCode() {
  var identity = ensureIdentity();
  var msToken = generateMsToken();
  var params = commonParams(identity, msToken);
  params.next = API_BASE;
  params.need_logo = 'false';
  params.need_short_url = 'false';
  var url = new URL('/passport/web/get_qrcode/', API_BASE);
  for (var name in params) {
    if (params[name] != null) url.searchParams.set(name, String(params[name]));
  }
  var headers = requestHeaders(identity, params.biz_trace_id);
  var response = await httpRequest('GET', url.toString(), headers, null, 30000);
  console.log('[QishuiGetQr] HTTP', response.status, 'body:', String(response.body || '').slice(0, 500));
  if (!response || response.status < 200 || response.status >= 400) {
    throw new Error('汽水二维码接口 HTTP ' + (response && response.status || 0) + ': ' + String(response && response.body || '').slice(0, 300));
  }
  var envelope;
  try { envelope = JSON.parse(response.body || '{}'); } catch (e) {
    throw new Error('汽水二维码接口返回了无效 JSON: ' + String(response.body || '').slice(0, 200));
  }
  console.log('[QishuiGetQr] envelope:', JSON.stringify({ message: envelope.message, error_code: envelope.data && envelope.data.error_code, has_token: !!(envelope.data && envelope.data.token), has_qrcode_index_url: !!(envelope.data && envelope.data.qrcode_index_url) }));
  var data = envelope.data || {};
  if (envelope.message !== 'success' || Number(data.error_code) !== 0) {
    throw new Error('二维码生成失败: code=' + data.error_code + ' ' + (data.description || envelope.message || ''));
  }
  var scanUrl = officialScanUrl(data.qrcode_index_url, identity.computerName);
  var qrDataUrl = await QRCode.toDataURL(scanUrl, {
    errorCorrectionLevel: 'M',
    margin: 2,
    width: 360,
    color: { dark: '#000000', light: '#ffffff' },
  });
  envelope.data = Object.assign({}, data, { qrcode: qrDataUrl, scan_url: scanUrl });
  return envelope;
}

async function checkQrConnect(token) {
  token = String(token || '').trim();
  if (!token) throw new Error('QISHUI_QR_TOKEN_REQUIRED');
  var identity = ensureIdentity();
  var msToken = generateMsToken();
  var params = commonParams(identity, msToken);
  var body = {
    need_logo: 'false',
    need_short_url: 'false',
    is_frontier: 'true',
    token: token,
    is_new_login: '1',
    next: API_BASE,
  };
  var url = new URL('/passport/web/check_qrconnect/', API_BASE);
  for (var name in params) {
    if (params[name] != null) url.searchParams.set(name, String(params[name]));
  }
  var headers = requestHeaders(identity, params.biz_trace_id);
  var bodyStr = new URLSearchParams();
  for (var name in body) {
    if (body[name] != null) bodyStr.set(name, String(body[name]));
  }
  bodyStr = bodyStr.toString();
  headers['Content-Type'] = 'application/x-www-form-urlencoded';
  headers['x-ss-stub'] = crypto.createHash('md5').update(bodyStr).digest('hex').toUpperCase();
  var response = await httpRequest('POST', url.toString(), headers, bodyStr, 30000);
  console.log('[QishuiCheckQr] HTTP', response.status, 'body:', String(response.body || '').slice(0, 500));
  if (!response || response.status < 200 || response.status >= 400) {
    throw new Error('汽水扫码检查 HTTP ' + (response && response.status || 0) + ': ' + String(response && response.body || '').slice(0, 300));
  }
  var envelope;
  try { envelope = JSON.parse(response.body || '{}'); } catch (e) {
    throw new Error('汽水扫码检查返回了无效 JSON: ' + String(response.body || '').slice(0, 200));
  }
  console.log('[QishuiCheckQr] envelope:', JSON.stringify({ message: envelope.message, error_code: envelope.data && envelope.data.error_code, status: envelope.data && envelope.data.status, has_session_cookie: !!(envelope.data && envelope.data.session_cookie) }));
  // 如果返回了 session_cookie,保存到 config
  var data = envelope.data || {};
  if (Number(data.error_code) === 0 && (String(data.status) === '3' || String(data.status) === 'confirmed' || data.session_cookie)) {
    if (data.session_cookie && typeof updateConfig === 'function') {
      var config = getConfig();
      var existingCookie = config.cookie || '';
      updateConfig({ cookie: existingCookie + (existingCookie ? '; ' : '') + data.session_cookie });
    }
  }
  return envelope;
}

async function ensureSecurityToken() {
  // Android 端无法生成真实安全签名,返回空值
  return '';
}

// 构建 check_qrconnect 请求 payload(不发起请求),供前端签名桥接使用
function buildCheckQrConnectPayload(token) {
  token = String(token || '').trim();
  if (!token) throw new Error('QISHUI_QR_TOKEN_REQUIRED');
  var identity = ensureIdentity();
  var msToken = generateMsToken();
  var params = commonParams(identity, msToken);
  var body = {
    need_logo: 'false',
    need_short_url: 'false',
    is_frontier: 'true',
    token: token,
    is_new_login: '1',
    next: API_BASE,
  };
  var url = new URL('/passport/web/check_qrconnect/', API_BASE);
  for (var name in params) {
    if (params[name] != null) url.searchParams.set(name, String(params[name]));
  }
  var headers = requestHeaders(identity, params.biz_trace_id);
  var bodyStr = new URLSearchParams();
  for (var name in body) {
    if (body[name] != null) bodyStr.set(name, String(body[name]));
  }
  bodyStr = bodyStr.toString();
  headers['Content-Type'] = 'application/x-www-form-urlencoded';
  headers['x-ss-stub'] = crypto.createHash('md5').update(bodyStr).digest('hex').toUpperCase();
  return {
    method: 'POST',
    url: url.toString(),
    headers: headers,
    body: bodyStr,
    timeout: 30000
  };
}

// 从 2046 响应中提取 MFA 决策数据(供前端显示二次验证面板)
function extractMfaDecision(envelope) {
  if (!envelope || !envelope.data || Number(envelope.data.error_code) !== 2046) return null;
  var decision = { ...(envelope || {}), ...(envelope.data || {}) };
  delete decision.data;
  var identity = ensureIdentity();
  if (!decision.verify_portrait_id) decision.verify_portrait_id = identity.verifyPortraitId;
  return decision;
}

// 提取 biz_params 为对象(供前端传回)
function extractBizParams(envelope) {
  if (!envelope || !envelope.data) return {};
  var bizParams = envelope.data.biz_params;
  if (!bizParams) return {};
  if (typeof bizParams === 'string') {
    try { return JSON.parse(bizParams); } catch (_) { return {}; }
  }
  if (typeof bizParams === 'object') return bizParams;
  return {};
}

// 重新发送 check_qrconnect(带 isResend=true + bizParams,用于 MFA 验证后)
async function checkQrConnectResend(token, bizParams) {
  token = String(token || '').trim();
  if (!token) throw new Error('QISHUI_QR_TOKEN_REQUIRED');
  var identity = ensureIdentity();
  var msToken = generateMsToken();
  var params = commonParams(identity, msToken);
  params.isResend = 'true';
  var body = {
    need_logo: 'false',
    need_short_url: 'false',
    is_frontier: 'true',
    token: token,
    is_new_login: '1',
    next: API_BASE,
  };
  // 合并 biz_params 到 body
  if (bizParams && typeof bizParams === 'object') {
    for (var key in bizParams) {
      if (bizParams[key] != null) {
        body[key] = typeof bizParams[key] === 'object' ? JSON.stringify(bizParams[key]) : String(bizParams[key]);
      }
    }
  }
  var url = new URL('/passport/web/check_qrconnect/', API_BASE);
  for (var name in params) {
    if (params[name] != null) url.searchParams.set(name, String(params[name]));
  }
  var headers = requestHeaders(identity, params.biz_trace_id);
  var bodyStr = new URLSearchParams();
  for (var name in body) {
    if (body[name] != null) bodyStr.set(name, String(body[name]));
  }
  bodyStr = bodyStr.toString();
  headers['Content-Type'] = 'application/x-www-form-urlencoded';
  headers['x-ss-stub'] = crypto.createHash('md5').update(bodyStr).digest('hex').toUpperCase();
  var response = await httpRequest('POST', url.toString(), headers, bodyStr, 30000);
  console.log('[QishuiCheckQrResend] HTTP', response.status, 'body:', String(response.body || '').slice(0, 500));
  if (!response || response.status < 200 || response.status >= 400) {
    throw new Error('汽水扫码检查重发 HTTP ' + (response && response.status || 0) + ': ' + String(response && response.body || '').slice(0, 300));
  }
  var envelope;
  try { envelope = JSON.parse(response.body || '{}'); } catch (e) {
    throw new Error('汽水扫码检查重发返回了无效 JSON: ' + String(response.body || '').slice(0, 200));
  }
  console.log('[QishuiCheckQrResend] envelope:', JSON.stringify({ message: envelope.message, error_code: envelope.data && envelope.data.error_code, status: envelope.data && envelope.data.status }));
  var data = envelope.data || {};
  if (Number(data.error_code) === 0 && (String(data.status) === '3' || String(data.status) === 'confirmed' || data.session_cookie)) {
    if (data.session_cookie && typeof updateConfig === 'function') {
      var config = getConfig();
      var existingCookie = config.cookie || '';
      updateConfig({ cookie: existingCookie + (existingCookie ? '; ' : '') + data.session_cookie });
    }
  }
  return envelope;
}

// 处理签名后的 check_qrconnect 响应(前端通过签名桥接获取后回传)
function processCheckQrConnectResponse(responseBody) {
  var envelope;
  try { envelope = JSON.parse(responseBody || '{}'); } catch (e) {
    throw new Error('汽水扫码检查返回了无效 JSON: ' + String(responseBody || '').slice(0, 200));
  }
  console.log('[QishuiCheckQr] signed envelope:', JSON.stringify({ message: envelope.message, error_code: envelope.data && envelope.data.error_code, status: envelope.data && envelope.data.status, has_session_cookie: !!(envelope.data && envelope.data.session_cookie) }));
  var data = envelope.data || {};
  if (Number(data.error_code) === 0 && (String(data.status) === '3' || String(data.status) === 'confirmed' || data.session_cookie)) {
    if (data.session_cookie && typeof updateConfig === 'function') {
      var config = getConfig();
      var existingCookie = config.cookie || '';
      updateConfig({ cookie: existingCookie + (existingCookie ? '; ' : '') + data.session_cookie });
    }
  }
  return envelope;
}

// 清除登录态: 重置 config 中的会话相关字段
// (qishui-qr-login.js 的 clear() 会调用 auth.clear())
function clear() {
  if (typeof updateConfig === 'function') {
    updateConfig({ cookie: '', msToken: '' });
  }
}

module.exports = {
  configure: configure,
  ensureIdentity: ensureIdentity,
  ensureSecurityToken: ensureSecurityToken,
  delay: delay,
  getQrCode: getQrCode,
  checkQrConnect: checkQrConnect,
  buildCheckQrConnectPayload: buildCheckQrConnectPayload,
  processCheckQrConnectResponse: processCheckQrConnectResponse,
  extractMfaDecision: extractMfaDecision,
  extractBizParams: extractBizParams,
  checkQrConnectResend: checkQrConnectResend,
  clear: clear,
};
