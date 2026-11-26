function mineradioCacheStorageNode(id) {
  return document.getElementById(id);
}

/** 桌面版用 desktopWindow；安卓回退到 43-android-system-tools.js 提供的桥。 */
function mineradioCacheApi() {
  return window.desktopWindow || window.__androidSystemApi || null;
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
  var api = mineradioCacheApi();
  if (!api || typeof api.getCacheSettings !== 'function') {
    applyMineradioCacheSettings({ ok: false, error: '当前环境不支持读取缓存信息' });
    return Promise.resolve();
  }
  setMineradioCacheStorageText('cache-storage-total', '正在统计...');
  return api.getCacheSettings().then(applyMineradioCacheSettings).catch(function (error) {
    applyMineradioCacheSettings({ ok: false, error: error && error.message || '读取失败' });
  });
}

/** 安卓/桌面通用：清空缓存目录（不动用户数据、导入壁纸与插件）。 */
function clearMineradioCache() {
  var api = mineradioCacheApi();
  if (!api || typeof api.clearCache !== 'function') {
    if (typeof showToast === 'function') showToast('当前环境不支持清理缓存');
    return;
  }
  setMineradioCacheStorageText('cache-storage-total', '正在清理...');
  api.clearCache().then(function (result) {
    if (result && result.ok) {
      if (typeof showToast === 'function') showToast('缓存已清理：' + formatMineradioCacheBytes(result.freedBytes));
    } else if (typeof showToast === 'function') {
      showToast('清理失败：' + ((result && result.error) || '未知错误'));
    }
    refreshMineradioCacheSettings();
  }).catch(function (error) {
    if (typeof showToast === 'function') showToast('清理失败：' + (error && error.message || error));
    refreshMineradioCacheSettings();
  });
}

function chooseMineradioCacheRoot() {
  var api = mineradioCacheApi();
  if (!api || typeof api.chooseCacheDirectory !== 'function') return;
  api.chooseCacheDirectory().then(function (choice) {
    if (!choice || !choice.ok || choice.canceled || !choice.rootPath) return;
    return api.setCacheSettings({ rootPath: choice.rootPath });
  }).then(function (snapshot) {
    if (snapshot) applyMineradioCacheSettings(snapshot);
  }).catch(function (error) {
    applyMineradioCacheSettings({ ok: false, error: error && error.message || '保存失败' });
  });
}

function restartMineradioForCachePath() {
  var api = mineradioCacheApi();
  if (!api || typeof api.restartApp !== 'function') return;
  api.restartApp();
}

setTimeout(refreshMineradioCacheSettings, 450);
