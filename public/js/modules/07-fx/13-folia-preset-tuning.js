// ============================================================
// Folia 预设调参面板
// - 每个预设（可视化器）都有自己的 tuning：商籁的排版/后期、凝彩的深度、花火的花火量、
//   町景的场景/时段/天气/镜头…… 这里按 12-folia-tuning-schema.js 的字段表统一渲染
// - 数值 → 滑块；开关 → 开/关；枚举 → 胶囊按钮
// - 当前值从内嵌页的 store 读（__foliaGetTuning），改动用 __foliaSetTuning 实时下发
// ============================================================

var foliaTuningCurrentMode = '';
var foliaTuningApplying = false;

function foliaTuningModeForPreset() {
  var state = (typeof foliaLyricState !== 'undefined') ? foliaLyricState : null;
  if (!state || state.index < 0) return '';
  var id = state.activeId || '';
  if (!id) return '';
  var alias = (typeof FOLIA_TUNING_MODE_ALIAS !== 'undefined') ? FOLIA_TUNING_MODE_ALIAS : {};
  var mode = alias[id] || id;
  return (typeof FOLIA_TUNING_MODES !== 'undefined' && FOLIA_TUNING_MODES[mode]) ? mode : '';
}

function foliaTuningPresetName(mode) {
  if (typeof FOLIA_LYRIC_PRESETS === 'undefined') return mode;
  for (var i = 0; i < FOLIA_LYRIC_PRESETS.length; i += 1) {
    if (FOLIA_LYRIC_PRESETS[i].id === mode) return FOLIA_LYRIC_PRESETS[i].name;
  }
  return mode;
}

function foliaTuningFrame() {
  return document.getElementById('folia-lyrics-frame');
}

function foliaTuningReadValues(mode) {
  var meta = (typeof FOLIA_TUNING_MODES !== 'undefined') ? FOLIA_TUNING_MODES[mode] : null;
  if (!meta) return null;
  var frame = foliaTuningFrame();
  try {
    var win = frame && frame.contentWindow;
    if (win && typeof win.__foliaGetTuning === 'function') {
      var live = win.__foliaGetTuning(meta.settingsKey);
      if (live && typeof live === 'object') return live;
    }
  } catch (e) { /* 忽略 */ }
  try {
    var raw = localStorage.getItem(meta.storageKey);
    if (raw) {
      var parsed = JSON.parse(raw);
      if (parsed && typeof parsed === 'object') return parsed;
    }
  } catch (e) { /* 忽略 */ }
  return null;
}

function foliaTuningPersistLocal(mode, patch) {
  var meta = (typeof FOLIA_TUNING_MODES !== 'undefined') ? FOLIA_TUNING_MODES[mode] : null;
  if (!meta) return;
  try {
    var raw = localStorage.getItem(meta.storageKey);
    var current = raw ? (JSON.parse(raw) || {}) : {};
    var next = Object.assign({}, current, patch);
    localStorage.setItem(meta.storageKey, JSON.stringify(next));
  } catch (e) { /* 忽略 */ }
}

// 把一组调参下发给内嵌页；有桥就实时生效，没有就写存档后重载内嵌页
function foliaTuningPush(mode, patch) {
  var meta = (typeof FOLIA_TUNING_MODES !== 'undefined') ? FOLIA_TUNING_MODES[mode] : null;
  if (!meta) return false;
  var win = null;
  try {
    var frame = foliaTuningFrame();
    win = frame && frame.contentWindow;
  } catch (e) { win = null; }

  if (win) {
    try {
      if (typeof win.__foliaSetTuning === 'function') {
        if (win.__foliaSetTuning(meta.setterKey, patch) === true) return true;
      }
    } catch (e) { /* 忽略 */ }
    try {
      win.postMessage({ type: 'folia-tuning', setter: meta.setterKey, tuning: patch }, '*');
    } catch (e) { /* 忽略 */ }
  }

  foliaTuningPersistLocal(mode, patch);
  try {
    if (typeof foliaFrameState !== 'undefined' && foliaFrameState.ready) {
      var frame2 = foliaTuningFrame();
      foliaFrameState.ready = false;
      frame2.src = FOLIA_FRAME_SRC + '?tuning=' + Date.now();
    }
  } catch (e) { /* 忽略 */ }
  return false;
}

function foliaTuningApply(field, value) {
  var mode = foliaTuningCurrentMode;
  if (!mode) return;
  var patch = {};
  patch[field] = value;
  foliaTuningPush(mode, patch);
  foliaTuningRender();
}

