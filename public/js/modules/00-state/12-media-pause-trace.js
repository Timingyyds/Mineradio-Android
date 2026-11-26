// 播放暂停诊断（临时）
//
// 症状：开了「启动自动播放」后，重启 App 歌能响一秒，然后自己暂停，再点播放才正常。
// 这种问题光看日志看不出是谁调的 pause，所以给 HTMLMediaElement.pause 挂一层
// 记录：谁暂停的、当时 readyState / currentTime 多少、调用栈前几层。
// 每次启动最多记 12 条，平时不影响性能。

(function installMediaPauseTrace() {
  if (window.__mrMediaPauseTraceInstalled) return;
  window.__mrMediaPauseTraceInstalled = true;
  var proto = window.HTMLMediaElement && window.HTMLMediaElement.prototype;
  if (!proto || typeof proto.pause !== 'function') return;
  var originalPause = proto.pause;
  var budget = 12;

  function shortStack() {
    try {
      var lines = String(new Error().stack || '').split('\n');
      var parts = [];
      for (var i = 1; i < lines.length && parts.length < 5; i += 1) {
        var line = lines[i].trim();
        if (!line || /installMediaPauseTrace/.test(line)) continue;
        parts.push(line);
      }
      return parts.join(' ← ');
    } catch (_error) {
      return '';
    }
  }

  proto.pause = function tracedPause() {
    if (budget > 0) {
      budget -= 1;
      try {
        if (this && this.src) {
          console.log('[MediaPause] 暂停 src=' + String(this.src).slice(0, 90) +
            ' readyState=' + this.readyState +
            ' paused=' + this.paused +
            ' t=' + (Number(this.currentTime) || 0).toFixed(2) +
            ' | ' + shortStack());
        }
      } catch (_error) { /* 忽略 */ }
    }
    return originalPause.apply(this, arguments);
  };
})();