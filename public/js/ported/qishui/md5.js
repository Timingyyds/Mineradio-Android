// 汽水模块需要同步 MD5（blueimp-md5 的替身）。
// 直接复用已经打包进来的 crypto-es，避免再塞一份 MD5 实现。
import CryptoJS from '../vendor/crypto-es.mjs';

const md5 = (value) => CryptoJS.MD5(String(value)).toString();

export default md5;
