// ============================================================
function raycasterFromPointerEvent(e) {
  var mx = (e.clientX / innerWidth) * 2 - 1;
  var my = -(e.clientY / innerHeight) * 2 + 1;
  var rc = new THREE.Raycaster();
  rc.setFromCamera(new THREE.Vector2(mx, my), camera);
  return rc;
}
function pointerCardHit(rc, e, screenPad) {
  if (!shelfManager) return null;
  return shelfManager.raycastCards(rc) || (shelfManager.pickCardAtScreen && shelfManager.pickCardAtScreen(e.clientX, e.clientY, screenPad));
}
function isSideShelfFocusHit(e) {
  if (!e || !shelfManager || !shelfManager.getMode || shelfManager.getMode() !== 'side') return false;
  if (typeof shelfPlaybackSwitchGuardActive === 'function' && shelfPlaybackSwitchGuardActive()) return false;
  if (shelfPinnedOpen) return true;
  if (shelfAlwaysVisible()) return !!pointerCardHit(raycasterFromPointerEvent(e), e, 18);
  if (!shelfAutoHiddenInputReady()) return false;
  if (shelfVisibility > 0.34 && (isShelfClickZone(e) || isShelfPreviewUseZone(e))) return true;
  return !!(shelfPreviewIsVisible() && pointerCardHit(raycasterFromPointerEvent(e), e, 24));
}
function updateShelfCardHoverSelection(e) {
  if (!shelfManager || !shelfManager.clearSelected || !shelfManager.setSelected) return;
  if (typeof shelfPlaybackSwitchGuardActive === 'function' && shelfPlaybackSwitchGuardActive()) {
    shelfManager.clearSelected();
    return;
  }
  if (!e || document.body.classList.contains('splash-active') || isPointerOverUi(e)) {
    shelfManager.clearSelected();
    return;
  }
  var mode = shelfManager.getMode && shelfManager.getMode();
  if (!mode || mode === 'off') {
    shelfManager.clearSelected();
    return;
  }
  if (shelfManager.hasOpenContent && shelfManager.hasOpenContent()) {
    shelfManager.clearSelected();
    return;
  }
  var canInteract = shelfManager.canInteract && shelfManager.canInteract();
  if (!canInteract) {
    shelfManager.clearSelected();
    return;
  }
  if (mode === 'side') {
    if (!shelfPinnedOpen && shelfAlwaysVisible()) {
      var alwaysHit = pointerCardHit(raycasterFromPointerEvent(e), e, 18);
      if (alwaysHit && alwaysHit.card) shelfManager.setSelected(alwaysHit.card.index);
      else shelfManager.clearSelected();
      return;
    }
    var sideUsable = shelfPinnedOpen || shelfAutoHiddenInputReady();
    if (!sideUsable) {
      shelfManager.clearSelected();
      return;
    }
  } else if (mode !== 'stage') {
    shelfManager.clearSelected();
    return;
  }
  var hit = pointerCardHit(raycasterFromPointerEvent(e), e);
  if (hit && hit.card) shelfManager.setSelected(hit.card.index);
  else shelfManager.clearSelected();
}
function isShelfPlaylistPlayHit(hit) {
  if (!hit || !hit.card || !hit.uv || !hit.card.item || hit.card.item.type !== 'playlist') return false;
  return hit.uv.x >= 0.49 && hit.uv.x <= 0.72 && hit.uv.y >= 0.13 && hit.uv.y <= 0.42;
}
renderer.domElement.addEventListener('click', function (e) {
  if (!shelfManager || shelfManager.getMode() === 'off') return;
  if (typeof shelfPlaybackSwitchGuardActive === 'function' && shelfPlaybackSwitchGuardActive()) return;
  if (document.body.classList.contains('splash-active')) return;
  if (isPointerOverUi(e)) return;
  if (mouseDownAt.hadDrag) { mouseDownAt.hadDrag = false; return; }

  var rc = raycasterFromPointerEvent(e);
  var mode = shelfManager.getMode();
  var canInteract = shelfManager.canInteract && shelfManager.canInteract();

  // 优先二级内容框
  if (shelfManager.hasOpenContent()) {
    var cl = shelfManager.getContentList && shelfManager.getContentList();
    if (cl) {
      var rowHit = cl.raycastRows(rc);
      if (!rowHit && cl.pickRowAtScreen) rowHit = cl.pickRowAtScreen(e.clientX, e.clientY);
      if (rowHit) {
        if (cl.pulseRow) cl.pulseRow(rowHit.row, 0.72);
        var selectedRow = Math.abs(rowHit.row.index - cl.getCenterIdx()) < 0.5;
        var rowIsPodcastRadio = !!(rowHit.row.song && rowHit.row.song.type === 'podcast-radio');
        var hitLikeButton = rowHit.uv && rowHit.uv.x > 0.61 && rowHit.uv.x < 0.68 && rowHit.uv.y > 0.20 && rowHit.uv.y < 0.82;
        var hitCollectButton = rowHit.uv && rowHit.uv.x >= 0.68 && rowHit.uv.x < 0.75 && rowHit.uv.y > 0.20 && rowHit.uv.y < 0.82;
        var hitNextButton = rowHit.uv && rowHit.uv.x >= 0.75 && rowHit.uv.x < 0.82 && rowHit.uv.y > 0.20 && rowHit.uv.y < 0.82;
        var hitPlayButton = rowHit.uv && rowHit.uv.x >= 0.82 && rowHit.uv.y > 0.20 && rowHit.uv.y < 0.82;
        var screenAction = (!rowHit.uv && cl.rowActionAtScreen) ? cl.rowActionAtScreen(rowHit.row, e.clientX, e.clientY) : null;
        hitLikeButton = hitLikeButton || screenAction === 'like';
        hitCollectButton = hitCollectButton || screenAction === 'collect';
        hitNextButton = hitNextButton || screenAction === 'next';
        hitPlayButton = hitPlayButton || screenAction === 'play';
        // 详情页支持直接点歌曲播放；红心/收藏按钮仍然保留原动作。
        if (selectedRow && !rowIsPodcastRadio && hitLikeButton) {
          toggleLikeDetailSong(rowHit.row.song);
        } else if (selectedRow && !rowIsPodcastRadio && hitCollectButton) {
          collectDetailSong(rowHit.row.song);
        } else if (selectedRow && !rowIsPodcastRadio && hitNextButton) {
          queueDetailSongNext(rowHit.row.song);
        } else if ((rowHit.row.song && rowHit.row.song.id) || rowIsPodcastRadio || (selectedRow && hitPlayButton)) {
          cl.playRow(rowHit.row);
        } else {
          // 滚到这行
          cl.scrollBy(rowHit.row.index - cl.getCenterIdx());
        }
        return;
      }
      var returnHit = shelfManager.raycastCards(rc);
      safeShelfCloseContent('shelf-card-return');
      if (mode === 'side') setShelfPinnedOpen(true, true);
      if (returnHit && returnHit.card) {
        shelfManager.scrollBy(returnHit.card.index - shelfManager.getCenterIdx());
      }
      return;
    }
  }

  // 一级卡片
  var hit = pointerCardHit(rc, e, mode === 'side' && !shelfPinnedOpen && shelfAlwaysVisible() ? 18 : undefined);
  if (mode === 'side' && !shelfPinnedOpen && !canUseSideShelfWithoutPinnedOpen()) return;

  if (hit) {
    if (mode === 'side') setShelfPinnedOpen(true, true);
    var idx = hit.card.index;
    if (Math.abs(idx - shelfManager.getCenterIdx()) < 0.5) {
      if (isShelfPlaylistPlayHit(hit) && shelfManager.playPlaylistAt && shelfManager.playPlaylistAt(idx)) return;
      shelfManager.openContent(idx);
    } else {
      shelfManager.scrollBy(idx - shelfManager.getCenterIdx());
    }
  } else if (mode === 'side' && shelfPinnedOpen) {
    setShelfPinnedOpen(false, true);
  }
});

