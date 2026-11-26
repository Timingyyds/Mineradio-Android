/* ═══════════════════════════════════════════
 * 在线插件下载 — 读取蓝奏云文件夹，列出插件名，点下载自动安装
 * 官方插件与第三方插件使用各自独立的分享文件夹，互不混用
 * 原生接口：MineradioPlugins.listOnlinePlugins / installOnlinePlugin
 * ═══════════════════════════════════════════ */
'use strict';

// 官方插件目录：留空则视为未配置（发布版不内置任何网盘地址）
var OFFICIAL_PLUGIN_FOLDER = '';
// 第三方插件目录：留空则视为未配置（发布版不内置任何网盘地址）
var ONLINE_PLUGIN_FOLDER = '';

function createPluginStore(kind, listIds, statusIds, folder) {
  return {
    kind: kind,
    listIds: listIds,
    statusIds: statusIds,
    folder: folder,
    list: [],
    loaded: false,
    loading: false
  };
}

var officialPluginState = createPluginStore(
  'official',
  ['official-plugin-list'],
  ['official-plugin-status'],
  OFFICIAL_PLUGIN_FOLDER
);

var onlinePluginState = createPluginStore(
  'thirdparty',
  ['online-plugin-list'],
  ['online-plugin-status'],
  ONLINE_PLUGIN_FOLDER
);

var pluginInstalledCache = [];
var pluginInstallState = { name: null, timer: 0 };

/** 拉取已安装插件，用于在插件列表里显示「已安装」。 */
async function pluginRefreshInstalled() {
  try {
    var data = await (await fetch('/api/plugins/list?_t=' + Date.now())).json();
    pluginInstalledCache = (data.plugins || []).map(function (p) {
      return { id: p.id || '', name: p.name || '' };
    });
  } catch (e) {
    pluginInstalledCache = [];
  }
}

async function onlinePluginRefreshInstalled() {
  return pluginRefreshInstalled();
}

function onlinePluginNormName(s) {
  return String(s || '')
    .replace(/\.[^.]+$/, '')
    .toLowerCase()
    .replace(/[^a-z0-9\u4e00-\u9fa5]/g, '');
}

/** 在线包的 zip 名与插件 manifest 名可能不同，这里做等价匹配。 */
function pluginFindInstalled(name) {
  var key = onlinePluginNormName(name);
  if (!key) return null;
  var latin = (key.match(/^[a-z0-9]{3,}/) || [''])[0];
  var list = pluginInstalledCache || [];
  for (var i = 0; i < list.length; i += 1) {
    var other = onlinePluginNormName(list[i].name);
    if (!other) continue;
    if (other === key || other.indexOf(key) >= 0 || key.indexOf(other) >= 0) return list[i];
    if (latin && other.indexOf(latin) === 0) return list[i];
  }
  return null;
}

function onlinePluginFindInstalled(name) {
  return pluginFindInstalled(name);
}

function onlinePluginApi() {
  var cap = window.Capacitor;
  if (!cap || !cap.isNativePlatform || !cap.isNativePlatform()) return null;
  return (cap.Plugins && cap.Plugins.MineradioPlugins) || null;
}

