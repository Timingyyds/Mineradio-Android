// public/js/ported/ref/bootstrap.js
// 把参考层的 API 路由挂到 window.__mrProviderRouter，供 10-api-router 调用。
// prelude 必须先于 router 执行：router 的依赖模块（cookies.js / netease.js 等）
// 会在自己的模块体里读取 chrome.storage / chrome.cookies。

import './prelude.js';
import { handleApiRequest } from './router.js';

window.__mrProviderRouter = handleApiRequest;
console.log('[MrRef] 网易云 / QQ 参考层路由已就绪');
