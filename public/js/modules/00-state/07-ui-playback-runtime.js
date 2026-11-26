var presetTransition = { active: false, start: -10, duration: 0.92, from: 0, to: 0 };
var controlsAutoHide = readBooleanPreference(CONTROLS_AUTO_HIDE_STORE_KEY, false);
var controlsHovering = false;
var controlsHideTimer = null;
var controlsRevealHoldUntil = 0;
var controlsHandleDimTimer = null;
var controlsLastMoveAt = 0;
var controlsShelfSuppressUntil = 0;
var cursorHideTimer = null;
var CURSOR_HIDE_DELAY = 2500;
var fxPanelPinned = false;
var playlistPanelPinned = readBooleanPreference(PLAYLIST_PANEL_PIN_STORE_KEY, false);
var userCapsuleAutoHide = readBooleanPreference(USER_CAPSULE_AUTO_HIDE_STORE_KEY, false);
// 视觉控制台按钮改为常驻：手机上既没有 hover 也没有物理键，自动隐藏等于再也点不到。
var fxFabAutoHide = false;
var fxFabAutoHideRevealArmed = true;
var closeBehaviorPreference = readCloseBehaviorPreference();
var startupAutoplayPreference = readBooleanPreference(STARTUP_AUTOPLAY_STORE_KEY, false);
var startupFastSkipPreference = readBooleanPreference(STARTUP_FAST_SKIP_STORE_KEY, false);
var startupResumeModePreference = readStartupResumeModePreference();
var startupAutoplayAttempted = false;
var startupAutoplayJobId = 0;
var startupAutoplayRetryTimer = null;
var startupAutoplayAttemptCount = 0;
var startupAutoplayHomeFallbackTried = false;
var startupAutoplayHomeQueuedReason = '';
var startupHomeRevealReady = false;
var startupRestoreHomePending = false;
var pendingPlaybackResumeAt = 0;
var restoredLastPlaybackSnapshot = null;
var lastPlaybackSnapshotSavedAt = 0;
var hotkeySettings = readHotkeySettings();
var immersiveMode = false;
var immersiveState = {
  shelfMode: null,
  shelfPinnedOpen: false,
  lyrics: true,
  controlsAutoHide: true,
  bottomVisible: false
};

// 鼠标 / 摄像头视差
var pointerParallax = { x: 0, y: 0 };
var pointerTarget = { x: 0, y: 0 };
var headParallax = { x: 0, y: 0, active: false };
var headNeutral = null;
