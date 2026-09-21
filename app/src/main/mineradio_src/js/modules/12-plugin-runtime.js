/* ============================================================
 *  Mineradio 插件运行时 + 管理面板
 *  ------------------------------------------------------------
 *  职责：
 *    1. 启动时从 /api/plugins/list 拉取已启用插件，动态注入 main.js
 *    2. 提供插件管理面板 UI：列表 / 安装 / 启用 / 禁用 / 卸载
 *    3. 通过 KeepApp.pickPluginZip 触发文件选择器
 *
 *  本文件被打包到 APK 中,作为运行时引擎 + UI
 *  用户的插件代码（main.js）是运行时通过 fetch 动态加载并执行的
 * ============================================================ */
(function (global) {
  'use strict';

  // ── 全局命名空间 ──────────────────────────────────
  var MR = global.MineradioPlugins = global.MineradioPlugins || {
    registry: {},      // 已加载的插件实例
    loaded: {},        // 已成功加载的插件 id 集合
    api: null          // 当前会话的公共 API
  };

  // ── 工具 ──────────────────────────────────────────
  function log() {
    try {
      var args = Array.prototype.slice.call(arguments);
      args.unshift('%c[Plugin]', 'color:#4dd0e1;font-weight:bold');
      console.log.apply(console, args);
    } catch (e) {}
  }
  function warn() {
    try {
      var args = Array.prototype.slice.call(arguments);
      args.unshift('%c[Plugin]', 'color:#ffb300;font-weight:bold');
      console.warn.apply(console, args);
    } catch (e) {}
  }
  function err() {
    try {
      var args = Array.prototype.slice.call(arguments);
      args.unshift('%c[Plugin]', 'color:#ef5350;font-weight:bold');
      console.error.apply(console, args);
    } catch (e) {}
  }
  function safe(fn) { try { return fn(); } catch (e) { err('异常:', e && e.message || e); return undefined; } }
  function $(sel) { return document.querySelector(sel); }
  function $$(sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); }
  function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  // ── 插件 API 上下文（暴露给每个 main.js 使用） ────
  function createPluginApi(pluginId) {
    var timers = { t: [], i: [] };
    var uninstallers = [];

    function restoreAll() {
      uninstallers.forEach(function (u) { try { u(); } catch (e) {} });
      timers.t.forEach(function (id) { try { clearTimeout(id); } catch (e) {} });
      timers.i.forEach(function (id) { try { clearInterval(id); } catch (e) {} });
      uninstallers = []; timers.t = []; timers.i = [];
    }

    var api = {
      id: pluginId,
      _uninstallers: uninstallers,
      _restoreAll: restoreAll,

      // ── DOM API ─────────────────────────────────
      dom: {
        $: $,
        $$: $$,
        hide: function (sel) { $$(sel).forEach(function (el) { el.style.setProperty('display', 'none', 'important'); }); return this; },
        show: function (sel) { $$(sel).forEach(function (el) { el.style.removeProperty('display'); }); return this; },
        toggle: function (sel, force) {
          $$(sel).forEach(function (el) {
            var hidden = el.style.display === 'none';
            var should = (typeof force === 'boolean') ? force : hidden;
            if (should) { el.style.removeProperty('display'); } else { el.style.setProperty('display', 'none', 'important'); }
          });
          return this;
        },
        addCSS: function (cssText) {
          var style = document.createElement('style');
          style.setAttribute('data-mr-plugin', pluginId);
          style.textContent = cssText;
          (document.head || document.documentElement).appendChild(style);
          uninstallers.push(function () { try { style.parentNode && style.parentNode.removeChild(style); } catch (e) {} });
          return style;
        },
        addElement: function (parent, html) {
          var p = (typeof parent === 'string') ? $(parent) : parent;
          if (!p) p = document.body;
          var tmp = document.createElement('div');
          tmp.innerHTML = html;
          var node = tmp.firstElementChild;
          if (node) {
            // ★ 自动打标记,卸载时统一清理
            node.setAttribute('data-mr-plugin', pluginId);
            p.appendChild(node);
            // ★ 修复: 顶层面板被插件管理模态层(#plugin-manager-modal,
            //   z-index:9999, inset:0)覆盖导致无法交互/无法关闭的问题
            //   原因: 管理面板是覆盖整个视口的半透明遮罩,会拦截所有点击事件,
            //         插件自己的面板虽可见但被压在遮罩下面,触碰不到也关不掉
            //   方案: 添加到 body 的顶层面板,自动提升 z-index 到管理面板之上,
            //         并确保 position 非 static(否则 z-index 不生效)
            if (p === document.body) {
              try {
                var curPos = window.getComputedStyle(node).position;
                if (curPos === 'static' || !curPos) {
                  node.style.setProperty('position', 'fixed', 'important');
                }
                node.style.setProperty('z-index', '100000', 'important');
              } catch (e) {}
            }
          }
          return node;
        },
        insertAfter: function (refEl, html) {
          var ref = (typeof refEl === 'string') ? $(refEl) : refEl;
          if (!ref || !ref.parentNode) return null;
          var tmp = document.createElement('div');
          tmp.innerHTML = html;
          var node = tmp.firstElementChild;
          if (node) {
            // ★ 自动打标记
            node.setAttribute('data-mr-plugin', pluginId);
            ref.parentNode.insertBefore(node, ref.nextSibling);
          }
          return node;
        },
        removeElement: function (sel) { $$(sel).forEach(function (el) { el.parentNode && el.parentNode.removeChild(el); }); return this; },
        on: function (sel, evt, handler, opts) { $$(sel).forEach(function (el) { el.addEventListener(evt, handler, opts); }); return this; },
        off: function (sel, evt, handler, opts) { $$(sel).forEach(function (el) { el.removeEventListener(evt, handler, opts); }); return this; },
        attr: function (sel, name, val) {
          var el = (typeof sel === 'string') ? $(sel) : sel;
          if (!el) return null;
          if (arguments.length >= 3) { el.setAttribute(name, val); return this; }
          return el.getAttribute(name);
        },
        style: function (sel, prop, val) {
          var el = (typeof sel === 'string') ? $(sel) : sel;
          if (!el) return this;
          if (arguments.length >= 3) { el.style.setProperty(prop, val); return this; }
          return el.style.getPropertyValue(prop);
        },
        watch: function (sel, opts, callback) {
          var el = (typeof sel === 'string') ? $(sel) : sel;
          if (!el || !window.MutationObserver) return null;
          var obs = new MutationObserver(function (muts) { callback(muts, el); });
          obs.observe(el, opts || { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style'] });
          uninstallers.push(function () { try { obs.disconnect(); } catch (e) {} });
          return obs;
        }
      },

      // ── Hook API ────────────────────────────────
      hook: (function () {
        function getFn(name) {
          var parts = name.split('.');
          var cur = global;
          for (var i = 0; i < parts.length; i++) { if (cur == null) return null; cur = cur[parts[i]]; }
          return typeof cur === 'function' ? cur : null;
        }
        function setFn(name, fn) {
          var parts = name.split('.');
          var cur = global;
          for (var i = 0; i < parts.length - 1; i++) { if (cur[parts[i]] == null) cur[parts[i]] = {}; cur = cur[parts[i]]; }
          cur[parts[parts.length - 1]] = fn;
        }
        return {
          before: function (fnName, interceptor) {
            var orig = getFn(fnName);
            if (!orig) { warn('Hook before 失败: 函数不存在', fnName); return function () {}; }
            var wrapped = function () {
              var args = Array.prototype.slice.call(arguments);
              try { var modified = interceptor.apply(this, args); if (Array.isArray(modified)) args = modified; } catch (e) { err('Hook before 异常', fnName, e); }
              return orig.apply(this, args);
            };
            setFn(fnName, wrapped);
            var restore = function () { setFn(fnName, orig); };
            uninstallers.push(restore);
            return restore;
          },
          after: function (fnName, interceptor) {
            var orig = getFn(fnName);
            if (!orig) { warn('Hook after 失败: 函数不存在', fnName); return function () {}; }
            var wrapped = function () {
              var result = orig.apply(this, arguments);
              try { var r2 = interceptor.call(this, result, arguments); if (r2 !== undefined) return r2; } catch (e) { err('Hook after 异常', fnName, e); }
              return result;
            };
            setFn(fnName, wrapped);
            var restore = function () { setFn(fnName, orig); };
            uninstallers.push(restore);
            return restore;
          },
          replace: function (fnName, newFn) {
            var orig = getFn(fnName);
            setFn(fnName, newFn);
            var restore = function () { setFn(fnName, orig); };
            uninstallers.push(restore);
            return restore;
          },
          call: function (fnName) {
            var orig = getFn(fnName);
            if (!orig) { warn('调用失败: 函数不存在', fnName); return undefined; }
            var args = Array.prototype.slice.call(arguments, 1);
            return orig.apply(null, args);
          }
        };
      })(),

      // ── 事件 API ────────────────────────────────
      event: (function () {
        var listeners = {};
        return {
          on: function (name, cb) { (listeners[name] || (listeners[name] = [])).push(cb); return this; },
          off: function (name, cb) {
            if (!listeners[name]) return this;
            listeners[name] = listeners[name].filter(function (it) { return it !== cb; });
            return this;
          },
          emit: function (name) {
            var args = Array.prototype.slice.call(arguments, 1);
            (listeners[name] || []).forEach(function (cb) { safe(function () { cb.apply(null, args); }); });
            return this;
          }
        };
      })(),

      // ── 网络 API ────────────────────────────────
      net: {
        fetch: function (url, opts) { return fetch(url, opts || {}).then(function (r) { return r.text(); }); },
        json: function (url, opts) { return fetch(url, opts || {}).then(function (r) { return r.json(); }); },
        get: function (url) { return this.fetch(url, { method: 'GET' }); },
        post: function (url, body, contentType) {
          return this.fetch(url, {
            method: 'POST',
            body: typeof body === 'string' ? body : JSON.stringify(body),
            headers: { 'Content-Type': contentType || 'application/json' }
          });
        }
      },

      // ── 原生桥接 API ────────────────────────────
      native: {
        call: function (method) {
          var args = Array.prototype.slice.call(arguments, 1);
          if (global.KeepApp && typeof global.KeepApp[method] === 'function') {
            try { return global.KeepApp[method].apply(global.KeepApp, args); } catch (e) { err('Native 调用失败', method, e); return null; }
          }
          warn('KeepApp 桥接不可用:', method);
          return null;
        },
        available: function () { return !!(global.KeepApp); },
        evalJS: function (code) { try { return (0, eval)(code); } catch (e) { err('evalJS 异常', e); return undefined; } },

        // ★★★ v2.1 扩展：原生库加载 / 悬浮窗渲染 / 后台自启动 ★★★

        /**
         * ★ 加载插件内置 Native 库（.so）
         * @param libName 库名（不含 lib 前缀和 .so 后缀），如 "Live2DCubismCore"
         *                也支持完整文件名 "libLive2DCubismCore.so"
         *                也支持相对路径 "lib/arm64-v8a/libLive2DCubismCore.so"
         * @return "1"=成功, 其他=错误信息
         *
         * ★ 自动定位：
         *   - 若 libName 包含路径分隔符，直接当作相对路径
         *   - 否则尝试 lib/arm64-v8a/lib<libName>.so
         *   - 也尝试 lib/arm64-v8a/<libName>（若 libName 以 .so 结尾）
         */
        loadPluginLibrary: function (libName) {
          if (!global.KeepApp || typeof global.KeepApp.loadPluginLibrary !== 'function') {
            warn('loadPluginLibrary 不可用（KeepApp 未实现）');
            return 'loadPluginLibrary unavailable';
          }
          try {
            // 标准化库名：
            //   - "Live2DCubismCore" → lib/arm64-v8a/libLive2DCubismCore.so
            //   - "libLive2DCubismCore.so" → lib/arm64-v8a/libLive2DCubismCore.so
            //   - "lib/arm64-v8a/libLive2DCubismCore.so" → 原样
            var name = String(libName || '');
            if (name.indexOf('/') < 0) {
              // 自动加 lib 前缀和 .so 后缀
              if (!name.startsWith('lib')) name = 'lib' + name;
              if (!name.endsWith('.so')) name = name + '.so';
              name = 'lib/arm64-v8a/' + name;
            }
            return global.KeepApp.loadPluginLibrary(pluginId, name);
          } catch (e) {
            err('loadPluginLibrary 异常', e);
            return 'exception: ' + (e && e.message || e);
          }
        },

        /**
         * ★ 渲染原生悬浮窗（启动一个由配置驱动的悬浮窗）
         * @param config 配置对象，例如:
         *   {
         *     type: 'live2d',            // 悬浮窗类型
         *     width: 200, height: 280,
         *     x: 0, y: 0,                // 位置
         *     pluginId: 'desktop-pet',   // 插件 ID（用于资源定位）
         *     modelPath: 'assets/live2d/xiaoaimisi/小爱弥斯.model3.json',
         *     sdkLib: 'Live2DCubismCore', // SDK 库名（可选）
         *     touchPassthrough: false,
         *     draggable: true,
         *     pinned: false,
         *     ...
         *   }
         * @return "1"=成功, 其他=错误信息
         */
        renderFloatWindow: function (config) {
          if (!global.KeepApp || typeof global.KeepApp.renderFloatWindow !== 'function') {
            warn('renderFloatWindow 不可用（KeepApp 未实现）');
            return 'renderFloatWindow unavailable';
          }
          try {
            // 自动注入 pluginId
            if (!config) config = {};
            if (!config.pluginId) config.pluginId = pluginId;
            return global.KeepApp.renderFloatWindow(JSON.stringify(config));
          } catch (e) {
            err('renderFloatWindow 异常', e);
            return 'exception: ' + (e && e.message || e);
          }
        },

        /**
         * ★ 关闭原生悬浮窗
         * @param windowId 悬浮窗 ID（可选，默认 'default'）
         * @return "1"=成功
         */
        closeFloatWindow: function (windowId) {
          if (!global.KeepApp || typeof global.KeepApp.closeFloatWindow !== 'function') {
            warn('closeFloatWindow 不可用');
            return 'closeFloatWindow unavailable';
          }
          try {
            return global.KeepApp.closeFloatWindow(windowId || 'default');
          } catch (e) {
            err('closeFloatWindow 异常', e);
            return 'exception: ' + (e && e.message || e);
          }
        },

        /**
         * ★ 后台自启动注册
         * @param config 配置对象，例如:
         *   { action: 'boot_completed' | 'app_killed' | 'background', enabled: true }
         * @return "1"=成功
         */
        registerAutoStart: function (config) {
          if (!global.KeepApp || typeof global.KeepApp.registerAutoStart !== 'function') {
            warn('registerAutoStart 不可用');
            return 'registerAutoStart unavailable';
          }
          try {
            if (!config) config = {};
            if (!config.pluginId) config.pluginId = pluginId;
            return global.KeepApp.registerAutoStart(JSON.stringify(config));
          } catch (e) {
            err('registerAutoStart 异常', e);
            return 'exception: ' + (e && e.message || e);
          }
        }
      },

      // ═══════════════════════════════════════════════════════════
      //  ★★★ v2.2 扩展：结构化原生能力命名空间 ★★★
      //   - api.floatWindow.*  悬浮窗完整控制
      //   - api.live2d.*        Live2D 动态加载
      //   - api.broadcast.*     跨插件广播通信
      //   - api.system.*        系统级能力
      // ═══════════════════════════════════════════════════════════

      // ── 悬浮窗完整控制 ──────────────────────────
      floatWindow: {
        /** 查询悬浮窗是否激活
         * @param windowId 悬浮窗 ID（可选，默认 'default'）
         * @return '1'=激活, '0'=未激活 */
        isActive: function (windowId) {
          if (!global.KeepApp || typeof global.KeepApp.isFloatWindowActive !== 'function') {
            warn('floatWindow.isActive 不可用');
            return '0';
          }
          try { return global.KeepApp.isFloatWindowActive(windowId || 'default'); }
          catch (e) { err('floatWindow.isActive 异常', e); return '0'; }
        },

        /** 设置悬浮窗位置（屏幕坐标，px） */
        setPosition: function (x, y, windowId) {
          if (!global.KeepApp || typeof global.KeepApp.setFloatWindowPosition !== 'function') {
            warn('floatWindow.setPosition 不可用'); return 'unavailable';
          }
          try { return global.KeepApp.setFloatWindowPosition(windowId || 'default', x | 0, y | 0); }
          catch (e) { err('floatWindow.setPosition 异常', e); return 'exception: ' + (e && e.message || e); }
        },

        /** 设置悬浮窗大小（像素） */
        setSize: function (width, height, windowId) {
          if (!global.KeepApp || typeof global.KeepApp.setFloatWindowSize !== 'function') {
            warn('floatWindow.setSize 不可用'); return 'unavailable';
          }
          try { return global.KeepApp.setFloatWindowSize(windowId || 'default', width | 0, height | 0); }
          catch (e) { err('floatWindow.setSize 异常', e); return 'exception: ' + (e && e.message || e); }
        },

        /** 设置悬浮窗透明度 (0.0-1.0) */
        setOpacity: function (alpha, windowId) {
          if (!global.KeepApp || typeof global.KeepApp.setFloatWindowOpacity !== 'function') {
            warn('floatWindow.setOpacity 不可用'); return 'unavailable';
          }
          try { return global.KeepApp.setFloatWindowOpacity(windowId || 'default', Number(alpha) || 0); }
          catch (e) { err('floatWindow.setOpacity 异常', e); return 'exception: ' + (e && e.message || e); }
        },

        /** 获取悬浮窗信息（位置、大小、激活状态） */
        getInfo: function (windowId) {
          if (!global.KeepApp || typeof global.KeepApp.getFloatWindowInfo !== 'function') {
            warn('floatWindow.getInfo 不可用'); return null;
          }
          try {
            var jsonStr = global.KeepApp.getFloatWindowInfo(windowId || 'default');
            if (!jsonStr) return null;
            try { return JSON.parse(jsonStr); } catch (e2) { return null; }
          } catch (e) { err('floatWindow.getInfo 异常', e); return null; }
        },

        /** 关闭悬浮窗（兼容旧 API native.closeFloatWindow） */
        close: function (windowId) {
          if (!global.KeepApp || typeof global.KeepApp.closeFloatWindow !== 'function') {
            warn('floatWindow.close 不可用'); return 'unavailable';
          }
          try { return global.KeepApp.closeFloatWindow(windowId || 'default'); }
          catch (e) { err('floatWindow.close 异常', e); return 'exception: ' + (e && e.message || e); }
        }
      },

      // ── Live2D 动态加载 ──────────────────────────
      live2d: {
        /** 加载指定路径的 Live2D 模型
         * @param targetPluginId 模型所在插件 ID（不传则用当前插件 ID）
         * @param modelPath 模型相对路径（如 "assets/live2d/xxx/xxx.model3.json"）
         * @return '1'=成功, 其他=错误信息 */
        loadModel: function (targetPluginId, modelPath) {
          if (!global.KeepApp || typeof global.KeepApp.loadLive2DModel !== 'function') {
            warn('live2d.loadModel 不可用'); return 'unavailable';
          }
          // 未传 targetPluginId 时回退到当前插件 ID
          var pid = targetPluginId || pluginId;
          try { return global.KeepApp.loadLive2DModel(pid, modelPath || ''); }
          catch (e) { err('live2d.loadModel 异常', e); return 'exception: ' + (e && e.message || e); }
        },

        /** 触发动作（motion3.json 文件名，不含扩展名） */
        triggerMotion: function (motionName) {
          if (!global.KeepApp || typeof global.KeepApp.triggerMotion !== 'function') {
            warn('live2d.triggerMotion 不可用'); return 'unavailable';
          }
          try { return global.KeepApp.triggerMotion(motionName || ''); }
          catch (e) { err('live2d.triggerMotion 异常', e); return 'exception: ' + (e && e.message || e); }
        },

        /** 触发表情（exp3.json 文件名，不含扩展名） */
        triggerExpression: function (expressionName) {
          if (!global.KeepApp || typeof global.KeepApp.triggerLive2DExpression !== 'function') {
            warn('live2d.triggerExpression 不可用'); return 'unavailable';
          }
          try { return global.KeepApp.triggerLive2DExpression(expressionName || ''); }
          catch (e) { err('live2d.triggerExpression 异常', e); return 'exception: ' + (e && e.message || e); }
        },

        /** 获取当前模型信息（modelName, modelDirName, isCustom, isRunning, expressions[]） */
        getModelInfo: function () {
          if (!global.KeepApp || typeof global.KeepApp.getLive2DModelInfo !== 'function') {
            warn('live2d.getModelInfo 不可用'); return null;
          }
          try {
            var jsonStr = global.KeepApp.getLive2DModelInfo();
            if (!jsonStr) return null;
            try { return JSON.parse(jsonStr); } catch (e2) { return null; }
          } catch (e) { err('live2d.getModelInfo 异常', e); return null; }
        }
      },

      // ── 跨插件广播通信 ──────────────────────────
      broadcast: (function () {
        var listeners = {};  // { action: [cb, cb, ...] }

        // ★ 内部分发函数：原生层通过 evaluateJavascript 调用此函数
        //   payload = { action: 'music.beat', data: '...' }
        function dispatch(payload) {
          try {
            if (!payload || typeof payload !== 'object') return;
            var action = payload.action || '';
            var data = payload.data || '';
            var cbs = listeners[action] || [];
            var anyCbs = listeners['*'] || [];  // 通配监听
            cbs.forEach(function (cb) {
              try { cb(data, action); } catch (e) { err('broadcast listener 异常', action, e); }
            });
            anyCbs.forEach(function (cb) {
              try { cb(data, action); } catch (e) { err('broadcast listener 异常 *', e); }
            });
          } catch (e) { err('broadcast dispatch 异常', e); }
        }

        // ★ 暴露给原生层调用（注册到全局命名空间）
        try {
          if (!global.MineradioPlugins) global.MineradioPlugins = {};
          global.MineradioPlugins._dispatchBroadcast = dispatch;
        } catch (e) { err('注册 _dispatchBroadcast 失败', e); }

        return {
          /** 监听指定 action 的广播
           * @param action 广播动作名（或 '*' 监听所有）
           * @param cb 回调函数 (data, action) */
          on: function (action, cb) {
            if (typeof action !== 'string' || typeof cb !== 'function') return;
            if (!listeners[action]) listeners[action] = [];
            listeners[action].push(cb);
            return function () {
              var arr = listeners[action];
              if (!arr) return;
              var i = arr.indexOf(cb);
              if (i >= 0) arr.splice(i, 1);
            };
          },

          /** 取消监听 */
          off: function (action, cb) {
            if (!listeners[action]) return;
            if (cb) {
              listeners[action] = listeners[action].filter(function (it) { return it !== cb; });
            } else {
              delete listeners[action];
            }
          },

          /** 发送广播给其他插件
           * @param action 动作名
           * @param data 任意数据（字符串或对象，会 JSON.stringify）
           * @return 1=成功, -1=失败 */
          post: function (action, data) {
            if (!global.KeepApp || typeof global.KeepApp.postPluginBroadcast !== 'function') {
              warn('broadcast.post 不可用'); return -1;
            }
            var dataStr;
            if (typeof data === 'string') dataStr = data;
            else {
              try { dataStr = JSON.stringify(data || ''); }
              catch (e) { dataStr = String(data); }
            }
            try { return global.KeepApp.postPluginBroadcast(action || '', dataStr); }
            catch (e) { err('broadcast.post 异常', e); return -1; }
          }
        };
      })(),

      // ── 系统级能力 ──────────────────────────────
      system: {
        /** 发送系统广播 */
        sendBroadcast: function (action, data) {
          if (!global.KeepApp || typeof global.KeepApp.sendSystemBroadcast !== 'function') {
            warn('system.sendBroadcast 不可用'); return 'unavailable';
          }
          var dataStr;
          if (typeof data === 'string') dataStr = data;
          else {
            try { dataStr = JSON.stringify(data || ''); }
            catch (e) { dataStr = String(data); }
          }
          try { return global.KeepApp.sendSystemBroadcast(action || '', dataStr); }
          catch (e) { err('system.sendBroadcast 异常', e); return 'exception: ' + (e && e.message || e); }
        },

        /** 获取系统状态
         * @param key 取值: screen_width / screen_height / battery / network /
         *              package_name / version_name / android_version / device_model */
        getState: function (key) {
          if (!global.KeepApp || typeof global.KeepApp.getSystemState !== 'function') {
            warn('system.getState 不可用'); return '';
          }
          try { return global.KeepApp.getSystemState(key || ''); }
          catch (e) { err('system.getState 异常', e); return ''; }
        },

        /** 启动其他应用（通过包名） */
        launchApp: function (packageName) {
          if (!global.KeepApp || typeof global.KeepApp.launchApp !== 'function') {
            warn('system.launchApp 不可用'); return 'unavailable';
          }
          try { return global.KeepApp.launchApp(packageName || ''); }
          catch (e) { err('system.launchApp 异常', e); return 'exception: ' + (e && e.message || e); }
        }
      },

      // ── 资源加载 API ────────────────────────────
      resource: {
        loadScript: function (src) {
          return new Promise(function (resolve, reject) {
            var s = document.createElement('script');
            s.src = src; s.async = true;
            s.onload = function () { resolve(s); };
            s.onerror = function () { reject(new Error('脚本加载失败: ' + src)); };
            (document.head || document.documentElement).appendChild(s);
          });
        },
        loadStyle: function (href) {
          return new Promise(function (resolve, reject) {
            var l = document.createElement('link');
            l.rel = 'stylesheet'; l.href = href;
            l.onload = function () { resolve(l); };
            l.onerror = function () { reject(new Error('样式加载失败: ' + href)); };
            (document.head || document.documentElement).appendChild(l);
          });
        },

        // ★★★ v2.1 扩展：插件资源加载（从插件目录读取任意文件）★★★

        /**
         * ★ 加载插件内置二进制资源
         * @param relPath 相对路径（POSIX 风格 / 分隔），如 "lib/arm64-v8a/libxxx.so"
         *                或 "assets/live2d/xiaoaimisi/小爱弥斯.moc3"
         * @return Promise<ArrayBuffer>
         */
        loadBinary: function (relPath) {
          var url = '/api/plugins/' + encodeURIComponent(pluginId) + '/resource?path=' + encodeURIComponent(relPath);
          return fetch(url, { method: 'GET' }).then(function (resp) {
            if (!resp.ok) throw new Error('loadBinary 失败: ' + resp.status + ' ' + relPath);
            return resp.arrayBuffer();
          });
        },

        /**
         * ★ 加载插件内置文本资源（UTF-8）
         * @param relPath 相对路径
         * @return Promise<string>
         */
        loadText: function (relPath) {
          var url = '/api/plugins/' + encodeURIComponent(pluginId) + '/resource/text?path=' + encodeURIComponent(relPath);
          return fetch(url, { method: 'GET' }).then(function (resp) {
            if (!resp.ok) throw new Error('loadText 失败: ' + resp.status + ' ' + relPath);
            return resp.text();
          });
        },

        /**
         * ★ 加载插件内置 JSON 资源
         * @param relPath 相对路径
         * @return Promise<object>
         */
        loadJson: function (relPath) {
          return api.resource.loadText(relPath).then(function (text) {
            try { return JSON.parse(text); } catch (e) { throw new Error('loadJson 解析失败: ' + relPath + ' ' + e.message); }
          });
        },

        /**
         * ★ 列出插件目录中指定子目录下所有文件（递归）
         * @param subDir 子目录路径（""=插件根目录，"lib/arm64-v8a"=指定子目录）
         * @return Promise<string[]> 相对路径列表
         */
        listFiles: function (subDir) {
          var dir = subDir || '';
          var url = '/api/plugins/' + encodeURIComponent(pluginId) + '/files?dir=' + encodeURIComponent(dir);
          return fetch(url, { method: 'GET' }).then(function (resp) {
            if (!resp.ok) throw new Error('listFiles 失败: ' + resp.status);
            return resp.json();
          }).then(function (data) {
            return data.files || [];
          });
        },

        /**
         * ★ 获取插件资源的绝对路径（用于 native .so 加载等场景）
         * @param relPath 相对路径
         * @return Promise<string> 绝对路径
         */
        getResourcePath: function (relPath) {
          var url = '/api/plugins/' + encodeURIComponent(pluginId) + '/path?relPath=' + encodeURIComponent(relPath);
          return fetch(url, { method: 'GET' }).then(function (resp) {
            if (!resp.ok) throw new Error('getResourcePath 失败: ' + resp.status);
            return resp.json();
          }).then(function (data) {
            return data.absPath;
          });
        },

        /**
         * ★ 加载插件内置 Base64 编码资源（用于图片纹理等）
         * @param relPath 相对路径
         * @return Promise<string> base64 编码字符串
         */
        loadBase64: function (relPath) {
          return api.resource.loadBinary(relPath).then(function (buf) {
            var bytes = new Uint8Array(buf);
            var bin = '';
            for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
            return global.btoa(bin);
          });
        }
      },

      // ── 定时器 API ──────────────────────────────
      timer: {
        setTimeout: function (fn, ms) { var id = setTimeout(fn, ms); timers.t.push(id); return id; },
        setInterval: function (fn, ms) { var id = setInterval(fn, ms); timers.i.push(id); return id; },
        clearTimeout: function (id) { clearTimeout(id); var i = timers.t.indexOf(id); if (i >= 0) timers.t.splice(i, 1); },
        clearInterval: function (id) { clearInterval(id); var i = timers.i.indexOf(id); if (i >= 0) timers.i.splice(i, 1); },
        requestAnimationFrame: function (fn) { var id = requestAnimationFrame(fn); timers.t.push(id); return id; }
      },

      // ── 存储 API ────────────────────────────────
      storage: {
        get: function (key) { try { return JSON.parse(localStorage.getItem('mr_plugin_' + pluginId + '_' + key)); } catch (e) { return null; } },
        set: function (key, val) { try { localStorage.setItem('mr_plugin_' + pluginId + '_' + key, JSON.stringify(val)); return true; } catch (e) { return false; } },
        remove: function (key) { try { localStorage.removeItem('mr_plugin_' + pluginId + '_' + key); return true; } catch (e) { return false; } },
        keys: function () {
          var prefix = 'mr_plugin_' + pluginId + '_';
          var out = [];
          for (var i = 0; i < localStorage.length; i++) {
            var k = localStorage.key(i);
            if (k && k.indexOf(prefix) === 0) out.push(k.substring(prefix.length));
          }
          return out;
        }
      },

      // ── 配置 API ────────────────────────────────
      config: function (key, def) {
        var all = {};
        try { all = JSON.parse(localStorage.getItem('mr_plugin_config_' + pluginId) || '{}'); } catch (e) {}
        if (key === undefined) return all;
        return (key in all) ? all[key] : def;
      },
      setConfig: function (key, val) {
        var all = this.config();
        all[key] = val;
        try { localStorage.setItem('mr_plugin_config_' + pluginId, JSON.stringify(all)); } catch (e) {}
      },

      // ── 生命周期 ──────────────────────────────────
      ready: function (cb) {
        if (document.readyState === 'complete' || document.readyState === 'interactive') setTimeout(cb, 0);
        else document.addEventListener('DOMContentLoaded', cb);
      },
      onAppReady: function (cb) {
        if (global.__mrAppReady) setTimeout(cb, 0);
        else document.addEventListener('mr-app-ready', cb);
      },

      // ── 日志 ─────────────────────────────────────
      log: function () { var a = ['[' + pluginId + ']']; a.push.apply(a, arguments); log.apply(null, a); },
      warn: function () { var a = ['[' + pluginId + ']']; a.push.apply(a, arguments); warn.apply(null, a); },
      error: function () { var a = ['[' + pluginId + ']']; a.push.apply(a, arguments); err.apply(null, a); },

      // ── 注册卸载器（用户在 main.js 中可手动添加） ──
      registerUninstaller: function (fn) { if (typeof fn === 'function') uninstallers.push(fn); }
    };

    return api;
  }

  // ── 卸载单个插件 (强制彻底清理) ───────────────────
  function unloadPlugin(pluginId) {
    var api = MR.registry[pluginId];
    if (!api) {
      // 即使 api 不在 registry,也尝试兜底清理 DOM/CSS
      forceCleanupPluginTraces(pluginId);
      restoreMainAppState();
      return;
    }
    if (pluginId === 'login-panel' && typeof global.__disableLoginPanel === 'function') {
      try { global.__disableLoginPanel(); } catch(e) { err('disableLoginPanel error:', e); }
    }
    // ★ 关键顺序: 先强制清理 DOM, 再调用 _restoreAll 断开 observer, 最后还原主程序状态
    //   原因: restoreMainAppState 会触发 #preset-grid 变化,
    //         如果 observer 还活着, 会重新注入已清理的元素
    // 1. 先强制清理所有 DOM/CSS/全局变量 (此时 observer 还未断开, 但元素已被移除)
    forceCleanupPluginTraces(pluginId);
    // 2. 调用插件自己提供的卸载函数 (此时插件看到的所有 DOM 已被清空)
    try { if (typeof api._uninstall === 'function') api._uninstall(); } catch (e) { err('卸载失败', pluginId, e); }
    // 3. 调用 _restoreAll (★ 断开 MutationObserver、移除 hook、清理 timer 等)
    try { api._restoreAll(); } catch (e) {}
    // 4. 再次强制清理 (防止 _uninstall 期间又注入了新元素; 此时 observer 已断开, 不会再注入)
    forceCleanupPluginTraces(pluginId);
    // 5. ★ 现在所有 observer 已断开, 安全地还原主程序状态 (可能触发 #preset-grid 变化)
    restoreMainAppState();
    delete MR.registry[pluginId];
    delete MR.loaded[pluginId];
    log('卸载插件:', pluginId);
  }

  // ★ 强制清理插件可能遗留的所有痕迹
  function forceCleanupPluginTraces(pluginId) {
    // (a) 清理带 data-mr-plugin="<id>" 的 <style> 标签
    try {
      var styles = document.querySelectorAll('style[data-mr-plugin="' + cssEscape(pluginId) + '"]');
      for (var i = 0; i < styles.length; i++) {
        if (styles[i].parentNode) styles[i].parentNode.removeChild(styles[i]);
      }
    } catch (e) {}

    // (b) 清理带 data-mr-plugin="<id>" 的普通 DOM 元素
    try {
      var els = document.querySelectorAll('[data-mr-plugin="' + cssEscape(pluginId) + '"]');
      for (var j = 0; j < els.length; j++) {
        if (els[j].parentNode) els[j].parentNode.removeChild(els[j]);
      }
    } catch (e) {}

    // (c) 清理插件可能挂在 window 上的全局变量
    //     ★ 安全策略: 只删除明确属于插件的全局变量,不做模糊匹配
    //     原因: 之前用"函数名包含 pluginId 变体"做子串匹配,
    //           导致主程序的 updateStageLyrics3D 被误删(因为函数名包含 "3d")
    try {
      // 策略1: 已知安全前缀 (插件通过 api.storage/api.config 注册的)
      var prefixes = ['__mr_' + pluginId + '_', '__' + pluginId + '__', '__imgParticle', '__' + pluginId];
      // 策略2: 插件 ID 的常见命名变体 (精确匹配,不是子串)
      var exactNames = [
        pluginId + 'Plugin',
        pluginId + 'Panel',
        pluginId + 'Instance',
        pluginId + 'State',
        pluginId + 'Config'
      ];
      // 策略3: 缩写变体的精确函数名 (如 cover-slider-3d → openCs3dPanel/closeCs3dPanel)
      //   只匹配"缩写+动词"模式,不匹配主程序函数
      var abbrVariants = getIdVariants(pluginId).filter(function (v) {
        return v.length >= 3 && v.length <= 8 && /^[a-z]/.test(v);
      });
      var abbrFuncPatterns = [];
      for (var ai = 0; ai < abbrVariants.length; ai++) {
        var av = abbrVariants[ai];
        // 匹配 openXxx/closeXxx/toggleXxx/initXxx/destroyXxx 模式 (首字母大写)
        abbrFuncPatterns.push('open' + av.charAt(0).toUpperCase() + av.slice(1));
        abbrFuncPatterns.push('close' + av.charAt(0).toUpperCase() + av.slice(1));
        abbrFuncPatterns.push('toggle' + av.charAt(0).toUpperCase() + av.slice(1));
        abbrFuncPatterns.push('init' + av.charAt(0).toUpperCase() + av.slice(1));
        abbrFuncPatterns.push('destroy' + av.charAt(0).toUpperCase() + av.slice(1));
        abbrFuncPatterns.push('apply' + av.charAt(0).toUpperCase() + av.slice(1));
        abbrFuncPatterns.push('clear' + av.charAt(0).toUpperCase() + av.slice(1));
        abbrFuncPatterns.push('pick' + av.charAt(0).toUpperCase() + av.slice(1));
        abbrFuncPatterns.push('select' + av.charAt(0).toUpperCase() + av.slice(1));
        abbrFuncPatterns.push('update' + av.charAt(0).toUpperCase() + av.slice(1));
        // 小写开头: cs3dPickFiles / cs3dApply 等
        abbrFuncPatterns.push(av + 'Pick');
        abbrFuncPatterns.push(av + 'Apply');
        abbrFuncPatterns.push(av + 'Clear');
        abbrFuncPatterns.push(av + 'Open');
        abbrFuncPatterns.push(av + 'Close');
        abbrFuncPatterns.push(av + 'Toggle');
        abbrFuncPatterns.push(av + 'Init');
      }
      var toDelete = [];
      for (var k in global) {
        if (!global.hasOwnProperty(k)) continue;
        // 策略1: 前缀匹配
        for (var pi = 0; pi < prefixes.length; pi++) {
          if (k.indexOf(prefixes[pi]) === 0) { toDelete.push(k); break; }
        }
        if (toDelete.indexOf(k) >= 0) continue;
        // 策略2: 精确匹配
        if (exactNames.indexOf(k) >= 0) { toDelete.push(k); continue; }
        // 策略3: 缩写函数模式精确匹配
        if (abbrFuncPatterns.indexOf(k) >= 0) { toDelete.push(k); continue; }
      }
      for (var d = 0; d < toDelete.length; d++) {
        try { delete global[toDelete[d]]; } catch (e) { try { global[toDelete[d]] = undefined; } catch(e2) {} }
      }
    } catch (e) {}

    // (d) 清理 localStorage 中该插件的所有数据 (mr_plugin_<id>_*, mr_plugin_config_<id>)
    try {
      var keysToRemove = [];
      var lsPrefix1 = 'mr_plugin_' + pluginId + '_';
      var lsPrefix2 = 'mr_plugin_config_' + pluginId;
      for (var n = 0; n < localStorage.length; n++) {
        var lk = localStorage.key(n);
        if (lk && (lk.indexOf(lsPrefix1) === 0 || lk === lsPrefix2)) {
          keysToRemove.push(lk);
        }
      }
      for (var r = 0; r < keysToRemove.length; r++) {
        try { localStorage.removeItem(keysToRemove[r]); } catch (e) {}
      }
    } catch (e) {}

    // (e) 清理插件可能注入到 #preset-grid / #plugin-list-container 的自定义卡片
    try {
      var customCards = document.querySelectorAll('[data-plugin-card="' + cssEscape(pluginId) + '"]');
      for (var cc = 0; cc < customCards.length; cc++) {
        if (customCards[cc].parentNode) customCards[cc].parentNode.removeChild(customCards[cc]);
      }
    } catch (e) {}

    // (e2) ★ 按 class 前缀清理插件可能手动创建的元素 (不走 api.dom.addElement 的插件)
    //      扫描所有元素,找出 class/id 中包含 pluginId 或其变体的元素
    //      ★ 安全限制: 变体长度必须 >= 4,避免短变体(如 "3d")误匹配主程序元素
    try {
      var idVariants = getIdVariants(pluginId).filter(function (v) { return v && v.length >= 4; });
      var allElements = document.querySelectorAll('*');
      for (var ae = 0; ae < allElements.length; ae++) {
        var el = allElements[ae];
        // 跳过主程序核心元素 (html/head/body)
        var tag = el.tagName;
        if (tag === 'HTML' || tag === 'HEAD' || tag === 'BODY') continue;
        // 检查 class
        var cls = el.className || '';
        if (typeof cls !== 'string') continue;
        var idAttr = el.getAttribute('id') || '';
        var dataPlugin = el.getAttribute('data-plugin-card') || '';
        var matched = false;
        for (var iv = 0; iv < idVariants.length; iv++) {
          var v = idVariants[iv];
          if (v && (cls.indexOf(v) >= 0 || idAttr.indexOf(v) >= 0 || dataPlugin === pluginId)) {
            matched = true;
            break;
          }
        }
        if (matched && el.parentNode) {
          el.parentNode.removeChild(el);
        }
      }
    } catch (e) {}

    // (e3) ★ 强制恢复主程序 3D 粒子系统
    //      插件可能: 1) 把 window.particles.visible 设为 false
    //                2) 把 window.particles 从 scene 移除 (但没加回去)
    //                3) 替换了 window.particles 为自己的粒子对象
    //      这里需要: 恢复 visible, 确保仍在 scene 中
    try {
      if (global.particles && global.scene) {
        global.particles.visible = true;
        // 检查 particles 是否还在 scene 中,如果不在则加回去
        var stillInScene = false;
        try {
          stillInScene = global.scene.children.indexOf(global.particles) >= 0;
        } catch (e) {}
        if (!stillInScene) {
          try { global.scene.add(global.particles); } catch (e) {}
        }
      }
    } catch (e) {}

    // (e3b) ★ 恢复 document.body.style 和 CSS 变量 (插件可能修改了背景色)
    try {
      document.body.style.backgroundColor = '';
      document.documentElement.style.removeProperty('--fc-bg');
    } catch (e) {}

    // (f) ★ 注意: 不在这里调用 restoreMainAppState, 由调用方在 observer 断开后调用
    //     原因: restoreMainAppState 会触发 #preset-grid 变化,
    //           如果 observer 还活着, 会重新注入元素
  }

  // 获取插件 ID 的所有可能变体 (用于 class/id 匹配)
  // 例如 "image-particle" → ["image-particle", "imageParticle", "ip-", "ip_"]
  // 例如 "cover-slider-3d" → ["cover-slider-3d", "coverSlider3d", "cs3d", "cs3d-", "cs3d_"]
  function getIdVariants(pluginId) {
    var variants = [pluginId];
    // 驼峰变体: image-particle → imageParticle, cover-slider-3d → coverSlider3d
    var camel = pluginId.replace(/-([a-z0-9])/gi, function (_, c) { return c.toUpperCase(); });
    if (camel !== pluginId) variants.push(camel);
    // 下划线变体: image-particle → image_particle
    var snake = pluginId.replace(/-/g, '_');
    if (snake !== pluginId) variants.push(snake);
    // 缩写前缀: 取每个词的首字母 (字母取首字母,数字保留完整)
    //   image-particle → ip
    //   cover-slider-3d → cs3d (注意: "3d" 作为整体保留,不是只取 "3")
    var parts = pluginId.split('-');
    if (parts.length > 1) {
      var abbr = parts.map(function (p) {
        // 如果是纯数字开头(如 "3d"),保留完整;否则取首字母
        return /^\d/.test(p) ? p : p.charAt(0);
      }).join('');
      variants.push(abbr, abbr + '-', abbr + '_', abbr + '-btn', abbr + '-card', abbr + '-panel', abbr + '-open-btn', abbr + '-preset-card', abbr + '-hint', abbr + '-fab');
    }
    return variants;
  }

  // ★ 还原主程序状态 (卸载插件后调用,确保插件修改的视觉/音频状态完全恢复)
  function restoreMainAppState() {
    // 1. 清除插件可能应用的封面/粒子纹理 (恢复默认粒子)
    try {
      if (typeof global.loadCoverFromUrl === 'function') {
        global.loadCoverFromUrl('', { silent: true });
      }
    } catch (e) {}

    // 2. 重置视觉特效参数到默认值
    try {
      if (typeof global.resetFx === 'function') {
        global.resetFx();
      } else {
        // 降级: 直接重置关键字段
        if (typeof global.fx === 'object' && global.fxDefaults) {
          var savedCam = global.fx.cam, savedShelf = global.fx.shelf;
          global.fx = Object.assign({}, global.fxDefaults, { cam: savedCam, shelf: savedShelf });
        }
      }
    } catch (e) {}

    // 3. 刷新预设网格 (移除插件注入的高亮状态)
    try {
      if (typeof global.refreshPresetGrid === 'function') {
        global.refreshPresetGrid();
      }
    } catch (e) {}

    // 4. 重置 UI 高亮色 / 视觉主色 (如果插件修改了)
    try {
      if (typeof global.resetUiAccentColor === 'function') global.resetUiAccentColor();
    } catch (e) {}
    try {
      if (typeof global.resetVisualTintColor === 'function') global.resetVisualTintColor();
    } catch (e) {}

    // 5. 重置 3D 场景相机
    try {
      if (typeof global.resetFreeCameraToDefault === 'function') global.resetFreeCameraToDefault();
    } catch (e) {}

    // 6. 重置音频可视化状态
    try {
      if (typeof global.resetAudioVisualState === 'function') global.resetAudioVisualState();
    } catch (e) {}

    // 7. 强制刷新插件管理面板列表
    try {
      if (typeof global.refreshPluginList === 'function') global.refreshPluginList();
    } catch (e) {}
  }

  // CSS.escape polyfill (用于属性选择器中的特殊字符)
  function cssEscape(s) {
    if (window.CSS && typeof window.CSS.escape === 'function') return window.CSS.escape(s);
    return String(s).replace(/[^a-zA-Z0-9_-]/g, function (c) { return '\\' + c; });
  }

  // ── 插件错误状态存储 ──────────────────────────────
  // MR.errors[pluginId] = { message, stack, stage, time, codePreview }
  MR.errors = MR.errors || {};

  // 包装错误对象,附加阶段/时间/代码上下文
  function makePluginError(message, stack, stage, code) {
    return {
      __mrError: true,
      message: String(message || '未知错误'),
      stack: String(stack || ''),
      stage: stage, // 'fetch' | 'parse' | 'execute'
      time: new Date().toISOString(),
      codePreview: code ? extractCodePreview(code, message) : ''
    };
  }

  // 从错误消息中提取行号,返回周边代码片段
  function extractCodePreview(code, message) {
    try {
      var m = /(?:line\s+|:)(\d+)(?::(\d+))?/i.exec(message || '');
      if (!m) return '';
      var line = parseInt(m[1], 10);
      if (isNaN(line) || line < 1) return '';
      var lines = String(code).split('\n');
      // new Function 包装会增加若干前置行,尽量修正
      var offset = 0;
      var realLine = line;
      // 如果行号超出代码范围,尝试减去包装偏移
      while (realLine > lines.length && offset < 10) { realLine--; offset++; }
      var start = Math.max(0, realLine - 3);
      var end = Math.min(lines.length, realLine + 2);
      return lines.slice(start, end).map(function (l, i) {
        var n = start + i + 1;
        return (n === realLine ? '>>> ' : '    ') + n + ': ' + l;
      }).join('\n');
    } catch (e) { return ''; }
  }

  function recordPluginError(pluginId, errInfo) {
    MR.errors[pluginId] = errInfo;
  }
  function clearPluginError(pluginId) {
    delete MR.errors[pluginId];
  }

  // 错误提示 Toast (优先用主程序 showToast,降级 KeepApp.showToast)
  function toastPluginError(pluginId, errInfo) {
    var stageLabel = { fetch: '加载', parse: '语法', execute: '执行' }[errInfo.stage] || errInfo.stage;
    var msg = '[' + pluginId + '] ' + stageLabel + '错误: ' + errInfo.message;
    if (msg.length > 140) msg = msg.substring(0, 137) + '...';
    if (typeof global.showToast === 'function') showToast(msg);
    else if (global.KeepApp && global.KeepApp.showToast) global.KeepApp.showToast(msg);
  }

  // ── 加载并执行单个插件的 main.js ────────────────
  function loadPlugin(pluginId, manifest) {
    // ★ 云端开关检查: 默认关闭,只有云端检测返回"开"才允许加载
    if (typeof updateState !== 'undefined' && updateState && !updateState.pluginEnabled) {
      log('云端插件开关为关,拒绝加载:', pluginId);
      return;
    }
    if (MR._cloudDisabled) {
      log('插件系统已被云端冻结,拒绝加载:', pluginId);
      return;
    }
    if (MR.loaded[pluginId]) { warn('插件已加载,跳过:', pluginId); return; }
    log('加载插件:', pluginId, manifest && manifest.name);
    // 清除上次错误状态
    clearPluginError(pluginId);

    fetch('/api/plugins/' + encodeURIComponent(pluginId) + '/main.js?_t=' + Date.now())
      .then(function (r) {
        if (!r.ok) throw new Error('main.js 加载失败 (HTTP ' + r.status + ')');
        return r.text();
      })
      .then(function (code) {
        if (!code || !code.trim()) {
          var e1 = makePluginError('main.js 为空文件', '', 'parse', code);
          recordPluginError(pluginId, e1);
          err('插件 main.js 为空:', pluginId);
          toastPluginError(pluginId, e1);
          refreshList();
          return;
        }
        var api = createPluginApi(pluginId);
        MR.registry[pluginId] = api;
        try {
          MR._pendingFactory = null;

          var hasTopReturn = /^\s*return\s/m.test(code) || /\n\s*return\s/m.test(code);
          var factory;
          // ★ 阶段1: 语法解析 (new Function 在解析期抛 SyntaxError)
          try {
            if (hasTopReturn) {
              factory = new Function('MR', 'api', 'MineradioPlugins', code);
            } else {
              factory = new Function('MR', 'api', 'MineradioPlugins', code + '\n;return (typeof install === "function") ? install : null;');
            }
          } catch (syntaxErr) {
            throw makePluginError('语法错误: ' + (syntaxErr.message || syntaxErr), syntaxErr.stack, 'parse', code);
          }

          // ★ 阶段2: 代码执行
          var ret;
          try {
            if (hasTopReturn) {
              ret = factory(MR, api, global.MineradioPlugins);
              if (typeof ret === 'function') api._uninstall = ret;
              if (MR._pendingFactory) {
                var pf0 = MR._pendingFactory;
                MR._pendingFactory = null;
                var ret0 = pf0.factory(api);
                if (typeof ret0 === 'function') api._uninstall = ret0;
              }
            } else {
              var installFn = factory(MR, api, global.MineradioPlugins);
              if (typeof installFn === 'function') {
                var ret2 = installFn(api);
                if (typeof ret2 === 'function') api._uninstall = ret2;
              } else if (MR._pendingFactory) {
                var pf = MR._pendingFactory;
                MR._pendingFactory = null;
                if (pf.meta.id !== pluginId) {
                  warn('插件 id 不一致: manifest=' + pluginId + ' register=' + pf.meta.id + ',以 manifest 为准');
                }
                var ret3 = pf.factory(api);
                if (typeof ret3 === 'function') api._uninstall = ret3;
              }
            }
          } catch (execErr) {
            // 如果已经是包装过的错误,直接抛;否则包装
            if (execErr && execErr.__mrError) throw execErr;
            throw makePluginError('执行错误: ' + (execErr.message || execErr), execErr.stack, 'execute', code);
          }

          // ★ 阶段3: 检测是否正确注册/返回
          if (!api._uninstall && !MR._pendingFactory) {
            // 没有返回卸载函数也没关系,只是警告
            warn('插件未返回卸载函数:', pluginId);
          }

          MR.loaded[pluginId] = true;
          log('插件加载成功:', pluginId);
          try { api.event.emit('plugin:loaded', pluginId); } catch (e) {}
          if (pluginId === 'login-panel' && typeof global.__enableLoginPanel === 'function') {
            setTimeout(function(){ try { global.__enableLoginPanel(); } catch(e) { err('enableLoginPanel error:', e); } }, 50);
          }
        } catch (e) {
          // ★ 统一错误处理:记录、Toast 提示、刷新列表
          var errInfo = (e && e.__mrError) ? e : makePluginError('执行错误: ' + (e && e.message || e), e && e.stack, 'execute', '');
          recordPluginError(pluginId, errInfo);
          err('插件执行异常:', pluginId, errInfo.message);
          toastPluginError(pluginId, errInfo);
          unloadPlugin(pluginId);
          refreshList();
        }
      })
      .catch(function (e) {
        // ★ 网络加载失败
        var errInfo = makePluginError('加载失败: ' + (e && e.message || e), e && e.stack, 'fetch', '');
        recordPluginError(pluginId, errInfo);
        err('加载插件失败:', pluginId, errInfo.message);
        toastPluginError(pluginId, errInfo);
        refreshList();
      });
  }

  // ── 启动时加载所有已启用的插件 ───────────────────
  function bootstrap() {
    log('初始化插件运行时...');
    // ★ 检查云端插件开关: 默认关闭,只有云端检测返回"开"才加载
    //   不依赖 pluginSwitchApplied,只要 pluginEnabled !== true 就不加载
    if (typeof updateState !== 'undefined' && updateState && !updateState.pluginEnabled) {
      log('云端插件开关为关(或尚未检测到开),跳过插件加载');
      var fab = document.getElementById('plugin-fab');
      if (fab) { fab.style.display = 'none'; fab.style.visibility = 'hidden'; fab.style.opacity = '0'; fab.style.pointerEvents = 'none'; }
      return;
    }
    // ★ 检查插件系统是否被云端冻结
    if (MR._cloudDisabled) {
      log('插件系统已被云端冻结,跳过加载');
      return;
    }
    fetch('/api/plugins/list?_t=' + Date.now())
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data || !data.ok || !Array.isArray(data.plugins)) {
          warn('插件列表为空或格式错误');
          return;
        }
        // ★ 再次检查云端开关(云更新可能在这期间返回)
        if (typeof updateState !== 'undefined' && updateState && !updateState.pluginEnabled) {
          log('云端插件开关为关,不加载插件');
          return;
        }
        log('发现', data.plugins.length, '个已安装插件');
        data.plugins.forEach(function (p) {
          if (p.enabled && p.installed !== false) {
            loadPlugin(p.id, p);
          } else {
            log('跳过未启用的插件:', p.id);
          }
        });
      })
      .catch(function (e) { err('拉取插件列表失败:', e); });
  }

  // ═══════════════════════════════════════════════════
  //  插件管理面板 UI 逻辑
  // ═══════════════════════════════════════════════════
  var panelState = { open: false };

  function openPanel() {
    // ★ 云端插件开关检查: 如果云端禁用,不允许打开面板
    if (typeof updateState !== 'undefined' && updateState && !updateState.pluginEnabled) {
      console.log('[Plugin] 云端已禁用插件功能');
      if (typeof global.showToast === 'function') global.showToast('插件功能已被云端禁用');
      return;
    }
    // ★ 插件系统被冻结时也不允许打开
    if (MR._cloudDisabled) {
      console.log('[Plugin] 插件系统已被云端冻结');
      if (typeof global.showToast === 'function') global.showToast('插件系统已被冻结');
      return;
    }
    var modal = document.getElementById('plugin-manager-modal');
    if (!modal) { err('找不到 #plugin-manager-modal'); return; }
    // ★ 使用项目约定的 .show 类(与 .modal-mask.show 配合)
    modal.classList.add('show');
    // 双保险:直接设置 inline style 覆盖默认隐藏
    modal.style.setProperty('display', 'flex', 'important');
    modal.style.setProperty('opacity', '1', 'important');
    modal.style.setProperty('visibility', 'visible', 'important');
    modal.style.setProperty('z-index', '9999', 'important');
    panelState.open = true;
    log('打开插件管理面板');
    refreshList();
  }
  function closePanel() {
    var modal = document.getElementById('plugin-manager-modal');
    if (!modal) return;
    modal.classList.remove('show');
    modal.style.removeProperty('display');
    modal.style.removeProperty('opacity');
    modal.style.removeProperty('visibility');
    modal.style.removeProperty('z-index');
    panelState.open = false;
  }
  global.openPluginManagerPanel = openPanel;
  global.closePluginManagerPanel = closePanel;
  global.refreshPluginList = function () { refreshList(); };

  // ★★★ 插件市场入口：调用原生 PluginMarketActivity 打开 H5 商店页 ★★★
  global.openPluginMarket = function () {
    if (typeof global.KeepApp === 'undefined' || !global.KeepApp || typeof global.KeepApp.openPluginMarket !== 'function') {
      if (typeof global.showToast === 'function') global.showToast('当前环境不支持打开插件市场');
      else if (global.KeepApp && global.KeepApp.showToast) global.KeepApp.showToast('当前环境不支持打开插件市场');
      return;
    }
    try {
      global.KeepApp.openPluginMarket();
      log('打开插件市场');
    } catch (e) {
      err('openPluginMarket failed: ' + (e && e.message ? e.message : String(e)));
      if (typeof global.showToast === 'function') global.showToast('打开插件市场失败');
    }
  };

  // ★★★ 插件市场视图切换（复用 #plugin-manager-modal，切换内部 view） ★★★
  // H5 页面已内置到 APK（assets/mineradio/market/），通过本地服务器加载，无需梯子
  // ★ 修复：通过 URL hash 告诉 iframe 显示哪个视图，避免 postMessage 时序问题
  //   （iframe 还未加载完成时 postMessage 会丢失，导致点社区却显示插件市场）
  var MARKET_URL = 'http://127.0.0.1:8800/market/index.html';
  function switchToPluginMarketView() {
    var managerView = document.getElementById('plugin-manager-view');
    var marketView = document.getElementById('plugin-market-view');
    if (!managerView || !marketView) { err('找不到插件市场视图元素'); return; }
    managerView.style.display = 'none';
    marketView.style.display = 'flex';
    var iframe = document.getElementById('plugin-market-iframe');
    if (iframe) {
      // ★ 通过 hash='#market' 告诉 iframe 显示插件市场视图
      //   每次切换都强制加载最新版本（添加时间戳避免缓存）
      iframe.src = MARKET_URL + '?t=' + Date.now() + '#market';
    }
    log('切换到插件市场视图');
  }
  function switchToPluginManagerView() {
    var managerView = document.getElementById('plugin-manager-view');
    var marketView = document.getElementById('plugin-market-view');
    if (!managerView || !marketView) return;
    marketView.style.display = 'none';
    managerView.style.display = 'block';
    log('返回插件管理视图');
  }

  // ★★★ 社区入口：切换到市场视图后自动打开社区模态框 ★★★
  function openCommunityInMarket() {
    var managerView = document.getElementById('plugin-manager-view');
    var marketView = document.getElementById('plugin-market-view');
    if (!managerView || !marketView) { err('找不到插件市场视图元素'); return; }
    managerView.style.display = 'none';
    marketView.style.display = 'flex';
    var iframe = document.getElementById('plugin-market-iframe');
    if (iframe) {
      // ★ 修复：始终重新加载 iframe 并通过 hash='#community' 告诉 iframe 显示社区视图
      //   原实现使用 postMessage，但 iframe 还未加载完成时 postMessage 会丢失
      //   导致用户点社区按钮但 iframe 加载完成后默认显示插件市场
      iframe.src = MARKET_URL + '?t=' + Date.now() + '#community';
    }
    log('切换到插件市场视图（社区）');
  }

  // ★★★ 账号信息弹窗（在主页面独立显示，不切换到插件市场视图） ★★★
  //   主页面与 iframe 同源（http://127.0.0.1:8800），共享 localStorage，
  //   因此可直接读取 plugin_market_token / plugin_market_user，并调用后端 /api/auth/me
  //   这样"账号信息"按钮与"插件市场"按钮完全独立，互不影响。
  function showMainAccountInfoModal() {
    var modal = document.getElementById('main-account-info-modal');
    if (!modal) { err('找不到账号信息弹窗元素'); return; }

    var body = document.getElementById('mainAccountInfoBody');
    if (!body) { err('找不到账号信息主体元素'); return; }

    // 读取 token 和 user（与 market/app.js 共享 localStorage）
    var token = localStorage.getItem('plugin_market_token') || '';
    var user = null;
    try { user = JSON.parse(localStorage.getItem('plugin_market_user') || 'null'); } catch (e) {}

    // ★ 优化刷新卡顿：先用缓存的 user 立即渲染（毫秒级），再后台拉取最新数据
    modal.classList.add('show');

    if (!token || !user) {
      // ★ 未登录时直接在账号信息面板内显示登录/注册表单
      //   不再引导用户去"插件市场"，体验更连贯
      body.innerHTML = renderMainAuthForm();
      bindMainAuthEvents(body);
      return;
    }

    // 立即用缓存渲染（避免空白等待）
    renderMainAccountInfo(user, body);

    // 后台异步刷新用户信息（不阻塞 UI，刷新后重新渲染）
    fetch('http://127.0.0.1:8800/api/cloud/api/auth/me', {
      headers: { 'Authorization': 'Bearer ' + token }
    })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data && data.ok && data.data) {
          var newUser = Object.assign({}, user, data.data);
          try { localStorage.setItem('plugin_market_user', JSON.stringify(newUser)); } catch (e) {}
          // 只有数据真的变了才重渲染（避免抖动）
          if (JSON.stringify(newUser) !== JSON.stringify(user)) {
            renderMainAccountInfo(newUser, body);
          }
        }
      })
      .catch(function (e) { /* 网络错误：保持显示缓存数据 */ });
  }

  function renderMainAccountInfo(user, body) {
    var publisherSection = '';
    var app = user.publisher_application;
    if (user.is_publisher) {
      publisherSection = row('发布者', '<span style="color:#4dd0e1">✓ 是（已开通）</span>');
    } else if (app && app.status === 'pending') {
      publisherSection = row('发布者', '<span style="color:#ffb300">⏳ 申请审核中</span>') +
        '<div style="margin-top:8px;padding:8px 10px;border-radius:8px;background:rgba(255,179,0,.06);border:1px solid rgba(255,179,0,.22);font-size:11px;color:rgba(255,179,0,.85);line-height:1.5">已提交申请，等待管理员审核（申请时间：' + fmtMainTime(app.created_at) + '）</div>';
    } else if (app && app.status === 'rejected') {
      publisherSection = row('发布者', '<span style="color:#ef5350">✗ 申请被拒绝</span>') +
        (app.admin_note ? '<div style="margin-top:8px;padding:8px 10px;border-radius:8px;background:rgba(239,83,83,.06);border:1px solid rgba(239,83,83,.22);font-size:11px;color:#ef5350;line-height:1.5">管理员备注：' + escapeHtml(app.admin_note) + '</div>' : '') +
        '<button id="mainApplyPublisherBtn" type="button" style="margin-top:10px;width:100%;height:36px;border-radius:10px;background:rgba(124,77,255,.32);border:1px solid rgba(124,77,255,.5);color:#fff;font-size:12.5px;cursor:pointer">重新申请成为发布者</button>';
    } else {
      publisherSection = row('发布者', '<span style="color:rgba(255,255,255,.6)">✗ 否</span>') +
        '<button id="mainApplyPublisherBtn" type="button" style="margin-top:10px;width:100%;height:36px;border-radius:10px;background:rgba(124,77,255,.32);border:1px solid rgba(124,77,255,.5);color:#fff;font-size:12.5px;cursor:pointer">申请成为发布者</button>';
    }

    var html =
      '<div style="text-align:left;font-size:12.5px;line-height:1.7">' +
      row('用户名', escapeHtml(user.username || '')) +
      row('邮箱', escapeHtml(user.email || '')) +
      row('角色', user.role === 'admin' ? '管理员' : '普通用户') +
      publisherSection +
      '<div style="margin-top:14px;padding:10px 12px;border-radius:10px;background:rgba(124,77,255,.08);border:1px solid rgba(124,77,255,.22);font-size:11.5px;line-height:1.6;color:rgba(255,255,255,.7)">' +
      '你的用户 ID（申请成为发布者时，管理员可通过此 ID 查找你的申请）：' +
      '<code style="display:block;margin-top:6px;padding:6px 8px;background:rgba(0,0,0,.3);border-radius:6px;color:#4dd0e1;font-family:monospace;word-break:break-all">' + escapeHtml(user.id || '') + '</code>' +
      '</div>' +
      '<button id="mainShowMyPluginsBtn" type="button" style="margin-top:14px;width:100%;height:36px;border-radius:10px;background:rgba(124,77,255,.18);border:1px solid rgba(124,77,255,.42);color:#cbb6ff;font-size:12.5px;cursor:pointer">查看我的插件和发帖记录</button>' +
      '<button id="mainLogoutBtn" type="button" style="margin-top:10px;width:100%;height:34px;border-radius:10px;background:rgba(239,83,83,.14);border:1px solid rgba(239,83,83,.42);color:#ef9a9a;font-size:12.5px;cursor:pointer">退出登录</button>' +
      '</div>';

    body.innerHTML = html;

    // 绑定"查看我的插件和评论"按钮
    var btn = document.getElementById('mainShowMyPluginsBtn');
    if (btn) {
      btn.addEventListener('click', function () {
        showMyPluginsInMainModal(user, body);
      });
    }

    // ★ 绑定"退出登录"按钮
    var logoutBtn = document.getElementById('mainLogoutBtn');
    if (logoutBtn) {
      logoutBtn.addEventListener('click', doMainLogout);
    }

    // 绑定"申请成为发布者"按钮
    var applyBtn = document.getElementById('mainApplyPublisherBtn');
    if (applyBtn) {
      applyBtn.addEventListener('click', async function () {
        // 使用主程序原生的 prompt 弹窗
        var reason = (typeof mrAppPrompt === 'function')
          ? await mrAppPrompt('请填写申请理由（可选，帮助管理员了解你）：', '', '申请成为发布者')
          : prompt('请填写申请理由（可选）：') || '';
        if (reason === null) return;  // 用户取消
        reason = String(reason || '').trim();
        applyBtn.disabled = true;
        applyBtn.textContent = '提交中...';
        try {
          var token = localStorage.getItem('plugin_market_token') || '';
          var r = await fetch('http://127.0.0.1:8800/api/cloud/api/auth/apply-publisher', {
            method: 'POST',
            headers: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' },
            body: JSON.stringify({ reason: reason })
          }).then(function (r) { return r.json(); });
          if (r.ok) {
            // 重新加载用户信息（会刷新 publisher_application 状态）
            var refreshR = await fetch('http://127.0.0.1:8800/api/cloud/api/auth/me', {
              headers: { 'Authorization': 'Bearer ' + token }
            }).then(function (r) { return r.json(); });
            if (refreshR.ok) {
              try { localStorage.setItem('plugin_market_user', JSON.stringify(refreshR.data)); } catch (e) {}
              renderMainAccountInfo(refreshR.data, body);
            }
            if (typeof mrAppAlert === 'function') mrAppAlert('申请已提交，请等待管理员审核', '提交成功');
            else alert('申请已提交，请等待管理员审核');
          } else {
            if (typeof mrAppAlert === 'function') mrAppAlert(r.message || r.error || '提交失败', '错误');
            else alert(r.message || r.error || '提交失败');
          }
        } catch (e) {
          if (typeof mrAppAlert === 'function') mrAppAlert('网络错误：' + e.message, '错误');
          else alert('网络错误：' + e.message);
        } finally {
          applyBtn.disabled = false;
          applyBtn.textContent = (user.publisher_application && user.publisher_application.status === 'rejected') ? '重新申请成为发布者' : '申请成为发布者';
        }
      });
    }
  }

  // ★★★ 账号信息面板内置登录/注册表单 ★★★
  //   样式适配主页面账号信息面板（深色玻璃质感、圆角、与已登录视图一致）
  //   直接调用后端 /api/cloud/api/auth/login|register，不再跳转到插件市场

  function renderMainAuthForm() {
    return '' +
      '<div class="main-auth-tabs" style="display:flex;gap:4px;margin-bottom:16px;border-bottom:1px solid rgba(255,255,255,.08)">' +
        '<button type="button" id="mainAuthTabLogin" class="main-auth-tab active" style="flex:1;background:transparent;border:none;color:#4dd0e1;padding:10px;font-size:13px;border-bottom:2px solid #4dd0e1;cursor:pointer">登录</button>' +
        '<button type="button" id="mainAuthTabRegister" class="main-auth-tab" style="flex:1;background:transparent;border:none;color:rgba(255,255,255,.5);padding:10px;font-size:13px;border-bottom:2px solid transparent;cursor:pointer">注册</button>' +
      '</div>' +
      '<div id="mainAuthLoginForm" style="display:flex;flex-direction:column;gap:12px">' +
        '<label style="display:block"><span style="display:block;font-size:12px;color:rgba(255,255,255,.6);margin-bottom:6px">用户名</span>' +
        '<input id="mainLoginUser" type="text" autocomplete="username" style="width:100%;padding:11px 14px;background:rgba(8,9,11,.72);border:1px solid rgba(255,255,255,.1);border-radius:10px;color:#fff;font-size:14px;outline:none;box-sizing:border-box"></label>' +
        '<label style="display:block"><span style="display:block;font-size:12px;color:rgba(255,255,255,.6);margin-bottom:6px">密码</span>' +
        '<input id="mainLoginPass" type="password" autocomplete="current-password" style="width:100%;padding:11px 14px;background:rgba(8,9,11,.72);border:1px solid rgba(255,255,255,.1);border-radius:10px;color:#fff;font-size:14px;outline:none;box-sizing:border-box"></label>' +
        '<button id="mainLoginBtn" type="button" style="width:100%;height:38px;border-radius:10px;background:rgba(77,208,225,.22);border:1px solid rgba(77,208,225,.5);color:#4dd0e1;font-size:13px;cursor:pointer">登录</button>' +
      '</div>' +
      '<div id="mainAuthRegisterForm" style="display:none;flex-direction:column;gap:12px">' +
        '<label style="display:block"><span style="display:block;font-size:12px;color:rgba(255,255,255,.6);margin-bottom:6px">用户名（3-20 位字母数字下划线）</span>' +
        '<input id="mainRegUser" type="text" style="width:100%;padding:11px 14px;background:rgba(8,9,11,.72);border:1px solid rgba(255,255,255,.1);border-radius:10px;color:#fff;font-size:14px;outline:none;box-sizing:border-box"></label>' +
        '<label style="display:block"><span style="display:block;font-size:12px;color:rgba(255,255,255,.6);margin-bottom:6px">邮箱</span>' +
        '<input id="mainRegEmail" type="email" style="width:100%;padding:11px 14px;background:rgba(8,9,11,.72);border:1px solid rgba(255,255,255,.1);border-radius:10px;color:#fff;font-size:14px;outline:none;box-sizing:border-box"></label>' +
        '<label style="display:block"><span style="display:block;font-size:12px;color:rgba(255,255,255,.6);margin-bottom:6px">密码（至少 8 位）</span>' +
        '<input id="mainRegPass" type="password" style="width:100%;padding:11px 14px;background:rgba(8,9,11,.72);border:1px solid rgba(255,255,255,.1);border-radius:10px;color:#fff;font-size:14px;outline:none;box-sizing:border-box"></label>' +
        '<button id="mainRegisterBtn" type="button" style="width:100%;height:38px;border-radius:10px;background:rgba(124,77,255,.22);border:1px solid rgba(124,77,255,.5);color:#cbb6ff;font-size:13px;cursor:pointer">注册</button>' +
      '</div>' +
      '<div id="mainAuthMsg" style="margin-top:10px;font-size:12px;text-align:center;min-height:16px"></div>';
  }

  function bindMainAuthEvents(body) {
    // Tab 切换
    var tabLogin = body.querySelector('#mainAuthTabLogin');
    var tabReg = body.querySelector('#mainAuthTabRegister');
    var loginForm = body.querySelector('#mainAuthLoginForm');
    var regForm = body.querySelector('#mainAuthRegisterForm');
    function switchTab(tab) {
      if (tab === 'login') {
        tabLogin.style.color = '#4dd0e1'; tabLogin.style.borderBottomColor = '#4dd0e1';
        tabReg.style.color = 'rgba(255,255,255,.5)'; tabReg.style.borderBottomColor = 'transparent';
        loginForm.style.display = 'flex'; regForm.style.display = 'none';
      } else {
        tabReg.style.color = '#4dd0e1'; tabReg.style.borderBottomColor = '#4dd0e1';
        tabLogin.style.color = 'rgba(255,255,255,.5)'; tabLogin.style.borderBottomColor = 'transparent';
        loginForm.style.display = 'none'; regForm.style.display = 'flex';
      }
    }
    if (tabLogin) tabLogin.addEventListener('click', function () { switchTab('login'); });
    if (tabReg) tabReg.addEventListener('click', function () { switchTab('register'); });

    var loginBtn = body.querySelector('#mainLoginBtn');
    var regBtn = body.querySelector('#mainRegisterBtn');
    if (loginBtn) loginBtn.addEventListener('click', doMainLogin);
    if (regBtn) regBtn.addEventListener('click', doMainRegister);

    // 回车提交
    var loginPass = body.querySelector('#mainLoginPass');
    var regPass = body.querySelector('#mainRegPass');
    if (loginPass) loginPass.addEventListener('keydown', function (e) { if (e.key === 'Enter') doMainLogin(); });
    if (regPass) regPass.addEventListener('keydown', function (e) { if (e.key === 'Enter') doMainRegister(); });
  }

  function showMainAuthMsg(msg, isErr) {
    var el = document.getElementById('mainAuthMsg');
    if (!el) return;
    el.textContent = msg || '';
    el.style.color = isErr ? '#ef5350' : '#4dd0e1';
  }

  async function doMainLogin() {
    var userEl = document.getElementById('mainLoginUser');
    var passEl = document.getElementById('mainLoginPass');
    var btn = document.getElementById('mainLoginBtn');
    if (!userEl || !passEl) return;
    var username = userEl.value.trim();
    var password = passEl.value;
    if (!username || !password) { showMainAuthMsg('请填写用户名和密码', true); return; }
    if (btn) { btn.disabled = true; btn.textContent = '登录中...'; }
    showMainAuthMsg('', false);
    try {
      var r = await fetch('http://127.0.0.1:8800/api/cloud/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: username, password: password })
      }).then(function (r) { return r.json(); });
      if (r.ok && r.data && r.data.token) {
        try {
          localStorage.setItem('plugin_market_token', r.data.token);
          localStorage.setItem('plugin_market_user', JSON.stringify(r.data.user));
        } catch (e) {}
        showMainAuthMsg('登录成功', false);
        // 重新渲染为已登录状态
        var body = document.getElementById('mainAccountInfoBody');
        if (body) renderMainAccountInfo(r.data.user, body);
        // 后台异步刷新
        fetch('http://127.0.0.1:8800/api/cloud/api/auth/me', { headers: { 'Authorization': 'Bearer ' + r.data.token } })
          .then(function (r) { return r.json(); })
          .then(function (data) {
            if (data && data.ok && data.data) {
              var newU = Object.assign({}, r.data.user, data.data);
              try { localStorage.setItem('plugin_market_user', JSON.stringify(newU)); } catch (e) {}
              var b2 = document.getElementById('mainAccountInfoBody');
              if (b2) renderMainAccountInfo(newU, b2);
            }
          }).catch(function () {});
      } else {
        showMainAuthMsg(r.message || r.error || '登录失败', true);
      }
    } catch (e) {
      showMainAuthMsg('网络错误：' + e.message, true);
    } finally {
      if (btn) { btn.disabled = false; btn.textContent = '登录'; }
    }
  }

  async function doMainRegister() {
    var userEl = document.getElementById('mainRegUser');
    var emailEl = document.getElementById('mainRegEmail');
    var passEl = document.getElementById('mainRegPass');
    var btn = document.getElementById('mainRegisterBtn');
    if (!userEl || !emailEl || !passEl) return;
    var username = userEl.value.trim();
    var email = emailEl.value.trim();
    var password = passEl.value;
    if (!username || !email || !password) { showMainAuthMsg('请填写完整信息', true); return; }
    if (password.length < 8) { showMainAuthMsg('密码至少 8 位', true); return; }
    if (btn) { btn.disabled = true; btn.textContent = '注册中...'; }
    showMainAuthMsg('', false);
    try {
      var r = await fetch('http://127.0.0.1:8800/api/cloud/api/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: username, email: email, password: password })
      }).then(function (r) { return r.json(); });
      if (r.ok && r.data && r.data.token) {
        try {
          localStorage.setItem('plugin_market_token', r.data.token);
          localStorage.setItem('plugin_market_user', JSON.stringify(r.data.user));
        } catch (e) {}
        showMainAuthMsg('注册成功，已自动登录', false);
        var body = document.getElementById('mainAccountInfoBody');
        if (body) renderMainAccountInfo(r.data.user, body);
      } else {
        showMainAuthMsg(r.message || r.error || '注册失败', true);
      }
    } catch (e) {
      showMainAuthMsg('网络错误：' + e.message, true);
    } finally {
      if (btn) { btn.disabled = false; btn.textContent = '注册'; }
    }
  }

  function doMainLogout() {
    try {
      localStorage.removeItem('plugin_market_token');
      localStorage.removeItem('plugin_market_user');
    } catch (e) {}
    // 重新渲染为登录表单
    var body = document.getElementById('mainAccountInfoBody');
    if (body) {
      body.innerHTML = renderMainAuthForm();
      bindMainAuthEvents(body);
    }
  }

  function fmtMainTime(ts) {
    if (!ts) return '-';
    var d = new Date(ts * 1000);
    return d.toLocaleString('zh-CN', { hour12: false });
  }

  // 在账号信息弹窗内显示用户已发布的插件和发帖记录
  function showMyPluginsInMainModal(user, body) {
    var token = localStorage.getItem('plugin_market_token') || '';
    body.innerHTML = '<div style="text-align:center;padding:24px;color:rgba(255,255,255,.6);font-size:13px">加载中...</div>';

    fetch('http://127.0.0.1:8800/api/cloud/api/plugins/my', {
      headers: { 'Authorization': 'Bearer ' + token }
    })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data || !data.ok) {
          body.innerHTML =
            '<div style="text-align:center;padding:20px;color:rgba(255,255,255,.6);font-size:13px">' +
            escapeHtml((data && (data.message || data.error)) || '加载失败') +
            '</div>' + backToAccountInfoBtn(user, body);
          bindBackBtn(user, body);
          return;
        }
        var plugins = (data.data && data.data.plugins) || [];
        var posts = (data.data && data.data.posts) || [];

        var html = '<div style="text-align:left;font-size:12.5px;line-height:1.6">';

        // 插件列表
        html += '<div style="font-size:13px;color:rgba(255,255,255,.85);font-weight:600;margin-bottom:8px;padding-bottom:6px;border-bottom:1px solid rgba(255,255,255,.08)">已上传 ' + plugins.length + ' 个插件</div>';
        if (plugins.length === 0) {
          html += '<div style="text-align:center;padding:12px;color:rgba(255,255,255,.45);font-size:12px">暂未上传任何插件</div>';
        } else {
          plugins.forEach(function (p) {
            var statusText = p.status === 'pending' ? '待审核' : (p.status === 'published' ? '已发布' : '已拒绝');
            var statusColor = p.status === 'pending' ? '#ffb300' : (p.status === 'published' ? '#4dd0e1' : '#ef5350');
            html +=
              '<div data-plugin-id="' + escapeHtml(p.id) + '" style="padding:10px 12px;margin-bottom:8px;border-radius:10px;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.06)">' +
              '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:4px">' +
              '<span style="color:rgba(255,255,255,.92);font-weight:600;font-size:13px">' + escapeHtml(p.name) + '</span>' +
              '<span style="color:' + statusColor + ';font-size:11px;font-weight:600">' + statusText + '</span>' +
              '</div>' +
              '<div style="color:rgba(255,255,255,.5);font-size:11px">v' + escapeHtml(p.version) + ' · ' + fmtMainSize(p.file_size) + ' · 下载 ' + (p.download_count || 0) + ' 次</div>' +
              (p.description ? '<div style="color:rgba(255,255,255,.65);font-size:11.5px;margin-top:4px;line-height:1.5">' + escapeHtml(p.description) + '</div>' : '') +
              (p.status === 'rejected' && p.review_note ? '<div style="color:#ef5350;font-size:11px;margin-top:6px;padding:6px 8px;background:rgba(239,83,83,.08);border-radius:6px">拒绝原因：' + escapeHtml(p.review_note) + '</div>' : '') +
              '<div style="display:flex;gap:6px;margin-top:8px">' +
              '<button type="button" data-act="edit-plugin" data-id="' + escapeHtml(p.id) + '" data-name="' + escapeHtml(p.name) + '" data-version="' + escapeHtml(p.version) + '" data-desc="' + escapeHtml(p.description || '') + '" style="flex:1;padding:6px 10px;border-radius:8px;background:rgba(124,77,255,.16);border:1px solid rgba(124,77,255,.4);color:#cbb6ff;font-size:11.5px;cursor:pointer">编辑</button>' +
              '<button type="button" data-act="del-plugin" data-id="' + escapeHtml(p.id) + '" data-name="' + escapeHtml(p.name) + '" style="flex:1;padding:6px 10px;border-radius:8px;background:rgba(239,83,83,.16);border:1px solid rgba(239,83,83,.4);color:#ef5350;font-size:11.5px;cursor:pointer">删除</button>' +
              '</div>' +
              '</div>';
          });
        }

        // 发帖记录（社区反馈）
        html += '<div style="font-size:13px;color:rgba(255,255,255,.85);font-weight:600;margin:14px 0 8px;padding-bottom:6px;border-bottom:1px solid rgba(255,255,255,.08)">我的发帖记录（共 ' + posts.length + ' 条）<span style="font-size:11px;color:rgba(255,255,255,.5);font-weight:normal">· 点击查看别人回复</span></div>';
        if (posts.length === 0) {
          html += '<div style="text-align:center;padding:12px;color:rgba(255,255,255,.45);font-size:12px">暂无发帖记录</div>';
        } else {
          posts.forEach(function (p) {
            var preview = (p.content || '').substring(0, 80);
            if ((p.content || '').length > 80) preview += '...';
            html +=
              '<div data-act="view-post" data-post-id="' + escapeHtml(p.id) + '" style="padding:10px 12px;margin-bottom:8px;border-radius:10px;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.06);cursor:pointer">' +
              '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:4px">' +
              '<span style="color:rgba(255,255,255,.92);font-weight:600;font-size:13px">' + escapeHtml(p.title) + '</span>' +
              '<span style="color:#4dd0e1;font-size:11px">' + (p.comment_count || 0) + ' 回复</span>' +
              '</div>' +
              (preview ? '<div style="color:rgba(255,255,255,.65);font-size:11.5px;line-height:1.5">' + escapeHtml(preview) + '</div>' : '') +
              '<div style="color:rgba(255,255,255,.4);font-size:10.5px;margin-top:4px">' + fmtMainTime(p.created_at) + '</div>' +
              '</div>';
          });
        }

        html += '</div>';
        // 返回按钮
        html += backToAccountInfoBtn(user, body);

        body.innerHTML = html;

        bindBackBtn(user, body);

        // 绑定插件编辑/删除按钮
        body.querySelectorAll('button[data-act="del-plugin"]').forEach(function (btn) {
          btn.addEventListener('click', function (e) {
            e.stopPropagation();
            handleDeletePlugin(btn.dataset.id, btn.dataset.name, user, body);
          });
        });
        body.querySelectorAll('button[data-act="edit-plugin"]').forEach(function (btn) {
          btn.addEventListener('click', function (e) {
            e.stopPropagation();
            handleEditPlugin(btn, user, body);
          });
        });

        // 绑定帖子点击 → 打开专属反馈界面
        body.querySelectorAll('div[data-act="view-post"]').forEach(function (card) {
          card.addEventListener('click', function () {
            showPostDetailInMainModal(card.dataset.postId, user, body);
          });
        });
      })
      .catch(function (e) {
        body.innerHTML =
          '<div style="text-align:center;padding:20px;color:rgba(255,255,255,.6);font-size:13px">加载失败：' + escapeHtml(e.message || '') + '</div>' +
          backToAccountInfoBtn(user, body);
        bindBackBtn(user, body);
      });
  }

  // 绑定返回账号信息按钮
  function bindBackBtn(user, body) {
    var backBtn = document.getElementById('mainBackToAccountBtn');
    if (backBtn) {
      backBtn.addEventListener('click', function () {
        renderMainAccountInfo(user, body);
      });
    }
  }

  // 删除插件（用户操作）
  async function handleDeletePlugin(pluginId, pluginName, user, body) {
    var ok = await mrAppConfirm('确定删除插件「' + pluginName + '」？删除后无法恢复。', '删除插件');
    if (!ok) return;
    try {
      var token = localStorage.getItem('plugin_market_token') || '';
      var r = await fetch('http://127.0.0.1:8800/api/cloud/api/plugins/' + pluginId, {
        method: 'DELETE',
        headers: { 'Authorization': 'Bearer ' + token }
      }).then(function (r) { return r.json(); });
      if (r.ok) {
        mrAppAlert('已删除', '成功');
        showMyPluginsInMainModal(user, body);
      } else {
        mrAppAlert(r.message || r.error || '删除失败', '错误');
      }
    } catch (e) {
      mrAppAlert('网络错误：' + (e.message || ''), '错误');
    }
  }

  // 编辑插件（重新提交审核）- 完整面板，支持重新上传文件
  async function handleEditPlugin(btn, user, body) {
    var pluginId = btn.dataset.id;
    var oldName = btn.dataset.name || '';
    var oldVersion = btn.dataset.version || '';
    var oldDesc = btn.dataset.desc || '';

    // 渲染编辑面板
    var html = '<div style="text-align:left;font-size:12.5px;line-height:1.6">';
    html += '<button id="mainEditCancelTopBtn" type="button" style="margin-bottom:12px;padding:6px 14px;border-radius:8px;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.12);color:rgba(255,255,255,.8);font-size:12px;cursor:pointer">← 返回</button>';
    html += '<div style="font-size:14px;color:#cbb6ff;font-weight:600;margin-bottom:14px;padding-bottom:8px;border-bottom:1px solid rgba(124,77,255,.22)">编辑插件（提交后重新审核）</div>';

    html += '<div style="margin-bottom:12px">';
    html += '<label style="display:block;color:rgba(255,255,255,.6);font-size:11px;margin-bottom:6px">插件名（1-50 字）</label>';
    html += '<input id="mainEditName" type="text" value="' + escapeHtml(oldName) + '" style="width:100%;padding:9px 12px;border-radius:8px;background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.12);color:#fff;font-size:13px;box-sizing:border-box;outline:none" />';
    html += '</div>';

    html += '<div style="margin-bottom:12px">';
    html += '<label style="display:block;color:rgba(255,255,255,.6);font-size:11px;margin-bottom:6px">版本号</label>';
    html += '<input id="mainEditVersion" type="text" value="' + escapeHtml(oldVersion) + '" style="width:100%;padding:9px 12px;border-radius:8px;background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.12);color:#fff;font-size:13px;box-sizing:border-box;outline:none" />';
    html += '</div>';

    html += '<div style="margin-bottom:12px">';
    html += '<label style="display:block;color:rgba(255,255,255,.6);font-size:11px;margin-bottom:6px">描述（可选，最多 500 字）</label>';
    html += '<textarea id="mainEditDesc" rows="4" style="width:100%;padding:9px 12px;border-radius:8px;background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.12);color:#fff;font-size:13px;box-sizing:border-box;outline:none;resize:vertical;min-height:80px;font-family:inherit">' + escapeHtml(oldDesc) + '</textarea>';
    html += '</div>';

    html += '<div style="margin-bottom:14px">';
    html += '<label style="display:block;color:rgba(255,255,255,.6);font-size:11px;margin-bottom:6px">重新上传插件文件（可选，不选则保留原文件）</label>';
    html += '<input id="mainEditFile" type="file" accept=".MR,.zip,.apk,.js,.json,.tar,.gz,application/vnd.android.package-archive" style="width:100%;padding:6px;border-radius:8px;background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.12);color:#fff;font-size:12px;box-sizing:border-box;outline:none" />';
    html += '<div style="color:rgba(255,255,255,.4);font-size:10.5px;margin-top:4px">支持 .MR / .zip / .apk / .js / .json / .tar / .gz</div>';
    html += '</div>';

    html += '<button id="mainEditSubmitBtn" type="button" style="width:100%;height:40px;border-radius:10px;background:rgba(124,77,255,.25);border:1px solid rgba(124,77,255,.5);color:#cbb6ff;font-size:13px;font-weight:600;cursor:pointer">提交（等待管理员重新审核）</button>';

    html += '</div>';
    body.innerHTML = html;

    // 绑定返回按钮
    var cancelTopBtn = document.getElementById('mainEditCancelTopBtn');
    if (cancelTopBtn) {
      cancelTopBtn.addEventListener('click', function () {
        showMyPluginsInMainModal(user, body);
      });
    }

    // 绑定提交按钮
    var submitBtn = document.getElementById('mainEditSubmitBtn');
    if (submitBtn) {
      submitBtn.addEventListener('click', async function () {
        var newName = (document.getElementById('mainEditName').value || '').trim();
        var newVersion = (document.getElementById('mainEditVersion').value || '').trim();
        var newDesc = (document.getElementById('mainEditDesc').value || '').trim();
        var fileInput = document.getElementById('mainEditFile');
        var file = fileInput && fileInput.files && fileInput.files[0];

        if (!newName) { mrAppAlert('插件名不能为空', '错误'); return; }
        if (!newVersion) { mrAppAlert('版本号不能为空', '错误'); return; }

        var payload = { name: newName, version: newVersion, description: newDesc };

        submitBtn.disabled = true;
        submitBtn.textContent = '提交中...';

        try {
          // 如果选了文件，先读取为 base64
          if (file) {
            submitBtn.textContent = '读取文件中...';
            var fileBase64 = await new Promise(function (resolve, reject) {
              var reader = new FileReader();
              reader.onload = function () {
                var s = String(reader.result || '');
                var idx = s.indexOf(',');
                resolve(idx >= 0 ? s.substring(idx + 1) : s);
              };
              reader.onerror = function () { reject(reader.error); };
              reader.readAsDataURL(file);
            });
            payload.file_base64 = fileBase64;
            payload.filename = file.name;
          }

          submitBtn.textContent = '上传中...';
          var token = localStorage.getItem('plugin_market_token') || '';
          var r = await fetch('http://127.0.0.1:8800/api/cloud/api/plugins/' + pluginId, {
            method: 'PUT',
            headers: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
          }).then(function (r) { return r.json(); });
          if (r.ok) {
            mrAppAlert('已提交，等待管理员重新审核', '成功');
            showMyPluginsInMainModal(user, body);
          } else {
            mrAppAlert(r.message || r.error || '提交失败', '错误');
          }
        } catch (e) {
          mrAppAlert('网络错误：' + (e.message || ''), '错误');
        } finally {
          submitBtn.disabled = false;
          submitBtn.textContent = '提交（等待管理员重新审核）';
        }
      });
    }
  }

  // 专属反馈界面：在账号信息弹窗内显示某个帖子详情 + 别人回复
  function showPostDetailInMainModal(postId, user, body) {
    body.innerHTML = '<div style="text-align:center;padding:24px;color:rgba(255,255,255,.6);font-size:13px">加载中...</div>';
    var token = localStorage.getItem('plugin_market_token') || '';
    Promise.all([
      fetch('http://127.0.0.1:8800/api/cloud/api/posts/' + postId, {
        headers: { 'Authorization': 'Bearer ' + token }
      }).then(function (r) { return r.json(); }),
      fetch('http://127.0.0.1:8800/api/cloud/api/posts/' + postId + '/comments', {
        headers: { 'Authorization': 'Bearer ' + token }
      }).then(function (r) { return r.json(); })
    ]).then(function (results) {
      var postR = results[0];
      var commentsR = results[1];
      if (!postR.ok) {
        body.innerHTML =
          '<div style="text-align:center;padding:20px;color:rgba(255,255,255,.6);font-size:13px">帖子不存在或已删除</div>' +
          backToAccountInfoBtn(user, body);
        bindBackBtn(user, body);
        return;
      }
      var p = postR.data;
      var comments = (commentsR.ok && commentsR.data) ? commentsR.data : [];

      var html = '<div style="text-align:left;font-size:12.5px;line-height:1.6">';
      // 返回到我的发帖记录按钮（上方）
      html += '<button id="mainBackToMyPostsBtn" type="button" style="margin-bottom:12px;padding:6px 14px;border-radius:8px;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.12);color:rgba(255,255,255,.8);font-size:12px;cursor:pointer">← 返回发帖记录</button>';

      // 帖子主体
      html += '<div style="padding:12px 14px;border-radius:12px;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.08);margin-bottom:14px">' +
        '<div style="color:rgba(255,255,255,.95);font-weight:600;font-size:14px;margin-bottom:6px">' + escapeHtml(p.title) + '</div>' +
        '<div style="color:rgba(255,255,255,.5);font-size:11px;margin-bottom:8px">' + escapeHtml(p.author_name || '我') + ' · ' + fmtMainTime(p.created_at) + '</div>' +
        '<div style="color:rgba(255,255,255,.8);font-size:12.5px;white-space:pre-wrap;word-break:break-word;line-height:1.6">' + escapeHtml(p.content || '') + '</div>' +
        '</div>';

      // 评论列表（别人的回复）
      html += '<div style="font-size:13px;color:rgba(255,255,255,.85);font-weight:600;margin:8px 0;padding-bottom:6px;border-bottom:1px solid rgba(255,255,255,.08)">回复（' + comments.length + '）<span style="font-size:11px;color:rgba(255,255,255,.5);font-weight:normal">· 评论仅保留 7 天</span></div>';
      if (comments.length === 0) {
        html += '<div style="text-align:center;padding:14px;color:rgba(255,255,255,.45);font-size:12px">暂无回复</div>';
      } else {
        comments.forEach(function (c) {
          html +=
            '<div style="padding:10px 12px;margin-bottom:8px;border-radius:10px;background:rgba(255,255,255,.03);border:1px solid rgba(255,255,255,.05)">' +
            '<div style="color:rgba(255,255,255,.5);font-size:11px;margin-bottom:4px">' + escapeHtml(c.author_name || '匿名') + ' · ' + fmtMainTime(c.created_at) + '</div>' +
            '<div style="color:rgba(255,255,255,.8);font-size:12px;line-height:1.5;white-space:pre-wrap;word-break:break-word">' + escapeHtml(c.content) + '</div>' +
            '</div>';
        });
      }

      html += '</div>';
      // 返回账号信息按钮
      html += backToAccountInfoBtn(user, body);

      body.innerHTML = html;

      // 绑定返回到我的发帖记录
      var backToPostsBtn = document.getElementById('mainBackToMyPostsBtn');
      if (backToPostsBtn) {
        backToPostsBtn.addEventListener('click', function () {
          showMyPluginsInMainModal(user, body);
        });
      }
      bindBackBtn(user, body);
    }).catch(function (e) {
      body.innerHTML =
        '<div style="text-align:center;padding:20px;color:rgba(255,255,255,.6);font-size:13px">加载失败：' + escapeHtml(e.message || '') + '</div>' +
        backToAccountInfoBtn(user, body);
      bindBackBtn(user, body);
    });
  }

  function backToAccountInfoBtn(user, body) {
    return '<button id="mainBackToAccountBtn" type="button" style="margin-top:14px;width:100%;height:36px;border-radius:10px;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.12);color:rgba(255,255,255,.8);font-size:12.5px;cursor:pointer">返回账号信息</button>';
  }

  function fmtMainSize(bytes) {
    if (!bytes) return '0 B';
    var units = ['B', 'KB', 'MB', 'GB'];
    var i = 0; var n = bytes;
    while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
    return n.toFixed(1) + ' ' + units[i];
  }

  function row(label, value) {
    return '<div style="display:flex;justify-content:space-between;align-items:center;padding:7px 0;border-bottom:1px solid rgba(255,255,255,.06)">' +
      '<span style="color:rgba(255,255,255,.5)">' + label + '</span>' +
      '<span style="color:rgba(255,255,255,.9);text-align:right">' + value + '</span>' +
      '</div>';
  }

  function closeMainAccountInfoModal() {
    var modal = document.getElementById('main-account-info-modal');
    if (modal) modal.classList.remove('show');
  }

  global.switchToPluginMarketView = switchToPluginMarketView;
  global.switchToPluginManagerView = switchToPluginManagerView;
  global.openCommunityInMarket = openCommunityInMarket;
  global.showMainAccountInfoModal = showMainAccountInfoModal;
  global.closeMainAccountInfoModal = closeMainAccountInfoModal;

  // ★★★ iframe 通信：监听市场页 postMessage，转发到原生 KeepApp.installPluginFromUrl ★★★
  if (!global.__marketMessageListenerAdded) {
    global.__marketMessageListenerAdded = true;
    // 安装结果回调表
    global.__marketCallbackMap = {};
    // 安装结果回调（由原生 KeepApp.installPluginFromUrl 调用 window.__onMarketInstall）
    global.__onMarketInstall = function (callbackId, ok, msg) {
      var cb = global.__marketCallbackMap[callbackId];
      if (cb) {
        try { cb(ok, msg); } catch (e) { err('market install callback error: ' + e); }
        delete global.__marketCallbackMap[callbackId];
      }
    };
    window.addEventListener('message', function (event) {
      var data = event.data;
      if (!data || typeof data !== 'object') return;
      if (data.type === 'install_plugin' && data.url && data.filename) {
        var callbackId = data.callbackId || ('cb_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6));
        // 注册回调，安装完成后通过 postMessage 通知 iframe
        global.__marketCallbackMap[callbackId] = function (ok, msg) {
          var iframe = document.getElementById('plugin-market-iframe');
          if (iframe && iframe.contentWindow) {
            try {
              iframe.contentWindow.postMessage({ type: 'install_result', callbackId: callbackId, ok: ok, msg: msg }, '*');
            } catch (e) { err('postMessage to iframe failed: ' + e); }
          }
        };
        if (global.KeepApp && typeof global.KeepApp.installPluginFromUrl === 'function') {
          try {
            global.KeepApp.installPluginFromUrl(data.url, data.filename, callbackId);
          } catch (e) {
            err('KeepApp.installPluginFromUrl failed: ' + e);
            global.__marketCallbackMap[callbackId](false, '调用原生安装失败：' + (e && e.message ? e.message : String(e)));
          }
        } else {
          global.__marketCallbackMap[callbackId](false, '当前环境不支持原生安装');
        }
      }
    });
    log('插件市场 iframe message 监听器已注册');
  }

  // ★ 描述文字点击展开/收起: 在原位置展开完整描述,点击其他地方关闭
  global.togglePluginDesc = function (el) {
    if (!el || !el.classList) return;
    var wasExpanded = el.classList.contains('expanded');
    // 先关闭所有其他展开的描述
    var all = document.querySelectorAll('.plugin-item-desc.expanded');
    for (var i = 0; i < all.length; i++) {
      if (all[i] !== el) all[i].classList.remove('expanded');
    }
    // 切换当前
    if (wasExpanded) el.classList.remove('expanded');
    else el.classList.add('expanded');
  };
  // 点击其他地方(非描述文字)关闭所有展开
  document.addEventListener('click', function (e) {
    var expanded = document.querySelectorAll('.plugin-item-desc.expanded');
    if (!expanded.length) return;
    // 如果点击的是某个描述文字,交给 togglePluginDesc 处理
    if (e.target && e.target.classList && e.target.classList.contains('plugin-item-desc')) return;
    for (var i = 0; i < expanded.length; i++) expanded[i].classList.remove('expanded');
  });

  function refreshList() {
    var container = document.getElementById('plugin-list-container');
    if (!container) return;
    container.innerHTML = '<div class="plugin-empty">加载中...</div>';
    fetch('/api/plugins/list?_t=' + Date.now())
      .then(function (r) { return r.json(); })
      .then(function (data) {
        var plugins = (data && data.ok && data.plugins) ? data.plugins : [];
        renderList(plugins);
        // 同时更新入口副标题
        var sub = document.getElementById('plugin-entry-sub');
        if (sub) sub.textContent = plugins.length ? (plugins.length + ' 个已安装') : '点击安装插件';
      })
      .catch(function (e) {
        container.innerHTML = '<div class="plugin-empty" style="color:#ef5350">加载失败: ' + escapeHtml(e.message) + '</div>';
      });
  }

  function renderList(plugins) {
    var container = document.getElementById('plugin-list-container');
    if (!container) return;
    if (!plugins.length) {
      container.innerHTML = '<div class="plugin-empty">暂无已安装插件,点击上方按钮安装</div>';
      // 即使无插件也通知插件列表已渲染
      notifyPluginListRendered();
      return;
    }
    var html = plugins.map(function (p) {
      var hasError = !!MR.errors[p.id];
      var errInfo = hasError ? MR.errors[p.id] : null;
      var stageLabel = errInfo ? ({ fetch: '加载', parse: '语法', execute: '执行' }[errInfo.stage] || errInfo.stage) : '';
      var errFlag = hasError
        ? ' <span class="plugin-err-flag" title="' + escapeHtml(errInfo.message) + '">⚠ ' + escapeHtml(stageLabel) + '错误</span>'
        : '';
      var errBadge = hasError
        ? '<button class="plugin-error-badge" onclick="showPluginErrorDetail(\'' + escapeHtml(p.id) + '\')" title="查看错误详情">错误详情</button>'
        : '';
      return [
        '<div class="plugin-item' + (hasError ? ' plugin-item-error' : '') + '" data-id="' + escapeHtml(p.id) + '">',
        '  <div class="plugin-item-info">',
        '    <div class="plugin-item-name">' + escapeHtml(p.name || p.id) + ' <span class="plugin-item-ver">v' + escapeHtml(p.version || '1.0.0') + '</span>' + errFlag + '</div>',
        '    <div class="plugin-item-desc" onclick="togglePluginDesc(this)" title="点击查看完整描述">' + escapeHtml(p.description || '(无描述)') + '</div>',
        '    <div class="plugin-item-meta">ID: ' + escapeHtml(p.id) + ' · 作者: ' + escapeHtml(p.author || 'unknown') + (hasError ? ' · <span style="color:#ef5350">' + escapeHtml(errInfo.time) + '</span>' : '') + '</div>',
        '  </div>',
        '  <div class="plugin-item-actions">',
        errBadge,
        '    <button class="fx-mini-btn ' + (p.enabled ? 'primary' : 'ghost') + '" onclick="togglePlugin(\'' + escapeHtml(p.id) + '\',' + !p.enabled + ')">' + (p.enabled ? '已启用' : '已禁用') + '</button>',
        '    <button class="fx-mini-btn ghost danger" onclick="uninstallPlugin(\'' + escapeHtml(p.id) + '\', this)">卸载</button>',
        '  </div>',
        '</div>'
      ].join('');
    }).join('');
    container.innerHTML = html;
    // ★ 通知插件列表已渲染完成,让插件能立即注入"打开面板"按钮
    //   (不依赖 MutationObserver 的异步触发,避免需要关闭再打开才显示)
    notifyPluginListRendered();
    // ★ v2.2.2 恢复持久化的加载进度条（关闭面板重开后能继续显示未完成的进度）
    _restorePersistedProgress();
  }

  // ★ 派发"插件列表已渲染"事件,让插件能立即注入按钮
  function notifyPluginListRendered() {
    try {
      // 1. 通过 CustomEvent 通知 (插件可用 api.event.on('pluginListRendered', cb) 监听)
      if (typeof global.CustomEvent === 'function') {
        var evt = new CustomEvent('pluginListRendered', { detail: { container: '#plugin-list-container' } });
        document.dispatchEvent(evt);
      }
      // 2. 通过 MR 事件总线通知
      if (MR.api && MR.api.event) {
        MR.api.event.emit('pluginListRendered');
      }
      // 3. 兜底: setTimeout(0) 确保 MutationObserver 有机会触发
      setTimeout(function () {
        // 手动触发一次 #plugin-list-container 的"变化",让 Observer 回调执行
        var container = document.getElementById('plugin-list-container');
        if (container) {
          var dummy = document.createComment('mr-render-done');
          container.appendChild(dummy);
          if (dummy.parentNode) dummy.parentNode.removeChild(dummy);
        }
      }, 0);
    } catch (e) {}
  }

  // ── 启用/禁用 ────────────────────────────────────
  global.togglePlugin = function (id, enable) {
    var url = '/api/plugins/' + (enable ? 'enable' : 'disable') + '?id=' + encodeURIComponent(id);

    // ★ v2.2 新增：启用大插件时显示加载进度条
    //   避免用户以为卡死，特别是含 SDK/模型的大插件（如 desktop-pet.MR 6.8MB）
    var progressEl = null;
    if (enable) {
      progressEl = showPluginLoadingProgress(id);
    }

    fetch(url).then(function () {
      // 同步本地：启用→加载；禁用→卸载
      if (enable) {
        fetch('/api/plugins/list?_t=' + Date.now())
          .then(function (r) { return r.json(); })
          .then(function (data) {
            var p = (data.plugins || []).find(function (it) { return it.id === id; });
            if (p) {
              // ★ 更新进度：开始加载 main.js
              updatePluginLoadingProgress(id, 50, '正在加载插件代码...');
              // 给一个小延迟让 UI 更新
              setTimeout(function () {
                loadPlugin(id, p);
                // loadPlugin 是异步的，给它 1 秒完成 fetch + 执行
                setTimeout(function () {
                  updatePluginLoadingProgress(id, 100, '加载完成');
                  // 500ms 后移除进度条
                  setTimeout(function () { removePluginLoadingProgress(id); }, 500);
                }, 1000);
              }, 50);
            } else {
              removePluginLoadingProgress(id);
            }
          }).catch(function () {
            removePluginLoadingProgress(id);
          });
      } else {
        unloadPlugin(id);
        removePluginLoadingProgress(id);
      }
      refreshList();
      // 提示
      if (typeof global.showToast === 'function') showToast(enable ? '插件已启用' : '插件已禁用');
      else if (global.KeepApp && global.KeepApp.showToast) global.KeepApp.showToast(enable ? '插件已启用' : '插件已禁用');
    }).catch(function () {
      removePluginLoadingProgress(id);
    });
  };

  // ★ v2.2 新增：插件加载进度条
  //   在插件卡片下方显示一个带百分比的进度条
  //   showPluginLoadingProgress(id) → 返回进度条元素
  //   updatePluginLoadingProgress(id, percent, label)
  //   removePluginLoadingProgress(id)
  //   ★ v2.2.2 新增：使用 localStorage 持久化进度状态
  //     - 关闭面板再打开时，未完成的进度条会恢复显示
  //     - 进度达 100% 或被移除时，自动清除持久化
  var _progressBars = {};  // { id: { el, label, bar } }
  var _PROGRESS_STORAGE_KEY = 'mr_plugin_loading_progress';

  // ★ 持久化保存当前所有进行中的进度条状态
  function _persistProgress() {
    try {
      var toSave = {};
      for (var id in _progressBars) {
        var p = _progressBars[id];
        if (p && p.el && p.el.parentNode) {
          // 仅保存 DOM 中仍存在的（未完成）进度条
          toSave[id] = {
            percent: parseInt(p.pct.textContent, 10) || 0,
            label: p.label ? (p.label.textContent || '') : ''
          };
        }
      }
      localStorage.setItem(_PROGRESS_STORAGE_KEY, JSON.stringify(toSave));
    } catch (e) {}
  }

  // ★ 从 localStorage 恢复未完成的进度条
  function _restorePersistedProgress() {
    try {
      var raw = localStorage.getItem(_PROGRESS_STORAGE_KEY);
      if (!raw) return;
      var saved = JSON.parse(raw);
      if (!saved || typeof saved !== 'object') return;
      for (var id in saved) {
        var item = saved[id];
        if (!item || item.percent >= 100) continue;  // 已完成的不恢复
        // 仅当该插件卡片存在时才恢复
        var node = document.querySelector('.plugin-item[data-id="' + cssEscape(id) + '"]');
        if (!node) continue;
        // 如果已经有进度条了，跳过
        if (_progressBars[id] && _progressBars[id].el && _progressBars[id].el.parentNode) continue;
        // 重建进度条
        var wrap = document.createElement('div');
        wrap.className = 'plugin-loading-progress';
        wrap.style.cssText = 'padding:6px 12px;background:rgba(0,245,212,0.08);border-top:1px solid rgba(0,245,212,0.2);font-size:11px;color:#00f5d4';
        var pct = Math.max(0, Math.min(100, item.percent | 0));
        wrap.innerHTML =
          '<div class="plp-label" style="margin-bottom:4px;display:flex;justify-content:space-between">' +
          '  <span class="plp-text">' + escapeHtml(item.label || '加载中...') + '</span>' +
          '  <span class="plp-pct">' + pct + '%</span>' +
          '</div>' +
          '<div class="plp-track" style="height:3px;background:rgba(255,255,255,0.1);border-radius:2px;overflow:hidden">' +
          '  <div class="plp-fill" style="width:' + pct + '%;height:100%;background:linear-gradient(90deg,#00f5d4,#00b4d8);transition:width 0.3s ease"></div>' +
          '</div>';
        node.appendChild(wrap);
        _progressBars[id] = {
          el: wrap,
          label: wrap.querySelector('.plp-text'),
          pct: wrap.querySelector('.plp-pct'),
          fill: wrap.querySelector('.plp-fill')
        };
      }
    } catch (e) { err('_restorePersistedProgress 异常:', e); }
  }

  // ★ 清除指定插件的持久化进度
  function _clearPersistedProgress(pluginId) {
    try {
      var raw = localStorage.getItem(_PROGRESS_STORAGE_KEY);
      if (!raw) return;
      var saved = JSON.parse(raw);
      if (!saved || typeof saved !== 'object') return;
      if (saved[pluginId]) {
        delete saved[pluginId];
        localStorage.setItem(_PROGRESS_STORAGE_KEY, JSON.stringify(saved));
      }
    } catch (e) {}
  }

  function showPluginLoadingProgress(pluginId) {
    try {
      removePluginLoadingProgress(pluginId);  // 先清理旧的
      var item = document.querySelector('.plugin-item[data-id="' + cssEscape(pluginId) + '"]');
      if (!item) return null;

      var wrap = document.createElement('div');
      wrap.className = 'plugin-loading-progress';
      wrap.style.cssText = 'padding:6px 12px;background:rgba(0,245,212,0.08);border-top:1px solid rgba(0,245,212,0.2);font-size:11px;color:#00f5d4';
      wrap.innerHTML =
        '<div class="plp-label" style="margin-bottom:4px;display:flex;justify-content:space-between">' +
        '  <span class="plp-text">准备加载...</span>' +
        '  <span class="plp-pct">0%</span>' +
        '</div>' +
        '<div class="plp-track" style="height:3px;background:rgba(255,255,255,0.1);border-radius:2px;overflow:hidden">' +
        '  <div class="plp-fill" style="width:0%;height:100%;background:linear-gradient(90deg,#00f5d4,#00b4d8);transition:width 0.3s ease"></div>' +
        '</div>';

      item.appendChild(wrap);
      _progressBars[pluginId] = {
        el: wrap,
        label: wrap.querySelector('.plp-text'),
        pct: wrap.querySelector('.plp-pct'),
        fill: wrap.querySelector('.plp-fill')
      };

      // 初始动画
      setTimeout(function () { updatePluginLoadingProgress(pluginId, 10, '准备中...'); }, 30);
      return wrap;
    } catch (e) { err('showPluginLoadingProgress 异常:', e); return null; }
  }

  function updatePluginLoadingProgress(pluginId, percent, label) {
    try {
      var p = _progressBars[pluginId];
      if (!p || !p.el) return;
      percent = Math.max(0, Math.min(100, percent | 0));
      p.fill.style.width = percent + '%';
      p.pct.textContent = percent + '%';
      if (label) p.label.textContent = label;
      // ★ v2.2.2 持久化进度状态
      _persistProgress();
      // 完成后清除持久化（延迟，让用户看到 100%）
      if (percent >= 100) {
        setTimeout(function () { _clearPersistedProgress(pluginId); }, 1500);
      }
    } catch (e) {}
  }

  function removePluginLoadingProgress(pluginId) {
    try {
      var p = _progressBars[pluginId];
      if (!p) return;
      if (p.el && p.el.parentNode) p.el.parentNode.removeChild(p.el);
      delete _progressBars[pluginId];
      // ★ v2.2.2 清除持久化
      _clearPersistedProgress(pluginId);
      _persistProgress();
    } catch (e) {}
  }

  // ── 卸载 ────────────────────────────────────────
  // ★ 新交互：点击"卸载"按钮不弹全屏面板，而是在卡片下方弹出绿色"确认卸载"按钮条
  //   - 点击绿色"确认卸载"才真正执行卸载
  //   - 点击其他地方/取消按钮/4秒后自动收起
  function closeAllUninstConfirmBars() {
    document.querySelectorAll('.plugin-uninst-confirm-bar').forEach(function (el) {
      if (el.parentNode) el.parentNode.removeChild(el);
    });
  }
  global.uninstallPlugin = function (id, btnEl) {
    // 先关闭其他已展开的确认条
    closeAllUninstConfirmBars();
    var card = btnEl ? (btnEl.closest ? btnEl.closest('.plugin-item') : null) : null;
    if (!card || !card.parentNode) {
      // 兜底：找不到卡片时退回原逻辑（直接卸载）
      doUninstallPlugin(id);
      return;
    }
    // 如果该卡片下方已有确认条 → 切换为收起
    var next = card.nextSibling;
    if (next && next.classList && next.classList.contains('plugin-uninst-confirm-bar')) {
      next.parentNode.removeChild(next);
      return;
    }
    // 创建绿色确认条，插入到卡片后面
    var bar = document.createElement('div');
    bar.className = 'plugin-uninst-confirm-bar';
    bar.innerHTML = ''
      + '<span class="plg-uninst-text">确认卸载 <b style="color:#ef5350">' + escapeHtml(id) + '</b> ？</span>'
      + '<div class="plg-uninst-btns">'
      + '<button class="plg-uninst-cancel-btn">取消</button>'
      + '<button class="plg-uninst-confirm-btn">确认卸载</button>'
      + '</div>';
    card.parentNode.insertBefore(bar, card.nextSibling);

    var autoCloseTimer = setTimeout(close, 4000);
    function close() {
      if (autoCloseTimer) { clearTimeout(autoCloseTimer); autoCloseTimer = null; }
      if (bar.parentNode) bar.parentNode.removeChild(bar);
      document.removeEventListener('pointerdown', onDocClick, true);
    }
    function onDocClick(e) {
      // 点击确认条本身或原卡片 → 不关闭（让按钮自己处理）
      if (bar.contains(e.target) || card.contains(e.target)) return;
      close();
    }
    // 延迟一帧绑定，避免本次点击立即触发
    setTimeout(function () {
      document.addEventListener('pointerdown', onDocClick, true);
    }, 0);

    bar.querySelector('.plg-uninst-cancel-btn').onclick = close;
    bar.querySelector('.plg-uninst-confirm-btn').onclick = function () {
      close();
      doUninstallPlugin(id);
    };
  };
  // ★ 真正执行卸载（卸载插件 + 清理错误 + 通知后端 + 刷新列表）
  function doUninstallPlugin(id) {
    unloadPlugin(id);
    clearPluginError(id); // ★ 卸载时清除错误状态
    fetch('/api/plugins/uninstall?id=' + encodeURIComponent(id)).then(function () {
      refreshList();
      if (typeof global.showToast === 'function') showToast('插件已卸载');
      else if (global.KeepApp && global.KeepApp.showToast) global.KeepApp.showToast('插件已卸载');
    });
  }

  // ── 查看插件错误详情 ──────────────────────────────
  global.showPluginErrorDetail = function (id) {
    var errInfo = MR.errors[id];
    if (!errInfo) {
      if (typeof global.showToast === 'function') showToast('该插件无错误记录');
      else if (global.KeepApp && global.KeepApp.showToast) global.KeepApp.showToast('该插件无错误记录');
      return;
    }
    var stageLabel = { fetch: '网络加载', parse: '语法解析', execute: '代码执行' }[errInfo.stage] || errInfo.stage;
    var mask = document.createElement('div');
    mask.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.85);z-index:100001;display:flex;align-items:center;justify-content:center;';
    var box = document.createElement('div');
    box.style.cssText = 'background:#1a1f2e;color:#e6ecf5;border-radius:16px;padding:20px;max-width:520px;width:92%;max-height:80vh;overflow:auto;font-size:13px;box-shadow:0 8px 32px rgba(0,0,0,0.6);border:1px solid rgba(239,83,80,.3);';
    box.innerHTML = ''
      + '<div style="font-size:16px;font-weight:600;margin-bottom:14px;color:#ef5350;display:flex;align-items:center;gap:8px;">⚠ 插件错误详情</div>'
      + '<div style="margin-bottom:10px;"><b style="display:inline-block;width:80px;color:#8b95a8;">插件 ID:</b> <span style="color:#fff;">' + escapeHtml(id) + '</span></div>'
      + '<div style="margin-bottom:10px;"><b style="display:inline-block;width:80px;color:#8b95a8;">错误阶段:</b> <span style="color:#ffb300;">' + escapeHtml(stageLabel) + '</span></div>'
      + '<div style="margin-bottom:10px;"><b style="display:inline-block;width:80px;color:#8b95a8;">发生时间:</b> <span style="color:#c9d1d9;">' + escapeHtml(errInfo.time) + '</span></div>'
      + '<div style="margin-bottom:12px;"><b style="display:inline-block;width:80px;color:#8b95a8;">错误信息:</b> <span style="color:#ef5350;word-break:break-all;">' + escapeHtml(errInfo.message) + '</span></div>'
      + (errInfo.codePreview
        ? '<div style="margin-bottom:12px;"><b style="color:#8b95a8;display:block;margin-bottom:6px;">代码上下文:</b><pre style="background:#0d1117;padding:10px;border-radius:8px;overflow:auto;font-family:monospace;font-size:11px;color:#c9d1d9;margin:0;border:1px solid rgba(255,255,255,.06);">' + escapeHtml(errInfo.codePreview) + '</pre></div>'
        : '')
      + (errInfo.stack
        ? '<div style="margin-bottom:12px;"><b style="color:#8b95a8;display:block;margin-bottom:6px;">调用堆栈:</b><pre style="background:#0d1117;padding:10px;border-radius:8px;overflow:auto;font-family:monospace;font-size:10px;color:#8b949e;margin:0;max-height:180px;border:1px solid rgba(255,255,255,.06);">' + escapeHtml(errInfo.stack) + '</pre></div>'
        : '')
      + '<div style="display:flex;gap:10px;margin-top:18px;">'
      + '<button id="plg-err-close" style="flex:1;padding:10px;background:#2a3142;color:#e6ecf5;border:1px solid #3a4258;border-radius:10px;font-size:13px;cursor:pointer;">关闭</button>'
      + '<button id="plg-err-copy" style="flex:1;padding:10px;background:linear-gradient(135deg,#00F5D4,#00b8a3);color:#001b1a;border:none;border-radius:10px;font-size:13px;font-weight:600;cursor:pointer;">复制错误信息</button>'
      + '<button id="plg-err-retry" style="flex:1;padding:10px;background:linear-gradient(135deg,#7c4dff,#5a30d3);color:#fff;border:none;border-radius:10px;font-size:13px;font-weight:500;cursor:pointer;">重试加载</button>'
      + '</div>';
    mask.appendChild(box);
    document.body.appendChild(mask);
    function close() { try { document.body.removeChild(mask); } catch (e) {} }
    box.querySelector('#plg-err-close').onclick = close;
    mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
    box.querySelector('#plg-err-copy').onclick = function () {
      var text = 'Mineradio Plugin Error\n'
        + 'Plugin: ' + id + '\n'
        + 'Stage: ' + stageLabel + '\n'
        + 'Time: ' + errInfo.time + '\n'
        + 'Message: ' + errInfo.message + '\n\n'
        + (errInfo.codePreview ? 'Code Preview:\n' + errInfo.codePreview + '\n\n' : '')
        + (errInfo.stack ? 'Stack:\n' + errInfo.stack : '');
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text);
        } else {
          var ta = document.createElement('textarea');
          ta.value = text;
          document.body.appendChild(ta);
          ta.select();
          document.execCommand('copy');
          document.body.removeChild(ta);
        }
        if (typeof global.showToast === 'function') showToast('错误信息已复制');
      } catch (e) {
        if (typeof global.showToast === 'function') showToast('复制失败: ' + e.message);
      }
    };
    box.querySelector('#plg-err-retry').onclick = function () {
      close();
      unloadPlugin(id);
      clearPluginError(id);
      // 重新加载
      fetch('/api/plugins/list?_t=' + Date.now())
        .then(function (r) { return r.json(); })
        .then(function (data) {
          var p = (data.plugins || []).find(function (it) { return it.id === id; });
          if (p) {
            if (typeof global.showToast === 'function') showToast('正在重新加载...');
            loadPlugin(id, p);
          } else {
            if (typeof global.showToast === 'function') showToast('插件不存在');
          }
        });
    };
  };


  // ── 选择 zip 安装 ────────────────────────────────
  global.pickPluginZip = function () {
    if (!global.KeepApp || typeof global.KeepApp.pickPluginZip !== 'function') {
      if (typeof global.showToast === 'function') showToast('当前环境不支持安装插件');
      else if (global.KeepApp && global.KeepApp.showToast) global.KeepApp.showToast('当前环境不支持安装插件');
      return;
    }
    var callbackId = 'plg_' + Date.now() + '_' + Math.floor(Math.random() * 1e6);

    // ★ v2.2 新增：安装时立即显示进度卡片
    //   用户选择文件后，原生层会解密+解压+复制（大插件如 6.8MB 需要几秒）
    //   期间在面板顶部显示"正在安装..."进度卡片，避免用户以为卡死
    var installingCard = showInstallingCard();

    global._onPluginZipPicked = function (cbId, result) {
      if (cbId !== callbackId) return; // 不是本次回调,忽略

      if (result && result.ok) {
        // ★ 安装成功，更新进度卡片为"加载中"
        updateInstallingCard(installingCard, 70, '安装成功，正在加载插件...');

        var okMsg = '插件安装成功: ' + (result.name || result.pluginId);
        if (typeof global.showToast === 'function') showToast(okMsg);
        else if (global.KeepApp && global.KeepApp.showToast) global.KeepApp.showToast(okMsg);

        // 先刷新列表让新插件出现
        fetch('/api/plugins/list?_t=' + Date.now())
          .then(function (r) { return r.json(); })
          .then(function (data) {
            var plugins = (data && data.ok && data.plugins) ? data.plugins : [];
            renderList(plugins);
            // 同时更新入口副标题
            var sub = document.getElementById('plugin-entry-sub');
            if (sub) sub.textContent = plugins.length ? (plugins.length + ' 个已安装') : '点击安装插件';

            // 找到新安装的插件，在其卡片下方显示加载进度条
            if (result.pluginId) {
              var newPlugin = plugins.find(function (p) { return p.id === result.pluginId; });
              if (newPlugin) {
                // 在新插件卡片下方显示加载进度条
                showPluginLoadingProgress(result.pluginId);
                updatePluginLoadingProgress(result.pluginId, 80, '正在加载插件代码...');

                // 卸载旧版本（如果已加载）然后加载新版本
                unloadPlugin(result.pluginId);

                setTimeout(function () {
                  loadPlugin(result.pluginId, newPlugin);
                  updatePluginLoadingProgress(result.pluginId, 100, '加载完成');
                  // 1 秒后移除进度条
                  setTimeout(function () { removePluginLoadingProgress(result.pluginId); }, 1000);
                }, 100);
              }
            }
            // 移除安装中卡片
            removeInstallingCard(installingCard);
          }).catch(function () {
            removeInstallingCard(installingCard);
          });
      } else {
        // 安装失败
        removeInstallingCard(installingCard);
        // ★ 改用 Toast 替代网页 alert 弹窗
        var errMsg = '安装失败: ' + (result && result.message ? result.message : '未知错误');
        if (typeof global.showToast === 'function') showToast(errMsg);
        else if (global.KeepApp && global.KeepApp.showToast) global.KeepApp.showToast(errMsg);
      }
    };

    // ★ 模拟安装进度（因为原生安装过程是同步的，无法精确获取进度）
    //   使用动画式进度条，从 10% 到 60% 缓慢推进，给用户视觉反馈
    startFakeInstallProgress(installingCard);

    global.KeepApp.pickPluginZip(callbackId);
  };

  // ★ v2.2 新增：安装中卡片（显示在面板顶部）
  function showInstallingCard() {
    try {
      var container = document.getElementById('plugin-list-container');
      if (!container) return null;

      var card = document.createElement('div');
      card.className = 'plugin-item plugin-installing-card';
      card.style.cssText = 'background:rgba(0,245,212,0.06);border-color:rgba(0,245,212,0.3);animation:plgUninstSlideIn .25s cubic-bezier(0.16,1,0.3,1)';
      card.innerHTML =
        '<div class="plugin-item-info">' +
        '  <div class="plugin-item-name">正在安装插件... <span class="plg-install-pct" style="color:#00f5d4">0%</span></div>' +
        '  <div class="plg-install-track" style="height:3px;background:rgba(255,255,255,0.1);border-radius:2px;overflow:hidden;margin-top:6px">' +
        '    <div class="plg-install-fill" style="width:0%;height:100%;background:linear-gradient(90deg,#00f5d4,#00b4d8);transition:width 0.3s ease"></div>' +
        '  </div>' +
        '  <div class="plg-install-label" style="font-size:11px;color:rgba(255,255,255,.6);margin-top:4px">正在解密和复制文件...</div>' +
        '</div>';
      container.insertBefore(card, container.firstChild);
      return card;
    } catch (e) { err('showInstallingCard 异常:', e); return null; }
  }

  function updateInstallingCard(card, percent, label) {
    try {
      if (!card) return;
      var fill = card.querySelector('.plg-install-fill');
      var pct = card.querySelector('.plg-install-pct');
      var lab = card.querySelector('.plg-install-label');
      if (fill) fill.style.width = percent + '%';
      if (pct) pct.textContent = percent + '%';
      if (lab && label) lab.textContent = label;
    } catch (e) {}
  }

  function removeInstallingCard(card) {
    try {
      if (card && card.parentNode) card.parentNode.removeChild(card);
    } catch (e) {}
  }

  // ★ v2.2.7 模拟安装进度动画（10% → 95% 分阶段推进，避免卡在60%）
  //   阶段 1: 10% → 30%   正在解密文件...     (快速,200ms/次,+5~10%)
  //   阶段 2: 30% → 60%   正在复制资源...     (中速,200ms/次,+2~5%)
  //   阶段 3: 60% → 85%  正在加载模型资源...  (慢速,400ms/次,+0.5~1.5%)
  //   阶段 4: 85% → 95%  正在初始化插件...    (极慢,600ms/次,+0.1~0.5%)
  //   ★ 不再卡在60%，让用户看到进度持续增长
  //   ★ 大插件(6.8MB+93文件)实际安装可能需要 30~60 秒
  function startFakeInstallProgress(card) {
    if (!card) return;
    var pct = 10;
    var labelStage = 0;
    var labelCycle = 0;
    var timer = setInterval(function () {
      // 根据阶段调整推进速度
      if (pct < 30) {
        // 阶段 1: 快速推进 10→30
        pct += Math.random() * 8 + 2;
      } else if (pct < 60) {
        // 阶段 2: 中速推进 30→60
        pct += Math.random() * 4 + 1;
      } else if (pct < 85) {
        // 阶段 3: 慢速推进 60→85 (不再卡住)
        pct += Math.random() * 1.5 + 0.3;
      } else if (pct < 95) {
        // 阶段 4: 极慢推进 85→95 (等待原生回调)
        pct += Math.random() * 0.5 + 0.1;
      } else {
        // 到达 95% 后停止推进，等待原生回调
        pct = 95;
      }
      pct = Math.min(pct, 95);

      // 根据阶段切换标签文字（让用户感觉在做不同的事）
      if (pct < 30) {
        updateInstallingCard(card, Math.floor(pct), '正在解密文件...');
      } else if (pct < 60) {
        // 循环显示不同资源复制阶段，避免一直显示同一句话
        var copyLabels = [
          '正在复制资源...',
          '正在复制 Live2D SDK...',
          '正在复制模型文件...',
          '正在复制贴图资源...',
          '正在写入配置文件...'
        ];
        updateInstallingCard(card, Math.floor(pct), copyLabels[labelCycle % copyLabels.length]);
        labelCycle++;
      } else if (pct < 85) {
        var loadLabels = [
          '正在加载模型资源...',
          '正在解析模型配置...',
          '正在初始化 Live2D 引擎...',
          '正在预处理贴图...'
        ];
        updateInstallingCard(card, Math.floor(pct), loadLabels[labelCycle % loadLabels.length]);
        labelCycle++;
      } else {
        updateInstallingCard(card, Math.floor(pct), '正在初始化插件...');
      }
    }, 300);
    // 把 timer 挂到 card 上，方便清理
    card._fakeTimer = timer;
    // 120 秒后自动停止（大插件需要更多时间）
    setTimeout(function () {
      if (timer) clearInterval(timer);
    }, 120000);
  }

  // ── 注入面板样式 ─────────────────────────────────
  function injectStyles() {
    var css = [
      '.plugin-empty{padding:20px;text-align:center;color:rgba(255,255,255,.45);font-size:12px}',
      '.plugin-item{display:flex;align-items:center;gap:10px;padding:12px;border-radius:10px;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.08);margin-bottom:8px;transition:background .2s ease,border-color .2s ease}',
      '.plugin-item:hover{background:rgba(255,255,255,.06)}',
      '.plugin-item-error{background:rgba(239,83,80,.08)!important;border-color:rgba(239,83,80,.35)!important}',
      '.plugin-item-error:hover{background:rgba(239,83,80,.12)!important}',
      '.plugin-item-info{flex:1;min-width:0}',
      '.plugin-item-name{font-size:13px;font-weight:600;color:#fff;margin-bottom:3px;display:flex;align-items:center;flex-wrap:wrap;gap:6px}',
      '.plugin-item-ver{font-size:10px;color:rgba(124,77,255,.85);font-weight:400}',
      '.plugin-item-desc{font-size:11px;color:rgba(255,255,255,.6);margin-bottom:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;cursor:pointer;transition:background .25s cubic-bezier(0.16,1,0.3,1),color .25s ease,padding .25s ease,border-radius .25s ease;padding:2px 4px;margin:0 -4px 2px;border-radius:4px}',
      '.plugin-item-desc:hover{color:rgba(255,255,255,.88);background:rgba(124,77,255,.10)}',
      '.plugin-item-desc.expanded{white-space:normal;overflow:visible;text-overflow:clip;background:rgba(124,77,255,.16);color:rgba(255,255,255,.96);padding:8px 10px;border-radius:8px;box-shadow:0 2px 12px rgba(124,77,255,.20);line-height:1.5}',
      '.plugin-item-meta{font-size:10px;color:rgba(255,255,255,.35);font-family:monospace}',
      '.plugin-item-actions{display:flex;gap:6px;flex-shrink:0;align-items:center;flex-wrap:wrap}',
      /* ★ v2.2.3 优化插件按钮外观：更精致的圆角、渐变和动效 */
      '.plugin-item-actions .fx-mini-btn{height:30px;padding:0 14px;border-radius:10px;border:1px solid rgba(255,255,255,.10);background:rgba(255,255,255,.045);color:rgba(255,255,255,.78);font-size:11.5px;font-weight:500;letter-spacing:.3px;cursor:pointer;transition:all .22s cubic-bezier(.16,1,.3,1);backdrop-filter:blur(12px) saturate(1.2);-webkit-backdrop-filter:blur(12px) saturate(1.2);box-shadow:inset 0 1px 0 rgba(255,255,255,.06),0 2px 8px rgba(0,0,0,.10)}',
      '.plugin-item-actions .fx-mini-btn:hover{transform:translateY(-1px);box-shadow:inset 0 1px 0 rgba(255,255,255,.10),0 6px 16px rgba(0,0,0,.16)}',
      '.plugin-item-actions .fx-mini-btn:active{transform:translateY(0) scale(.97)}',
      /* 已启用按钮：青绿色渐变，发光效果 */
      '.plugin-item-actions .fx-mini-btn.primary{border:1px solid rgba(0,245,212,.42);background:linear-gradient(135deg,rgba(0,245,212,.16),rgba(0,180,216,.10));color:#00f5d4;box-shadow:inset 0 1px 0 rgba(255,255,255,.10),0 0 14px rgba(0,245,212,.18),0 4px 12px rgba(0,0,0,.14)}',
      '.plugin-item-actions .fx-mini-btn.primary:hover{border-color:rgba(0,245,212,.62);background:linear-gradient(135deg,rgba(0,245,212,.24),rgba(0,180,216,.16));box-shadow:inset 0 1px 0 rgba(255,255,255,.14),0 0 22px rgba(0,245,212,.32),0 6px 18px rgba(0,0,0,.18);color:#fff}',
      /* ghost（已禁用）按钮：透明玻璃质感 */
      '.plugin-item-actions .fx-mini-btn.ghost{border:1px solid rgba(255,255,255,.10);background:rgba(255,255,255,.025);color:rgba(255,255,255,.62)}',
      '.plugin-item-actions .fx-mini-btn.ghost:hover{border-color:rgba(255,255,255,.22);background:rgba(255,255,255,.06);color:#fff;box-shadow:inset 0 1px 0 rgba(255,255,255,.08),0 4px 12px rgba(0,0,0,.12)}',
      /* 卸载按钮（红色 ghost）：危险操作的视觉提示 */
      '.plugin-item-actions .fx-mini-btn.ghost[style*="ef5350"],.plugin-item-actions .fx-mini-btn.danger{border:1px solid rgba(239,83,80,.32);background:rgba(239,83,80,.06);color:rgba(239,83,80,.86);box-shadow:inset 0 1px 0 rgba(255,255,255,.04),0 2px 8px rgba(239,83,80,.08)}',
      '.plugin-item-actions .fx-mini-btn.ghost[style*="ef5350"]:hover,.plugin-item-actions .fx-mini-btn.danger:hover{border-color:rgba(239,83,80,.58);background:rgba(239,83,80,.14);color:#fff;box-shadow:inset 0 1px 0 rgba(255,255,255,.08),0 0 16px rgba(239,83,80,.28),0 4px 12px rgba(0,0,0,.14)}',
      '.plugin-err-flag{display:inline-block;padding:1px 6px;border-radius:4px;background:rgba(239,83,80,.18);color:#ef5350;font-size:10px;font-weight:500;border:1px solid rgba(239,83,80,.35)}',
      '.plugin-error-badge{padding:5px 10px;border-radius:6px;background:linear-gradient(135deg,#ef5350,#c62828);color:#fff;border:none;font-size:10.5px;font-weight:500;cursor:pointer;letter-spacing:.2px;box-shadow:0 2px 8px rgba(239,83,80,.3);transition:transform .15s ease}',
      '.plugin-error-badge:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(239,83,80,.4)}',
      '#plugin-manager-modal{display:none}',
      '#plugin-manager-modal.show{display:flex!important;opacity:1!important;visibility:visible!important;z-index:9999!important}',
      // ★ 卸载确认条（绿色，展开在插件卡片下方）
      '.plugin-uninst-confirm-bar{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:10px 12px;margin:-4px 0 8px;border-radius:10px;background:rgba(76,175,80,.12);border:1px solid rgba(76,175,80,.45);animation:plgUninstSlideIn .25s cubic-bezier(0.16,1,0.3,1)}',
      '@keyframes plgUninstSlideIn{from{opacity:0;transform:translateY(-6px) scale(.98)}to{opacity:1;transform:translateY(0) scale(1)}}',
      '.plg-uninst-text{font-size:12px;color:rgba(255,255,255,.88);flex:1;min-width:0}',
      '.plg-uninst-btns{display:flex;gap:8px;flex-shrink:0}',
      '.plg-uninst-cancel-btn{padding:7px 14px;border-radius:8px;background:rgba(255,255,255,.08);color:rgba(255,255,255,.7);border:1px solid rgba(255,255,255,.15);font-size:11.5px;cursor:pointer;transition:background .2s ease}',
      '.plg-uninst-cancel-btn:hover{background:rgba(255,255,255,.14)}',
      '.plg-uninst-confirm-btn{padding:7px 16px;border-radius:8px;background:linear-gradient(135deg,#4caf50,#2e7d32);color:#fff;border:none;font-size:11.5px;font-weight:600;cursor:pointer;box-shadow:0 2px 10px rgba(76,175,80,.4);transition:box-shadow .2s ease,transform .15s ease}',
      '.plg-uninst-confirm-btn:hover{box-shadow:0 4px 14px rgba(76,175,80,.6);transform:translateY(-1px)}',
      '.plg-uninst-confirm-btn:active{transform:translateY(0)}'
    ].join('\n');
    var style = document.createElement('style');
    style.setAttribute('data-mr-plugin-runtime', 'true');
    style.textContent = css;
    (document.head || document.documentElement).appendChild(style);
  }

  // ── 公共 API（供外部 / 原生调用） ────────────────
  MR.loadPlugin = loadPlugin;
  MR.unloadPlugin = unloadPlugin;
  MR.refreshList = refreshList;
  // ★ 提供 register 方法,供 main.js 调用 (兼容静态插件写法)
  //    main.js 形如: (function(MR){ MR.register({...}, function(api){...}); })(window.MineradioPlugins);
  //    register 会暂存 factory,loadPlugin 执行后会检测并调用
  MR._pendingFactory = null;
  MR.register = function (meta, factory) {
    if (!meta || !meta.id) { err('register 失败: 缺少 id'); return; }
    MR._pendingFactory = { meta: meta, factory: factory };
    MR.registry[meta.id] = { id: meta.id, name: meta.name || meta.id, version: meta.version || '1.0.0', loaded: true };
    log('register:', meta.id, 'v' + (meta.version || '1.0.0'));
  };
  global.MR_listPlugins = function () {
    return Object.keys(MR.loaded).map(function (id) { return { id: id, loaded: true }; });
  };

  // ── 启动 ──────────────────────────────────────────
  function start() {
    injectStyles();
    log('插件运行时就绪');
    // 延迟一帧确保主程序结构已就绪
    setTimeout(bootstrap, 0);
  }

  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    start();
  } else {
    document.addEventListener('DOMContentLoaded', start);
  }

  global.MineradioPlugins = MR;

  // ── MR.auth: 可扩展登录平台 SDK ──────────────────
  // 插件通过 MR.auth.registerProvider() 注册新平台
  // 以后添加新平台只需更新插件zip,APK无需修改
  var _authProviders = {};
  var _authStatus = {};
  var _authEventHandlers = {};

  function authEmit(event, data) {
    var handlers = _authEventHandlers[event] || [];
    for (var i = 0; i < handlers.length; i++) {
      try { handlers[i](data); } catch(e) { console.warn('[MR.auth] event handler error:', e); }
    }
    if (event === 'providerRegistered' || event === 'providerUnregistered') {
      setTimeout(function() {
        try { MR.auth.ui.rebuildSearchTabs(); } catch(e) {}
        try { MR.auth.ui.updateUserModal(); } catch(e) {}
      }, 10);
    }
    if (event === 'login' || event === 'logout' || event === 'statusChanged') {
      setTimeout(function() {
        try { MR.auth.ui.updateUserModal(); } catch(e) {}
      }, 10);
    }
  }

  MR.auth = {
    utils: {
      normalizeSong: function(song, providerId) {
        if (!song) return null;
        var s = Object.assign({}, song);
        s.provider = s.provider || s.source || providerId;
        s.source = s.source || s.provider;
        s.id = s.id || s.songId || s.trackId || s.sid || '';
        s.name = s.name || s.title || s.songName || '未知歌曲';
        s.artist = s.artist || s.singer || s.arName || (s.ar && s.ar[0] && s.ar[0].name) || '未知歌手';
        s.album = s.album || s.alName || (s.al && s.al.name) || '';
        s.cover = s.cover || s.picUrl || s.alPicUrl || (s.al && s.al.picUrl) || '';
        s.duration = s.duration || s.dt || s.length || 0;
        if (typeof s.duration === 'number' && s.duration > 10000) s.duration = s.duration / 1000;
        s.vip = !!s.vip || !!s.fee || s.privilege && s.privilege.fee > 0;
        return s;
      },
      normalizePlaylist: function(pl, providerId) {
        if (!pl) return null;
        var p = Object.assign({}, pl);
        p.provider = p.provider || p.source || providerId;
        p.source = p.source || p.provider;
        p.id = p.id || p.playlistId || p.pid || '';
        p.name = p.name || p.title || '歌单';
        p.cover = p.cover || p.coverImgUrl || p.picUrl || p.imgUrl || '';
        p.trackCount = p.trackCount || p.trackCount || p.songCount || 0;
        p.creator = p.creator || p.nickname || '';
        return p;
      },
      audioProxy: function(url) {
        if (!url) return '';
        if (url.indexOf('/api/audio') === 0 || url.indexOf('/qs-cache/') === 0) return url;
        return '/api/audio?url=' + encodeURIComponent(url);
      },
      coverProxy: function(url) {
        if (!url) return '';
        if (url.indexOf('/api/cover') === 0) return url;
        return '/api/cover?url=' + encodeURIComponent(url);
      }
    },

    registerProvider: function(config) {
      if (!config || !config.id) { console.warn('[MR.auth] registerProvider: missing id'); return false; }
      var id = config.id;
      var existing = _authProviders[id];
      _authProviders[id] = Object.assign({
        id: id,
        name: config.name || id,
        shortName: config.shortName || id.substring(0, 2).toUpperCase(),
        color: config.color || '#00f5d4',
        dotColor: config.dotColor || config.color || '#00f5d4',
        loginMethods: config.loginMethods || ['qr'],
        qrApiPath: config.qrApiPath || ('/api/' + id + '/login/qr'),
        webLoginUrl: config.webLoginUrl || null,
        checkLoginPath: config.checkLoginPath || ('/api/' + id + '/login/status'),
        logoutPath: config.logoutPath || ('/api/' + id + '/logout'),
        searchPath: config.searchPath || ('/api/' + id + '/search'),
        songUrlPath: config.songUrlPath || ('/api/' + id + '/song/url'),
        lyricPath: config.lyricPath || ('/api/' + id + '/lyric'),
        playlistsPath: config.playlistsPath || ('/api/' + id + '/user/playlists'),
        playlistTracksPath: config.playlistTracksPath || ('/api/' + id + '/playlist/tracks'),
        searchSongs: config.searchSongs || null,
        getSongUrl: config.getSongUrl || null,
        getLyric: config.getLyric || null,
        getUserPlaylists: config.getUserPlaylists || null,
        getPlaylistTracks: config.getPlaylistTracks || null,
        checkLogin: config.checkLogin || null,
        doLogout: config.doLogout || null,
        startLogin: config.startLogin || null,
        startQrLogin: config.startQrLogin || null,
        renderQrCode: config.renderQrCode || null,
        pollQrLogin: config.pollQrLogin || null,
        songSourceLabel: config.songSourceLabel || null,
        songProviderKey: config.songProviderKey || null,
        mapSearchSong: config.mapSearchSong || null,
        supportsPlayback: config.supportsPlayback !== false,
        builtin: !!config.builtin
      }, config);
      if (!_authStatus[id]) _authStatus[id] = { provider: id, loggedIn: false, nickname: '', avatar: '', vipType: 0, isVip: false, vipLabel: '' };
      if (global.__authOnProviderRegistered) {
        try { global.__authOnProviderRegistered(_authProviders[id]); } catch(e) {}
      }
      authEmit('providerRegistered', _authProviders[id]);
      log('auth provider registered:', id, existing ? '(updated)' : '(new)');
      return true;
    },

    unregisterProvider: function(id) {
      if (!id || !_authProviders[id]) return;
      var provider = _authProviders[id];
      delete _authProviders[id];
      _authStatus[id] = { provider: id, loggedIn: false };
      if (global.__authOnProviderUnregistered) {
        try { global.__authOnProviderUnregistered(provider); } catch(e) {}
      }
      authEmit('providerUnregistered', provider);
      log('auth provider unregistered:', id);
    },

    getProviders: function() {
      return Object.keys(_authProviders).map(function(id) { return _authProviders[id]; });
    },

    getProvider: function(id) { return _authProviders[id] || null; },

    getStatus: function(id) { return _authStatus[id] || { provider: id, loggedIn: false }; },

    setStatus: function(id, status) {
      if (!id) return;
      var prev = _authStatus[id] || { provider: id, loggedIn: false };
      _authStatus[id] = Object.assign({ provider: id }, prev, status || {});
      var wasLoggedIn = !!prev.loggedIn;
      var isLoggedIn = !!_authStatus[id].loggedIn;
      if (isLoggedIn !== wasLoggedIn) {
        authEmit(isLoggedIn ? 'login' : 'logout', { provider: id, status: _authStatus[id] });
        if (global.__authOnStatusChanged) {
          try { global.__authOnStatusChanged(id, _authStatus[id], isLoggedIn); } catch(e) {}
        }
      }
      authEmit('statusChanged', { provider: id, status: _authStatus[id] });
    },

    http: {
      get: function(url, opts) {
        opts = opts || {};
        return fetch(url, {
          method: 'GET',
          headers: Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {})
        }).then(function(r) { return opts.raw ? r : r.json(); });
      },
      post: function(url, body, opts) {
        opts = opts || {};
        return fetch(url, {
          method: 'POST',
          headers: Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {}),
          body: body ? (typeof body === 'string' ? body : JSON.stringify(body)) : undefined
        }).then(function(r) { return opts.raw ? r : r.json(); });
      },
      proxy: function(targetUrl, opts) {
        opts = opts || {};
        var method = (opts.method || 'GET').toUpperCase();
        var proxyUrl = '/api/proxy?url=' + encodeURIComponent(targetUrl) + '&method=' + method;
        if (opts.headers) {
          try {
            proxyUrl += '&headers=' + encodeURIComponent(JSON.stringify(opts.headers));
          } catch(e) {}
        }
        var fetchOpts = { method: method, headers: {} };
        if (method !== 'GET' && opts.body) {
          fetchOpts.body = typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body);
          fetchOpts.headers['Content-Type'] = opts.contentType || 'application/json';
        }
        return fetch(proxyUrl, fetchOpts).then(function(r) {
          return r.json().then(function(data) {
            if (opts.rawResponse) return data;
            if (data.body) {
              try {
                var parsed = JSON.parse(data.body);
                return parsed;
              } catch(e) {
                return data;
              }
            }
            return data;
          });
        });
      }
    },

    openExternalUrl: function(url) {
      if (typeof KeepApp !== 'undefined' && KeepApp && KeepApp.openExternalUrl) {
        try { KeepApp.openExternalUrl(url); return; } catch(e) {}
      }
      if (typeof MR.api !== 'undefined' && MR.api.openUrl) {
        try { MR.api.openUrl(url); return; } catch(e) {}
      }
      window.open(url, '_blank');
    },

    showToast: function(msg) {
      if (typeof showToast === 'function') { showToast(msg); return; }
      if (typeof MR.api !== 'undefined' && MR.api.toast) { MR.api.toast(msg); return; }
      console.log('[toast]', msg);
    },

    on: function(event, handler) {
      if (!_authEventHandlers[event]) _authEventHandlers[event] = [];
      _authEventHandlers[event].push(handler);
    },

    off: function(event, handler) {
      var handlers = _authEventHandlers[event];
      if (!handlers) return;
      var idx = handlers.indexOf(handler);
      if (idx >= 0) handlers.splice(idx, 1);
    },

    ui: {
      rebuildSearchTabs: function() {
        var container = document.getElementById('search-mode-providers');
        if (!container) return;
        container.innerHTML = '';
        var providers = MR.auth.getProviders();
        for (var i = 0; i < providers.length; i++) {
          var p = providers[i];
          var btn = document.createElement('button');
          btn.id = 'search-mode-' + p.id;
          btn.type = 'button';
          btn.setAttribute('data-provider', p.id);
          btn.textContent = p.shortName || p.name.substring(0, 2).toUpperCase();
          btn.setAttribute('aria-selected', 'false');
          btn.onclick = (function(pid) { return function() { setSearchMode(pid); }; })(p.id);
          container.appendChild(btn);
        }
        if (typeof updateSearchModeUI === 'function') {
          try { updateSearchModeUI(); } catch(e) {}
        }
      },

      rebuildUserTabs: function() {
        var tabsContainer = document.getElementById('user-platform-tabs');
        var addContainer = document.getElementById('account-add-buttons');
        if (!tabsContainer || !addContainer) return;
        tabsContainer.innerHTML = '';
        addContainer.innerHTML = '';
        var providers = MR.auth.getProviders();
        var firstLoggedIn = null;
        var anyLoggedIn = false;

        for (var i = 0; i < providers.length; i++) {
          var p = providers[i];
          var status = MR.auth.getStatus(p.id);
          if (status.loggedIn) {
            anyLoggedIn = true;
            if (!firstLoggedIn) firstLoggedIn = p.id;
            var tabBtn = document.createElement('button');
            tabBtn.id = 'user-provider-' + p.id;
            tabBtn.type = 'button';
            tabBtn.className = p.dotColor || p.id;
            tabBtn.setAttribute('data-provider', p.id);
            tabBtn.textContent = p.name;
            tabBtn.onclick = (function(pid) { return function() { setActiveAccountProvider(pid); }; })(p.id);
            tabsContainer.appendChild(tabBtn);
          }
        }

        if (providers.length > 1 && anyLoggedIn) {
          var bothBtn = document.createElement('button');
          bothBtn.id = 'user-provider-both';
          bothBtn.type = 'button';
          bothBtn.className = 'both';
          bothBtn.textContent = '多平台';
          bothBtn.onclick = function() { enableDualAccountView(); };
          tabsContainer.appendChild(bothBtn);
        }

        for (var j = 0; j < providers.length; j++) {
          var p2 = providers[j];
          var addBtn = document.createElement('button');
          addBtn.id = 'account-add-' + p2.id;
          addBtn.className = 'modal-btn';
          addBtn.setAttribute('data-provider', p2.id);
          addBtn.textContent = p2.name;
          addBtn.onclick = (function(pid) { return function() { openProviderLogin(pid); }; })(p2.id);
          addContainer.appendChild(addBtn);
        }

        if (anyLoggedIn && firstLoggedIn) {
          setTimeout(function() {
            try { setActiveAccountProvider(firstLoggedIn); } catch(e) {}
          }, 50);
        }
      },

      updateProviderChip: function(providerId) {
        var chip = document.getElementById('account-provider-chip');
        var dot = chip ? chip.querySelector('.account-source-dot') : null;
        var span = chip ? chip.querySelector('span:last-child') : null;
        var p = MR.auth.getProvider(providerId);
        if (!chip || !p) {
          if (chip) chip.style.display = 'none';
          return;
        }
        chip.style.display = '';
        chip.className = 'account-provider-chip ' + (p.dotColor || p.id);
        if (dot) dot.className = 'account-source-dot ' + (p.dotColor || p.id);
        if (span) span.textContent = p.name;
      },

      updateUserModal: function() {
        var providers = MR.auth.getProviders();
        var anyLoggedIn = false;
        for (var i = 0; i < providers.length; i++) {
          if (MR.auth.getStatus(providers[i].id).loggedIn) { anyLoggedIn = true; break; }
        }
        var logoutBtn = document.getElementById('account-logout-btn');
        var cancelBtn = document.getElementById('account-logout-cancel');
        if (logoutBtn) {
          logoutBtn.style.display = anyLoggedIn ? '' : 'none';
        }
        if (cancelBtn) {
          cancelBtn.textContent = anyLoggedIn ? '取消' : '关闭';
        }
        var plHint = document.getElementById('pl-pane-hint');
        if (plHint) {
          plHint.textContent = anyLoggedIn ? '我的歌单' : '登录后显示歌单';
        }
        MR.auth.ui.rebuildUserTabs();
      }
    }
  };

  // ── 登录面板插件状态轮询检测 ──────────────────────
  // 每5秒检查login-panel插件是否仍然启用,直接删除zip文件也能立即生效
  var _loginPluginPollTimer = null;
  function pollLoginPluginStatus() {
    var wasEnabled = !!global.__loginPanelEnabled;
    fetch('/api/plugins/list').then(function(r) { return r.json(); }).then(function(data) {
      var list = (data && data.plugins) ? data.plugins : (Array.isArray(data) ? data : []);
      var enabled = false;
      for (var i = 0; i < list.length; i++) {
        var p = list[i];
        var id = p.id || p.pluginId || '';
        var en = p.enabled !== false && p.status !== 'disabled';
        if (id === 'login-panel' && en) { enabled = true; break; }
      }
      if (!enabled && wasEnabled) {
        if (MR.loaded['login-panel']) {
          unloadPlugin('login-panel');
        } else if (typeof global.__disableLoginPanel === 'function') {
          try { global.__disableLoginPanel(); } catch(e) {}
        }
      } else if (enabled && !wasEnabled && !MR.loaded['login-panel']) {
        loadPlugin('login-panel').catch(function() {});
      }
    }).catch(function() {});
  }
  function startLoginPluginPolling() {
    if (_loginPluginPollTimer) return;
    _loginPluginPollTimer = setInterval(pollLoginPluginStatus, 5000);
  }
  setTimeout(startLoginPluginPolling, 3000);

  // ── API 403拦截: 收到login_panel_required自动禁用 ─
  var _origFetch = global.fetch;
  if (_origFetch) {
    global.fetch = function(url, opts) {
      return _origFetch.apply(this, arguments).then(function(resp) {
        if (resp && resp.status === 403) {
          resp.clone().json().then(function(data) {
            if (data && data.error === 'login_panel_required') {
              if (MR.loaded['login-panel']) {
                unloadPlugin('login-panel');
              } else if (typeof global.__disableLoginPanel === 'function') {
                try { global.__disableLoginPanel(); } catch(e) {}
              }
            }
          }).catch(function(){});
        }
        return resp;
      });
    };
  }
})(window);
