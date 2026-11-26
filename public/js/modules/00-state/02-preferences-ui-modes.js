function readSavedVolume() {
  try {
    var v = parseFloat(localStorage.getItem('apex-player-volume'));
    return isFinite(v) ? Math.max(0, Math.min(1, v)) : 1.0;
  } catch (e) {
    return 1.0;
  }
}
function normalizeAudioFadeMs(value, fallback) {
  var ms = Math.round(Number(value));
  if (!isFinite(ms)) ms = fallback;
  return Math.max(AUDIO_FADE_MIN_MS, Math.min(AUDIO_FADE_MAX_MS, ms));
}
function readAudioFadePreference() {
  var defaults = { fadeInMs: 460, fadeOutMs: 420 };
  try {
    var raw = JSON.parse(localStorage.getItem(AUDIO_FADE_STORE_KEY) || '{}') || {};
    return {
      fadeInMs: normalizeAudioFadeMs(raw.fadeInMs, defaults.fadeInMs),
      fadeOutMs: normalizeAudioFadeMs(raw.fadeOutMs, defaults.fadeOutMs)
    };
  } catch (e) {
    return defaults;
  }
}
function saveAudioFadePreference() {
  try {
    localStorage.setItem(AUDIO_FADE_STORE_KEY, JSON.stringify({
      fadeInMs: AUDIO_FADE_IN_MS,
      fadeOutMs: AUDIO_FADE_OUT_MS
    }));
  } catch (e) { }
}
function readDiyModePreference() {
  try { return localStorage.getItem(DIY_MODE_STORE_KEY) === '1'; } catch (e) { return false; }
}
function saveDiyModePreference(on) {
  try { localStorage.setItem(DIY_MODE_STORE_KEY, on ? '1' : '0'); } catch (e) { }
}
function readBooleanPreference(key, fallback) {
  try {
    var raw = localStorage.getItem(key);
    if (raw == null) return !!fallback;
    return raw === '1';
  } catch (e) {
    return !!fallback;
  }
}
function saveBooleanPreference(key, on) {
  try { localStorage.setItem(key, on ? '1' : '0'); } catch (e) { }
}
function normalizePlaylistPanelTab(tab) {
  tab = String(tab || '').trim();
  if (tab === 'podcasts') return 'podcasts';
  if (tab === 'playlists') return 'playlists';
  if (tab === 'local') return 'local';
  return 'queue';
}
function readPlaylistPanelTabPreference() {
  try { return normalizePlaylistPanelTab(localStorage.getItem(PLAYLIST_PANEL_TAB_STORE_KEY) || 'queue'); } catch (e) { return 'queue'; }
}
function savePlaylistPanelTabPreference(tab) {
  try { localStorage.setItem(PLAYLIST_PANEL_TAB_STORE_KEY, normalizePlaylistPanelTab(tab)); } catch (e) { }
}
function normalizeCloseBehavior(value) {
  return value === 'tray' ? 'tray' : 'exit';
}
function readCloseBehaviorPreference() {
  try { return normalizeCloseBehavior(localStorage.getItem(CLOSE_BEHAVIOR_STORE_KEY) || 'exit'); } catch (e) { return 'exit'; }
}
function saveCloseBehaviorPreference(value) {
  try { localStorage.setItem(CLOSE_BEHAVIOR_STORE_KEY, normalizeCloseBehavior(value)); } catch (e) { }
}
function syncCloseBehaviorUi() {
  document.querySelectorAll('#close-behavior-seg [data-close-behavior]').forEach(function (btn) {
    btn.classList.toggle('active', btn.getAttribute('data-close-behavior') === closeBehaviorPreference);
  });
}
function setCloseBehaviorPreference(value, opts) {
  opts = opts || {};
  closeBehaviorPreference = normalizeCloseBehavior(value);
  saveCloseBehaviorPreference(closeBehaviorPreference);
  syncCloseBehaviorUi();
  if (window.desktopWindow && typeof window.desktopWindow.setCloseBehavior === 'function') {
    window.desktopWindow.setCloseBehavior(closeBehaviorPreference).catch(function (e) { console.warn('[CloseBehavior]', e); });
  }
  if (opts.toast) showToast(closeBehaviorPreference === 'tray' ? '关闭按钮将放到后台托盘' : '关闭按钮将直接退出');
}
function bindCloseBehaviorControls() {
  var seg = document.getElementById('close-behavior-seg');
  if (!seg || seg._bound) return;
  seg._bound = true;
  seg.addEventListener('click', function (e) {
    var btn = e.target && e.target.closest ? e.target.closest('[data-close-behavior]') : null;
    if (!btn) return;
    setCloseBehaviorPreference(btn.getAttribute('data-close-behavior'), { toast: true });
  });
  syncCloseBehaviorUi();
}
function initializeDesktopCloseBehavior() {
  bindCloseBehaviorControls();
  setCloseBehaviorPreference(closeBehaviorPreference, { toast: false });
}
function normalizeStartupResumeMode(value) {
  return value === 'restart' ? 'restart' : 'resume';
}
function readStartupResumeModePreference() {
  try { return normalizeStartupResumeMode(localStorage.getItem(STARTUP_RESUME_MODE_STORE_KEY) || 'resume'); } catch (e) { return 'resume'; }
}
function saveStartupResumeModePreference(value) {
  try { localStorage.setItem(STARTUP_RESUME_MODE_STORE_KEY, normalizeStartupResumeMode(value)); } catch (e) { }
}
function startupResumeSecondsFromSnapshot(snapshot) {
  if (startupResumeModePreference === 'restart') return 0;
  return Math.max(0, Number(snapshot && snapshot.currentTime) || 0);
}
function applyStartupResumeModeToRestoredSnapshot() {
  if (!restoredLastPlaybackSnapshot || (audio && audio.src)) return;
  pendingPlaybackResumeAt = startupResumeSecondsFromSnapshot(restoredLastPlaybackSnapshot);
  applyRestoredPlaybackProgressUi(Object.assign({}, restoredLastPlaybackSnapshot, { currentTime: pendingPlaybackResumeAt }));
}
function syncStartupResumeModeUi() {
  document.querySelectorAll('#startup-resume-mode-seg [data-startup-resume-mode]').forEach(function (btn) {
    btn.classList.toggle('active', btn.getAttribute('data-startup-resume-mode') === startupResumeModePreference);
  });
}
function setStartupResumeModePreference(value, opts) {
  opts = opts || {};
  startupResumeModePreference = normalizeStartupResumeMode(value);
  saveStartupResumeModePreference(startupResumeModePreference);
  syncStartupResumeModeUi();
  applyStartupResumeModeToRestoredSnapshot();
  if (opts.toast) showToast(startupResumeModePreference === 'restart' ? '恢复播放将重播整首' : '恢复播放将按上次进度继续');
}
function bindStartupResumeModeControls() {
  var seg = document.getElementById('startup-resume-mode-seg');
  if (!seg || seg._bound) return;
  seg._bound = true;
  seg.addEventListener('click', function (e) {
    var btn = e.target && e.target.closest ? e.target.closest('[data-startup-resume-mode]') : null;
    if (!btn) return;
    setStartupResumeModePreference(btn.getAttribute('data-startup-resume-mode'), { toast: true });
  });
  syncStartupResumeModeUi();
}
function applyStartupAutoplayUi() {
  var btn = document.getElementById('t-startupAutoplay');
  if (btn) btn.classList.toggle('on', !!startupAutoplayPreference);
  var skipBtn = document.getElementById('t-startupFastSkip');
  if (skipBtn) skipBtn.classList.toggle('on', !!startupFastSkipPreference);
  syncStartupResumeModeUi();
}
function toggleStartupAutoplay() {
  startupAutoplayPreference = !startupAutoplayPreference;
  saveBooleanPreference(STARTUP_AUTOPLAY_STORE_KEY, startupAutoplayPreference);
  applyStartupAutoplayUi();
  showToast(startupAutoplayPreference ? '启动自动播放已开启' : '启动自动播放已关闭');
  if (startupAutoplayPreference) {
    startupAutoplayAttempted = false;
    queueStartupAutoplayAfterHomeReveal('setting-toggle');
  } else {
    startupAutoplayHomeQueuedReason = '';
    startupAutoplayJobId += 1;
    clearStartupAutoplayRetryTimer();
  }
}
function toggleStartupFastSkip() {
  startupFastSkipPreference = !startupFastSkipPreference;
  saveBooleanPreference(STARTUP_FAST_SKIP_STORE_KEY, startupFastSkipPreference);
  applyStartupAutoplayUi();
  showToast(startupFastSkipPreference ? '秒启动已开启' : '秒启动已关闭');
}
window.toggleStartupAutoplay = toggleStartupAutoplay;
window.toggleStartupFastSkip = toggleStartupFastSkip;
function applyUserCapsuleAutoHideState() {
  document.body.classList.toggle('user-capsule-auto-hide', !!userCapsuleAutoHide);
  armCapsuleFirstTapGuard();
  var btn = document.getElementById('user-capsule-hide-btn');
  if (btn) {
    btn.classList.toggle('on', !!userCapsuleAutoHide);
    btn.textContent = userCapsuleAutoHide ? '›' : '‹';
    btn.title = userCapsuleAutoHide ? '取消自动隐藏账号胶囊' : '自动隐藏账号胶囊';
  }
}
function toggleUserCapsuleAutoHide(e) {
  if (e && e.stopPropagation) e.stopPropagation();
  userCapsuleAutoHide = !userCapsuleAutoHide;
  capsuleLockedUntil = Date.now() + CAPSULE_TAP_LOCK_MS;
  saveBooleanPreference(USER_CAPSULE_AUTO_HIDE_STORE_KEY, userCapsuleAutoHide);
  if (!userCapsuleAutoHide) {
    capsuleLockedUntil = 0;
    document.body.classList.remove('capsule-locked', 'user-capsule-peek');
  }
  applyUserCapsuleAutoHideState();
  showToast(userCapsuleAutoHide ? '账号胶囊已自动隐藏' : '账号胶囊已固定显示');
}
/**
 * 隐藏态胶囊：显示出来后强制锁定 0.4 秒，这段时间里点哪儿都不触发按钮。
 *
 * 前两版没生效的原因：守卫只绑在 #top-right 上。而唤出胶囊靠的是
 * `#top-right::before` 那块向左伸出 82px 的伪元素热区（css 3831 行），
 * 手指点在热区上时，事件目标未必是 #top-right，监听根本不触发；
 * 同时 pointerdown 一发生，:hover / .user-capsule-peek 立刻让胶囊滑到手指底下，
 * 那一下点击就落进 #user-btn，直接把登录窗口打开了。
 *
 * 所以这次改为在 document 层按【坐标】判断：只要这一下落在胶囊/热区范围内，
 * 就补上显示状态并锁 0.4 秒；锁定期间任何点击一律吞掉。
 * 0.4 秒后自动解锁，用户再点才是真的按按钮 —— 也就是你要的「先显示，再能点」。
 */

