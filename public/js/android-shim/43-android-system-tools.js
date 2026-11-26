// public/js/android-shim/43-android-system-tools.js
// 安卓版「内存管理 / 缓存与存储」适配层。
// 这两个面板原本调用 window.desktopWindow（Electron 专有），手机上全部不可用。
// 这里提供 __androidSystemApi，业务模块只需在 desktopWindow 缺失时回退到它。
'use strict';

(function (root) {
  function plugin() {
    var cap = root.Capacitor;
    if (!cap || !cap.isNativePlatform || !cap.isNativePlatform()) return null;
    return (cap.Plugins && cap.Plugins.MineradioSystem) || null;
  }

  function call(method, params) {
    var p = plugin();
    if (!p || typeof p[method] !== 'function') return Promise.reject(new Error('系统工具不可用'));
    return p[method](params || {});
  }

  root.__androidSystemApi = {
    getMemorySnapshot: function () {
      return call('getMemorySnapshot').then(function (r) {
        return r && r.ok !== false ? r : { ok: false, error: (r && r.error) || '读取失败' };
      });
    },
    trimAppMemory: function (opts) {
      return call('trimAppMemory', opts || {}).then(function (r) {
        return r && r.ok !== false ? r : { ok: false, error: (r && r.error) || '压缩失败' };
      });
    },
    // 安卓没有桌面版那种系统级内存释放
    purgeSystemMemory: function () {
      return Promise.resolve({
        ok: false,
        skipped: false,
        systemPurgeAvailable: false,
        result: { ok: false, needAdmin: false, message: '安卓不支持系统级内存释放' },
      });
    },
    configureMemoryReduct: function () {
      return Promise.resolve({ ok: true, systemPurgeAvailable: false, auto: { enabled: false } });
    },
    getCacheSettings: function () {
      return call('getCacheSettings').then(function (r) {
        return r && r.ok !== false ? r : { ok: false, error: (r && r.error) || '读取失败' };
      });
    },
    clearCache: function () {
      return call('clearCache').then(function (r) {
        return r && r.ok !== false ? r : { ok: false, error: (r && r.error) || '清理失败' };
      });
    },
    chooseCacheDirectory: function () { return Promise.resolve(null); },
    setCacheSettings: function () { return Promise.resolve(null); },
    restartApp: function () { },
  };

  // 安卓上没有「更改目录」，把这个按钮藏掉，避免用户点了没反应
  function hideDesktopOnlyButtons() {
    try {
      var btn = document.querySelector('#cache-storage-panel button[onclick="chooseMineradioCacheRoot()"]');
      if (btn) btn.style.display = 'none';
    } catch (e) { }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', hideDesktopOnlyButtons);
  else hideDesktopOnlyButtons();
  setTimeout(hideDesktopOnlyButtons, 1200);

  console.log('[SystemTools] 安卓内存/缓存适配已安装');
})(window);
