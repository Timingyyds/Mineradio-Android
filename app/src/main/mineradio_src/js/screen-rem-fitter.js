/**
 * screen-rem-fitter.js
 * Source: https://github.com/nongshuqiner/screen-rem-fitter.js
 * License: MIT
 *
 * 根据浏览器/屏幕宽度，按设置比例动态调整 html 的 font-size，
 * 用于 rem 单位随屏幕等比例缩放 UI 面板。
 */
(function (root, pluginName, factory) {
  if (typeof define === 'function' && define.amd) {
    // AMD:
    define(factory()); // define([], factory);
  } else if (typeof module === 'object' && module.exports) {
    // Node:
    module.exports = factory();
    module.exports.default = module.exports;
  } else {
    // Browser:
    if (root === undefined) {
      root = typeof global !== "undefined" ? global : window
    }
    root[pluginName] = factory();
  }
}(this, 'screenRemFitter', function () {
  'use strict';
  var screenRemFitter = {
    name: 'screenRemFitter',
    htmlFontSize: null,
    maxWidth: null, // 最大运算宽度
    datumWidth: null, // 运算基准宽度
    screenWidth: null, // 浏览器宽度
    // datumWidth: 必传; maxWidth: 非必传;
    setData: function (datumWidth, maxWidth) {
      if (maxWidth) {
        this.maxWidth = Math.ceil(maxWidth);
      }
      this.datumWidth = Math.ceil(datumWidth);
    },
    flexible: function () {
      var fitter = this.fitter.bind(this);
      var resizeTimer = null;
      fitter();
      window.addEventListener('resize', function () {
        if (resizeTimer) return;
        resizeTimer = setTimeout(function () {
          resizeTimer = null;
          fitter();
        }, 120);
      });
    },
    fitter: function () {
      if (!this.datumWidth) {
        console.error(new Error("Don't set datumWidth!"));
        return;
      }
      var screenWidth = Math.max(document.documentElement.clientWidth, window.innerWidth || 0);
      this.screenWidth = screenWidth;
      var width = this.maxWidth ? (screenWidth > this.maxWidth ? this.maxWidth : screenWidth) : screenWidth;
      var fz = ~~(width / this.datumWidth * 100 * 10000) / 10000;
      if (fz === this.lastFz) return;
      this.lastFz = fz;
      var html = document.getElementsByTagName('html')[0];
      html.style.fontSize = fz + 'px';
      var realfz = ~~(+window.getComputedStyle(html).fontSize.replace('px', '') * 10000) / 10000;
      if (fz !== realfz) {
        var corrected = fz * (fz / realfz);
        this.lastFz = corrected;
        html.style.fontSize = corrected + 'px'
      };
      this.htmlFontSize = +window.getComputedStyle(html).fontSize.replace('px', '');
    }
  };
  return screenRemFitter;
}));