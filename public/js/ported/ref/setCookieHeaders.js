/**
 * 读取响应里的 Set-Cookie。
 *
 * `Set-Cookie` 是浏览器禁止暴露给 JS 的响应头（Fetch 规范里的 forbidden response-header name），
 * 安卓桥构造 Response 时写进去也会被静默丢掉。原生侧因此把同一批值逐条 base64 后镜像到
 * `x-folia-set-cookie`（见 prelude.js），这里把三处来源合并：
 *   - 浏览器 / 扩展能用的 `headers.getSetCookie()`；
 *   - 扩展页面里能直接读到的 `Set-Cookie`；
 *   - 安卓桥的镜像头（多条以 ", " 拼接，需要拆开并 base64 解码）。
 *
 * 少了这条通路，扫码登录只能从 cookie 罐里回捞 MUSIC_U，而罐里可能是上一次会话留下的旧值 ——
 * 表现就是「扫码已确认但没有登录凭证」。
 */

export const SET_COOKIE_MIRROR_HEADER = 'x-folia-set-cookie';

function decodeMirrorValue(raw) {
  const text = String(raw || '');
  if (!text) return '';
  try {
    // btoa 结果只含 base64 字符集，不含逗号，可以安全按 ", " 拆分。
    const binary = atob(text.trim());
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
    return new TextDecoder().decode(bytes);
  } catch (_) {
    return text;
  }
}

/** 原始 Set-Cookie 头值（可能一条里含多个 cookie，调用方负责拆分）。 */
export function readRawSetCookies(resp) {
  const list = [];
  const headers = resp && resp.headers;
  if (!headers) return list;
  try {
    if (typeof headers.getSetCookie === 'function') {
      const values = headers.getSetCookie();
      if (Array.isArray(values)) list.push(...values.filter(Boolean).map(String));
    }
  } catch (_) {}
  try {
    const single = headers.get('Set-Cookie');
    if (single) list.push(String(single));
  } catch (_) {}
  try {
    const mirrored = headers.get(SET_COOKIE_MIRROR_HEADER);
    if (mirrored) {
      String(mirrored)
        .split(/,\s*/)
        .map((part) => part.trim())
        .filter(Boolean)
        .forEach((part) => {
          const decoded = decodeMirrorValue(part);
          if (decoded) list.push(decoded);
        });
    }
  } catch (_) {}
  return list;
}
