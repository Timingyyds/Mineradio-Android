function normalizeBuiltInPlaylistRows(rows) {
  return (Array.isArray(rows) ? rows : []).map(function (playlist) {
    return Object.assign({}, playlist, {
      provider: 'mineradio',
      source: 'mineradio',
      builtin: true,
      creator: playlist.creator || 'Mineradio',
      shelfPane: 'mine',
      subscribed: false
    });
  }).filter(function (playlist) { return !!playlist.id; });
}

function applyBuiltInPlaylistSnapshot(result, opts) {
  opts = opts || {};
  if (!result || result.ok !== true) return false;
  builtInPlaylists = normalizeBuiltInPlaylistRows(result.playlists);
  if (typeof rebuildUserPlaylistsFromCatalog === 'function') {
    rebuildUserPlaylistsFromCatalog({
      animate: !!opts.animate,
      preserveScroll: opts.preserveScroll !== false,
      reason: opts.reason || 'built-in-playlists'
    });
  } else {
    userPlaylists = builtInPlaylists.concat(neteasePlaylists, qqPlaylists, kugouPlaylists, qishuiPlaylists, bodianPlaylists, spotifyPlaylists);
    playlistCatalogRevision += 1;
  }
  return true;
}

// ============================================================
//  内置歌单 API 选择器
//  ── 桌面（Electron）：window.desktopWindow（Node 侧持久化）
//  ── 安卓（Capacitor）：window.__mrBuiltInPlaylists（android-shim/45-builtin-playlists.js，
//     localStorage 本机持久化）
//  安卓壳【故意】不设 window.desktopWindow（它会触发端游渲染分支），
//  所以这里不能用「有没有 desktopWindow」当唯一判据，必须回落到本机实现。
//  业务层所有内置歌单操作都只通过本函数拿 API，不再直接写 window.desktopWindow.xxx。
// ============================================================
var BUILT_IN_PLAYLIST_API_METHODS = [
  'listBuiltInPlaylists',
  'createBuiltInPlaylist',
  'readBuiltInPlaylist',
  'addBuiltInPlaylistTrack',
  'removeBuiltInPlaylistTrack',
  'renameBuiltInPlaylist',
  'deleteBuiltInPlaylist'
];

function builtInPlaylistApiHasMethods(api) {
  if (!api) return false;
  for (var i = 0; i < BUILT_IN_PLAYLIST_API_METHODS.length; i += 1) {
    if (typeof api[BUILT_IN_PLAYLIST_API_METHODS[i]] !== 'function') return false;
  }
  return true;
}

function builtInPlaylistApi() {
  var desktop = window.desktopWindow;
  if (builtInPlaylistApiHasMethods(desktop)) return desktop;
  var local = window.__mrBuiltInPlaylists;
  if (!builtInPlaylistApiHasMethods(local) && typeof window.__mrBuiltInPlaylistBridge === 'function') {
    try { local = window.__mrBuiltInPlaylistBridge(); } catch (e) { local = null; }
  }
  if (builtInPlaylistApiHasMethods(local)) return local;
  return null;
}

function builtInPlaylistApiAvailable() {
  return !!builtInPlaylistApi();
}

// 当前是否走桌面（Electron）那套原生接口 —— 决定弹窗用原生 prompt/confirm 还是自绘对话框。
function builtInPlaylistUsesDesktopApi() {
  var desktop = window.desktopWindow;
  return builtInPlaylistApiHasMethods(desktop) && builtInPlaylistApi() === desktop;
}

function builtInPlaylistErrorMessage(result, fallback) {
  var code = String(result && result.error || '');
  if (code === 'BUILT_IN_PLAYLIST_LIMIT_REACHED') return '内置歌单数量已达到上限';
  if (code === 'BUILT_IN_PLAYLIST_TRACK_LIMIT_REACHED') return '这个内置歌单已经装满了';
  if (code === 'BUILT_IN_PLAYLIST_INDEX_TOO_LARGE') return '内置歌单数据已达到存储上限';
  if (code === 'BUILT_IN_PLAYLIST_TRACK_INVALID') return '这首歌缺少可保存的音源标识';
  if (code === 'BUILT_IN_PLAYLIST_NOT_FOUND') return '内置歌单已不存在';
  if (code === 'BUILT_IN_PLAYLIST_NAME_REQUIRED') return '先输入内置歌单名称';
  if (code === 'BUILT_IN_PLAYLIST_UNAVAILABLE') return '当前环境无法保存内置歌单';
  return fallback || '内置歌单操作失败';
}