renderer.domElement.addEventListener('contextmenu', function (e) {
  if (document.body.classList.contains('splash-active')) return;
  if (typeof shelfPlaybackSwitchGuardActive === 'function' && shelfPlaybackSwitchGuardActive()) return;
  if (isPointerOverUi(e)) return;
  e.preventDefault();
  e.stopPropagation();
  if (typeof suppressBottomControlsForShelf === 'function') suppressBottomControlsForShelf(980);
  if (!shelfManager) return;
  var mode = shelfManager.getMode && shelfManager.getMode();
  if (mode === 'off') {
    setShelfMode('side');
    mode = 'side';
  }
  if (mode !== 'side') return;
  if (shelfManager.hasOpenContent && shelfManager.hasOpenContent()) {
    var rc = raycasterFromPointerEvent(e);
    var cl = shelfManager.getContentList && shelfManager.getContentList();
    var rowHit = cl && cl.raycastRows ? cl.raycastRows(rc) : null;
    if (rowHit && rowHit.row && rowHit.row.song && rowHit.row.song.id && rowHit.row.song.type !== 'podcast-radio') {
      if (cl.pulseRow) cl.pulseRow(rowHit.row, 0.88);
      queueDetailSongNext(rowHit.row.song);
      return;
    }
    safeShelfCloseContent('shelf-context-toggle');
    setShelfPinnedOpen(true, true);
    return;
  }
  setShelfPinnedOpen(!shelfPinnedOpen, true);
  if (!shelfPinnedOpen && typeof setFocusZone === 'function') setFocusZone(null, true);
});

