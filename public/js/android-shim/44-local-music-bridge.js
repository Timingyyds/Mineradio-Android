// public/js/android-shim/44-local-music-bridge.js
// 本地音乐原生桥：把 FoliaCore 的 scanLocalAudio/pickAudioFiles/pickAudioFolder
// 挂成 Promise 风格的 window.LocalMusicNative。
//
// 为什么用它：原生 pickAudioFiles 会把选中的音频【复制进 App 私有目录】
// （files/imported-audio/），并给出一个稳定的本地流地址（imported-xxx）。
// 这正是「导入即永久保存」的架构——重启后文件还在，只要按当前端口重拼
// URL 就能播，不需要用户再导一次。

(function (root) {
  'use strict';

  function plugin() {
    var cap = root.Capacitor;
    if (!cap || !cap.isNativePlatform || !cap.isNativePlatform()) return null;
    return (cap.Plugins && (cap.Plugins.FoliaNative || cap.Plugins.FoliaCore)) || null;
  }

  function call(method, params) {
    var p = plugin();
    if (!p) return Promise.reject(new Error('当前环境不支持本地音乐原生桥'));
    if (typeof p[method] === 'function') return p[method](params || {});
    if (typeof p.call === 'function') return p.call(method, params || {});
    return Promise.reject(new Error('FoliaCore 插件缺少方法: ' + method));
  }

  /** 当前本地流服务器端口（每次启动可能不同），带短缓存。 */
  var cachedPort = 0;
  var cachedPortAt = 0;
  function getPort(force) {
    if (!force && cachedPort && Date.now() - cachedPortAt < 60000) {
      return Promise.resolve(cachedPort);
    }
    return call('localAudioServerPort').then(function (r) {
      cachedPort = (r && r.port) || 0;
      cachedPortAt = Date.now();
      return cachedPort;
    });
  }

  /** 用 ref（imported-xxx）拼出当前会话可用的流地址。 */
  function urlForRef(ref) {
    return getPort().then(function (port) {
      if (!port) throw new Error('本地音频服务未启动');
      return 'http://127.0.0.1:' + port + '/audio/' + encodeURIComponent(String(ref || ''));
    });
  }

  root.LocalMusicNative = {
    /** 系统文件选择器（可多选）。成功后文件已复制进私有目录。 */
    pickFiles: function () { return call('pickAudioFiles'); },
    /** 选择整个文件夹（递归收集音频）。 */
    pickFolder: function () { return call('pickAudioFolder'); },
    /** 扫描系统媒体库。 */
    scan: function () { return call('scanLocalAudio'); },
    /** ref → 当前会话的流地址。 */
    urlForRef: urlForRef,
    getPort: getPort,
    isAvailable: function () { return !!plugin(); }
  };
})(window);