function onlinePluginEscape(s) {
  if (s == null) return '';
  return String(s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

function pluginShowStatus(store, msg, type) {
  store.statusIds.forEach(function (id) {
    var el = document.getElementById(id);
    if (!el) return;
    el.textContent = msg || '';
    el.className = 'online-wp-status' + (type ? ' ' + type : '');
  });
}

function onlinePluginShowStatus(msg, type) {
  pluginShowStatus(onlinePluginState, msg, type);
}

function officialPluginShowStatus(msg, type) {
  pluginShowStatus(officialPluginState, msg, type);
}

/**
 * 已安装插件仍由插件管理小面板维护；
 * 官方插件与第三方插件各自用独立的大面板展示与下载。
 */
function openOnlinePluginStore() {
  var modal = document.getElementById('online-plugin-modal');
  if (modal) modal.classList.add('show');
  if (!onlinePluginState.loaded && !onlinePluginState.loading) pluginStoreLoad(onlinePluginState);
}

function closeOnlinePluginStore() {
  var modal = document.getElementById('online-plugin-modal');
  if (modal) modal.classList.remove('show');
}

function openOfficialPluginStore() {
  var modal = document.getElementById('official-plugin-modal');
  if (modal) modal.classList.add('show');
  if (!officialPluginState.loaded && !officialPluginState.loading) pluginStoreLoad(officialPluginState);
}

function closeOfficialPluginStore() {
  var modal = document.getElementById('official-plugin-modal');
  if (modal) modal.classList.remove('show');
}

function onlinePluginFolderValue() {
  var input = document.getElementById('online-plugin-folder');
  var value = input && input.value ? input.value.trim() : '';
  return value || ONLINE_PLUGIN_FOLDER;
}

async function pluginStoreRunLoad(store, api) {
  try {
    var data = await api.listOnlinePlugins({ folderUrl: store.folder });
    if (!data || !data.ok) {
      store.loaded = false;
      pluginShowStatus(store, '读取失败：' + ((data && data.error) || '未知错误'), 'error');
      store.listIds.forEach(function (id) {
        var el = document.getElementById(id);
        if (el) el.innerHTML = '<div class="online-wp-empty">读取失败，请检查分享链接</div>';
      });
      return;
    }
    store.list = Array.isArray(data.files) ? data.files : [];
    store.loaded = true;
    await pluginRefreshInstalled();
    pluginStoreRender(store);
    pluginShowStatus(store, '共 ' + store.list.length + ' 个插件包', store.list.length ? 'success' : '');
  } catch (e) {
    store.loaded = false;
    pluginShowStatus(store, '读取失败：' + (e && e.message ? e.message : e), 'error');
    store.listIds.forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.innerHTML = '<div class="online-wp-empty">读取失败，请检查网络或分享链接</div>';
    });
  } finally {
    store.loading = false;
  }
}

function pluginStoreLoad(store) {
  var api = onlinePluginApi();
  if (!store.folder) {
    pluginShowStatus(store, '未配置插件目录', 'error');
    store.listIds.forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.innerHTML = '<div class="online-wp-empty">未配置插件目录</div>';
    });
    return Promise.resolve();
  }
  if (!api || typeof api.listOnlinePlugins !== 'function') {
    pluginShowStatus(store, '错误：当前环境不支持在线插件', 'error');
    return Promise.resolve();
  }
  if (store.loading) return Promise.resolve();
  store.loading = true;
  store.listIds.forEach(function (id) {
    var el = document.getElementById(id);
    if (el) el.innerHTML = '<div class="online-wp-empty">正在读取插件目录...</div>';
  });
  pluginShowStatus(store, '正在读取插件目录...', '');
  return pluginStoreRunLoad(store, api);
}

function onlinePluginLoad() {
  return pluginStoreLoad(onlinePluginState);
}

function officialPluginLoad() {
  return pluginStoreLoad(officialPluginState);
}

function pluginStoreRender(store) {
  store.listIds.forEach(function (id) {
    pluginStoreRenderInto(store, id);
  });
}

function pluginRenderAll() {
  pluginStoreRender(onlinePluginState);
  pluginStoreRender(officialPluginState);
}

function onlinePluginRender() {
  pluginStoreRender(onlinePluginState);
}

function officialPluginRender() {
  pluginStoreRender(officialPluginState);
}