function foliaTuningReset() {
  var mode = foliaTuningCurrentMode;
  var meta = (typeof FOLIA_TUNING_MODES !== 'undefined') ? FOLIA_TUNING_MODES[mode] : null;
  if (!meta) return;
  try {
    var win = (foliaTuningFrame() || {}).contentWindow;
    if (win && typeof win.__foliaSetTuning === 'function') {
      win.__foliaSetTuning(meta.resetKey, null);
    }
  } catch (e) { /* 忽略 */ }
  try { localStorage.removeItem(meta.storageKey); } catch (e) { /* 忽略 */ }
  if (typeof showToast === 'function') showToast('已恢复「' + foliaTuningPresetName(mode) + '」默认参数');
  setTimeout(foliaTuningRender, 60);
}

function foliaTuningEnumOptions(mode, field) {
  if (typeof FOLIA_TUNING_ENUMS === 'undefined') return null;
  return FOLIA_TUNING_ENUMS[mode + '|' + field] || null;
}

function foliaTuningEscape(text) {
  if (typeof foliaLyricEscape === 'function') return foliaLyricEscape(text);
  return String(text == null ? '' : text).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

function foliaTuningFormat(value, step) {
  var n = Number(value);
  if (!isFinite(n)) return String(value);
  var digits = step && step < 0.1 ? 2 : (step && step < 1 ? 1 : 0);
  return n.toFixed(digits);
}

function foliaTuningRenderRow(mode, field, value) {
  var fields = (typeof FOLIA_TUNING_FIELDS !== 'undefined') ? FOLIA_TUNING_FIELDS : {};
  var cfg = fields[field] || {};
  var label = cfg.label || field;
  var options = foliaTuningEnumOptions(mode, field);

  if (options) {
    var current = String(value);
    var chips = '';
    for (var i = 0; i < options.length; i += 1) {
      var optionValue = options[i][0];
      var active = String(optionValue) === current ? ' active' : '';
      chips += '<button type="button" class="folia-chip' + active + '" data-folia-tune-field="' + field +
        '" data-folia-tune-value="' + String(optionValue) + '">' + foliaTuningEscape(options[i][1]) + '</button>';
    }
    return '<div class="folia-tuning-row"><span class="folia-tuning-name">' + foliaTuningEscape(label) +
      '</span><div class="folia-chip-grid">' + chips + '</div></div>';
  }

  if (Array.isArray(value) && typeof FOLIA_TUNING_MULTI !== 'undefined') {
    var multi = FOLIA_TUNING_MULTI[mode + '|' + field];
    if (multi) {
      var picked = value.map(function (item) { return String(item); });
      var multiChips = '';
      for (var m = 0; m < multi.length; m += 1) {
        var mValue = String(multi[m][0]);
        var mActive = picked.indexOf(mValue) >= 0 ? ' active' : '';
        multiChips += '<button type="button" class="folia-chip' + mActive + '" data-folia-tune-multi="' + field +
          '" data-folia-tune-value="' + mValue + '">' + foliaTuningEscape(multi[m][1]) + '</button>';
      }
      return '<div class="folia-tuning-row"><span class="folia-tuning-name">' + foliaTuningEscape(label) +
        '</span><div class="folia-chip-grid">' + multiChips + '</div></div>';
    }
  }

  if (typeof value === 'boolean') {
    return '<div class="folia-tuning-row"><span class="folia-tuning-name">' + foliaTuningEscape(label) +
      '</span><div class="folia-chip-grid">' +
      '<button type="button" class="folia-chip' + (value ? ' active' : '') + '" data-folia-tune-field="' + field +
      '" data-folia-tune-value="on">开</button>' +
      '<button type="button" class="folia-chip' + (value ? '' : ' active') + '" data-folia-tune-field="' + field +
      '" data-folia-tune-value="off">关</button>' +
      '</div></div>';
  }

  if (typeof value === 'number' && isFinite(value) && typeof cfg.min === 'number' && typeof cfg.max === 'number') {
    var step = typeof cfg.step === 'number' ? cfg.step : 0.05;
    var clamped = Math.min(cfg.max, Math.max(cfg.min, value));
    return '<label class="folia-machi-slider folia-tuning-slider"><span class="folia-machi-slider-name">' +
      foliaTuningEscape(label) + '</span><input type="range" min="' + cfg.min + '" max="' + cfg.max +
      '" step="' + step + '" value="' + clamped + '" data-folia-tune-range="' + field + '">' +
      '<output class="folia-machi-slider-value">' + foliaTuningFormat(clamped, step) + '</output></label>';
  }

  return '';
}

function foliaTuningRender() {
  var section = document.getElementById('folia-tuning-section');
  var host = document.getElementById('folia-tuning-host');
  if (!section || !host) return;

  var mode = foliaTuningModeForPreset();
  foliaTuningCurrentMode = mode;
  var meta = (typeof FOLIA_TUNING_MODES !== 'undefined') ? FOLIA_TUNING_MODES[mode] : null;
  if (!meta) {
    section.hidden = true;
    host.innerHTML = '';
    return;
  }

  var title = document.getElementById('folia-tuning-preset-name');
  if (title) title.textContent = foliaTuningPresetName(mode);

  var values = foliaTuningReadValues(mode);
  if (!values) {
    section.hidden = false;
    host.innerHTML = '<div class="folia-tuning-empty">内嵌页还没就绪：选一个画面开始播放后，这里的参数会自动出现</div>';
    return;
  }

  var order = (typeof FOLIA_TUNING_ORDER !== 'undefined' && FOLIA_TUNING_ORDER[mode]) ? FOLIA_TUNING_ORDER[mode] : Object.keys(values);
  var html = '';
  var shown = 0;
  for (var i = 0; i < order.length; i += 1) {
    var field = order[i];
    if (!Object.prototype.hasOwnProperty.call(values, field)) continue;
    var row = foliaTuningRenderRow(mode, field, values[field]);
    if (!row) continue;
    html += row;
    shown += 1;
  }
  section.hidden = shown === 0;
  host.innerHTML = html || '<div class="folia-tuning-empty">这个预设暂时没有可调项</div>';
}

function foliaTuningOnClick(event) {
  var multiChip = event.target && event.target.closest ? event.target.closest('[data-folia-tune-multi]') : null;
  if (multiChip) {
    var multiField = multiChip.getAttribute('data-folia-tune-multi');
    var multiValue = multiChip.getAttribute('data-folia-tune-value');
    var values = foliaTuningReadValues(foliaTuningCurrentMode) || {};
    var list = Array.isArray(values[multiField]) ? values[multiField].map(String) : [];
    var at = list.indexOf(multiValue);
    if (at >= 0) list.splice(at, 1); else list.push(multiValue);
    if (!list.length) return;
    foliaTuningApply(multiField, list);
    return;
  }
  var chip = event.target && event.target.closest ? event.target.closest('[data-folia-tune-field]') : null;
  if (!chip) return;
  var field = chip.getAttribute('data-folia-tune-field');
  var raw = chip.getAttribute('data-folia-tune-value');
  if (!field) return;
  var value = raw;
  if (raw === 'on') value = true;
  else if (raw === 'off') value = false;
  else if (/^-?\d+(\.\d+)?$/.test(raw)) value = Number(raw);
  foliaTuningApply(field, value);
}

function foliaTuningOnInput(event) {
  var range = event.target;
  if (!range || !range.getAttribute) return;
  var field = range.getAttribute('data-folia-tune-range');
  if (!field) return;
  var value = Number(range.value);
  if (!isFinite(value)) return;
  var output = range.parentNode ? range.parentNode.querySelector('output') : null;
  var fields = (typeof FOLIA_TUNING_FIELDS !== 'undefined') ? FOLIA_TUNING_FIELDS : {};
  var cfg = fields[field] || {};
  if (output) output.textContent = foliaTuningFormat(value, cfg.step);
  if (foliaTuningApplying) return;
  foliaTuningApplying = true;
  try {
    foliaTuningPush(foliaTuningCurrentMode, (function () { var patch = {}; patch[field] = value; return patch; })());
  } finally {
    foliaTuningApplying = false;
  }
}

// 内嵌页 ready 后父层不需要推默认值（内嵌页自己读存档），这里只刷新一次界面
function foliaTuningPushAll() {
  setTimeout(foliaTuningRender, 40);
}

function foliaTuningBindOnce() {
  var modal = document.getElementById('folia-console-modal');
  if (!modal || modal._foliaTuningBound) return;
  modal._foliaTuningBound = true;
  modal.addEventListener('click', foliaTuningOnClick);
  modal.addEventListener('input', foliaTuningOnInput);
}

setTimeout(function () {
  foliaTuningBindOnce();
  foliaTuningRender();
}, 0);