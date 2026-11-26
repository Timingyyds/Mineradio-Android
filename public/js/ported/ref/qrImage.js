// 二维码出图：沿用 mineradio 已经打包进来的 qrcode 内核（window.MRQrcode），
// 不再依赖 qrcode 包的 Node 版路径，也不走任何第三方出图服务。
export const buildQrSvg = (text) => window.MRQrcode.buildSvg(String(text));

export const buildQrDataUrl = (text) => window.MRQrcode.toDataUrl(String(text));