function refreshBuiltInPlaylists(force) {
  var api = builtInPlaylistApi();
  if (!api) return Promise.resolve(false);
  if (builtInPlaylistLoadPromise && !force) return builtInPlaylistLoadPromise;
  var request = Promise.resolve().then(function () {
    return api.listBuiltInPlaylists();
  }).then(function (result) {
    if (!result || result.ok !== true) throw new Error(result && result.error || 'BUILT_IN_PLAYLIST_READ_FAILED');
    applyBuiltInPlaylistSnapshot(result, { preserveScroll: true, reason: 'built-in-playlists-refresh' });
    return true;
  }).catch(function (error) {
    console.warn('[BuiltInPlaylists]', error);
    return false;
  }).then(function (value) {
    if (builtInPlaylistLoadPromise === request) builtInPlaylistLoadPromise = null;
    return value;
  });
  builtInPlaylistLoadPromise = request;
  return request;
}

async function builtInPlaylistTracksPage(id, options) {
  var api = builtInPlaylistApi();
  if (!api) {
    return { ok: false, playlist: null, tracks: [], total: 0, hasMore: false, nextOffset: 0, error: 'BUILT_IN_PLAYLIST_UNAVAILABLE' };
  }
  return api.readBuiltInPlaylist(String(id || ''), options || {});
}

async function createBuiltInPlaylist(name, initialTrack) {
  name = String(name || '').trim();
  if (!name) {
    if (typeof showToast === 'function') showToast('先输入内置歌单名称');
    return null;
  }
  var api = builtInPlaylistApi();
  if (!api) {
    if (typeof showToast === 'function') showToast('当前环境无法保存内置歌单');
    return null;
  }
  var result = await api.createBuiltInPlaylist(name);
  if (!result || result.ok !== true || !result.playlist) {
    if (typeof showToast === 'function') showToast(builtInPlaylistErrorMessage(result, '创建内置歌单失败'));
    return null;
  }
  applyBuiltInPlaylistSnapshot(result, { animate: true, reason: 'built-in-playlist-create' });
  if (initialTrack) {
    var added = await addTrackToBuiltInPlaylist(result.playlist.id, initialTrack, { silentSuccess: true });
    if (!added) return result.playlist;
  }
  if (typeof showToast === 'function') showToast('内置歌单已创建');
  return result.playlist;
}

function promptCreateBuiltInPlaylist() {
  openBuiltInPlaylistNameDialog('新建 Mineradio 内置歌单', '我的歌单', function (name) {
    if (name == null) return;
    var trimmed = String(name).trim();
    if (!trimmed) {
      if (typeof showToast === 'function') showToast('先输入内置歌单名称');
      return;
    }
    Promise.resolve().then(function () {
      return createBuiltInPlaylist(trimmed);
    }).catch(function (error) {
      console.warn('[BuiltInPlaylistCreate]', error);
      if (typeof showToast === 'function') showToast('创建内置歌单失败');
    });
  });
}

async function addTrackToBuiltInPlaylist(id, track, opts) {
  opts = opts || {};
  var api = builtInPlaylistApi();
  if (!api) return false;
  var result = await api.addBuiltInPlaylistTrack(String(id || ''), track || {});
  if (!result || result.ok !== true) {
    if (typeof showToast === 'function') showToast(builtInPlaylistErrorMessage(result, '加入内置歌单失败'));
    return false;
  }
  applyBuiltInPlaylistSnapshot(result, { preserveScroll: true, reason: 'built-in-playlist-add-track' });
  if (typeof showToast === 'function' && !opts.silentSuccess) showToast(result.duplicate ? '歌曲已在这个内置歌单中' : '已加入内置歌单');
  return result.duplicate ? 'duplicate' : true;
}

async function removeTrackFromBuiltInPlaylist(id, index) {
  var api = builtInPlaylistApi();
  if (!api) return false;
  var result = await api.removeBuiltInPlaylistTrack(String(id || ''), Number(index));
  if (!result || result.ok !== true) {
    if (typeof showToast === 'function') showToast(builtInPlaylistErrorMessage(result, '移除歌曲失败'));
    return false;
  }
  if (playlistPanelDetailState && playlistPanelDetailState.key === 'mineradio:' + String(id || '')) {
    playlistPanelDetailState.tracks.splice(Number(index), 1);
    playlistPanelDetailState.total = Math.max(0, playlistPanelDetailState.tracks.length);
    playlistPanelDetailState.nextOffset = playlistPanelDetailState.tracks.length;
    playlistPanelDetailState.hasMore = false;
  }
  applyBuiltInPlaylistSnapshot(result, { preserveScroll: true, reason: 'built-in-playlist-remove-track' });
  if (typeof showToast === 'function') showToast('已从内置歌单移除');
  return true;
}

