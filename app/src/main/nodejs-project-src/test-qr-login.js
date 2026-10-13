'use strict';
// 扫码登录真机测试台：在 PC 上跑，手机扫码，打印全链路真实数据
// 用法：node test-qr-login.js qq   或   node test-qr-login.js kugou

const fs = require('fs');
const path = require('path');
const providers = require('./qr-login-providers');

providers.setDebugLogger((label, data) => {
  console.log('\n>>> [' + label + ']');
  console.log(typeof data === 'string' ? data : JSON.stringify(data, null, 2));
});

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const QQ_HEADERS = { Referer: 'https://y.qq.com/', 'User-Agent': UA };

function saveQrPng(dataUrl, name) {
  const b64 = String(dataUrl || '').replace(/^data:image\/\w+;base64,/, '');
  if (!b64) { console.log('(二维码为空，跳过保存)'); return; }
  const file = path.join(__dirname, name);
  fs.writeFileSync(file, Buffer.from(b64, 'base64'));
  console.log('\n================ 二维码已保存，请打开并扫码 ================');
  console.log('  ' + file);
  console.log('============================================================\n');
}

function parseCookie(str) {
  const obj = {};
  String(str || '').split(';').forEach((part) => {
    const eq = part.indexOf('=');
    if (eq > 0) obj[part.slice(0, eq).trim()] = part.slice(eq + 1).trim();
  });
  return obj;
}

async function qqProfileCheck(cookie) {
  const obj = parseCookie(cookie);
  const uin = String(obj.uin || obj.qqmusic_uin || obj.p_uin || '').replace(/\D/g, '');
  const musicKey = obj.qm_keyst || obj.qqmusic_key || '';
  console.log('\n>>> [qq:profile] uin=' + uin + ' has_qm_keyst=' + !!musicKey);
  const u = new URL('https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg');
  u.searchParams.set('cid', '205360838');
  u.searchParams.set('userid', uin);
  u.searchParams.set('reqfrom', '1');
  u.searchParams.set('g_tk', '5381');
  u.searchParams.set('loginUin', uin);
  u.searchParams.set('hostUin', '0');
  u.searchParams.set('format', 'json');
  u.searchParams.set('inCharset', 'utf8');
  u.searchParams.set('outCharset', 'utf-8');
  u.searchParams.set('notice', '0');
  u.searchParams.set('platform', 'yqq.json');
  u.searchParams.set('needNewCode', '0');
  const resp = await providers.rawRequest(u.toString(), {
    headers: { ...QQ_HEADERS, Cookie: cookie },
  }, 10000);
  const text = resp.body.toString('utf8');
  console.log('>>> [qq:profile] HTTP ' + resp.status);
  try {
    const json = JSON.parse(text);
    console.log(JSON.stringify({
      code: json.code,
      result: json.result,
      hasData: !!json.data,
      nick: json.data && json.data.creator && (json.data.creator.nick || json.data.creator.nickname),
      headpic: json.data && json.data.creator && json.data.creator.headpic,
    }, null, 2));
  } catch (_) {
    console.log(text.slice(0, 500));
  }

  // VIP 探测（与 APK 内 fetchQQVipStatus 相同）
  if (musicKey) {
    const body = JSON.stringify({
      comm: { uin, format: 'json', ct: 24, cv: 0, authst: musicKey },
      req_1: {
        module: 'userInfo.VipQueryServer',
        method: 'SRFVipQuery_V2',
        param: { uin_list: [String(uin)] },
      },
    });
    const vresp = await providers.rawRequest('https://u.y.qq.com/cgi-bin/musicu.fcg', {
      method: 'POST',
      headers: { ...QQ_HEADERS, 'Content-Type': 'application/x-www-form-urlencoded', Cookie: cookie },
      body,
    }, 10000);
    console.log('>>> [qq:vip] HTTP ' + vresp.status);
    console.log(vresp.body.toString('utf8').slice(0, 800));
  }
}

async function runQQ() {
  // 二维码约 2 分钟过期：过期/未扫码时自动换新码，直到扫码成功或按 Ctrl+C 退出
  while (true) {
    const qr = await providers.qqGetLoginQr();
    saveQrPng(qr.img, 'test-qq-qr.png');
    console.log('qrsig=' + qr.qrsig + '\nptqrtoken=' + qr.ptqrtoken);
    let done = false;
    for (let i = 0; i < 25 && !done; i++) {
      await new Promise((r) => setTimeout(r, 2500));
      const st = await providers.qqCheckLoginQr({ qrsig: qr.qrsig, ptqrtoken: qr.ptqrtoken });
      console.log('poll#' + i + ' status=' + st.status + ' code=' + st.code + ' msg=' + (st.message || ''));
      if (st.status === 'ok') {
        console.log('\n================ QQ 登录成功 ================');
        console.log('uin=' + st.uin + ' nickname=' + st.nickname);
        console.log('cookie=' + st.cookie);
        await qqProfileCheck(st.cookie);
        return;
      }
      if (st.status === 'expired' || (st.status === 'error' && !st.retryable)) {
        console.log('>>> 本次二维码' + (st.status === 'expired' ? '已过期，自动换新码…' : ('失败: ' + (st.message || ''))));
        done = true;
      }
    }
  }
}

async function runKugou() {
  const buildQr = async (text) => {
    const QRCode = require('qrcode');
    return QRCode.toDataURL(text, { margin: 2, width: 320 });
  };
  const qr = await providers.kgGetLoginQrKey(buildQr);
  console.log('key=' + qr.key + '\nurl=' + qr.url);
  saveQrPng(qr.qrimg, 'test-kugou-qr.png');
  for (let i = 0; i < 60; i++) {
    const st = await providers.kgCheckLoginQr(qr.key).catch((e) => ({ status: 'error', message: e.message }));
    console.log('poll#' + i + ' ' + JSON.stringify(st).slice(0, 400));
    if (st.status === 'ok') {
      console.log('\n================ 酷狗登录成功 ================');
      console.log('cookie=' + st.cookie);
      const { getKugouLoginInfo } = require('./kugou-api');
      try {
        const info = await getKugouLoginInfo(st.cookie);
        console.log('getKugouLoginInfo=' + JSON.stringify(info, null, 2).slice(0, 1200));
      } catch (e) {
        console.log('getKugouLoginInfo 失败: ' + e.message);
      }
      return;
    }
    if (st.status === 'expired' || st.status === 'error') {
      console.log('终态: ' + st.status);
      return;
    }
    await new Promise((r) => setTimeout(r, 2000));
  }
}

const target = (process.argv[2] || 'qq').toLowerCase();
(target === 'kugou' ? runKugou : runQQ)().catch((e) => {
  console.error('测试失败:', e && e.stack || e);
  process.exit(1);
});
