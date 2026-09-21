// ============================================================
function queueItemKey(song) {
  if (!song) return '';
  if (song.provider === 'spotify' || song.source === 'spotify' || song.type === 'spotify' || song.spotifyId || song.spotifyUri) return 'spotify:' + (song.spotifyId || song.id || song.spotifyUri || song.uri || (song.name + '|' + song.artist));
  if (song.provider === 'qq' || song.source === 'qq' || song.type === 'qq') return 'qq:' + (song.mid || song.songmid || song.id || (song.name + '|' + song.artist));
  if (song.provider === 'kugou' || song.source === 'kugou' || song.type === 'kugou' || song.hash || song.audioHash) return 'kugou:' + (song.hash || song.fileHash || song.audioHash || song.id || (song.name + '|' + song.artist));
  if (song.type === 'podcast' && song.programId) return 'podcast:' + song.programId;
  if (song.localKey) return 'local:' + song.localKey;
  if (song.id != null && song.id !== '') return 'song:' + song.id;
  return String(song.name || '') + '|' + String(song.artist || '');
}
function playbackRestoreSongSnapshot(song) {
  song = song || {};
  var snap = {};
  [
    'provider', 'source', 'type', 'id', 'mid', 'songmid', 'mediaMid', 'media_mid', 'qqId',
    'spotifyId', 'spotifyUri', 'spotifyUrl', 'uri', 'albumUri',
    'hash', 'fileHash', 'audioHash', 'albumId', 'album_id', 'albumMid', 'albummid', 'albumAudioId', 'album_audio_id', 'mixSongId', 'hqHash', 'sqHash', 'resHash',
    'name', 'title', 'artist', 'album', 'cover', 'duration', 'durationMs', 'dt', 'fee',
    'playable', 'playbackMode', 'recommendationSource', 'programId', 'radioId', 'radioName', 'localKey', 'localFileId'
  ].forEach(function (key) {
    if (song[key] != null && song[key] !== '') snap[key] = song[key];
  });
  if (Array.isArray(song.artists)) snap.artists = song.artists.slice(0, 6);
  if (song.type === 'local' || song.localKey) snap.localMissing = true;
  return snap;
}
function readLastPlaybackSnapshot() {
  try {
    var raw = localStorage.getItem(LAST_PLAYBACK_STORE_KEY);
    if (!raw) return null;
    var data = JSON.parse(raw);
    if (!data || data.version !== 1 || !data.current) return null;
    return data;
  } catch (e) {
    return null;
  }
}
function saveLastPlaybackSnapshot(force, reason) {
  var now = Date.now();
  if (!force && now - lastPlaybackSnapshotSavedAt < 2500) return;
  var song = currentCoverSong();
  if (!song) return;
  if (!audio && restoredLastPlaybackSnapshot && restoredLastPlaybackSnapshot.current && queueItemKey(song) === queueItemKey(restoredLastPlaybackSnapshot.current)) return;
  var durationSec = getPlaybackDurationSeconds();
  var currentSec = getPlaybackCurrentSeconds();
  if (durationSec > 0 && currentSec > durationSec) currentSec = durationSec;
  if (durationSec > 0 && ((audio && audio.ended) || currentSec >= durationSec - 3)) currentSec = 0;
  var queue = Array.isArray(playQueue) ? playQueue.slice(0, 120).map(playbackRestoreSongSnapshot).filter(function (item) { return item && (item.id || item.mid || item.localKey || item.name); }) : [];
  var payload = {
    version: 1,
    savedAt: now,
    reason: reason || '',
    currentIdx: currentIdx,
    currentTime: Math.max(0, Number(currentSec) || 0),
    duration: Math.max(0, Number(durationSec) || playbackDurationFromSong(song) || 0),
    playing: !!(audio && !audio.paused && !audio.ended),
    current: playbackRestoreSongSnapshot(song),
    queue: queue
  };
  try {
    localStorage.setItem(LAST_PLAYBACK_STORE_KEY, JSON.stringify(payload));
    lastPlaybackSnapshotSavedAt = now;
  } catch (e) { }
}
function applyRestoredPlaybackProgressUi(snapshot) {
  snapshot = snapshot || {};
  var durationSec = Number(snapshot.duration) || playbackDurationFromSong(snapshot.current) || 0;
  var currentSec = Math.max(0, Number(snapshot.currentTime) || 0);
  if (durationSec > 0 && currentSec > durationSec) currentSec = durationSec;
  setProgressVisual(durationSec > 0 ? (currentSec / durationSec * 100) : 0);
  var timeDisplay = document.getElementById('time-display');
  if (timeDisplay) timeDisplay.textContent = formatProgramTime(currentSec) + ' / ' + (durationSec > 0 ? formatProgramTime(durationSec) : '0:00');
}
function restoreLastPlaybackSnapshot() {
  if (restoredLastPlaybackSnapshot) return false;
  var snapshot = readLastPlaybackSnapshot();
  console.log('[AutoplayDiag] restoreLastPlaybackSnapshot: found=' + !!(snapshot && snapshot.current) + ' queue=' + (snapshot && Array.isArray(snapshot.queue) ? snapshot.queue.length : '-') + ' current.type=' + (snapshot && snapshot.current ? snapshot.current.type : '-') + ' localKey=' + (snapshot && snapshot.current ? (snapshot.current.localKey || snapshot.current.localFileId || '') : '-') + ' curIdx=' + (snapshot && snapshot.currentIdx));
  if (!snapshot || !snapshot.current) return false;
  var current = hydrateCustomCover(Object.assign({}, snapshot.current));
  var isLocal = current.type === 'local' || !!current.localKey || current.localMissing;
  restoredLastPlaybackSnapshot = snapshot;
  startupRestoreHomePending = !startupAutoplayPreference;
  pendingPlaybackResumeAt = startupResumeSecondsFromSnapshot(snapshot);
  if (isLocal) {
    currentLocalSong = current;
    currentIdx = -1;
    playQueue = [];
  } else {
    var queue = Array.isArray(snapshot.queue) ? snapshot.queue.map(function (song) { return hydrateCustomCover(Object.assign({}, song)); }).filter(function (song) { return song && (song.id || song.mid || song.name); }) : [];
    if (!queue.length) queue = [current];
    var idx = Math.max(0, Math.min(queue.length - 1, Number(snapshot.currentIdx) || 0));
    if (!queue[idx] || queueItemKey(queue[idx]) !== queueItemKey(current)) {
      var found = -1;
      for (var i = 0; i < queue.length; i++) {
        if (queueItemKey(queue[i]) === queueItemKey(current)) { found = i; break; }
      }
      if (found >= 0) idx = found;
      else { queue.unshift(current); idx = 0; }
    }
    playQueue = queue;
    currentIdx = idx;
    currentLocalSong = null;
  }
  var shownSong = currentCoverSong() || current;
  if (shownSong) {
    updateControlTrackInfo(shownSong);
    var titleEl = document.getElementById('thumb-title');
    var artistEl = document.getElementById('thumb-artist');
    if (titleEl) titleEl.textContent = shownSong.name || shownSong.title || '上一首';
    if (artistEl) artistEl.textContent = isLocal ? '本地文件 · 需要重新导入' : (shownSong.artist || songSourceLabel(shownSong));
    var thumbWrap = document.getElementById('thumb-wrap');
    if (thumbWrap) thumbWrap.classList.add('visible');
    if (!isLocal && shownSong.cover) {
      setTimeout(function () {
        if (!audio && currentIdx >= 0 && playQueue[currentIdx] && queueItemKey(playQueue[currentIdx]) === queueItemKey(shownSong)) {
          loadCoverFromUrl(songCoverSrc(shownSong, 400), { deferHeavy: true, delay: 120, timeout: 700 });
        }
      }, 180);
    }
  }
  applyRestoredPlaybackProgressUi(Object.assign({}, snapshot, { currentTime: pendingPlaybackResumeAt }));
  showRestoredPlaybackControls('restore');
  console.log('[AutoplayDiag] restore DONE: global currentIdx=' + currentIdx + ' playQueue=' + playQueue.length + ' isLocalBranch=' + isLocal);
  return true;
}
function repairStartupAutoplayCurrentIndex() {
  if (!restoredLastPlaybackSnapshot || !Array.isArray(playQueue) || !playQueue.length) return;
  var snapshotCurrent = restoredLastPlaybackSnapshot.current;
  if (snapshotCurrent) {
    var targetKey = queueItemKey(snapshotCurrent);
    for (var i = 0; i < playQueue.length; i++) {
      if (playQueue[i] && queueItemKey(playQueue[i]) === targetKey) {
        currentIdx = i;
        return;
      }
    }
  }
  currentIdx = 0;
}
function canStartupAutoplayRestoredSnapshot() {
  console.log('[AutoplayDiag] canStartupAutoplayRestoredSnapshot: pref=' + !!startupAutoplayPreference + ' attempted=' + !!startupAutoplayAttempted + ' restored=' + !!restoredLastPlaybackSnapshot + ' queue=' + (Array.isArray(playQueue) ? playQueue.length : '-') + ' idx=' + currentIdx);
  if (!startupAutoplayPreference || startupAutoplayAttempted) return false;
  if (!restoredLastPlaybackSnapshot) return false;
  if (currentLocalSong && (!Array.isArray(playQueue) || !playQueue.length)) return false;
  if (!(Array.isArray(playQueue) && currentIdx >= 0 && currentIdx < playQueue.length && playQueue[currentIdx])) {
    repairStartupAutoplayCurrentIndex();
    console.log('[AutoplayDiag] repairStartupAutoplayCurrentIndex -> idx=' + currentIdx + ' queue=' + playQueue.length);
  }
  return !!(Array.isArray(playQueue) && currentIdx >= 0 && currentIdx < playQueue.length && playQueue[currentIdx]);
}
function isStartupAutoplayPlaying() {
  return !!(audio && audio.src && !audio.paused && !audio.ended);
}
function clearStartupAutoplayRetryTimer() {
  if (startupAutoplayRetryTimer) {
    clearTimeout(startupAutoplayRetryTimer);
    startupAutoplayRetryTimer = null;
  }
}
function restoreHomeAfterStartupAutoplayFallback() {
  startupRestoreHomePending = true;
  forcePlaybackControlsInteractive();
  showRestoredPlaybackControls('restore');
  updateEmptyHomeVisibility({ forceLoad: true });
}
function handleStartupAutoplayUnavailable(reason) {
  console.log('[AutoplayDiag] handleStartupAutoplayUnavailable reason=' + reason + ' hasLogin=' + (typeof hasAnyPlatformLogin === 'function' ? hasAnyPlatformLogin() : 'n/a'));
  startupAutoplayJobId += 1;
  startupAutoplayAttemptCount = 0;
  startupAutoplayHomeFallbackTried = false;
  if ((reason === 'local-missing' || reason === 'queue-empty') && tryStartupAutoplayHomeFallback(startupAutoplayJobId)) return;
  restoreHomeAfterStartupAutoplayFallback();
  if (reason === 'queue-empty' && !startupAutoplayDiagnosticNotified) {
    // 用户侧看不到任何动静时给出一次性诊断提示, 避免"为什么没自动播放"困惑。
    startupAutoplayDiagnosticNotified = true;
    setTimeout(function () {
      if (typeof showToast === 'function') {
        showToast('启动自动播放：没有找到播放记录' + (typeof hasAnyPlatformLogin === 'function' && hasAnyPlatformLogin() ? '' : '，且未登录账号'));
      }
    }, 900);
  }
}
function startupAutoplayUnavailableReason() {
  if (!startupAutoplayPreference || startupAutoplayAttempted) return '';
  if (!restoredLastPlaybackSnapshot) return 'queue-empty';
  if (currentLocalSong && (!Array.isArray(playQueue) || !playQueue.length)) return 'local-missing';
  if (!(Array.isArray(playQueue) && currentIdx >= 0 && playQueue[currentIdx])) return 'queue-empty';
  return '';
}
function startupAutoplayRetryDelay(attempt) {
  // 覆盖本地 server 慢启动(2-6s 甚至更久)场景: 最长约 3 分钟窗口内持续重试,
  // 只要 server 一旦就绪或登录状态恢复, 重试即可成功接上。
  var delays = [80, 260, 620, 1100, 1800, 2800, 4200, 6200, 8800, 12000, 16000, 22000, 30000, 42000, 60000];
  return delays[Math.min(delays.length - 1, Math.max(0, attempt))];
}
function startupAutoplayNextPlayableIndex() {
  if (!Array.isArray(playQueue) || !playQueue.length) return -1;
  if (currentIdx < 0 || currentIdx >= playQueue.length) return 0;
  if (isQueueItemRecentlyPlaybackFailed(currentIdx) && playQueue.length > 1) {
    var nextIdx = nextUnblockedQueueIndex(currentIdx);
    if (nextIdx >= 0) return nextIdx;
  }
  return currentIdx;
}
function scheduleStartupAutoplayRetry(jobId, reason, delay) {
  clearStartupAutoplayRetryTimer();
  if (!startupAutoplayPreference || jobId !== startupAutoplayJobId) return false;
  if (isStartupAutoplayPlaying()) return true;
  startupAutoplayRetryTimer = setTimeout(function () {
    startupAutoplayRetryTimer = null;
    runStartupAutoplayAttempt(jobId, reason || 'retry');
  }, delay == null ? startupAutoplayRetryDelay(startupAutoplayAttemptCount) : delay);
  return true;
}
function tryStartupAutoplayHomeFallback(jobId) {
  console.log('[AutoplayDiag] tryStartupAutoplayHomeFallback jobId=' + jobId + ' fallbackTried=' + !!startupAutoplayHomeFallbackTried + ' hasLogin=' + (typeof hasAnyPlatformLogin === 'function' ? hasAnyPlatformLogin() : 'n/a'));
  if (startupAutoplayHomeFallbackTried) return false;
  if (!hasAnyPlatformLogin()) {
    // 登录状态查询在启动早期拿到的是降级"未登录"响应, 并非真实状态;
    // 标记为"登录就绪后需重试", 由 retryStartupAutoplayAfterLoginReady 唤醒。
    startupAutoplayRetryOnLoginReady = true;
    return false;
  }
  startupAutoplayHomeFallbackTried = true;
  Promise.resolve().then(async function () {
    try {
      await waitForHomeDiscoverIdle(1800);
      if (!homeDiscoverState.loaded || (!homeDiscoverState.songs.length && !homeDiscoverState.loading)) {
        await loadHomeDiscover(true);
      }
      if (jobId !== startupAutoplayJobId || !startupAutoplayPreference || isStartupAutoplayPlaying()) return;
      if (!homeDiscoverState.songs.length) {
        startupAutoplayRetryOnLoginReady = true;
        restoreHomeAfterStartupAutoplayFallback();
        return;
      }
      playQueue = homeDiscoverState.songs.map(cloneSong);
      currentIdx = 0;
      currentLocalSong = null;
      pendingPlaybackResumeAt = 0;
      startupRestoreHomePending = false;
      startupAutoplayAttemptCount = 0;
      safeRenderQueuePanel('startup-autoplay-home-fallback', { scrollCurrent: miniQueueOpen });
      safeShelfRebuild('startup-autoplay-home-fallback', true);
      forcePlaybackControlsInteractive();
      await playQueueAt(0, { manual: false, startupAutoplay: true });
      setTimeout(function () {
        if (jobId !== startupAutoplayJobId || !startupAutoplayPreference) return;
        if (isStartupAutoplayPlaying()) finishStartupAutoplayJob(true);
        else scheduleStartupAutoplayRetry(jobId, 'home-fallback-retry', 420);
      }, 360);
    } catch (e) {
      console.warn('[StartupAutoplayHomeFallback]', e);
      if (jobId === startupAutoplayJobId && startupAutoplayPreference && !isStartupAutoplayPlaying()) {
        startupAutoplayRetryOnLoginReady = true;
        restoreHomeAfterStartupAutoplayFallback();
      }
    }
  });
  return true;
}
function finishStartupAutoplayJob(success) {
  console.log('[AutoplayDiag] finishStartupAutoplayJob success=' + success);
  clearStartupAutoplayRetryTimer();
  if (success) {
    startupRestoreHomePending = false;
    forcePlaybackControlsInteractive();
    return;
  }
  // 失败终点: 等待下一次登录状态刷新 / server-ready 时重新唤醒自动播放。
  startupAutoplayRetryOnLoginReady = true;
  if (tryStartupAutoplayHomeFallback(startupAutoplayJobId)) return;
  restoreHomeAfterStartupAutoplayFallback();
}
// 登录状态查询 / 本地 server 就绪后调用: 若自动播放曾因"未登录或降级响应"放弃,
// 在此重置尝试标记并重新调度, 让"重启后自动播放"在真实条件就绪时接上。
function retryStartupAutoplayAfterLoginReady() {
  console.log('[AutoplayDiag] retryStartupAutoplayAfterLoginReady pref=' + !!startupAutoplayPreference + ' attempted=' + !!startupAutoplayAttempted + ' retryOnLoginReady=' + !!startupAutoplayRetryOnLoginReady + ' restored=' + !!restoredLastPlaybackSnapshot);
  if (!startupAutoplayPreference) return false;
  if (isStartupAutoplayPlaying()) {
    finishStartupAutoplayJob(true);
    return true;
  }
  if (startupAutoplayAttempted && !startupAutoplayRetryOnLoginReady) return false;
  if (!startupAutoplayAttempted && restoredLastPlaybackSnapshot) {
    startupAutoplayRetryOnLoginReady = true;
  }
  if (!startupAutoplayAttempted && !startupAutoplayRetryOnLoginReady) return false;
  startupAutoplayRetryOnLoginReady = false;
  startupAutoplayAttempted = false;
  startupAutoplayHomeFallbackTried = false;
  startupAutoplayDiagnosticNotified = false;
  return queueStartupAutoplayAfterHomeReveal('login-ready');
}
function runStartupAutoplayAttempt(jobId, reason) {
  console.log('[AutoplayDiag] runStartupAutoplayAttempt jobId=' + jobId + ' reason=' + reason + ' attempt=' + startupAutoplayAttemptCount + ' restored=' + !!restoredLastPlaybackSnapshot);
  if (!startupAutoplayPreference || jobId !== startupAutoplayJobId) return false;
  if (!restoredLastPlaybackSnapshot) return false;
  if (startupAutoplayAttemptRunning) return true;
  if (isStartupAutoplayPlaying()) {
    finishStartupAutoplayJob(true);
    return true;
  }
  var idx = startupAutoplayNextPlayableIndex();
  if (idx < 0) {
    finishStartupAutoplayJob(false);
    return false;
  }
  var retryLoadedAudio = !!(audio && audio.src && currentIdx === idx && startupAutoplayAttemptCount > 0 && !isQueueItemRecentlyPlaybackFailed(idx));
  startupAutoplayAttemptCount += 1;
  currentIdx = idx;
  startupAutoplayAttemptRunning = true;
  showRestoredPlaybackControls('startup-autoplay');
  Promise.resolve(retryLoadedAudio ? playAudio({ silent: true, startupAutoplay: true }) : playQueueAt(idx, { manual: false, startupAutoplay: true }))
    .catch(function (e) { console.warn('[StartupAutoplay]', reason || 'startup', e); })
    .finally(function () {
      startupAutoplayAttemptRunning = false;
      if (jobId !== startupAutoplayJobId || !startupAutoplayPreference) return;
      setTimeout(function () {
        if (jobId !== startupAutoplayJobId || !startupAutoplayPreference) return;
        if (startupAutoplayAttemptRunning) return;
        if (isStartupAutoplayPlaying()) {
          finishStartupAutoplayJob(true);
          return;
        }
        if (startupAutoplayAttemptCount >= 12) {
          finishStartupAutoplayJob(false);
          return;
        }
        scheduleStartupAutoplayRetry(jobId, 'retry-after-' + reason);
      }, 260);
    });
  return true;
}
function isStartupHomeReadyForAutoplay() {
  if (startupHomeRevealReady) return true;
  return !(document.body && document.body.classList && document.body.classList.contains('splash-active'));
}
function queueStartupAutoplayAfterHomeReveal(reason) {
  console.log('[AutoplayDiag] queueStartupAutoplayAfterHomeReveal reason=' + reason + ' pref=' + !!startupAutoplayPreference + ' homeReady=' + isStartupHomeReadyForAutoplay());
  if (!startupAutoplayPreference) return false;
  if (isStartupHomeReadyForAutoplay()) return scheduleStartupAutoplayFromSnapshot(reason || 'startup');
  startupAutoplayHomeQueuedReason = reason || 'startup';
  return true;
}
function flushStartupAutoplayAfterHomeReveal(reason, delay) {
  console.log('[AutoplayDiag] flushStartupAutoplayAfterHomeReveal queued=' + startupAutoplayHomeQueuedReason + ' pref=' + !!startupAutoplayPreference + ' homeReady=' + isStartupHomeReadyForAutoplay());
  if (!startupAutoplayHomeQueuedReason || !startupAutoplayPreference) return false;
  if (!isStartupHomeReadyForAutoplay()) return false;
  var queuedReason = startupAutoplayHomeQueuedReason;
  startupAutoplayHomeQueuedReason = '';
  setTimeout(function () {
    if (!startupAutoplayPreference || startupAutoplayAttempted) return;
    scheduleStartupAutoplayFromSnapshot(queuedReason || reason || 'home-revealed');
  }, delay == null ? 120 : Math.max(0, Number(delay) || 0));
  return true;
}
function markStartupHomeReadyForAutoplay(reason, delay) {
  startupHomeRevealReady = true;
  flushStartupAutoplayAfterHomeReveal(reason || 'home-revealed', delay);
}
function scheduleStartupAutoplayFromSnapshot(reason) {
  console.log('[AutoplayDiag] scheduleStartupAutoplayFromSnapshot reason=' + reason + ' homeReady=' + isStartupHomeReadyForAutoplay());
  if (!isStartupHomeReadyForAutoplay()) {
    startupAutoplayHomeQueuedReason = reason || 'startup';
    return true;
  }
  if (!canStartupAutoplayRestoredSnapshot()) {
    var unavailableReason = startupAutoplayUnavailableReason();
    console.log('[AutoplayDiag] scheduleStartupAutoplayFromSnapshot unavailable=' + unavailableReason);
    if (unavailableReason) {
      startupAutoplayAttempted = true;
      handleStartupAutoplayUnavailable(unavailableReason);
    }
    return false;
  }
  clearStartupAutoplayRetryTimer();
  startupAutoplayJobId += 1;
  startupAutoplayAttemptCount = 0;
  startupAutoplayHomeFallbackTried = false;
  startupAutoplayAttempted = true;
  showRestoredPlaybackControls('startup-autoplay');
  scheduleStartupAutoplayRetry(
    startupAutoplayJobId,
    reason || 'startup',
    reason === 'login-status' ? 360 : (reason === 'setting-toggle' ? 40 : 900)
  );
  return true;
}