/** 显示之后的锁定窗口（毫秒）。 */
var CAPSULE_TAP_LOCK_MS = 400;
var capsuleLockedUntil = 0;
var capsuleGuardInstalled = false;

/** 这一下点击是否落在胶囊热区（含向左伸出的 82px）。 */
function isInCapsuleHotZone(event) {
  var topRight = document.getElementById('top-right');
  if (!topRight) return false;
  if (!userCapsuleAutoHide || immersiveMode) return false;

  var rect = topRight.getBoundingClientRect();
  // 隐藏时 rect.right 是负值/屏幕外，这里按「右边缘 + 热区宽度」算，
  // 只要点在大致这一片右上角就都算，宁可多拦一次也不要漏拦。
  var x = event.clientX;
  var y = event.clientY;
  if (typeof x !== 'number' || typeof y !== 'number') return false;

  var rightEdge = Math.max(rect.right, window.innerWidth || 0);
  var leftEdge = rightEdge - Math.max(rect.width || 0, 0) - 120;  // 含 82px 热区余量
  var topEdge = rect.top - 30;
  var bottomEdge = rect.bottom + 20;
  return x >= leftEdge && x <= rightEdge + 10 && y >= topEdge && y <= bottomEdge;
}

/** 让胶囊立刻进入可见状态，并开始锁定（CSS 那边靠 body.capsule-locked 生效）。 */
function revealCapsuleLocked() {
  document.body.classList.add('user-capsule-peek');
  capsuleLockedUntil = Date.now() + CAPSULE_TAP_LOCK_MS;
  document.body.classList.add('capsule-locked');
  // 到点自动解锁：CSS 的 pointer-events: none 和 JS 的吞点击一起失效。
  setTimeout(function () {
    if (Date.now() >= capsuleLockedUntil) {
      document.body.classList.remove('capsule-locked');
    }
  }, CAPSULE_TAP_LOCK_MS + 10);
}

