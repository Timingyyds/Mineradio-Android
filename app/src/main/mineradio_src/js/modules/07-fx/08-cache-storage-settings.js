function mineradioCacheStorageNode(id) {
  return document.getElementById(id);
}

function formatMineradioCacheBytes(value) {
  var bytes = Math.max(0, Number(value) || 0);
  if (bytes < 1024) return bytes + ' B';
  var units = ['KB', 'MB', 'GB', 'TB'];
  var index = -1;
  do {
    bytes /= 1024;
    index += 1;
  } while (bytes >= 1024 && index < units.length - 1);
  return (bytes >= 100 || index === 0 ? bytes.toFixed(0) : bytes.toFixed(1)) + ' ' + units[index];
}

function setMineradioCacheStorageText(id, value) {
  var node = mineradioCacheStorageNode(id);
  if (node) node.textContent = value == null || value === '' ? '—' : String(value);
}

function applyMineradioCacheSettings(snapshot) {
  if (!snapshot || !snapshot.ok) {
    setMineradioCacheStorageText('cache-storage-total', '读取失败');
    setMineradioCacheStorageText('cache-storage-note', snapshot && snapshot.error ? ('缓存设置不可用：' + snapshot.error) : '缓存设置不可用');
    return;
  }
  var settings = snapshot.settings || {};
  var usage = snapshot.usage || {};
  setMineradioCacheStorageText('cache-storage-root', settings.rootPath);
  setMineradioCacheStorageText('cache-storage-total', '已占用 ' + formatMineradioCacheBytes(usage.totalManagedBytes));
  setMineradioCacheStorageText('cache-storage-lyrics-path', settings.lyricsPath);
  setMineradioCacheStorageText('cache-storage-lyrics-size', formatMineradioCacheBytes(usage.lyricsBytes));
  setMineradioCacheStorageText('cache-storage-chromium-path', settings.activeChromiumPath || settings.chromiumPath);
  setMineradioCacheStorageText('cache-storage-chromium-size', formatMineradioCacheBytes(usage.chromiumBytes));
  setMineradioCacheStorageText('cache-storage-beatmaps-path', settings.activeBeatmapsPath || settings.beatmapsPath);
  setMineradioCacheStorageText('cache-storage-beatmaps-size', formatMineradioCacheBytes(usage.beatmapsBytes));
  setMineradioCacheStorageText('cache-storage-wallpaper-path', settings.activeWallpaperEnginePath || settings.wallpaperEnginePath);
  setMineradioCacheStorageText('cache-storage-wallpaper-size', formatMineradioCacheBytes(usage.wallpaperEngineBytes));
  setMineradioCacheStorageText('cache-storage-userdata-path', settings.userDataPath || '系统安全数据目录');
  setMineradioCacheStorageText('cache-storage-userdata-size', formatMineradioCacheBytes(usage.userDataBytes));
  var restartButton = mineradioCacheStorageNode('cache-storage-restart');
  if (restartButton) restartButton.hidden = !settings.restartRequired;
  setMineradioCacheStorageText(
    'cache-storage-note',
    settings.restartRequired
      ? '歌词缓存已切换；封面、网络、音频分片、节奏分析与 WE 静音场景将在重启后改用新目录。'
      : '歌词缓存立即生效；封面、网络、音频分片、节奏分析与 WE 静音场景已使用此目录。'
  );
}

function refreshMineradioCacheSettings() {
  // ★ Android 手机版：无桌面缓存目录桥接，走原生 KeepApp 统计可清理缓存并展示
  if (!window.desktopWindow) {
    return refreshMineradioAndroidCache();
  }
  if (typeof window.desktopWindow.getCacheSettings !== 'function') {
    applyMineradioCacheSettings({ ok: false, error: '仅桌面版支持本地缓存路径设置' });
    return Promise.resolve();
  }
  setMineradioCacheStorageText('cache-storage-total', '正在统计...');
  return window.desktopWindow.getCacheSettings().then(applyMineradioCacheSettings).catch(function (error) {
    applyMineradioCacheSettings({ ok: false, error: error && error.message || '读取失败' });
  });
}