// 滚轮: 在真实卡片或右侧窄热区内滚卡片; 否则保留给封面粒子/视角
//   side 模式: 常驻不再用半屏预览区接管滚轮
//   stage 模式: 鼠标 y > 60% 屏幕高
//   shift + wheel: 强制滚卡片
var wheelOverShelf = false;
renderer.domElement.addEventListener('wheel', function (e) {
  if (isPointerOverUi(e)) return;
  if (!shelfManager || shelfManager.getMode() === 'off') return;
  if (typeof shelfPlaybackSwitchGuardActive === 'function' && shelfPlaybackSwitchGuardActive()) return;
  markRenderInteraction('shelf-wheel', 900);
  var rc = raycasterFromPointerEvent(e);
  // 二级框打开时, 只有真正命中详情行才接管滚轮
  if (shelfManager.hasOpenContent()) {
    var cl = shelfManager.getContentList();
    if (cl) {
      var rowHit = cl.raycastRows(rc);
      var panelHit = !rowHit && cl.raycastPanel ? cl.raycastPanel(rc) : null;
      var panelScreenHit = !rowHit && !panelHit && cl.screenContainsPanel ? cl.screenContainsPanel(e.clientX, e.clientY) : false;
      if (!rowHit && !panelHit && !panelScreenHit) return;
      e.preventDefault(); e.stopImmediatePropagation();
      cl.scrollBy(e.deltaY > 0 ? 1 : -1);
      return;
    }
  }
  var mode = shelfManager.getMode();
  var inShelfArea = false;
  var canScrollShelf = shelfManager.canInteract && shelfManager.canInteract();
  var shelfPreviewActive = shelfAutoHiddenInputReady();
  var cardWheelHit = canScrollShelf ? pointerCardHit(rc, e, mode === 'side' && !shelfPinnedOpen && shelfAlwaysVisible() ? 18 : undefined) : null;
  if (canScrollShelf && e.shiftKey && (mode !== 'side' || shelfPinnedOpen || shelfPreviewActive || shelfAlwaysVisible())) inShelfArea = true;
  else if (canScrollShelf && mode === 'side') {
    if (shelfPinnedOpen) inShelfArea = isShelfWheelZone(e) || !!cardWheelHit;
    else if (shelfAlwaysVisible()) inShelfArea = !!cardWheelHit;
    else if (shelfPreviewActive) inShelfArea = isShelfWheelZone(e) || !!cardWheelHit;
  }
  else if (canScrollShelf && mode === 'stage' && cardWheelHit) inShelfArea = true;
  if (inShelfArea) {
    e.preventDefault();
    e.stopImmediatePropagation();
    shelfManager.scrollBy(e.deltaY > 0 ? 1 : -1);
  }
}, { passive: false, capture: true });

