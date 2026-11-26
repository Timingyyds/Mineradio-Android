// ============================================================
var PEEK_HIDE_DELAY = 170;
var PLAYLIST_PANEL_HIDE_DELAY = 72;
var peekTimers = { search: null, fx: null, pl: null };
var searchPeekRevealToken = 0;
var searchPeekRevealPending = false;
var PLAYLIST_PANEL_MOTION_MS = 360;
var PLAYLIST_PANEL_OPEN_ANIM_COOLDOWN = 520;
var PLAYLIST_PANEL_OPEN_DURATION_RANGE = { min: 0.08, max: 0.72, fallback: 0.28 };
var PLAYLIST_PANEL_CLOSE_DURATION_RANGE = { min: 0.06, max: 0.48, fallback: 0.18 };

// ★ 安卓 WebView 的 100vh 是「布局视口高度」，往往比真实可见区域高得多，
//   歌单面板用 calc(100vh - 120px) 会变成一屏多高，列表下半截永远够不到。
//   这里用真实可视高度给面板设一个 max-height，让列表在面板内部滚动。
var playlistViewportSyncBound = false;
function syncPlaylistPanelViewportHeight() {
  var panel = document.getElementById('playlist-panel');
  if (!panel) return;
  var h = (window.visualViewport && window.visualViewport.height) || window.innerHeight || 0;
  if (h > 0) {
    panel.style.maxHeight = Math.max(240, Math.round(h - 110)) + 'px';
  }
}
function bindPlaylistPanelViewportSync() {
  if (playlistViewportSyncBound) return;
  playlistViewportSyncBound = true;
  window.addEventListener('resize', syncPlaylistPanelViewportHeight);
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', syncPlaylistPanelViewportHeight);
  }
  syncPlaylistPanelViewportHeight();
  setTimeout(syncPlaylistPanelViewportHeight, 600);
  setTimeout(syncPlaylistPanelViewportHeight, 1600);
}
bindPlaylistPanelViewportSync();
function playlistPanelMotionRange(type) {
  return type === 'close' ? PLAYLIST_PANEL_CLOSE_DURATION_RANGE : PLAYLIST_PANEL_OPEN_DURATION_RANGE;
}
function playlistPanelMotionMs(type) {
  var key = type === 'close' ? 'playlistPanelCloseDuration' : 'playlistPanelOpenDuration';
  var range = playlistPanelMotionRange(type);
  var value = (typeof fx !== 'undefined' && fx && isFinite(fx[key])) ? Number(fx[key]) : range.fallback;
  return Math.round(clampRange(value, range.min, range.max) * 1000);
}
function playlistPeekHideDelay(key) {
  return key === 'pl' ? PLAYLIST_PANEL_HIDE_DELAY : PEEK_HIDE_DELAY;
}
function markPlaylistPanelMotion(panel, duration) {
  if (!panel) return;
  panel.__playlistMotionUntil = performance.now() + (duration == null ? PLAYLIST_PANEL_MOTION_MS : duration);
}
function isPlaylistPanelInMotion(panel) {
  return !!(panel && panel.__playlistMotionUntil && performance.now() < panel.__playlistMotionUntil);
}
function isPlaylistPanelOpeningMotion(panel) {
  return !!(isPlaylistPanelInMotion(panel) && !panel.classList.contains('playlist-panel-closing'));
}
function isSearchGlassReadyForReveal() {
  var root = document.documentElement;
  return !root.classList.contains('control-glass-svg-ok') ||
    root.classList.contains('search-glass-ready') ||
    root.classList.contains('search-glass-fallback');
}
function cancelPendingSearchPeekReveal() {
  searchPeekRevealToken += 1;
  searchPeekRevealPending = false;
}
function isSearchPeekRevealPending() {
  return searchPeekRevealPending;
}
function scheduleSearchPeekAfterGlassReady(el) {
  if (!el) return;
  var token = searchPeekRevealToken + 1;
  searchPeekRevealToken = token;
  searchPeekRevealPending = true;
  var startedAt = performance.now();
  function waitForGlass() {
    if (token !== searchPeekRevealToken) return;
    if (isSearchGlassReadyForReveal() || performance.now() - startedAt > 140) {
      searchPeekRevealPending = false;
      if (immersiveMode) return;
      el.classList.add('peek');
      return;
    }
    requestAnimationFrame(waitForGlass);
  }
  requestAnimationFrame(waitForGlass);
}
function shouldAnimatePlaylistPanelOpen(panel) {
  if (!panel) return false;
  var now = performance.now();
  if (panel.__lastOpenListAnimAt && now - panel.__lastOpenListAnimAt < PLAYLIST_PANEL_OPEN_ANIM_COOLDOWN) return false;
  panel.__lastOpenListAnimAt = now;
  return true;
}
function setPeek(el, on, key) {
  if (!el) return;
  if (immersiveMode && on && (key === 'search' || key === 'fx')) return;
  if (on && !diyPlayerMode && key === 'fx') return;
  if (!on && key === 'search' && emptyHomeActive && !immersiveMode) return;
  if (!on && key === 'pl' && playlistPanelPinned) return;
  if (on && key === 'fx') document.body.classList.remove('fullscreen-diy-peek');
  if (on) {
    if (key === 'pl') resetSecondaryPlaylistEdgeGuard();
    var wasPeek = el.classList.contains('peek');
    if (peekTimers[key]) { clearTimeout(peekTimers[key]); peekTimers[key] = null; }
    if (key === 'fx') el.classList.remove('closing');
    if (key === 'pl') el.classList.remove('playlist-panel-closing');
    if (key === 'search' && typeof prepareSearchGlassBeforePeek === 'function' && !wasPeek) {
      var searchGlassReady = prepareSearchGlassBeforePeek();
      if (!searchGlassReady) {
        scheduleSearchPeekAfterGlassReady(el);
        return;
      }
    }
    var runPlaylistOpenAnimation = key === 'pl' && !wasPeek ? shouldAnimatePlaylistPanelOpen(el) : false;
    if (key === 'pl' && !wasPeek) preparePlaylistPanelTabOnOpen(el);
    if (!wasPeek) el.__peekOpenedAt = performance.now();
    el.classList.add('peek');
    if (key === 'pl' && !wasPeek) markPlaylistPanelMotion(el, playlistPanelMotionMs('open'));
    if (key === 'pl' && !wasPeek) {
      scheduleUiWarmTask(function () {
        flushDeferredQueuePanel('playlist-panel-peek');
        if (runPlaylistOpenAnimation) animatePlaylistPanelCurrentTab(el, { scrollActive: false });
      }, 180);
    }
    if (key === 'fx') {
      var fabOn = document.getElementById('fx-fab');
      if (fabOn) fabOn.classList.add('active');
    }
  } else {
    if (key === 'search') cancelPendingSearchPeekReveal();
    if (key === 'pl') resetSecondaryPlaylistEdgeGuard();
    if (peekTimers[key]) clearTimeout(peekTimers[key]);
    peekTimers[key] = setTimeout(function () {
      if (key === 'pl') el.classList.add('playlist-panel-closing');
      el.classList.remove('peek');
      if (key === 'pl') markPlaylistPanelMotion(el, playlistPanelMotionMs('close'));
      if (key === 'pl') setTimeout(function () { el.classList.remove('playlist-panel-closing'); }, playlistPanelMotionMs('close') + 80);
      if (key === 'fx') {
        var fabOff = document.getElementById('fx-fab');
        if (fabOff && !el.classList.contains('show')) fabOff.classList.remove('active');
      }
      peekTimers[key] = null;
    }, playlistPeekHideDelay(key));
  }
}
function uploadTipWasSeen() {
  try { return localStorage.getItem(UPLOAD_TIP_STORE_KEY) === '1'; } catch (e) { return true; }
}
function markUploadTipSeen() {
  try { localStorage.setItem(UPLOAD_TIP_STORE_KEY, '1'); } catch (e) { }
}
function closeUploadTip(manual) {
  var tip = document.getElementById('upload-tip');
  if (uploadTipTimer) { clearTimeout(uploadTipTimer); uploadTipTimer = null; }
  if (manual) markUploadTipSeen();
  if (!tip || !tip.classList.contains('show')) return;
  if (window.gsap) {
    window.gsap.killTweensOf(tip);
    window.gsap.to(tip, {
      autoAlpha: 0,
      y: -8,
      scale: 0.98,
      duration: 0.24,
      ease: 'power2.in',
      overwrite: true,
      onComplete: function () {
        tip.classList.remove('show');
        window.gsap.set(tip, { clearProps: 'opacity,visibility,transform,filter' });
      }
    });
  } else {
    tip.classList.remove('show');
  }
}
function maybeShowUploadTipOnce() {
  if (!diyPlayerMode) return;
  if (uploadTipWasSeen()) return;
  if (immersiveMode) {
    setTimeout(maybeShowUploadTipOnce, 1800);
    return;
  }
  if (document.body.classList.contains('splash-active') || loginGuideAnimating) {
    setTimeout(maybeShowUploadTipOnce, 900);
    return;
  }
  var loginModal = document.getElementById('login-modal');
  var userModal = document.getElementById('user-modal');
  var coverModal = document.getElementById('cover-crop-modal');
  var hasModal = (loginModal && loginModal.classList.contains('show')) ||
    (userModal && userModal.classList.contains('show')) ||
    (coverModal && coverModal.classList.contains('show'));
  if (hasModal) {
    uploadTipAttempts++;
    if (uploadTipAttempts < 18) setTimeout(maybeShowUploadTipOnce, 1800);
    return;
  }
  var area = document.getElementById('search-area');
  var tip = document.getElementById('upload-tip');
  if (!area || !tip) return;
  markUploadTipSeen();
  setPeek(area, true, 'search');
  tip.classList.add('show');
  if (window.gsap) {
    window.gsap.killTweensOf(tip);
    window.gsap.fromTo(tip,
      { autoAlpha: 0, y: -10, scale: 0.975 },
      { autoAlpha: 1, y: 0, scale: 1, duration: 0.62, ease: 'expo.out', overwrite: true }
    );
    var uploadBtn = document.getElementById('upload-btn');
    if (uploadBtn) {
      window.gsap.fromTo(uploadBtn,
        { scale: 1, boxShadow: '0 10px 32px rgba(0,0,0,.22)' },
        { scale: 1.07, boxShadow: '0 0 0 8px rgba(244,210,138,0),0 16px 46px rgba(244,210,138,.14)', duration: 0.58, ease: 'sine.inOut', yoyo: true, repeat: 3, overwrite: true }
      );
    }
  }
  uploadTipTimer = setTimeout(function () {
    uploadTipTimer = null;
    closeUploadTip(false);
    setPeek(area, false, 'search');
  }, 6800);
}
var secondaryPlaylistEdgeGuard = { enteredAt: 0, timer: null, x: 0, y: 0, H: 0 };
var SECONDARY_PLAYLIST_EDGE_MIN_X = 14;
var SECONDARY_PLAYLIST_EDGE_MAX_X = 112;
var SECONDARY_PLAYLIST_EDGE_DWELL_MS = 220;
var SECONDARY_PLAYLIST_SEAM_CLOSE_X = 6;
var PLAYLIST_PANEL_EDGE_TRIGGER_X = 104;
var PLAYLIST_PANEL_FULLSCREEN_EDGE_TRIGGER_X = 128;
var PLAYLIST_PANEL_HOME_EDGE_TRIGGER_X = 16;
var PLAYLIST_PANEL_FULLSCREEN_FOCUS_HOLD_X = 14;
var PLAYLIST_PANEL_FULLSCREEN_EDGE_LEAVE_TOLERANCE_X = -8;
var PLAYLIST_PANEL_BOTTOM_LEFT_BLOCK_X = 176;
function isPlaylistFullscreenEdgeMode() {
  return !!((typeof desktopRuntimeState !== 'undefined' && desktopRuntimeState && desktopRuntimeState.fullscreen) ||
    (typeof desktopFullscreenActive !== 'undefined' && desktopFullscreenActive) ||
    document.fullscreenElement ||
    document.body.classList.contains('desktop-fullscreen'));
}
function isSecondaryLeftDisplaySeamGuardActive() {
  var state = (typeof desktopWindowState !== 'undefined' && desktopWindowState) ? desktopWindowState : {};
  return !!(!isPlaylistFullscreenEdgeMode() && window.desktopWindow && window.desktopWindow.isDesktop && state.isPrimaryDisplay === false && state.hasDisplayOnLeft);
}
function resetSecondaryPlaylistEdgeGuard() {
  if (secondaryPlaylistEdgeGuard.timer) {
    clearTimeout(secondaryPlaylistEdgeGuard.timer);
    secondaryPlaylistEdgeGuard.timer = null;
  }
  secondaryPlaylistEdgeGuard.enteredAt = 0;
}
function playlistPanelEdgeTopGutter(H) {
  return Math.max(82, Math.min(132, Math.round(H * (isPlaylistFullscreenEdgeMode() ? 0.10 : 0.14))));
}
function playlistPanelEdgeBottomGutter(H) {
  return Math.max(104, Math.min(150, Math.round(H * (isPlaylistFullscreenEdgeMode() ? 0.13 : 0.16))));
}
function isPlaylistPanelBottomControlsConflict(ex, ey, H) {
  var nearBottomLeft = ey >= H - 112 && ex < PLAYLIST_PANEL_BOTTOM_LEFT_BLOCK_X;
  var handle = document.getElementById('bottom-handle');
  var bar = document.getElementById('bottom-bar');
  var handleRect = handle ? handle.getBoundingClientRect() : null;
  var barRect = bar ? bar.getBoundingClientRect() : null;
  var overHandle = !!(handleRect && ex >= handleRect.left - 28 && ex <= handleRect.right + 28 && ey >= handleRect.top - 18 && ey <= handleRect.bottom + 22);
  var barActive = !!((bar && bar.classList.contains('visible') && !bar.classList.contains('soft-hidden')) || document.body.classList.contains('controls-visible'));
  var overBarLeft = !!(barActive && barRect && ex >= barRect.left - 22 && ex <= Math.min(barRect.left + 300, barRect.right + 22) && ey >= barRect.top - 28 && ey <= barRect.bottom + 22);
  return nearBottomLeft || overHandle || overBarLeft;
}
function isSecondaryPlaylistSafeBandPoint(ex, ey, H) {
  return ey > playlistPanelEdgeTopGutter(H) && ey < H - playlistPanelEdgeBottomGutter(H) &&
    ex >= SECONDARY_PLAYLIST_EDGE_MIN_X && ex < SECONDARY_PLAYLIST_EDGE_MAX_X &&
    !isPlaylistPanelBottomControlsConflict(ex, ey, H);
}
function armSecondaryPlaylistEdgeDwell() {
  if (secondaryPlaylistEdgeGuard.timer) return;
  secondaryPlaylistEdgeGuard.timer = setTimeout(function () {
    secondaryPlaylistEdgeGuard.timer = null;
    if (!isSecondaryLeftDisplaySeamGuardActive()) return;
    if (!isSecondaryPlaylistSafeBandPoint(secondaryPlaylistEdgeGuard.x, secondaryPlaylistEdgeGuard.y, secondaryPlaylistEdgeGuard.H)) return;
    var panel = document.getElementById('playlist-panel');
    if (panel) setPeek(panel, true, 'pl');
  }, SECONDARY_PLAYLIST_EDGE_DWELL_MS);
}
function playlistPanelInitialEdgeTriggerX(defaultWidth, eventTarget) {
  if (!emptyHomeActive || !eventTarget || !eventTarget.closest || !eventTarget.closest('#empty-home')) return defaultWidth;
  var panel = document.getElementById('playlist-panel');
  if (isPlaylistPanelActiveState(panel)) return defaultWidth;
  return Math.min(defaultWidth, PLAYLIST_PANEL_HOME_EDGE_TRIGGER_X);
}
function isPlaylistEdgeTrigger(ex, ey, H, eventTarget) {
  var inVerticalBand = ey > playlistPanelEdgeTopGutter(H) && ey < H - playlistPanelEdgeBottomGutter(H);
  if (!inVerticalBand) {
    resetSecondaryPlaylistEdgeGuard();
    return false;
  }
  if (isPlaylistPanelBottomControlsConflict(ex, ey, H)) {
    resetSecondaryPlaylistEdgeGuard();
    return false;
  }
  if (isPlaylistFullscreenEdgeMode()) {
    resetSecondaryPlaylistEdgeGuard();
    return ex >= 0 && ex < playlistPanelInitialEdgeTriggerX(PLAYLIST_PANEL_FULLSCREEN_EDGE_TRIGGER_X, eventTarget);
  }
  if (!isSecondaryLeftDisplaySeamGuardActive()) {
    resetSecondaryPlaylistEdgeGuard();
    return ex >= 0 && ex < playlistPanelInitialEdgeTriggerX(PLAYLIST_PANEL_EDGE_TRIGGER_X, eventTarget);
  }
  if (ex < SECONDARY_PLAYLIST_SEAM_CLOSE_X) {
    resetSecondaryPlaylistEdgeGuard();
    return false;
  }
  var inSafeBand = isSecondaryPlaylistSafeBandPoint(ex, ey, H);
  if (!inSafeBand) {
    resetSecondaryPlaylistEdgeGuard();
    return false;
  }
  secondaryPlaylistEdgeGuard.x = ex;
  secondaryPlaylistEdgeGuard.y = ey;
  secondaryPlaylistEdgeGuard.H = H;
  var now = performance.now();
  if (!secondaryPlaylistEdgeGuard.enteredAt) secondaryPlaylistEdgeGuard.enteredAt = now;
  armSecondaryPlaylistEdgeDwell();
  return now - secondaryPlaylistEdgeGuard.enteredAt >= SECONDARY_PLAYLIST_EDGE_DWELL_MS;
}
function playlistPanelExitPadding() {
  return isSecondaryLeftDisplaySeamGuardActive() ? 28 : 46;
}
function playlistPanelFocusPadding() {
  return isSecondaryLeftDisplaySeamGuardActive() ? 30 : 42;
}
function isPlaylistPanelActiveState(panel) {
  return !!(panel && (panel.classList.contains('peek') || panel.classList.contains('show') || isPlaylistPanelOpeningMotion(panel)));
}
function isPlaylistFullscreenEdgeFocusHold(panel, ex, ey, H) {
  if (!isPlaylistFullscreenEdgeMode() || !isPlaylistPanelActiveState(panel)) return false;
  if (isPlaylistPanelBottomControlsConflict(ex, ey, H)) return false;
  return ex >= PLAYLIST_PANEL_FULLSCREEN_EDGE_LEAVE_TOLERANCE_X && ex <= PLAYLIST_PANEL_FULLSCREEN_FOCUS_HOLD_X &&
    ey > playlistPanelEdgeTopGutter(H) && ey < H - playlistPanelEdgeBottomGutter(H);
}
function isFullscreenPlaylistQueueFocusLockedAtEdge(e) {
  var panel = document.getElementById('playlist-panel');
  if (!panel || !isPlaylistPanelActiveState(panel)) return false;
  var ex = e && isFinite(e.clientX) ? e.clientX : 0;
  var ey = e && isFinite(e.clientY) ? e.clientY : (shelfHoverCue && isFinite(shelfHoverCue.y) ? shelfHoverCue.y : innerHeight * 0.5);
  return isPlaylistFullscreenEdgeFocusHold(panel, ex, ey, innerHeight);
}
function playlistPanelTargetRect(panel, currentRect) {
  currentRect = currentRect || { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 };
  if (!panel) return currentRect;
  var width = panel.offsetWidth || currentRect.width || Math.max(0, currentRect.right - currentRect.left);
  var height = panel.offsetHeight || currentRect.height || Math.max(0, currentRect.bottom - currentRect.top);
  var left = isFinite(panel.offsetLeft) ? panel.offsetLeft : currentRect.left;
  var top = isFinite(panel.offsetTop) ? panel.offsetTop : currentRect.top;
  return { left: left, top: top, right: left + width, bottom: top + height, width: width, height: height };
}
function isPlaylistPanelPanelHit(panel, ppRect, ex, ey) {
  if (!isPlaylistPanelActiveState(panel)) return false;
  var targetRect = playlistPanelTargetRect(panel, ppRect);
  return ex >= targetRect.left - 26 && ex <= targetRect.right + 28 && ey >= targetRect.top - 30 && ey <= targetRect.bottom + 30;
}
function isPlaylistPanelBridgeHit(panel, ppRect, ex, ey, H) {
  if (!isPlaylistPanelActiveState(panel)) return false;
  if (isSecondaryLeftDisplaySeamGuardActive() && ex < SECONDARY_PLAYLIST_SEAM_CLOSE_X) return false;
  var targetRect = playlistPanelTargetRect(panel, ppRect);
  var top = Math.max(0, targetRect.top - 34);
  var bottom = Math.min(H, targetRect.bottom + 34);
  if (ey < top || ey > bottom) return false;
  return ex >= 0 && ex <= targetRect.right + playlistPanelFocusPadding();
}
function shouldClosePlaylistPanelFromPointer(ppOn, ex, ppRect, ey, H) {
  if (!ppOn) return false;
  var panel = document.getElementById('playlist-panel');
  var targetRect = playlistPanelTargetRect(panel, ppRect);
  if (isPlaylistPanelBottomControlsConflict(ex, ey, H)) return true;
  if (isPlaylistPanelOpeningMotion(panel) && ex < targetRect.right + playlistPanelFocusPadding()) return false;
  if (isSecondaryLeftDisplaySeamGuardActive() && ex < SECONDARY_PLAYLIST_SEAM_CLOSE_X) return true;
  return ex > targetRect.right + playlistPanelExitPadding();
}
function isPlaylistPanelFocusActive(inTrigger, inPanel, pp, ex, ppRect, ey, H) {
  if (isSecondaryLeftDisplaySeamGuardActive() && ex < SECONDARY_PLAYLIST_SEAM_CLOSE_X) return false;
  return inTrigger || inPanel || isPlaylistFullscreenEdgeFocusHold(pp, ex, ey, H);
}
function shouldHidePlaylistPanelOnWindowLeave(e) {
  var panel = document.getElementById('playlist-panel');
  if (!panel || playlistPanelPinned) return false;
  if (!panel.classList.contains('peek') && !panel.classList.contains('show')) return false;
  if (e && e.relatedTarget) return false;
  if (!e) return true;
  if (isPlaylistFullscreenEdgeMode() && e.clientX <= 2 && e.clientY > playlistPanelEdgeTopGutter(window.innerHeight) && e.clientY < window.innerHeight - playlistPanelEdgeBottomGutter(window.innerHeight)) return false;
  return e.clientX <= 12 || e.clientX >= window.innerWidth - 2 || e.clientY <= 2 || e.clientY >= window.innerHeight - 2;
}
document.addEventListener('mouseleave', function (e) {
  // 触屏设备上 mouseleave 多半是合成事件（手指抬起/切换焦点），不能拿它收歌单面板
  if (isTouchDevice()) return;
  if (shouldHidePlaylistPanelOnWindowLeave(e)) closePlaylistPanelSoft('window-leave');
}, true);
window.addEventListener('blur', function () {
  if (playlistPanelPinned) return;
  setTimeout(function () { closePlaylistPanelSoft('window-blur'); }, 60);
});
/*
 * 【修复】触屏守卫（大肥鱼 2026-11-03）：
 *
 * 问题：面板显隐全由 mousemove 驱动。触屏设备的手指点按会产生一次「合成
 * mousemove」，坐标就是手指位置——于是出现两个手机版特有 bug：
 *   1) 搜索栏：点顶部弹出的瞬间，下一次触点（如键盘/点击别处）的合成事件
 *      坐标不在顶部 66px 内，setPeek(sa,false) 立刻把它收回去；
 *   2) 歌单栏：边缘点按能开，但打开后任何一次合成事件坐标稍偏出面板，
 *      shouldClosePlaylistPanelFromPointer 就把它关了，所以「怎么点都不容易出来」。
 *
 * 方案：touch 事件出现后 1200ms 内，忽略 mousemove 对这两个面板的自动
 * 开/关判定（桌面鼠标完全不受影响——真鼠标移动不会先触发 touchstart）。
 * 触屏的打开入口改为点按：左边缘点一下开歌单，点搜索胶囊/顶部开搜索，
 * 面板开着时点面板外区域才收起。
 */
