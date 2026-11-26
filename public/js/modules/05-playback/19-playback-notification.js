/* ═══════════════════════════════════════════
 * 媒体通知卡（通知栏 / 锁屏）— Web 侧同步
 *
 * 原生侧 FoliaPlaybackService 已经建好了 MediaSession + 媒体通知
 * （封面 / 歌名 / 歌手 / 播放暂停 / 上一首 / 下一首 / 进度），
 * 但此前 Web 层从未把播放状态推过去，通知卡一直是空的。
 * 这里补上「状态同步 + 通知按钮回调」这条线。
 * ═══════════════════════════════════════════ */
'use strict';

var playNotifState = {
  plugin: null,
  listening: false,
  boundAudio: null,
  lastKey: '',
  lastPlaying: null,
  lastPosition: -1,
  lastPushAt: 0,
  active: false,
};

function playNotifPlugin() {
  var cap = window.Capacitor;
  if (!cap || !cap.isNativePlatform || !cap.isNativePlatform()) return null;
  var plugins = cap.Plugins || null;
  if (!plugins) return null;
  // FoliaCorePlugin 注册名是 FoliaNative（FoliaCore 只是类名）
  return plugins.FoliaNative || plugins.FoliaCore || null;
}

/** playQueue[currentIdx] 优先；本地播放回退 currentLocalSong。 */
function playNotifSong() {
  try {
    if (typeof playQueue !== 'undefined' && Array.isArray(playQueue)
      && typeof currentIdx === 'number' && currentIdx >= 0 && currentIdx < playQueue.length) {
      var q = playQueue[currentIdx];
      if (q) return q;
    }
  } catch (e) { /* 忽略 */ }
  try {
    if (typeof currentLocalSong !== 'undefined' && currentLocalSong) return currentLocalSong;
  } catch (e) { /* 忽略 */ }
  return null;
}

function playNotifSongKey(song) {
  if (!song) return '';
  return [
    song.id || song.songId || song.mid || song.hash || song.localKey || song.localUrl || '',
    song.name || song.title || '',
    song.artist || song.artistName || '',
  ].join('|');
}

function playNotifCoverUrl(song) {
  if (!song) return '';
  var cover = song.customCover || song.cover || song.picUrl || song.albumCover || song.coverUrl || '';
  return String(cover || '').trim();
}

/** 当前播放状态快照。 */
function playNotifSnapshot() {
  var song = playNotifSong();
  var a = (typeof audio !== 'undefined') ? audio : null;
  var playing = !!(a && !a.paused && !a.ended);
  var duration = (a && isFinite(a.duration) && a.duration > 0) ? Math.round(a.duration * 1000) : 0;
  var position = (a && isFinite(a.currentTime) && a.currentTime > 0) ? Math.round(a.currentTime * 1000) : 0;
  return {
    title: song ? String(song.name || song.title || '') : '',
    artist: song ? String(song.artist || song.artistName || '') : '',
    album: song ? String(song.album || song.albumName || '') : '',
    coverUrl: playNotifCoverUrl(song),
    songKey: playNotifSongKey(song),
    playing: playing,
    position: position,
    duration: duration,
  };
}

/**
 * 推送播放状态。
 * 换歌 / 播放状态变化 / 每 3 秒进度更新时推一次，避免每条 timeupdate 都过桥。
 */
function playNotifPush(force) {
  var plugin = playNotifPlugin();
  if (!plugin || typeof plugin.updatePlaybackState !== 'function') return;
  var snap = playNotifSnapshot();
  var media = (typeof audio !== 'undefined') ? audio : null;
  // 还没真正载入过音频（刚启动、只填了队列）时不要弹通知卡
  if (!snap.playing && (!media || !media.src)) {
    if (playNotifState.active && typeof plugin.clearPlaybackState === 'function') {
      playNotifState.active = false;
      playNotifState.lastKey = '';
      playNotifState.lastPlaying = null;
      try { plugin.clearPlaybackState(); } catch (e) { /* 忽略 */ }
    }
    return;
  }
  if (!snap.title && !snap.songKey && !snap.playing) {
    // 没有歌曲：如果之前推送过，通知服务该收掉了
    if (playNotifState.active && typeof plugin.clearPlaybackState === 'function') {
      playNotifState.active = false;
      playNotifState.lastKey = '';
      playNotifState.lastPlaying = null;
      try { plugin.clearPlaybackState(); } catch (e) { /* 忽略 */ }
    }
    return;
  }
  var now = Date.now();
  var sameTrack = snap.songKey === playNotifState.lastKey;
  var samePlaying = snap.playing === playNotifState.lastPlaying;
  var moved = Math.abs(snap.position - playNotifState.lastPosition) >= 1000;
  // 【性能】30s 兜底：play/pause/ended/seeked/换歌全部走 force 直推，不受此限；
  // 非强制的只有 1.5s 保底轮询（现 3s），它只是补漏，30s 足够。桥调用 -90%。
  if (!force && sameTrack && samePlaying && !moved && now - playNotifState.lastPushAt < 30000) return;

  playNotifState.active = true;
  playNotifState.lastKey = snap.songKey;
  playNotifState.lastPlaying = snap.playing;
  playNotifState.lastPosition = snap.position;
  playNotifState.lastPushAt = now;

  try {
    var result = plugin.updatePlaybackState({
      title: snap.title,
      artist: snap.artist,
      album: snap.album,
      coverUrl: snap.coverUrl,
      songKey: snap.songKey,
      playing: snap.playing,
      position: snap.position,
      duration: snap.duration,
    });
    if (result && typeof result.catch === 'function') {
      result.catch(function (e) {
        console.warn('[PlaybackNotification] push failed:', e && e.message || e);
      });
    }
  } catch (e) {
    console.warn('[PlaybackNotification] push failed:', e && e.message || e);
  }
}

