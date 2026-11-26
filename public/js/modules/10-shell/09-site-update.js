// 应用内「检查更新」+ 官网跳转
//
// 方案照搬《应用内更新提示-通用方案.md》：
//   站点放一个 version.json（本站是 http://buchumingdetiming.top，所以用名字区分的
//   mineradio-version.json，避免和 folia 的版本文件互相覆盖），App 启动后等一会儿
//   再读它，比本机版本新就弹窗；「跳过」记「版本 + 日期」，当天不再提、跨天再提。
//
// 关键点：
//   · 站点是 http，页面在 https://localhost 里直接 fetch 会被混合内容拦掉，
//     所以优先走原生 httpRequest（OkHttp），没有原生桥才退回 fetch；
//   · 版本号按数字段比较，不能按字符串（2.10.0 > 2.9.0）；
//   · 每一步都写日志 [SiteUpdate]，出问题能查；
//   · 「去下载」也记跳过，否则浏览器没开成回来又被弹。

var SITE_UPDATE_LOG_SCOPE = 'SiteUpdate';
var SITE_UPDATE_ORIGIN = 'http://buchumingdetiming.top';
var SITE_UPDATE_VERSION_URL = SITE_UPDATE_ORIGIN + '/mineradio-version.json';
var SITE_UPDATE_DOWNLOAD_PAGE = SITE_UPDATE_ORIGIN + '/#download';
var SITE_UPDATE_TIMEOUT_MS = 6000;
var SITE_UPDATE_DEFER_MS = 1200;
var SITE_UPDATE_SKIPPED_VERSION_KEY = 'mineradio-site-update-skipped-version';
var SITE_UPDATE_SKIPPED_DAY_KEY = 'mineradio-site-update-skipped-day';
// 提醒频率：always = 每次冷启动都提醒（默认，符合「有新版就该看到」）；
//          daily  = 同一天只提醒一次（跳过记版本+日期）。
var SITE_UPDATE_MODE_KEY = 'mineradio-site-update-mode';

var siteUpdateState = {
  mode: 'always',
  forced: false,          // 站点 version.json 里 forced:true → 强制更新（只留「去下载」+ 常驻悬浮条）
  dismissedThisSession: false,
  checking: false,
  checked: false,
  appVersion: '',
  remote: null,
  pending: null
};

function siteUpdateRenderVersionLine(text) {
  var el = document.getElementById('app-update-version-line');
  if (el && text != null) el.textContent = String(text);
}

(function siteUpdateLoadMode() {
  try {
    var saved = window.localStorage.getItem(SITE_UPDATE_MODE_KEY);
    siteUpdateState.mode = saved === 'daily' ? 'daily' : 'always';
  } catch (_error) {
    siteUpdateState.mode = 'always';
  }
})();

function siteUpdateRenderModeButton() {
  var btn = document.getElementById('app-update-mode-btn');
  if (!btn) return;
  btn.textContent = siteUpdateState.mode === 'daily' ? '提醒频率 · 每天一次' : '提醒频率 · 每次启动';
  btn.classList.toggle('active', siteUpdateState.mode === 'always');
}

function toggleSiteUpdateMode() {
  siteUpdateState.mode = siteUpdateState.mode === 'daily' ? 'always' : 'daily';
  try {
    window.localStorage.setItem(SITE_UPDATE_MODE_KEY, siteUpdateState.mode);
  } catch (_error) { /* 忽略 */ }
  siteUpdateRenderModeButton();
  if (typeof showToast === 'function') {
    showToast(siteUpdateState.mode === 'daily' ? '更新提示：每天提醒一次' : '更新提示：每次启动都提醒');
  }
}

setTimeout(siteUpdateRenderModeButton, 1500);

function siteUpdateLog(message) {
  try { console.log('[' + SITE_UPDATE_LOG_SCOPE + '] ' + message); } catch (_error) { /* 忽略 */ }
}

function siteUpdateNative() {
  var cap = window.Capacitor;
  return (cap && cap.Plugins && cap.Plugins.FoliaNative) || null;
}

