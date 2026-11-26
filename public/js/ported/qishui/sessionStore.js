// src/audioNative/sessionStore.ts
// 与 src/services/onlineMusic/providerStorage.ts 等价的 localStorage 会话存储。
// 刻意独立实现（键名格式完全一致）：rolldown 对动态入口 chunk 的跨 chunk 导入
// 存在"引用保留、定义丢失"的裁剪缺陷，audioNative 内禁止从 services/ 导入运行时值。
const sessionKey = (providerId, key) => `online_provider:${providerId}:${key}`;
export const readProviderSessionValue = (providerId, key) => {
    if (typeof localStorage === 'undefined')
        return null;
    return localStorage.getItem(sessionKey(providerId, key));
};
export const writeProviderSessionValue = (providerId, key, value) => {
    if (typeof localStorage !== 'undefined')
        localStorage.setItem(sessionKey(providerId, key), value);
};
export const removeProviderSessionValue = (providerId, key) => {
    if (typeof localStorage !== 'undefined')
        localStorage.removeItem(sessionKey(providerId, key));
};
