function compactCount(n) {
  n = Number(n) || 0;
  if (n >= 100000000) return (n / 100000000).toFixed(1) + '亿';
  if (n >= 10000) return (n / 10000).toFixed(1) + '万';
  return String(n);
}
function drawCanvasHeart(ctx, cx, cy, size, color) {
  var s = (size || 20) / 28;
  ctx.save();
  ctx.translate(cx, cy);
  ctx.scale(s, s);
  ctx.beginPath();
  ctx.moveTo(0, 10.2);
  ctx.bezierCurveTo(-8.9, 2.6, -13.8, -1.9, -13.8, -7.4);
  ctx.bezierCurveTo(-13.8, -12.0, -10.3, -15.2, -5.9, -15.2);
  ctx.bezierCurveTo(-3.2, -15.2, -1.1, -13.9, 0, -11.9);
  ctx.bezierCurveTo(1.1, -13.9, 3.2, -15.2, 5.9, -15.2);
  ctx.bezierCurveTo(10.3, -15.2, 13.8, -12.0, 13.8, -7.4);
  ctx.bezierCurveTo(13.8, -1.9, 8.9, 2.6, 0, 10.2);
  ctx.closePath();
  ctx.fillStyle = color || '#ff7a90';
  ctx.fill();
  ctx.restore();
}
function requestPlaylistCover(url, cb) {
  if (!url) { if (cb) cb(null); return; }
  var rec = playlistCoverCache[url];
  if (rec && rec.loaded) { if (cb) setTimeout(function () { cb(rec.img); }, 0); return; }
  if (rec && rec.loading) { if (cb) rec.waiters.push(cb); return; }
  rec = playlistCoverCache[url] = { loaded: false, loading: true, waiters: cb ? [cb] : [], img: null, failed: false };

  function finishFail() {
    rec.loading = false; rec.failed = true;
    rec.waiters.splice(0).forEach(function (fn) { setTimeout(function () { fn(null); }, 0); });
  }
  function finishOk(image) {
    rec.loaded = true; rec.loading = false; rec.img = image;
    rec.waiters.splice(0).forEach(function (fn) { setTimeout(function () { fn(image); }, 0); });
  }
  function loadFrom(src, sameOrigin) {
    var img = new Image();
    if (!sameOrigin) img.crossOrigin = 'anonymous';
    img.onload = function () { finishOk(img); };
    img.onerror = finishFail;
    img.src = src;
  }

  /*
   * 3D 歌单架的封面会被画进 2D canvas，再由 THREE.CanvasTexture 上传成贴图。
   * 直接拿 CDN 原图会污染 canvas（各家 CDN 都没有 CORS 头），WebGL 上传时报安全错误，
   * 结果就是歌单架上一块黑方块、详情里一排空图。
   * 原生壳里统一走 __mrLoadCoverObjectUrl：它用原生代理取字节并转成 data: URI，
   * data: 是同源资源，画进 canvas 不会污染，贴图才能正常上传。
   */
  if (typeof window.__mrLoadCoverObjectUrl === 'function') {
    window.__mrLoadCoverObjectUrl(url).then(function (src) {
      if (src) { loadFrom(src, true); return; }
      var direct = typeof coverDisplaySrc === 'function' ? coverDisplaySrc(url) : '';
      if (direct) loadFrom(direct, false);
      else finishFail();
    }, finishFail);
    return;
  }

  var src = coverProxySrc(url);
  if (src) loadFrom(src, false);
  else finishFail();
}

// ============================================================
//  3D 卡片交互 - PSP 风格
//   - 滚轮: 滚动 center 卡 (一级或二级)
//   - 点击 center 卡: 打开内容框 (歌单) 或 播放 (队列)
//   - 点击两侧卡: 滚到那张
//   - ESC: 关闭内容框