/** `2.1.0`、`v2.1`、`2.1.0-beta.1` → 可比较的几段；读不出数字返回 null。 */
function siteUpdateParseVersion(raw) {
  var text = String(raw || '').trim().replace(/^v/i, '');
  if (!text) return null;
  var pieces = text.split('-');
  var core = pieces[0];
  var pre = pieces.length > 1 ? pieces.slice(1).join('-') : '';
  var parts = core.split('.');
  if (!parts.length) return null;
  for (var i = 0; i < parts.length; i += 1) {
    if (!/^\d+$/.test(parts[i])) return null;
  }
  return {
    numbers: parts.map(function (part) { return parseInt(part, 10); }),
    pre: pre
  };
}

/** 本机比站点新返回正数，相同 0，比站点旧返回负数；任一方读不出当相等（宁可不提示）。 */
function siteUpdateCompareVersions(a, b) {
  var left = siteUpdateParseVersion(a);
  var right = siteUpdateParseVersion(b);
  if (!left || !right) return 0;
  var length = Math.max(left.numbers.length, right.numbers.length);
  for (var i = 0; i < length; i += 1) {
    var lv = left.numbers[i] == null ? 0 : left.numbers[i];
    var rv = right.numbers[i] == null ? 0 : right.numbers[i];
    if (lv !== rv) return lv > rv ? 1 : -1;
  }
  if (left.pre === right.pre) return 0;
  if (!left.pre) return 1;
  if (!right.pre) return -1;
  return left.pre > right.pre ? 1 : -1;
}

function siteUpdateToday() {
  var now = new Date();
  var month = String(now.getMonth() + 1);
  var day = String(now.getDate());
  if (month.length < 2) month = '0' + month;
  if (day.length < 2) day = '0' + day;
  return now.getFullYear() + '-' + month + '-' + day;
}

function siteUpdateReadSkip() {
  var version = '';
  var day = '';
  try {
    version = window.localStorage.getItem(SITE_UPDATE_SKIPPED_VERSION_KEY) || '';
    day = window.localStorage.getItem(SITE_UPDATE_SKIPPED_DAY_KEY) || '';
  } catch (_error) { /* 隐私模式读不到就按没跳过处理 */ }
  return { version: version, day: day };
}

function siteUpdateWriteSkip(version) {
  try {
    window.localStorage.setItem(SITE_UPDATE_SKIPPED_VERSION_KEY, String(version || ''));
    window.localStorage.setItem(SITE_UPDATE_SKIPPED_DAY_KEY, siteUpdateToday());
  } catch (_error) { /* 忽略 */ }
}

function siteUpdateShouldPrompt(remoteVersion) {
  if (siteUpdateState.mode === 'daily') {
    var skip = siteUpdateReadSkip();
    return skip.version !== remoteVersion || skip.day !== siteUpdateToday();
  }
  // always：只要这次启动还没点过「跳过/去下载」，重启一次就再提醒一次
  return !siteUpdateState.dismissedThisSession;
}

/** base64 → UTF-8 文本（原生 httpRequest 返回的是 bodyBase64）。 */
function siteUpdateBase64ToText(base64) {
  if (!base64) return '';
  try {
    var binary = window.atob(base64);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
    if (window.TextDecoder) return new TextDecoder('utf-8').decode(bytes);
    return decodeURIComponent(escape(binary));
  } catch (_error) {
    return '';
  }
}

/** 取一段文本：优先原生 HTTP（不受混合内容/CORS 限制），失败再退回 fetch。 */
function siteUpdateRequestText(url) {
  var startedAt = Date.now();
  var native = siteUpdateNative();
  if (native && typeof native.httpRequest === 'function') {
    siteUpdateLog('请求 ' + url + '（原生桥）');
    return native.httpRequest({ url: url, method: 'GET', timeoutMs: SITE_UPDATE_TIMEOUT_MS })
      .then(function (answer) {
        var cost = Date.now() - startedAt;
        var status = Number(answer && answer.status) || 0;
        if (status < 200 || status >= 300) {
          siteUpdateLog('原生桥返回 HTTP ' + status + '，用时 ' + cost + 'ms');
          return null;
        }
        var text = siteUpdateBase64ToText(answer && answer.bodyBase64);
        siteUpdateLog('返回 HTTP ' + status + '，' + text.length + ' 字节，用时 ' + cost + 'ms');
        return text;
      })
      .catch(function (error) {
        siteUpdateLog('请求失败，用时 ' + (Date.now() - startedAt) + 'ms：' + (error && error.message || error));
        return null;
      });
  }
  siteUpdateLog('请求 ' + url + '（浏览器 fetch）');
  var controller = typeof AbortController === 'function' ? new AbortController() : null;
  var timer = controller ? setTimeout(function () { controller.abort(); }, SITE_UPDATE_TIMEOUT_MS) : 0;
  return fetch(url, { cache: 'no-store', signal: controller ? controller.signal : undefined })
    .then(function (response) {
      if (timer) clearTimeout(timer);
      if (!response.ok) {
        siteUpdateLog('fetch 返回 HTTP ' + response.status + '，用时 ' + (Date.now() - startedAt) + 'ms');
        return null;
      }
      return response.text();
    })
    .catch(function (error) {
      if (timer) clearTimeout(timer);
      siteUpdateLog('请求失败，用时 ' + (Date.now() - startedAt) + 'ms：' + (error && error.message || error));
      return null;
    });
}

