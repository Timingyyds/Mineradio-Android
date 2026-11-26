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
    'playable', 'playbackMode', 'recommendationSource', 'programId', 'radioId', 'radioName', 'localKey', 'localFileId',
    // 【本地持久播放】storageRef：私有目录音频的稳定标识，重启后按端口重拼即可播
    'storageRef'
  ].forEach(function (key) {
    if (song[key] != null && song[key] !== '') snap[key] = song[key];
  });
  if (Array.isArray(song.artists)) snap.artists = song.artists.slice(0, 6);
  // 【修复】不要无条件标 localMissing：有 storageRef（音频在私有目录）的歌
  // 重启后可以直接播；只有没有 storageRef 的才算真丢失。
  if (song.type === 'local' || song.localKey) {
    snap.localMissing = !song.storageRef;
  }
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
  if (!snapshot || !snapshot.current) return false;
  var current = hydrateCustomCover(Object.assign({}, snapshot.current));
  var isLocal = current.type === 'local' || !!current.localKey || current.localMissing;
  /*
   * 【修复】本地歌重启恢复：从「本地歌曲」歌单按 localKey 找到条目重建——
   * storageRef（私有目录音频）+ 封面 + 歌词全部回来，不再是"需要重新导入"。
   * 歌单里没有（旧数据）才退回原来的 localMissing 展示。
   */
  if (isLocal && typeof localSongsLibraryLoad === 'function') {
    var libEntry = null;
    try {
      var lib = localSongsLibraryLoad();
      for (var li = 0; li < lib.length; li++) {
        if (lib[li].localKey === current.localKey) { libEntry = lib[li]; break; }
      }
    } catch (e) { }
    if (libEntry && typeof localSongFromLibraryEntry === 'function') {
      var rebuilt = localSongFromLibraryEntry(libEntry);
      // 快照里的进度信息保留
      rebuilt.__restoredSnapshot = true;
      current = rebuilt;
    }
  }
  restoredLastPlaybackSnapshot = snapshot;
  startupRestoreHomePending = !startupAutoplayPreference;
  pendingPlaybackResumeAt = startupResumeSecondsFromSnapshot(snapshot);
  if (isLocal) {
    currentLocalSong = current;
    /*
     * 【修复】本地歌重启后用「本地歌曲」歌单重建完整队列——
     * 之前这里 playQueue = []，队列只剩当前一首，下一首按钮无效。
     * 歌单顺序 = 导入顺序（新的在前），当前歌定位到队列里的对应位置；
     * 每首都带 storageRef（私有目录音频），下一首直接可播。
     */
    var libQueue = [];
    try {
      if (typeof localSongsLibraryLoad === 'function') {
        var libAll = localSongsLibraryLoad();
        for (var lq = 0; lq < libAll.length; lq++) {
          libQueue.push(localSongFromLibraryEntry(libAll[lq]));
        }
      }
    } catch (e) { }
    if (libQueue.length > 1) {
      // 当前歌放回队列中它的位置（找不到就放最前）
      var curPos = -1;
      for (var fq = 0; fq < libQueue.length; fq++) {
        if (queueItemKey(libQueue[fq]) === queueItemKey(current)) { curPos = fq; break; }
      }
      if (curPos < 0) { libQueue.unshift(current); curPos = 0; }
      playQueue = libQueue;
      currentIdx = curPos;
    } else {
      currentIdx = -1;
      playQueue = [];
    }
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
    // 【修复】有 storageRef 的本地歌不是"需要重新导入"，显示正常歌手名
    var localPlayable = isLocal && (shownSong.storageRef || !shownSong.localMissing);
    if (artistEl) artistEl.textContent = (isLocal && !localPlayable) ? '本地文件 · 需要重新导入' : (shownSong.artist || songSourceLabel(shownSong));
    var thumbWrap = document.getElementById('thumb-wrap');
    if (thumbWrap) thumbWrap.classList.add('visible');
    // 【修复】本地歌的封面（dataURL 内嵌）也要显示——dataURL 走 applyCoverDataUrl
    if (shownSong.cover) {
      var coverSrcRestore = String(shownSong.cover);
      setTimeout(function () {
        if (audio || !shownSong) return;
        if (/^data:/i.test(coverSrcRestore) && typeof applyCoverDataUrl === 'function') {
          applyCoverDataUrl(coverSrcRestore, { deferHeavy: true, delay: 120, timeout: 700 });
        } else if (!isLocal || localPlayable) {
          loadCoverFromUrl(songCoverSrc(shownSong, 400), { deferHeavy: true, delay: 120, timeout: 700 });
        }
      }, 180);
    }
  }
  applyRestoredPlaybackProgressUi(Object.assign({}, snapshot, { currentTime: pendingPlaybackResumeAt }));
  showRestoredPlaybackControls('restore');
  return true;
}
function canStartupAutoplayRestoredSnapshot() {
  if (!startupAutoplayPreference || startupAutoplayAttempted) return false;
  if (!restoredLastPlaybackSnapshot) return false;
  if (currentLocalSong && (!Array.isArray(playQueue) || !playQueue.length)) return false;
  return !!(Array.isArray(playQueue) && currentIdx >= 0 && playQueue[currentIdx]);
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
  startupAutoplayJobId += 1;
  startupAutoplayAttemptCount = 0;
  startupAutoplayHomeFallbackTried = false;
  if ((reason === 'local-missing' || reason === 'queue-empty') && tryStartupAutoplayHomeFallback(startupAutoplayJobId)) return;
  restoreHomeAfterStartupAutoplayFallback();
}
function startupAutoplayUnavailableReason() {
  if (!startupAutoplayPreference || startupAutoplayAttempted) return '';
  if (!restoredLastPlaybackSnapshot) return '';
  if (currentLocalSong && (!Array.isArray(playQueue) || !playQueue.length)) return 'local-missing';
  if (!(Array.isArray(playQueue) && currentIdx >= 0 && playQueue[currentIdx])) return 'queue-empty';
  return '';
}
function startupAutoplayRetryDelay(attempt) {
  var delays = [80, 260, 620, 1100, 1800, 2800, 4200, 6200, 8800, 12000, 16000, 22000];
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
  if (startupAutoplayHomeFallbackTried) return false;
  if (!hasAnyPlatformLogin()) return false;
  startupAutoplayHomeFallbackTried = true;
  Promise.resolve().then(async function () {
    try {
      await waitForHomeDiscoverIdle(1800);
      if (!homeDiscoverState.loaded || (!homeDiscoverState.songs.length && !homeDiscoverState.loading)) {
        await loadHomeDiscover(true);
      }
      if (jobId !== startupAutoplayJobId || !startupAutoplayPreference || isStartupAutoplayPlaying()) return;
      if (!homeDiscoverState.songs.length) {
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
        restoreHomeAfterStartupAutoplayFallback();
      }
    }
  });
  return true;
}
function finishStartupAutoplayJob(success) {
  clearStartupAutoplayRetryTimer();
  if (success) {
    startupRestoreHomePending = false;
    forcePlaybackControlsInteractive();
    return;
  }
  if (tryStartupAutoplayHomeFallback(startupAutoplayJobId)) return;
  restoreHomeAfterStartupAutoplayFallback();
}
function runStartupAutoplayAttempt(jobId, reason) {
  if (!startupAutoplayPreference || jobId !== startupAutoplayJobId) return false;
  if (!restoredLastPlaybackSnapshot) return false;
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
  showRestoredPlaybackControls('startup-autoplay');
  Promise.resolve(retryLoadedAudio ? playAudio({ silent: true, startupAutoplay: true }) : playQueueAt(idx, { manual: false, startupAutoplay: true }))
    .catch(function (e) { console.warn('[StartupAutoplay]', reason || 'startup', e); })
    .finally(function () {
      if (jobId !== startupAutoplayJobId || !startupAutoplayPreference) return;
      setTimeout(function () {
        if (jobId !== startupAutoplayJobId || !startupAutoplayPreference) return;
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
  if (!startupAutoplayPreference) return false;
  if (isStartupHomeReadyForAutoplay()) return scheduleStartupAutoplayFromSnapshot(reason || 'startup');
  startupAutoplayHomeQueuedReason = reason || 'startup';
  return true;
}
function flushStartupAutoplayAfterHomeReveal(reason, delay) {
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
  if (!isStartupHomeReadyForAutoplay()) {
    startupAutoplayHomeQueuedReason = reason || 'startup';
    return true;
  }
  if (!canStartupAutoplayRestoredSnapshot()) {
    var unavailableReason = startupAutoplayUnavailableReason();
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
