// public/js/android-shim/05-bridge.js
// 把原生 OkHttp 桥挂成 window.__mrBridgeRequest，给 nodeShim 的 http/https 和 API 路由层用。
//
// 为什么一定要走原生：前端跑在 https://localhost，直接 fetch 各平台的 https 接口会被 CORS 拦，
// 而酷狗/网易云这些接口不给 CORS 头。原生桥不受同源策略约束，也不受混合内容限制。
//
// 桥本身是 Capacitor 插件 FoliaBridge（folia 里已验证过的那一个），这里只做形状适配：
// 它要 { url, method, headers, body, timeoutMs, binary }，回 { status, headers, body, bodyBase64 }。

(function (root) {
  'use strict';

  /** 找到 FoliaBridge 插件。Capacitor 把注册过的插件挂在 window.Capacitor.Plugins 上。 */
  var findBridgePlugin = function () {
    var capacitor = root.Capacitor;
    if (!capacitor) return null;
    var plugins = capacitor.Plugins;
    if (plugins && plugins.FoliaBridge && typeof plugins.FoliaBridge.request === 'function') {
      return plugins.FoliaBridge;
    }
    return null;
  };

  /** 是否在原生环境（安卓壳里）。 */
  var isNative = function () {
    try {
      return Boolean(root.Capacitor && typeof root.Capacitor.isNativePlatform === 'function' && root.Capacitor.isNativePlatform());
    } catch (e) {
      return false;
    }
  };

  /**
   * 统一的请求入口。返回 Promise<{status, headers, body, bodyBase64}>。
   * 与 nodeShim/http 期望的契约一致。
   */
  var bridgeRequest = async function (options) {
    var opts = options || {};
    var plugin = findBridgePlugin();
    if (!plugin) {
      throw new Error('原生桥不可用：FoliaBridge 未注册');
    }
    var answer = await plugin.request({
      url: opts.url,
      method: opts.method || 'GET',
      headers: opts.headers || {},
      body: opts.body == null ? undefined : opts.body,
      timeoutMs: opts.timeoutMs || 15000,
      binary: Boolean(opts.binary),
      // 参考层（汽水）需要控制重定向与是否附带浏览器 Cookie
      ...(opts.followRedirects === undefined ? {} : { followRedirects: Boolean(opts.followRedirects) }),
      ...(opts.omitCookies === undefined ? {} : { omitCookies: Boolean(opts.omitCookies) }),
    });
    return {
      status: answer.status,
      headers: answer.headers || {},
      body: answer.body == null ? '' : answer.body,
      bodyBase64: answer.bodyBase64 || '',
      // Set-Cookie 必须一并回传：扫码登录（QQ/网易云）要靠它拿 qrsig / MUSIC_U，
      // 参考层的 setCookieHeaders.js 也是从这份镜像里读。
      setCookies: answer.setCookies || [],
    };
  };

  root.__mrBridgeRequest = bridgeRequest;
  root.__mrBridgeReady = isNative();

  /**
   * 音频地址适配。
   *
   * 桌面版由 server.js 提供 /api/audio?url=… 代理（给音频元素加同源头，
   * 好让 Web Audio 不因跨域被静音）。安卓壳里没有这个服务端，请求
   * /api/audio 只会拿到 index.html（text/html），音频元素直接报错 ——
   * 表现就是「所有歌都播不了」。
   *
   * 酷狗/QQ/网易云的音频 CDN 都带 Access-Control-Allow-Origin: *，
   * 而播放用的 audio 元素本来就设了 crossOrigin='anonymous'，
   * 所以在原生壳里直接给原始地址即可：既能播，Web Audio 也不会被静音。
   */
  var directMediaAllowed = isNative();
  root.__mrMediaSrc = function (url) {
    var raw = String(url || '').trim();
    if (!raw) return '';
    if (!/^https?:\/\//i.test(raw)) return raw;
    if (directMediaAllowed) {
      // 酷狗的媒体 CDN（尤其 VIP 的 fs.*.kugou.com）只开 HTTP，必须保留原协议；
      // 网易云 / QQ 的 CDN 都支持 HTTPS，页面本身又是 https://localhost，
      // 保持 http 会被当成混合内容拦掉（音频元素报 no supported source）。
      try {
        var parsed = new URL(raw);
        // 本机代理（LocalAudioServer）本来就是 http://127.0.0.1:port，不能被升级成 https，
        // 否则连不上就直接「无法播放媒体」。
        var isLoopback = /^(127\.0\.0\.1|localhost|\[::1\])$/i.test(parsed.hostname);
        if (!isLoopback && parsed.protocol === 'http:' && !/(^|\.)kugou\.com$/i.test(parsed.hostname)) {
          parsed.protocol = 'https:';
          return parsed.toString();
        }
      } catch (e) { /* 解析失败就用原地址 */ }
      return raw;
    }
    return '/api/audio?url=' + encodeURIComponent(raw);
  };

  /**
   * 需要用 fetch 读取音频字节时（节奏分析、播客锁拍）用的地址。
   *
   * 音频元素能直接吃 http/https 混排的 CDN 地址，但页面跑在 https://localhost，
   * 用 fetch 去打 http 的酷狗 CDN 会被当混合内容拦掉（Failed to fetch）。
   * 这些地方仍旧走 /api/audio，由 10-api-router 用原生桥取回字节再交给调用方。
   */
  root.__mrAudioFetchSrc = function (url) {
    var raw = String(url || '').trim();
    if (!raw) return '';
    if (!/^https?:\/\//i.test(raw)) return raw;
    return '/api/audio?url=' + encodeURIComponent(raw);
  };

  // 环境自检：给开发者设置里的日志看
  console.log('[MrBridge] 原生桥 ' + (isNative() ? '已就绪' : '未就绪（网页环境）') +
    (findBridgePlugin() ? '，FoliaBridge 已找到' : '，FoliaBridge 未找到') +
    '，音频源：' + (directMediaAllowed ? '直连 CDN' : '/api/audio 代理'));
})(window);