/** 降级：从下载页 HTML 里抠「MR 音乐播放器 <em>2.1.8</em>」。 */
function siteUpdateParseVersionFromPage(html) {
  var match = String(html || '').match(/MR\s*音乐播放器\s*<em>\s*v?([\d.]+)\s*<\/em>/i)
    || String(html || '').match(/<h3>\s*[^<]*<em>\s*v?([\d.]+)\s*<\/em>/i);
  return match ? String(match[1]).trim() : null;
}

function siteUpdateReadAppVersion() {
  var native = siteUpdateNative();
  if (native && typeof native.getAppVersion === 'function') {
    return native.getAppVersion().then(function (info) {
      return String((info && info.versionName) || '').trim();
    }).catch(function () { return ''; });
  }
  return Promise.resolve('');
}

function siteUpdateFetchRemote() {
  return siteUpdateRequestText(SITE_UPDATE_VERSION_URL).then(function (manifest) {
    if (manifest) {
      try {
        var data = JSON.parse(manifest);
        if (data && typeof data.version === 'string' && siteUpdateParseVersion(data.version)) {
          siteUpdateLog('version.json 读到版本 ' + data.version);
          // 强制更新开关：forced / force / mandatory 任一为 true 都算强制
          var forced = data.forced === true || data.force === true || data.mandatory === true
            || data.forced === 'true' || data.force === 'true';
          return {
            version: data.version.trim().replace(/^v/i, ''),
            downloadUrl: (typeof data.downloadUrl === 'string' && data.downloadUrl) ? data.downloadUrl : SITE_UPDATE_DOWNLOAD_PAGE,
            notes: typeof data.notes === 'string' ? data.notes : '',
            publishedAt: typeof data.publishedAt === 'string' ? data.publishedAt : '',
            forced: forced
          };
        }
        siteUpdateLog('version.json 没有可用的 version 字段');
      } catch (error) {
        siteUpdateLog('version.json 解析失败：' + (error && error.message || error));
      }
    }
    siteUpdateLog('version.json 读不到，退回解析下载页');
    return siteUpdateRequestText(SITE_UPDATE_ORIGIN + '/').then(function (page) {
      var version = page ? siteUpdateParseVersionFromPage(page) : null;
      if (!page || !version) {
        siteUpdateLog('下载页也读不到版本号，这次不提示');
        return null;
      }
      return { version: version, downloadUrl: SITE_UPDATE_DOWNLOAD_PAGE, notes: '', publishedAt: '', forced: false };
    });
  });
}