var touchGuardUntil = 0;
function touchGuardActive() { return performance.now() < touchGuardUntil; }
function armTouchGuard(ms) {
  touchGuardUntil = performance.now() + (ms || 1200);
  if (peekTimers.search) { clearTimeout(peekTimers.search); peekTimers.search = null; }
  if (peekTimers.pl) { clearTimeout(peekTimers.pl); peekTimers.pl = null; }
}
function isTouchDevice() {
  return !!(window.matchMedia && window.matchMedia('(hover: none)').matches);
}
window.addEventListener('touchstart', function () {
  armTouchGuard(1200);
}, { passive: true, capture: true });

/* 触屏：点面板外收起已打开的搜索栏/歌单栏（模拟「点外部关闭」语义）。
   只在触屏设备上生效；桌面靠 mousemove 的 away 判定，保持原行为。 */
window.addEventListener('touchstart', function (e) {
  if (!isTouchDevice() || !e.touches || e.touches.length !== 1) return;
  var t = e.touches[0];
  var x = t.clientX, y = t.clientY;
  // 【触屏打开搜索】点按顶部边缘带（mousemove 里 ey<66 的触屏等价物）→ 打开搜索栏。
  // 手指点顶部会产生合成 mousemove，但会被触屏守卫拦掉，所以必须显式打开；
  // 守卫同时保证它不会被 1.2s 内的合成事件收回去。
  var sa = document.getElementById('search-area');
  if (y <= 88 && sa && !sa.classList.contains('peek')) {
    if (typeof markUploadTipSeen === 'function') markUploadTipSeen();
    setPeek(sa, true, 'search');
    return;
  }
  // 搜索栏：开着、点在它外面（且不在顶部触发带里）→ 收起
  if (sa && sa.classList.contains('peek')) {
    var r = sa.getBoundingClientRect();
    var insideSearch = x >= r.left - 12 && x <= r.right + 12 && y >= r.top - 12 && y <= r.bottom + 12;
    if (!insideSearch && y > 88) setPeek(sa, false, 'search');
  }
  // 歌单栏：开着、点在它外面 → 收起。
  // 左缘触发带的点按交给下面的开关处理器处理：开着时再点左缘 = 收起（切换语义）。
  // 触屏守卫（1.2s）已足够防合成事件误关，不需要额外钉住——
  // 之前的 4 秒钉住会把「有意点外面收起」也拦住，正是「点外面收不了」的原因。
  var pp = document.getElementById('playlist-panel');
  if (pp && isPlaylistPanelActiveState(pp) && !playlistPanelPinned) {
    var pr = pp.getBoundingClientRect();
    // 【误关修复】面板刚弹出 400ms 内不判收起：手指落点和动画中的矩形经常对不上，
    // 表现就是「刚点开就自己关掉」。
    var openedAt = Number(pp.__touchOpenedAt || 0);
    if (openedAt && performance.now() - openedAt < 400) return;
    // 内侧判定放宽到 18/20px：歌单卡片、详情行贴着面板边缘点也不算「点外面」。
    var insideQueue = x >= pr.left - 18 && x <= pr.right + 18 && y >= pr.top - 20 && y <= pr.bottom + 20;
    var inEdgeBand = x < PLAYLIST_PANEL_EDGE_TRIGGER_X;
    if (!insideQueue && !inEdgeBand) {
      setPeek(pp, false, 'pl');
    }
  }
}, { passive: true, capture: true });

