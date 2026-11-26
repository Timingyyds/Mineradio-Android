// public/js/modules/00-state/00-runtime-log.js
// 运行日志：把 console 输出、未捕获异常、接口请求都收进环形缓冲，并提供一个可复制的日志面板。
// 作用类似 Folia 的日志页，出问题时用户直接复制发来即可定位。
'use strict';

var MRRuntimeLog = (function () {
  var MAX_ENTRIES = 800;
  var entries = [];
  var seq = 0;
  var originals = {};
  var renderScheduled = false;
  var listeners = [];
  var pendingLines = [];
  var flushTimer = null;
  var LOG_FILE = 'Mineradio-log.txt';

  function pad(n, width) {
    var s = String(n);
    while (s.length < width) s = '0' + s;
    return s;
  }

  function stamp() {
    var d = new Date();
    return pad(d.getHours(), 2) + ':' + pad(d.getMinutes(), 2) + ':' + pad(d.getSeconds(), 2) + '.' + pad(d.getMilliseconds(), 3);
  }

  function stringify(value) {
    if (value === null) return 'null';
    if (value === undefined) return 'undefined';
    if (typeof value === 'string') return value;
    if (typeof value === 'number' || typeof value === 'boolean') return String(value);
    if (value instanceof Error) return value.name + ': ' + value.message + (value.stack ? ('\n' + value.stack) : '');
    try {
      var json = JSON.stringify(value);
      return json && json.length > 2000 ? json.slice(0, 2000) + '…' : json;
    } catch (e) {
      return String(value);
    }
  }

  function push(level, args, tag) {
    var message = Array.prototype.slice.call(args || []).map(stringify).join(' ');
    var entry = {
      id: ++seq,
      time: stamp(),
      level: String(level || 'log').toLowerCase(),
      tag: tag || '',
      message: message
    };
    entries.push(entry);
    if (entries.length > MAX_ENTRIES) entries.splice(0, entries.length - MAX_ENTRIES);
    pendingLines.push(formatLine(entry));
    if (pendingLines.length > 400) pendingLines.splice(0, pendingLines.length - 400);
    scheduleFlush();
    scheduleRender();
    listeners.forEach(function (fn) { try { fn(entry); } catch (e) { /* 忽略 */ } });
    return entry;
  }

  function formatLine(entry) {
    return '[' + entry.time + '] ' + entry.level.toUpperCase() + (entry.tag ? ('/' + entry.tag) : '') + ' ' + entry.message;
  }

  function nativeBridge() {
    try {
      var cap = window.Capacitor;
      if (!cap || typeof cap.isNativePlatform !== 'function' || !cap.isNativePlatform()) return null;
      var plugins = cap.Plugins || {};
      return plugins.FoliaNative || null;
    } catch (e) { return null; }
  }

  function scheduleFlush() {
    if (flushTimer) return;
    flushTimer = setTimeout(function () { flushTimer = null; flush(); }, 1500);
  }

  function flush() {
    if (!pendingLines.length) return;
    var plugin = nativeBridge();
    if (!plugin || typeof plugin.appendRuntimeLog !== 'function') return;
    var text = pendingLines.join('\n') + '\n';
    pendingLines = [];
    try {
      plugin.appendRuntimeLog({ text: text, fileName: LOG_FILE }).catch(function () { /* 忽略 */ });
    } catch (e) { /* 忽略 */ }
  }

  function writeHeader() {
    push('info', ['==== Mineradio 启动 ' + new Date().toLocaleString() + ' UA=' + String(navigator.userAgent || '').slice(0, 90)], 'boot');
  }

  function scheduleRender() {
    if (renderScheduled) return;
    renderScheduled = true;
    setTimeout(function () {
      renderScheduled = false;
      try { if (typeof renderRuntimeLog === 'function') renderRuntimeLog(); } catch (e) { /* 忽略 */ }
    }, 120);
  }

  // 包装 console，保留原始输出
  ['log', 'info', 'warn', 'error', 'debug'].forEach(function (level) {
    originals[level] = console[level] ? console[level].bind(console) : function () {};
    console[level] = function () {
      try { push(level, arguments); } catch (e) { /* 忽略 */ }
      originals[level].apply(null, arguments);
    };
  });

  window.addEventListener('error', function (event) {
    var message = event && event.message ? event.message : 'Script error';
    var where = event && event.filename ? (' @ ' + String(event.filename).split('/').pop() + ':' + (event.lineno || 0)) : '';
    push('error', [message + where], 'uncaught');
  });

  window.addEventListener('unhandledrejection', function (event) {
    var reason = event && event.reason;
    push('error', [stringify(reason)], 'promise');
  });

  function wrapFetch() {
    if (typeof window.fetch !== 'function' || window.__mrFetchLogged) return;
    window.__mrFetchLogged = true;
    var original = window.fetch;
    window.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      var method = String((init && init.method) || 'GET').toUpperCase();
      var started = Date.now();
      var watch = /\/api\//.test(String(url));
      return original.apply(this, arguments).then(function (response) {
        if (watch) {
          push(response.ok ? 'info' : 'warn', [method + ' ' + String(url).replace(/^https?:\/\/[^/]+/, '') + ' → ' + response.status + ' (' + (Date.now() - started) + 'ms)'], 'api');
          return response;
        }
        return response;
      }).catch(function (error) {
        if (watch) push('error', [method + ' ' + String(url).replace(/^https?:\/\/[^/]+/, '') + ' ✗ ' + (error && error.message || error)], 'api');
        throw error;
      });
    };
  }
  wrapFetch();
  writeHeader();

  return {
    push: push,
    entries: function () { return entries.slice(); },
    latest: function (count) { return entries.slice(-Math.max(1, Number(count) || 50)); },
    clear: function () { entries.length = 0; seq = 0; pendingLines = []; scheduleRender(); },
    flush: flush,
    logFile: function () { return LOG_FILE; },
    subscribe: function (fn) { if (typeof fn === 'function') listeners.push(fn); },
    text: function () { return entries.map(formatLine).join('\n'); }
  };
})();

