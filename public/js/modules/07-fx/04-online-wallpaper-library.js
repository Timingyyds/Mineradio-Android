/* ═══════════════════════════════════════════
 * 在线壁纸库 / 壁纸库 / 预览面板 — 前端逻辑
 * 桥接 window.OnlineWpNative（Capacitor MpkgWallpaper 插件，Promise 版）
 * 移植自 Mineradio-Android-main 的同名模块，把同步 window.KeepApp.* 改为 await。
 * ═══════════════════════════════════════════ */
'use strict';

// ===== 在线壁纸：状态 =====
var ONLINE_WP_SOURCE = 'moewalls';
var onlineWpState = {
  page: 1,
  keyword: '',
  list: [],
  hasMore: false,
  totalPages: 0,
  total: 0,
  loading: false,
  downloading: {},   // detailUrl -> {name, progress, status, startTime}
  scrollTimer: 0,
};

// ===== 壁纸库：状态 =====
var wpLibraryState = {
  category: 'all',
  list: [],
  loading: false,
  appBgId: '',   // 【修复】原生登记的「应用内背景」壁纸 id（用于显示「使用中」）
};

// 已下载壁纸名称集合（在线列表显示「已下载」）
var wpDownloadedNames = null;

// ===== 预览：状态 =====
var wpPreviewState = {
  currentId: '',
  currentType: 0,
  currentName: '',
  currentMpkg: null,
};

// ===== 桥接辅助 =====
function owp() {
  return (typeof window !== 'undefined' && window.OnlineWpNative) ? window.OnlineWpNative : null;
}

