// public/js/ported/qishui/bootstrap.js
// 把汽水音乐的 /api/qishui/* 适配器挂到 window，供 10-api-router 调用。
import { handleQishuiRequest } from './router.js';

window.__mrQishuiRouter = handleQishuiRequest;
console.log('[MrRef] 汽水音乐路由已就绪');