/** 跑一次检查。manual=true 时用户主动点，结果用 toast 告诉一声。 */
function runSiteUpdateCheck(manual) {
  if (siteUpdateState.checking) return Promise.resolve(false);
  siteUpdateState.checking = true;
  return siteUpdateReadAppVersion().then(function (appVersion) {
    siteUpdateState.appVersion = appVersion;
    if (!appVersion) {
      siteUpdateLog('读不到本机版本，跳过检查');
      siteUpdateState.checking = false;
      return false;
    }
    siteUpdateRenderVersionLine('当前版本 ' + appVersion);
    siteUpdateLog('开始检查，本机版本 ' + appVersion);
    return siteUpdateFetchRemote().then(function (remote) {
      siteUpdateState.checked = true;
      siteUpdateState.checking = false;
      if (!remote) {
        siteUpdateLog('没读到站点版本，这次不提示');
        if (siteUpdateState.forced) siteUpdateShowForceBar(siteUpdateState.pending);
        if (manual && typeof showToast === 'function') showToast('检查更新失败：读不到站点版本');
        return false;
      }
      siteUpdateState.remote = remote;
      siteUpdateState.forced = !!remote.forced;
      var diff = siteUpdateCompareVersions(appVersion, remote.version);
      siteUpdateLog('本机 ' + appVersion + ' vs 站点 ' + remote.version + '，比较结果 ' + diff);
      siteUpdateRenderVersionLine('当前版本 ' + appVersion + ' · 站点最新 ' + remote.version);
      if (diff >= 0) {
        siteUpdateLog('本机不比站点旧，不提示');
        siteUpdateHideForceBar();
        siteUpdateRenderVersionLine('当前版本 ' + appVersion + ' · 已是最新');
        if (manual && typeof showToast === 'function') showToast('已是最新版本 ' + appVersion);
        return false;
      }
      if (!siteUpdateState.forced && !manual && !siteUpdateShouldPrompt(remote.version)) {
        var skip = siteUpdateReadSkip();
        siteUpdateLog('站点版本 ' + remote.version + ' 本次启动已忽略（模式=' + siteUpdateState.mode +
          '，跳过的是 ' + skip.version + '，跳过于 ' + skip.day + '），不再提示');
        return false;
      }
      siteUpdateLog('提示站点版本 ' + remote.version + (siteUpdateState.forced ? '（强制更新）' : ''));
      siteUpdateState.pending = remote;
      showSiteUpdateDialog(remote);
      if (siteUpdateState.forced) siteUpdateShowForceBar(remote);
      return true;
    });
  }).catch(function (error) {
    siteUpdateState.checking = false;
    siteUpdateLog('检查出错：' + (error && error.message || error));
    return false;
  });
}

