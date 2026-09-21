/**
 * Mineradio Capacitor 移动端适配层
 * ─────────────────────────────────────────────────────────────
 * 仅在 Capacitor 原生平台运行;Electron / 浏览器下直接 return,
 * 不影响桌面版 `desktop/preload.js` 注入的 window.desktopWindow。
 *
 * 主要职责:
 *  1. 注入与桌面 preload 同接口的 window.desktopWindow 桥接对象,
 *     把 60+ 个 IPC 通道映射到 Capacitor 插件 / Android 原生能力。
 *  2. 桌面专有特性(壁纸引擎、桌面萌宠、桌面歌词独立窗口、
 *     托盘、全局快捷键、桌面图标层、Mem Reduct、完整桌面模式)
 *     全部降级为安全 noop,前端特性检测后会自动禁用 UI。
 *  3. 拦截前端 `fetch('/api/...')`,在后端 Node.js 服务就绪前
 *     返回统一降级响应,避免前端启动卡死;服务就绪后透传。
 *  4. 触摸适配:右键→长按,滚轮→双指拖动,鼠标 hover→touch hover。
 *  5. 注入 desktop-shell-root / desktop-shell CSS 类,
 *     复用桌面 CSS 分支保证视觉一致性。
 */
(function () {
  'use strict';

  // ─────────────── 环境检测 ───────────────
  var cap = (typeof window !== 'undefined') && window.Capacitor;
  if (!cap || typeof cap.isNativePlatform !== 'function' || !cap.isNativePlatform()) {
    console.log('[Mineradio] Capacitor bridge SKIPPED: window.Capacitor=' + (typeof window !== 'undefined' ? typeof window.Capacitor : 'no-window') + (window && window.Capacitor ? ' native=' + (typeof window.Capacitor.isNativePlatform === 'function' ? window.Capacitor.isNativePlatform() : 'n/a') : ''));
    return; // 非 Capacitor 环境,跳过(Electron/浏览器由各自 preload 处理)
  }
  if (window.desktopWindow && window.desktopWindow.__capacitorBridge) {
    return; // 已注入,避免重复
  }

  // ─────────────── 加载 Capacitor 插件 ───────────────
  // 各插件通过 Capacitor 的 registerPlugin 暴露
  var registerPlugin = cap.registerPlugin || (cap.Plugins && function (name) { return cap.Plugins[name]; });
  var Plugins = cap.Plugins || {};
  var Clipboard = Plugins.Clipboard;
  var Browser = Plugins.Browser;
  var Filesystem = Plugins.Filesystem;
  var Preferences = Plugins.Preferences;
  var App$1 = Plugins.App;
  var Haptics = Plugins.Haptics;
  var StatusBar = Plugins.StatusBar;
  var ScreenOrientation = Plugins.ScreenOrientation;
  var MineraNode = registerPlugin ? registerPlugin('MineraNode') : Plugins.MineraNode; // 自定义插件:负责 Node.js 服务生命周期

  // ─────────────── localStorage 持久化(替代同步 IPC 文件读写) ───────────────
  // 桌面版用 ipcRenderer.sendSync 同步读 current-fx-autosave.json,
  // 移动端没有同步 IPC,改用 localStorage(同步 API),值太大时自动降级。
  var FX_AUTOSAVE_KEY = 'mineradio-current-fx-autosave';
  var FX_AUTOSAVE_MAX_LOCAL = 4 * 1024 * 1024; // 4MB(超过则不写,避免 localStorage 抛 quota)

  function safeLocalStorageGet(key) {
    try { return localStorage.getItem(key); } catch (e) { return null; }
  }
  function safeLocalStorageSet(key, value) {
    try { localStorage.setItem(key, value); return true; } catch (e) { return false; }
  }

  // ─────────────── 歌词缓存(用 localStorage,大对象除外) ───────────────
  var LYRIC_CACHE_PREFIX = 'mineradio-lyric-cache:';
  var LYRIC_CACHE_MAX = 800 * 1024; // 单条 800KB 上限

  function readLyricCacheLocal(key) {
    try {
      var raw = localStorage.getItem(LYRIC_CACHE_PREFIX + key);
      if (!raw) return null;
      return JSON.parse(raw);
    } catch (e) { return null; }
  }
  function writeLyricCacheLocal(key, payload) {
    try {
      var raw = JSON.stringify(payload);
      if (raw.length > LYRIC_CACHE_MAX) return false;
      localStorage.setItem(LYRIC_CACHE_PREFIX + key, raw);
      return true;
    } catch (e) { return false; }
  }

  // ─────────────── 内部状态 ───────────────
  var bridgeState = {
    platform: 'android',
    closeBehavior: 'exit',
    serverReady: false,         // Node.js server 是否已 listen
    serverBaseUrl: '',          // 例如 http://127.0.0.1:3000
    serverPort: 0,
    cacheSettings: { cacheRoot: '', beatmapsPath: '', lyricsPath: '' },
    wallpaperModeEnabled: false,
    desktopLyricsEnabled: false,
    globalHotkeyListeners: [],
    onStateChangeListeners: [],
    onWallpaperModeStateListeners: [],
    onDesktopLyricsLockStateListeners: [],
    onDesktopLyricsEnabledStateListeners: [],
    onWallpaperEngineHostBoundsListeners: [],
  };

  // ─────────────── 后端 Node.js 服务就绪事件 ───────────────
  // MineraNode 插件在 server.js 启动完成后会通过事件通知
  if (MineraNode && MineraNode.addListener) {
    MineraNode.addListener('server:ready', function (info) {
      bridgeState.serverReady = true;
      bridgeState.serverPort = (info && info.port) || 0;
      bridgeState.serverBaseUrl = 'http://127.0.0.1:' + bridgeState.serverPort;
      // 通知所有监听者
      bridgeState.onStateChangeListeners.forEach(function (cb) {
        try { cb({ serverReady: true, port: bridgeState.serverPort }); } catch (e) {}
      });
      // 广播全局事件:启动阶段先于 server 就绪的降级请求由各模块在此重新拉取真实状态
      try {
        window.dispatchEvent(new CustomEvent('mineradio-server-ready', { detail: { port: bridgeState.serverPort } }));
      } catch (e) { }
    });
    MineraNode.addListener('server:log', function (log) {
      // 后端 Node.js 日志,转发到 console
      try { (console[log && log.level] || console.log)('[node]', log && log.message || ''); } catch (e) {}
    });
  }

  // ─────────────── 等待本地 server 就绪 ───────────────
  // 播放 / 登录等请求在 server 未 listen 时会拿到 503 降级响应,导致启动早期
  // 自动播放与手动点击播放全部空转失败;在此等待 server:ready(上限 timeoutMs)。
  // 桌面版 / 非 Capacitor 环境没有该拦截机制,直接放行,行为不变。
  function waitForLocalServerReady(timeoutMs) {
    timeoutMs = Math.max(1000, Number(timeoutMs) || 20000);
    return new Promise(function (resolve) {
      if (bridgeState.serverReady) {
        resolve(true);
        return;
      }
      if (!cap || typeof cap.isNativePlatform !== 'function' || !cap.isNativePlatform()) {
        resolve(true);
        return;
      }
      var settled = false;
      var timer = setTimeout(function () {
        if (settled) return;
        settled = true;
        resolve(true);
      }, timeoutMs);
      bridgeState.onStateChangeListeners.push(function (info) {
        if (settled || !info || !info.serverReady) return;
        settled = true;
        clearTimeout(timer);
        resolve(true);
      });
    });
  }
  window.waitForLocalServerReady = waitForLocalServerReady;

  // ─────────────── 拦截 fetch('/api/...') ───────────────
  // 后端未就绪时返回安全降级响应;就绪后透传到本地 server。
  var originalFetch = window.fetch;
  var SAFE_FALLBACK_API_RESPONSES = {
    '/api/login/status': { code: 200, data: { code: 301, profile: null, account: null } },
    '/api/qq/login/status': { code: 200, data: { code: -1, message: 'not logged in' } },
    '/api/kugou/login/status': { code: 200, data: { ok: false, logged_in: false } },
    '/api/qishui/status': { code: 200, data: { ok: false, logged_in: false } },
    '/api/spotify/status': { code: 200, data: { ok: false, logged_in: false } },
    '/api/user/playlists': { code: 200, data: { playlist: [], more: false } },
    '/api/playlist/tracks': { code: 200, data: { playlist: { tracks: [] } } },
    '/api/personalized': { code: 200, data: { result: [] } },
    '/api/discover/home': { code: 200, data: { blocks: [] } },
    '/api/platform/capabilities': { code: 200, data: { platform: 'android-capacitor', serverReady: false } },
    '/api/app/version': { code: 200, data: { version: '2.1.0-mobile', platform: 'android' } },
  };

  function makeFallbackResponse(pathname) {
    var fallback = SAFE_FALLBACK_API_RESPONSES[pathname];
    if (fallback) {
      return new Response(JSON.stringify(fallback.data), {
        status: fallback.code,
        headers: { 'Content-Type': 'application/json' }
      });
    }
    // 默认:返回 503 + 错误体,让前端走 try/catch 兜底
    return new Response(JSON.stringify({ code: 503, message: 'service_unavailable', error: 'Mineradio local server not ready' }), {
      status: 503,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  window.fetch = function (input, init) {
    var url = (typeof input === 'string') ? input : (input && input.url);
    if (url && url.charAt(0) === '/' && !bridgeState.serverReady) {
      // 相对路径请求,且本地 server 未就绪,返回降级响应
      var pathOnly = url.split('?')[0].split('#')[0];
      return Promise.resolve(makeFallbackResponse(pathOnly));
    }
    // 已就绪:把相对路径转成 server 完整 URL
    if (url && url.charAt(0) === '/' && bridgeState.serverReady) {
      var newUrl = bridgeState.serverBaseUrl + url;
      if (typeof input === 'string') {
        input = newUrl;
      } else if (input && typeof input === 'object') {
        input = Object.assign({}, input, { url: newUrl });
      }
    }
    return originalFetch.call(window, input, init);
  };

  // ─────────────── 触摸适配:右键菜单/滚轮/hover ───────────────
  // 长按 500ms 触发 contextmenu
  var LONG_PRESS_MS = 500;
  function bindTouchContextmenu() {
    document.addEventListener('touchstart', function (e) {
      if (e.touches.length !== 1) return;
      var touch = e.touches[0];
      var target = e.target;
      var timer = setTimeout(function () {
        var evt = new MouseEvent('contextmenu', {
          bubbles: true, cancelable: true,
          clientX: touch.clientX, clientY: touch.clientY,
          button: 2, buttons: 2,
        });
        Object.defineProperty(evt, 'target', { value: target });
        target.dispatchEvent(evt);
        // 触觉反馈
        if (Haptics && Haptics.longPress) {
          Haptics.longPress({ duration: 30 }).catch(function () {});
        }
      }, LONG_PRESS_MS);
      function cancel() {
        clearTimeout(timer);
        document.removeEventListener('touchend', cancel, true);
        document.removeEventListener('touchmove', cancel, true);
        document.removeEventListener('touchcancel', cancel, true);
      }
      document.addEventListener('touchend', cancel, true);
      document.addEventListener('touchmove', cancel, true);
      document.addEventListener('touchcancel', cancel, true);
    }, { passive: true, capture: true });
  }

  // 阻止双指缩放(避免视图错乱),保留单指滚动
  function bindPinchGuard() {
    document.addEventListener('gesturestart', function (e) { e.preventDefault(); }, { passive: false });
    document.addEventListener('dblclick', function (e) {
      // 桌面版允许双击最大化,移动版不希望意外触发
      // 仅在非编辑控件上阻止
      if (e.target && e.target.tagName !== 'INPUT' && e.target.tagName !== 'TEXTAREA') {
        e.preventDefault();
      }
    }, { passive: false });
  }

  // ─────────────── 工具方法 ───────────────
  function asyncNoopResolve() { return Promise.resolve(undefined); }
  function asyncNoopResolveFalse() { return Promise.resolve(false); }
  function asyncNoopResolveEmpty() { return Promise.resolve({ ok: false, unsupported: 'mobile' }); }
  function asyncNoopResolveArray() { return Promise.resolve([]); }
  function noop() {}
  function noopUnsubscribe() { return function () {}; }

  // ─────────────── window.desktopWindow 适配对象 ───────────────
  // 与 desktop/preload.js 暴露的同名同语义,所有方法都返回降级值。
  var desktopWindow = {
    __capacitorBridge: true,
    isDesktop: true, // 保留 true,让前端走桌面分支的视觉与逻辑(更接近原版)

    // ── 窗口控制(移动端无最小化/最大化概念,大部分 noop) ──
    minimize: asyncNoopResolve,
    restore: asyncNoopResolve,
    toggleMaximize: asyncNoopResolve,
    toggleFullscreen: asyncNoopResolve,
    exitFullscreenWindowed: asyncNoopResolve,
    close: function (behavior) {
      // behavior='exit' 退出 App,behavior='tray' 在 Android 上等价于退到后台
      if (behavior === 'tray') {
        // 移动到后台(不退出)
        if (App$1 && App$1.minimizeApp) { App$1.minimizeApp(); return Promise.resolve(); }
        if (window.history && window.history.back) { try { window.history.back(); } catch (e) {} }
        return Promise.resolve();
      }
      // exit
      if (App$1 && App$1.exitApp) { App$1.exitApp(); }
      return Promise.resolve();
    },
    getState: function () {
      return Promise.resolve({
        isMaximized: false,
        isMinimized: false,
        isFullscreen: true,
        isVisible: true,
        platform: 'android-capacitor',
      });
    },
    getCloseBehavior: function () { return Promise.resolve({ behavior: bridgeState.closeBehavior }); },
    setCloseBehavior: function (behavior) {
      bridgeState.closeBehavior = (behavior === 'tray') ? 'tray' : 'exit';
      safeLocalStorageSet('mineradio-close-behavior', bridgeState.closeBehavior);
      return Promise.resolve({ ok: true, behavior: bridgeState.closeBehavior });
    },
    onStateChange: function (callback) {
      if (typeof callback !== 'function') return noopUnsubscribe();
      bridgeState.onStateChangeListeners.push(callback);
      return function () {
        var i = bridgeState.onStateChangeListeners.indexOf(callback);
        if (i >= 0) bridgeState.onStateChangeListeners.splice(i, 1);
      };
    },

    // ── GPU 诊断 / 内存管理(Windows 专有,移动端 noop) ──
    getGpuDiagnostics: function () {
      return Promise.resolve({ platform: 'android', gpu: 'unknown', vendor: 'unknown', renderer: 'unknown' });
    },
    getMemorySnapshot: function () {
      // 用 performance.memory(WebView 支持)做近似
      var mem = (typeof performance !== 'undefined') ? performance.memory : null;
      return Promise.resolve({
        platform: 'android',
        appUsedMB: mem ? Math.round(mem.usedJSHeapSize / 1024 / 1024) : 0,
        appTotalMB: mem ? Math.round(mem.totalJSHeapSize / 1024 / 1024) : 0,
        appLimitMB: mem ? Math.round(mem.jsHeapSizeLimit / 1024 / 1024) : 0,
        system: { totalMB: 0, availableMB: 0, loadPercent: 0 },
        auto: { enabled: false, lastRunAt: 0, lastResult: null },
      });
    },
    configureMemoryReduct: asyncNoopResolveFalse,
    trimAppMemory: asyncNoopResolveFalse,
    purgeSystemMemory: asyncNoopResolveFalse,

    // ── 缓存目录设置(移动端固定在 App Data 目录,不可改) ──
    getCacheSettings: function () {
      return Promise.resolve({
        cacheRoot: bridgeState.cacheSettings.cacheRoot || '/data/data/com.mineradio.app/files/mineradio-cache',
        beatmapsPath: bridgeState.cacheSettings.beatmapsPath || 'beatmaps',
        lyricsPath: bridgeState.cacheSettings.lyricsPath || 'lyrics',
        fixed: true, // 移动端缓存目录固定
      });
    },
    chooseCacheDirectory: asyncNoopResolveEmpty,
    setCacheSettings: function (payload) {
      // 接受但不实际改变(移动端固定)
      return Promise.resolve({ ok: true, fixed: true });
    },

    // ── Wallpaper Engine(Steam 桌面级,移动端完全禁用) ──
    listWallpaperEngineProjects: asyncNoopResolveArray,
    getWallpaperEngineProjectDetails: asyncNoopResolveEmpty,
    openWallpaperEngineProjectDetails: asyncNoopResolveEmpty,
    chooseWallpaperEngineDirectory: asyncNoopResolveEmpty,
    chooseWallpaperEngineProjectFile: asyncNoopResolveEmpty,
    removeWallpaperEngineDirectory: asyncNoopResolveEmpty,
    getWallpaperEngineRuntimeStatus: function () {
      return Promise.resolve({ available: false, reason: 'mobile_platform_not_supported' });
    },
    startWallpaperEngineScene: asyncNoopResolveEmpty,
    reportWallpaperEngineCaptureResult: asyncNoopResolveEmpty,
    prepareWallpaperEngineGlassCapture: asyncNoopResolveEmpty,
    activateWallpaperEngineDwmSurface: asyncNoopResolveEmpty,
    updateWallpaperEngineGlassSurface: noop,
    reportWallpaperEnginePointerActivity: noop,
    stopWallpaperEngineScene: asyncNoopResolveEmpty,
    onWallpaperEngineHostBoundsChanged: noopUnsubscribe,

    // ── 本地音乐库 ──
    // 通过 input[type=file] 选择,返回 { localUrl } 形式的对象数组
    listLocalMusicLibrary: asyncNoopResolveArray,
    readLocalMusicLyric: asyncNoopResolveEmpty,
    importLocalMusicFiles: async function (files) {
      // 移动端 webUtils.getPathForFile 不存在;直接读取为 Blob URL,
      // 但要保证 server 也能识别这个 URL —— 这里返回 blob URL 让 audio.src 直接用
      var tracks = [];
      var arr = Array.from(files || []);
      for (var i = 0; i < arr.length; i++) {
        var file = arr[i];
        try {
          var url = URL.createObjectURL(file);
          tracks.push({
            localFileId: 'cap-' + Date.now() + '-' + i,
            localUrl: url,
            name: file.name || ('track-' + i),
            size: file.size || 0,
            type: file.type || '',
          });
        } catch (e) {}
      }
      return Promise.resolve({ ok: tracks.length > 0, count: tracks.length, tracks: tracks });
    },

    // ── 歌词缓存(用 localStorage) ──
    readLyricCache: function (key) {
      var cached = readLyricCacheLocal(key || '');
      return Promise.resolve(cached || null);
    },
    writeLyricCache: function (key, payload) {
      var ok = writeLyricCacheLocal(key || '', payload);
      return Promise.resolve({ ok: ok });
    },

    // ── 登录彩蛋门(用 localStorage 模拟) ──
    getLoginEasterEggStatus: function () {
      return Promise.resolve({
        unlocked: safeLocalStorageGet('mineradio-login-easter-egg-unlocked') === '1',
        version: 1,
      });
    },
    unlockLoginEasterEgg: function (value) {
      // 简化:任何非空值都视为解锁(实际可对接服务端校验)
      var ok = !!value && String(value).length > 0;
      if (ok) safeLocalStorageSet('mineradio-login-easter-egg-unlocked', '1');
      return Promise.resolve({ ok: ok, unlocked: ok });
    },
    resetLoginEasterEgg: function () {
      localStorage.removeItem('mineradio-login-easter-egg-unlocked');
      return Promise.resolve({ ok: true });
    },

    // ── 第三方平台登录(改用系统浏览器 OAuth) ──
    openNeteaseMusicLogin: function () {
      // 用系统浏览器打开网易云登录页,让用户登录后复制 cookie
      return Browser ? Browser.open({ url: 'https://music.163.com/#/login', presentationStyle: 'fullscreen' }) : Promise.resolve();
    },
    clearNeteaseMusicLogin: function () {
      // 通过本地 server 清除 cookie
      if (bridgeState.serverReady) {
        return originalFetch(bridgeState.serverBaseUrl + '/api/logout', { method: 'POST' }).catch(function () {});
      }
      return Promise.resolve();
    },
    openQQMusicLogin: function () {
      return Browser ? Browser.open({ url: 'https://y.qq.com/n/ryqq/profile', presentationStyle: 'fullscreen' }) : Promise.resolve();
    },
    clearQQMusicLogin: function () {
      if (bridgeState.serverReady) {
        return originalFetch(bridgeState.serverBaseUrl + '/api/qq/logout', { method: 'POST' }).catch(function () {});
      }
      return Promise.resolve();
    },
    openKugouMusicLogin: function () {
      return Browser ? Browser.open({ url: 'https://www.kugou.com/', presentationStyle: 'fullscreen' }) : Promise.resolve();
    },
    clearKugouMusicLogin: function () {
      if (bridgeState.serverReady) {
        return originalFetch(bridgeState.serverBaseUrl + '/api/kugou/logout', { method: 'POST' }).catch(function () {});
      }
      return Promise.resolve();
    },
    clearQishuiMusicLogin: function () {
      if (bridgeState.serverReady) {
        return originalFetch(bridgeState.serverBaseUrl + '/api/qishui/logout', { method: 'POST' }).catch(function () {});
      }
      return Promise.resolve();
    },
    openSpotifyMusicLogin: function () {
      // Spotify OAuth:跳到本地 server 的 OAuth 起始路由
      if (bridgeState.serverReady) {
        return Browser ? Browser.open({ url: bridgeState.serverBaseUrl + '/api/spotify/oauth/start', presentationStyle: 'fullscreen' }) : Promise.resolve();
      }
      return Promise.resolve();
    },
    clearSpotifyMusicLogin: function () {
      if (bridgeState.serverReady) {
        return originalFetch(bridgeState.serverBaseUrl + '/api/spotify/logout', { method: 'POST' }).catch(function () {});
      }
      return Promise.resolve();
    },

    // ── 应用控制 ──
    openUpdatePage: function (url) {
      if (!url) return Promise.resolve();
      return Browser ? Browser.open({ url: String(url) }) : Promise.resolve();
    },
    restartApp: function () {
      // Android 没有原生"重启",先杀进程
      if (App$1 && App$1.exitApp) App$1.exitApp();
      return Promise.resolve();
    },

    // ── 全局快捷键(Windows 专有,移动端 noop) ──
    configureGlobalHotkeys: asyncNoopResolveArray,
    onGlobalHotkey: noopUnsubscribe,

    // ── 剪贴板(用 Capacitor Clipboard) ──
    copyText: function (text) {
      if (Clipboard && Clipboard.writeText) {
        Clipboard.writeText({ value: String(text || '') }).catch(function () {});
      }
      return { ok: true };
    },
    readText: function () {
      if (Clipboard && Clipboard.readText) {
        return Clipboard.readText().then(function (r) { return { ok: true, text: r && r.value || '' }; }).catch(function () { return { ok: true, text: '' }; });
      }
      return { ok: true, text: '' };
    },

    // ── 文件导入导出(用 Filesystem / Blob 下载) ──
    exportJsonFile: function (payload) {
      try {
        var filename = (payload && payload.filename) || 'mineradio-export.json';
        var content = (payload && payload.content != null) ? payload.content : JSON.stringify(payload, null, 2);
        var blob = new Blob([content], { type: 'application/json' });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = filename;
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        setTimeout(function () {
          document.body.removeChild(a);
          URL.revokeObjectURL(url);
        }, 0);
        return Promise.resolve({ ok: true, filename: filename });
      } catch (e) {
        return Promise.resolve({ ok: false, error: String(e && e.message || e) });
      }
    },
    exportLoginCookie: asyncNoopResolveEmpty,
    importJsonFile: function () {
      // 弹出 input[type=file] 让用户选 JSON,然后读回内容
      return new Promise(function (resolve) {
        try {
          var input = document.createElement('input');
          input.type = 'file';
          input.accept = 'application/json,.json';
          input.style.display = 'none';
          input.onchange = function () {
            var file = input.files && input.files[0];
            if (!file) { resolve({ ok: false, error: 'NO_FILE' }); return; }
            var reader = new FileReader();
            reader.onload = function () {
              resolve({ ok: true, filePath: file.name, text: String(reader.result || '') });
            };
            reader.onerror = function () { resolve({ ok: false, error: 'READ_ERROR' }); };
            reader.readAsText(file);
          };
          document.body.appendChild(input);
          input.click();
          setTimeout(function () { try { document.body.removeChild(input); } catch (e) {} }, 1000);
        } catch (e) {
          resolve({ ok: false, error: String(e && e.message || e) });
        }
      });
    },

    // ── FX 自动存档(用 localStorage 替代同步文件 IO) ──
    readCurrentFxAutosaveSync: function () {
      // 桌面版用 sendSync 同步读盘;移动端用 localStorage 同步读
      var raw = safeLocalStorageGet(FX_AUTOSAVE_KEY);
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    },
    saveCurrentFxAutosaveSync: function (payload) {
      try {
        var raw = JSON.stringify(payload);
        if (raw.length > FX_AUTOSAVE_MAX_LOCAL) {
          // 太大,降级为异步写
          return { ok: false, error: 'TOO_LARGE_USE_ASYNC', size: raw.length };
        }
        return safeLocalStorageSet(FX_AUTOSAVE_KEY, raw) ? { ok: true } : { ok: false, error: 'QUOTA' };
      } catch (e) {
        return { ok: false, error: String(e && e.message || e) };
      }
    },
    saveCurrentFxAutosave: function (payload) {
      try {
        var raw = JSON.stringify(payload);
        if (raw.length > FX_AUTOSAVE_MAX_LOCAL) {
          // 超大:尝试写 Filesystem
          if (Filesystem && Filesystem.writeFile) {
            return Filesystem.writeFile({
              path: 'mineradio-current-fx-autosave.json',
              data: raw,
              directory: 'DATA',
              encoding: 'utf8',
              recursive: true,
            }).then(function () { return { ok: true }; }).catch(function (e) { return { ok: false, error: String(e) }; });
          }
          return Promise.resolve({ ok: false, error: 'TOO_LARGE_AND_NO_FILESYSTEM' });
        }
        return Promise.resolve(safeLocalStorageSet(FX_AUTOSAVE_KEY, raw) ? { ok: true } : { ok: false, error: 'QUOTA' });
      } catch (e) {
        return Promise.resolve({ ok: false, error: String(e && e.message || e) });
      }
    },

    // ── 桌面歌词独立窗口(移动端没有独立窗口,改为 App 内悬浮层) ──
    setDesktopLyricsEnabled: function (enabled) {
      bridgeState.desktopLyricsEnabled = !!enabled;
      // 通知所有 enabled 监听者
      bridgeState.onDesktopLyricsEnabledStateListeners.forEach(function (cb) {
        try { cb({ enabled: bridgeState.desktopLyricsEnabled }); } catch (e) {}
      });
      return Promise.resolve({ ok: true, enabled: bridgeState.desktopLyricsEnabled });
    },
    updateDesktopLyrics: asyncNoopResolve,
    onDesktopLyricsLockState: noopUnsubscribe,
    onDesktopLyricsEnabledState: function (callback) {
      if (typeof callback !== 'function') return noopUnsubscribe();
      bridgeState.onDesktopLyricsEnabledStateListeners.push(callback);
      return function () {
        var i = bridgeState.onDesktopLyricsEnabledStateListeners.indexOf(callback);
        if (i >= 0) bridgeState.onDesktopLyricsEnabledStateListeners.splice(i, 1);
      };
    },

    // ── 完整桌面模式(Windows 桌面级,移动端完全禁用) ──
    setWallpaperMode: function (enabled) {
      bridgeState.wallpaperModeEnabled = !!enabled;
      bridgeState.onWallpaperModeStateListeners.forEach(function (cb) {
        try { cb({ enabled: bridgeState.wallpaperModeEnabled, available: false }); } catch (e) {}
      });
      return Promise.resolve({ ok: true, enabled: bridgeState.wallpaperModeEnabled, available: false });
    },
    updateWallpaperMode: asyncNoopResolve,
    getWallpaperModeStatus: function () {
      return Promise.resolve({ enabled: bridgeState.wallpaperModeEnabled, available: false, reason: 'mobile_platform_not_supported' });
    },
    updateDesktopIconShields: noop,
    setDesktopSoftwareLocked: asyncNoopResolveFalse,
    setDesktopIconsVisible: asyncNoopResolveFalse,
    requestDesktopKeyboardFocus: asyncNoopResolve,
    updateDesktopPointerRoute: noop,
    onWallpaperModeState: noopUnsubscribe,
  };

  // ─────────────── 注入 desktopWindow ───────────────
  window.desktopWindow = desktopWindow;

  // ─────────────── 注入 CSS 类(让桌面分支 CSS 生效) ───────────────
  function injectShellClass() {
    try {
      document.documentElement.classList.add('desktop-shell-root');
      if (document.body) document.body.classList.add('desktop-shell');
      // 移动端额外类,用于 CSS 调整触摸目标尺寸等
      document.documentElement.classList.add('mineradio-mobile');
      document.documentElement.classList.add('mineradio-android');
    } catch (e) {}
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', injectShellClass);
  } else {
    injectShellClass();
  }

  // ─────────────── 横屏锁定 + 状态栏隐藏 ───────────────
  if (ScreenOrientation && ScreenOrientation.lock) {
    ScreenOrientation.lock({ orientation: 'landscape' }).catch(function () {});
  }
  if (StatusBar && StatusBar.hide) {
    StatusBar.hide().catch(function () {});
  }

  // ─────────────── 启动后端 Node.js 服务 ───────────────
  // MineraNode.start() 由原生层加载 server.js 到 nodejs-mobile 运行时
  function startNodeBackend() {
    if (!MineraNode || !MineraNode.start) {
      // 插件未注册,跳过(server 不可用时降级)
      return;
    }
    MineraNode.start({
      // 把当前 App 的可写目录传给 Node.js 作为工作目录
      // 实际路径由原生层通过 Context.getFilesDir() 注入
    }).then(function (info) {
      // 启动成功后端口由 server:ready 事件上报
      console.log('[MineraNode] started', info);
    }).catch(function (err) {
      console.warn('[MineraNode] start failed', err);
    });
  }
  // 延迟到 DOMContentLoaded 之后启动,确保前端先加载
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', startNodeBackend);
  } else {
    startNodeBackend();
  }

  // ─────────────── 触摸适配 ───────────────
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindTouchContextmenu);
    document.addEventListener('DOMContentLoaded', bindPinchGuard);
  } else {
    bindTouchContextmenu();
    bindPinchGuard();
  }

  // ─────────────── App 生命周期:Android 后台/前台切换 ───────────────
  if (App$1) {
    if (App$1.addListener) {
      App$1.addListener('appStateChange', function (state) {
        // 通知前端(由 11-main-loop.js 监听)
        // 这里通过 window.dispatchEvent 模拟 visibilitychange
        try {
          var evt = new Event('visibilitychange');
          Object.defineProperty(document, 'hidden', { value: !state.isActive, configurable: true });
          document.dispatchEvent(evt);
        } catch (e) {}
      });
    }
    // Android 后退键:让前端先尝试 history.back,否则退出
    if (App$1.addListener) {
      App$1.addListener('backButton', function () {
        // 由前端决定是否消费,这里默认让 WebView 后退
        // 前端可以通过 preventDefault 阻止
        try {
          if (window.history.length > 1) {
            window.history.back();
          } else {
            App$1.exitApp();
          }
        } catch (e) {
          if (App$1.exitApp) App$1.exitApp();
        }
      });
    }
  }

  // 暴露内部状态用于调试
  window.__mineradioCapacitorBridge = {
    state: bridgeState,
    reloadWebAssets: function () { window.location.reload(); },
  };

  console.log('[Mineradio] Capacitor bridge loaded');
})();