/** audio 元素可能被重建，绑定前先比对引用。 */
function playNotifBindAudio() {
  if (typeof audio === 'undefined' || !audio) return;
  if (playNotifState.boundAudio === audio) return;
  playNotifState.boundAudio = audio;
  ['play', 'pause', 'ended', 'loadedmetadata', 'durationchange', 'seeked'].forEach(function (name) {
    try {
      audio.addEventListener(name, function () {
        playNotifPush(name === 'play' || name === 'pause' || name === 'ended' || name === 'seeked');
      });
    } catch (e) { /* 忽略 */ }
  });
}

/** 通知按钮 / 锁屏控制 → 现有播放控制函数。 */
function playNotifInstallListeners() {
  if (playNotifState.listening) return;
  var plugin = playNotifPlugin();
  if (!plugin || typeof plugin.addListener !== 'function') return;
  playNotifState.listening = true;
  try {
    var handle = plugin.addListener('mediaAction', function (event) {
      var action = String(event && event.action || '');
      if (!action) return;
      if (action === 'play') {
        if (typeof audio !== 'undefined' && audio && audio.paused && typeof togglePlay === 'function') togglePlay();
        else playNotifPush(true);
      } else if (action === 'pause') {
        if (typeof audio !== 'undefined' && audio && !audio.paused && typeof togglePlay === 'function') togglePlay();
        else playNotifPush(true);
      } else if (action === 'next') {
        if (typeof nextTrack === 'function') nextTrack(true);
      } else if (action === 'previous') {
        if (typeof prevTrack === 'function') prevTrack(true);
      } else if (action === 'stop') {
        if (typeof audio !== 'undefined' && audio && !audio.paused && typeof togglePlay === 'function') togglePlay();
      } else if (action.indexOf('seek:') === 0) {
        var ms = Number(action.substring(5));
        if (isFinite(ms) && typeof audio !== 'undefined' && audio) {
          try { audio.currentTime = Math.max(0, ms / 1000); } catch (e) { /* 忽略 */ }
          if (typeof updatePlaybackProgressUi === 'function') updatePlaybackProgressUi();
          playNotifPush(true);
        }
      }
    });
    if (handle && typeof handle.catch === 'function') handle.catch(function () { /* 忽略 */ });
  } catch (e) {
    console.warn('[PlaybackNotification] listener failed:', e && e.message || e);
  }
}

/** 包装换歌/暂停等既有同步入口，让通知状态和它们同拍。 */
(function playNotifWrapSync() {
  try {
    var original = window.syncPlaybackStateFromAudioEvent;
    if (typeof original === 'function' && !original.__playNotifWrapped) {
      var wrapped = function () {
        var result = original.apply(this, arguments);
        playNotifPush(true);
        return result;
      };
      wrapped.__playNotifWrapped = true;
      window.syncPlaybackStateFromAudioEvent = wrapped;
    }
  } catch (e) { /* 忽略 */ }
})();

/** 保底轮询：【性能】3s 一次（原 1.5s）。bind/install 都是引用比对早退，
 *  push 走内部节流；所有真实播放事件另有 force 直推，通知永远不会迟。 */
setInterval(function () {
  playNotifBindAudio();
  playNotifInstallListeners();
  playNotifPush(false);
}, 3000);

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', function () { playNotifPush(true); });
} else {
  playNotifPush(true);
}
