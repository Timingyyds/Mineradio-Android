package com.mineradio.app.manager

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 基础设施服务 — 加密、Cookie 存储、文件缓存
 *
 * 平台业务 API（网易云/QQ/酷狗/汽水）已全部迁移到 login-panel-v4 插件，
 * 由插件在 JS 层通过 apiJson 拦截器处理。本文件只保留：
 *  1. 加密工具（weapi / RSA / MD5 / 酷狗签名）— 供插件通过 /api/crypto 端点调用
 *  2. Cookie 存储（4 个平台）— 供插件通过 /api/cookie 调用
 *  3. 汽水音乐缓存文件查询 — 供 MineradioServer /qs-cache 路由使用
 */
object NeteaseMusicApi {
    private const val NONCE = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"

    // RSA 公钥参数
    private const val RSA_MODULUS = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val RSA_PUBKEY = "010001"

    // Cookie 存储
    private var userCookie = ""
    private var cookieFile: java.io.File? = null
    private var qqCookie = ""
    private var qqCookieFile: java.io.File? = null
    private var kgCookie = ""
    private var kgCookieFile: java.io.File? = null
    private var qsCookie = "" // 仅 sessionid 值（用于 Web API: me/playlist/detail）
    private var qsXHelios = "" // ★ x-helios 头（PC App 风控指纹，抓包获取）
    private var qsXMedusa = "" // ★ x-medusa 头（PC App 请求签名，抓包获取）
    private var qsCookieFile: java.io.File? = null
    private var qsXHeliosFile: java.io.File? = null
    private var qsXMedusaFile: java.io.File? = null
    private var appFilesDir: java.io.File? = null

    // ==================== 酷狗音乐常量 ====================
    private const val KG_APPID = 1005
    private const val KG_ANDROID_SALT = "OIlwieks28dk2k092lksi2UIkp"
    private const val KG_H5_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    private const val KG_SIGN_KEY_SALT = "57ae12eb6890223e355ccfcb74edf70d"

    fun init(context: Context) {
        appFilesDir = context.filesDir
        cookieFile = java.io.File(context.filesDir, ".netease_cookie")
        qqCookieFile = java.io.File(context.filesDir, ".qq_cookie")
        kgCookieFile = java.io.File(context.filesDir, ".kg_cookie")
        qsCookieFile = java.io.File(context.filesDir, ".qs_cookie")
        qsXHeliosFile = java.io.File(context.filesDir, ".qs_xhelios")
        qsXMedusaFile = java.io.File(context.filesDir, ".qs_xmedusa")
        try {
            if (cookieFile!!.exists()) {
                userCookie = cookieFile!!.readText().trim()
            }
        } catch (e: Exception) {
            userCookie = ""
        }
        try {
            if (qqCookieFile!!.exists()) {
                qqCookie = qqCookieFile!!.readText().trim()
            }
        } catch (e: Exception) {
            qqCookie = ""
        }
        try {
            if (kgCookieFile!!.exists()) {
                kgCookie = kgCookieFile!!.readText().trim()
                Log.d("NeteaseMusic", "KG cookie loaded from disk: ${kgCookieFile?.absolutePath} size=${kgCookie.length}")
            } else {
                Log.d("NeteaseMusic", "KG cookie file not found at ${kgCookieFile?.absolutePath}")
            }
        } catch (e: Exception) {
            kgCookie = ""
            Log.w("NeteaseMusic", "KG cookie load error: ${e.message}")
        }
        try {
            if (qsCookieFile!!.exists()) {
                qsCookie = qsCookieFile!!.readText().trim()
                Log.d("NeteaseMusic", "QS cookie loaded from disk: ${qsCookieFile?.absolutePath} size=${qsCookie.length}")
            } else {
                Log.d("NeteaseMusic", "QS cookie file not found at ${qsCookieFile?.absolutePath}")
            }
        } catch (e: Exception) {
            qsCookie = ""
            Log.w("NeteaseMusic", "QS cookie load error: ${e.message}")
        }
        try {
            if (qsXHeliosFile!!.exists()) {
                qsXHelios = qsXHeliosFile!!.readText().trim()
                Log.d("NeteaseMusic", "QS x-helios loaded from disk: size=${qsXHelios.length}")
            }
        } catch (e: Exception) {
            qsXHelios = ""
            Log.w("NeteaseMusic", "QS x-helios load error: ${e.message}")
        }
        try {
            if (qsXMedusaFile!!.exists()) {
                qsXMedusa = qsXMedusaFile!!.readText().trim()
                Log.d("NeteaseMusic", "QS x-medusa loaded from disk: size=${qsXMedusa.length}")
            }
        } catch (e: Exception) {
            qsXMedusa = ""
            Log.w("NeteaseMusic", "QS x-medusa load error: ${e.message}")
        }
    }