function armCapsuleFirstTapGuard() {
  if (capsuleGuardInstalled) return;
  capsuleGuardInstalled = true;

  // capture 阶段拦截：比任何按钮自己的监听都早，且能拦下伪元素热区上的点击。
  document.addEventListener('pointerdown', function (event) {
    if (!userCapsuleAutoHide || immersiveMode) return;

    var target = event.target;
    // ‹ 小把手是「开关自动隐藏」的按钮，必须始终可点，否则用户关不掉这个状态。
    if (target && target.closest && target.closest('#user-capsule-hide-btn')) return;

    // 锁定期内：一律吞掉，这就是「显示出来后 0.4 秒内点不动」
    if (Date.now() < capsuleLockedUntil) {
      event.preventDefault();
      event.stopPropagation();
      if (event.stopImmediatePropagation) event.stopImmediatePropagation();
      return;
    }

    // 还没显示、且点在热区里：这一下只负责显示，并开始锁定
    if (!isInCapsuleHotZone(event)) return;
    var visible = document.body.classList.contains('user-capsule-peek');
    if (!visible) {
      revealCapsuleLocked();
      event.preventDefault();
      event.stopPropagation();
      if (event.stopImmediatePropagation) event.stopImmediatePropagation();
      return;
    }
    // 已经显示但还没锁过（例如鼠标 hover 出来的）：补上锁定
    if (capsuleLockedUntil === 0) {
      revealCapsuleLocked();
      event.preventDefault();
      event.stopPropagation();
      if (event.stopImmediatePropagation) event.stopImmediatePropagation();
    }
  }, true);

  // 点击事件也拦一道：pointerdown 吞掉后浏览器仍可能补发 click
  document.addEventListener('click', function (event) {
    if (!userCapsuleAutoHide || immersiveMode) return;
    if (Date.now() >= capsuleLockedUntil) return;
    var target = event.target;
    if (target && target.closest && target.closest('#user-capsule-hide-btn')) return;
    event.preventDefault();
    event.stopPropagation();
    if (event.stopImmediatePropagation) event.stopImmediatePropagation();
  }, true);
}