var runtimeLogFilter = '';

// 手动把当前日志写进文件（返回实际路径，方便用户去文件管理器找）
function saveRuntimeLogFile() {
  var plugin = null;
  try {
    var cap = window.Capacitor;
    if (cap && cap.Plugins) plugin = cap.Plugins.FoliaNative || null;
  } catch (e) { plugin = null; }
  if (!plugin || typeof plugin.appendRuntimeLog !== 'function') {
    if (typeof showToast === 'function') showToast('当前环境不支持写入文件');
    return;
  }
  var text = '\n===== 手动导出 ' + new Date().toLocaleString() + ' =====\n' + MRRuntimeLog.text() + '\n';
  plugin.appendRuntimeLog({ text: text, fileName: MRRuntimeLog.logFile() }).then(function (res) {
    if (typeof showToast === 'function') showToast('日志已写入：' + (res && res.path ? res.path : '文件'));
  }).catch(function (err) {
    if (typeof showToast === 'function') showToast('写入失败：' + (err && err.message || err));
  });
}

function openRuntimeLog() {
  var modal = document.getElementById('runtime-log-modal');
  if (!modal) return;
  modal.classList.add('show');
  modal.setAttribute('aria-hidden', 'false');
  // 打开面板时顺手把缓冲写进文件，避免崩溃时丢日志
  try { if (typeof MRRuntimeLog !== 'undefined') MRRuntimeLog.flush(); } catch (e) { /* 忽略 */ }
  renderRuntimeLog(true);
}

function closeRuntimeLog() {
  var modal = document.getElementById('runtime-log-modal');
  if (!modal) return;
  modal.classList.remove('show');
  modal.setAttribute('aria-hidden', 'true');
}

function clearRuntimeLog() {
  if (typeof MRRuntimeLog !== 'undefined') MRRuntimeLog.clear();
  renderRuntimeLog(true);
}

function runtimeLogFilterText() {
  var input = document.getElementById('runtime-log-filter');
  return input ? String(input.value || '').trim().toLowerCase() : '';
}

function renderRuntimeLog(forceScroll) {
  var list = document.getElementById('runtime-log-list');
  if (!list) return;
  var modal = document.getElementById('runtime-log-modal');
  if (modal && !modal.classList.contains('show')) {
    var countIdle = document.getElementById('runtime-log-count');
    if (countIdle) countIdle.textContent = String((MRRuntimeLog.entries() || []).length);
    return;
  }
  var filter = runtimeLogFilterText();
  var rows = MRRuntimeLog.entries().filter(function (entry) {
    if (!filter) return true;
    return (entry.message + ' ' + entry.tag + ' ' + entry.level).toLowerCase().indexOf(filter) >= 0;
  });
  var countEl = document.getElementById('runtime-log-count');
  if (countEl) countEl.textContent = String(rows.length);
  var nearBottom = list.scrollTop + list.clientHeight >= list.scrollHeight - 40;
  list.innerHTML = rows.map(function (entry) {
    var text = String(entry.message || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    return '<div class="runtime-log-row level-' + entry.level + '">' +
      '<span class="runtime-log-time">' + entry.time + '</span>' +
      '<span class="runtime-log-level">' + entry.level.toUpperCase() + '</span>' +
      (entry.tag ? '<span class="runtime-log-tag">' + entry.tag + '</span>' : '') +
      '<span class="runtime-log-msg">' + text + '</span>' +
      '</div>';
  }).join('') || '<div class="runtime-log-empty">暂时没有日志</div>';
  if (forceScroll || nearBottom) list.scrollTop = list.scrollHeight;
}

function copyRuntimeLog() {
  var text = typeof MRRuntimeLog !== 'undefined' ? MRRuntimeLog.text() : '';
  if (!text) { if (typeof showToast === 'function') showToast('日志是空的'); return; }
  var done = function () { if (typeof showToast === 'function') showToast('日志已复制到剪贴板'); };
  var fail = function () {
    try {
      var area = document.createElement('textarea');
      area.value = text;
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      document.execCommand('copy');
      document.body.removeChild(area);
      done();
    } catch (e) {
      if (typeof showToast === 'function') showToast('复制失败，请长按选择文本');
    }
  };
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(fail);
    } else {
      fail();
    }
  } catch (e) {
    fail();
  }
}