    // ==================== 加密工具 ====================

    private fun randomString(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val sb = StringBuilder()
        val rnd = SecureRandom()
        for (i in 0 until length) {
            sb.append(chars[rnd.nextInt(chars.length)])
        }
        return sb.toString()
    }

    private fun aesEncrypt(
        text: String,
        key: String,
        ivStr: String = IV,
    ): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(ivStr.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun rsaEncrypt(text: ByteArray): String {
        // 对标 NeteaseCloudMusicApi 的 forge.publicKey.encrypt(str, 'NONE')：
        // 1. 将输入字节当作正 BigInteger（big-endian）
        // 2. 计算 m^e mod n
        // 3. 输出 128 字节 hex
        val m = BigInteger(1, text)
        val e = BigInteger(RSA_PUBKEY, 16)
        val n = BigInteger(RSA_MODULUS, 16)
        val c = m.modPow(e, n)
        val encrypted = c.toByteArray()
        // BigInteger.toByteArray() 可能返回 129 字节（多一个 0x00 符号位），去头
        val result =
            if (encrypted.size > 128) {
                encrypted.copyOfRange(encrypted.size - 128, encrypted.size)
            } else if (encrypted.size < 128) {
                // 左侧补零到 128 字节
                val padded = ByteArray(128)
                System.arraycopy(encrypted, 0, padded, 128 - encrypted.size, encrypted.size)
                padded
            } else {
                encrypted
            }
        return bytesToHex(result)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun md5(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        return bytesToHex(md.digest(text.toByteArray(Charsets.UTF_8)))
    }

    /**
     * weapi 加密
     * 对标 NeteaseCloudMusicApi 的 weapi() 函数
     */
    private fun weapi(text: String): Map<String, String> {
        val secKey = randomString(16)
        val encText = aesEncrypt(aesEncrypt(text, NONCE), secKey)
        // 关键：secKey 需要先反转（对标 secretKey.split('').reverse().join('')）
        val encSecKey = rsaEncrypt(secKey.toByteArray(Charsets.UTF_8).reversedArray())
        return mapOf("params" to encText, "encSecKey" to encSecKey)
    }

    // ==================== Cookie 解析工具 ====================

    private fun parseCookieString(cookieText: String): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        cookieText.split(";").forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                val key = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                if (key.isNotEmpty()) map[key] = value
            }
        }
        return map
    }

    // ==================== 酷狗签名算法 ====================

    private fun md5Text(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /**
     * Android 版请求签名 — MD5(salt + 排序参数串 + bodyData + salt)
     */
    private fun signatureAndroidParams(
        params: Map<String, String>,
        bodyData: String? = null,
    ): String {
        val sorted =
            params.entries
                .sortedBy { it.key }
                .joinToString("") { "${it.key}=${if (it.value.startsWith("{")) it.value else it.value}" }
        val signStr = KG_ANDROID_SALT + sorted + (bodyData ?: "") + KG_ANDROID_SALT
        return md5Text(signStr)
    }

    /**
     * H5 版请求签名 — MD5(H5_salt + 排序参数串 + JSON(bodyObj) + H5_salt)
     */
    private fun signatureH5Params(
        params: Map<String, String>,
        bodyJson: String?,
    ): String {
        val parts =
            params.entries
                .sortedBy { it.key }
                .joinToString("") { "${it.key}=${it.value}" }
        val signStr = KG_H5_SALT + parts + (bodyJson ?: "") + KG_H5_SALT
        return md5Text(signStr)
    }

    /**
     * signKey — MD5(hash + "57ae12eb6890223e355ccfcb74edf70d" + appid + mid + userid)
     * ★ public — 供插件通过 /api/crypto/kugou/sign_key 调用
     */
    fun signKey(
        hash: String,
        mid: String,
        userid: String,
        appid: Int = KG_APPID,
    ): String = md5Text("$hash$KG_SIGN_KEY_SALT$appid$mid$userid")

    /**
     * kugouCloudKey — MD5(hash + "kgcloud")
     * ★ public — 供插件通过 /api/crypto/kugou/cloud_key 调用
     */
    fun kugouCloudKey(hash: String): String = md5Text(hash + "kgcloud")

    // ==================== Cookie 存储API（供 /api/cookie 调用） ====================

    fun getCookie(): String = userCookie

    fun setCookie(cookie: String) {
        userCookie = cookie
        try {
            cookieFile?.writeText(cookie)
        } catch (e: Exception) {
        }
    }

    fun getQQCookie(): String = qqCookie

    fun saveQQCookie(cookie: String) {
        qqCookie = cookie.trim()
        try {
            qqCookieFile?.writeText(qqCookie)
        } catch (e: Exception) {
            Log.e("NeteaseMusic", "QQ cookie file write FAILED: ${e.message}", e)
        }
    }

    fun getKGCookie(): String = kgCookie

    fun saveKGCookie(cookie: String) {
        kgCookie = cookie.trim()
        Log.d("NeteaseMusic", "KG cookie saved, length=${kgCookie.length}")
        try {
            kgCookieFile?.writeText(kgCookie)
            Log.d("NeteaseMusic", "KG cookie file written OK: ${kgCookieFile?.absolutePath} size=${kgCookieFile?.length()}")
        } catch (e: Exception) {
            Log.e("NeteaseMusic", "KG cookie file write FAILED: ${e.message}", e)
        }
    }

    fun saveQSCookie(cookie: String) {
        val raw = cookie.trim()
        // 提取 sessionid（用于 Web API: me/playlist/detail）
        val sessionId =
            if (raw.startsWith("sessionid=")) {
                raw.removePrefix("sessionid=").trim()
            } else if (raw.startsWith("sessionid_ss=")) {
                raw.removePrefix("sessionid_ss=").trim()
            } else {
                val match = Regex("(?:^|;\\s*)sessionid=([^;]+)").find(raw)
                match?.groupValues?.get(1)?.trim() ?: raw.take(300).trim()
            }
        qsCookie = sessionId
        // ★ 关键修复: 扫码登录的完整 cookie 包含 douyin.com 域的干扰字段，
        //   PC App 3.3.0 风控会误判返回 30 秒试听版，因此只保存纯 sessionid。
        Log.d("NeteaseMusic", "QS sessionid saved, length=${qsCookie.length}")
        try {
            qsCookieFile?.writeText(sessionId)
            Log.d("NeteaseMusic", "QS sessionid file written OK: ${qsCookieFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e("NeteaseMusic", "QS sessionid file write FAILED: ${e.message}", e)
        }
    }

    fun getQSCookie(): String = qsCookie

    // ★ 保存 x-helios / x-medusa（PC App 风控头，需抓包获取）
    fun saveQSXHelios(value: String) {
        qsXHelios = value.trim()
        Log.d("NeteaseMusic", "QS x-helios saved, length=${qsXHelios.length}")
        try {
            qsXHeliosFile?.writeText(qsXHelios)
        } catch (e: Exception) {
            Log.e("NeteaseMusic", "QS x-helios file write FAILED: ${e.message}", e)
        }
    }

    fun saveQSXMedusa(value: String) {
        qsXMedusa = value.trim()
        Log.d("NeteaseMusic", "QS x-medusa saved, length=${qsXMedusa.length}")
        try {
            qsXMedusaFile?.writeText(qsXMedusa)
        } catch (e: Exception) {
            Log.e("NeteaseMusic", "QS x-medusa file write FAILED: ${e.message}", e)
        }
    }

    fun getQSXHelios(): String = qsXHelios

    fun getQSXMedusa(): String = qsXMedusa

    // ==================== 汽水音乐缓存文件服务（供 /qs-cache/ 路由） ====================

    /**
     * 获取汽水音乐缓存文件 (供 MineradioServer 的 /qs-cache/ 路由调用)
     */
    fun getQSCacheFile(trackId: String): java.io.File? {
        val dir = appFilesDir ?: return null
        val file = java.io.File(dir, "qs_cache/$trackId.m4a")
        return if (file.exists() && file.isFile) file else null
    }

    // ==================== ★ 通用加密服务（供插件调用） ====================

    /**
     * 网易云 weapi 加密 — 供插件通过 /api/crypto/weapi 调用
     * @param text 原始 JSON 字符串
     * @return JSONObject 含 params 和 encSecKey
     */
    fun weapiEncrypt(text: String): JSONObject {
        val result = weapi(text)
        return JSONObject().apply {
            put("params", result["params"])
            put("encSecKey", result["encSecKey"])
        }
    }

    /**
     * 通用 MD5 哈希 — 供插件通过 /api/crypto/md5 调用
     */
    fun md5Hash(text: String): String = md5(text)

    /**
     * 酷狗 Android 签名 — 供插件通过 /api/crypto/kugou/sign_android 调用
     */
    fun kugouSignAndroid(
        params: Map<String, String>,
        bodyData: String?,
    ): String = signatureAndroidParams(params, bodyData)

    /**
     * 酷狗 H5 签名 — 供插件通过 /api/crypto/kugou/sign_h5 调用
     */
    fun kugouSignH5(
        params: Map<String, String>,
        bodyJson: String?,
    ): String = signatureH5Params(params, bodyJson)

    /**
     * parseCookieString 公开 — 供插件解析 cookie 字符串
     */
    fun parseCookieStr(cookieText: String): Map<String, String> = parseCookieString(cookieText)
}