// ★ 手机版缓存适配：显示可清理缓存占用，隐藏桌面专属的"更改目录"，改用"清理缓存"
function applyMineradioAndroidCache(info) {
  var isAndroid = !window.desktopWindow;
  var chooseBtn = document.querySelector('.cache-storage-actions button[onclick*="chooseMineradioCacheRoot"]');
  var clearBtn = document.getElementById('cache-storage-clear');
  if (chooseBtn) chooseBtn.style.display = isAndroid ? 'none' : '';
  if (clearBtn) clearBtn.style.display = isAndroid ? '' : 'none';
  if (!info || !info.ok) {
    setMineradioCacheStorageText('cache-storage-total', '读取失败');
    setMineradioCacheStorageText('cache-storage-note', (info && info.error) ? ('缓存读取失败：' + info.error) : '缓存读取失败');
    return;
  }
  setMineradioCacheStorageText('cache-storage-root', '应用本地目录');
  setMineradioCacheStorageText('cache-storage-total', '已占用 ' + formatMineradioCacheBytes(info.totalBytes));
  // ★ 展示真实缓存：歌词/译文、节奏分析、已下载歌曲（数据存于 WebView 本地存储与网络缓存）
  setMineradioCacheStorageText('cache-storage-lyrics-path', '歌词/译文数据（WebView 本地存储）');
  setMineradioCacheStorageText('cache-storage-lyrics-size', formatMineradioCacheBytes(info.lyricsBytes || 0));
  setMineradioCacheStorageText('cache-storage-chromium-path', '网页/音频网络缓存（已并入歌曲缓存）');
  setMineradioCacheStorageText('cache-storage-chromium-size', formatMineradioCacheBytes(info.webviewBytes || 0));
  setMineradioCacheStorageText('cache-storage-beatmaps-path', '节奏分析数据（WebView 本地存储）');
  setMineradioCacheStorageText('cache-storage-beatmaps-size', formatMineradioCacheBytes(info.beatmapsBytes || 0));
  setMineradioCacheStorageText('cache-storage-songs-path', '播放歌曲缓存（含网络音频）');
  setMineradioCacheStorageText('cache-storage-songs-size', formatMineradioCacheBytes(info.songsBytes || 0));
  setMineradioCacheStorageText('cache-storage-wallpaper-path', 'MPKG 场景解析 + 封面缓存');
  setMineradioCacheStorageText('cache-storage-wallpaper-size', formatMineradioCacheBytes((info.mpkgBytes || 0) + (info.coversBytes || 0)));
  setMineradioCacheStorageText('cache-storage-userdata-path', '安全固定（登录资料）');
  setMineradioCacheStorageText('cache-storage-userdata-size', '保留');
  var restartButton = mineradioCacheStorageNode('cache-storage-restart');
  if (restartButton) restartButton.hidden = true;
  setMineradioCacheStorageText('cache-storage-note', '歌词/译文、节奏分析与播放歌曲缓存均记录在应用内，播放歌曲后占用会增长；清理缓存可释放空间，保留登录资料与已导入壁纸。');
}

function refreshMineradioAndroidCache() {
  if (!window.KeepApp || typeof window.KeepApp.getAppCacheInfo !== 'function') {
    applyMineradioAndroidCache({ ok: false, error: '当前环境不支持缓存统计' });
    return Promise.resolve();
  }
  setMineradioCacheStorageText('cache-storage-total', '正在统计...');
  try {
    var info = JSON.parse(window.KeepApp.getAppCacheInfo() || '{}');
    applyMineradioAndroidCache(info);
  } catch (e) {
    applyMineradioAndroidCache({ ok: false, error: '统计失败' });
  }
  return Promise.resolve();
}

// ★ 手机版：一键清理应用缓存（原生层只清 WebView 网络缓存与封面缓存）
function clearMineradioCache() {
  if (!window.KeepApp || typeof window.KeepApp.clearAppCache !== 'function') {
    showToast('当前环境不支持清理缓存');
    return;
  }
  setMineradioCacheStorageText('cache-storage-total', '正在清理...');
  try {
    var result = JSON.parse(window.KeepApp.clearAppCache() || '{}');
    if (result && result.ok) {
      showToast('已清理缓存 ' + formatMineradioCacheBytes(result.freedBytes || 0));
    } else {
      showToast('缓存清理失败：' + (result && result.error || '未知错误'));
    }
  } catch (e) {
    showToast('缓存清理失败');
  }
  refreshMineradioAndroidCache();
}

function chooseMineradioCacheRoot() {
  if (!window.desktopWindow || typeof window.desktopWindow.chooseCacheDirectory !== 'function') return;
  window.desktopWindow.chooseCacheDirectory().then(function (choice) {
    if (!choice || !choice.ok || choice.canceled || !choice.rootPath) return;
    return window.desktopWindow.setCacheSettings({ rootPath: choice.rootPath });
  }).then(function (snapshot) {
    if (snapshot) applyMineradioCacheSettings(snapshot);
  }).catch(function (error) {
    applyMineradioCacheSettings({ ok: false, error: error && error.message || '保存失败' });
  });
}

function restartMineradioForCachePath() {
  if (!window.desktopWindow || typeof window.desktopWindow.restartApp !== 'function') return;
  window.desktopWindow.restartApp();
}

setTimeout(refreshMineradioCacheSettings, 450);
