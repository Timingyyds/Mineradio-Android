// public/js/ported/ytm/ytm-pot.js
// YouTube 的 PO Token（BotGuard）铸造：googlevideo 只给「带合法 pot 的地址」发缓存之外的字节，
// 没有 pot 时直链只能下开头约 1MB，之后一律 403（表现：有歌词有进度，就是没声音）。
//
// 做法照 Folia 的 ytmPoToken.ts：
//   隐藏 iframe 里 eval BotGuard 解释器 → 跑 program 拿 snapshot → 换 integrityToken
//   → WebPoMinter 铸 pot（绑定 videoId）。
// 注意：BotGuard 的请求不能带 Origin/Referer，否则 Google 下发降级程序、信号填不出来。
import { getChallenge, descrambleChallenge } from './vendor/bgutils/exports/botguard.js';
import { buildURL, getHeaders } from './vendor/bgutils/exports/utils.js';

const REQUEST_KEY = 'O43z0dpjhgX20SCx4KAo';
const SIGNALS = '__mrYtmSignals';
const INSTALL = '__mrYtmInstall';
const MINT = '__mrYtmMint';
const FRAME_MARK = '__mrYtmFrameId';
const DEFAULT_LIFETIME_MS = 6 * 60 * 60 * 1000;

let framePromise = null;
let frameSeq = 0;
let currentMint = null;
let currentUntil = 0;
let setUpPromise = null;

function log(level, message) {
  try {
    if (window.MRRuntimeLog && typeof window.MRRuntimeLog.push === 'function') window.MRRuntimeLog.push(level, [message], 'ytm-pot');
    else console.log('[YTM-POT] ' + message);
  } catch (e) { /* 忽略 */ }
}

function getFrame() {
  if (framePromise) return framePromise;
  framePromise = new Promise((resolve, reject) => {
    try {
      const frame = document.createElement('iframe');
      frame.style.display = 'none';
      frame.setAttribute('aria-hidden', 'true');
      frame.srcdoc = '<!doctype html><meta charset="utf-8"><title>ytm pot</title>';
      frame.addEventListener('load', () => resolve(frame), { once: true });
      document.body.appendChild(frame);
    } catch (error) { reject(error); }
  }).catch((error) => { framePromise = null; throw error; });
  return framePromise;
}

async function evaluate(code) {
  const frame = await getFrame();
  const win = frame.contentWindow;
  if (!win) throw new Error('BotGuard frame unavailable');
  // eslint-disable-next-line no-eval
  return await win.eval(code);
}

async function markFrame() {
  frameSeq += 1;
  const id = frameSeq;
  await evaluate(`globalThis[${JSON.stringify(FRAME_MARK)}] = ${id}; ${id};`);
  return id;
}

// 本地桥请求：不带 Origin/Referer（见文件头）
async function potFetch(url, init) {
  init = init || {};
  const method = String(init.method || 'GET').toUpperCase();
  const headers = {};
  try { new Headers(init.headers || {}).forEach((v, k) => { headers[k] = v; }); } catch (e) { /* 忽略 */ }
  delete headers.origin;
  delete headers.Origin;
  delete headers.referer;
  delete headers.Referer;
  const bridge = window.__mrBridgeRequest;
  const body = init.body == null ? undefined : String(init.body);
  if (typeof bridge !== 'function') return await fetch(url, { method, headers, body });
  const answer = await bridge({ url, method, headers, body, timeoutMs: 30000 });
  const text = String((answer && answer.body) || '');
  return new Response(text, {
    status: Number(answer && answer.status) || 0,
    headers: { 'content-type': (answer && answer.headers && (answer.headers['content-type'] || answer.headers['Content-Type'])) || 'application/json' }
  });
}

function runProgram(challenge) {
  return `(async () => {
    const vm = globalThis[${JSON.stringify(challenge.globalName)}];
    if (!vm || typeof vm.a !== 'function') throw new Error('BotGuard is unavailable');
    let started;
    const functions = new Promise(resolve => { started = resolve; });
    const quiet = () => {};
    await vm.a(${JSON.stringify(challenge.program)}, snapshot => started(snapshot), true, undefined, quiet, [[], []], undefined, false, [quiet, quiet, quiet, quiet, quiet]);
    const snapshot = await functions;
    if (typeof snapshot !== 'function') throw new Error('BotGuard gave no snapshot function');
    const signals = [];
    const answer = await new Promise(resolve => snapshot(resolve, [undefined, undefined, signals, undefined]));
    globalThis.${SIGNALS} = signals;
    globalThis.${INSTALL} = async standardToken => {
      const factory = signals[0];
      if (typeof factory !== 'function') throw new Error('signals[0] is ' + typeof factory);
      const mint = await factory(Uint8Array.from(atob(standardToken), c => c.charCodeAt(0)));
      if (typeof mint !== 'function') throw new Error('BotGuard returned no minter');
      globalThis.${MINT} = async binding => {
        const bytes = await mint(new TextEncoder().encode(binding));
        if (!(bytes instanceof Uint8Array)) throw new Error('mint returned non-bytes');
        return btoa(String.fromCharCode.apply(null, Array.from(bytes))).split('+').join('-').split('/').join('_');
      };
      return true;
    };
    return { answer, signalsLen: signals.length, sig0: typeof signals[0] };
  })()`;
}