function updateUserCapsuleAutoHideFromPointer(x, y) {
  if (!userCapsuleAutoHide || immersiveMode) {
    document.body.classList.remove('user-capsule-peek');
    return;
  }
  var nearTopRight = x > innerWidth - 112 && y < 126;
  document.body.classList.toggle('user-capsule-peek', nearTopRight);
}
function applyFxFabAutoHideState(opts) {
  opts = opts || {};
  document.body.classList.toggle('fx-fab-auto-hide', !!fxFabAutoHide);
  if (!fxFabAutoHide) {
    document.body.classList.remove('fx-fab-peek');
    fxFabAutoHideRevealArmed = true;
  } else if (opts.forceHidden) {
    document.body.classList.remove('fx-fab-peek');
    fxFabAutoHideRevealArmed = false;
  }
  var btn = document.getElementById('fx-fab-hide-btn');
  if (btn) {
    btn.classList.toggle('on', !!fxFabAutoHide);
    btn.textContent = fxFabAutoHide ? '›' : '‹';
    btn.title = fxFabAutoHide ? '取消自动隐藏视觉控制台' : '自动隐藏视觉控制台';
  }
}
function toggleFxFabAutoHide(e) {
  if (e && e.stopPropagation) e.stopPropagation();
  fxFabAutoHide = !fxFabAutoHide;
  saveBooleanPreference(FX_FAB_AUTO_HIDE_STORE_KEY, fxFabAutoHide);
  applyFxFabAutoHideState({ forceHidden: fxFabAutoHide });
  showToast(fxFabAutoHide ? '视觉控制台按钮已自动隐藏' : '视觉控制台按钮已固定显示');
}
function updateFxFabAutoHideFromPointer(x, y) {
  if (!fxFabAutoHide || !diyPlayerMode || immersiveMode) {
    document.body.classList.remove('fx-fab-peek');
    fxFabAutoHideRevealArmed = true;
    return;
  }
  var panel = document.getElementById('fx-panel');
  var panelOpen = !!(panel && (panel.classList.contains('peek') || panel.classList.contains('show')));
  var nearBottomRight = x > innerWidth - 126 && y > innerHeight - 158;
  if (!nearBottomRight) fxFabAutoHideRevealArmed = true;
  document.body.classList.toggle('fx-fab-peek', panelOpen || (nearBottomRight && fxFabAutoHideRevealArmed));
}
var fullscreenDiyLayoutFrame = 0;
var fullscreenDiyLayoutResizeObserver = null;
var fullscreenDiyLayoutMutationObserver = null;
function visibleFullscreenDiyAccountRects() {
  var root = document.getElementById('top-right');
  if (!root) return [];
  var pills = Array.prototype.slice.call(root.querySelectorAll('.top-account-pill'));
  var nodes = pills.length ? pills : [document.getElementById('user-btn') || root];
  return nodes.map(function (node) {
    if (!node || !node.isConnected) return null;
    var style = window.getComputedStyle ? getComputedStyle(node) : null;
    if (style && (style.display === 'none' || style.visibility === 'hidden')) return null;
    var rect = node.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0 ? rect : null;
  }).filter(Boolean);
}
function fullscreenDiyAccountBounds() {
  var rects = visibleFullscreenDiyAccountRects();
  if (!rects.length) {
    var fallback = document.getElementById('user-btn') || document.getElementById('top-right');
    return fallback ? fallback.getBoundingClientRect() : null;
  }
  return rects.reduce(function (bounds, rect) {
    bounds.left = Math.min(bounds.left, rect.left);
    bounds.right = Math.max(bounds.right, rect.right);
    bounds.top = Math.min(bounds.top, rect.top);
    bounds.bottom = Math.max(bounds.bottom, rect.bottom);
    return bounds;
  }, { left: Infinity, right: -Infinity, top: Infinity, bottom: -Infinity });
}
function layoutFullscreenDiyZone() {
  var width = innerWidth < 820 ? 104 : 128;
  var height = innerWidth < 720 ? 48 : 52;
  var left = innerWidth - 510;
  var top = 24;
  var rect = fullscreenDiyAccountBounds();
  if (rect && isFinite(rect.left) && isFinite(rect.right) && isFinite(rect.bottom)) {
    if (rect.right > rect.left && rect.bottom > rect.top) {
      var gap = innerWidth < 820 ? 8 : 12;
      left = rect.left + (rect.right - rect.left) / 2 - width / 2;
      top = rect.bottom + gap;
    }
  }
  left = Math.max(12, Math.min(innerWidth - width - 12, left));
  top = Math.max(8, Math.min(innerHeight - height - 8, top));
  document.documentElement.style.setProperty('--fullscreen-diy-left', left.toFixed(1) + 'px');
  document.documentElement.style.setProperty('--fullscreen-diy-top', top.toFixed(1) + 'px');
  document.documentElement.style.setProperty('--fullscreen-diy-width', width + 'px');
  return { left: left, top: top, width: width, height: height };
}
function scheduleFullscreenDiyLayout() {
  if (fullscreenDiyLayoutFrame) return;
  fullscreenDiyLayoutFrame = requestAnimationFrame(function () {
    fullscreenDiyLayoutFrame = 0;
    layoutFullscreenDiyZone();
  });
}
function setupFullscreenDiyLayoutTracking() {
  var root = document.getElementById('top-right');
  if (!root || root.__fullscreenDiyLayoutTracking) return;
  root.__fullscreenDiyLayoutTracking = true;
  window.addEventListener('resize', scheduleFullscreenDiyLayout, { passive: true });
  document.addEventListener('fullscreenchange', scheduleFullscreenDiyLayout);
  if (typeof ResizeObserver === 'function') {
    fullscreenDiyLayoutResizeObserver = new ResizeObserver(scheduleFullscreenDiyLayout);
    fullscreenDiyLayoutResizeObserver.observe(root);
  }
  if (typeof MutationObserver === 'function') {
    fullscreenDiyLayoutMutationObserver = new MutationObserver(scheduleFullscreenDiyLayout);
    fullscreenDiyLayoutMutationObserver.observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'hidden'] });
  }
  scheduleFullscreenDiyLayout();
}
function shouldSuppressFullscreenDiyPeek() {
  var fxPanel = document.getElementById('fx-panel');
  var hotkeyModal = document.getElementById('hotkey-modal');
  var fxPanelOpen = !!(fxPanel && (fxPanel.classList.contains('peek') || fxPanel.classList.contains('show')));
  var hotkeyOpen = !!(hotkeyModal && hotkeyModal.classList.contains('show'));
  return !!(visualGuideActive || fxPanelOpen || hotkeyOpen);
}
function updateFullscreenDiyPeekFromPointer(x, y) {
  var isFullscreen = !!(desktopRuntimeState.fullscreen || desktopFullscreenActive || document.fullscreenElement || document.body.classList.contains('desktop-fullscreen'));
  if (!isFullscreen || immersiveMode || shouldSuppressFullscreenDiyPeek()) {
    document.body.classList.remove('fullscreen-diy-peek');
    return;
  }
  var rect = layoutFullscreenDiyZone();
  var anchorRect = fullscreenDiyAccountBounds() || rect;
  var hitLeft = Math.min(rect.left, anchorRect.left) - 26;
  var hitRight = Math.max(rect.left + rect.width, anchorRect.right) + 26;
  var hitTop = Math.min(rect.top, anchorRect.top) - 18;
  var hitBottom = Math.max(rect.top + rect.height, anchorRect.bottom) + 16;
  var active = x >= hitLeft && x <= hitRight && y >= hitTop && y <= hitBottom;
  document.body.classList.toggle('fullscreen-diy-peek', active);
}
function isDiyMode() {
  return !!diyPlayerMode;
}
function syncDiyModeButton() {
  ['diy-mode-btn', 'fullscreen-diy-btn', 'diy-mode-btn-mobile'].forEach(function (id) {
    var btn = document.getElementById(id);
    if (!btn) return;
    btn.classList.toggle('on', diyPlayerMode);
    btn.setAttribute('aria-pressed', diyPlayerMode ? 'true' : 'false');
    btn.title = diyPlayerMode ? '关闭 DIY 玩家模式' : '开启 DIY 玩家模式';
    btn.setAttribute('aria-label', btn.title);
  });
}
function applyDiyMode(on, opts) {
  opts = opts || {};
  diyPlayerMode = !!on;
  document.documentElement.classList.toggle('diy-mode-preload', diyPlayerMode);
  document.documentElement.classList.toggle('simple-mode-preload', !diyPlayerMode);
  document.body.classList.toggle('diy-mode', diyPlayerMode);
  document.body.classList.toggle('simple-mode', !diyPlayerMode);
  syncDiyModeButton();
  if (opts.save) saveDiyModePreference(diyPlayerMode);
  if (!diyPlayerMode) {
    toggleFxPanel(false);
    togglePlaylistPanel(false);
    closeUploadTip(false);
    var quality = document.getElementById('quality-control');
    var volume = document.getElementById('volume-control');
    if (quality) quality.classList.remove('open');
    if (volume) volume.classList.remove('open');
  }
  if (opts.toast) showToast(diyPlayerMode ? 'DIY 玩家模式已开启' : '已切回简约模式');
  if (opts.animate && window.gsap) {
    ['diy-mode-btn', 'fullscreen-diy-btn', 'diy-mode-btn-mobile'].forEach(function (id) {
      var btn = document.getElementById(id);
      if (btn) window.gsap.fromTo(btn, { scale: 0.94 }, { scale: 1, duration: 0.34, ease: 'back.out(1.8)', overwrite: true });
    });
  }
}
function toggleDiyMode() {
  applyDiyMode(!diyPlayerMode, { save: true, toast: true, animate: true });
  if (visualGuideActive) {
    visualGuideState.mode = diyPlayerMode ? 'diy' : 'simple';
    showVisualGuideStep(0);
  }
}