// 手机：左边缘点一下展开歌单；开着时再点左缘 = 收起（开关语义）。
// 桌面靠鼠标悬停触发；触屏没有 hover，且主页(empty-home)上的可触发带宽只有 16 布局像素
// （≈6 物理像素），手指根本命中不了，所以这里给触摸单独放宽到默认的贴边宽度。
window.addEventListener('touchstart', function (e) {
  if (!e.touches || e.touches.length !== 1) return;
  var panel = document.getElementById('playlist-panel');
  if (!panel) return;
  var t = e.touches[0];
  var inEdge = isPlaylistEdgeTrigger(t.clientX, t.clientY, innerHeight, null);
  // 开着时点左缘 = 收起（否则处理器因为「面板已激活」直接 return，永远关不上）
  if (isPlaylistPanelActiveState(panel)) {
    // 【误关修复】原来「开着时点左缘 = 收起」，但歌单面板本身就贴在左边（left≈12~24px，
    // 宽 340+），PLAYLIST_PANEL_EDGE_TRIGGER_X=104 意味着面板左侧大半个区域都算「边缘」——
    // 点歌单卡片、点详情行全都落在里面，于是点一下歌单面板就被收起。
    // 现在要求触点真的在**面板矩形之外**才收起；面板内部的点按一律交给内容自己处理。
    var rect = panel.getBoundingClientRect();
    var insidePanel = t.clientX >= rect.left - 8 && t.clientX <= rect.right + 8 &&
      t.clientY >= rect.top - 8 && t.clientY <= rect.bottom + 8;
    if (inEdge && !insidePanel && !playlistPanelPinned) setPeek(panel, false, 'pl');
    return;
  }
  if (!inEdge) return;
  panel.__touchOpenedAt = performance.now();
  // 触屏守卫（armTouchGuard 1.2s）已保证合成 mousemove 不会立刻收起它
  setPeek(panel, true, 'pl');
}, { passive: true, capture: true });
window.addEventListener('mousemove', function (e) {
  var sa = document.getElementById('search-area');
  var fp = document.getElementById('fx-panel');
  var pp = document.getElementById('playlist-panel');
  var ex = e.clientX, ey = e.clientY, W = innerWidth, H = innerHeight;
  updateUserCapsuleAutoHideFromPointer(ex, ey);
  updateFxFabAutoHideFromPointer(ex, ey);
  updateFullscreenDiyPeekFromPointer(ex, ey);
  if (document.body.classList.contains('splash-active')) {
    updateShelfHoverCueFromPointer(null);
    updateShelfCardHoverSelection(null);
    setFocusZone(null);
    return;
  }
  if (immersiveMode) {
    updateShelfHoverCueFromPointer(e);
    updateShelfCardHoverSelection(e);
    updateControlsAutoHideFromPointer(ex, ey);
    var ppOnImm = isPlaylistPanelActiveState(pp);
    var ppRectImm = pp.getBoundingClientRect();
    var inQueueTriggerImm = isPlaylistEdgeTrigger(ex, ey, H, e.target);
    var inQueuePanelImm = isPlaylistPanelPanelHit(pp, ppRectImm, ex, ey);
    var inQueueBridgeImm = isPlaylistPanelBridgeHit(pp, ppRectImm, ex, ey, H);
    if (inQueueTriggerImm || inQueuePanelImm || inQueueBridgeImm) setPeek(pp, true, 'pl');
    // 触屏：合成 mousemove 不参与收起（同非沉浸分支的修复）
    else if (!isTouchDevice() && !touchGuardActive() && shouldClosePlaylistPanelFromPointer(ppOnImm, ex, ppRectImm, ey, H)) setPeek(pp, false, 'pl');
    var shelfCanFocusImm = !!(shelfManager && shelfManager.canInteract && shelfManager.canInteract());
    var newFocusImm = null;
    var queueFocusImm = isPlaylistPanelFocusActive(inQueueTriggerImm, inQueuePanelImm || inQueueBridgeImm, pp, ex, ppRectImm, ey, H);
    var shelfHoverFocusImm = !!(shelfCanFocusImm && isSideShelfFocusHit(e));
    if (queueFocusImm) newFocusImm = 'queue';
    else if (shelfManager && shelfManager.hasOpenContent && shelfManager.hasOpenContent()) newFocusImm = 'shelf-detail';
    else if (shelfHoverFocusImm) newFocusImm = 'shelf-side';
    else if (shelfCanFocusImm && shelfManager.getMode() === 'stage' && ey > H * 0.55) newFocusImm = 'shelf-stage';
    setFocusZone(newFocusImm, newFocusImm === 'queue');
    return;
  }
  updateShelfHoverCueFromPointer(e);
  updateShelfCardHoverSelection(e);
  // 搜索 (上): 顶部 48px 内进入; 已显示时鼠标在 280px 内保留
  var saOn = sa.classList.contains('peek');
  var saRect = sa.getBoundingClientRect();
  var searchFocused = document.activeElement === $input;
  var uploadTip = document.getElementById('upload-tip');
  var uploadTipOpen = !!(uploadTip && uploadTip.classList.contains('show'));
  var uploadImportOpen = typeof isUploadImportActive === 'function' && isUploadImportActive();
  var inSearchPanel = saOn && ex >= saRect.left - 24 && ex <= saRect.right + 24 && ey >= saRect.top - 22 && ey <= saRect.bottom + 42;
  // 【触屏守卫】touch 后 1.2s 内，合成 mousemove 不参与搜索栏/歌单栏的自动开合，
  // 只保留输入框聚焦等真实状态的保持逻辑。桌面无 touch，此分支永远不生效。
  var tg = touchGuardActive();
  if (tg) {
    if (searchFocused && !saOn) setPeek(sa, true, 'search');
    if (saOn && uploadTipOpen) { /* 保留 upload-tip 打开时的显示 */ }
  } else if (ey < 66 || inSearchPanel || searchFocused || uploadTipOpen || uploadImportOpen) setPeek(sa, true, 'search');
  else if ((saOn || isSearchPeekRevealPending()) && !emptyHomeActive) setPeek(sa, false, 'search');
  // 控制台: 右下角触发；一旦面板出现，就按真实面板矩形保留显示
  var fpOn = fp.classList.contains('peek') || fp.classList.contains('show');
  var fpRect = fp.getBoundingClientRect();
  var fab = document.getElementById('fx-fab');
  var fabRect = fab ? fab.getBoundingClientRect() : { left: W, right: W, top: H, bottom: H };
  var inFxPanel = fpOn && ex >= fpRect.left - 24 && ex <= fpRect.right + 24 && ey >= fpRect.top - 24 && ey <= fpRect.bottom + 24;
  var inFxFab = ex >= fabRect.left - 18 && ex <= fabRect.right + 18 && ey >= fabRect.top - 18 && ey <= fabRect.bottom + 18;
  var inFxBridge = fpOn && ex >= Math.min(fpRect.left, fabRect.left) - 18 && ex <= W && ey >= fpRect.bottom - 10 && ey <= fabRect.bottom + 18;
  if (!diyPlayerMode) inFxPanel = inFxFab = inFxBridge = false;
  if (inFxFab || inFxPanel || inFxBridge) setPeek(fp, true, 'fx');
  else if (fpOn) setPeek(fp, false, 'fx');
  // 歌单/队列 DOM 面板只在左侧明确停留时出现，避免和右侧 3D 架抢焦点
  var ppOn = isPlaylistPanelActiveState(pp);
  var ppRect = pp.getBoundingClientRect();
  // 【误关修复 · 2026-11-06】触屏设备上 mousemove 一律不参与歌单栏的开/合：
  // 手指点按、拖动、滚动列表都会产生「合成 mousemove」，坐标只要稍微离开面板右侧，
  // shouldClosePlaylistPanelFromPointer 就把面板关了 —— 这就是「左侧歌单太容易被关闭」。
  // 触屏的开合改由 touchstart 处理器负责（点左缘开、点面板外关），桌面鼠标行为完全不变。
  if (touchGuardActive() || isTouchDevice()) {
    if (searchFocused) { /* 输入态优先 */ }
  } else {
    var inQueueTrigger = isPlaylistEdgeTrigger(ex, ey, H, e.target);
    var inQueuePanel = isPlaylistPanelPanelHit(pp, ppRect, ex, ey);
    var inQueueBridge = isPlaylistPanelBridgeHit(pp, ppRect, ex, ey, H);
    if (inQueueTrigger || inQueuePanel || inQueueBridge) setPeek(pp, true, 'pl');
    else if (shouldClosePlaylistPanelFromPointer(ppOn, ex, ppRect, ey, H)) setPeek(pp, false, 'pl');
  }

  // v8: 镜头跟拍触发判断
  //   - 队列面板 peek 时 → queue focus
  //   - 3D shelf side 模式只在点击展开后 → shelf-side
  //   - 3D shelf stage 模式 + 鼠标在下 35% → shelf-stage
  var shelfCanFocus = !!(shelfManager && shelfManager.canInteract && shelfManager.canInteract());
  if (!shelfCanFocus && !(shelfManager && shelfManager.hasOpenContent && shelfManager.hasOpenContent())) {
    shelfPinnedOpen = false;
    if (fx) fx.shelfPinnedOpen = false;
  }

  var newFocus = null;
  var queueFocusActive = isPlaylistPanelFocusActive(inQueueTrigger, inQueuePanel || inQueueBridge, pp, ex, ppRect, ey, H);
  var shelfHoverFocus = !!(shelfCanFocus && isSideShelfFocusHit(e));
  if (queueFocusActive) {
    newFocus = 'queue';
  } else if (shelfManager && shelfManager.hasOpenContent && shelfManager.hasOpenContent()) {
    newFocus = 'shelf-detail';
  } else if (shelfHoverFocus) {
    newFocus = 'shelf-side';
  } else if (shelfCanFocus && shelfManager.getMode() === 'stage' && ey > H * 0.55) {
    newFocus = 'shelf-stage';
  }
  setFocusZone(newFocus, newFocus === 'queue');
});

// ============================================================
//  启动页 (splash) 控制
