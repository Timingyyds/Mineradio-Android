// public/js/android-shim/41-online-wallpaper-bridge.js
// 在线壁纸 / 壁纸库 → Capacitor MpkgWallpaper 插件的 Promise 适配层。
//
// 参考工程（Mineradio-Android-main）里这些能力直接挂在 window.KeepApp 上，
// 且大量方法是**同步返回 JSON 字符串**（wpLibraryList / wpLibraryPreview …）。
// 当前工程走 Capacitor 插件，天然是异步的，所以这里统一包成 Promise，
// 前端模块 07-fx/04-online-wallpaper-library.js 只认这组 Promise 方法。

(function (root) {
  'use strict';

  function plugin() {
    var cap = root.Capacitor;
    if (!cap || !cap.isNativePlatform || !cap.isNativePlatform()) return null;
    return (cap.Plugins && cap.Plugins.MpkgWallpaper) || null;
  }

  function call(method, params) {
    var p = plugin();
    if (!p) return Promise.reject(new Error('当前环境不支持在线壁纸'));
    // Capacitor 3+ 的插件代理把每个 @PluginMethod 挂成同名函数；
    // 旧版 / 部分封装才有通用 call(method, options)。
    if (typeof p[method] === 'function') return p[method](params || {});
    if (typeof p.call === 'function') return p.call(method, params || {});
    return Promise.reject(new Error('MpkgWallpaper 插件缺少方法: ' + method));
  }

  root.OnlineWpNative = {
    // 在线壁纸
    getList: function (params) { return call('onlineWpGetList', params); },
    download: function (params) { return call('onlineWpDownload', params); },
    getProgress: function () { return call('onlineWpGetProgress', {}); },

    // 本地壁纸库
    libraryList: function (category) { return call('wpLibraryList', { category: category || 'all' }); },
    libraryPreview: function (id) { return call('wpLibraryPreview', { id: id }); },
    librarySetAppBg: function (id) { return call('wpLibrarySetAppBg', { id: id }); },
    librarySetSystem: function (id) { return call('wpLibrarySetSystem', { id: id }); },
    libraryDelete: function (id) { return call('wpLibraryDelete', { id: id }); },
    /* 【修复】读取/清除原生登记的「应用内背景」：
       壁纸管理界面据此显示「使用中」徽标；清除背景时必须清登记+停 GL 层，
       否则背景永远清不掉。 */
    libraryGetAppBg: function () { return call('getAppBackground', {}); },
    libraryClearAppBg: function () { return call('clearAppBackground', {}); },

    // MPKG 导出为系统壁纸（分块写临时文件）
    writeTempStart: function (fileName) { return call('wpWriteTempFileStart', { fileName: fileName }); },
    writeTempChunk: function (path, base64Chunk) { return call('wpWriteTempFileChunk', { path: path, base64Chunk: base64Chunk }); },
    writeTempFinish: function (path) { return call('wpWriteTempFileFinish', { path: path }); },
    setSystemMpkgFromFile: function (params) { return call('wpLibrarySetSystemMpkgFromFile', params); },

    // 系统壁纸小操作
    clearSystem: function () { return call('wallpaperClearSystem', {}); },
    setForceSystem: function (value) { return call('setForceSystemWallpaper', { value: String(value) }); },
    gotoSystemWallpaperSettings: function () { return call('gotoSystemWallpaperSettings', {}); },
  };

  console.log('[OnlineWpBridge] window.OnlineWpNative 已安装（Capacitor MpkgWallpaper）');
})(window);
