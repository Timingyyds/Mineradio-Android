// public/js/ported/ref/prelude.js
// 参考层（网易云 / QQ 音乐）运行所需的三样垫片：
//   1. chrome.storage.local  → localStorage
//   2. chrome.cookies        → 原生 FoliaCore 插件（读写 WebView 的 CookieManager）
//   3. fetch                 → 跨域请求走 FoliaBridge（OkHttp，无同源限制）
//
// 参考层原本是浏览器扩展/Electron 环境，登录会话、二维码跳转的 Set-Cookie
// 都靠 chrome.cookies 回捞。安卓壳里没有这个 API，FoliaCore 已经提供了
// 等价的原生方法（cookiesGetAll / cookiesGet / cookiesSet / cookiesRemove）。

(function (root) {
  'use strict';

  // ── 1. chrome.storage.local ────────────────────────────────
  var STORAGE_PREFIX = 'mr_chrome_storage__';
  var readKey = function (key) {
    try {
      var raw = localStorage.getItem(STORAGE_PREFIX + key);
      return raw == null ? undefined : JSON.parse(raw);
    } catch (e) {
      return undefined;
    }
  };
  var writeKey = function (key, value) {
    try {
      if (value === undefined) localStorage.removeItem(STORAGE_PREFIX + key);
      else localStorage.setItem(STORAGE_PREFIX + key, JSON.stringify(value));
    } catch (e) { /* 存不下就算了 */ }
  };
  var normalizeKeyList = function (keys) {
    if (typeof keys === 'string') return [keys];
    if (Array.isArray(keys)) return keys.filter(Boolean).map(String);
    if (keys && typeof keys === 'object') return Object.keys(keys);
    return [];
  };

  var storageLocal = {
    get: function (keys) {
      return new Promise(function (resolve) {
        var out = {};
        normalizeKeyList(keys).forEach(function (key) {
          var value = readKey(key);
          if (value !== undefined) out[key] = value;
        });
        resolve(out);
      });
    },
    set: function (items) {
      return new Promise(function (resolve) {
        Object.keys(items || {}).forEach(function (key) { writeKey(key, items[key]); });
        resolve(true);
      });
    },
    remove: function (keys) {
      return new Promise(function (resolve) {
        normalizeKeyList(keys).forEach(function (key) { writeKey(key, undefined); });
        resolve(true);
      });
    },
    clear: function () {
      return new Promise(function (resolve) {
        var doomed = [];
        for (var i = 0; i < localStorage.length; i += 1) {
          var key = localStorage.key(i);
          if (key && key.indexOf(STORAGE_PREFIX) === 0) doomed.push(key);
        }
        doomed.forEach(function (key) { localStorage.removeItem(key); });
        resolve(true);
      });
    },
  };

  // ── 2. chrome.cookies → FoliaNative 原生 CookieManager ───────
  // 插件注册名是 FoliaNative（FoliaCorePlugin.java 的 @CapacitorPlugin(name="FoliaNative")）。
  // 以前这里写成 FoliaCore，取不到插件 → cookie 罐永远读空，扫码 803 的 MUSIC_U 也就回捞不到。
  var corePlugin = function () {
    var capacitor = root.Capacitor;
    if (!capacitor || !capacitor.Plugins) return null;
    return capacitor.Plugins.FoliaNative || capacitor.Plugins.FoliaCore || null;
  };

  var cookiesApi = {
    getAll: async function (details) {
      var plugin = corePlugin();
      if (!plugin || typeof plugin.cookiesGetAll !== 'function') return [];
      var answer = await plugin.cookiesGetAll({
        url: (details && details.url) || '',
        domain: (details && details.domain) || '',
      }).catch(function () { return null; });
      var list = (answer && answer.cookies) || [];
      var name = details && details.name;
      if (name) list = list.filter(function (item) { return item && item.name === name; });
      return list;
    },
    get: async function (details) {
      var plugin = corePlugin();
      if (!plugin || typeof plugin.cookiesGet !== 'function') return null;
      var answer = await plugin.cookiesGet({
        url: (details && details.url) || '',
        name: (details && details.name) || '',
      }).catch(function () { return null; });
      return (answer && answer.cookie) || null;
    },
    set: async function (details) {
      var plugin = corePlugin();
      if (!plugin || typeof plugin.cookiesSet !== 'function') return null;
      return plugin.cookiesSet({
        url: details && details.url,
        name: details && details.name,
        value: details && details.value,
        domain: (details && details.domain) || '',
        path: (details && details.path) || '/',
        secure: Boolean(details && details.secure),
        expirationDate: details && details.expirationDate,
      }).catch(function () { return null; });
    },
    remove: async function (details) {
      var plugin = corePlugin();
      if (!plugin || typeof plugin.cookiesRemove !== 'function') return null;
      return plugin.cookiesRemove({
        url: details && details.url,
        name: details && details.name,
      }).catch(function () { return null; });
    },
  };

  root.chrome = root.chrome || {};
  root.chrome.storage = root.chrome.storage || {};
  if (!root.chrome.storage.local) root.chrome.storage.local = storageLocal;
  if (!root.chrome.cookies) root.chrome.cookies = cookiesApi;
  if (!root.chrome.runtime) {
    root.chrome.runtime = {
      getManifest: function () { return { version: '0.0.0' }; },
    };
  }

  // ── 3. fetch → 原生桥（跨域） ──────────────────────────────
  if (typeof root.fetch !== 'function' || !root.__mrBridgeRequest) return;

  var installed = root.__mrNativeFetchInstalled === true;
  if (installed) return;
  root.__mrNativeFetchInstalled = true;

  var originalFetch = root.fetch.bind(root);
  var pageOrigin = root.location.origin;

  root.fetch = async function (input, init) {
    var url = '';
    try {
      url = typeof input === 'string' ? input : (input instanceof URL ? input.toString() : input.url);
    } catch (e) {
      return originalFetch(input, init);
    }
    // 同源与相对路径（含 /api 路由）走原来的 fetch
    if (!url || url.indexOf('/') === 0 || url.indexOf(pageOrigin) === 0) return originalFetch(input, init);

    var method = String((init && init.method) || 'GET').toUpperCase();
    var headers = {};
    var rawHeaders = init && init.headers;
    if (rawHeaders) {
      if (typeof Headers !== 'undefined' && rawHeaders instanceof Headers) {
        rawHeaders.forEach(function (value, key) { headers[key] = value; });
      } else if (Array.isArray(rawHeaders)) {
        rawHeaders.forEach(function (pair) { headers[pair[0]] = pair[1]; });
      } else {
        Object.keys(rawHeaders).forEach(function (key) { headers[key] = rawHeaders[key]; });
      }
    }
    var body = init && typeof init.body === 'string' ? init.body : undefined;

    try {
      var answer = await root.__mrBridgeRequest({
        url: url,
        method: method,
        headers: headers,
        body: body,
        timeoutMs: 30000,
        binary: true,
      });
      var responseHeaders = new Headers();
      Object.keys(answer.headers || {}).forEach(function (key) {
        if (String(key).toLowerCase() === 'set-cookie') return;
        var value = answer.headers[key];
        if (value != null) responseHeaders.set(key, String(value));
      });
      // 参考层约定：Set-Cookie 镜像到 x-folia-set-cookie（见 setCookieHeaders.js）。
      // 必须逐条 base64：同一个响应常有多条 Set-Cookie，Headers.append 之后 get() 会按 ", "
      // 拼成一个值，而 cookie 的 Expires 里本来就有逗号 —— 直接放原文会把 MUSIC_U 解析丢。
      (answer.setCookies || []).forEach(function (value) {
        try {
          var encoded = root.btoa(unescape(encodeURIComponent(String(value))));
          responseHeaders.append('x-folia-set-cookie', encoded);
        } catch (e) {
          try { responseHeaders.append('x-folia-set-cookie', String(value)); } catch (_) { /* 忽略 */ }
        }
      });

      var responseBody = answer.body == null ? '' : answer.body;
      if (answer.bodyBase64) {
        try {
          var binary = atob(answer.bodyBase64);
          var bytes = new Uint8Array(binary.length);
          for (var i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
          responseBody = bytes;
        } catch (e) { /* 解码失败退回文本 */ }
      }
      return new Response(responseBody, {
        status: answer.status || 200,
        headers: responseHeaders,
      });
    } catch (e) {
      return originalFetch(input, init);
    }
  };

  console.log('[MrRef] prelude ready（chrome.storage / chrome.cookies / fetch 已接管）');
})(window);
