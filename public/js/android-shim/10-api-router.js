// public/js/android-shim/10-api-router.js
// 把前端的 fetch('/api/...') 接到移植进来的平台模块上。
//
// 桌面版里这些请求发给 server.js（Express/Node），安卓上没有那个服务，
// 所以这里在 fetch 层做拦截：认出 /api/kugou/* 就交给 MRModules['kugou-api']。
// 前端一行都不用改——它仍旧发 fetch('/api/kugou/search?...')。
//
// 路由清单是从 server.js 里逐条对照搬过来的（路径、参数名、返回结构、错误码都一致），
// 改上游时照着 server.js 的对应段再对一遍。

(function (root) {
  'use strict';

  var shim = root.__nodeShim;
  if (!shim) throw new Error('api-router: nodeShim 未加载');

  /** 酷狗会话 cookie：桌面版存在 server 内存里，这里存 localStorage。 */
  var KUGOU_COOKIE_KEY = 'mineradio_kugou_cookie';

  var readKugouCookie = function () {
    try { return localStorage.getItem(KUGOU_COOKIE_KEY) || ''; } catch (e) { return ''; }
  };
  var writeKugouCookie = function (value) {
    try {
      if (value) localStorage.setItem(KUGOU_COOKIE_KEY, value);
      else localStorage.removeItem(KUGOU_COOKIE_KEY);
    } catch (e) { /* 存不下就算了，本会话仍可用内存里的 */ }
    memoryCookie = value || '';
  };
  var memoryCookie = readKugouCookie();

  var getKugouCookie = function () { return memoryCookie; };

  /** 合成一个 Response（前端只消费 ok/status/json/text）。 */
  var jsonResponse = function (payload, status) {
    var code = status || 200;
    return {
      ok: code >= 200 && code < 300,
      status: code,
      statusText: code === 200 ? 'OK' : 'Error',
      headers: {
        get: function (name) {
          return String(name).toLowerCase() === 'content-type' ? 'application/json; charset=utf-8' : null;
        },
      },
      json: function () { return Promise.resolve(payload); },
      text: function () { return Promise.resolve(JSON.stringify(payload)); },
      clone: function () { return jsonResponse(payload, code); },
    };
  };

  /**
   * 二进制响应（/api/cover 这类图片代理）。
   * 前端拿它做封面纹理/背景，必须是真图片字节，不能塞 JSON。
   */
  var binaryResponse = function (payload, status) {
    var code = status || (payload && payload.status) || 200;
    var buffer = (payload && payload.buffer) || new ArrayBuffer(0);
    var type = (payload && payload.contentType) || 'application/octet-stream';
    var blob = new Blob([buffer], { type: type });
    return {
      ok: code >= 200 && code < 300 && !(payload && payload.error),
      status: code,
      statusText: code === 200 ? 'OK' : 'Error',
      headers: {
        get: function (name) {
          return String(name).toLowerCase() === 'content-type' ? type : null;
        },
      },
      json: function () { return Promise.resolve(payload); },
      text: function () { return Promise.resolve(''); },
      blob: function () { return Promise.resolve(blob); },
      arrayBuffer: function () { return Promise.resolve(buffer); },
      clone: function () { return binaryResponse(payload, code); },
    };
  };

  /* ── 封面图：原生代理取字节 → 可直接用于 <img>/canvas 的地址 ─────────
   * y.gtimg.cn / 汽水 CDN 不带 CORS 头，图片直接进 <img crossOrigin> 会失败，
   * 画到 canvas 上就更不行。
   * 参考层的 /api/cover 返回的是 { __binary: true, dataUrl: 'data:image/…;base64,…' }
   * （不是 ArrayBuffer），data: URI 本身同源安全，直接拿来当图片源即可。
   */
  var coverBlobCache = new Map();
  var cacheCoverSrc = function (key, src) {
    if (coverBlobCache.size >= 24) {
      var oldest = coverBlobCache.keys().next().value;
      var oldValue = coverBlobCache.get(oldest);
      if (/^blob:/i.test(String(oldValue || ''))) {
        try { URL.revokeObjectURL(oldValue); } catch (e) { /* 忽略 */ }
      }
      coverBlobCache.delete(oldest);
    }
    coverBlobCache.set(key, src);
    return src;
  };
  root.__mrLoadCoverObjectUrl = function (url) {
    var key = String(url || '').trim();
    if (!key) return Promise.resolve('');
    if (coverBlobCache.has(key)) return Promise.resolve(coverBlobCache.get(key));
    var router = root.__mrProviderRouter;
    var task = (typeof router === 'function')
      ? Promise.resolve(router({ path: '/api/cover', method: 'GET', query: { url: key }, body: {} }))
      : root.fetch('/api/cover?url=' + encodeURIComponent(key)).then(function (resp) {
        if (!resp || !resp.ok) return null;
        return resp.json ? resp.json() : null;
      });
    return task.then(function (payload) {
      if (!payload) return '';
      if (payload.dataUrl) return cacheCoverSrc(key, payload.dataUrl);
      var buffer = payload.buffer;
      if (buffer && buffer.byteLength) {
        var blob = new Blob([buffer], { type: payload.contentType || 'image/jpeg' });
        return cacheCoverSrc(key, URL.createObjectURL(blob));
      }
      return '';
    }).catch(function () { return ''; });
  };

  /** 读请求体（前端 POST 时带 JSON）。 */
  var readBody = function (init) {
    if (!init || !init.body) return Promise.resolve({});
    try {
      if (typeof init.body === 'string') return Promise.resolve(JSON.parse(init.body || '{}'));
      return Promise.resolve({});
    } catch (e) {
      return Promise.resolve({});
    }
  };

  // ───────────────────────────────────────────────────────────
  // 酷狗路由
  // ───────────────────────────────────────────────────────────

  var handleKugou = async function (url, init, method) {
    var kugou = root.MRModules && root.MRModules['kugou-api'];
    if (!kugou) {
      return jsonResponse({ provider: 'kugou', error: 'KUGOU_MODULE_MISSING', message: '酷狗模块未加载' }, 500);
    }
    var pn = url.pathname;
    var cookie = getKugouCookie();

    try {
      // ── 登录状态 ──
      if (pn === '/api/kugou/login/status') {
        return jsonResponse(await kugou.getKugouLoginInfo(cookie));
      }

      // ── 保存 cookie（扫码完成后走这里，与 folia 的「拿到凭据就写会话」一致） ──
      if (pn === '/api/kugou/login/cookie') {
        var body = await readBody(init);
        var raw = body.cookie || body.data || body.text || '';
        var normalized = kugou.normalizeKugouCookieInput(raw);
        var auth = kugou.extractKugouAuth(normalized);
        if (!auth.loggedIn && !kugou.kugouCookieObject(normalized).kg_mid) {
          return jsonResponse({
            provider: 'kugou', loggedIn: false,
            error: 'INVALID_KUGOU_COOKIE', message: '酷狗 cookie 无效或缺少登录标识',
          }, 400);
        }
        writeKugouCookie(normalized);
        var info = await kugou.getKugouLoginInfo(normalized);
        return jsonResponse(Object.assign({}, info, {
          saved: true,
          partial: auth.loggedIn && !auth.playbackReady,
        }));
      }

      // ── 退出 ──
      if (pn === '/api/kugou/logout') {
        writeKugouCookie('');
        kugou.clearKugouSessionCaches();
        return jsonResponse({ provider: 'kugou', loggedIn: false, ok: true });
      }

      // ── 搜索（签名与 server.js:4952 一致：kw, limit, cookie, offset → 数组） ──
      if (pn === '/api/kugou/search') {
        var kw = url.searchParams.get('keywords') || url.searchParams.get('keyword') || url.searchParams.get('q') || '';
        var limit = Math.max(4, Math.min(20, parseInt(url.searchParams.get('limit') || '12', 10) || 12));
        var offset = Math.max(0, parseInt(url.searchParams.get('offset') || '0', 10) || 0);
        var songs = await kugou.handleKugouSearch(kw, limit, cookie, offset);
        return jsonResponse({
          provider: 'kugou', songs: songs, offset: offset, limit: limit,
          nextOffset: offset + songs.length, hasMore: songs.length >= limit,
        });
      }

      // ── 播放地址（对象参数，字段名照 server.js:5563） ──
      if (pn === '/api/kugou/song/url') {
        var qp = url.searchParams;
        var info = await kugou.handleKugouSongUrl({
          hash: qp.get('hash') || qp.get('id') || '',
          albumId: qp.get('albumId') || qp.get('album_id') || '',
          albumAudioId: qp.get('albumAudioId') || qp.get('album_audio_id') || qp.get('mixSongId') || '',
          mixSongId: qp.get('mixSongId') || qp.get('albumAudioId') || qp.get('album_audio_id') || '',
          hqHash: qp.get('hqHash') || qp.get('hq_hash') || '',
          sqHash: qp.get('sqHash') || qp.get('sq_hash') || '',
          resHash: qp.get('resHash') || qp.get('res_hash') || '',
          quality: qp.get('quality') || '',
          vipRequired: qp.get('vipRequired') || '',
          needVip: qp.get('needVip') || qp.get('need_vip') || '',
          onlyVipPlayable: qp.get('onlyVipPlayable') || qp.get('only_vip_playable') || '',
          privilege: qp.get('privilege') || qp.get('mediaPrivilege') || qp.get('media_privilege') || '',
          fee: qp.get('fee') || '',
        }, cookie);
        return jsonResponse(info);
      }

      // ── 歌词（位置参数，照 server.js:5592） ──
      if (pn === '/api/kugou/lyric') {
        var lhash = url.searchParams.get('hash') || url.searchParams.get('id') || '';
        var lAlbumAudioId = url.searchParams.get('albumAudioId') || url.searchParams.get('album_audio_id') || '';
        var lDuration = url.searchParams.get('duration') || '';
        if (!lhash) {
          return jsonResponse({ provider: 'kugou', error: 'Missing Kugou hash', lyric: '' }, 400);
        }
        return jsonResponse(await kugou.handleKugouLyric(lhash, lAlbumAudioId, lDuration));
      }

      // ── 猜你喜欢（位置参数，照 server.js:4968） ──
      if (pn === '/api/kugou/recommendations') {
        var recLimit = Math.max(4, Math.min(20, parseInt(url.searchParams.get('limit') || '12', 10) || 12));
        return jsonResponse(await kugou.handleKugouGuessLike(cookie, recLimit));
      }

      // ── 用户歌单 ──
      if (pn === '/api/kugou/user/playlists') {
        return jsonResponse(await kugou.handleKugouUserPlaylists(cookie));
      }

      // ── 歌单曲目（带分页与不带分页两种，与 server.js 一致） ──
      if (pn === '/api/kugou/playlist/tracks') {
        var id = url.searchParams.get('id') || url.searchParams.get('global_collection_id') || '';
        var paged = url.searchParams.has('limit') || url.searchParams.has('offset');
        var limit = Math.max(10, Math.min(50, parseInt(url.searchParams.get('limit') || '50', 10) || 50));
        var offset = Math.max(0, parseInt(url.searchParams.get('offset') || '0', 10) || 0);
        return jsonResponse(await kugou.handleKugouPlaylistTracks(id, cookie, paged ? { limit: limit, offset: offset, paged: true } : {}));
      }

      // ── 红心状态 ──
      if (pn === '/api/kugou/song/like/check') {
        if (!kugou.kugouCookieHasPlayback(cookie)) {
          return jsonResponse({ provider: 'kugou', loggedIn: false, liked: {}, error: 'KUGOU_AUTH_REQUIRED' });
        }
        var hashes = url.searchParams.get('hashes') || url.searchParams.get('hash') || '';
        return jsonResponse(await kugou.handleKugouLikeCheck({ hashes: hashes }, cookie));
      }

      // ── 红心开关 ──
      if (pn === '/api/kugou/song/like') {
        if (!kugou.kugouCookieHasPlayback(cookie)) {
          return jsonResponse({ provider: 'kugou', success: false, error: 'KUGOU_AUTH_REQUIRED' }, 401);
        }
        var likeBody = method === 'POST' ? await readBody(init) : {};
        var song = likeBody.song || {};
        var like = String(likeBody.like != null ? likeBody.like : (url.searchParams.get('like') || 'true')) !== 'false';
        return jsonResponse(await kugou.handleKugouLikeToggle(song, like, cookie));
      }

      // ── 加入歌单 ──
      if (pn === '/api/kugou/playlist/add-song') {
        if (!kugou.kugouCookieHasPlayback(cookie)) {
          return jsonResponse({ provider: 'kugou', success: false, error: 'KUGOU_AUTH_REQUIRED' }, 401);
        }
        var addBody = method === 'POST' ? await readBody(init) : {};
        var pid = addBody.pid || url.searchParams.get('pid') || '';
        if (!pid) {
          return jsonResponse({ provider: 'kugou', success: false, error: 'Missing playlist id' }, 400);
        }
        return jsonResponse(await kugou.handleKugouPlaylistAddSong(pid, addBody.song || addBody, cookie));
      }

      // ── 扫码登录三步（folia 的逻辑：key → 二维码图 → 轮询） ──
      // 桌面版没有这三步（它是开官方窗口登录），安卓上要自己实现。
      if (pn === '/api/kugou/login/qr/key') {
        return jsonResponse(await kugouLoginQrKey());
      }
      if (pn === '/api/kugou/login/qr/create') {
        var keyForQr = url.searchParams.get('key') || '';
        return jsonResponse(await kugouLoginQrCreate(keyForQr));
      }
      if (pn === '/api/kugou/login/qr/check') {
        var keyForCheck = url.searchParams.get('key') || '';
        return jsonResponse(await kugouLoginQrCheck(keyForCheck));
      }

      return jsonResponse({ provider: 'kugou', error: 'UNKNOWN_KUGOU_ROUTE', path: pn }, 404);
    } catch (error) {
      console.error('[KugouRoute] ' + pn, error);
      return jsonResponse({ provider: 'kugou', error: String(error && error.message || error) }, 500);
    }
  };

  // ───────────────────────────────────────────────────────────
  // 酷狗扫码登录：三步，与 folia 的 kugouProvider.auth 一一对应
  //
  // 协议照 folia 的 src/audioNative/reference/kugou.js（那边实测可用）：
  //   拿 key  : login-user.kugou.com  /v2/qrcode           参数 appid=1001, type=1, plat=4, srcappid=2919
  //   轮询    : login-user.kugou.com  /v2/get_userinfo_qrcode  参数 plat=4, appid=3116, srcappid=2919, qrcode=key
  //   二维码  : 本地画（qrcode 生成 h5.kugou.com 的登录链接），不依赖外部出图服务
  //
  // 签名用 H5 的 salt（不是 Android 的），参数按 key 排序后 md5(salt + 串 + salt)。
  // 这两点和「以为走 gateway.kugou.com」是这次最大的坑：那个 host 直接 502。
  // ───────────────────────────────────────────────────────────

  var KG_WEB_SIGN_SALT = 'NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt';
  var KG_SRCAPPID = 2919;
  var KG_QR_KEY_APPID = 1001;      // 取 key 用 1001（文档 5.1 ①）
  var KG_ANDROID_APPID = 1005;      // 轮询用 1005（文档 5.1 ③）
  var KG_ANDROID_CLIENTVER = 20489; // 文档常量表 clientVer
  var KG_UA_PC = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
  var KG_LOGIN_HOST = 'https://login-user.kugou.com';

  /**
   * Web 签名。严格按《酷狗音乐完整协议指南》2.2 的写法：
   *   先拼成 "key=value"，再对拼好的字符串排序，md5(盐 + 串 + body + 盐)
   * 与之相对的是 Android 签名：先按 key 排序。
   * 两者多数情况结果相同，但文档明确要求各实现各的，不能合并 —— 这里不合并。
   */
  var signKugouWebParams = function (params, body) {
    var pieces = Object.keys(params).map(function (key) {
      return key + '=' + params[key];
    });
    pieces.sort();
    return shim.crypto.createHash('md5')
      .update(KG_WEB_SIGN_SALT + pieces.join('') + (body || '') + KG_WEB_SIGN_SALT)
      .digest('hex');
  };

  /** 带 Web 签名的 GET。extraParams 叠在公共参数之上。 */
  var kgWebSignedGet = async function (baseURL, urlPath, extraParams, timeoutMs) {
    var mid = await ensureKugouMid();
    var clienttime = Math.floor(Date.now() / 1000);
    var params = Object.assign({
      dfid: '-',
      mid: mid,
      uuid: mid,
      clientver: KG_ANDROID_CLIENTVER,
      clienttime: clienttime,
    }, extraParams || {});
    params.signature = signKugouWebParams(params);
    var qs = Object.keys(params).map(function (key) {
      return encodeURIComponent(key) + '=' + encodeURIComponent(params[key]);
    }).join('&');
    var path = urlPath.charAt(0) === '/' ? urlPath : '/' + urlPath;
    var url = String(baseURL || '').replace(/\/$/, '') + path + '?' + qs;

    var answer = await root.__mrBridgeRequest({
      url: url,
      method: 'GET',
      headers: {
        'User-Agent': KG_UA_PC,
        Accept: 'application/json, text/plain, */*',
        Referer: 'https://www.kugou.com/',
      },
      timeoutMs: timeoutMs || 15000,
    });
    if (answer.status < 200 || answer.status >= 300) {
      throw new Error('KuGou HTTP ' + answer.status);
    }
    var parsed = JSON.parse(answer.body || '{}');
    // 把 Set-Cookie 一并交给调用方：登录响应里的 kg_mid / kg_dfid 必须落库，
    // 否则播放接口按设备字段校验 token 时取不到地址（文档 5.2 / 三根支柱）。
    parsed.__setCookies = answer.setCookies || [];
    return parsed;
  };

  /**
   * 从登录响应的 Set-Cookie 里提取设备字段，存进会话。
   *
   * 文档 5.2 明确要求：登录下发的 kg_mid / kg_dfid 必须覆盖本地随机值。
   * 不做的后果是「登录成功但播放取不到地址」，很隐蔽。
   * 只存这四类设备字段；账号本身的中转凭据（KugooID/token 等）在登录结果里另取。
   */
  var absorbKugouDeviceCookies = function (setCookies) {
    var wanted = /^(kg_mid|kg_dfid|dfid|KuGoo|KToken|t)$/i;
    var picked = {};
    (setCookies || []).forEach(function (raw) {
      // 每段 Set-Cookie 形如 "kg_mid=abc123; Path=/; Expires=..."
      var first = String(raw).split(';')[0] || '';
      var idx = first.indexOf('=');
      if (idx <= 0) return;
      var name = first.slice(0, idx).trim();
      var value = first.slice(idx + 1).trim();
      if (!name || !value) return;
      if (wanted.test(name)) picked[name] = value;
    });
    if (picked.kg_mid) {
      try { localStorage.setItem(KUGOU_MID_KEY, picked.kg_mid); } catch (e) {}
    }
    if (picked.kg_dfid || picked.dfid) {
      try { localStorage.setItem('mineradio_kugou_dfid', picked.kg_dfid || picked.dfid); } catch (e) {}
    }
    return picked;
  };

  /** 本地存的 dfid：登录下发的优先，没有就用 '-'（文档 4.1）。 */
  var readKugouDfid = function () {
    try { return localStorage.getItem('mineradio_kugou_dfid') || '-'; } catch (e) { return '-'; }
  };

  // mid：酷狗的机器标识，第一次用时生成并记住（与 folia 的 getKGMid 等价）
  var KUGOU_MID_KEY = 'mineradio_kugou_mid';
  var ensureKugouMid = async function () {
    try {
      var saved = localStorage.getItem(KUGOU_MID_KEY);
      if (saved) return saved;
    } catch (e) { /* 忽略 */ }
    var mid = shim.crypto.randomBytes(16).toString('hex');
    try { localStorage.setItem(KUGOU_MID_KEY, mid); } catch (e) {}
    return mid;
  };

  /** 第一步：拿二维码 key。 */
  var kugouLoginQrKey = async function () {
    var body = await kgWebSignedGet(KG_LOGIN_HOST, '/v2/qrcode', {
      appid: KG_QR_KEY_APPID,
      type: 1,
      plat: 4,
      qrcode_txt: 'https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=' + KG_ANDROID_APPID + '&',
      srcappid: KG_SRCAPPID,
    });
    var key = String(
      (body && body.data && (body.data.qrcode || body.data.qrcode_key || body.data.key))
      || (body && (body.qrcode || body.key))
      || '',
    ).trim();
    if (!key) throw new Error('酷狗二维码 key 获取失败');
    return { provider: 'kugou', key: key };
  };

  /** 第二步：出二维码图。返回 h5 登录页地址，前端自己画（不依赖外部出图服务）。 */
  var kugouLoginQrCreate = async function (key) {
    key = String(key || '').trim();
    if (!key) throw new Error('缺少二维码 key');
    var url = 'https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=' + encodeURIComponent(key);
    return { provider: 'kugou', key: key, url: url, qrimg: url };
  };

  /**
   * 向酷狗要真实用户资料（昵称 / 头像 / 会员）。
   *
   * 为什么要这一步：扫码接口 /v2/get_userinfo_qrcode 确认后返回的字段里
   * 并不保证带昵称和头像 —— 实测里经常只有 userid 和 token。
   * 光靠它拼 cookie，界面上就会显示成「酷狗 用户」这种兜底文案，头像也是空的。
   *
   * 正确来源是 userinfo.user.kugou.com/v2/get_user_info
   * （folia 的 fetchKGUserProfile 用的就是这个，见 reference/kugou.js:2461）。
   */
  var fetchKugouUserProfile = async function (userid, token, mid) {
    var headers = {
      'User-Agent': 'Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Mobile Safari/537.36 KGMusic/9.3.0',
      Accept: 'application/json, text/plain, */*',
      Referer: 'https://www.kugou.com/',
      Cookie: 'userid=' + userid + '; token=' + token + '; KugooID=' + userid + '; t=' + token,
    };
    var makeUrl = function (secure) {
      var base = secure ? 'https://' : 'http://';
      var qs = [
        'appid=1005',
        'clientver=' + KG_ANDROID_CLIENTVER,
        'mid=' + encodeURIComponent(mid),
        'userid=' + encodeURIComponent(userid),
        'token=' + encodeURIComponent(token),
        'dfid=-',
        'plat=0',
        'clienttime=' + Date.now(),
      ].join('&');
      return base + 'userinfo.user.kugou.com/v2/get_user_info?' + qs;
    };
    // 这个域名只走 http：
    // 实测 https 会因证书不含 userinfo.user.kugou.com 而握手失败
    // （证书 altnames 只有 *.kugou.com / *.kgimg.com），
    // 先试 https 只是白等一次超时。http 实测返回 200，接口本身是通的。
    for (var i = 1; i < 2; i += 1) {
      try {
        var answer = await root.__mrBridgeRequest({
          url: makeUrl(i === 0),
          method: 'GET',
          headers: headers,
          timeoutMs: 12000,
        });
        if (answer.status < 200 || answer.status >= 300) continue;
        var body = JSON.parse(answer.body || '{}');
        var code = Number(body.status != null ? body.status : body.errcode);
        if (code !== 1 && code !== 0 && body.error_code) continue;
        var data = body.data || body.info;
        if (!data || typeof data !== 'object') continue;
        return {
          nickname: String(data.nickname || data.nick_name || data.username || data.user_name || ''),
          avatar: String(data.pic || data.Pic || data.user_pic || data.avatar || data.userpic || ''),
          vipType: Number(data.vip_type || data.vipType || 0) || 0,
          vipToken: String(data.vip_token || data.vipToken || ''),
          raw: data,
        };
      } catch (error) {
        // 换下一个协议重试
      }
    }
    return null;
  };

  /**
   * 第三步：轮询。
   * status: 0 过期 / 1 等待 / 2 待确认 / 4 成功（带 token）
   * 与 folia 完全一致；确认时把凭据存成 cookie（folia 在 check 里写会话，这里等价）。
   */
  var kugouLoginQrCheck = async function (key) {
    key = String(key || '').trim();
    if (!key) throw new Error('缺少二维码 key');
    // 轮询参数与 folia 的参考层 handleKGLoginQrCheck 完全一致：只带 plat/appid/srcappid/qrcode。
    // 早先这里多塞了一个 dev=mid，服务端会把它当成另一台设备，扫码后状态可能一直不推进。
    var body = await kgWebSignedGet(KG_LOGIN_HOST, '/v2/get_userinfo_qrcode', {
      plat: 4,
      appid: KG_ANDROID_APPID,
      srcappid: KG_SRCAPPID,
      qrcode: key,
    }, 45000);

    // 登录响应的 Set-Cookie 里带 kg_mid / kg_dfid，必须立刻落库（文档 5.2）
    var deviceFields = absorbKugouDeviceCookies(body.__setCookies);

    var data = (body && body.data) || {};
    var status = Number(data.status != null ? data.status : (body && body.status)) || 0;
    var result = { provider: 'kugou', key: key, status: status };

    if (status === 0) { result.state = 'expired'; result.message = '二维码已过期，请刷新'; return result; }
    if (status === 1) { result.state = 'waiting'; result.message = '请用酷狗 App 扫码'; return result; }
    if (status === 2) { result.state = 'scanned'; result.message = '已扫码，请在手机确认'; return result; }

    if (status === 4) {
      var userId = String(data.userid || data.user_id || data.KugooID || '').trim();
      var token = String(data.token || data.t || '').trim();
      if (!userId || !token) {
        result.state = 'error';
        result.error = 'QR_NO_TOKEN';
        result.message = '扫码成功但未返回 token，请刷新二维码重试';
        return result;
      }
      var nickname = data.nickname || data.nick_name || data.username || '';
      // 昵称是纯数字时就是 userid 兜底，不算真名，后面去 get_user_info 再要一次
      if (/^\d+$/.test(String(nickname).trim())) nickname = '';
      var avatar = data.pic || data.user_pic || data.avatar || data.headimg || '';
      var vipToken = String(data.vip_token || data.vipToken || '').trim();
      var vipType = Number(data.vip_type || data.vipType || 0) || (vipToken ? 6 : 0);

      // 扫码返回值里经常没有昵称和头像（实测只有 userid + token）。
      // 只要缺，就去 get_user_info 要一次真实资料 —— 这是「名字显示成酷狗 用户、头像空白」的正解。
      if (!nickname || !avatar) {
        var midForProfile = await ensureKugouMid();
        var profile = await fetchKugouUserProfile(userId, token, midForProfile);
        if (profile) {
          nickname = nickname || profile.nickname || '';
          avatar = avatar || profile.avatar || '';
          if (!vipType && profile.vipType) vipType = profile.vipType;
          if (!vipToken && profile.vipToken) vipToken = profile.vipToken;
        }
      }

      // 拼 cookie。两个分隔符不能弄错，这是「登录后账号信息全不对」的根因：
      //   外层各字段之间用 ';'（parseCookieString 按 ; 切）
      //   KuGoo 复合字段【内部】用 '&'（parseKuGooCompound 按 & 切）
      // 之前把 NickName/Pic 直接放进外层，而 extractKugouAuth 优先读的是
      // KuGoo 里的 NickName/Pic（kugoo.NickName || obj.NickName …），
      // 结果昵称和头像都取不到，界面上就显示成 id 或者空。
      // folia 的 buildKuGooCookieValue 也是这个形状（见 reference/kugou.js）。
      // 按文档 4.1 的会话结构拼：扁平键值集合，而不是我早先以为的 KuGoo 嵌套。
      //   头像字段名是小写 pic（文档 4.1），extractKugouAuth 也读 obj.avatar/pic
      //   dfid / kg_dfid / kg_mid 必须一起带上，否则播放接口按设备校验 token 会失败
      var midNow = await ensureKugouMid();
      var dfidNow = deviceFields.kg_dfid || deviceFields.dfid || readKugouDfid();
      var cookieParts = [
        'userid=' + userId,
        'KugooID=' + userId,
        'token=' + token,
        't=' + token,
        'mid=' + midNow,
        'dfid=' + dfidNow,
        'kg_mid=' + midNow,
        'kg_dfid=' + dfidNow,
      ];
      // 扫码返回的 nickname 有时就是 userid 本身：那不是名字，别写进 cookie，
      // 让 getKugouLoginInfo 回落到歌单数据里取真实昵称。
      if (nickname && !/^\d+$/.test(String(nickname).trim())) {
        cookieParts.push('NickName=' + encodeURIComponent(nickname));
        // 文档 4.1 里昵称字段就是 NickName，同值再给小写一份供不同分支读取
        cookieParts.push('nickname=' + encodeURIComponent(nickname));
      } else {
        nickname = '';
      }
      if (avatar) {
        // 头像不要 encodeURIComponent：extractKugouAuth 直接拿 obj.pic 当 URL 用，
        // 编码过的 %3A%2F%2F 会被当成地址的一部分，头像就加载不出来。
        // 昵称那边有 decodeKugouDisplayText 帮忙解码，头像是没有的。
        var safeAvatar = String(avatar).replace(/;/g, '%3B');
        cookieParts.push('pic=' + safeAvatar);
        cookieParts.push('Pic=' + safeAvatar);
      }
      if (vipToken) cookieParts.push('vip_token=' + vipToken);
      if (vipType) cookieParts.push('vip_type=' + vipType);

      var cookie = cookieParts.join('; ');
      var kugou = root.MRModules && root.MRModules['kugou-api'];
      var normalized = kugou ? kugou.normalizeKugouCookieInput(cookie) : cookie;
      writeKugouCookie(normalized);
      result.state = 'confirmed';
      result.loggedIn = true;
      result.userId = userId;
      result.nickname = nickname;
      result.avatar = avatar;
      result.cookie = normalized;
      return result;
    }

    result.state = 'error';
    result.message = '等待扫码';
    return result;
  };

  // ───────────────────────────────────────────────────────────
  // /api/audio：把音频字节取回来给 fetch 用（节奏分析、播客锁拍）
  //
  // 桌面版由 server.js 直接流式代理。安卓壳里没有服务端，而页面跑在 https://localhost，
  // 用 fetch 打 http 的酷狗 CDN 会被混合内容拦掉，所以这里用原生桥取字节再合成 Response。
  // ───────────────────────────────────────────────────────────

  var base64ToBytes = function (text) {
    var binary = root.atob(String(text || ''));
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  };

  var handleAudioProxy = async function (url) {
    var target = url.searchParams.get('url') || '';
    if (!/^https?:\/\//i.test(target)) {
      return jsonResponse({ error: 'INVALID_AUDIO_URL', message: '音频地址无效' }, 400);
    }
    var answer = await root.__mrBridgeRequest({
      url: target,
      method: 'GET',
      headers: {},
      timeoutMs: 60000,
      binary: true,
    });
    var bytes = base64ToBytes(answer.bodyBase64 || '');
    var contentType = (answer.headers && (answer.headers['content-type'] || answer.headers['Content-Type'])) || 'audio/mpeg';
    return {
      ok: answer.status >= 200 && answer.status < 300,
      status: answer.status || 200,
      statusText: 'OK',
      headers: {
        get: function (name) {
          var key = String(name || '').toLowerCase();
          if (key === 'content-type') return contentType;
          if (key === 'content-length') return String(bytes.byteLength);
          return null;
        },
      },
      arrayBuffer: function () {
        return Promise.resolve(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
      },
      blob: function () { return Promise.resolve(new Blob([bytes], { type: contentType })); },
      json: function () { return Promise.reject(new Error('audio response is not json')); },
      text: function () { return Promise.resolve(''); },
      clone: function () { return this; },
    };
  };

  // 网易云/QQ 返回的封面、头像几乎都是 http://（比如 p2.music.126.net）。
  // 页面跑在 https://localhost，混合内容会把图片拦掉，表现就是「搜到歌但封面一片空白」。
  // 这里只把「看起来是图片」的字段升到 https，音频地址仍由播放层单独处理。
  var IMAGE_KEY_RE = /cover|avatar|pic|image|img/i;
  var upgradeImageUrls = function (value, depth) {
    if (!value || typeof value !== 'object' || depth > 8) return value;
    if (Array.isArray(value)) {
      for (var i = 0; i < value.length; i += 1) upgradeImageUrls(value[i], depth + 1);
      return value;
    }
    Object.keys(value).forEach(function (key) {
      var item = value[key];
      if (typeof item === 'string') {
        if (IMAGE_KEY_RE.test(key) && /^http:\/\//i.test(item)) {
          value[key] = item.replace(/^http:/i, 'https:');
        }
      } else if (item && typeof item === 'object') {
        upgradeImageUrls(item, depth + 1);
      }
    });
    return value;
  };

  // ───────────────────────────────────────────────────────────
  // 拦截 fetch
  // ───────────────────────────────────────────────────────────

  /**
   * 等参考层路由就绪。
   *
   * ref/bootstrap.js 是 type="module"，浏览器会把它排在所有普通 script 之后执行，
   * 而本文件是普通 script。首屏那几次请求可能在它之前就发出来了，
   * 那时 window.__mrProviderRouter 还不存在 —— 歌词就是这么丢的。
   *
   * 这里轮询等待，最多等 5 秒；超时仍没有才放行给原始 fetch（真没有参考层时至少不卡死）。
   */
  var PROVIDER_ROUTER_WAIT_MS = 5000;
  var PROVIDER_ROUTER_POLL_MS = 50;
  var waitForProviderRouter = function () {
    var existing = root.__mrProviderRouter;
    if (typeof existing === 'function') return Promise.resolve(existing);
    return new Promise(function (resolve) {
      var waited = 0;
      var check = function () {
        var router = root.__mrProviderRouter;
        if (typeof router === 'function') { resolve(router); return; }
        if (waited >= PROVIDER_ROUTER_WAIT_MS) {
          console.warn('[ApiRouter] 等待参考层超时（' + PROVIDER_ROUTER_WAIT_MS + 'ms），这次请求放行给原始 fetch');
          resolve(null);
          return;
        }
        waited += PROVIDER_ROUTER_POLL_MS;
        setTimeout(check, PROVIDER_ROUTER_POLL_MS);
      };
      check();
    });
  };

  var originalFetch = root.fetch.bind(root);

  root.fetch = function (input, init) {
    var urlString;
    if (typeof input === 'string') urlString = input;
    else if (input && input.url) urlString = input.url;
    else urlString = String(input);

    // 只接管同源 /api/kugou/* 与 /api/audio，其余原样放行
    var resolved;
    try {
      resolved = new URL(urlString, root.location && root.location.href || 'https://localhost/');
    } catch (e) {
      return originalFetch(input, init);
    }

    if (resolved.pathname === '/api/audio') {
      return handleAudioProxy(resolved);
    }

    // YouTube Music：由 ported/ytm/ytm-api.js 适配成前端期望的结构
    if (resolved.pathname.indexOf('/api/ytm/') === 0) {
      var ytmRouter = root.__mrYtmRouter;
      if (typeof ytmRouter === 'function') {
        var ytmQuery = {};
        resolved.searchParams.forEach(function (value, key) { ytmQuery[key] = value; });
        var ytmMethod = String((init && init.method) || 'GET').toUpperCase();
        return readBody(init)
          .then(function (bodyObj) {
            return ytmRouter({ path: resolved.pathname, method: ytmMethod, query: ytmQuery, body: bodyObj });
          })
          .then(function (payload) { return jsonResponse(payload); })
          .catch(function (err) {
            return jsonResponse({ provider: 'ytm', error: err && err.message ? err.message : String(err) }, 500);
          });
      }
    }

    // 波点音乐：由 ported/bodian/bodian-api.js 适配成前端期望的结构
    if (resolved.pathname.indexOf('/api/bodian/') === 0) {
      var bodianRouter = root.__mrBodianRouter;
      if (typeof bodianRouter === 'function') {
        var bodianQuery = {};
        resolved.searchParams.forEach(function (value, key) { bodianQuery[key] = value; });
        var bodianMethod = String((init && init.method) || 'GET').toUpperCase();
        return readBody(init)
          .then(function (bodyObj) {
            return bodianRouter({ path: resolved.pathname, method: bodianMethod, query: bodianQuery, body: bodyObj });
          })
          .then(function (payload) { return jsonResponse(payload); })
          .catch(function (err) {
            return jsonResponse({ provider: 'bodian', error: err && err.message ? err.message : String(err) }, 500);
          });
      }
    }

    // 汽水音乐：由 ported/qishui/router.js 适配成前端期望的结构
    if (resolved.pathname.indexOf('/api/qishui/') === 0) {
      var qishuiRouter = root.__mrQishuiRouter;
      if (typeof qishuiRouter === 'function') {
        var qishuiQuery = {};
        resolved.searchParams.forEach(function (value, key) { qishuiQuery[key] = value; });
        var qishuiMethod = String((init && init.method) || 'GET').toUpperCase();
        return readBody(init)
          .then(function (bodyObj) {
            return qishuiRouter({ path: resolved.pathname, method: qishuiMethod, query: qishuiQuery, body: bodyObj });
          })
          .then(function (payload) { return jsonResponse(payload); })
          .catch(function (err) {
            return jsonResponse({ provider: 'qishui', error: err && err.message ? err.message : String(err) }, 500);
          });
      }
    }

    // 网易云 / QQ 音乐（以及参考层的 /api/kg/*）交给参考层路由处理。
    // 这些路径与桌面版 server.js 完全一致，参考层 router.js 已逐条实现。
    //
    // 注意加载时序：ref/bootstrap.js 是 type="module"，比本文件（普通 script）晚执行。
    // 这里必须等它就绪，否则首屏那几次请求（含歌词）会在 __mrProviderRouter 还 undefined 时
    // 掉进下面的 originalFetch，拿 404 —— 表现就是「有歌没歌词」。
    if (/^\/api\//.test(resolved.pathname) && !/^\/api\/kugou\//.test(resolved.pathname)) {
      return waitForProviderRouter().then(function (providerRouter) {
        if (typeof providerRouter !== 'function') return originalFetch(input, init);
        var queryObj = {};
        resolved.searchParams.forEach(function (value, key) { queryObj[key] = value; });
        var refMethod = String((init && init.method) || 'GET').toUpperCase();
        return readBody(init)
          .then(function (bodyObj) {
            return providerRouter({ path: resolved.pathname, method: refMethod, query: queryObj, body: bodyObj });
          })
          .then(function (payload) {
            // /api/cover 返回的是二进制（图片字节），必须按二进制回给前端。
            if (payload && payload.__binary) {
              return binaryResponse(payload, payload.inputStatus || payload.status);
            }
            // /api/kg/* 是酷狗参考层（音频/头像多为 http 专用 CDN），不做图片升级。
            if (resolved.pathname.indexOf('/api/kg/') !== 0) upgradeImageUrls(payload, 0);
            return jsonResponse(payload);
          })
          .catch(function (err) {
            return jsonResponse({ error: err && err.message ? err.message : String(err) }, 500);
          });
      });
    }

    if (!/^\/api\/kugou\//.test(resolved.pathname)) {
      return originalFetch(input, init);
    }

    var method = String((init && init.method) || 'GET').toUpperCase();

    // 桌面版用相对路径，这里相对的就是 App 自己（https://localhost），所以同源成立
    return handleKugou(resolved, init, method);
  };

  // 顺带给业务层一个直连入口，方便调试
  root.__mrKugouProbe = {
    hasCookie: function () { return Boolean(getKugouCookie()); },
    cookie: function () { return getKugouCookie(); },
    qrKey: kugouLoginQrKey,
    qrCreate: kugouLoginQrCreate,
    qrCheck: kugouLoginQrCheck,
    clear: function () { writeKugouCookie(''); },
  };
})(window);
