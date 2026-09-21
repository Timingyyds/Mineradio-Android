/* ═══════════════════════════════════════════
 * 在线壁纸库 / 壁纸库 / 预览面板 — 前端逻辑
 * 桥接 window.KeepApp.onlineWp* 与 wpLibrary* 系列方法
 * ★ 所有网络请求均为异步回调，绝不阻塞 JS 主线程
 * ═══════════════════════════════════════════ */
'use strict';

// ===== 在线壁纸：状态 =====
var onlineWpState = {
  source: 'moewalls',          // 当前壁纸源（固定 moewalls，已移除多源切换）
  page: 1,                     // 当前页码
  keyword: '',                 // 搜索关键词
  list: [],                    // 当前页壁纸列表
  hasMore: false,              // 是否还有下一页
  totalPages: 0,               // 总页数
  total: 0,                    // 总壁纸数
  loading: false,              // 是否正在加载
  downloading: {},             // detailUrl -> {name, progress, status, startTime, timer}
  callbacks: {},               // callbackId -> 处理函数
  cbSeq: 0,                    // 回调 ID 序列号
  scrollTimer: 0,              // 滚动加载定时器
};

// ===== 壁纸库：状态 =====
var wpLibraryState = {
  category: 'all',
  list: [],
  loading: false,
};

// ★ 缓存已下载壁纸名称集合，用于在线列表显示"已下载"
var wpDownloadedNames = null;
function wpLibraryGetDownloadedNames() {
  if (wpDownloadedNames) return wpDownloadedNames;
  wpDownloadedNames = new Set();
  var bridge = onlineWpBridge();
  if (!bridge || typeof bridge.wpLibraryList !== 'function') return wpDownloadedNames;
  try {
    var raw = bridge.wpLibraryList('all');
    var data = onlineWpSafeParse(raw, { list: [] });
    if (data && Array.isArray(data.list)) {
      data.list.forEach(function (w) {
        var n = (w.name || '').replace(/\.[^.]+$/, '').trim().toLowerCase();
        if (n) wpDownloadedNames.add(n);
      });
    }
  } catch (e) {}
  return wpDownloadedNames;
}
function wpLibraryInvalidateDownloadedCache() {
  wpDownloadedNames = null;
}

// ===== 预览：状态 =====
var wpPreviewState = {
  currentId: '',
  currentUrl: '',
  currentType: 0,
  currentName: '',
};

// ===== 桥接辅助 =====
function onlineWpBridge() {
  return (typeof window !== 'undefined' && window.KeepApp) ? window.KeepApp : null;
}

function onlineWpSafeParse(jsonStr, fallback) {
  try {
    return JSON.parse(jsonStr);
  } catch (e) {
    return fallback;
  }
}

