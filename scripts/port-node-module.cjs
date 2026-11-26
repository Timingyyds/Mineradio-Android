// scripts/port-node-module.cjs
// 把桌面版的 Node 模块（CommonJS）转成能在 WebView 里跑的形式。
//
// 做的事很少，因为 nodeShim 已经提供了同名的内置模块：
//   require('crypto')  → window.__nodeShim.crypto
//   require('http')    → window.__nodeShim.http
//   ...
//   module.exports     → 挂到 window.MRModules[模块名]
//
// 不做的事（故意的）：
//   - 不改业务逻辑一行，将来上游更新直接重跑这个脚本即可
//   - 不打包不压缩，出问题时能在控制台直接看到原样的代码

'use strict';

const fs = require('fs');
const path = require('path');

const BUILTINS = ['crypto', 'http', 'https', 'fs', 'path', 'url', 'tls', 'os'];

/**
 * @param {string} sourcePath 桌面版源文件
 * @param {string} outputPath 输出文件（放在 public/js/ported/）
 * @param {string} moduleName 在 window.MRModules 下的名字
 * @param {{stubs?: Record<string,string>}} [options] 需要额外替换的 require
 */
function portModule(sourcePath, outputPath, moduleName, options) {
  const opts = options || {};
  let code = fs.readFileSync(sourcePath, 'utf8');

  // 1. 去掉 'use strict'（外层已经是 IIFE，严格模式在这里没有必要）
  code = code.replace(/^'use strict';\s*$/m, '');

  // 2. require(内置模块) → nodeShim
  const header = [];
  for (const name of BUILTINS) {
    // 匹配 const x = require('name');
    const pattern = new RegExp(
      '^\\s*(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*require\\(\\s*[\'"]' + name + '[\'"]\\s*\\);\\s*$',
      'gm',
    );
    code = code.replace(pattern, (_match, varName) => {
      return 'var ' + varName + ' = __shim.' + name + ';';
    });
  }

  // 3. 其余 require 按传入的 stub 替换，未声明的原样留着（会在控制台报错，好过静默错)
  if (opts.stubs) {
    for (const [request, replacement] of Object.entries(opts.stubs)) {
      const escaped = request.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const pattern = new RegExp(
        '^\\s*(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*require\\(\\s*[\'"]' + escaped + '[\'"]\\s*\\);\\s*$',
        'gm',
      );
      code = code.replace(pattern, () => replacement);
    }
  }

  // 4. module.exports = { ... } → return { ... }
  const exportPattern = /^module\.exports\s*=\s*\{/m;
  if (!exportPattern.test(code)) {
    throw new Error('portModule: no module.exports object found in ' + sourcePath);
  }
  // module.exports = { ... }  → window.MRModules['x'] = { ... }
  code = code.replace(exportPattern, "window.MRModules['" + moduleName + "'] = {");

  const finalCode = [
    '// 由 scripts/port-node-module.cjs 从桌面版的 ' + path.basename(sourcePath) + ' 生成，请勿手改。',
    '// 改上游后重跑脚本即可。逻辑一行未改，只把 require 换成了 nodeShim。',
    '(function () {',
    "  'use strict';",
    '  var __shim = window.__nodeShim;',
    '  if (!__shim) throw new Error("' + moduleName + ': nodeShim 未加载");',
    '  window.MRModules = window.MRModules || {};',
    '',
    code,
    '',
    '})();',
  ].join('\n');

  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, finalCode, 'utf8');
  console.log('  ✓ ' + path.basename(sourcePath) + ' → ' + path.relative(process.cwd(), outputPath));
  return finalCode;
}

// ─────────────────────────────────────────────────────────────
// 命令行用法：node scripts/port-node-module.cjs
// ─────────────────────────────────────────────────────────────

if (require.main === module) {
  const repo = process.env.MR_REPO || '/data/user/0/ti.DeepSeek.ming/files/build/mr-port/Mineradio-paused-main';
  const outDir = process.env.MR_OUT || path.join(__dirname, '..', 'public', 'js', 'ported');

  console.log('移植 Node 模块 → WebView');
  console.log('  源: ' + repo);
  console.log('  出: ' + outDir);

  portModule(path.join(repo, 'kugou-api.js'), path.join(outDir, 'kugou-api.js'), 'kugou-api');
}

module.exports = { portModule };
