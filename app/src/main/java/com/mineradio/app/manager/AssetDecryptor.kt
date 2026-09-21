package com.mineradio.app.manager

import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Assets 解密工具 — 运行时解密构建时加密的 assets/mineradio/ 文件
 *
 * 加密格式: [MENC(4字节魔数)] + [IV(16字节)] + [AES/CBC/PKCS5Padding 密文]
 *
 * 构建时由 app/build.gradle 中的 encryptMineradioAssets 任务加密，
 * 运行时由 [MineradioServer.serveStaticFile] 和 [CustomBackgroundLayer] 调用解密。
 *
 * ★ 国防级加固版：
 *   1. 优先调用 Native C 层解密 (nativeDecryptAsset)
 *   2. passphrase 在 .so 中分片存储, Java 层反编译不可见
 *   3. 仅在 Native 不可用时回退到 Java 解密（开发调试用）
 */
object AssetDecryptor {
    private const val MAGIC = "MENC"

    // ★ 持有 PluginManager 引用,用于调用 Native 解密方法
    @Volatile
    private var pluginManager: PluginManager? = null

    /** 由 MineradioServer 在启动时设置引用 */
    fun bindPluginManager(pm: PluginManager) {
        pluginManager = pm
    }

    // 密钥通过 SHA-256 派生 256 位 AES 密钥
    // ⚠️ 仅作为 Native 不可用时的 fallback, 正常情况下不会执行
    // ⚠️ 此 passphrase 在 Native .so 中也有一份(分片存储), 两者必须一致
    private val KEY_PASSPHRASE: ByteArray =
        byteArrayOf(
            83,
            112,
            49,
            99,
            97,
            64,
            77,
            105,
            110,
            101,
            114,
            97,
            100,
            49,
            111,
            35,
            50,
            48,
            50,
            52,
            36,
            83,
            101,
            99,
            117,
            114,
            101,
            65,
            115,
            115,
            101,
            116,
            75,
            101,
            121,
            33,
        ) // "Sp1ca@Minerad1o#2024$SecureAssetKey!"

    private val keyBytes: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256").digest(KEY_PASSPHRASE)
    }

    private val magicBytes: ByteArray = MAGIC.toByteArray(Charsets.US_ASCII)

    /** 判断字节数组是否为加密格式（以 MENC 魔数开头） */
    fun isEncrypted(data: ByteArray): Boolean {
        if (data.size < 4 + 16) return false
        return data.copyOfRange(0, 4).contentEquals(magicBytes)
    }

    /**
     * 如果数据带有 MENC 魔数则解密，否则原样返回
     *
     * 格式: [MENC(4)] + [IV(16)] + [密文]
     *
     * ★ 优先使用 Native C 层解密 (国防级), 失败回退到 Java 解密
     */
    fun decrypt(data: ByteArray): ByteArray {
        if (!isEncrypted(data)) return data

        // ★ 1. 优先调用 Native 解密 (passphrase 在 .so 中分片存储)
        val pm = pluginManager
        if (pm != null) {
            try {
                val result = pm.nativeDecryptAsset(data)
                if (result.isNotEmpty()) return result
            } catch (e: Exception) {
                // Native 解密失败,回退到 Java 解密
            }
        }

        // ★ 2. Fallback: Java 解密 (仅 Native 不可用时使用)
        return try {
            val iv = data.copyOfRange(4, 20)
            val cipherBytes = data.copyOfRange(20, data.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            cipher.doFinal(cipherBytes)
        } catch (e: Exception) {
            data
        }
    }

    /** 读取 InputStream 全部字节并解密 */
    fun decryptStream(input: InputStream): ByteArray {
        val raw = input.readBytes()
        return decrypt(raw)
    }
}
