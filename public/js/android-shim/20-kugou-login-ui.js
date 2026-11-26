// public/js/android-shim/20-kugou-login-ui.js
// 把现有登录弹窗里的「酷狗 → 扫码」接上真实的三步协议。
//
// 桌面版这一步是开 Electron 的官方登录窗口（window.desktopWindow.openKugouMusicLogin），
// 安卓上没有那个 API，原来的按钮在安卓账上等于死的。这里接管它：
//   拿 key → 出二维码 → 2 秒轮询 → 确认后保存 cookie → 刷新账号状态
//
// 只改酷狗这一栏，其他平台（网易云/QQ/汽水/Spotify）一行不动：
// 桌面版的 openKugouWebLogin 只在 window.desktopWindow 存在时才走官方窗口，
// 安卓上它不存在，所以调到这里。
//
// 轮询间隔与 folia 一致（2 秒），连续超时按「还没扫」处理而不是判死，
// 这点也照 folia（一次网络抖动不该让用户重新扫码）。

(function (root) {
  'use strict';

  if (!root.__mrKugouProbe) {
    console.warn('[KugouLoginUI] API 路由层未加载，跳过');
    return;
  }

  /** 轮询间隔：与 folia 的 PROVIDER_LOGIN_POLL_INTERVAL_MS 一致。 */
  var POLL_INTERVAL_MS = 2000;
  /** 连续超时几次才判失败：与 folia 的 PROVIDER_LOGIN_TRANSIENT_POLL_LIMIT 一致。 */
  var MAX_TRANSIENT = 2;
  /** 扫码后多久没确认就当过期：与 folia 的 60 秒一致。 */
  var SCANNED_LIMIT_MS = 60 * 1000;

  var state = {
    running: false,
    key: null,
    timer: null,
    scannedAt: 0,
    transientCount: 0,
    /* 用户关掉弹窗或重开时要停下上一轮 */
    generation: 0,
  };

  var $ = function (id) { return document.getElementById(id); };

  var setStatus = function (text, kind) {
    var el = $('qr-status');
    if (!el) return;
    el.textContent = text;
    el.className = kind || '';
  };

  /**
   * 画二维码。
   * 协议层给的是 h5 登录页地址，不是图片，所以要自己画。
   * 优先用页面里已有的二维码库；没有的话退回用 QR 服务出图（离线不可用时自动跳过）。
   */
  /**
   * 撤掉遮在二维码上的「KG 打开官方登录窗口」卡片。
   *
   * 页面的 updateLoginProviderUi 一选中酷狗就给 #qr-shell 加 web-login-preview 类，
   * 那个类会让 #qq-web-login-card 覆盖整个 qr-shell（css/index.css 的 .qq-login-mark）。
   * 桌面版那是「点了才开官方窗口」的入口，安卓上我们是直接出二维码，这层留着就会把码挡住 ——
   * 表现就是「只看到一个打开啥窗口的按钮，没有二维码」。
   */
  var clearPreviewOverlay = function () {
    var shell = $('qr-shell');
    if (shell) {
      shell.classList.remove('web-login-preview', 'qq-preview', 'netease-preview');
    }
    var card = $('qq-web-login-card');
    if (card) card.style.display = 'none';
  };

  var renderQr = function (text) {
    var img = $('qr-img');
    if (!img || !text) return;
    clearPreviewOverlay();
    // 本地编码，不发任何请求。
    // 不用 api.qrserver.com 这类第三方出图：folia 的实测记录里那台服务在部分设备上整个不通，
    // 而二维码不该依赖任何外部服务 —— 一条网络策略就能让所有扫码登录一起失效。
    if (root.MRQrcode && typeof root.MRQrcode.toDataUrl === 'function') {
      try {
        img.src = root.MRQrcode.toDataUrl(text);
        return;
      } catch (error) {
        console.warn('[KugouLoginUI] 本地画二维码失败', error);
      }
    }
    // 内核没加载时留下一句提示，至少让用户知道怎么回事
    setStatus('二维码组件未加载，无法显示', 'fail');
  };

  var clearQr = function () {
    var img = $('qr-img');
    if (img) img.src = '';
  };

  var stopPolling = function () {
    state.generation += 1;
    if (state.timer) {
      clearTimeout(state.timer);
      state.timer = null;
    }
    state.running = false;
    state.key = null;
    stopOverlaySuppression();
  };

  /**
   * 持续压制那个遮罩。
   *
   * 只在几个时机清一次不够：页面的 updateLoginProviderUi 在很多地方被调用，
   * 每次都可能把 web-login-preview 类加回 #qr-shell。这里监听 DOM 变化，
   * 只要当前是酷狗、且我们正在显示二维码，就把遮罩类立刻去掉。
   */
  var overlayObserver = null;
  var startOverlaySuppression = function () {
    if (overlayObserver) return;
    var shell = $('qr-shell');
    if (!shell || typeof MutationObserver === 'undefined') return;
    overlayObserver = new MutationObserver(function () {
      // 只在酷狗扫码流程进行中才压制，别的平台的行为完全不受影响
      if (!state.running) return;
      if (shell.classList.contains('web-login-preview')) {
        shell.classList.remove('web-login-preview');
      }
      var card = $('qq-web-login-card');
      if (card && card.style.display !== 'none') card.style.display = 'none';
    });
    overlayObserver.observe(shell, { attributes: true, attributeFilter: ['class'] });
  };

  var stopOverlaySuppression = function () {
    if (overlayObserver) {
      overlayObserver.disconnect();
      overlayObserver = null;
    }
  };

  /** 确认登录后：保存会话并刷新账号区。 */
  var finishLogin = async function (result) {
    stopPolling();
    clearQr();
    setStatus('酷狗音乐已登录' + (result.nickname ? '：' + result.nickname : ''), 'scan');

    // 扫码返回的字段不一定全：昵称可能有、头像常常没有。
    // 这里主动向 /api/kugou/login/status 拉一次完整账号信息（会走头像补拉与会员探测），
    // 再用它更新状态栏 —— folia 也是登录确认后调一次 getKGLoginStatus，而不是直接用扫码返回值。
    try {
      var statusAnswer = await fetch('/api/kugou/login/status');
      var info = await statusAnswer.json();
      if (info && info.loggedIn) {
        var line = '酷狗音乐已登录';
        var who = info.nickname || info.userId || '';
        if (who) line += '：' + who;
        if (info.vipLabel && info.vipLabel !== '无VIP') line += ' · ' + info.vipLabel;
        setStatus(line, 'scan');
        result = Object.assign({}, result, {
          nickname: info.nickname || result.nickname,
          avatar: info.avatar || result.avatar,
        });
      }
    } catch (error) {
      console.warn('[KugouLoginUI] 拉取账号信息失败，沿用扫码返回', error);
    }

    // 让页面的账号状态跟上（与桌面版登录成功后做的事一致）
    try {
      if (typeof root.refreshKugouLoginStatus === 'function') {
        await root.refreshKugouLoginStatus();
      }
      if (typeof root.renderUserBtn === 'function') root.renderUserBtn();
      if (typeof root.refreshUserPlaylists === 'function') root.refreshUserPlaylists(true);
      if (typeof root.showToast === 'function') {
        root.showToast('酷狗音乐已登录' + (result.nickname ? '：' + result.nickname : ''));
      }
      if (typeof root.closeLoginModal === 'function') {
        setTimeout(function () { root.closeLoginModal(); }, 500);
      }
    } catch (error) {
      console.warn('[KugouLoginUI] 刷新账号状态失败', error);
    }
  };

  var pollOnce = async function (generation) {
    if (generation !== state.generation || !state.key) return;
    var key = state.key;

    var answer;
    try {
      answer = await root.__mrKugouProbe.qrCheck(key);
    } catch (error) {
      // 与 folia 一致：瞬时失败在容忍次数内当作「还没扫」，超过才判失败
      state.transientCount += 1;
      if (state.transientCount <= MAX_TRANSIENT) {
        setStatus('网络不稳，正在重试…', 'preview');
        scheduleNext(generation);
        return;
      }
      setStatus('登录失败：' + (error && error.message || error), 'fail');
      stopPolling();
      return;
    }
    state.transientCount = 0;
    if (generation !== state.generation) return;

    if (answer.state === 'waiting') {
      setStatus('请用酷狗 App 扫码', 'preview');
      scheduleNext(generation);
      return;
    }
    if (answer.state === 'scanned') {
      if (!state.scannedAt) state.scannedAt = Date.now();
      // 扫码后一直不确认：照 folia 的 60 秒上限结束，等平台自己报过期不可靠
      if (Date.now() - state.scannedAt > SCANNED_LIMIT_MS) {
        setStatus('二维码已过期，请点「开始登录」刷新', 'fail');
        clearQr();
        stopPolling();
        return;
      }
      setStatus('已扫码，请在手机上确认', 'scan');
      scheduleNext(generation);
      return;
    }
    if (answer.state === 'confirmed') {
      await finishLogin(answer);
      return;
    }
    if (answer.state === 'expired') {
      setStatus('二维码已过期，请点「开始登录」刷新', 'fail');
      clearQr();
      stopPolling();
      return;
    }
    // error
    setStatus(answer.message || '登录失败，请刷新二维码', 'fail');
    clearQr();
    stopPolling();
  };

  var scheduleNext = function (generation) {
    if (generation !== state.generation) return;
    state.timer = setTimeout(function () { void pollOnce(generation); }, POLL_INTERVAL_MS);
  };

  /**
   * 把底层请求错误翻译成人话。
   * 最常见的两种：手机没网（DNS 解析失败 / 无默认网络）和接口真的报错。
   */
  var describeError = function (error) {
    var message = String((error && error.message) || error || '');
    if (/Unable to resolve host|No address associated with hostname|Failed to fetch|Network is unreachable|ENETUNREACH|ECONNREFUSED/i.test(message)) {
      return '网络未连接，请先连接 Wi-Fi 或移动数据后重试';
    }
    if (/timeout|timed out/i.test(message)) return '网络超时，请检查网络后重试';
    return message || '未知错误';
  };

  /**
   * 入口：安卓上的酷狗扫码登录。
   * 与桌面版 openKugouWebLogin 同名同位置，页面那边的调用点不用改。
   */
  var openKugouQrLogin = async function (force) {
    // force=true 时允许打断进行中的会话重新出码（点二维码刷新 / 点「开始登录」用）；
    // 以前无条件 return，上一轮会话没结束时再点只会被静默吞掉、码不刷新。
    if (state.running && !force) return;
    stopPolling();
    var generation = state.generation;
    state.running = true;
    state.scannedAt = 0;
    state.transientCount = 0;
    startOverlaySuppression();

    setStatus('正在获取二维码…', 'preview');
    clearQr();
    clearPreviewOverlay();

    try {
      var keyAnswer = await root.__mrKugouProbe.qrKey();
      if (generation !== state.generation) return;
      state.key = keyAnswer.key;

      var qrAnswer = await root.__mrKugouProbe.qrCreate(keyAnswer.key);
      if (generation !== state.generation) return;
      renderQr(qrAnswer.url || qrAnswer.qrimg || '');
      setStatus('请用酷狗 App 扫码', 'preview');

      // 立刻开始轮询
      scheduleNext(generation);
    } catch (error) {
      setStatus('获取二维码失败：' + describeError(error), 'fail');
      stopPolling();
    }
  };

  // 接管页面的酷狗登录入口。
  // 页面里的 openKugouWebLogin 走 window.desktopWindow.openKugouMusicLogin（Electron 专用），
  // 安卓上没有那个 API，原函数会直接报「当前环境不支持自动网页登录」。
  // 这里是页面唯一调用它的地方（openProviderWebLogin → openKugouWebLogin），所以替换它即可，
  // 其他平台的登录一行不动。
  root.__mrOpenKugouQrLogin = openKugouQrLogin;
  if (typeof root.openKugouWebLogin === 'function') {
    var originalKugouLogin = root.openKugouWebLogin;
    root.openKugouWebLogin = function () {
      // 桌面环境（有 desktopWindow）仍走原路径，安卓才接管
      if (root.desktopWindow && typeof root.desktopWindow.openKugouMusicLogin === 'function') {
        return originalKugouLogin.apply(this, arguments);
      }
      return openKugouQrLogin.apply(this, arguments);
    };
    console.log('[KugouLoginUI] 已接管 openKugouWebLogin');
  } else {
    console.warn('[KugouLoginUI] 页面没有 openKugouWebLogin，按钮可能需要别的接法');
  }
  root.__mrStopKugouQrLogin = function () {
    stopPolling();
    clearQr();
  };

  // 关掉登录弹窗时停掉轮询，免得在后台一直请求
  var originalClose = root.closeLoginModal;
  if (typeof originalClose === 'function') {
    root.closeLoginModal = function () {
      root.__mrStopKugouQrLogin();
      return originalClose.apply(this, arguments);
    };
  }

  console.log('[KugouLoginUI] 酷狗扫码登录已接管');
})(window);