function owpEscapeHtml(s) {
  if (s == null) return '';
  return String(s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

function owpSleep(ms) {
  return new Promise(function (resolve) { setTimeout(resolve, ms); });
}

function owpWithTimeout(promise, ms, message) {
  return new Promise(function (resolve, reject) {
    var done = false;
    var timer = setTimeout(function () {
      if (done) return;
      done = true;
      reject(new Error(message || '操作超时'));
    }, ms);
    Promise.resolve(promise).then(function (v) {
      if (done) return;
      done = true;
      clearTimeout(timer);
      resolve(v);
    }, function (e) {
      if (done) return;
      done = true;
      clearTimeout(timer);
      reject(e);
    });
  });
}

/** 绝对路径 → Capacitor 本地服务器可读的 URL（https://localhost/_capacitor_file_/...） */
function owpCapacitorFileUrl(path) {
  if (!path) return '';
  if (path.indexOf('http') === 0 || path.indexOf('content://') === 0 || path.indexOf('data:') === 0) return path;
  try {
    return 'https://localhost/_capacitor_file_' + encodeURI(path);
  } catch (e) {
    return 'file://' + path;
  }
}

// ===== 在线壁纸：打开 / 关闭 =====
function wallpaperOpenOnline() {
  var modal = document.getElementById('online-wallpaper-modal');
  if (modal) modal.classList.add('show');
  if (onlineWpState.list.length === 0 && !onlineWpState.loading) {
    onlineWpLoadPage(1);
  }
}

function closeOnlineWallpaper() {
  var modal = document.getElementById('online-wallpaper-modal');
  if (modal) modal.classList.remove('show');
  clearTimeout(onlineWpState.scrollTimer);
  onlineWpState.scrollTimer = 0;
}

// 源切换（固定 moewalls，保留函数兼容 HTML）
function onlineWpSelectSource(source) {
  if (!source) return;
  ONLINE_WP_SOURCE = source;
}

// ===== 搜索 =====
function onlineWpSearch() {
  var input = document.getElementById('online-wp-keyword');
  onlineWpState.keyword = input ? input.value.trim() : '';
  onlineWpState.page = 1;
  onlineWpState.list = [];
  onlineWpRenderList();
  onlineWpLoadPage(1);
}

function onlineWpClearSearch() {
  var input = document.getElementById('online-wp-keyword');
  if (input) input.value = '';
  onlineWpState.keyword = '';
  onlineWpState.page = 1;
  onlineWpState.list = [];
  onlineWpRenderList();
  onlineWpLoadPage(1);
}

// ===== 加载一页 =====
async function onlineWpLoadPage(page) {
  var api = owp();
  if (!api) {
    onlineWpShowStatus('错误：当前环境不支持在线壁纸', 'error');
    return;
  }
  if (onlineWpState.loading) return;
  onlineWpState.loading = true;
  onlineWpState.page = page;
  onlineWpShowLoading(true);
  onlineWpShowStatus('正在加载第 ' + page + ' 页...', '');

  try {
    var data = await owpWithTimeout(
      api.getList({
        page: page,
        keyword: onlineWpState.keyword,
        source: ONLINE_WP_SOURCE,
        category: '',
        resolution: '',
      }),
      45000,
      '加载超时：请检查网络连接后重试'
    );
    if (!data || !Array.isArray(data.list)) data = { list: [], hasMore: false, total: 0 };
    if (data.error) {
      onlineWpShowStatus('加载失败：' + data.error + '（请检查网络连接）', 'error');
    }
    if (page === 1) onlineWpState.list = data.list;
    else onlineWpState.list = onlineWpState.list.concat(data.list);
    onlineWpState.hasMore = !!data.hasMore;
    onlineWpState.totalPages = data.totalPages || 0;
    onlineWpState.total = data.total || onlineWpState.list.length;

    await wpLibraryEnsureDownloadedNames().catch(function () {});
    onlineWpRenderList();
    onlineWpRenderPagination();
    if (!data.error && onlineWpState.list.length === 0) {
      onlineWpShowStatus(onlineWpState.keyword ? '未找到匹配的壁纸' : '此源暂无壁纸', '');
    } else if (!data.error) {
      onlineWpShowStatus('共 ' + onlineWpState.total + ' 张壁纸 · 第 ' + page + ' 页', 'success');
    }
  } catch (e) {
    onlineWpShowStatus('加载失败：' + (e && e.message ? e.message : e), 'error');
  } finally {
    onlineWpState.loading = false;
    onlineWpShowLoading(false);
  }
}

function onlineWpShowLoading(show) {
  var el = document.getElementById('online-wp-loading');
  if (el) el.style.display = show ? 'flex' : 'none';
}

function onlineWpShowStatus(msg, type) {
  var el = document.getElementById('online-wp-status');
  if (!el) return;
  el.textContent = msg || '';
  el.className = 'online-wp-status' + (type ? ' ' + type : '');
}

// ===== 已下载名称缓存 =====
async function wpLibraryEnsureDownloadedNames() {
  if (wpDownloadedNames) return wpDownloadedNames;
  var set = new Set();
  var api = owp();
  if (api) {
    try {
      var data = await api.libraryList('all');
      if (data && Array.isArray(data.list)) {
        data.list.forEach(function (w) {
          var n = (w.name || '').replace(/\.[^.]+$/, '').trim().toLowerCase();
          if (n) set.add(n);
        });
      }
    } catch (e) { /* 列表失败时保留空集合 */ }
  }
  wpDownloadedNames = set;
  return set;
}

function wpLibraryInvalidateDownloadedCache() {
  wpDownloadedNames = null;
}

// ===== 渲染在线列表 =====
function onlineWpRenderList() {
  var grid = document.getElementById('online-wp-grid');
  if (!grid) return;
  var list = onlineWpState.list;
  if (!list || list.length === 0) {
    grid.innerHTML = '<div class="online-wp-empty">点击"重置"或输入关键词开始浏览壁纸</div>';
    grid.onscroll = null;
    return;
  }
  var downloadedNames = wpDownloadedNames || new Set();
  var html = '';
  list.forEach(function (item, idx) {
    var title = item.title || item.name || '未命名';
    var thumb = item.thumbnail || item.preview || '';
    var detailUrl = item.detailUrl || item.id || '';
    var source = item.source || ONLINE_WP_SOURCE;
    var type = item.type || 'image';
    var isVideo = type === 'video';
    var typeBadge = isVideo
      ? '<span class="online-wp-card-badge video">视频</span>'
      : '<span class="online-wp-card-badge">图片</span>';
    var resolution = item.resolution || '';
    var dlState = onlineWpState.downloading[detailUrl];
    var isDownloading = !!dlState;
    var progress = dlState ? dlState.progress : 0;
    var progressBar = isDownloading
      ? '<div class="online-wp-card-progress" style="width:' + progress + '%"></div>'
      : '';
    var normalizedTitle = title.replace(/\.[^.]+$/, '').trim().toLowerCase();
    var isDownloaded = !isDownloading && downloadedNames.has(normalizedTitle);
    var clickArg = '\'' + owpEscapeHtml(detailUrl).replace(/'/g, '\\\'') + '\',\'' +
      owpEscapeHtml(title).replace(/'/g, '\\\'') + '\',' + (isVideo ? 1 : 3) + ',\'' +
      owpEscapeHtml(source).replace(/'/g, '\\\'') + '\',\'' +
      owpEscapeHtml(item.fileId || '').replace(/'/g, '\\\'') + '\'';
    var downloadBtn = isDownloading
      ? ''
      : (isDownloaded
        ? '<span class="online-wp-card-downloaded">已下载</span>'
        : '<button class="online-wp-card-download" type="button" onclick="event.stopPropagation();onlineWpDownloadOne(' + clickArg + ')" title="下载到壁纸库">下载</button>');
    var media = '';
    if (thumb) {
      if (isVideo && item.previewVideo) {
        media = '<video src="' + onlineWpEscapeHtml(item.previewVideo) + '" poster="' + owpEscapeHtml(thumb) +
          '" muted loop playsinline preload="metadata" onmouseover="try{this.play().catch(function(){})}catch(e){}"' +
          ' onmouseout="try{this.pause()}catch(e){}"></video>';
      } else {
        media = '<img src="' + owpEscapeHtml(thumb) + '" loading="lazy" onerror="this.style.opacity=0.3" alt="">';
      }
    } else {
      media = '<div class="online-wp-card-placeholder">无预览</div>';
    }
    var cardClick = (isDownloading || isDownloaded) ? '' : 'onclick="onlineWpDownloadOne(' + clickArg + ')"';
    html += '<div class="online-wp-card" ' + cardClick + '>' +
      media + typeBadge + downloadBtn +
      '<div class="online-wp-card-title">' + owpEscapeHtml(title) +
      (resolution ? ' · ' + owpEscapeHtml(resolution) : '') + '</div>' +
      progressBar +
      '</div>';
  });
  grid.innerHTML = html;

  clearTimeout(onlineWpState.scrollTimer);
  onlineWpState.scrollTimer = 0;
  grid.onscroll = function () {
    if (!onlineWpState.hasMore || onlineWpState.loading) return;
    if (grid.scrollTop + grid.clientHeight >= grid.scrollHeight - 80) {
      if (!onlineWpState.scrollTimer) {
        onlineWpState.scrollTimer = setTimeout(function () {
          onlineWpState.scrollTimer = 0;
          onlineWpLoadPage(onlineWpState.page + 1);
        }, 200);
      }
    }
  };
}

function onlineWpRenderPagination() {
  var pag = document.getElementById('online-wp-pagination');
  if (!pag) return;
  if (onlineWpState.totalPages > 0) {
    pag.style.display = 'flex';
    pag.innerHTML = '<button type="button" onclick="onlineWpLoadPage(' + Math.max(1, onlineWpState.page - 1) + ')" ' +
      (onlineWpState.page <= 1 ? 'disabled' : '') + '>上一页</button>' +
      '<span>第 ' + onlineWpState.page + ' / ' + onlineWpState.totalPages + ' 页 · 共 ' + onlineWpState.total + ' 张</span>' +
      '<button type="button" onclick="onlineWpLoadPage(' + (onlineWpState.page + 1) + ')" ' +
      (!onlineWpState.hasMore ? 'disabled' : '') + '>下一页</button>';
  } else if (onlineWpState.hasMore) {
    pag.style.display = 'flex';
    pag.innerHTML = '<button type="button" onclick="onlineWpLoadPage(' + (onlineWpState.page + 1) + ')">加载更多</button>';
  } else {
    pag.style.display = 'none';
  }
}

// ===== 下载壁纸 =====
async function onlineWpDownloadOne(detailUrl, name, type, source, fileId) {
  var api = owp();
  if (!api) {
    onlineWpShowStatus('错误：当前环境不支持下载', 'error');
    return;
  }
  if (!detailUrl) return;
  if (onlineWpState.downloading[detailUrl]) {
    onlineWpShowStatus('此壁纸正在下载中，请稍候...', '');
    return;
  }
  onlineWpState.downloading[detailUrl] = {
    name: name,
    progress: 0,
    status: '准备中',
    startTime: Date.now(),
  };
  onlineWpRenderList();
  onlineWpShowStatus('开始下载 "' + name + '"...', '');

  try {
    await api.download({
      detailUrl: detailUrl,
      name: name,
      type: type || 3,
      source: source || ONLINE_WP_SOURCE,
      fileId: fileId || '',
    });
  } catch (e) {
    delete onlineWpState.downloading[detailUrl];
    onlineWpShowStatus('下载启动失败：' + (e && e.message ? e.message : e), 'error');
    onlineWpRenderList();
    return;
  }

  onlineWpPollProgress(detailUrl, name);
}

async function onlineWpPollProgress(detailUrl, name) {
  var api = owp();
  if (!api) return;
  var startedAt = Date.now();
  while (onlineWpState.downloading[detailUrl]) {
    await owpSleep(1200);
    var state = onlineWpState.downloading[detailUrl];
    if (!state) return;
    var data = { progress: 0, status: '' };
    try {
      data = (await api.getProgress()) || data;
    } catch (e) { /* 读取失败按 0 处理 */ }
    var progress = Math.max(0, Math.min(100, parseInt(data.progress || 0, 10) || 0));
    var status = String(data.status || '');
    state.progress = progress;
    state.status = status;
    onlineWpRenderList();

    if (/完成/.test(status) && progress >= 100) {
      delete onlineWpState.downloading[detailUrl];
      wpLibraryInvalidateDownloadedCache();
      onlineWpRenderList();
      onlineWpShowStatus('"' + name + '" 下载完成，已添加到壁纸库', 'success');
      var libModal = document.getElementById('wallpaper-library-modal');
      if (libModal && libModal.classList.contains('show')) wpLibraryLoad();
      return;
    }
    if (/失败|错误/.test(status)) {
      delete onlineWpState.downloading[detailUrl];
      onlineWpShowStatus('下载失败：' + status, 'error');
      onlineWpRenderList();
      return;
    }
    if (Date.now() - startedAt > 300000) {
      delete onlineWpState.downloading[detailUrl];
      onlineWpShowStatus('"' + name + '" 下载超时，请检查网络后重试', 'error');
      onlineWpRenderList();
      return;
    }
    onlineWpShowStatus('正在下载 "' + name + '"... ' + progress + '% · ' + status, '');
  }
}

// ═══════════════════════════════════════════
//  壁纸库（本地）
// ═══════════════════════════════════════════
function wallpaperOpenLibrary() {
  var modal = document.getElementById('wallpaper-library-modal');
  if (modal) modal.classList.add('show');
  // 【修复】打开面板即同步原生登记（「使用中」徽标 + 清除可用性），
  // 重启后壁纸画面在但面板读不到的问题在此解决
  wpLibrarySyncAppBg().then(function () {
    wpLibraryLoad();
    wpLibraryRender();
  }).catch(function () { wpLibraryLoad(); });
}

function closeWallpaperLibrary() {
  var modal = document.getElementById('wallpaper-library-modal');
  if (modal) modal.classList.remove('show');
}

function wpLibrarySelectTab(cat) {
  if (wpLibraryState.category === cat) return;
  wpLibraryState.category = cat;
  var tabs = document.querySelectorAll('#wp-library-tabs .online-wp-tab');
  for (var i = 0; i < tabs.length; i += 1) {
    tabs[i].classList.toggle('active', tabs[i].dataset.cat === cat);
  }
  wpLibraryLoad();
}

async function wpLibraryLoad() {
  var api = owp();
  if (!api) {
    wpLibraryShowStatus('错误：当前环境不支持壁纸库', 'error');
    return;
  }
  wpLibraryState.loading = true;
  wpLibraryShowStatus('正在加载壁纸库...', '');

  var baseList = [];
  var hasError = false;
  try {
    var data = await api.libraryList(wpLibraryState.category);
    if (data && Array.isArray(data.list)) baseList = data.list;
    if (data && data.error) {
      hasError = true;
      wpLibraryShowStatus('加载失败：' + data.error, 'error');
    }
  } catch (e) {
    hasError = true;
    wpLibraryShowStatus('加载失败：' + (e && e.message ? e.message : e), 'error');
  }

  // ★ 壁纸库只显示「在线壁纸下载的壁纸」。
  //   MPKG 场景包只在 Wallpaper Engine 面板里显示，两个面板互不混合。
  wpLibraryState.list = baseList.filter(function (w) {
    return !!(w && w.isOnline);
  });
  wpLibraryState.loading = false;
  // 【修复】读取原生登记的「应用内背景」,给使用中的卡片打「使用中」徽标。
  //  之前设置成功后没有任何状态回读,管理界面永远看不出哪张在用。
  await wpLibrarySyncAppBg();
  wpLibraryRender();
  if (!hasError) {
    var total = wpLibraryState.list.length;
    wpLibraryShowStatus('共 ' + total + ' 张壁纸', total > 0 ? 'success' : '');
  }
}

/* 【修复】读取原生登记的应用内背景 id,存进 wpLibraryState.appBgId。 */
async function wpLibrarySyncAppBg() {
  var api = owp();
  if (!api || typeof api.libraryGetAppBg !== 'function') { wpLibraryState.appBgId = ''; return; }
  try {
    var bg = await api.libraryGetAppBg();
    wpLibraryState.appBgId = (bg && bg.wallpaper && bg.wallpaper.id) ? String(bg.wallpaper.id) : '';
  } catch (e) {
    wpLibraryState.appBgId = '';
  }
}

function wpLibraryShowStatus(msg, type) {
  var el = document.getElementById('wp-library-status');
  if (!el) return;
  el.textContent = msg || '';
  el.className = 'online-wp-status' + (type ? ' ' + type : '');
}

function wpLibraryRender() {
  var grid = document.getElementById('wp-library-grid');
  if (!grid) return;
  var list = wpLibraryState.list || [];
  if (list.length === 0) {
    grid.innerHTML = '<div class="online-wp-empty">壁纸库为空，请先在线下载或导入壁纸</div>';
    return;
  }
  var html = '';
  list.forEach(function (item) {
    var id = item.id || '';
    var name = item.name || '未命名';
    var type = item.type || 0;
    var path = item.path || '';
    var badge = '';
    var media = '';
    var hasCover = false;

    if (item._mpkg) {
      var proj = item._mpkgProject || {};
      var preview = proj.previewData || '';
      if (preview) {
        media = '<img src="' + owpEscapeHtml(preview) + '" loading="lazy" onerror="this.style.opacity=0.3" alt="">';
        hasCover = true;
      }
      badge = '<span class="online-wp-card-badge">MPKG</span>';
    } else {
      if (type === 4) badge = '<span class="online-wp-card-badge">MPKG</span>';
      else if (item.isOnline) badge = '<span class="online-wp-card-badge">在线</span>';
      else if (item.isPhone) badge = '<span class="online-wp-card-badge">手机</span>';
      var thumb = item.thumbnail || '';
      if (thumb) {
        media = '<img src="' + owpEscapeHtml(thumb) + '" loading="lazy" onerror="this.style.opacity=0.3" alt="">';
        hasCover = true;
      } else if (path && type === 1) {
        media = '<video src="' + owpEscapeHtml(owpCapacitorFileUrl(path)) +
          '" muted preload="metadata" onmouseover="try{this.play().catch(function(){})}catch(e){}"' +
          ' onmouseout="try{this.pause()}catch(e){}"></video>';
        hasCover = true;
      }
    }
    // 没有封面也显示（占位卡），特别是 Wallpaper Engine 项目
    if (!hasCover) {
      media = '<div class="online-wp-card-placeholder">无预览</div>';
    }
    /* 【修复】原生登记为「应用内背景」的壁纸,卡片标「使用中」。
       与 MPKG/在线等业务徽标叠加显示,不改原有徽标逻辑。
       【双格式匹配】原生登记 MPKG 的 id 是 wp_usermpkg_<id>，而库列表卡片
       是 mpkg_<id>——剥掉各自前缀比对裸 id，两种命名都能命中。 */
    var appBgNorm = String(wpLibraryState.appBgId || '').replace(/^(wp_usermpkg_|mpkg_)/, '');
    var idNorm = String(id || '').replace(/^(wp_usermpkg_|mpkg_)/, '');
    var usingBadge = (appBgNorm && idNorm && appBgNorm === idNorm)
      ? '<span class="online-wp-card-badge online-wp-card-badge-using">使用中</span>'
      : '';
    html += '<div class="online-wp-card" onclick="wpLibraryPreview(\'' +
      owpEscapeHtml(id).replace(/'/g, '\\\'') + '\')">' +
      media + badge + usingBadge +
      '<div class="online-wp-card-title">' + owpEscapeHtml(name) + '</div>' +
      '</div>';
  });
  grid.innerHTML = html;
}

async function wpLibraryPreview(id) {
  // MPKG 项目：直接用 Wallpaper Engine 数据，不走原生桥
  if (typeof id === 'string' && id.indexOf('mpkg_') === 0) {
    var mpkgId = id.substring(5);
    var proj = (typeof wallpaperEngineProjects !== 'undefined' && Array.isArray(wallpaperEngineProjects))
      ? wallpaperEngineProjects.find(function (p) { return p.id === mpkgId; })
      : null;
    if (proj) {
      var dup = wpLibraryState.list.find(function (w) { return w.id === id; });
      var titleEl0 = document.getElementById('wp-preview-title');
      var mediaEl0 = document.getElementById('wp-preview-media');
      wpPreviewState.currentId = id;
      wpPreviewState.currentType = 2;
      wpPreviewState.currentName = proj.title || 'Wallpaper Engine';
      wpPreviewState.currentMpkg = (dup && dup._mpkgProject) || proj;
      if (titleEl0) titleEl0.textContent = proj.title || '壁纸预览';
      if (mediaEl0) {
        var preview0 = proj.previewData || '';
        mediaEl0.innerHTML = preview0
          ? '<img src="' + owpEscapeHtml(preview0) + '" alt="">'
          : '<div class="online-wp-empty">无预览内容</div>';
      }
      var statusEl0 = document.getElementById('wp-preview-status');
      if (statusEl0) statusEl0.textContent = '';
      var modal0 = document.getElementById('wallpaper-preview-modal');
      if (modal0) modal0.classList.add('show');
      return;
    }
  }

  var api = owp();
  if (!api) return;
  wpPreviewShowStatus('正在加载预览...', '');
  try {
    var data = await api.libraryPreview(id);
    if (!data || !data.ok) {
      wpLibraryShowStatus('预览失败：' + ((data && data.error) || '未知错误'), 'error');
      return;
    }
    wpPreviewState.currentId = data.id || id;
    wpPreviewState.currentType = data.type || 0;
    wpPreviewState.currentName = data.name || '';
    wpPreviewState.currentMpkg = null;

    var titleEl = document.getElementById('wp-preview-title');
    if (titleEl) titleEl.textContent = data.name || '壁纸预览';
    var mediaEl = document.getElementById('wp-preview-media');
    if (mediaEl) {
      var thumb = data.thumbnail || '';
      if (data.type === 1) {
        mediaEl.innerHTML = '<video src="' + owpEscapeHtml(owpCapacitorFileUrl(data.path || '')) + '"' +
          (thumb ? ' poster="' + owpEscapeHtml(thumb) + '"' : '') +
          ' controls autoplay loop muted playsinline></video>';
      } else if (data.type === 3) {
        mediaEl.innerHTML = '<img src="' + owpEscapeHtml(thumb || owpCapacitorFileUrl(data.path || '')) + '" alt="">';
      } else {
        // MPKG 场景包：原生已生成封面图，直接展示
        mediaEl.innerHTML = thumb
          ? '<img src="' + owpEscapeHtml(thumb) + '" alt="">'
          : '<div class="online-wp-empty">此类壁纸请在壁纸面板中预览</div>';
      }
    }
    wpPreviewShowStatus('', '');
    var modal = document.getElementById('wallpaper-preview-modal');
    if (modal) modal.classList.add('show');
  } catch (e) {
    wpPreviewShowStatus('预览失败：' + (e && e.message ? e.message : e), 'error');
  }
}

function closeWallpaperPreview() {
  wallpaperPreviewResetDeleteArm();
  var modal = document.getElementById('wallpaper-preview-modal');
  if (modal) modal.classList.remove('show');
  var mediaEl = document.getElementById('wp-preview-media');
  if (mediaEl) {
    var v = mediaEl.querySelector('video');
    if (v) { try { v.pause(); } catch (e) {} }
    mediaEl.innerHTML = '';
  }
  wpPreviewState.currentId = '';
  wpPreviewState.currentType = 0;
  wpPreviewState.currentMpkg = null;
}

// ===== 预览操作：设为应用背景 =====
async function wallpaperPreviewSetAppBg() {
  // MPKG 项目：走 Wallpaper Engine 面板的应用逻辑
  if (wpPreviewState.currentType === 2 && wpPreviewState.currentMpkg) {
    var proj = wpPreviewState.currentMpkg;
    if (typeof setCustomBackgroundMedia === 'function') setCustomBackgroundMedia(null, true);
    if (typeof activateWallpaperEngineItem === 'function') {
      activateWallpaperEngineItem(proj.id);
    } else if (typeof applyWallpaperEngineBackground === 'function') {
      applyWallpaperEngineBackground(proj, false);
    }
    if (typeof renderWallpaperEngineLibrary === 'function') renderWallpaperEngineLibrary(true);
    /*
     * 【修复】MPKG 导入双写：activateWallpaperEngineItem 只更新 JS 视觉层，
     * 原生 WallpaperManager 没有登记 → 重启后壁纸画面虽然在（原生恢复了），
     * 但壁纸管理面板读不到「使用中」，清除也清不掉。
     * 这里补一次原生登记（id 用原生命名 wp_usermpkg_<mpkgId>），再同步徽标。
     */
    try {
      var mpkgApi = owp();
      var nativeMpkgId = 'wp_usermpkg_' + String(proj.id || '');
      if (mpkgApi && typeof mpkgApi.librarySetAppBg === 'function') {
        var mpkgResult = await mpkgApi.librarySetAppBg(nativeMpkgId);
        if (mpkgResult && mpkgResult.ok) {
          await wpLibrarySyncAppBg();
          if (typeof wpLibraryRender === 'function' && document.getElementById('wallpaper-library-modal')) wpLibraryRender();
        }
      }
    } catch (e) { /* 原生登记失败不阻断 JS 视觉激活 */ }
    wpPreviewShowStatus('已设为应用背景：' + (proj.title || wpPreviewState.currentName), 'success');
    if (typeof refreshWallpaperInfo === 'function') refreshWallpaperInfo();
    return;
  }

  var api = owp();
  if (!api || !wpPreviewState.currentId) return;
  wpPreviewShowStatus('正在应用...', '');
  try {
    var data = await api.librarySetAppBg(wpPreviewState.currentId);
    if (!data || !data.ok) {
      wpPreviewShowStatus('设置失败：' + ((data && data.error) || '未知错误'), 'error');
      return;
    }
    // 图片/视频由 WebView 自己播放（原生只负责记录应用背景）
    if ((data.type === 1 || data.type === 3) && typeof fx !== 'undefined' && fx) {
      fx.backgroundMedia = {
        type: data.type === 1 ? 'video' : 'image',
        src: owpCapacitorFileUrl(data.path || ''),
        name: data.name || '',
      };
      fx.backgroundAlbumCover = false;
      if (typeof applyCustomBackground === 'function') applyCustomBackground();
      if (typeof saveLyricLayout === 'function') saveLyricLayout({ user: true, reason: 'backgroundMedia' });
    }
    wpPreviewShowStatus('已设为应用背景：' + (data.name || wpPreviewState.currentName), 'success');
    if (typeof refreshWallpaperInfo === 'function') refreshWallpaperInfo();
    if (typeof deactivateWallpaperEngineBackground === 'function') deactivateWallpaperEngineBackground(true);
    /* 【修复】登记已变,同步「使用中」徽标并刷新壁纸库(若开着)。 */
    await wpLibrarySyncAppBg();
    if (typeof wpLibraryRender === 'function' && document.getElementById('wallpaper-library-modal')) wpLibraryRender();
  } catch (e) {
    wpPreviewShowStatus('设置失败：' + (e && e.message ? e.message : e), 'error');
  }
}

// ===== 预览操作：设为系统桌面 =====
async function wallpaperPreviewSetSystem() {
  var api = owp();
  if (!api || !wpPreviewState.currentId) return;

  var currentId = wpPreviewState.currentId;
  if (currentId.indexOf('mpkg_') === 0) {
    await wpPreviewSetSystemMpkg(currentId.substring(5));
    return;
  }

  wpPreviewShowStatus('正在设置系统壁纸...', '');
  try {
    var data = await api.librarySetSystem(currentId);
    if (!data || !data.ok) {
      wpPreviewShowStatus('设置失败：' + ((data && data.error) || '未知错误'), 'error');
      return;
    }
    if (data.result === 'NEED_ACTIVATE') {
      wpPreviewShowStatus('已选定为系统壁纸，请在系统设置中激活动态壁纸', 'success');
      setTimeout(function () {
        try { api.gotoSystemWallpaperSettings(); } catch (e) {}
      }, 1200);
    } else {
      wpPreviewShowStatus('已设为系统壁纸：' + wpPreviewState.currentName, 'success');
    }
  } catch (e) {
    wpPreviewShowStatus('设置失败：' + (e && e.message ? e.message : e), 'error');
  }
}

async function wpPreviewSetSystemMpkg(mpkgId) {
  var api = owp();
  var proj = wpPreviewState.currentMpkg;
  if (!proj || !api) {
    wpPreviewShowStatus('设置失败：MPKG 项目数据缺失', 'error');
    return;
  }
  if (typeof readWallpaperEngineLocalProjects !== 'function') {
    wpPreviewShowStatus('设置失败：无法访问 Wallpaper Engine 存储', 'error');
    return;
  }
  var name = proj.title || 'Wallpaper Engine';
  wpPreviewShowStatus('正在导出 MPKG 文件...', '');
  try {
    var records = await readWallpaperEngineLocalProjects();
    var record = (records || []).find(function (r) { return r.id === mpkgId; });
    if (!record || !record.blob) {
      wpPreviewShowStatus('设置失败：找不到 MPKG 文件数据', 'error');
      return;
    }
    var fileName = owpSanitizeMpkgName(record.fileName || record.name || mpkgId);

    var blobUrl = URL.createObjectURL(record.blob);
    var arrayBuffer;
    try {
      arrayBuffer = await (await fetch(blobUrl)).arrayBuffer();
    } finally {
      URL.revokeObjectURL(blobUrl);
    }
    var bytes = new Uint8Array(arrayBuffer);

    var start = await api.writeTempStart(fileName);
    if (!start || !start.ok || !start.path) {
      wpPreviewShowStatus('设置失败：无法创建临时文件', 'error');
      return;
    }
    var tempPath = start.path;
    var CHUNK = 2 * 1024 * 1024;
    for (var offset = 0; offset < bytes.length; offset += CHUNK) {
      var end = Math.min(offset + CHUNK, bytes.length);
      var binary = '';
      for (var i = offset; i < end; i += 1) binary += String.fromCharCode(bytes[i]);
      var chunkRes = await api.writeTempChunk(tempPath, btoa(binary));
      if (!chunkRes || !chunkRes.ok) {
        wpPreviewShowStatus('设置失败：分块写入失败 ' + ((chunkRes && chunkRes.error) || ''), 'error');
        return;
      }
      await owpSleep(0);
    }
    var finish = await api.writeTempFinish(tempPath);
    if (!finish || !finish.ok) {
      wpPreviewShowStatus('设置失败：临时文件校验失败', 'error');
      return;
    }
    var result = await api.setSystemMpkgFromFile({
      mpkgId: mpkgId,
      name: name,
      fileName: fileName,
      tempPath: tempPath,
    });
    if (!result || !result.ok) {
      wpPreviewShowStatus('设置失败：' + ((result && result.error) || '未知错误'), 'error');
      return;
    }
    if (result.result === 'NEED_ACTIVATE') {
      wpPreviewShowStatus('已选定为系统壁纸，请在系统设置中激活动态壁纸', 'success');
      setTimeout(function () {
        try { api.gotoSystemWallpaperSettings(); } catch (e) {}
      }, 1200);
    } else {
      wpPreviewShowStatus('已设为系统壁纸：' + name, 'success');
    }
  } catch (e) {
    wpPreviewShowStatus('设置失败：' + (e && e.message ? e.message : e), 'error');
  }
}

function owpSanitizeMpkgName(raw) {
  var n = String(raw || '').split('?')[0].split('#')[0].replace(/[/\\]/g, '');
  n = n.replace(/[^a-zA-Z0-9._-]/g, '');
  if (!n) n = 'wallpaper';
  return n.toLowerCase().endsWith('.mpkg') ? n : (n + '.mpkg');
}

// ===== 预览操作：清除背景 =====
function wallpaperPreviewClearBg() {
  if (typeof setCustomBackgroundMedia === 'function') {
    setCustomBackgroundMedia(null);
    wpPreviewShowStatus('已清除背景媒体', 'success');
  }
  try {
    if (typeof wallpaperEngineSelection !== 'undefined' && wallpaperEngineSelection && wallpaperEngineSelection.active) {
      wallpaperEngineSelection.active = false;
      if (typeof saveWallpaperEngineSelection === 'function') saveWallpaperEngineSelection();
      if (typeof updateWallpaperEngineEntryUi === 'function') updateWallpaperEngineEntryUi();
    }
  } catch (e) { /* 忽略 WE 清理失败 */ }

  var api = owp();
  if (api) {
    api.clearSystem().catch(function () {});
    api.setForceSystem('0').catch(function () {});
    /* 【修复】必须清掉原生登记的应用内背景并停 GL 壁纸层,否则背景永远清不掉。
       clearAppBackground = MpkgBackgroundManager.stop() + 登记置 null,一体完成。 */
    if (typeof api.libraryClearAppBg === 'function') {
      api.libraryClearAppBg().then(function () {
        wpLibraryState.appBgId = '';
        wpLibraryRender();
      }).catch(function () { /* 旧版原生没有此方法时静默 */ });
    }
  }
}

// ===== 预览操作：删除（二次确认） =====
var wpPreviewDeleteArmed = false;
var wpPreviewDeleteArmTimer = 0;

function wallpaperPreviewResetDeleteArm() {
  wpPreviewDeleteArmed = false;
  var btn = document.getElementById('wp-preview-delete-btn');
  if (btn) {
    btn.textContent = '删除';
    btn.classList.remove('armed');
  }
  if (wpPreviewDeleteArmTimer) {
    clearTimeout(wpPreviewDeleteArmTimer);
    wpPreviewDeleteArmTimer = 0;
  }
}

async function wallpaperPreviewDelete() {
  var currentId = wpPreviewState.currentId;
  var currentName = wpPreviewState.currentName;
  if (!currentId) {
    wpPreviewShowStatus('删除失败：没有正在预览的壁纸', 'error');
    return;
  }
  var btn = document.getElementById('wp-preview-delete-btn');
  if (btn) { btn.textContent = '删除中…'; btn.disabled = true; }
  try {
    if (currentId.indexOf('mpkg_') === 0) {
      // 本地 MPKG：交给 03 模块，经桥接把 panelId 换成 nativeId 后真删文件
      var mpkgId = currentId.substring(5);
      if (typeof removeWallpaperEngineLocalProject !== 'function') {
        wpPreviewShowStatus('删除失败：Wallpaper Engine 模块未加载', 'error');
        return;
      }
      await removeWallpaperEngineLocalProject(mpkgId);
    } else {
      var api = owp();
      if (!api) {
        wpPreviewShowStatus('删除失败：当前环境不支持壁纸库', 'error');
        return;
      }
      await api.libraryDelete(currentId);
    }
    // 立即从当前列表移除：只重新拉一遍库，已删条目可能又从缓存/网络回来
    wpLibraryState.list = (wpLibraryState.list || []).filter(function (w) { return String(w.id) !== currentId; });
    wpLibraryRender();
    wpPreviewShowStatus('已删除：' + currentName, 'success');
    closeWallpaperPreview();
  } catch (e) {
    wpPreviewShowStatus('删除失败：' + (e && e.message ? e.message : e), 'error');
  } finally {
    if (btn) { btn.textContent = '删除'; btn.disabled = false; }
  }
}

function wpPreviewShowStatus(msg, type) {
  var el = document.getElementById('wp-preview-status');
  if (!el) return;
  el.textContent = msg || '';
  el.className = 'online-wp-status' + (type ? ' ' + type : '');
}
