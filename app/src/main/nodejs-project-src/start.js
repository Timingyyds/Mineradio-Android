// Mineradio Android Node 后端启动入口
// 由 NodeJSMobile.java 以 startNodeWithArguments(["node", "<path>/start.js"]) 启动
// 职责:加载 server.js,监听就绪后通过 rn_bridge 通知 Java 层 onNodeReady
(function () {
  // ★ MENC 解密拦截器:必须在 require 任何加密 .js 文件之前注入
  //   加密格式: MENC(4) + IV(16) + AES-256-CBC/PKCS5 密文
  //   密钥 = SHA-256("Sp1ca@Minerad1o#2024$SecureAssetKey!")
  //   与 build.gradle 的 prepareNodejsProject task 使用相同 passphrase
  //   原理: hook Module._extensions['.js'],检测 MENC 魔数后用 latin1 重读保留字节,
  //         AES-CBC 解密后转为 utf8 源码再 _compile
  (function installMencDecryptHook() {
    var crypto = require('crypto');
    var fs = require('fs');
    var Module = require('module');
    var PASSPHRASE = 'Sp1ca@Minerad1o#2024$SecureAssetKey!';
    var KEY = crypto.createHash('sha256').update(PASSPHRASE).digest(); // 32 bytes
    var origJsLoader = Module._extensions['.js'];
    Module._extensions['.js'] = function (module, filename) {
      var content = fs.readFileSync(filename, 'utf8');
      if (content.length >= 20 &&
          content[0] === 'M' && content[1] === 'E' && content[2] === 'N' && content[3] === 'C') {
        var raw = fs.readFileSync(filename, 'latin1');
        var buf = Buffer.from(raw, 'latin1');
        var iv = buf.slice(4, 20);
        var ct = buf.slice(20);
        var decipher = crypto.createDecipheriv('aes-256-cbc', KEY, iv);
        var plain = Buffer.concat([decipher.update(ct), decipher.final()]);
        content = plain.toString('utf8');
        console.log('[start.js] decrypted MENC module: ' + filename);
      }
      module._compile(content, filename);
    };
    console.log('[start.js] MENC decrypt hook installed');
  })();

  var path = require('path');
  var os = require('os');

  // Android 修复:os.tmpdir() 默认返回 /tmp(无写权限),改为应用缓存目录
  // 必须在 require server.js 之前执行,因为 NeteaseCloudMusicApi 加载时即写 tmpdir
  var fs = require('fs');
  var tmpRoot = process.env.TMPDIR || (process.env.HOME && process.env.HOME + '/cache') || '/data/data/com.mineradio.app/cache';
  try { if (!fs.existsSync(tmpRoot)) fs.mkdirSync(tmpRoot, { recursive: true }); } catch (e) {}
  process.env.TMPDIR = tmpRoot;
  process.env.TMP = tmpRoot;
  process.env.TEMP = tmpRoot;
  // monkey-patch os.tmpdir 确保返回可写目录
  os.tmpdir = function () { return tmpRoot; };

  // Android 缓存目录:使用应用专属外部存储目录(无需 root,用户可通过文件管理器访问)
  // Java 层(MainActivity)已创建目录并将路径写入 getFilesDir()/mineradio-cache-path.txt
  var androidExtStorage = '';
  try {
    var pathFile = (process.env.HOME && process.env.HOME + '/mineradio-cache-path.txt') || '/data/data/com.mineradio.app/files/mineradio-cache-path.txt';
    if (fs.existsSync(pathFile)) {
      androidExtStorage = fs.readFileSync(pathFile, 'utf8').trim();
    }
  } catch (e) {}
  // 如果读取失败,使用默认路径
  if (!androidExtStorage) {
    androidExtStorage = '/storage/emulated/0/Android/data/com.mineradio.app/files/Mineradio';
  }
  try { if (!fs.existsSync(androidExtStorage)) fs.mkdirSync(androidExtStorage, { recursive: true }); } catch (e) {}
  if (!process.env.MINERADIO_BEAT_CACHE_DIR) process.env.MINERADIO_BEAT_CACHE_DIR = androidExtStorage + '/beatmaps';
  if (!process.env.MINERADIO_LYRIC_CACHE_DIR) process.env.MINERADIO_LYRIC_CACHE_DIR = androidExtStorage + '/lyrics';
  if (!process.env.MINERADIO_COVER_CACHE_DIR) process.env.MINERADIO_COVER_CACHE_DIR = androidExtStorage + '/covers';
  if (!process.env.MINERADIO_AUDIO_CACHE_DIR) process.env.MINERADIO_AUDIO_CACHE_DIR = androidExtStorage + '/audio';
  if (!process.env.MINERADIO_CACHE_ROOT) process.env.MINERADIO_CACHE_ROOT = androidExtStorage;

  // Node 项目根目录(start.js 所在目录)
  var projectDir = __dirname;

  // 加载 rn_bridge(nodejs-mobile 内置链接模块,提供与 Java 的双向通道)
  var rnBridge = null;
  try {
    rnBridge = require('rn_bridge');
  } catch (e) {
    console.log('[start.js] rn_bridge not available, running standalone');
  }

  // 把项目目录加入 module 搜索路径,让 server.js 能 require ./qq-vip-api 等
  try {
    require('module').globalPaths.push(projectDir);
  } catch (e) {}

  // 环境变量兜底(Java 层已设置,这里防止直连场景)
  process.env.HOST = process.env.HOST || '127.0.0.1';
  process.env.PORT = process.env.PORT || '3000';

  console.log('[start.js] projectDir=' + projectDir);
  console.log('[start.js] HOST=' + process.env.HOST + ' PORT=' + process.env.PORT);
  console.log('[start.js] node version=' + process.version);

  // 监听全局未捕获异常,避免后端静默崩溃
  process.on('uncaughtException', function (err) {
    console.error('[start.js] uncaughtException:', err && err.stack || err);
  });
  process.on('unhandledRejection', function (reason) {
    console.error('[start.js] unhandledRejection:', reason);
  });

  var serverReady = false;
  function notifyJavaReady() {
    if (serverReady) return;
    serverReady = true;
    if (rnBridge && typeof rnBridge.sendMessage === 'function') {
      try {
        rnBridge.sendMessage('_SYSTEM_', 'server-listening');
        console.log('[start.js] notified Java: server-listening');
      } catch (e) {
        console.error('[start.js] notify Java failed:', e);
      }
    }
  }

  // 加载 server.js
  var serverModulePath = path.join(projectDir, 'server.js');
  console.log('[start.js] loading server from ' + serverModulePath);

  try {
    var server = require(serverModulePath);
    console.log('[start.js] server.js required, type=' + typeof server);

    // server.js 导出 http.Server 实例时,监听 listening 事件
    if (server && typeof server.on === 'function') {
      server.on('listening', function () {
        var addr = server.address();
        console.log('[start.js] server listening on ' + (addr && addr.address) + ':' + (addr && addr.port));
        notifyJavaReady();
      });
      server.on('error', function (err) {
        console.error('[start.js] server error:', err);
      });
      // 若已经在监听(同步加载完即 listen),立即通知
      if (server.listening) {
        console.log('[start.js] server already listening');
        notifyJavaReady();
      }
    } else {
      // server.js 不导出 Server 实例时,延迟探测 HTTP 端口
      console.log('[start.js] server.js did not export a Server, probing http port...');
      var http = require('http');
      var probePort = Number(process.env.PORT) || 3000;
      var probeAttempts = 0;
      function probe() {
        probeAttempts++;
        var req = http.get({ host: '127.0.0.1', port: probePort, path: '/', timeout: 800 }, function (res) {
          res.resume();
          console.log('[start.js] probe got ' + res.statusCode + ' on port ' + probePort);
          notifyJavaReady();
        });
        req.on('error', function () {
          if (probeAttempts < 40) setTimeout(probe, 500);
          else console.error('[start.js] probe gave up after ' + probeAttempts + ' attempts');
        });
        req.on('timeout', function () { req.destroy(); });
      }
      setTimeout(probe, 300);
    }
  } catch (e) {
    console.error('[start.js] failed to load server.js:', e && e.stack || e);
    if (rnBridge) {
      try { rnBridge.sendMessage('_SYSTEM_', 'server-failed:' + (e && e.message || e)); } catch (_) {}
    }
    throw e;
  }
})();