async function installMinter(standardToken) {
  const answer = await evaluate(`(async () => {
    const install = globalThis[${JSON.stringify(INSTALL)}];
    if (typeof install !== 'function') return { missing: true };
    await install(${JSON.stringify(standardToken)});
    return { mintType: typeof globalThis[${JSON.stringify(MINT)}] };
  })()`);
  if (answer && answer.missing) throw new Error('minter installer missing');
  if (!answer || answer.mintType !== 'function') throw new Error('minter was not installed');
}

async function callMinter(binding) {
  const answer = await evaluate(`(async () => {
    const fn = globalThis[${JSON.stringify(MINT)}];
    if (typeof fn !== 'function') return { missing: true };
    return { token: await fn(${JSON.stringify(binding)}) };
  })()`);
  if (!answer || answer.missing) throw new Error('minter missing');
  const token = String(answer.token || '');
  if (!token) throw new Error('minter returned no bytes');
  return token;
}

async function setUp() {
  const frameId = await markFrame();
  const challenge = await getChallenge({ fetchFunction: potFetch, requestKey: REQUEST_KEY, useYouTubeAPI: true });
  const interpreter = challenge && challenge.interpreterJavascript && challenge.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
  if (!interpreter || !challenge.globalName || !challenge.program) throw new Error('BotGuard challenge is incomplete');
  log('info', 'challenge 已取到（interpreter ' + interpreter.length + '、program ' + String(challenge.program).length + '、frame ' + frameId + '）');
  await evaluate(`(() => { ${interpreter}\n })(); true;`);
  const ran = await evaluate(runProgram(challenge));
  if (!ran || ran.signalsLen < 1 || ran.sig0 !== 'function') {
    throw new Error('BotGuard filled no signals (len=' + (ran && ran.signalsLen) + ')');
  }
  const response = await potFetch(buildURL('GenerateIT', true), {
    method: 'POST',
    headers: getHeaders(),
    body: JSON.stringify([REQUEST_KEY, ran.answer])
  });
  const raw = await response.text();
  if (!response.ok) throw new Error('BotGuard answer refused (HTTP ' + response.status + ')');
  let parsed = null;
  try { parsed = JSON.parse(raw); } catch (e) { parsed = null; }
  let shaped = parsed;
  if (Array.isArray(shaped) && typeof shaped[1] === 'string') {
    const descrambled = descrambleChallenge(String(shaped[1]));
    if (descrambled) { try { shaped = JSON.parse(descrambled); } catch (e) { /* 忽略 */ } }
  }
  let integrityToken = '';
  let lifetimeSeconds = 0;
  if (Array.isArray(shaped)) {
    integrityToken = String(shaped.find((v) => typeof v === 'string' && v.length > 40) || '');
    lifetimeSeconds = Number(shaped.find((v) => typeof v === 'number' && Number(v) > 0) || 0);
  } else if (shaped && typeof shaped === 'object') {
    integrityToken = String(shaped.integrityToken || '');
    lifetimeSeconds = Number(shaped.estimatedTtlSecs || shaped.ttlSecs || 0);
  }
  if (!integrityToken) throw new Error('BotGuard returned no integrity token');
  const standard = integrityToken.split('-').join('+').split('_').join('/').split('.').join('=');
  await installMinter(standard);
  await callMinter('selftest');
  const lifetime = lifetimeSeconds > 0 ? lifetimeSeconds * 1000 : DEFAULT_LIFETIME_MS;
  log('info', 'BotGuard 就绪，有效期约 ' + Math.round(lifetime / 60000) + ' 分钟');
  return { mint: callMinter, until: Date.now() + lifetime };
}

export async function mintPoToken(videoId) {
  try {
    if (!currentMint || Date.now() >= currentUntil) {
      if (!setUpPromise) {
        setUpPromise = setUp().then((result) => {
          currentMint = result.mint;
          currentUntil = result.until;
          return result;
        }).finally(() => { setUpPromise = null; });
      }
      await setUpPromise;
    }
    const token = await currentMint(videoId);
    log('info', 'poToken 铸造成功（' + token.length + ' 字符）');
    return token;
  } catch (error) {
    log('warn', 'poToken 铸造失败：' + (error && error.message || error));
    return '';
  }
}

window.__mrYtmPot = { mint: mintPoToken, ready: () => !!currentMint && Date.now() < currentUntil };
log('info', 'poToken 模块已加载');