function onlineWpEscapeHtml(s) {
  if (s == null) return '';
  return String(s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

// 生成唯一回调 ID
function onlineWpGenCallbackId() {
  onlineWpState.cbSeq++;
  return 'owp_' + Date.now() + '_' + onlineWpState.cbSeq;
}

// ===== 异步回调注册（由 Kotlin evaluateJavascript 调用）=====
window.onlineWpOnListResult = function (callbackId, json, error) {
  var cb = onlineWpState.callbacks[callbackId];
  if (cb) {
    delete onlineWpState.callbacks[callbackId];
    cb(json, error);
  }
};

window.onlineWpOnDetailResult = function (callbackId, json, error) {
  var cb = onlineWpState.callbacks[callbackId];
  if (cb) {
    delete onlineWpState.callbacks[callbackId];
    cb(json, error);
  }
};

// ===== 在线壁纸：打开 / 关闭 =====
function wallpaperOpenOnline() {
  var modal = document.getElementById('online-wallpaper-modal');
  if (modal) modal.classList.add('show');
  // 首次打开自动加载第一页（已移除分类加载）
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

// ===== 源切换（已固定 moewalls，保留函数兼容 HTML） =====
function onlineWpSelectSource(source) {
  // 已移除多源切换，空实现
}

// ===== 搜索 =====
function onlineWpSearch() {
  var input = document.getElementById('online-wp-keyword');
  var kw = input ? input.value.trim() : '';
  onlineWpState.keyword = kw;
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

// ===== 加载一页（异步回调模式，不阻塞 JS 线程）=====
function onlineWpLoadPage(page) {
  var bridge = onlineWpBridge();
  if (!bridge) {
    onlineWpShowStatus('错误：window.KeepApp 不存在', 'error');
    return;
  }
  if (typeof bridge.onlineWpGetList !== 'function') {
    // ★ 调试：列出 KeepApp 上所有 onlineWp 开头的方法名
    var methods = [];
    try {
      for (var k in bridge) { if (k.indexOf('onlineWp') === 0 || k.indexOf('wpLibrary') === 0) methods.push(k); }
    } catch (e) {}
    onlineWpShowStatus('错误：onlineWpGetList 方法不存在。可用方法: ' + methods.join(', '), 'error');
    return;
  }
  if (onlineWpState.loading) return;
  onlineWpState.loading = true;
  onlineWpState.page = page;
  onlineWpShowLoading(true);
  onlineWpShowStatus('正在加载第 ' + page + ' 页...', '');

  // ★ 异步回调：桥接方法立即返回，结果通过 window.onlineWpOnListResult 回调
  var callbackId = onlineWpGenCallbackId();
  onlineWpState.callbacks[callbackId] = function (jsonStr, error) {
    onlineWpState.loading = false;
    onlineWpShowLoading(false);

    if (error) {
      onlineWpShowStatus('加载失败：' + error + '（请检查网络连接）', 'error');
      return;
    }
    var data = onlineWpSafeParse(jsonStr, { list: [], hasMore: false, total: 0 });
    if (data && data.error) {
      onlineWpShowStatus('加载失败：' + data.error + '（请检查网络连接）', 'error');
    }
    if (!data || !Array.isArray(data.list)) {
      data = { list: [], hasMore: false, total: 0 };
    }
    if (page === 1) onlineWpState.list = data.list;
    else onlineWpState.list = onlineWpState.list.concat(data.list);
    onlineWpState.hasMore = !!data.hasMore;
    onlineWpState.totalPages = data.totalPages || 0;
    onlineWpState.total = data.total || onlineWpState.list.length;
    onlineWpRenderList();
    onlineWpRenderPagination();
    if (!data.error && onlineWpState.list.length === 0) {
      onlineWpShowStatus(onlineWpState.keyword ? '未找到匹配的壁纸' : '此源暂无壁纸', '');
    } else if (!data.error) {
      onlineWpShowStatus('共 ' + onlineWpState.total + ' 张壁纸 · 第 ' + page + ' 页', 'success');
    }
  };

  try {
    // ★ 桥接方法立即返回（后台线程执行），结果通过 evaluateJavascript 回调
    bridge.onlineWpGetList(
      page,
      onlineWpState.keyword,
      onlineWpState.source,
      '',  // 分类已移除，传空
      '',  // 分辨率不筛选
      callbackId
    );
  } catch (e) {
    delete onlineWpState.callbacks[callbackId];
    onlineWpState.loading = false;
    onlineWpShowLoading(false);
    onlineWpShowStatus('加载失败：' + (e && e.message ? e.message : e), 'error');
  }

  // ★ 超时保护：30 秒后若仍未回调，提示网络慢
  setTimeout(function () {
    if (onlineWpState.callbacks[callbackId]) {
      // 还没回调
      onlineWpShowStatus('网络较慢，正在耐心加载...', '');
    }
  }, 8000);
  setTimeout(function () {
    if (onlineWpState.callbacks[callbackId]) {
      delete onlineWpState.callbacks[callbackId];
      onlineWpState.loading = false;
      onlineWpShowLoading(false);
      onlineWpShowStatus('加载超时：请检查网络连接后重试', 'error');
    }
  }, 45000);
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

// ===== 渲染列表 =====
function onlineWpRenderList() {
  var grid = document.getElementById('online-wp-grid');
  if (!grid) return;
  var list = onlineWpState.list;
  if (list.length === 0) {
    grid.innerHTML = '<div class="online-wp-empty">点击"重置"或输入关键词开始浏览壁纸</div>';
    grid.onscroll = null;
    return;
  }
  var html = '';
  list.forEach(function (item, idx) {
    var id = item.id || item.detailUrl || ('item-' + idx);
    var title = item.title || item.name || '未命名';
    var thumb = item.thumbnail || item.preview || '';
    var detailUrl = item.detailUrl || item.id || '';
    var source = item.source || onlineWpState.source;
    var type = item.type || 'image';
    var typeBadge = type === 'video' ? '<span class="online-wp-card-badge video">视频</span>' : '<span class="online-wp-card-badge">图片</span>';
    var resolution = item.resolution || '';
    var isVideo = type === 'video';
    var dlState = onlineWpState.downloading[detailUrl];
    var isDownloading = !!dlState;
    var progress = dlState ? dlState.progress : 0;
    var progressBar = isDownloading ? '<div class="online-wp-card-progress" style="width:' + progress + '%"></div>' : '';
    // ★ 检查是否已下载过
    var downloadedNames = wpLibraryGetDownloadedNames();
    var normalizedTitle = title.replace(/\.[^.]+$/, '').trim().toLowerCase();
    var isDownloaded = !isDownloading && downloadedNames.has(normalizedTitle);
    var downloadBtn = isDownloading ? '' : (isDownloaded ? '<span class="online-wp-card-downloaded">已下载</span>' : '<button class="online-wp-card-download" type="button" onclick="event.stopPropagation();onlineWpDownloadOne(\'' + onlineWpEscapeHtml(detailUrl).replace(/'/g, "\\'") + '\',\'' + onlineWpEscapeHtml(title).replace(/'/g, "\\'") + '\',' + (isVideo ? 1 : 3) + ',\'' + onlineWpEscapeHtml(source).replace(/'/g, "\\'") + '\',\'' + onlineWpEscapeHtml(item.fileId || '').replace(/'/g, "\\'") + '\')" title="下载到壁纸库">下载</button>');
    var media = '';
    if (thumb) {
      if (isVideo && item.previewVideo) {
        media = '<video src="' + onlineWpEscapeHtml(item.previewVideo) + '" poster="' + onlineWpEscapeHtml(thumb) + '" muted loop playsinline preload="metadata" onmouseover="try{this.play().catch(function(){})}catch(e){}" onmouseout="try{this.pause()}catch(e){}"></video>';
      } else {
        media = '<img src="' + onlineWpEscapeHtml(thumb) + '" loading="lazy" onerror="this.style.opacity=0.3" alt="">';
      }
    } else {
      media = '<div class="online-wp-card-placeholder">无预览</div>';
    }
    var cardClick = (isDownloading || isDownloaded) ? '' : 'onclick="onlineWpDownloadOne(\'' + onlineWpEscapeHtml(detailUrl).replace(/'/g, "\\'") + '\',\'' + onlineWpEscapeHtml(title).replace(/'/g, "\\'") + '\',' + (isVideo ? 1 : 3) + ',\'' + onlineWpEscapeHtml(source).replace(/'/g, "\\'") + '\',\'' + onlineWpEscapeHtml(item.fileId || '').replace(/'/g, "\\'") + '\')"';
    html += '<div class="online-wp-card" ' + cardClick + '>' +
      media + typeBadge + downloadBtn +
      '<div class="online-wp-card-title">' + onlineWpEscapeHtml(title) + (resolution ? ' · ' + onlineWpEscapeHtml(resolution) : '') + '</div>' +
      progressBar +
      '</div>';
  });
  grid.innerHTML = html;

  // 绑定滚动加载下一页
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
    pag.innerHTML = '<button type="button" onclick="onlineWpLoadPage(' + Math.max(1, onlineWpState.page - 1) + ')" ' + (onlineWpState.page <= 1 ? 'disabled' : '') + '>上一页</button>' +
      '<span>第 ' + onlineWpState.page + ' / ' + onlineWpState.totalPages + ' 页 · 共 ' + onlineWpState.total + ' 张</span>' +
      '<button type="button" onclick="onlineWpLoadPage(' + (onlineWpState.page + 1) + ')" ' + (!onlineWpState.hasMore ? 'disabled' : '') + '>下一页</button>';
  } else if (onlineWpState.hasMore) {
    pag.style.display = 'flex';
    pag.innerHTML = '<button type="button" onclick="onlineWpLoadPage(' + (onlineWpState.page + 1) + ')">加载更多</button>';
  } else {
    pag.style.display = 'none';
  }
}

// ===== 下载壁纸（异步，不阻塞 UI）=====
function onlineWpDownloadOne(detailUrl, name, type, source, fileId) {
  var bridge = onlineWpBridge();
  if (!bridge || typeof bridge.onlineWpDownload !== 'function') {
    onlineWpShowStatus('错误：当前环境不支持下载', 'error');
    return;
  }
  if (onlineWpState.downloading[detailUrl]) {
    onlineWpShowStatus('此壁纸正在下载中，请稍候...', '');
    return;
  }
  // ★ 不弹 confirm，直接开始下载
  onlineWpState.downloading[detailUrl] = {
    name: name,
    progress: 0,
    status: '准备中',
    startTime: Date.now(),
    timer: 0,
  };
  onlineWpRenderList();
  onlineWpShowStatus('开始下载 "' + name + '"...', '');

  try {
    bridge.onlineWpDownload(detailUrl, name, type || 1, source || onlineWpState.source, fileId || '');
  } catch (e) {
    delete onlineWpState.downloading[detailUrl];
    onlineWpShowStatus('下载启动失败：' + (e && e.message ? e.message : e), 'error');
    onlineWpRenderList();
    return;
  }

  // 启动进度轮询
  onlineWpPollProgress(detailUrl, name);
}

function onlineWpPollProgress(detailUrl, name) {
  var bridge = onlineWpBridge();
  if (!bridge || typeof bridge.onlineWpGetProgress !== 'function') return;

  var poll = function () {
    if (!onlineWpState.downloading[detailUrl]) return; // 已完成或已取消
    var raw = '';
    try { raw = bridge.onlineWpGetProgress(); } catch (e) { raw = '{"progress":0,"status":""}'; }
    var data = onlineWpSafeParse(raw, { progress: 0, status: '' });
    var progress = Math.max(0, Math.min(100, parseInt(data.progress || 0, 10) || 0));
    var status = data.status || '';

    var dlState = onlineWpState.downloading[detailUrl];
    if (!dlState) return;
    dlState.progress = progress;
    dlState.status = status;
    onlineWpRenderList();

    // ★ 判断完成：status 包含"完成"或"导入完成"
    if (/完成|导入完成/.test(status) && progress >= 100) {
      delete onlineWpState.downloading[detailUrl];
      onlineWpShowStatus('"' + name + '" 下载完成，已添加到壁纸库', 'success');
      // ★ 刷新已下载缓存
      wpLibraryInvalidateDownloadedCache();
      onlineWpRenderList();
      // 如果壁纸库面板已打开，刷新列表
      var libModal = document.getElementById('wallpaper-library-modal');
      if (libModal && libModal.classList.contains('show')) {
        wpLibraryLoad();
      }
      return;
    }
    // ★ 判断失败：status 包含"失败"或"错误"
    if (/失败|错误/.test(status)) {
      delete onlineWpState.downloading[detailUrl];
      onlineWpShowStatus('下载失败：' + status, 'error');
      onlineWpRenderList();
      return;
    }

    // 更新状态文字
    onlineWpShowStatus('正在下载 "' + name + '"... ' + progress + '% · ' + status, '');

    // ★ 超时保护：5 分钟未完成则停止轮询
    if (Date.now() - dlState.startTime > 300000) {
      delete onlineWpState.downloading[detailUrl];
      onlineWpShowStatus('"' + name + '" 下载超时，请检查网络后重试', 'error');
      onlineWpRenderList();
      return;
    }

    // 继续轮询（1.2 秒间隔）
    dlState.timer = setTimeout(poll, 1200);
  };
  // 首次延迟 800ms 让后台线程先启动
  onlineWpState.downloading[detailUrl].timer = setTimeout(poll, 800);
}

// ═══════════════════════════════════════════
//  壁纸库（本地）
// ═══════════════════════════════════════════
function wallpaperOpenLibrary() {
  var modal = document.getElementById('wallpaper-library-modal');
  if (modal) modal.classList.add('show');
  wpLibraryLoad();
}

function closeWallpaperLibrary() {
  var modal = document.getElementById('wallpaper-library-modal');
  if (modal) modal.classList.remove('show');
}

function wpLibrarySelectTab(cat) {
  if (wpLibraryState.category === cat) return;
  wpLibraryState.category = cat;
  document.querySelectorAll('#wp-library-tabs .online-wp-tab').forEach(function (btn) {
    btn.classList.toggle('active', btn.dataset.cat === cat);
  });
  wpLibraryLoad();
}

function wpLibraryLoad() {
  var bridge = onlineWpBridge();
  if (!bridge || typeof bridge.wpLibraryList !== 'function') {
    wpLibraryShowStatus('错误：当前环境不支持壁纸库', 'error');
    return;
  }
  wpLibraryState.loading = true;
  wpLibraryShowStatus('正在加载壁纸库...', '');
  setTimeout(function () {
    var raw = '';
    try { raw = bridge.wpLibraryList(wpLibraryState.category); } catch (e) {
      raw = '{"list":[],"total":0,"error":"' + (e && e.message ? e.message : e) + '"}';
    }
    wpLibraryState.loading = false;
    var data = onlineWpSafeParse(raw, { list: [], total: 0 });
    if (data && data.error) {
      wpLibraryShowStatus('加载失败：' + data.error, 'error');
    }
    if (!data || !Array.isArray(data.list)) data = { list: [], total: 0 };
    var baseList = data.list;
    var baseTotal = data.total;
    var hasError = !!data.error;
    // ★ 合并 Wallpaper Engine (MPKG) 导入的项目，但只含有 previewData 的才显示
    var done = function (mpkgList) {
      var merged = baseList.concat(mpkgList || []);
      // ★ 全局去重：按标准化标题（去扩展名、转小写）去重，跨所有类型
      var seen = {};
      wpLibraryState.list = merged.filter(function (w) {
        var rawName = (w.name || w._mpkgProject && w._mpkgProject.title || '').replace(/\.[^.]+$/, '').trim().toLowerCase();
        var key = rawName || (w._mpkg ? 'mpkg:' + (w._mpkgProject && w._mpkgProject.id || '') : 'file:' + (w.path || w.id || ''));
        if (seen[key]) return false;
        seen[key] = true;
        return true;
      });
      wpLibraryRender();
      if (!hasError) {
        var total = wpLibraryState.list.length;
        wpLibraryShowStatus('共 ' + total + ' 张壁纸', total > 0 ? 'success' : '');
      }
    };
    if (typeof restoreWallpaperEngineLocalProjects !== 'function') { done([]); return; }
    Promise.resolve(restoreWallpaperEngineLocalProjects()).then(function () {
      var mpkgList = [];
      if (typeof wallpaperEngineProjects !== 'undefined' && Array.isArray(wallpaperEngineProjects)) {
        wallpaperEngineProjects.forEach(function (proj) {
          // ★ 只有有 previewData 的 MPKG 项目才加入壁纸库，无封面的不显示
          if (proj && proj.previewData) {
            mpkgList.push({
              id: 'mpkg_' + proj.id,
              name: proj.title || 'Wallpaper Engine',
              type: 2,
              _mpkg: true,
              _mpkgProject: proj
            });
          }
        });
      }
      done(mpkgList);
    }).catch(function () { done([]); });
  }, 30);
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
  var list = wpLibraryState.list;
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
    var isPhone = !!item.isPhone;
    var isOnline = !!item.isOnline;
    // ★ 优先使用 thumbnail（视频壁纸的第一帧缩略图），图片壁纸用原路径
    var thumbUrl = item.thumbnail || '';
    var fullUrl = path ? (path.indexOf('http') === 0 ? path : 'file://' + path) : '';
    var badge = isPhone ? '<span class="online-wp-card-badge">手机</span>' : (isOnline ? '<span class="online-wp-card-badge">在线</span>' : '');
    var media = '';
    var hasCover = false; // ★ 标记是否有有效封面
    if (item._mpkg) {
      // ★ MPKG 项目：仅使用 previewData（实际数据URL），不用 wallpaperEngineMediaUrl 协议URL
      var proj = item._mpkgProject;
      var mpkgPreview = (proj && proj.previewData) || '';
      if (mpkgPreview) {
        media = '<img src="' + onlineWpEscapeHtml(mpkgPreview) + '" loading="lazy" onerror="this.style.opacity=0.3" alt="">';
        hasCover = true;
      } else {
        media = '<div class="online-wp-card-placeholder">无预览</div>';
      }
      badge = '<span class="online-wp-card-badge">MPKG</span>';
    } else if (thumbUrl) {
      media = '<img src="' + onlineWpEscapeHtml(thumbUrl) + '" loading="lazy" onerror="this.style.opacity=0.3" alt="">';
      hasCover = true;
    } else if (fullUrl && type === 1) {
      // 视频类型：即使无缩略图也显示（取第一帧当封面）
      media = '<video src="' + onlineWpEscapeHtml(fullUrl) + '" muted preload="metadata" onmouseover="try{this.play().catch(function(){})}catch(e){}" onmouseout="try{this.pause()}catch(e){}"></video>';
      hasCover = true;
    } else {
      media = '<div class="online-wp-card-placeholder">无预览</div>';
    }
    // ★ 无封面的壁纸不加载（用户要求：没有封面的壁纸不显示）
    if (!hasCover) return;
    html += '<div class="online-wp-card" onclick="wpLibraryPreview(\'' + onlineWpEscapeHtml(id).replace(/'/g, "\\'") + '\')">' +
      media + badge +
      '<div class="online-wp-card-title">' + onlineWpEscapeHtml(name) + '</div>' +
      '</div>';
  });
  grid.innerHTML = html;
}

function wpLibraryPreview(id) {
  // ★ MPKG 项目：直接使用 Wallpaper Engine 数据，不走 Kotlin 桥接
  if (typeof id === 'string' && id.indexOf('mpkg_') === 0) {
    var mpkgId = id.substring(5);
    var proj = (typeof wallpaperEngineProjects !== 'undefined' && Array.isArray(wallpaperEngineProjects))
      ? wallpaperEngineProjects.find(function (p) { return p.id === mpkgId; }) : null;
    if (proj) {
      wpPreviewState.currentId = id;
      wpPreviewState.currentType = 2;
      wpPreviewState.currentName = proj.title || 'Wallpaper Engine';
      wpPreviewState.currentUrl = '';
      wpPreviewState._mpkgProject = proj;
      var titleEl0 = document.getElementById('wp-preview-title');
      if (titleEl0) titleEl0.textContent = proj.title || '壁纸预览';
      var mediaEl0 = document.getElementById('wp-preview-media');
      if (mediaEl0) {
        var mpkgPreview = proj.previewData || (proj.hasPreview && typeof wallpaperEngineMediaUrl === 'function' ? wallpaperEngineMediaUrl(proj, 'preview') : '');
        if (mpkgPreview) {
          mediaEl0.innerHTML = '<img src="' + onlineWpEscapeHtml(mpkgPreview) + '" alt="">';
        } else {
          mediaEl0.innerHTML = '<div class="online-wp-empty">无预览内容</div>';
        }
      }
      var statusEl0 = document.getElementById('wp-preview-status');
      if (statusEl0) statusEl0.textContent = '';
      var modal0 = document.getElementById('wallpaper-preview-modal');
      if (modal0) modal0.classList.add('show');
      return;
    }
  }
  var bridge = onlineWpBridge();
  if (!bridge || typeof bridge.wpLibraryPreview !== 'function') return;
  var raw = '';
  try { raw = bridge.wpLibraryPreview(id); } catch (e) {
    wpLibraryShowStatus('预览失败：' + (e && e.message ? e.message : e), 'error');
    return;
  }
  var data = onlineWpSafeParse(raw, { ok: false });
  if (!data.ok) {
    wpLibraryShowStatus('预览失败：' + (data.error || '未知错误'), 'error');
    return;
  }
  wpPreviewState.currentId = data.id || id;
  wpPreviewState.currentUrl = data.url || '';
  wpPreviewState.currentType = data.type || 0;
  wpPreviewState.currentName = data.name || '';
  wpPreviewState._mpkgProject = null;

  var titleEl = document.getElementById('wp-preview-title');
  if (titleEl) titleEl.textContent = data.name || '壁纸预览';
  var mediaEl = document.getElementById('wp-preview-media');
  if (mediaEl) {
    var url = data.url || '';
    var thumb = data.thumbnail || '';
    if (url) {
      if (data.type === 1) {
        mediaEl.innerHTML = '<video src="' + onlineWpEscapeHtml(url) + '"' +
          (thumb ? ' poster="' + onlineWpEscapeHtml(thumb) + '"' : '') +
          ' controls autoplay loop muted playsinline></video>';
      } else {
        mediaEl.innerHTML = '<img src="' + onlineWpEscapeHtml(thumb || url) + '" alt="">';
      }
    } else {
      mediaEl.innerHTML = '<div class="online-wp-empty">无预览内容</div>';
    }
  }
  var statusEl = document.getElementById('wp-preview-status');
  if (statusEl) statusEl.textContent = '';

  var modal = document.getElementById('wallpaper-preview-modal');
  if (modal) modal.classList.add('show');
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
  wpPreviewState.currentUrl = '';
  wpPreviewState._mpkgProject = null;
}

function wallpaperPreviewSetAppBg() {
  // ★ MPKG 项目：直接调用 Wallpaper Engine 的应用背景函数
  if (wpPreviewState.currentType === 2 && wpPreviewState._mpkgProject) {
    if (typeof setCustomBackgroundMedia === 'function') setCustomBackgroundMedia(null, true);
    if (typeof activateWallpaperEngineItem === 'function') {
      activateWallpaperEngineItem(wpPreviewState._mpkgProject.id);
    } else if (typeof applyWallpaperEngineBackground === 'function') {
      applyWallpaperEngineBackground(wpPreviewState._mpkgProject, false);
    }
    if (typeof renderWallpaperEngineLibrary === 'function') renderWallpaperEngineLibrary(true);
    wpPreviewShowStatus('已设为应用背景：' + (wpPreviewState._mpkgProject.title || wpPreviewState.currentName), 'success');
    if (typeof refreshWallpaperInfo === 'function') refreshWallpaperInfo();
    return;
  }
  var bridge = onlineWpBridge();
  if (!bridge || !wpPreviewState.currentId) return;
  var raw = '';
  try { raw = bridge.wpLibrarySetAppBg(wpPreviewState.currentId); } catch (e) {
    wpPreviewShowStatus('设置失败：' + (e && e.message ? e.message : e), 'error');
    return;
  }
  var data = onlineWpSafeParse(raw, { ok: false });
  if (data.ok) {
    wpPreviewShowStatus('已设为应用背景：' + (data.name || wpPreviewState.currentName), 'success');
    if (typeof refreshWallpaperInfo === 'function') refreshWallpaperInfo();
    if (typeof deactivateWallpaperEngineBackground === 'function') deactivateWallpaperEngineBackground(true);
    if (data.type === 1 && wpPreviewState.currentId) {
      var videoUrl = location.origin + '/api/wallpaper/file?id=' + encodeURIComponent(wpPreviewState.currentId) + '&raw=1';
      fx.backgroundMedia = { type: 'video', src: videoUrl, name: data.name || '' };
      fx.backgroundAlbumCover = false;
      applyCustomBackground();
      saveLyricLayout({ user: true, reason: 'backgroundMedia' });
    }
  } else {
    wpPreviewShowStatus('设置失败：' + (data.error || '未知错误'), 'error');
  }
}

function wallpaperPreviewClearBg() {
  if (typeof setCustomBackgroundMedia === 'function') {
    setCustomBackgroundMedia(null);
    wpPreviewShowStatus('已清除背景媒体', 'success');
  }
  // ★ 同步清除 Wallpaper Engine 选择状态，避免重启后自动重新应用旧壁纸
  try {
    if (typeof wallpaperEngineSelection !== 'undefined' && wallpaperEngineSelection && wallpaperEngineSelection.active) {
      wallpaperEngineSelection.active = false;
      if (typeof saveWallpaperEngineSelection === 'function') saveWallpaperEngineSelection();
      if (typeof updateWallpaperEngineEntryUi === 'function') updateWallpaperEngineEntryUi();
    }
  } catch (e) { console.warn('clear WE selection failed:', e); }
  // ★ 同步清除系统壁纸记录并关闭强制系统壁纸开关，避免重启后自动恢复旧壁纸导致黑屏
  var bridge = onlineWpBridge();
  if (bridge) {
    try { if (typeof bridge.wallpaperClearSystem === 'function') bridge.wallpaperClearSystem(); } catch (e) { console.warn('wallpaperClearSystem failed:', e); }
    try { if (typeof bridge.setForceSystemWallpaper === 'function') bridge.setForceSystemWallpaper('0'); } catch (e) { console.warn('setForceSystemWallpaper failed:', e); }
  }
}

function wallpaperPreviewSetSystem() {
  var bridge = onlineWpBridge();
  if (!bridge || !wpPreviewState.currentId) return;
  var currentId = wpPreviewState.currentId;
  // ★ MPKG 壁纸：从 IndexedDB 读取 Blob，转为 base64 传给 Kotlin 端保存为文件
  if (typeof currentId === 'string' && currentId.indexOf('mpkg_') === 0) {
    var mpkgId = currentId.substring(5);
    var proj = wpPreviewState._mpkgProject;
    if (!proj) {
      wpPreviewShowStatus('设置失败：MPKG 项目数据缺失', 'error');
      return;
    }
    var mpkgName = proj.title || 'Wallpaper Engine';
    wpPreviewShowStatus('正在导出 MPKG 文件...', '');
    // 从 IndexedDB 读取 MPKG Blob
    if (typeof readWallpaperEngineLocalProjects !== 'function') {
      wpPreviewShowStatus('设置失败：无法访问 Wallpaper Engine 存储', 'error');
      return;
    }
    readWallpaperEngineLocalProjects().then(function (records) {
      var record = records.find(function (r) { return r.id === mpkgId; });
      if (!record || !record.blob) {
        wpPreviewShowStatus('设置失败：找不到 MPKG 文件数据', 'error');
        return;
      }
      var blob = record.blob;
      // ★ 严格清洗文件名：剥离 URL 查询参数（?v=...&token=...）和非法字符
      //   原因：record.name 在某些路径下可能被污染为 mediaUrl 片段（含 ?v=&token=）
      //   导致 Kotlin 端 File(targetDir, fileName) 创建出带 ? 的非法路径，mpkg 文件读取失败 → 黑屏
      var rawName = String(record.fileName || record.name || mpkgId || '');
      // 1. 去掉 URL 查询参数（从 ? 开始的全部内容）
      rawName = rawName.split('?')[0].split('#')[0];
      // 2. 去掉路径分隔符（保险）
      rawName = rawName.replace(/[/\\]/g, '');
      // 3. 只保留字母、数字、点、下划线、连字符
      rawName = rawName.replace(/[^a-zA-Z0-9._-]/g, '');
      // 4. 如果清洗后为空，使用干净的 mpkgId
      if (!rawName) rawName = mpkgId;
      // 5. 确保以 .mpkg 结尾
      var fileName = rawName.toLowerCase().endsWith('.mpkg') ? rawName : (rawName + '.mpkg');
      console.log('[wpLibrarySetSystem] mpkgId=', mpkgId, 'rawName=', rawName, 'fileName=', fileName);
      // ★ 分块写入临时文件，避免 Base64.decode 大文件导致 OOM
      var blobUrl = null;
      try { blobUrl = URL.createObjectURL(blob); } catch (e) {}
      if (!blobUrl) {
        wpPreviewShowStatus('设置失败：无法创建临时URL', 'error');
        return;
      }
      wpPreviewShowStatus('正在写入临时文件...', '');
      fetch(blobUrl).then(function (resp) { return resp.arrayBuffer(); }).then(function (buf) {
        URL.revokeObjectURL(blobUrl);
        var arr = new Uint8Array(buf);
        // ★ 分块写入：每块 2MB，避免 Base64.decode 单次过大
        var CHUNK_SIZE = 2 * 1024 * 1024;
        var startRes = onlineWpSafeParse(bridge.wpWriteTempFileStart(fileName), { ok: false });
        if (!startRes.ok || !startRes.path) {
          wpPreviewShowStatus('设置失败：无法创建临时文件', 'error');
          return;
        }
        var tempPath = startRes.path;
        var offset = 0;
        function writeNextChunk() {
          if (offset >= arr.length) {
            // 所有块写入完成
            var finishRes = onlineWpSafeParse(bridge.wpWriteTempFileFinish(tempPath), { ok: false });
            if (!finishRes.ok) {
              wpPreviewShowStatus('设置失败：临时文件写入完成检查失败', 'error');
              return;
            }
            // 调用 Kotlin 端从临时文件复制并设为系统壁纸
            var raw = '';
            try {
              raw = bridge.wpLibrarySetSystemMpkgFromFile(mpkgId, mpkgName, fileName, tempPath);
            } catch (e) {
              wpPreviewShowStatus('设置失败：' + (e && e.message ? e.message : e), 'error');
              return;
            }
            var data = onlineWpSafeParse(raw, { ok: false });
            if (!data.ok) {
              wpPreviewShowStatus('设置失败：' + (data.error || '未知错误'), 'error');
              return;
            }
            if (data.result === 'NEED_ACTIVATE') {
              wpPreviewShowStatus('已选定为系统壁纸，请在系统设置中激活动态壁纸', 'success');
              if (typeof bridge.gotoSystemWallpaperSettings === 'function') {
                setTimeout(function () { try { bridge.gotoSystemWallpaperSettings(); } catch (e) {} }, 1200);
              }
            } else {
              wpPreviewShowStatus('已设为系统壁纸：' + mpkgName, 'success');
            }
            return;
          }
          var end = Math.min(offset + CHUNK_SIZE, arr.length);
          var chunk = arr.subarray(offset, end);
          // 转 base64
          var binary = '';
          for (var i = 0; i < chunk.length; i++) binary += String.fromCharCode(chunk[i]);
          var b64 = btoa(binary);
          try {
            var chunkRes = onlineWpSafeParse(bridge.wpWriteTempFileChunk(tempPath, b64), { ok: false });
            if (!chunkRes.ok) {
              wpPreviewShowStatus('设置失败：分块写入失败 ' + (chunkRes.error || ''), 'error');
              return;
            }
          } catch (e) {
            wpPreviewShowStatus('设置失败：分块写入异常 ' + (e && e.message ? e.message : e), 'error');
            return;
          }
          offset = end;
          // 异步递归，避免阻塞 UI
          setTimeout(writeNextChunk, 0);
        }
        writeNextChunk();
      }).catch(function (e) {
        if (blobUrl) URL.revokeObjectURL(blobUrl);
        wpPreviewShowStatus('设置失败：MPKG 文件读取失败 ' + (e && e.message ? e.message : e), 'error');
      });
    }).catch(function (e) {
      wpPreviewShowStatus('设置失败：' + (e && e.message ? e.message : e), 'error');
    });
    return;
  }
  // ★ 普通壁纸：通过 Kotlin 桥接设为系统壁纸
  var raw = '';
  try { raw = bridge.wpLibrarySetSystem(currentId); } catch (e) {
    wpPreviewShowStatus('设置失败：' + (e && e.message ? e.message : e), 'error');
    return;
  }
  var data = onlineWpSafeParse(raw, { ok: false });
  if (!data.ok) {
    wpPreviewShowStatus('设置失败：' + (data.error || '未知错误'), 'error');
    return;
  }
  if (data.result === 'NEED_ACTIVATE') {
    wpPreviewShowStatus('已选定为系统壁纸，请在系统设置中激活动态壁纸', 'success');
    if (typeof bridge.gotoSystemWallpaperSettings === 'function') {
      setTimeout(function () { try { bridge.gotoSystemWallpaperSettings(); } catch (e) {} }, 1200);
    }
  } else {
    wpPreviewShowStatus('已设为系统壁纸：' + wpPreviewState.currentName, 'success');
  }
}

var wpPreviewDeleteArmed = false;
var wpPreviewDeleteArmTimer = 0;
function wallpaperPreviewResetDeleteArm() {
  wpPreviewDeleteArmed = false;
  var btn = document.getElementById('wp-preview-delete-btn');
  if (btn) { btn.textContent = '删除'; btn.classList.remove('armed'); }
  if (wpPreviewDeleteArmTimer) { clearTimeout(wpPreviewDeleteArmTimer); wpPreviewDeleteArmTimer = 0; }
}
function wallpaperPreviewDelete() {
  var bridge = onlineWpBridge();
  if (!bridge || !wpPreviewState.currentId) return;
  if (!wpPreviewDeleteArmed) {
    wpPreviewDeleteArmed = true;
    var btn = document.getElementById('wp-preview-delete-btn');
    if (btn) { btn.textContent = '确定删除？'; btn.classList.add('armed'); }
    if (wpPreviewDeleteArmTimer) clearTimeout(wpPreviewDeleteArmTimer);
    wpPreviewDeleteArmTimer = setTimeout(function () { wallpaperPreviewResetDeleteArm(); }, 3000);
    return;
  }
  wallpaperPreviewResetDeleteArm();
  var currentId = wpPreviewState.currentId;
  // ★ MPKG 壁纸：从 IndexedDB 和 WallpaperEngine 项目列表中删除
  if (typeof currentId === 'string' && currentId.indexOf('mpkg_') === 0) {
    var mpkgId = currentId.substring(5);
    if (typeof removeWallpaperEngineLocalProject === 'function') {
      removeWallpaperEngineLocalProject(mpkgId).then(function () {
        // 同时从 wallpaperEngineProjects 数组中移除
        if (typeof wallpaperEngineProjects !== 'undefined' && Array.isArray(wallpaperEngineProjects)) {
          var idx = wallpaperEngineProjects.findIndex(function (p) { return p.id === mpkgId; });
          if (idx >= 0) wallpaperEngineProjects.splice(idx, 1);
        }
        // 从隐藏列表中也移除（如果有）
        if (typeof hiddenWallpaperEngineIds !== 'undefined' && hiddenWallpaperEngineIds.delete) {
          hiddenWallpaperEngineIds.delete(mpkgId);
        }
        wpPreviewShowStatus('已删除：' + wpPreviewState.currentName, 'success');
        closeWallpaperPreview();
        wpLibraryLoad();
      }).catch(function (e) {
        wpPreviewShowStatus('删除失败：' + (e && e.message ? e.message : e), 'error');
      });
    } else {
      wpPreviewShowStatus('删除失败：Wallpaper Engine 模块未加载', 'error');
    }
    return;
  }
  // ★ 普通壁纸：通过 Kotlin 桥接删除（会从本地和私有目录彻底删除）
  try { bridge.wpLibraryDelete(currentId); } catch (e) {
    wpPreviewShowStatus('删除失败：' + (e && e.message ? e.message : e), 'error');
    return;
  }
  wpPreviewShowStatus('已删除：' + wpPreviewState.currentName, 'success');
  closeWallpaperPreview();
  wpLibraryLoad();
}

function wpPreviewShowStatus(msg, type) {
  var el = document.getElementById('wp-preview-status');
  if (!el) return;
  el.textContent = msg || '';
  el.className = 'online-wp-status' + (type ? ' ' + type : '');
}