// ------------------------------------------------------------
//  触屏滚动：手机没有滚轮，单指上下滑动画成和滚轮同样的步进
//   - 歌单架详情打开时：滑动详情列表翻歌
//   - 否则：滑动歌单架本身切换卡片
//  手指一动就设 __mrShelfTouchScroll，让封面旋转让位，避免同一根手指又去转粒子。
// ------------------------------------------------------------
var shelfTouchScroll = { tracking: false, id: null, lastY: 0, acc: 0, overContent: false };
var SHELF_TOUCH_STEP_PX = 46;

function shelfTouchOverContent(t) {
  if (!shelfManager || !shelfManager.hasOpenContent || !shelfManager.hasOpenContent()) return false;
  var cl = shelfManager.getContentList && shelfManager.getContentList();
  if (!cl) return false;
  if (typeof cl.screenContainsPanel === 'function' && cl.screenContainsPanel(t.clientX, t.clientY)) return true;
  var rc = raycasterFromPointerEvent(t);
  if (cl.raycastRows && cl.raycastRows(rc)) return true;
  if (cl.raycastPanel && cl.raycastPanel(rc)) return true;
  return false;
}
function shelfTouchOverShelf(t) {
  if (!shelfManager || shelfManager.getMode() === 'off') return false;
  if (!(shelfManager.canInteract && shelfManager.canInteract())) return false;
  if (typeof isShelfWheelZone === 'function' && isShelfWheelZone(t)) return true;
  var rc = raycasterFromPointerEvent(t);
  return !!pointerCardHit(rc, t, shelfManager.getMode() === 'side' && !shelfPinnedOpen ? 18 : undefined);
}
function endShelfTouchScroll() {
  shelfTouchScroll.tracking = false;
  shelfTouchScroll.id = null;
  shelfTouchScroll.acc = 0;
  window.__mrShelfTouchScroll = false;
}
renderer.domElement.addEventListener('touchstart', function (e) {
  endShelfTouchScroll();
  if (!e.touches || e.touches.length !== 1) return;
  var t = e.touches[0];
  if (isPointerOverUi(t)) return;
  var overContent = shelfTouchOverContent(t);
  if (!overContent && !shelfTouchOverShelf(t)) return;
  shelfTouchScroll.tracking = true;
  shelfTouchScroll.id = t.identifier;
  shelfTouchScroll.lastY = t.clientY;
  shelfTouchScroll.acc = 0;
  shelfTouchScroll.overContent = overContent;
  window.__mrShelfTouchScroll = true;
  markRenderInteraction('shelf-touch', 900);
}, { passive: true, capture: true });
renderer.domElement.addEventListener('touchmove', function (e) {
  if (!shelfTouchScroll.tracking) return;
  var t = null;
  for (var i = 0; i < e.touches.length; i++) {
    if (e.touches[i].identifier === shelfTouchScroll.id) { t = e.touches[i]; break; }
  }
  if (!t) return;
  shelfTouchScroll.acc += shelfTouchScroll.lastY - t.clientY; // 手指上滑 → 下一项
  shelfTouchScroll.lastY = t.clientY;
  while (Math.abs(shelfTouchScroll.acc) >= SHELF_TOUCH_STEP_PX) {
    var dir = shelfTouchScroll.acc > 0 ? 1 : -1;
    shelfTouchScroll.acc -= dir * SHELF_TOUCH_STEP_PX;
    if (shelfTouchScroll.overContent) {
      var cl = shelfManager && shelfManager.getContentList && shelfManager.getContentList();
      if (cl) cl.scrollBy(dir);
    } else if (shelfManager) {
      shelfManager.scrollBy(dir);
    }
  }
}, { passive: true, capture: true });
renderer.domElement.addEventListener('touchend', endShelfTouchScroll, { passive: true, capture: true });
renderer.domElement.addEventListener('touchcancel', endShelfTouchScroll, { passive: true, capture: true });

// 键盘 / 全局事件
