// scripts/bundle-qrcode.cjs
// 把 qrcode 的编码内核打包成一个浏览器可直接加载的文件。
//
// 为什么不用 CDN 的 qrcode 库、也不调 api.qrserver.com：
// folia 的实测记录（0.7.17-android.124）写着那台第三方出图服务在这类设备上整个不通，
// 而二维码不是任何平台的一部分 —— 一条网络策略就能让所有扫码登录一起失效。
// 这里只在本地编码：算出二维码的模块矩阵，再拼成 SVG data URL，不发任何请求。
//
// 产出一个 window.MRQrcode = { toDataUrl } 的全局对象。

'use strict';

const fs = require('fs');
const path = require('path');

const QR_ROOT = process.env.QR_ROOT
  || '/storage/emulated/0/我的程序/folia-android-main/node_modules/qrcode/lib/core';
const OUT = process.env.QR_OUT
  || path.join(__dirname, '..', 'public', 'js', 'android-shim', 'qrcode-core.js');

/** qrcode 内核里的模块清单（lib/core 下那些互相 require 的文件）。 */
const MODULES = [
  'alignment-pattern',
  'alphanumeric-data',
  'bit-buffer',
  'bit-matrix',
  'byte-data',
  'error-correction-code',
  'error-correction-level',
  'finder-pattern',
  'format-info',
  'galois-field',
  'kanji-data',
  'mask-pattern',
  'mode',
  'numeric-data',
  'polynomial',
  'qrcode',
  'reed-solomon-encoder',
  'regex',
  'segments',
  'utils',
  'version-check',
  'version',
];

/** 外部小依赖：这些包只被内核间接用到，但内容很小，一并打进来。 */
const EXTRA_MODULES = [
  // segments.js 用它在「按什么编码方式分段最省」的图里找最短路
  { name: 'dijkstrajs', file: process.env.DIJKSTRA_PATH
      || '/storage/emulated/0/我的程序/folia-android-main/node_modules/dijkstrajs/dijkstra.js' },
];

function build() {
  const parts = [];
  parts.push('// 由 scripts/bundle-qrcode.cjs 生成，请勿手改。');
  parts.push('// 来源：qrcode 包的 lib/core（MIT），只保留编码内核，去掉 Node 依赖。');
  parts.push('(function () {');
  parts.push("  'use strict';");
  parts.push('  var __defs = {};');
  parts.push('  var __cache = {};');
  parts.push('');
  parts.push('  function __require(name) {');
  parts.push('    if (__cache[name]) return __cache[name].exports;');
  parts.push('    var def = __defs[name];');
  parts.push('    if (!def) throw new Error("qrcode-core: missing module " + name);');
  parts.push('    var module = { exports: {} };');
  parts.push('    __cache[name] = module;');
  parts.push('    def(module, module.exports, __require);');
  parts.push('    return module.exports;');
  parts.push('  }');
  parts.push('');

  for (const name of MODULES) {
    const file = path.join(QR_ROOT, name + '.js');
    if (!fs.existsSync(file)) {
      console.warn('  跳过不存在的模块: ' + name);
      continue;
    }
    let code = fs.readFileSync(file, 'utf8');
    // 把 require('./x') 与 require('包名') 都换成 __require。
    // 用负向断言 (?<!_) 避开已经替换好的 __require，否则第二次替换会把它改成 ____require。
    code = code.replace(/(?<!_)require\(\s*['"]\.\/([\w-]+)['"]\s*\)/g, "__require('$1')");
    code = code.replace(/(?<!_)require\(\s*['"]([\w@/-]+)['"]\s*\)/g, "__require('$1')");
    // 去掉多余的 'use strict'（外层已有）
    code = code.replace(/^'use strict';?\s*$/m, '');
    parts.push('  __defs["' + name + '"] = function (module, exports, __require) {');
    parts.push(code);
    parts.push('  };');
    parts.push('');
  }

  // 外部小依赖
  for (const extra of EXTRA_MODULES) {
    if (!fs.existsSync(extra.file)) {
      console.warn('  跳过不存在的外部依赖: ' + extra.name);
      continue;
    }
    let code = fs.readFileSync(extra.file, 'utf8');
    code = code.replace(/^'use strict';?\s*$/m, '');
    parts.push('  __defs["' + extra.name + '"] = function (module, exports, __require) {');
    parts.push(code);
    parts.push('  };');
    parts.push('');
  }

  // 对外接口：文本 → SVG data URL
  parts.push('  var QRCore = __require("qrcode");');
  parts.push('');
  parts.push('  /** 二维码规范要求的最小静默区（模块数）。少了它，扫码识别率会明显下降。 */');
  parts.push('  var QUIET_ZONE = 4;');
  parts.push('');
  parts.push('  /** 编码并渲染成 SVG 文本。 */');
  parts.push('  function buildSvg(text) {');
  parts.push('    var symbol = QRCore.create(String(text), { errorCorrectionLevel: "M" });');
  parts.push('    var size = symbol.modules.size;');
  parts.push('    var data = symbol.modules.data;');
  parts.push('    var side = size + QUIET_ZONE * 2;');
  parts.push('    var runs = [];');
  parts.push('    for (var row = 0; row < size; row += 1) {');
  parts.push('      var column = 0;');
  parts.push('      while (column < size) {');
  parts.push('        if (!data[row * size + column]) { column += 1; continue; }');
  parts.push('        var end = column;');
  parts.push('        while (end + 1 < size && data[row * size + end + 1]) end += 1;');
  parts.push('        var length = end - column + 1;');
  parts.push('        runs.push("M" + (column + QUIET_ZONE) + " " + (row + QUIET_ZONE) + "h" + length + "v1h-" + length + "z");');
  parts.push('        column = end + 1;');
  parts.push('      }');
  parts.push('    }');
  parts.push('    return \'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 \' + side + \' \' + side + \'" '
    + 'shape-rendering="crispEdges"><rect width="100%" height="100%" fill="#fff"/>'
    + '<path d="\' + runs.join("") + \'" fill="#000"/></svg>\';');
  parts.push('  }');
  parts.push('');
  parts.push('  /** SVG 文本 → data URL（可直接放进 <img src>）。 */');
  parts.push('  window.MRQrcode = {');
  parts.push('    buildSvg: buildSvg,');
  parts.push('    toDataUrl: function (text) {');
  parts.push('      var svg = buildSvg(text);');
  parts.push('      return "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);');
  parts.push('    }');
  parts.push('  };');
  parts.push('})();');

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, parts.join('\n'), 'utf8');
  console.log('✓ 二维码内核已打包: ' + OUT);
  console.log('  大小: ' + (fs.statSync(OUT).size / 1024).toFixed(1) + ' KB');
}

build();
