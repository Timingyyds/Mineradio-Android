// public/js/android-shim/00-node-shim.js
// Node 内置模块的浏览器替代层。
//
// 桌面版 Mineradio 的后端是 Node（server.js + kugou-api.js 等），这些模块用 require('crypto')、
// require('http') 之类拿内置能力。安卓上没有 Node，所以这里造一份同名同形的替身，
// 让移植进来的平台模块原样跑起来，不必逐行改写。
//
// 覆盖范围是实际用到的那几样（已 grep 核对）：
//   crypto  → createHash('md5'|'sha256')、randomBytes
//   http/https → 发请求（转给 FoliaBridge 原生 OkHttp，绕开 CORS 与混合内容）
//   fs      → existsSync / readFileSync / writeFileSync 等（转给 localStorage 或原生 Filesystem）
//   path / url / tls / os → 纯字符串处理或空实现
//
// 全部挂在 window.__nodeShim 上，由移植后的模块从这里取，不污染全局。

(function (root) {
  'use strict';

  // ─────────────────────────────────────────────────────────────
  // Buffer：只实现移植代码真正用到的那几个方法
  // ─────────────────────────────────────────────────────────────

  function NodeBuffer(input) {
    if (!(this instanceof NodeBuffer)) return new NodeBuffer(input);
    this._bytes = NodeBuffer._toBytes(input);
  }

  NodeBuffer._toBytes = function (input) {
    if (input instanceof NodeBuffer) return input._bytes.slice();
    if (input instanceof Uint8Array) return Uint8Array.from(input);
    if (input instanceof ArrayBuffer) return new Uint8Array(input.slice(0));
    if (Array.isArray(input)) return Uint8Array.from(input);
    if (typeof input === 'string') return NodeBuffer._utf8Encode(input);
    if (input === undefined || input === null) return new Uint8Array(0);
    throw new TypeError('NodeBuffer: unsupported input');
  };

  // 用 TextEncoder 把字符串编成 UTF-8 字节
  NodeBuffer._utf8Encode = (function () {
    var encoder = typeof TextEncoder !== 'undefined' ? new TextEncoder() : null;
    return function (text) {
      if (encoder) return new Uint8Array(encoder.encode(text));
      // 没有 TextEncoder 时的退化实现
      var out = [];
      for (var i = 0; i < text.length; i++) {
        var c = text.charCodeAt(i);
        if (c < 0x80) out.push(c);
        else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
        else out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      }
      return Uint8Array.from(out);
    };
  })();

  NodeBuffer._utf8Decode = (function () {
    var decoder = typeof TextDecoder !== 'undefined' ? new TextDecoder() : null;
    return function (bytes) {
      if (decoder) return decoder.decode(bytes);
      var out = '';
      for (var i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
      return out;
    };
  })();

  NodeBuffer.from = function (input, encoding) {
    if (typeof input === 'string' && (encoding === 'hex' || encoding === 'base64')) {
      return new NodeBuffer(NodeBuffer._decodeText(input, encoding));
    }
    return new NodeBuffer(input);
  };

  NodeBuffer._decodeText = function (text, encoding) {
    if (encoding === 'hex') {
      var clean = String(text).replace(/[^0-9a-fA-F]/g, '');
      var out = new Uint8Array(clean.length >> 1);
      for (var i = 0; i < out.length; i++) out[i] = parseInt(clean.substr(i * 2, 2), 16);
      return out;
    }
    if (encoding === 'base64') {
      var binary = atob(String(text).replace(/\s+/g, ''));
      var bytes = new Uint8Array(binary.length);
      for (var j = 0; j < binary.length; j++) bytes[j] = binary.charCodeAt(j);
      return bytes;
    }
    return NodeBuffer._utf8Encode(String(text));
  };

  NodeBuffer.isBuffer = function (value) { return value instanceof NodeBuffer; };

  NodeBuffer.prototype.toString = function (encoding) {
    var bytes = this._bytes;
    if (encoding === 'hex') {
      var hex = '';
      for (var i = 0; i < bytes.length; i++) hex += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
      return hex;
    }
    if (encoding === 'base64') {
      var binary = '';
      for (var j = 0; j < bytes.length; j++) binary += String.fromCharCode(bytes[j]);
      return btoa(binary);
    }
    return NodeBuffer._utf8Decode(bytes);
  };

  NodeBuffer.prototype.length = 0; // 由下面 defineProperty 换成真值
  Object.defineProperty(NodeBuffer.prototype, 'length', {
    get: function () { return this._bytes.length; },
  });

  NodeBuffer.prototype.toJSON = function () {
    return { type: 'Buffer', data: Array.from(this._bytes) };
  };

  NodeBuffer.concat = function (list) {
    // 入参可能是 NodeBuffer、Uint8Array、ArrayBuffer 或字符串，统一转成字节
    var total = 0;
    var normalized = [];
    for (var i = 0; i < list.length; i++) {
      var bytes = NodeBuffer._toBytes(list[i]);
      normalized.push(bytes);
      total += bytes.length;
    }
    var out = new Uint8Array(total);
    var offset = 0;
    for (var j = 0; j < normalized.length; j++) {
      out.set(normalized[j], offset);
      offset += normalized[j].length;
    }
    return new NodeBuffer(out);
  };

  // ─────────────────────────────────────────────────────────────
  // MD5：Web Crypto 不提供，必须自己实现
  // 酷狗的每个签名都用 md5（见 kugou-api.js 里的 createHash('md5')）
  // ─────────────────────────────────────────────────────────────

  var MD5_S = [7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21];

  var MD5_K = (function () {
    var k = new Int32Array(64);
    for (var i = 0; i < 64; i++) k[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) | 0;
    return k;
  })();

  function md5Bytes(inputBytes) {
    var bytes = NodeBuffer._toBytes(inputBytes);
    var length = bytes.length;
    // 补位：原文 + 0x80 + 若干 0，直到长度 ≡ 56 (mod 64)，再接 8 字节长度
    var paddedLength = (((length + 8) >> 6) + 1) << 6;
    var buffer = new Uint8Array(paddedLength);
    buffer.set(bytes);
    buffer[length] = 0x80;
    // 长度按小端写入最后 8 字节（位）
    var bitLengthLow = (length << 3) >>> 0;
    var bitLengthHigh = Math.floor(length / 0x20000000) >>> 0;
    buffer[paddedLength - 8] = bitLengthLow & 0xff;
    buffer[paddedLength - 7] = (bitLengthLow >>> 8) & 0xff;
    buffer[paddedLength - 6] = (bitLengthLow >>> 16) & 0xff;
    buffer[paddedLength - 5] = (bitLengthLow >>> 24) & 0xff;
    buffer[paddedLength - 4] = bitLengthHigh & 0xff;
    buffer[paddedLength - 3] = (bitLengthHigh >>> 8) & 0xff;
    buffer[paddedLength - 2] = (bitLengthHigh >>> 16) & 0xff;
    buffer[paddedLength - 1] = (bitLengthHigh >>> 24) & 0xff;

    var a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
    var M = new Int32Array(16);

    for (var offset = 0; offset < paddedLength; offset += 64) {
      for (var m = 0; m < 16; m++) {
        M[m] = buffer[offset + m * 4]
          | (buffer[offset + m * 4 + 1] << 8)
          | (buffer[offset + m * 4 + 2] << 16)
          | (buffer[offset + m * 4 + 3] << 24);
      }
      var A = a0, B = b0, C = c0, D = d0;
      for (var i = 0; i < 64; i++) {
        var F, g;
        if (i < 16) { F = (B & C) | (~B & D); g = i; }
        else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) & 15; }
        else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) & 15; }
        else { F = C ^ (B | ~D); g = (7 * i) & 15; }
        F = (F + A + MD5_K[i] + M[g]) | 0;
        A = D;
        D = C;
        C = B;
        B = (B + ((F << MD5_S[i]) | (F >>> (32 - MD5_S[i])))) | 0;
      }
      a0 = (a0 + A) | 0;
      b0 = (b0 + B) | 0;
      c0 = (c0 + C) | 0;
      d0 = (d0 + D) | 0;
    }

    var out = new Uint8Array(16);
    var words = [a0, b0, c0, d0];
    for (var w = 0; w < 4; w++) {
      var value = words[w];
      out[w * 4] = value & 0xff;
      out[w * 4 + 1] = (value >>> 8) & 0xff;
      out[w * 4 + 2] = (value >>> 16) & 0xff;
      out[w * 4 + 3] = (value >>> 24) & 0xff;
    }
    return out;
  }

  // ─────────────────────────────────────────────────────────────
  // SHA-256：Web Crypto 的 digest 是异步的，而移植代码要同步拿摘要，
  // 所以这里自己实现一个同步版（kugou-api 用它做 vip 缓存键）。
  // ─────────────────────────────────────────────────────────────

  var SHA256_K = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];

  function sha256Bytes(inputBytes) {
    var bytes = NodeBuffer._toBytes(inputBytes);
    var length = bytes.length;
    var paddedLength = (((length + 8) >> 6) + 1) << 6;
    var buffer = new Uint8Array(paddedLength);
    buffer.set(bytes);
    buffer[length] = 0x80;
    // 长度按大端写入最后 8 字节（位）
    var bitLengthHigh = Math.floor(length / 0x20000000) >>> 0;
    var bitLengthLow = (length << 3) >>> 0;
    buffer[paddedLength - 8] = (bitLengthHigh >>> 24) & 0xff;
    buffer[paddedLength - 7] = (bitLengthHigh >>> 16) & 0xff;
    buffer[paddedLength - 6] = (bitLengthHigh >>> 8) & 0xff;
    buffer[paddedLength - 5] = bitLengthHigh & 0xff;
    buffer[paddedLength - 4] = (bitLengthLow >>> 24) & 0xff;
    buffer[paddedLength - 3] = (bitLengthLow >>> 16) & 0xff;
    buffer[paddedLength - 2] = (bitLengthLow >>> 8) & 0xff;
    buffer[paddedLength - 1] = bitLengthLow & 0xff;

    var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
      0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    var W = new Int32Array(64);

    var rotr = function (x, n) { return (x >>> n) | (x << (32 - n)); };

    for (var offset = 0; offset < paddedLength; offset += 64) {
      for (var i = 0; i < 16; i++) {
        W[i] = (buffer[offset + i * 4] << 24)
          | (buffer[offset + i * 4 + 1] << 16)
          | (buffer[offset + i * 4 + 2] << 8)
          | buffer[offset + i * 4 + 3];
      }
      for (var j = 16; j < 64; j++) {
        var s0 = rotr(W[j - 15], 7) ^ rotr(W[j - 15], 18) ^ (W[j - 15] >>> 3);
        var s1 = rotr(W[j - 2], 17) ^ rotr(W[j - 2], 19) ^ (W[j - 2] >>> 10);
        W[j] = (W[j - 16] + s0 + W[j - 7] + s1) | 0;
      }
      var a = H[0], b = H[1], c = H[2], d = H[3];
      var e = H[4], f = H[5], g = H[6], h = H[7];

      for (var t2 = 0; t2 < 64; t2++) {
        var S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        var ch = (e & f) ^ (~e & g);
        var temp1 = (h + S1 + ch + SHA256_K[t2] + W[t2]) | 0;
        var S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        var maj = (a & b) ^ (a & c) ^ (b & c);
        var temp2 = (S0 + maj) | 0;
        h = g; g = f; f = e;
        e = (d + temp1) | 0;
        d = c; c = b; b = a;
        a = (temp1 + temp2) | 0;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0;
      H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
      H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0;
      H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
    }

    var out = new Uint8Array(32);
    for (var k2 = 0; k2 < 8; k2++) {
      out[k2 * 4] = (H[k2] >>> 24) & 0xff;
      out[k2 * 4 + 1] = (H[k2] >>> 16) & 0xff;
      out[k2 * 4 + 2] = (H[k2] >>> 8) & 0xff;
      out[k2 * 4 + 3] = H[k2] & 0xff;
    }
    return out;
  }

  // ─────────────────────────────────────────────────────────────
  // crypto 替身
  // ─────────────────────────────────────────────────────────────

  function createHash(algorithm) {
    var name = String(algorithm).toLowerCase().replace(/-/g, '');
    var chunks = [];
    var api = {
      update: function (data) {
        chunks.push(NodeBuffer._toBytes(typeof data === 'string' ? NodeBuffer._utf8Encode(data) : data));
        return api;
      },
      digest: function (encoding) {
        // 把分块拼起来
        var total = 0;
        for (var i = 0; i < chunks.length; i++) total += chunks[i].length;
        var merged = new Uint8Array(total);
        var at = 0;
        for (var j = 0; j < chunks.length; j++) {
          merged.set(chunks[j], at);
          at += chunks[j].length;
        }
        if (name === 'md5') {
          var digestBytes = new NodeBuffer(md5Bytes(merged));
          return encoding === 'hex' ? digestBytes.toString('hex') : digestBytes;
        }
        if (name === 'sha256') {
          var shaBytes = new NodeBuffer(sha256Bytes(merged));
          return encoding === 'hex' ? shaBytes.toString('hex') : shaBytes;
        }
        throw new Error('nodeShim: hash ' + algorithm + ' not implemented');
      },
    };
    return api;
  }

  async function digestAsync(algorithm, dataBytes) {
    var subtle = (typeof crypto !== 'undefined' && crypto.subtle) ? crypto.subtle : null;
    if (!subtle) throw new Error('nodeShim: Web Crypto unavailable');
    var name = String(algorithm).toLowerCase();
    var buffer = await subtle.digest(name, dataBytes);
    return new Uint8Array(buffer);
  }

  var nodeCrypto = {
    createHash: createHash,
    randomBytes: function (size) {
      var out = new Uint8Array(size);
      if (typeof crypto !== 'undefined' && crypto.getRandomValues) crypto.getRandomValues(out);
      else for (var i = 0; i < size; i++) out[i] = Math.floor(Math.random() * 256);
      return new NodeBuffer(out);
    },
    // 给需要异步摘要的调用方用
    _digestAsync: digestAsync,
  };

  // ─────────────────────────────────────────────────────────────
  // http / https 替身：转给原生桥
  // ─────────────────────────────────────────────────────────────

  function bridgeAvailable() {
    return Boolean(root.Capacitor && typeof root.Capacitor.isNativePlatform === 'function' && root.Capacitor.isNativePlatform());
  }

  // 原生桥插件（由 app 侧的 audioNative/core.ts 注册成 window.__mrBridgeRequest）
  function requestViaBridge(options) {
    var send = root.__mrBridgeRequest;
    if (typeof send !== 'function') {
      return Promise.reject(new Error('nodeShim: native bridge unavailable'));
    }
    return send(options);
  }

  function createHttpModule(defaultProtocol) {
    function request(target, opts, callback) {
      var options = opts || {};
      // 兼容 http.request(url, cb) 与 http.request(options, cb)
      var urlString;
      if (typeof target === 'string') urlString = target;
      else if (target && target.href) urlString = target.href;
      else if (target && typeof target === 'object') {
        var protocol = target.protocol || defaultProtocol;
        var host = target.hostname || target.host || 'localhost';
        var port = target.port ? ':' + target.port : '';
        urlString = protocol + '//' + host + port + (target.path || '/');
      } else {
        urlString = String(target || '');
      }

      var listeners = { data: [], end: [], error: [] };
      var settled = false;
      var chunks = [];
      var dispatched = false;

      // 真正的请求：把 req.write() 攒下来的内容当 body 发出去。
      // 老实现里 write/end 是空函数，移植代码的 POST 于是变成「没有 body 的 POST」，
      // 原生桥直接拒绝（method POST must have a request body），歌单/电台全空。
      var dispatch = function () {
        if (dispatched || settled) return;
        dispatched = true;
        var method = String(options.method || 'GET').toUpperCase();
        var bodyText = options.body != null
          ? options.body
          : (chunks.length ? chunks.join('') : undefined);
        if (bodyText === undefined && method === 'POST') bodyText = '';
        requestViaBridge({
          url: urlString,
          method: method,
          headers: options.headers || {},
          body: bodyText,
          timeoutMs: options.timeoutMs,
          binary: false,
        }).then(function (answer) {
          if (settled) return;
          var body = answer.body || '';
          var response = {
            statusCode: answer.status,
            headers: answer.headers || {},
            on: function (event, handler) {
              if (event === 'data' && handler) {
                handler(new NodeBuffer(body));
              } else if (event === 'end' && handler) {
                handler();
              } else if (event === 'error' && handler) {
                // 已经成功，不触发
              }
              return response;
            },
          };
          if (typeof callback === 'function') callback(response);
        }).catch(function (error) {
          if (settled) return;
          listeners.error.forEach(function (handler) { handler(error); });
        });
      };

      var req = {
        on: function (event, handler) {
          if (listeners[event]) listeners[event].push(handler);
          return req;
        },
        once: function (event, handler) { return req.on(event, handler); },
        write: function (chunk) {
          if (chunk != null) {
            chunks.push(typeof chunk === 'string' ? chunk : new NodeBuffer(chunk).toString());
          }
          return req;
        },
        end: function (chunk) {
          if (chunk != null) req.write(chunk);
          // 下一个 tick 再发，让调用方有机会先挂 on('error') 等监听
          setTimeout(dispatch, 0);
          return req;
        },
        destroy: function () { settled = true; return req; },
        setTimeout: function () { return req; },
        abort: function () { settled = true; },
      };

      return req;
    }
    return { request: request, get: function (target, opts, cb) { return request(target, opts, cb); } };
  }

  var nodeHttp = createHttpModule('http:');
  var nodeHttps = createHttpModule('https:');

  // ─────────────────────────────────────────────────────────────
  // fs 替身：存 localStorage（小文件）——移植代码只用它存 cookie 缓存等
  // ─────────────────────────────────────────────────────────────

  var FS_PREFIX = '__mrfs:';

  var nodeFs = {
    existsSync: function (filePath) {
      try { return localStorage.getItem(FS_PREFIX + filePath) !== null; } catch (e) { return false; }
    },
    readFileSync: function (filePath) {
      try {
        var raw = localStorage.getItem(FS_PREFIX + filePath);
        if (raw === null) {
          var error = new Error("ENOENT: no such file or directory, open '" + filePath + "'");
          error.code = 'ENOENT';
          throw error;
        }
        return new NodeBuffer(NodeBuffer._utf8Encode(raw));
      } catch (e) { throw e; }
    },
    writeFileSync: function (filePath, data) {
      try {
        var text = (data instanceof NodeBuffer) ? data.toString() : String(data);
        localStorage.setItem(FS_PREFIX + filePath, text);
      } catch (e) { /* 配额满了等：静默失败，与桌面端的容错一致 */ }
    },
    mkdirSync: function () { /* 扁平命名空间，无需建目录 */ },
    unlinkSync: function (filePath) { try { localStorage.removeItem(FS_PREFIX + filePath); } catch (e) {} },
    renameSync: function (from, to) {
      try {
        var value = localStorage.getItem(FS_PREFIX + from);
        if (value !== null) {
          localStorage.setItem(FS_PREFIX + to, value);
          localStorage.removeItem(FS_PREFIX + from);
        }
      } catch (e) {}
    },
    readdirSync: function () { return []; },
    statSync: function (filePath) {
      return { isFile: function () { return nodeFs.existsSync(filePath); }, isDirectory: function () { return false; }, size: 0 };
    },
  };

  // ─────────────────────────────────────────────────────────────
  // path / url / tls / os：纯字符串处理或空实现
  // ─────────────────────────────────────────────────────────────

  var nodePath = {
    join: function () {
      var parts = Array.prototype.slice.call(arguments).filter(function (p) { return p !== '' && p != null; });
      return parts.join('/').replace(/\/+/g, '/');
    },
    resolve: function () { return nodePath.join.apply(null, arguments); },
    basename: function (p) { return String(p).split('/').pop(); },
    dirname: function (p) { var s = String(p).split('/'); s.pop(); return s.join('/') || '.'; },
    extname: function (p) { var b = nodePath.basename(p); var i = b.lastIndexOf('.'); return i > 0 ? b.slice(i) : ''; },
    sep: '/',
    delimiter: ':',
  };

  var nodeUrl = {
    URL: root.URL,
    parse: function (value) { try { return new URL(value); } catch (e) { return null; } },
    fileURLToPath: function (value) { return String(value).replace(/^file:\/\//, ''); },
  };

  var nodeTls = {
    // 桌面版只用 tls 读证书，安卓不需要
    connect: function () { throw new Error('nodeShim: tls is not supported'); },
  };

  var nodeOs = {
    tmpdir: function () { return '/tmp'; },
    platform: function () { return 'android'; },
    homedir: function () { return '/'; },
    cpus: function () { return [{ model: 'arm64', speed: 0 }]; },
  };

  // ─────────────────────────────────────────────────────────────
  // 暴露出去
  // ─────────────────────────────────────────────────────────────

  root.__nodeShim = {
    Buffer: NodeBuffer,
    crypto: nodeCrypto,
    http: nodeHttp,
    https: nodeHttps,
    fs: nodeFs,
    path: nodePath,
    url: nodeUrl,
    tls: nodeTls,
    os: nodeOs,
    // 给测试用
    _md5Hex: function (text) { return new NodeBuffer(md5Bytes(NodeBuffer._utf8Encode(text))).toString('hex'); },
    _sha256Hex: function (text) { return new NodeBuffer(sha256Bytes(NodeBuffer._utf8Encode(text))).toString('hex'); },
  };

  // 移植代码里大量出现全局 Buffer，这里提供一个受控的别名
  if (typeof root.Buffer === 'undefined') root.Buffer = NodeBuffer;
})(window);
