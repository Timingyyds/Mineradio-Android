// public/js/android-shim/40-wallpaper-bridge.js
// 给壁纸面板接上原生 WallpaperManager。
//
// 背景：03-wallpaper-engine-library.js 面板调用 getDesktopWindowApi() 上的
// listWallpaperEngineProjects 等方法。这些在桌面版是 Electron 的 IPC，
// 原生工程里全部是空实现（capacitor-bridge.js 注释「移动端完全禁用」）——
// 所以面板一直显示「仅桌面版支持本地壁纸识别」，导入和面板都不可用。
//
// 这里做一层适配：安卓上把 MpkgWallpaper（Capacitor 插件，包着 WallpaperManager）
// 暴露成面板期望的那组方法。面板代码一行不改。
//
// 数据形状对照（面板 normalizeWallpaperEngineProject 消费的字段）：
//   { id, name, type: 'mpkg'|'video'|'image'|'web', author, previewUrl, ... }
// 插件返回的是 WallpaperEntity（wallpaperType 0..4），在这里映射。

(function (root) {
  'use strict';

  /** WallpaperEntity.TYPE_* → 面板的 type 字符串 */
  var TYPE_NAMES = { 0: 'web', 1: 'video', 2: 'view', 3: 'image', 4: 'mpkg' };

  function capacitor() {
    return root.Capacitor && root.Capacitor.isNativePlatform && root.Capacitor.isNativePlatform()
      ? root.Capacitor : null;
  }

  function plugin(quiet) {
    var cap = capacitor();
    if (!cap) {
      if (!quiet) console.warn('[MpkgBridge] Capacitor 不存在');
      return null;
    }
    if (!cap.Plugins) {
      if (!quiet) console.warn('[MpkgBridge] Capacitor.Plugins 不存在');
      return null;
    }
    if (!cap.Plugins.MpkgWallpaper) {
      if (!quiet) console.warn('[MpkgBridge] MpkgWallpaper 插件未注册。可用插件:', Object.keys(cap.Plugins).join(', '));
      return null;
    }
    return cap.Plugins.MpkgWallpaper;
  }

  /**
   * 导入入口：弹系统文件选择器（SAF），由原生侧流式导入并把结果推回面板列表。
   *
   * ★ 之前这个函数只被 injectImportButton 引用、却从未定义 —— 一旦捕获层被绑定，
   *   点击「选择目录」就会抛 ReferenceError: openImportPicker is not defined，
   *   导入流程直接断掉。这里补上实现。
   */
  async function openImportPicker() {
    // ★ 用私有全局，不读 window.desktopWindow（那会让其它模块误判成端游）
    var api = root.__mpkgWallpaperApi;
    if (!api || typeof api.chooseWallpaperEngineDirectory !== 'function') {
      if (typeof root.showToast === 'function') root.showToast('当前环境不支持导入');
      return;
    }
    try {
      var snap = await api.chooseWallpaperEngineDirectory();
      if (!snap || snap.ok === false) {
        if (typeof root.showToast === 'function') {
          root.showToast((snap && snap.error) || '导入失败');
        }
      }
    } catch (e) {
      if (typeof root.showToast === 'function') {
        root.showToast('导入失败: ' + (e && e.message || e));
      }
    }
  }

  /**
 * 把原生 id（wp_时间戳 等）映射成面板要求的 24 位十六进制 id。
 *
 * 面板的 normalizeWallpaperEngineProject 有硬校验：
 *   id 去掉非 [a-f0-9] 后必须恰好 24 位，否则整个条目被丢弃 ——
 * 之前列表一直是空的，这比"接口没接"还隐蔽。
 * 映射关系保存在 localStorage，保证同一个原生 id 每次得到同一个面板 id。
 */
var PANEL_ID_MAP_KEY = 'mineradio_wp_panel_id_map_v2';
// ★ v1 的映射表里存的是碰撞后的同一个 id（所有壁纸共用一个），
//   必须用新 key 让旧缓存失效，否则面板仍会把多张壁纸当成同一张。
//   同时清理旧 key，避免占空间。
try { root.localStorage.removeItem('mineradio_wp_panel_id_map'); } catch (e) {}
var panelIdMap = null;

function loadPanelIdMap() {
  if (panelIdMap) return panelIdMap;
  try { panelIdMap = JSON.parse(root.localStorage.getItem(PANEL_ID_MAP_KEY) || '{}'); } catch (e) { panelIdMap = {}; }
  /*
   * ★ 旧版本把 panelId 也当 nativeId 写进这张表（key 不是 wp_ 形式），
   *   导致同一张壁纸每次启动可能算出不同 panelId ——
   *   隐藏/收藏/删除的 id 全部对不上，表现为「点了隐藏还在、删了又回来」。
   *   这里把非原生 id 的脏条目清掉，映射恢复到 nativeId → panelId 的单向关系。
   */
  if (panelIdMap && typeof panelIdMap === 'object') {
    var dirty = false;
    for (var k in panelIdMap) {
      if (!/^wp_/.test(k)) { delete panelIdMap[k]; dirty = true; }
    }
    if (dirty) {
      try { root.localStorage.setItem(PANEL_ID_MAP_KEY, JSON.stringify(panelIdMap)); } catch (e) { }
    }
  }
  return panelIdMap;
}

function nativeIdFor(panelId) {
  var map = loadPanelIdMap();
  for (var k in map) if (map[k] === panelId) return k;
  // 兜底：本来就是原生 id
  return panelId;
}

function panelIdFor(nativeId) {
  var map = loadPanelIdMap();
  if (map[nativeId]) return map[nativeId];
  // 由原生 id 派生 24 位 hex（确定性，不随机，重启不变）。
  //
  // ★★★ 之前这里有个致命 bug：算法是
  //     for (...) hex += ((charCode*31 + i*7) % 65536).toString(16)
  //     hex = (hex + '0000...').slice(0, 24)   // 只取前 24 位
  //   而原生 id 都是 "wp_1793xxxxxxx_xxx" 形式，前 6 个字符完全相同！
  //   24 位 hex = 6 个字符的产出 → 所有壁纸算出同一个 panelId，
  //   面板里 4 张卡片 id 全一样，点哪张都命中第一张（视频包），
  //   于是"只能用那一个壁纸"、场景包永远选不中。
  //
  //   改成对完整字符串做 3 段独立 32 位哈希再拼接，24 位 hex 全部有效。
  var s = String(nativeId || 'wp');
  var h1 = 0x811c9dc5, h2 = 0x1000193, h3 = 0x9e3779b9;
  for (var i = 0; i < s.length; i += 1) {
    var c = s.charCodeAt(i);
    h1 = (h1 ^ c) >>> 0;
    h1 = Math.imul(h1, 16777619) >>> 0;
    h2 = (h2 + Math.imul(c + 1, 2654435761)) >>> 0;
    h3 = (h3 ^ Math.imul(c, 40503)) >>> 0;
    h3 = ((h3 << 13) | (h3 >>> 19)) >>> 0;
  }
  var hex = ((h1 >>> 0).toString(16).padStart(8, '0')
    + (h2 >>> 0).toString(16).padStart(8, '0')
    + (h3 >>> 0).toString(16).padStart(8, '0')).slice(0, 24);
  map[nativeId] = hex;
  try { root.localStorage.setItem(PANEL_ID_MAP_KEY, JSON.stringify(map)); } catch (e) {}
  return hex;
}

/** WallpaperEntity → 面板的项目对象。字段名按 normalizeWallpaperEngineProject 的硬校验给。 */
  function toProject(w) {
    if (!w) return null;
    /*
     * ★ 已经是面板项目（consumeWallpaperEngineSnapshot 之后）时直接原样返回。
     *
     * 这些对象的 id 是 24 位面板 id、previewData 是已经抓好的 dataUrl。
     * 再按 WallpaperEntity 处理会：
     *   1) 把 previewData 硬编码成 '' → 面板所有封面变黑块；
     *   2) 用 panelIdFor(panelId) 二次哈希 → 卡片 id 漂移，
     *      点击应用时反查不到原生 id → 「壁纸媒体不可用，已恢复原背景」。
     */
    if (w.source === 'android-mpkg' && w.nativeId && /^[a-f0-9]{24}$/i.test(String(w.id || ''))) {
      return Object.assign({}, w);
    }
    var typeName = TYPE_NAMES[w.wallpaperType] || 'web';
    var isMpkg = Number(w.wallpaperType) === 4;
    var isVideo = Number(w.wallpaperType) === 1;
    var isImage = Number(w.wallpaperType) === 3;
    var project = {
      // ★ 24 位 hex：面板的硬性要求
      id: panelIdFor(w.id),
      nativeId: w.id,
      name: w.name || w.id,
      // 面板语义：projectType=scene 才能走引擎渲染；mediaType 决定 <video>/<img>
      projectType: isMpkg ? 'scene' : 'media',
      mediaType: isVideo ? 'video' : (isImage ? 'image' : ''),
      mediaAnimated: isVideo || isMpkg,
      // 可播放：mpkg/视频给 enginePlayable（走引擎），图片走 media
      playable: isImage || isVideo,
      enginePlayable: isMpkg,
      previewOnly: false,
      hasPreview: false,
      previewAnimated: false,
      source: 'android-mpkg',
      sourceLabel: '本地导入',
      workshopId: '',
      propertyCount: 0,
      audioPropertyCount: 0,
      mutedAudioPropertyCount: 0,
      updatedAt: Date.now(),
      safetyMode: isMpkg ? 'native-engine' : 'direct-media',
      // 原始字段（本适配层自己的消费方用）
      type: typeName,
      wallpaperType: w.wallpaperType,
      author: w.author || '',
      description: w.description || '',
      title: w.name || w.id,
      available: w.fileExists !== false,
      fileExists: w.fileExists !== false,
      previewUrl: '',
      preview: '',
      // dataUrl 由 getPreview 抓回后填上；面板渲染 img 时优先取它
      previewData: '',
      mpkgPrivatePath: w.mpkgPrivatePath || '',
      path: w.path || '',
    };
    return project;
  }

  /**
   * 面板快照形状：consumeWallpaperEngineSnapshot 消费
   * { ok, count, projects, directories, mediaToken }
   */
  /**
   * 生成 48 位十六进制 mediaToken。
   *
   * ★ 面板 consumeWallpaperEngineSnapshot 只认 /^[a-f0-9]{48}$/；
   *   之前塞的是 Date.now()（13 位数字），token 会被直接清空，
   *   mineradio-wallpaper:// 预览地址因此永远加载失败。
   */
  function newWallpaperMediaToken() {
    var token = '';
    for (var i = 0; i < 48; i += 1) token += Math.floor(Math.random() * 16).toString(16);
    return token;
  }

  /** 原生 WallpaperEntity 数组 → 面板快照。 */
  function buildSnapshot(list, currentId) {
    var projects = (list || []).map(toProject).filter(Boolean);
    return snapshotFromProjects(projects, currentId);
  }

  /**
   * 面板项目数组 → 快照：projects 原样传递，绝不再跑 toProject。
   *
   * 导入流程必须用这个函数。用 buildSnapshot 会把已抓好的封面清空、
   * 把面板 id 二次哈希（详见 toProject 里的说明）。
   */
  function snapshotFromProjects(projects, currentId) {
    var arr = Array.isArray(projects) ? projects.slice() : [];
    return {
      ok: true,
      count: arr.length,
      projects: arr,
      directories: [],
      mediaToken: newWallpaperMediaToken(),
      currentId: currentId || '',
      source: 'android-mpkg',
    };
  }

  // ─── 封面缓存 ───────────────────────────────────────────────────
  /*
   * 原生 getPreview 对 MPKG 要解开几十 MB 的包再抽帧/缩略，列表一多，
   * 每次打开面板都全量重抓就是「每次重新加载所有壁纸」的卡顿来源。
   * 这里按 nativeId 把抓到的 dataUrl 存进 localStorage：
   *   · 列表刷新先命中缓存，只对缺失项调原生；
   *   · 最多 24 张 / 3MB，超出按最久未用淘汰，避免撑爆 WebView 存储。
   */
  /*
   * v2：封面改为「引擎实际渲染帧优先」，旧版缓存里存的是包内 preview.gif，
   * 升级 key 让旧封面失效一次，重新抓成与后台一致的画面。
   */
  var COVER_CACHE_KEY = 'mineradio_wp_cover_cache_v2';
  var COVER_CACHE_MAX_ITEMS = 24;
  var COVER_CACHE_MAX_BYTES = 3 * 1024 * 1024;
  var coverCache = null;

  function loadCoverCache() {
    if (coverCache) return coverCache;
    try {
      var raw = JSON.parse(root.localStorage.getItem(COVER_CACHE_KEY) || '{}');
      coverCache = raw && typeof raw === 'object' ? raw : {};
    } catch (e) {
      coverCache = {};
    }
    return coverCache;
  }

  function coverCacheBytes(cache) {
    var total = 0;
    for (var k in cache) total += String(cache[k] && cache[k].dataUrl || '').length;
    return total;
  }

  function saveCoverCache() {
    var cache = coverCache || {};
    var keys = Object.keys(cache).sort(function (a, b) {
      return (cache[b].at || 0) - (cache[a].at || 0);
    });
    while (keys.length > COVER_CACHE_MAX_ITEMS || coverCacheBytes(cache) > COVER_CACHE_MAX_BYTES) {
      var drop = keys.pop();
      if (!drop) break;
      delete cache[drop];
    }
    try { root.localStorage.setItem(COVER_CACHE_KEY, JSON.stringify(cache)); } catch (e) { }
  }

  function cachedCover(nativeId) {
    var hit = loadCoverCache()[String(nativeId || '')];
    return hit && typeof hit.dataUrl === 'string' ? hit : null;
  }

  function rememberCover(nativeId, dataUrl) {
    var id = String(nativeId || '');
    if (!id || typeof dataUrl !== 'string' || !dataUrl) return;
    var cache = loadCoverCache();
    cache[id] = { dataUrl: dataUrl, at: Date.now() };
    saveCoverCache();
  }

  // ─── 安装到 desktopWindow API ───────────────────────────────────

  function install() {
    var p = plugin();
    if (!p) {
      console.log('[MpkgBridge] 非原生环境或插件未注册，壁纸面板保持桌面提示');
      return;
    }

    // ★★★ 关键设计：**不碰 window.desktopWindow，也不设 isDesktop**。
    //
    // 为什么不能像以前那样 `api.isDesktop = true; root.desktopWindow = api;`：
    //   整个 App 把「window.desktopWindow 存在 / isDesktop 为真」当作「这是端游」的判据，
    //   至少有这些地方在读它：
    //     · 10-shell/04-desktop-overlay-fullscreen.js:1533  initDesktopWindowShell()
    //         → 给 <body> 加 .desktop-shell → 手机上冒出桌面标题栏（右上角最小化 / 关闭）
    //           并且 #fx-panel 会切到 `body.desktop-shell.diy-mode #fx-panel` 那套布局，
    //           右下角设置面板看起来就"不见了"
    //     · 00-state/08-desktop-render-power.js:15  desktop: !!window.desktopWindow
    //     · 10-shell/01/02、08-account/03 的一堆端游专属分支
    //
    // 正确做法：把壁纸 API 挂在自己的私有全局上，然后**只给壁纸面板走的那条路**
    // 打补丁 —— getDesktopWindowApi() 是 07-fx/03-wallpaper-engine-library.js:243 唯一
    // 用来判定壁纸环境的函数，改它不会波及任何其它模块。
    var api = root.__mpkgWallpaperApi || {};
    root.__mpkgWallpaperApi = api;

    var coverBackfillRunning = false;

    /*
     * ─── 项目元数据持久化（对齐原版 IndexedDB 的「秒开」效果）───
     *
     * 原版 Mineradio-Android 把本地导入的壁纸项目连同封面写进 IndexedDB，
     * 面板一打开直接 restore 就渲染，完全不碰原生扫描。
     * 安卓原生侧已经持有 mpkg 文件本身，这里不需要再存 Blob，
     * 只持久化面板需要的项目字段（含 panelId ↔ nativeId 映射），
     * 封面仍由 coverCache 提供（见上面 COVER_CACHE_KEY）。
     */
    var PROJECT_CACHE_KEY = 'mineradio_wp_projects_cache_v1';

    function projectMeta(p) {
      return {
        id: String(p.id || ''),
        // 归一化可能丢掉 nativeId（旧数据），用 panelId 反查一次
        nativeId: String(p.nativeId || nativeIdFor(p.id) || ''),
        title: String(p.title || ''),
        projectType: String(p.projectType || ''),
        mediaType: String(p.mediaType || ''),
        mediaAnimated: p.mediaAnimated === true,
        playable: p.playable === true,
        enginePlayable: p.enginePlayable === true,
        previewOnly: p.previewOnly === true,
        hasPreview: p.hasPreview === true,
        previewAnimated: p.previewAnimated === true,
        source: 'android-mpkg',
        sourceLabel: String(p.sourceLabel || '本地 MPKG'),
        workshopId: String(p.workshopId || ''),
        propertyCount: Number(p.propertyCount) || 0,
        audioPropertyCount: Number(p.audioPropertyCount) || 0,
        mutedAudioPropertyCount: Number(p.mutedAudioPropertyCount) || 0,
        updatedAt: Number(p.updatedAt) || Date.now(),
        safetyMode: String(p.safetyMode || 'native-engine'),
      };
    }

    function persistProjects() {
      try {
        var arr = Array.isArray(root.wallpaperEngineProjects) ? root.wallpaperEngineProjects : [];
        var metas = arr.map(projectMeta).filter(function (m) {
          return /^[a-f0-9]{24}$/i.test(m.id);
        });
        root.localStorage.setItem(PROJECT_CACHE_KEY, JSON.stringify({ at: Date.now(), projects: metas }));
      } catch (e) {
        // 存储满/被禁用时忽略：这只影响下次启动的首屏速度，不影响功能
      }
    }

    /** 面板打开时先调用：同步返回是否恢复了缓存（恢复后直接出卡片）。 */
    function restoreProjects() {
      try {
        var raw = JSON.parse(root.localStorage.getItem(PROJECT_CACHE_KEY) || '{}');
        var metas = (raw && raw.projects) || [];
        if (!metas.length) return false;
        var arr = metas.map(function (m) {
          var p = Object.assign({}, m);
          // 以 nativeId 重新派生 panelId：旧缓存里的 id 可能来自已修复的二次哈希问题，
          // 不重算会让隐藏/收藏/删除都指向一个列表里不存在的 id。
          if (m.nativeId) {
            p.nativeId = String(m.nativeId);
            p.id = panelIdFor(String(m.nativeId));
          }
          var cover = cachedCover(m.nativeId);
          if (cover) {
            p.hasPreview = true;
            p.previewData = cover.dataUrl;
          }
          return p;
        });
        root.wallpaperEngineProjects = arr;
        if (typeof root.consumeWallpaperEngineSnapshot === 'function') {
          root.consumeWallpaperEngineSnapshot(snapshotFromProjects(arr, ''));
        } else if (typeof root.renderWallpaperEngineLibrary === 'function') {
          root.renderWallpaperEngineLibrary();
        }
        console.log('[MpkgBridge] 已从本地缓存恢复壁纸项目:', arr.length);
        return true;
      } catch (e) {
        return false;
      }
    }

    /**
     * 后台补抓缺少封面的项目。
     *
     * ★ 不再在 listWallpaperEngineProjects 里 await 全部 getPreview：
     *   每张 MPKG 要解包、抽帧/缩略，库里多张时会连续阻塞十几秒，
     *   面板只能显示「正在读取…」且一张卡片都没有。现在先返回列表（缓存命中直接出图），
     *   缺封面的再在后台逐张补，抓到一个就刷新一次，界面始终可用。
     */
    var COVER_BACKFILL_LIMIT = 8;

    async function backfillCovers(rawList, currentId) {
      if (coverBackfillRunning) return;
      coverBackfillRunning = true;
      try {
        /*
         * ★ 一次最多补 8 张，且新导入的优先。
         *   用户库里可能有几十个包，如果打开面板就逐项 getPreview，
         *   每个都要解 1MB 预览/抽帧，几秒到几十秒不等 —— 这就是「加载半天」。
         *   其余项目留给用户滚动/点选时再取，面板先保持可操作。
         */
        var queue = (rawList || []).slice().sort(function (a, b) {
          return String(b.id || '').localeCompare(String(a.id || ''));
        });
        var done = 0;
        for (var i = 0; i < queue.length && done < COVER_BACKFILL_LIMIT; i += 1) {
          var raw = queue[i];
          if (!raw || cachedCover(raw.id)) continue;
          done += 1;
          var pv = null;
          try { pv = await p.getPreview({ nativeId: raw.id }); } catch (e) { pv = null; }
          if (!pv || !pv.dataUrl) continue;
          rememberCover(raw.id, pv.dataUrl);
          var arr = Array.isArray(root.wallpaperEngineProjects)
            ? root.wallpaperEngineProjects.slice() : [];
          var panelId = panelIdFor(raw.id);
          var idx = arr.findIndex(function (pr) { return pr.id === panelId; });
          if (idx >= 0) {
            arr[idx].hasPreview = true;
            arr[idx].previewData = pv.dataUrl;
          } else {
            var np = toProject(raw);
            if (np) {
              np.hasPreview = true;
              np.previewData = pv.dataUrl;
              arr.push(np);
            }
          }
          root.wallpaperEngineProjects = arr;
          if (typeof root.consumeWallpaperEngineSnapshot === 'function') {
            root.consumeWallpaperEngineSnapshot(snapshotFromProjects(arr, currentId));
          } else if (typeof root.renderWallpaperEngineLibrary === 'function') {
            root.renderWallpaperEngineLibrary(true);
          }
          persistProjects();
          console.log('[MpkgBridge] 后台补抓封面成功:', raw.id, pv.dataUrl.length, 'B');
          await new Promise(function (resolve) { setTimeout(resolve, 24); });
        }
      } finally {
        coverBackfillRunning = false;
      }
    }

    // ── 列表 ──
    api.listWallpaperEngineProjects = async function () {
      try {
        var answer = await p.listWallpapers();
        var data = answer || {};
        var list = data.wallpapers || [];
        var projects = (list || []).map(toProject).filter(Boolean);
        // ① 先套缓存：有封面的项目直接显示，不再走原生解码
        for (var i = 0; i < projects.length && i < list.length; i += 1) {
          var proj = projects[i];
          var raw = list[i];
          if (!proj || !raw) continue;
          var hit = cachedCover(raw.id);
          if (hit) {
            proj.hasPreview = true;
            proj.previewData = hit.dataUrl;
          }
        }
        var snap = snapshotFromProjects(projects, data.currentId);
        // ② 缺失封面放后台补抓，先把列表还给面板，避免整屏卡在加载占位
        backfillCovers(list, data.currentId);
        return snap;
      } catch (error) {
        return { ok: false, error: error && error.message || String(error), projects: [], count: 0 };
      }
    };

    // ── 详情（面板点开条目时用） ──
    api.getWallpaperEngineProjectDetails = async function (payload) {
      var id = payload && (payload.id || payload.projectId) || '';
      try {
        var answer = await p.listWallpapers();
        var list = (answer && answer.wallpapers) || [];
        var found = list.filter(function (w) { return w.id === id; })[0];
        return found ? toProject(found) : null;
      } catch (error) {
        return null;
      }
    };
    api.openWallpaperEngineProjectDetails = async function (payload) {
      // 面板的详情弹层由它渲染；安卓上直接返回详情数据即可
      return api.getWallpaperEngineProjectDetails(payload);
    };

    // ── 导入：单文件（导入项目）与整个文件夹（导入目录）两条独立链路 ──
    // 面板 HTML 上已经是两个按钮：
    //   chooseWallpaperEngineProjectFile() → 选一个 .mpkg / 视频 / 图片
    //   chooseWallpaperEngineDirectory()   → 选一个文件夹，批量导入里面的壁纸
    function mergeImportedWallpapers(list, currentId, autoApply) {
      var projects = (list || []).map(toProject).filter(Boolean);
      if (!projects.length) return { ok: false, error: '没有可导入的壁纸' };
      var merged = Array.isArray(root.wallpaperEngineProjects)
        ? root.wallpaperEngineProjects.slice() : [];
      for (var i = 0; i < projects.length; i += 1) {
        var pj = projects[i];
        var dup = merged.findIndex(function (pr) { return pr.id === pj.id; });
        if (dup >= 0) merged[dup] = pj; else merged.push(pj);
      }
      var current = currentId || projects[0].id;
      root.wallpaperEngineProjects = merged;
      if (typeof root.consumeWallpaperEngineSnapshot === 'function') {
        root.consumeWallpaperEngineSnapshot(snapshotFromProjects(merged, current));
      }
      if (typeof root.renderWallpaperEngineLibrary === 'function') {
        root.renderWallpaperEngineLibrary();
      }
      persistProjects();
      // 封面后台补（只补这批新导入的）
      backfillCovers(list, current);
      // 后台核对原生列表（补齐内存里还没有的旧项目），不阻塞导入返回
      p.listWallpapers().then(function (rescan) {
        var rescanList = (rescan && rescan.wallpapers) || [];
        var arr = Array.isArray(root.wallpaperEngineProjects)
          ? root.wallpaperEngineProjects.slice() : [];
        for (var ri = 0; ri < rescanList.length; ri += 1) {
          var rp = toProject(rescanList[ri]);
          if (!rp) continue;
          if (!arr.some(function (pr) { return pr.id === rp.id; })) arr.push(rp);
        }
        root.wallpaperEngineProjects = arr;
        if (typeof root.consumeWallpaperEngineSnapshot === 'function') {
          root.consumeWallpaperEngineSnapshot(snapshotFromProjects(arr, current));
        }
        persistProjects();
      }).catch(function (rescanErr) {
        console.warn('[MpkgBridge] 原生列表复核失败，仅用缓存合并:', rescanErr && rescanErr.message);
      });
      if (autoApply) applyImportedWallpaper(projects[0]);
      return snapshotFromProjects(root.wallpaperEngineProjects, current);
    }

    /** 导入即应用：后台登记原生背景并启动场景，失败静默（用户可手动再点）。 */
    function applyImportedWallpaper(project) {
      if (!project || !project.nativeId) return;
      try {
        var setApi = root.OnlineWpNative;
        if (setApi && typeof setApi.librarySetAppBg === 'function') {
          Promise.resolve(setApi.librarySetAppBg(project.nativeId)).then(function (setRes) {
            if (setRes && setRes.ok && typeof root.wpLibrarySyncAppBg === 'function') {
              root.wpLibrarySyncAppBg().catch(function () { });
            }
          }).catch(function (applyErr) {
            console.warn('[MpkgBridge] 导入即应用（原生登记）失败:', applyErr && applyErr.message);
          });
        }
      } catch (applyErr) {
        console.warn('[MpkgBridge] 导入即应用（原生登记）失败:', applyErr && applyErr.message);
      }
    }

    async function importFromPicker(picker, autoApply) {
      try {
        var answer = await picker();
        var single = answer && answer.wallpaper;
        var many = answer && answer.wallpapers;
        var list = single ? [single] : (Array.isArray(many) ? many : []);
        if (!list.length) {
          return { ok: false, error: (answer && answer.error) || '未选择壁纸' };
        }
        var first = toProject(list[0]);
        var snap = mergeImportedWallpapers(list, first ? first.id : '', autoApply);
        if (typeof root.showToast === 'function') {
          root.showToast(list.length > 1
            ? ('已导入 ' + list.length + ' 张壁纸')
            : ('已添加到壁纸面板: ' + ((first && (first.title || first.name)) || '壁纸')));
        }
        return snap;
      } catch (error) {
        console.error('[MpkgBridge] 导入失败:', error && error.message || error);
        return { ok: false, error: error && error.message || String(error) };
      }
    }

    api.chooseWallpaperEngineProjectFile = async function () {
      return importFromPicker(function () { return p.pickAndImport({}); }, true);
    };
    api.chooseWallpaperEngineDirectory = async function () {
      // 不再走文件夹授权：直接调多选文件选择器，选一个或多个都能导入
      return importFromPicker(function () { return p.pickAndImport({}); }, false);
    };
    api.removeWallpaperEngineDirectory = async function () {
      return { ok: true };
    };

    // ── 场景生命周期（应用内背景层由 LWService/原生层负责，这里上报成功让面板不再等待） ──
    var lastSceneStartId = '';
    var lastSceneStartAt = 0;
    api.startWallpaperEngineScene = async function (payload) {
      try {
        var panelId = payload && (payload.id || payload.projectId);
        if (!panelId) return { ok: false, error: '缺少壁纸 id' };
        var nativeId = nativeIdFor(panelId);
        // ★★★ 反查兜底：localStorage 里的 panelId↔nativeId 映射可能丢失
        //   （用户清除应用数据 / WebView 存储被系统重建），但壁纸库本身存在**外部文件**
        //   （wallpaper_config.json，清数据不删）。此时 nativeIdFor 退化成返回 panelId，
        //   插件按它查库查不到 → 整条「应用壁纸」流程静默失败。
        //   这里用原生列表把每个 nativeId 重算一遍 panelId，命中即取回真正的 nativeId。
        if (!nativeId || nativeId === panelId) {
          try {
            var ans = await p.listWallpapers();
            var arr = (ans && ans.wallpapers) || [];
            for (var i = 0; i < arr.length; i += 1) {
              if (panelIdFor(arr[i].id) === panelId) { nativeId = arr[i].id; break; }
            }
          } catch (e) { /* 反查失败则继续走原逻辑 */ }
        }
        if (!nativeId || nativeId === panelId) return { ok: false, error: '壁纸不存在（映射丢失）' };
        // 同一张壁纸 2.5 秒内被连点：上一次原生 initScene 可能还在跑，
        // 再调 setAppBackground 会先 teardown 再重建，画面只会更晚出来。
        var startedAt = Date.now();
        if (nativeId === lastSceneStartId && startedAt - lastSceneStartAt < 2500) {
          return { ok: true, captureMode: 'dwm-thumbnail', sessionId: panelId };
        }
        lastSceneStartId = nativeId;
        lastSceneStartAt = startedAt;
        /*
         * ★ 切换壁纸前先停掉上一张的原生背景层，让页面回到原背景，再启动新的。
         *   否则旧 TextureView/EGL 还在，新场景 initScene 期间会叠着旧画面，
         *   用户看到的就是「点了没反应 / 背景还是上一张」。
         */
        try {
          await api.stopWallpaperEngineScene();
        } catch (stopErr) {
          console.warn('[MpkgBridge] 切换前停止旧背景失败:', stopErr && stopErr.message);
        }
        // setAppBackground 内部会启动原生的 GLSurfaceView 背景层
        //（程序内背景 = WebView 下的 GL 层，不是系统壁纸，也不是面板自己的 layer）
        await p.setAppBackground({ nativeId: nativeId });
        console.log('[MpkgBridge] 已应用壁纸（GL 背景层已启动）: native=', nativeId);
        // 面板的 startWallpaperEngineNativeBackground 要求：
        //   sessionId 必须是 24 位 hex，且 captureMode 决定后续分支。
        // 安卓没有 DWM 也没有 MediaStream —— 真正的画面由原生 GLSurfaceView 渲染，
        // 面板这一侧只需要"认为启动成功"。所以报 dwm-thumbnail（面板会走
        // reportWallpaperEngineCaptureResult 确认路径），并让那个确认返回
        // accepted/captureReady 为 true，面板就收下并显示成功。
        return { ok: true, captureMode: 'dwm-thumbnail', sessionId: panelId };
      } catch (error) {
        return { ok: false, error: error && error.message || String(error) };
      }
    };
    api.stopWallpaperEngineScene = async function () {
      // ★ 必须真的停掉原生背景层，否则点「恢复原背景」后
      //   GLSurfaceView / TextureView 还留在 WebView 之下，
      //   会和页面自身背景叠成两层（用户看到的"背景还有背景"）。
      try {
        await p.stopBackground({});
        console.log('[MpkgBridge] 原生背景层已停止');
      } catch (e) {
        console.warn('[MpkgBridge] 停止背景层失败:', e && e.message);
      }
      // 兼容旧的清理路径
      try {
        if (typeof api.stopWallpaperEngineNativeBackground === 'function') {
          await api.stopWallpaperEngineNativeBackground();
        }
      } catch (e2) { }
      return { ok: true };
    };
    api.getWallpaperEngineRuntimeStatus = async function () {
      return { available: true, reason: 'android_lwservice', engine: 'libscenejni' };
    };

    // ── 其余桌面专有方法：给成功空响应，让面板流程能走下去 ──
    var noopOk = async function () { return { ok: true }; };
    // 面板在 dwm 分支会检查 accepted / captureReady，缺一就判定失败
    api.reportWallpaperEngineCaptureResult = async function () {
      return { ok: true, accepted: true, captureReady: true };
    };
    api.prepareWallpaperEngineGlassCapture = noopOk;
    api.activateWallpaperEngineDwmSurface = noopOk;
    api.updateWallpaperEngineGlassSurface = function () {};
    api.reportWallpaperEnginePointerActivity = function () {};
    api.onWallpaperEngineHostBoundsChanged = function () {
      // 面板期待返回「取消订阅」函数
      return function () {};
    };
    api.updateWallpaperEngineVisualSettings = async function (payload) {
      // 视觉参数（亮度/速度等）由原生场景属性承担；这里先记住，接参数调节时用
      try { root.localStorage.setItem('mineradio_wp_visual', JSON.stringify(payload || {})); } catch (e) {}
      return { ok: true };
    };

    // ── 导入：面板没有这个入口（桌面是选目录），这里补一个面板可见的按钮行为 ──
    //     见 injectImportButton()。
    api.importMpkgWallpaper = async function (sourcePath, name, type) {
      var answer = await p.importFromFile({ sourcePath: sourcePath, name: name || '', type: type });
      return toProject(answer && answer.wallpaper);
    };
    api.applyMpkgWallpaper = async function (id) {
      await p.setCurrentWallpaper({ id: id });
      return { ok: true };
    };
    api.removeMpkgWallpaper = async function (id) {
      var panelId = String(id || '');
      var nativeId = nativeIdFor(panelId);
      /*
       * ★ 面板传进来的是 panelId（24 位 hex），原生库只认 nativeId（wp_…）。
       *   之前直接 { id: panelId } 传给原生，查不到 → 删除静默失败，
       *   用户看到「点了删除/隐藏还是原样」。
       *   映射丢失时用原生列表按 panelId 反查一次。
       */
      if (!nativeId || nativeId === panelId) {
        try {
          var ans = await p.listWallpapers();
          var arr = (ans && ans.wallpapers) || [];
          for (var i = 0; i < arr.length; i += 1) {
            if (panelIdFor(arr[i].id) === panelId) { nativeId = arr[i].id; break; }
          }
        } catch (e) { }
      }
      if (!nativeId || nativeId === panelId) return { ok: false, error: '壁纸不存在（映射丢失）' };
      await p.removeWallpaper({ id: nativeId });
      // 删除成功后同步内存和本地缓存，卡片立即消失、重启也不会再回来
      try {
        var left = (Array.isArray(root.wallpaperEngineProjects) ? root.wallpaperEngineProjects : [])
          .filter(function (pr) { return pr.id !== panelId; });
        root.wallpaperEngineProjects = left;
        if (typeof root.consumeWallpaperEngineSnapshot === 'function') {
          root.consumeWallpaperEngineSnapshot(snapshotFromProjects(left, ''));
        }
        persistProjects();
      } catch (e) { }
      console.log('[MpkgBridge] 已删除壁纸:', nativeId);
      return { ok: true };
    };

    // ── 本地项目缓存：面板打开先 restore（秒出卡片），扫描后 persist ──
    api.restoreWallpaperEngineProjects = function () { return restoreProjects(); };
    api.persistWallpaperEngineProjects = function () { persistProjects(); };

    installDesktopWindowApiPatch();
    console.log('[MpkgBridge] 壁纸桥已安装：list/import/apply/remove 可用');
    // 面板的加载入口在模块加载时会先跑一次 loadWallpaperEngineLibrary()，
    // 那一次拿不到 API（补丁还没生效）。这里补一次刷新，让列表和状态文案立刻正确。
    try {
      if (typeof root.refreshWallpaperEngineLibrary === 'function') {
        // 先用本地缓存把面板填上，再让 refresh 在后台校准原生列表
        restoreProjects();
        root.refreshWallpaperEngineLibrary();
      }
    } catch (e) { }
  }

  /**
   * 给 getDesktopWindowApi() 打补丁：
   *   - 真端游（Electron）下原样返回，什么都不改；
   *   - 安卓下返回壁纸 API，但**不**让其它模块看到 window.desktopWindow。
   *
   * 定义在 10-shell/04-desktop-overlay-fullscreen.js:89 的 getDesktopWindowApi() 是
   * 一份普通的全局函数声明（index-loader 用 <script> 传统方式加载，所以挂在 window 上），
   * 因此可以直接替换。
   */
  function installDesktopWindowApiPatch() {
    try {
      if (typeof root.getDesktopWindowApi !== 'function') {
        // index-loader 还没跑完 → 稍后重试
        if (patchAttempts < 40) {
          patchAttempts += 1;
          setTimeout(installDesktopWindowApiPatch, 100);
        }
        return false;
      }
      if (root.getDesktopWindowApi.__mpkgPatched) return true;
      var original = root.getDesktopWindowApi;
      var patched = function () {
        var real = original();
        if (real) return real;                      // 真端游：保持原行为
        return root.__mpkgWallpaperApi || null;     // 安卓：只把壁纸桥交出去
      };
      patched.__mpkgPatched = true;
      root.getDesktopWindowApi = patched;
      console.log('[MpkgBridge] getDesktopWindowApi 补丁已装（不影响端游判定）');
      return true;
    } catch (e) {
      return false;
    }
  }
  var patchAttempts = 0;

  /**
   * 在壁纸面板里补一个「导入 MPKG」按钮。
   * 桌面版面板顶部是「选择 Wallpaper Engine 目录」；安卓把它的点击行为改成文件选择。
   */
  function injectImportButton() {
    // 等面板 DOM 出现（面板是 index.html 里就有的，但保险起见等 body）
    var tryBind = function () {
      // 面板里原有的目录选择按钮（文案可能是「选择目录/添加目录」）
      var candidates = document.querySelectorAll('#wallpaper-engine-modal button');
      for (var i = 0; i < candidates.length; i += 1) {
        var btn = candidates[i];
        var text = (btn.textContent || '').trim();
        if (/选择.*目录|添加.*目录|识别.*目录/.test(text) && !btn.__mrImportBound) {
          btn.__mrImportBound = true;
          btn.addEventListener('click', function (event) {
            // 安卓上直接弹文件选择器
            if (!capacitor()) return; // 桌面保持原行为
            event.preventDefault();
            event.stopPropagation();
            openImportPicker();
          }, true);
        }
      }
    };
    var observer = new MutationObserver(function () { tryBind(); });
    var start = function () {
      if (document.body) { tryBind(); observer.observe(document.body, { childList: true, subtree: true }); }
      else setTimeout(start, 300);
    };
    start();
  }

  // 面板脚本（03-wallpaper-engine-library.js）是普通 script，先于本文件执行，
  // 所以这里挂上去的时机足够早。DOM 注入等 document 就绪后再做。
  //
  // ★★★ 重试安装：window.Capacitor.Plugins 由原生 Bridge 注入，
  //   个别机型/启动顺序下本脚本可能在插件注册完成之前执行。
  //   一次装不上就再也不装 → 面板永久「当前环境不支持」。所以这里必须重试。
  var installAttempts = 0;
  function installWithRetry() {
    installAttempts += 1;
    if (plugin(true)) { install(); return; }
    if (installAttempts >= 20) {
      console.warn('[MpkgBridge] 重试 20 次仍拿不到 MpkgWallpaper 插件（原生未注册或非 Capacitor 环境），'
        + '壁纸面板将保持桌面提示');
      return;
    }
    setTimeout(installWithRetry, 250);
  }
  installWithRetry();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { injectImportButton(); });
  } else {
    injectImportButton();
  }
})(window);