function siteUpdateEsc(value) {
  return String(value == null ? '' : value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function ensureSiteUpdateDialog() {
  var mask = document.getElementById('site-update-modal');
  if (mask) return mask;
  mask = document.createElement('div');
  mask.id = 'site-update-modal';
  mask.className = 'site-update-mask';
  mask.innerHTML =
    '<div class="site-update-panel" role="dialog" aria-label="发现新版本">' +
    '<div class="site-update-head"><strong id="site-update-title">发现新版本</strong>' +
    '<button type="button" class="site-update-close" id="site-update-close-btn" onclick="closeSiteUpdateDialog(true)">×</button></div>' +
    '<div class="site-update-versions" id="site-update-versions"></div>' +
    '<div class="site-update-notes" id="site-update-notes"></div>' +
    '<div class="site-update-actions">' +
    '<button type="button" class="site-update-btn primary" id="site-update-download" onclick="siteUpdateGoDownload()">去下载</button>' +
    '<button type="button" class="site-update-btn" onclick="siteUpdateOpenSite()">打开官网</button>' +
    '<button type="button" class="site-update-btn ghost" id="site-update-skip-btn" onclick="closeSiteUpdateDialog(true)">跳过</button>' +
    '</div>' +
    '</div>';
  mask.addEventListener('click', function (event) {
    // 强制更新时点遮罩不关闭；普通更新允许点外面关掉
    if (event.target === mask && !siteUpdateState.forced) closeSiteUpdateDialog(true);
  });
  document.body.appendChild(mask);
  return mask;
}

function showSiteUpdateDialog(remote) {
  remote = remote || siteUpdateState.pending;
  if (!remote) return;
  var mask = ensureSiteUpdateDialog();
  var versions = mask.querySelector('#site-update-versions');
  if (versions) {
    versions.innerHTML = '<span>当前 ' + siteUpdateEsc(siteUpdateState.appVersion || '未知') + '</span>' +
      '<em>→</em><strong>' + siteUpdateEsc(remote.version) + '</strong>';
  }
  var notes = mask.querySelector('#site-update-notes');
  if (notes) {
    notes.textContent = remote.notes || '有新版本可用，建议更新后再使用。';
  }
  var forced = !!remote.forced;
  siteUpdateState.forced = forced;
  var closeBtn = mask.querySelector('#site-update-close-btn');
  var skipBtn = mask.querySelector('#site-update-skip-btn');
  var titleEl = mask.querySelector('#site-update-title');
  if (closeBtn) closeBtn.style.display = forced ? 'none' : '';
  if (skipBtn) skipBtn.style.display = forced ? 'none' : '';
  if (titleEl) titleEl.textContent = forced ? '必须更新后才能继续使用' : '发现新版本';
  mask.classList.toggle('forced', forced);
  mask.classList.add('show');
}

function closeSiteUpdateDialog(markSkipped) {
  var mask = document.getElementById('site-update-modal');
  var remote = siteUpdateState.pending;
  // 强制更新：弹窗关不掉（没有叉/跳过），悬浮条常驻；这里只在用户点「去下载/官网」时收起
  if (siteUpdateState.forced) {
    if (markSkipped) {
      if (mask) mask.classList.remove('show');
      siteUpdateShowForceBar(remote);
    }
    return;
  }
  if (mask) mask.classList.remove('show');
  if (markSkipped && remote && remote.version) {
    siteUpdateState.dismissedThisSession = true;
    siteUpdateWriteSkip(remote.version);
  }
}

/**
 * 强制更新的常驻悬浮条：只要站点要求强制更新且本机版本还没跟上，就一直挂在底部，
 * 点一下重新打开更新弹窗。更新完成（或站点改回非强制且已是最新）后自动消失。
 */
function siteUpdateEnsureForceBar() {
  var bar = document.getElementById('site-update-force-bar');
  if (bar) return bar;
  bar = document.createElement('button');
  bar.id = 'site-update-force-bar';
  bar.type = 'button';
  bar.className = 'site-update-force-bar';
  bar.addEventListener('click', function () { siteUpdateOpenDialogFromBar(); });
  document.body.appendChild(bar);
  return bar;
}

function siteUpdateShowForceBar(remote) {
  remote = remote || siteUpdateState.pending;
  if (!remote) return;
  var bar = siteUpdateEnsureForceBar();
  bar.innerHTML = '<span class="site-update-force-dot" aria-hidden="true"></span>' +
    '<span class="site-update-force-text">必须更新到 ' + siteUpdateEsc(remote.version) + ' · 点此下载</span>';
  bar.hidden = false;
  bar.classList.add('show');
  console.log('[SiteUpdate] 强制更新悬浮条已显示（版本 ' + remote.version + '）');
}

function siteUpdateHideForceBar() {
  var bar = document.getElementById('site-update-force-bar');
  if (bar) {
    bar.classList.remove('show');
    bar.hidden = true;
  }
}

function siteUpdateOpenDialogFromBar() {
  var remote = siteUpdateState.remote || siteUpdateState.pending;
  if (!remote) return;
  siteUpdateState.pending = remote;
  showSiteUpdateDialog(remote);
}

function siteUpdateOpenUrl(url) {
  url = String(url || '');
  if (!url) return;
  var native = siteUpdateNative();
  if (native && typeof native.openExternalUrl === 'function') {
    native.openExternalUrl({ url: url }).catch(function () {
      try { window.open(url, '_blank', 'noopener,noreferrer'); } catch (_error) { /* 忽略 */ }
    });
    return;
  }
  try { window.open(url, '_blank', 'noopener,noreferrer'); } catch (_error) { /* 忽略 */ }
}

function siteUpdateGoDownload() {
  var remote = siteUpdateState.pending;
  var url = (remote && remote.downloadUrl) || SITE_UPDATE_DOWNLOAD_PAGE;
  siteUpdateLog('打开下载地址 ' + url);
  siteUpdateOpenUrl(url);
  // 强制更新：弹窗收起但悬浮条留着，回来还能一键再弹
  if (siteUpdateState.forced) {
    var mask = document.getElementById('site-update-modal');
    if (mask) mask.classList.remove('show');
    siteUpdateShowForceBar(remote);
    return;
  }
  // 普通更新：点了「去下载」也记跳过，否则浏览器没开成回来又被弹一次
  closeSiteUpdateDialog(true);
}

function siteUpdateOpenSite() {
  siteUpdateLog('打开官网 ' + SITE_UPDATE_ORIGIN);
  siteUpdateOpenUrl(SITE_UPDATE_ORIGIN + '/');
}

/** 设置里「检查更新」按钮走这里。 */
function openSiteUpdateCheck() {
  if (typeof showToast === 'function') showToast('正在检查更新…');
  return runSiteUpdateCheck(true);
}

setTimeout(function () {
  runSiteUpdateCheck(false);
}, SITE_UPDATE_DEFER_MS);