function pluginStoreRenderInto(store, containerId) {
  var listEl = document.getElementById(containerId);
  if (!listEl) return;
  var list = store.list || [];
  if (!list.length) {
    listEl.innerHTML = '<div class="online-wp-empty">这个文件夹里暂时没有可用插件</div>';
    return;
  }
  var installFn = store.kind === 'official' ? 'officialPluginInstall' : 'onlinePluginInstall';
  var html = '';
  list.forEach(function (item, idx) {
    var name = item.name || ('插件 ' + (idx + 1));
    var size = item.size || '';
    var time = item.time || '';
    var installing = pluginInstallState.name === name;
    var installedEntry = pluginFindInstalled(name);
    var actionHtml;
    if (installing) {
      actionHtml = '<button class="fx-mini-btn ghost" type="button" disabled>安装中…</button>';
    } else if (installedEntry) {
      actionHtml = '<span class="online-plugin-installed" title="已下载：' +
        onlinePluginEscape(installedEntry.name) + '">已下载</span>';
    } else {
      actionHtml = '<button class="fx-mini-btn" type="button" onclick="' + installFn + '(\'' +
        onlinePluginEscape(item.url).replace(/'/g, '\\\'') + '\',\'' +
        onlinePluginEscape(name).replace(/'/g, '\\\'') + '\')">下载</button>';
    }
    var nameHtml = '<span class="online-plugin-name-text">' + onlinePluginEscape(name) + '</span>';
    if (store.kind === 'official') {
      nameHtml += '<span class="plugin-official-badge">官方</span>';
    }
    html += '<div class="online-plugin-row">' +
      '<div class="online-plugin-info">' +
      '<div class="online-plugin-name">' + nameHtml + '</div>' +
      '<div class="online-plugin-meta">' +
      (size ? onlinePluginEscape(size) : '') +
      (time ? (size ? ' · ' : '') + onlinePluginEscape(time) : '') +
      '</div></div>' +
      actionHtml +
      '</div>';
  });
  listEl.innerHTML = html;
}

function pluginStartProgress(store) {
  pluginStopProgress();
  var api = onlinePluginApi();
  if (!api || typeof api.getOnlineInstallProgress !== 'function') return;
  pluginInstallState.timer = setInterval(async function () {
    try {
      var p = await api.getOnlineInstallProgress();
      if (p && p.status) pluginShowStatus(store, p.status, '');
    } catch (e) { /* 忽略 */ }
  }, 1200);
}

function pluginStopProgress() {
  if (pluginInstallState.timer) {
    clearInterval(pluginInstallState.timer);
    pluginInstallState.timer = 0;
  }
}

async function pluginInstall(store, url, name) {
  var api = onlinePluginApi();
  if (!api || typeof api.installOnlinePlugin !== 'function') {
    pluginShowStatus(store, '错误：当前环境不支持在线安装', 'error');
    return;
  }
  if (pluginInstallState.name) {
    pluginShowStatus(store, '已有插件正在安装，请稍候', '');
    return;
  }
  pluginInstallState.name = name;
  pluginRenderAll();
  pluginShowStatus(store, '正在下载：' + name, '');
  pluginStartProgress(store);

  try {
    var result = await api.installOnlinePlugin({ url: url, name: name });
    pluginStopProgress();
    pluginInstallState.name = null;
    if (!result || !result.ok) {
      pluginShowStatus(store, '安装失败：' + ((result && (result.message || result.error)) || '未知错误'), 'error');
      pluginRenderAll();
      return;
    }
    var id = result.pluginId || '';
    pluginShowStatus(store, '已安装：' + (result.name || name), 'success');
    if (typeof showToast === 'function') showToast('插件已安装：' + (result.name || name));
    await pluginRefreshInstalled();
    pluginRenderAll();

    // 刷新插件管理面板，并立即加载新插件
    try {
      await fetch('/api/plugins/list?_t=' + Date.now());
      if (typeof refreshPluginList === 'function') refreshPluginList();
      if (id && window.MineradioPlugins) {
        var data = await (await fetch('/api/plugins/list?_t=' + Date.now())).json();
        var p = (data.plugins || []).find(function (it) { return it.id === id; });
        if (p) {
          window.MineradioPlugins.unloadPlugin(id);
          window.MineradioPlugins.loadPlugin(id, p);
        }
      }
    } catch (e) { /* 刷新失败不影响安装结果 */ }
  } catch (e) {
    pluginStopProgress();
    pluginInstallState.name = null;
    pluginShowStatus(store, '安装失败：' + (e && e.message ? e.message : e), 'error');
    pluginRenderAll();
  }
}

function onlinePluginInstall(url, name) {
  return pluginInstall(onlinePluginState, url, name);
}

function officialPluginInstall(url, name) {
  return pluginInstall(officialPluginState, url, name);
}