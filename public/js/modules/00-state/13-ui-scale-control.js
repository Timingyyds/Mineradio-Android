// 界面整体比例滑块（常用 → 视觉预设 下方）
//
// 这个 App 的整套排版是按「布局宽度 = 屏宽 / 比例」再等比缩到铺满屏幕来做的，
// 默认值在 index.html 的启动脚本里按屏幕尺寸判断：手机 0.40、平板 0.80（短边 ≥600 CSS px）。
// 不同手机、不同视力的用户会觉得偏小/偏大，所以这里给一个滑块，实时改这个比例：
//   · 拖动时立刻调用 window.__mrApplyUiScale()，重算 viewport 并重排；
//   · 选中的值写进 localStorage（mineradio-ui-scale-v1），下次启动直接生效；
//   · 右侧 output 显示百分比（例如 30%）。
(function bindUiScaleSlider() {
  var KEY = 'mineradio-ui-scale-v1';
  var bound = false;

  function formatPercent(value) {
    return Math.round(Number(value) * 100) + '%';
  }

  function outputOf(input) {
    var row = input && input.parentNode;
    return row ? row.querySelector('output') : null;
  }

  function syncFromState() {
    var input = document.getElementById('fx-uiscale');
    if (!input) return;
    var current = (typeof window.__mrUiScale === 'number') ? window.__mrUiScale : Number(input.value);
    if (!(current > 0)) current = 0.40;
    input.value = String(current);
    var out = outputOf(input);
    if (out) out.textContent = formatPercent(current);
  }

  function bind() {
    var input = document.getElementById('fx-uiscale');
    if (!input || bound) return;
    bound = true;
    input.addEventListener('input', function () {
      var next = Number(input.value);
      if (typeof window.__mrApplyUiScale === 'function') {
        next = Number(window.__mrApplyUiScale(next)) || next;
      }
      var out = outputOf(input);
      if (out) out.textContent = formatPercent(next);
      try { window.localStorage.setItem(KEY, String(next)); } catch (_error) { /* 忽略 */ }
    });
    input.addEventListener('change', syncFromState);
    syncFromState();
    console.log('[UiScale] 滑块就绪，当前 ' + formatPercent(input.value));
  }

  function installScaleControl() {
    if (typeof window.__mrApplyUiScale !== 'function') return;
    var probe = document.getElementById('fx-uiscale');
    if (!probe) return;
    bind();
    // 让控制台里的「界面比例」那一行也能直接看到当前值
    var row = document.getElementById('fx-uiscale-row');
    if (row && row.getAttribute('data-bound') !== '1') {
      row.setAttribute('data-bound', '1');
    }
  }

  if (document.readyState !== 'loading') installScaleControl();
  else document.addEventListener('DOMContentLoaded', installScaleControl);
  // 控制台是动态渲染的：稍后再补绑几次，保证一定能绑上
  setTimeout(installScaleControl, 900);
  setTimeout(installScaleControl, 2400);
})();