async function renameBuiltInPlaylist(id, currentName) {
  var api = builtInPlaylistApi();
  if (!api) return false;
  // 返回 Promise<boolean>：弹窗确认 + 重命名完成后 resolve（与原 desktopWindow
  // 版本的「await 后拿到 true/false」语义一致，调用方无需改动）。
  return new Promise(function (resolve) {
    openBuiltInPlaylistNameDialog('重命名内置歌单', String(currentName || ''), function (name) {
      if (name == null || !String(name).trim()) { resolve(false); return; }
      var trimmed = String(name).trim();
      Promise.resolve().then(function () {
        return api.renameBuiltInPlaylist(String(id || ''), trimmed);
      }).then(function (result) {
        if (!result || result.ok !== true) {
          if (typeof showToast === 'function') showToast(builtInPlaylistErrorMessage(result, '重命名失败'));
          resolve(false);
          return;
        }
        if (playlistPanelDetailState && playlistPanelDetailState.key === 'mineradio:' + String(id || '') && playlistPanelDetailState.playlist) {
          playlistPanelDetailState.playlist.name = trimmed;
        }
        applyBuiltInPlaylistSnapshot(result, { preserveScroll: true, reason: 'built-in-playlist-rename' });
        if (typeof showToast === 'function') showToast('内置歌单已重命名');
        resolve(true);
      }).catch(function (error) {
        console.warn('[BuiltInPlaylistRename]', error);
        if (typeof showToast === 'function') showToast('重命名失败');
        resolve(false);
      });
    });
  });
}

async function deleteBuiltInPlaylist(id, currentName) {
  var api = builtInPlaylistApi();
  if (!api || !String(id || '')) return false;
  return new Promise(function (resolve) {
    openBuiltInPlaylistConfirm('删除内置歌单“' + String(currentName || '未命名歌单') + '”？\n只删除歌单，不会删除平台或本地歌曲。', function (confirmed) {
      if (!confirmed) { resolve(false); return; }
      Promise.resolve().then(function () {
        return api.deleteBuiltInPlaylist(String(id || ''));
      }).then(function (result) {
        if (!result || result.ok !== true) {
          if (typeof showToast === 'function') showToast(builtInPlaylistErrorMessage(result, '删除内置歌单失败'));
          resolve(false);
          return;
        }
        if (playlistPanelDetailState && playlistPanelDetailState.key === 'mineradio:' + String(id || '')) {
          cancelPlaylistPanelDetailRequest();
          playlistPanelDetailState.key = '';
          playlistPanelDetailState.tracks = [];
          playlistPanelDetailState.playlist = null;
        }
        applyBuiltInPlaylistSnapshot(result, { preserveScroll: true, reason: 'built-in-playlist-delete' });
        if (typeof showToast === 'function') showToast('内置歌单已删除');
        resolve(true);
      }).catch(function (error) {
        console.warn('[BuiltInPlaylistDelete]', error);
        if (typeof showToast === 'function') showToast('删除内置歌单失败');
        resolve(false);
      });
    });
  });
}

// ============================================================
//  输入 / 确认弹窗兜底（安卓 WebView 里 window.prompt 常被禁用）
//  ── 桌面：照旧用原生 prompt / confirm
//  ── 安卓：先用 window.__mrConfirm（若别处提供），否则用本文件自绘的极简遮罩层
//  遮罩层全部用内联样式、append 到 body，不依赖 index.html 增加 DOM，也不受
//  .modal-mask / body.immersive-mode 的隐藏规则影响。
// ============================================================
var builtInPlaylistDialogCleanup = null;

