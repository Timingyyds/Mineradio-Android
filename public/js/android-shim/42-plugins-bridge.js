// public/js/android-shim/42-plugins-bridge.js
// 扩展插件：把参考工程 12-plugin-runtime.js 依赖的 /api/plugins/* 接口
// 与 window.KeepApp.pickPluginZip 接到 Capacitor 原生插件 MineradioPlugins，
// 这样插件运行时本身可以原样使用，不用改它的业务代码。
'use strict';

(function (root) {
  var nativeFetch = root.fetch ? root.fetch.bind(root) : null;

  function plugin() {
    var cap = root.Capacitor;
    if (!cap || !cap.isNativePlatform || !cap.isNativePlatform()) return null;
    return (cap.Plugins && cap.Plugins.MineradioPlugins) || null;
  }

  function jsonResponse(obj) {
    return new Response(JSON.stringify(obj), {
      status: 200,
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
    });
  }

  function textResponse(text) {
    return new Response(text == null ? '' : String(text), {
      status: 200,
      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
    });
  }

  function binaryResponse(resp) {
    return resp;
  }

  /** 私有目录文件 → WebView 可访问的 Capacitor 本地 URL */
  function capFileUrl(absPath) {
    if (!absPath) return '';
    if (absPath.indexOf('http') === 0 || absPath.indexOf('data:') === 0) return absPath;
    return 'https://localhost/_capacitor_file_' + encodeURI(absPath);
  }

  function callMethod(name, params) {
    var p = plugin();
    if (!p) return Promise.reject(new Error('插件功能不可用：MineradioPlugins 未注册'));
    if (typeof p[name] !== 'function') return Promise.reject(new Error('插件方法不存在: ' + name));
    return p[name](params || {});
  }

  /** /api/plugins/* 的本地实现 */
  function handlePluginApi(fullUrl, init) {
    var p = plugin();
    if (!p) return Promise.resolve(jsonResponse({ ok: false, error: '当前环境不支持插件' }));
    var u = new URL(fullUrl, root.location.href);
    var path = u.pathname;
    var params = u.searchParams;
    var method = (init && init.method || 'GET').toUpperCase();

    if (path === '/api/plugins/list') {
      return callMethod('list').then(function (data) {
        return jsonResponse({ ok: true, plugins: (data && data.plugins) || [] });
      }).catch(function (e) {
        return jsonResponse({ ok: false, error: e.message || String(e) });
      });
    }
    if (path === '/api/plugins/enable' || path === '/api/plugins/disable') {
      return callMethod('setEnabled', {
        id: params.get('id') || '',
        enabled: path === '/api/plugins/enable',
      }).then(function () { return jsonResponse({ ok: true }); })
        .catch(function (e) { return jsonResponse({ ok: false, error: e.message }); });
    }
    if (path === '/api/plugins/uninstall') {
      return callMethod('uninstall', { id: params.get('id') || '' })
        .then(function () { return jsonResponse({ ok: true }); })
        .catch(function (e) { return jsonResponse({ ok: false, error: e.message }); });
    }
    if (path === '/api/plugins/install_from_path') {
      return Promise.resolve(jsonResponse({ ok: false, error: '请在插件面板点「安装插件」选择 .zip' }));
    }

    var m = path.match(/^\/api\/plugins\/([^/]+)\/(.+)$/);
    if (!m) return Promise.resolve(jsonResponse({ ok: false, error: 'unsupported api: ' + path }));
    var id = decodeURIComponent(m[1]);
    var rest = m[2];

    if (rest === 'main.js') {
      if (method === 'POST') {
        var body = (init && init.body) || '';
        return callMethod('writeMainJs', { id: id, content: String(body) })
          .then(function () { return jsonResponse({ ok: true }); })
          .catch(function (e) { return jsonResponse({ ok: false, error: e.message }); });
      }
      return callMethod('readMainJs', { id: id })
        .then(function (res) { return textResponse(res && res.text); })
        .catch(function (e) { return textResponse(''); });
    }
    if (rest === 'manifest') {
      return callMethod('readManifest', { id: id })
        .then(function (res) { return textResponse(res && res.text); })
        .catch(function (e) { return textResponse(''); });
    }
    if (rest === 'entry') {
      return callMethod('readEntry', { id: id })
        .then(function (res) { return textResponse(res && res.text); })
        .catch(function (e) { return textResponse(''); });
    }
    if (rest === 'resource/text') {
      return callMethod('readResourceText', { id: id, path: params.get('path') || '' })
        .then(function (res) { return textResponse(res && res.text); })
        .catch(function (e) { return textResponse(''); });
    }
    if (rest === 'resource') {
      return callMethod('getResourcePath', { id: id, path: params.get('path') || '' })
        .then(function (res) {
          if (!res || !res.path) throw new Error('resource not found');
          return nativeFetch ? nativeFetch(capFileUrl(res.path)) : new Response('');
        })
        .catch(function (e) { return new Response('', { status: 404 }); });
    }
    if (rest === 'files') {
      return callMethod('listResources', { id: id, dir: params.get('dir') || '' })
        .then(function (res) { return jsonResponse((res && res.list) || []); })
        .catch(function (e) { return jsonResponse([]); });
    }
    if (rest === 'path') {
      return callMethod('getResourcePath', { id: id, path: params.get('relPath') || '' })
        .then(function (res) { return jsonResponse({ ok: true, path: res && res.path }); })
        .catch(function (e) { return jsonResponse({ ok: false, error: e.message }); });
    }
    return Promise.resolve(jsonResponse({ ok: false, error: 'unsupported api: ' + rest }));
  }

  // ── fetch 拦截：只接管相对路径 /api/plugins/* ──
  if (nativeFetch) {
    root.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url);
      if (url && url.indexOf('/api/plugins') === 0) {
        return handlePluginApi(url, init);
      }
      return nativeFetch(input, init);
    };
  }

  // ── KeepApp：插件运行时只依赖这两个能力 ──
  var KeepApp = root.KeepApp || (root.KeepApp = {});
  KeepApp.showToast = function (msg) {
    if (typeof root.showToast === 'function') root.showToast(msg);
    else console.log('[PluginToast]', msg);
  };
  KeepApp.pickPluginZip = function (callbackId) {
    var p = plugin();
    if (!p || typeof p.pickAndInstall !== 'function') {
      if (root._onPluginZipPicked) root._onPluginZipPicked(callbackId, { ok: false, message: '当前环境不支持安装插件' });
      return;
    }
    p.pickAndInstall({}).then(function (res) {
      if (root._onPluginZipPicked) root._onPluginZipPicked(callbackId, res || { ok: false, message: '未知错误' });
    }).catch(function (e) {
      if (root._onPluginZipPicked) root._onPluginZipPicked(callbackId, { ok: false, message: e.message || String(e) });
    });
  };

  // 插件运行时启动时会检查 updateState.pluginEnabled；本工程没有云端开关，这里直接放行
  if (typeof root.updateState === 'undefined' || !root.updateState) root.updateState = {};
  root.updateState.pluginEnabled = true;

  console.log('[PluginsBridge] 插件桥已安装（MineradioPlugins）');
})(window);