function builtInPlaylistDialogMask() {
  var existing = document.getElementById('mr-builtin-playlist-dialog');
  if (existing) return existing;
  if (!document.body) return null;
  var mask = document.createElement('div');
  mask.id = 'mr-builtin-playlist-dialog';
  mask.setAttribute('role', 'dialog');
  mask.setAttribute('aria-modal', 'true');
  var style = mask.style;
  style.position = 'fixed';
  style.left = '0';
  style.top = '0';
  style.right = '0';
  style.bottom = '0';
  style.zIndex = '100200';  // 压过登录引导层(100000)与登录回忆成就(100010)
  style.display = 'none';
  style.alignItems = 'center';
  style.justifyContent = 'center';
  style.background = 'rgba(0, 0, 0, 0.66)';
  style.backdropFilter = 'blur(6px)';
  style.webkitBackdropFilter = 'blur(6px)';
  style.padding = '18px';
  style.boxSizing = 'border-box';
  mask.innerHTML = [
    '<div style="width:min(420px,92vw);background:linear-gradient(180deg,rgba(26,25,29,.98),rgba(14,13,15,.96));',
    'border:1px solid rgba(255,255,255,.12);border-radius:16px;padding:18px 18px 14px;',
    'box-shadow:0 24px 70px rgba(0,0,0,.55);font-family:inherit;color:#fff">',
    '<div data-mr-dialog-title style="font-size:14.5px;font-weight:600;letter-spacing:.4px;color:#f4d28a;margin-bottom:10px"></div>',
    '<div data-mr-dialog-message style="font-size:12.5px;line-height:1.6;color:rgba(255,255,255,.78);white-space:pre-wrap;margin-bottom:12px;display:none"></div>',
    '<input data-mr-dialog-input type="text" autocomplete="off" maxlength="40"',
    ' style="width:100%;height:38px;box-sizing:border-box;padding:0 12px;border-radius:10px;',
    'border:1px solid rgba(255,255,255,.18);background:rgba(255,255,255,.06);color:#fff;',
    'font-size:13px;font-family:inherit;outline:none;margin-bottom:14px;display:none">',
    '<div style="display:flex;gap:10px;justify-content:flex-end">',
    '<button type="button" data-mr-dialog-cancel',
    ' style="padding:8px 18px;font-size:12.5px;border-radius:9px;border:1px solid rgba(255,255,255,.15);',
    'background:rgba(255,255,255,.05);color:#fff;font-family:inherit">取消</button>',
    '<button type="button" data-mr-dialog-confirm',
    ' style="padding:8px 18px;font-size:12.5px;border-radius:9px;border:1px solid rgba(244,210,138,.5);',
    'background:rgba(244,210,138,.16);color:#f4d28a;font-family:inherit">确定</button>',
    '</div>',
    '</div>'
  ].join('');
  document.body.appendChild(mask);
  return mask;
}

function closeBuiltInPlaylistDialog() {
  var cleanup = builtInPlaylistDialogCleanup;
  builtInPlaylistDialogCleanup = null;
  if (typeof cleanup === 'function') {
    try { cleanup(); } catch (e) { }
  }
  var mask = document.getElementById('mr-builtin-playlist-dialog');
  if (!mask) return;
  mask.style.display = 'none';
  var input = mask.querySelector('[data-mr-dialog-input]');
  if (input) input.value = '';
}

/**
 * 极简对话框（自洽：DOM 不存在就动态建，挂在 body 上）。
 * options: { title, message, input, defaultValue, confirmText, cancelText, onDone }
 * onDone 收到：输入模式 → 字符串或 null；确认模式 → true / false。
 */
function openBuiltInPlaylistDialog(options) {
  options = options || {};
  if (builtInPlaylistDialogCleanup) closeBuiltInPlaylistDialog();
  var mask = builtInPlaylistDialogMask();
  if (!mask) {
    var fallback = options.input === true ? window.prompt(String(options.title || ''), String(options.defaultValue || '')) : window.confirm(String(options.message || options.title || ''));
    if (typeof options.onDone === 'function') options.onDone(options.input === true ? (fallback == null ? null : String(fallback)) : !!fallback);
    return;
  }
  var titleEl = mask.querySelector('[data-mr-dialog-title]');
  var messageEl = mask.querySelector('[data-mr-dialog-message]');
  var inputEl = mask.querySelector('[data-mr-dialog-input]');
  var confirmBtn = mask.querySelector('[data-mr-dialog-confirm]');
  var cancelBtn = mask.querySelector('[data-mr-dialog-cancel]');
  if (!titleEl || !messageEl || !inputEl || !confirmBtn || !cancelBtn) {
    if (typeof options.onDone === 'function') options.onDone(options.input === true ? null : false);
    return;
  }
  var wantsInput = options.input === true;
  titleEl.textContent = String(options.title || '');
  titleEl.style.display = titleEl.textContent ? '' : 'none';
  messageEl.textContent = String(options.message || '');
  messageEl.style.display = messageEl.textContent ? 'block' : 'none';
  inputEl.style.display = wantsInput ? 'block' : 'none';
  inputEl.value = wantsInput ? String(options.defaultValue == null ? '' : options.defaultValue) : '';
  confirmBtn.textContent = String(options.confirmText || '确定');
  cancelBtn.textContent = String(options.cancelText || '取消');
  mask.style.display = 'flex';

  var settled = false;
  var done = function (value) {
    if (settled) return;
    settled = true;
    closeBuiltInPlaylistDialog();
    if (typeof options.onDone === 'function') {
      try { options.onDone(value); } catch (e) { console.warn('[BuiltInPlaylistDialog]', e); }
    }
  };
  var accept = function () {
    if (!wantsInput) { done(true); return; }
    var value = String(inputEl.value || '').trim();
    if (!value) {
      if (typeof showToast === 'function') showToast('先输入内置歌单名称');
      try { inputEl.focus(); } catch (e) { }
      return;
    }
    done(value);
  };
  var rejectAll = function () { done(wantsInput ? null : false); };

  var onConfirm = function (event) { if (event) { event.preventDefault(); event.stopPropagation(); } accept(); };
  var onCancel = function (event) { if (event) { event.preventDefault(); event.stopPropagation(); } rejectAll(); };
  var onMaskClick = function (event) { if (event && event.target === mask) rejectAll(); };
  var onKeyDown = function (event) {
    if (!event) return;
    if (event.key === 'Escape' || event.keyCode === 27) { rejectAll(); return; }
    if (wantsInput && (event.key === 'Enter' || event.keyCode === 13)) accept();
  };
  confirmBtn.addEventListener('click', onConfirm);
  cancelBtn.addEventListener('click', onCancel);
  mask.addEventListener('click', onMaskClick);
  document.addEventListener('keydown', onKeyDown);
  builtInPlaylistDialogCleanup = function () {
    confirmBtn.removeEventListener('click', onConfirm);
    cancelBtn.removeEventListener('click', onCancel);
    mask.removeEventListener('click', onMaskClick);
    document.removeEventListener('keydown', onKeyDown);
  };
  if (wantsInput) {
    setTimeout(function () {
      try {
        inputEl.focus();
        var len = inputEl.value.length;
        if (inputEl.setSelectionRange) inputEl.setSelectionRange(len, len);
      } catch (e) { }
    }, 60);
  } else {
    setTimeout(function () { try { confirmBtn.focus(); } catch (e) { } }, 60);
  }
}

/** 统一的「输入名称」入口：桌面走原生 prompt，安卓走自绘对话框。 */
function openBuiltInPlaylistNameDialog(title, defaultValue, callback) {
  var onResult = typeof callback === 'function' ? callback : function () { };
  if (builtInPlaylistUsesDesktopApi() && typeof window.prompt === 'function') {
    var answer = window.prompt(String(title || '输入名称'), String(defaultValue == null ? '' : defaultValue));
    onResult(answer == null ? null : String(answer).trim());
    return;
  }
  openBuiltInPlaylistDialog({
    title: String(title || '输入名称'),
    input: true,
    defaultValue: String(defaultValue == null ? '' : defaultValue),
    confirmText: '确定',
    cancelText: '取消',
    onDone: function (value) { onResult(value == null ? null : String(value).trim()); }
  });
}

/** 统一的「确认」入口：桌面走原生 confirm；安卓优先 __mrConfirm，否则自绘。 */
function openBuiltInPlaylistConfirm(message, callback) {
  var onResult = typeof callback === 'function' ? callback : function () { };
  if (builtInPlaylistUsesDesktopApi() && typeof window.confirm === 'function') {
    onResult(!!window.confirm(String(message || '')));
    return;
  }
  if (typeof window.__mrConfirm === 'function') {
    try {
      var handled = window.__mrConfirm(String(message || ''), function (confirmed) { onResult(!!confirmed); });
      if (handled !== false) return;
    } catch (e) {
      console.warn('[BuiltInPlaylistConfirm]', e);
    }
  }
  openBuiltInPlaylistDialog({
    title: '请确认',
    message: String(message || ''),
    confirmText: '确认',
    cancelText: '取消',
    onDone: function (confirmed) { onResult(!!confirmed); }
  });
}
