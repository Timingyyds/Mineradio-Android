package com.mineradio.app.manager

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ★ 运行时插件管理器（国防级加固版）
 *
 * 负责插件的安装（解压 zip / 解密 .mr）、列举、卸载、启用/禁用。
 * 插件目录结构:
 *   <filesDir>/mineradio_plugins/
 *     ├── <pluginId>/
 *     │   ├── manifest.json   — 元数据 {id, name, version, author, description}
 *     │   ├── main.js         — 实现代码
 *     │   └── entry.json      — 与主程序连接的入口配置
 *     └── plugins_state.json  — 全局启用/禁用状态
 *
 * ★ 支持 .mr 加密插件包:
 *   - MRPKG v1 (旧版): AES-256-CBC, 固定密钥派生 (向后兼容)
 *   - MRPKG v2 (新版): RSA-2048 + AES-256-CBC 混合加密, 全程在 Native C 层解密
 *
 * ★ 国防级安全设计:
 *   1. RSA 私钥以 5 分片 + 置换混淆存储在 .so 中, 静态分析无法识别
 *   2. RSA + AES 解密全程在 C 层完成, Java 层不接触明文密钥
 *   3. 反 Frida / 反 Xposed / 反调试检测, 检测到攻击返回 NULL
 *   4. JNI 动态注册, 方法名不暴露在 .so 导出表
 *   5. 内存清零: 敏感数据用完立即 memset 清零
 *   6. 编译选项强化: strip-all + gc-sections + visibility=hidden
 */
class PluginManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "PluginManager"
        const val PLUGINS_DIR = "mineradio_plugins"
        const val STATE_FILE = "plugins_state.json"

        // ★ v2.1 放宽必须文件限制：支持多种文件名
        //  manifest 候选（按优先级查找）
        val MANIFEST_CANDIDATES = arrayOf("manifest.json", "plugin.json", "package.json")

        //  入口 JS 候选（按优先级查找）
        val ENTRY_JS_CANDIDATES = arrayOf("main.js", "index.js", "init.js", "plugin.js")
        //  entry.json 改为可选

        // 旧版兼容（仅用于日志/老代码引用）
        const val FILE_MANIFEST = "manifest.json"
        const val FILE_MAIN_JS = "main.js"
        const val FILE_ENTRY = "entry.json"

        // ★ MRPKG 格式常量
        private val MRPKG_MAGIC = byteArrayOf(0x4D, 0x52, 0x50, 0x4B, 0x47, 0x0A) // "MRPKG\n"
        private const val MRPKG_VERSION_V1: Byte = 1
        private const val MRPKG_VERSION_V2: Byte = 2
        private const val MRPKG_VERSION_V21: Byte = 3 // ★ v2.1: 2bytes BE FileCount

        init {
            // ★ 加载 Native 解密库（国防级加固版）
            try {
                System.loadLibrary("mr_decrypt")
                Log.i(TAG, "Native decrypt library loaded (libmr_decrypt.so, 国防级加固版)")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libmr_decrypt.so", e)
            }
        }
    }

    // ★ Native 方法声明 (实现在 mr_decrypt.c, JNI 动态注册)
    //   ★ 私钥不再暴露给 Java 层, 所有解密在 C 层完成
    private external fun nativeDecryptMrV2(
        encKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray?

    /** ★ 解密 Assets 文件 (替代 AssetDecryptor.kt 的 Java 实现) */
    external fun nativeDecryptAsset(data: ByteArray): ByteArray

    external fun nativeGetMrVersion(): Int

    /** ★ 安全检测: 检测 Frida/Xposed/调试器, 返回 true 表示检测到攻击 */
    external fun nativeSecurityCheck(): Boolean

    private val baseDir: File by lazy {
        File(context.filesDir, PLUGINS_DIR).apply { if (!exists()) mkdirs() }
    }

    private val stateFile: File by lazy { File(baseDir, STATE_FILE) }

    // ★ 派生插件解密密钥 (与打包脚本 build_mr.ps1 一致)
    //  种子字符串以 XOR 0x4D 混淆存储,反编译不可见
    //  ⚠️ 仅用于 MRPKG v1 向后兼容
    private fun derivePluginKey(): ByteArray {
        val enc =
            intArrayOf(
                0x00,
                0x1F,
                0x01,
                0x22,
                0x2A,
                0x24,
                0x23,
                0x1D,
                0x2C,
                0x23,
                0x28,
                0x21,
                0x1E,
                0x28,
                0x2E,
                0x3F,
                0x28,
                0x39,
                0x06,
                0x28,
                0x34,
                0x7F,
                0x7D,
                0x7F,
                0x79,
                0x00,
                0x24,
                0x23,
                0x28,
                0x3F,
                0x2C,
                0x29,
                0x24,
                0x22,
                0x6C,
                0x0D,
            )
        val seed = ByteArray(enc.size) { (enc[it] xor 0x4D).toByte() }
        return MessageDigest.getInstance("SHA-256").digest(seed)
    }

    // ★ 解密单个文件内容 — MRPKG v1 (AES-256-CBC, data = IV(16B) + ciphertext)
    //  ⚠️ 仅用于向后兼容旧版 .mr 文件
    private fun decryptMrDataV1(data: ByteArray): ByteArray {
        require(data.size > 16) { "加密数据太短: ${data.size} bytes" }
        val key = derivePluginKey()
        val iv = data.copyOfRange(0, 16)
        val ciphertext = data.copyOfRange(16, data.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    // ★ 解密单个文件内容 — MRPKG v2 (国防级: 全程 C 层完成 RSA + AES 解密)
    //  data = encKey(RSA加密的AES密钥) + IV(16B) + ciphertext(AES密文)
    //  ★ Java 层只传参, 不接触私钥和明文密钥
    private fun decryptMrDataV2(
        encKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val plain =
            nativeDecryptMrV2(encKey, iv, ciphertext)
                ?: throw SecurityException("解密失败: 可能检测到调试环境或数据损坏")
        return plain
    }

    /** 读取持久化的启用状态 */
    private fun readState(): JSONObject =
        try {
            if (stateFile.exists()) JSONObject(stateFile.readText()) else JSONObject()
        } catch (e: Exception) {
            Log.w(TAG, "读取状态失败: ${e.message}")
            JSONObject()
        }

    /** 写入持久化启用状态 */
    private fun writeState(state: JSONObject) {
        try {
            stateFile.writeText(state.toString())
        } catch (e: Exception) {
            Log.e(TAG, "写入状态失败: ${e.message}")
        }
    }

    /** 安装插件：从 zip Uri 解压到 <filesDir>/mineradio_plugins/<id>/ */
    data class InstallResult(
        val success: Boolean,
        val pluginId: String = "",
        val name: String = "",
        val message: String = "",
    )

    fun installFromZip(uri: Uri): InstallResult {
        try {
            val input =
                context.contentResolver.openInputStream(uri)
                    ?: return InstallResult(false, message = "无法读取文件")
            input.use { stream ->
                // 先解压到临时目录,验证完整后再正式落地
                val tempDir = File(baseDir, "_tmp_${System.currentTimeMillis()}")
                tempDir.mkdirs()
                try {
                    if (!extractZip(stream, tempDir)) {
                        tempDir.deleteRecursively()
                        return InstallResult(false, message = "解压失败:无法读取 zip 内容")
                    }
                    // 校验必须的三个文件(在 zip 根目录或单层子目录下)
                    val (manifestFile, mainJsFile, entryFile) =
                        findRequiredFiles(tempDir)
                            ?: run {
                                tempDir.deleteRecursively()
                                return InstallResult(false, message = "压缩包缺少必须文件:manifest.json / main.js / entry.json")
                            }

                    // 解析 manifest
                    val manifest = JSONObject(manifestFile.readText())
                    val pluginId = manifest.optString("id", "").trim()
                    if (pluginId.isEmpty() || !pluginId.matches(Regex("^[a-zA-Z0-9_\\-]+$"))) {
                        tempDir.deleteRecursively()
                        return InstallResult(false, message = "manifest.json 中 id 字段无效(仅允许字母数字下划线短横)")
                    }
                    val name = manifest.optString("name", pluginId)

                    // 检查是否已存在 → 覆盖安装
                    val targetDir = File(baseDir, pluginId)
                    if (targetDir.exists()) targetDir.deleteRecursively()

                    // 移动临时目录到正式位置
                    tempDir.renameTo(targetDir)
                    // 把三个文件统一规范名(避免 zip 内大小写不一致)
                    ensureCanonicalNames(targetDir)

                    // 默认启用
                    val state = readState()
                    state.put(pluginId, true)
                    writeState(state)

                    Log.i(TAG, "插件安装成功: $pluginId ($name)")
                    return InstallResult(true, pluginId, name, "安装成功")
                } finally {
                    if (tempDir.exists()) tempDir.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "installFromZip 异常", e)
            return InstallResult(false, message = "安装异常: ${e.message}")
        }
    }

    /** ★ 安装插件：从 .mr 加密包解密安装 (支持 v1 和 v2) */
    fun installFromMr(uri: Uri): InstallResult {
        try {
            val input =
                context.contentResolver.openInputStream(uri)
                    ?: return InstallResult(false, message = "无法读取文件")
            input.use { stream ->
                val tempDir = File(baseDir, "_tmp_${System.currentTimeMillis()}")
                tempDir.mkdirs()
                try {
                    // 读取全部字节
                    val allBytes = stream.readBytes()
                    if (allBytes.size < 8) {
                        return InstallResult(false, message = ".mr 文件太小,格式无效")
                    }

                    // 校验 Magic
                    val magic = allBytes.copyOfRange(0, 6)
                    if (!magic.contentEquals(MRPKG_MAGIC)) {
                        return InstallResult(false, message = "不是有效的 .mr 文件(MRPKG 魔数不匹配)")
                    }

                    // ★ 检查版本（v1=1, v2=2, v2.1=3）
                    val version = allBytes[6]
                    if (version != MRPKG_VERSION_V1 && version != MRPKG_VERSION_V2 && version != MRPKG_VERSION_V21) {
                        return InstallResult(false, message = "不支持的 .mr 版本: $version")
                    }

                    // ★ v2.1: FileCount=2bytes BE；v1/v2: FileCount=1byte
                    val fileCount: Int
                    val headerLen: Int
                    when (version) {
                        MRPKG_VERSION_V21 -> {
                            // 2 bytes BE
                            fileCount = ((allBytes[7].toInt() and 0xFF) shl 8) or (allBytes[8].toInt() and 0xFF)
                            headerLen = 9
                        }
                        else -> {
                            // 1 byte
                            fileCount = allBytes[7].toInt() and 0xFF
                            headerLen = 8
                        }
                    }
                    Log.i(TAG, ".mr 文件: version=$version, fileCount=$fileCount, headerLen=$headerLen")

                    // 解析每个文件
                    val dis = DataInputStream(ByteArrayInputStream(allBytes, headerLen, allBytes.size - headerLen))
                    val decryptedFiles = mutableMapOf<String, ByteArray>()

                    for (i in 0 until fileCount) {
                        // NameLen: 2 bytes (big-endian)
                        val nameLen = (dis.readUnsignedByte() shl 8) or dis.readUnsignedByte()
                        // Name
                        val nameBytes = ByteArray(nameLen)
                        dis.readFully(nameBytes)
                        val name = String(nameBytes, Charsets.UTF_8)

                        when (version) {
                            MRPKG_VERSION_V1 -> {
                                // v1 格式: DataLen(4B) + Data(IV+ciphertext)
                                val dataLen =
                                    (dis.readUnsignedByte() shl 24) or
                                        (dis.readUnsignedByte() shl 16) or
                                        (dis.readUnsignedByte() shl 8) or
                                        dis.readUnsignedByte()
                                val encData = ByteArray(dataLen)
                                dis.readFully(encData)

                                val plainData = decryptMrDataV1(encData)
                                decryptedFiles[name] = plainData
                                Log.i(TAG, ".mr v1 解密文件: $name (${encData.size} -> ${plainData.size} bytes)")
                            }
                            MRPKG_VERSION_V2, MRPKG_VERSION_V21 -> {
                                // v2/v2.1 格式相同: EncKeyLen(2B) + EncKey + IV(16B) + DataLen(4B) + Data
                                // RSA 加密的 AES 密钥
                                val encKeyLen = (dis.readUnsignedByte() shl 8) or dis.readUnsignedByte()
                                val encKey = ByteArray(encKeyLen)
                                dis.readFully(encKey)

                                // IV (16 bytes)
                                val iv = ByteArray(16)
                                dis.readFully(iv)

                                // AES 密文
                                val dataLen =
                                    (dis.readUnsignedByte() shl 24) or
                                        (dis.readUnsignedByte() shl 16) or
                                        (dis.readUnsignedByte() shl 8) or
                                        dis.readUnsignedByte()
                                val ciphertext = ByteArray(dataLen)
                                dis.readFully(ciphertext)

                                val plainData = decryptMrDataV2(encKey, iv, ciphertext)
                                decryptedFiles[name] = plainData
                                Log.i(
                                    TAG,
                                    ".mr v${if (version == MRPKG_VERSION_V21) "2.1" else "2"} 解密文件: $name (encKey=$encKeyLen, ct=$dataLen -> plain=${plainData.size} bytes)",
                                )
                            }
                        }
                    }

                    // 写入临时目录
                    for ((name, data) in decryptedFiles) {
                        val outFile = File(tempDir, name)
                        outFile.parentFile?.mkdirs()
                        outFile.writeBytes(data)
                    }

                    // ★ v2.1 校验必须文件（放宽限制：支持多种文件名）
                    val (manifestFile, mainJsFile, entryFile) =
                        findRequiredFiles(tempDir)
                            ?: run {
                                tempDir.deleteRecursively()
                                return InstallResult(
                                    false,
                                    message = ".mr 解密后缺少必须文件: manifest.json(或 plugin.json/package.json) / main.js(或 index.js/init.js/plugin.js)",
                                )
                            }

                    // 解析 manifest
                    val manifest = JSONObject(manifestFile.readText())
                    val pluginId = manifest.optString("id", "").trim()
                    if (pluginId.isEmpty() || !pluginId.matches(Regex("^[a-zA-Z0-9_\\-]+$"))) {
                        tempDir.deleteRecursively()
                        return InstallResult(false, message = "manifest.json 中 id 字段无效")
                    }
                    val name = manifest.optString("name", pluginId)

                    // 覆盖安装
                    val targetDir = File(baseDir, pluginId)
                    if (targetDir.exists()) targetDir.deleteRecursively()

                    tempDir.renameTo(targetDir)
                    ensureCanonicalNames(targetDir)

                    // 默认启用
                    val state = readState()
                    state.put(pluginId, true)
                    writeState(state)

                    Log.i(TAG, ".mr 插件安装成功: $pluginId ($name) [v$version]")
                    return InstallResult(true, pluginId, name, "安装成功")
                } finally {
                    if (tempDir.exists()) tempDir.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "installFromMr 异常", e)
            return InstallResult(false, message = "安装异常: ${e.message}")
        }
    }

    /** ★ 统一安装入口：优先检测 .mr 格式，失败再降级 zip */
    fun install(uri: Uri): InstallResult {
        val fileName = uri.lastPathSegment ?: ""

        // 1. 扩展名明确为 .mr → 直接 .mr 安装
        if (fileName.endsWith(".mr", ignoreCase = true)) {
            return installFromMr(uri)
        }

        // 2. 扩展名明确为 .zip → 直接 zip 安装
        if (fileName.endsWith(".zip", ignoreCase = true)) {
            return installFromZip(uri)
        }

        // 3. 扩展名未知（URI 是数字 ID 等情况）→ 读取文件头判断
        //    优先尝试 MRPKG 格式，因为它有明确的 Magic 头
        try {
            val header = ByteArray(6)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val read = stream.read(header)
                if (read == 6 && header.contentEquals(MRPKG_MAGIC)) {
                    Log.i(TAG, "通过文件头识别为 .mr 格式")
                    return installFromMr(uri)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取文件头失败", e)
        }

        // 4. 降级为 zip 处理
        Log.i(TAG, "默认按 zip 格式处理")
        return installFromZip(uri)
    }

    /** 解压 zip 到目标目录 */
    private fun extractZip(
        input: InputStream,
        targetDir: File,
    ): Boolean =
        try {
            val zis = ZipInputStream(input)
            var entry = zis.nextEntry
            var count = 0
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(targetDir, entry.name)
                    // 防 zip slip 路径穿越
                    val canonicalPath = outFile.canonicalPath
                    if (!canonicalPath.startsWith(targetDir.canonicalPath + File.separator) &&
                        canonicalPath != targetDir.canonicalPath
                    ) {
                        Log.w(TAG, "跳过可疑路径: ${entry.name}")
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            val buf = ByteArray(8192)
                            var len: Int
                            while (zis.read(buf).also { len = it } > 0) fos.write(buf, 0, len)
                        }
                        count++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            zis.close()
            count > 0
        } catch (e: Exception) {
            Log.e(TAG, "extractZip 异常: ${e.message}")
            false
        }

    /** 在解压目录中查找必须的三个文件(支持根目录或单层子目录) */
    private fun findRequiredFiles(dir: File): Triple<File, File, File>? {
        // ★ v2.1 支持多种文件名（按优先级查找）
        // 1. 直接在根目录找
        val m = findFirstExisting(dir, MANIFEST_CANDIDATES)
        val j = findFirstExisting(dir, ENTRY_JS_CANDIDATES)
        // entry.json 改为可选
        val e =
            File(dir, FILE_ENTRY).takeIf { it.exists() }
                ?: File(dir, "plugin-entry.json").takeIf { it.exists() }
                ?: File(dir, "init.json").takeIf { it.exists() }
        if (m != null && j != null) {
            // 标准化为 canonical 名（便于 PluginManager 后续读取）
            ensureCanonicalFile(dir, m, FILE_MANIFEST, MANIFEST_CANDIDATES)
            ensureCanonicalFile(dir, j, FILE_MAIN_JS, ENTRY_JS_CANDIDATES)
            // entry 为可选；如果不存在，则创建默认值
            val entryFile =
                e ?: run {
                    val defaultEntry = File(dir, FILE_ENTRY)
                    defaultEntry.writeText("""{"type":"panel","autoRun":true}""")
                    defaultEntry
                }
            return Triple(File(dir, FILE_MANIFEST), File(dir, FILE_MAIN_JS), entryFile)
        }

        // 2. 在单层子目录中找（zip 整体打了一个文件夹）
        dir.listFiles()?.forEach { sub ->
            if (sub.isDirectory) {
                val mm = findFirstExisting(sub, MANIFEST_CANDIDATES)
                val jj = findFirstExisting(sub, ENTRY_JS_CANDIDATES)
                val ee =
                    File(sub, FILE_ENTRY).takeIf { it.exists() }
                        ?: File(sub, "plugin-entry.json").takeIf { it.exists() }
                        ?: File(sub, "init.json").takeIf { it.exists() }
                if (mm != null && jj != null) {
                    // 提升到根目录并标准化为 canonical 名
                    mm.copyTo(File(dir, FILE_MANIFEST), overwrite = true)
                    jj.copyTo(File(dir, FILE_MAIN_JS), overwrite = true)
                    val targetEntry =
                        ee?.let { File(dir, FILE_ENTRY).apply { it.copyTo(this, overwrite = true) } }
                            ?: File(dir, FILE_ENTRY).apply { writeText("""{"type":"panel","autoRun":true}""") }
                    sub.deleteRecursively()
                    return Triple(File(dir, FILE_MANIFEST), File(dir, FILE_MAIN_JS), targetEntry)
                }
            }
        }
        return null
    }

    /** 在目录中按候选列表查找第一个存在的文件 */
    private fun findFirstExisting(
        dir: File,
        candidates: Array<String>,
    ): File? {
        for (name in candidates) {
            val f = File(dir, name)
            if (f.exists() && f.isFile) return f
        }
        return null
    }

    /** 将找到的非 canonical 名文件重命名为 canonical 名（避免覆盖已有 canonical 文件） */
    private fun ensureCanonicalFile(
        dir: File,
        found: File,
        canonicalName: String,
        allCandidates: Array<String>,
    ) {
        if (found.name == canonicalName) return
        val target = File(dir, canonicalName)
        if (target.exists()) return // canonical 已存在，不再覆盖
        found.renameTo(target)
    }

    /** 规范文件名:确保三个文件名小写标准 */
    private fun ensureCanonicalNames(dir: File) {
        // v2.1 兼容多种大小写和变体名
        val manifestMap = mutableMapOf<String, String>()
        for (name in MANIFEST_CANDIDATES) {
            manifestMap[name.uppercase()] = FILE_MANIFEST
            manifestMap[name.replaceFirstChar { it.uppercase() }] = FILE_MANIFEST
        }
        val jsMap = mutableMapOf<String, String>()
        for (name in ENTRY_JS_CANDIDATES) {
            jsMap[name.uppercase()] = FILE_MAIN_JS
            jsMap[name.replaceFirstChar { it.uppercase() }] = FILE_MAIN_JS
        }
        val entryMap =
            mapOf(
                "ENTRY.JSON" to FILE_ENTRY,
                "Entry.json" to FILE_ENTRY,
                "PLUGIN-ENTRY.JSON" to FILE_ENTRY,
                "INIT.JSON" to FILE_ENTRY,
            )
        (manifestMap + jsMap + entryMap).forEach { (from, to) ->
            val f = File(dir, from)
            if (f.exists() && !File(dir, to).exists()) f.renameTo(File(dir, to))
        }
    }

    /** 列出所有已安装插件 */
    fun listPlugins(): JSONArray {
        val arr = JSONArray()
        val state = readState()
        baseDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory || dir.name == "_tmp") return@forEach
            val manifestFile = File(dir, FILE_MANIFEST)
            if (!manifestFile.exists()) return@forEach
            try {
                val m = JSONObject(manifestFile.readText())
                val obj = JSONObject()
                obj.put("id", dir.name)
                obj.put("name", m.optString("name", dir.name))
                obj.put("version", m.optString("version", "1.0.0"))
                obj.put("author", m.optString("author", "unknown"))
                obj.put("description", m.optString("description", ""))
                obj.put("enabled", state.optBoolean(dir.name, true))
                obj.put("installed", true)
                arr.put(obj)
            } catch (e: Exception) {
                Log.w(TAG, "插件清单解析失败 ${dir.name}: ${e.message}")
            }
        }
        return arr
    }

    /** 获取插件详情(包含 entry 配置) */
    fun getPluginInfo(pluginId: String): JSONObject? {
        val dir = File(baseDir, pluginId)
        if (!dir.exists()) return null
        val mFile = File(dir, FILE_MANIFEST)
        val eFile = File(dir, FILE_ENTRY)
        if (!mFile.exists()) return null
        return try {
            val m = JSONObject(mFile.readText())
            val obj = JSONObject()
            obj.put("id", pluginId)
            obj.put("name", m.optString("name", pluginId))
            obj.put("version", m.optString("version", "1.0.0"))
            obj.put("author", m.optString("author", "unknown"))
            obj.put("description", m.optString("description", ""))
            obj.put("entry", if (eFile.exists()) JSONObject(eFile.readText()) else JSONObject())
            obj
        } catch (e: Exception) {
            Log.e(TAG, "getPluginInfo 异常", e)
            null
        }
    }

    /** 读取插件 main.js 内容 */
    fun readMainJs(pluginId: String): String? {
        val f = File(baseDir, "$pluginId/$FILE_MAIN_JS")
        return if (f.exists()) f.readText() else null
    }

    /** 写入插件 main.js（用于开发时热更新插件代码） */
    fun writeMainJs(
        pluginId: String,
        content: String,
    ): Boolean {
        val f = File(baseDir, "$pluginId/$FILE_MAIN_JS")
        return try {
            f.writeText(content)
            Log.i(TAG, "插件 $pluginId main.js 已更新 (${content.length} chars)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "写入 main.js 失败: ${e.message}")
            false
        }
    }

    /** 读取插件 manifest.json 内容 */
    fun readManifest(pluginId: String): String? {
        val f = File(baseDir, "$pluginId/$FILE_MANIFEST")
        return if (f.exists()) f.readText() else null
    }

    /** 读取插件 entry.json 内容 */
    fun readEntry(pluginId: String): String? {
        val f = File(baseDir, "$pluginId/$FILE_ENTRY")
        return if (f.exists()) f.readText() else null
    }

    // ═══════════════════════════════════════════════════════════
    //  ★★★ 通用资源读取 API（v2.1 扩展，支持插件内置 .so / 模型 / 任意资源）
    // ═══════════════════════════════════════════════════════════

    /**
     * ★ 读取插件目录中任意二进制资源
     * @param pluginId 插件 ID
     * @param relPath 相对路径（POSIX 风格 / 分隔），如 "lib/arm64-v8a/libxxx.so" 或 "assets/live2d/xiaoaimisi/小爱弥斯.moc3"
     * @return 二进制数据，文件不存在返回 null
     *
     * ★ 安全：防止路径穿越（.. 检测），不能逃出插件目录
     */
    fun readResource(
        pluginId: String,
        relPath: String,
    ): ByteArray? {
        return try {
            val pluginDir = File(baseDir, pluginId)
            if (!pluginDir.isDirectory) return null

            // 安全：规范化路径，防止 .. 穿越
            val safePath = relPath.replace("\\", "/").trimStart('/')
            if (safePath.contains("..")) {
                Log.w(TAG, "readResource: 拒绝可疑路径 $relPath")
                return null
            }

            val resFile = File(pluginDir, safePath)
            val canonicalPluginDir = pluginDir.canonicalPath
            val canonicalResFile = resFile.canonicalPath
            if (!canonicalResFile.startsWith(canonicalPluginDir + File.separator) &&
                canonicalResFile != canonicalPluginDir
            ) {
                Log.w(TAG, "readResource: 路径逃逸插件目录，拒绝 $relPath")
                return null
            }

            if (!resFile.exists() || !resFile.isFile) return null
            resFile.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "readResource 失败: ${e.message}")
            null
        }
    }

    /**
     * ★ 读取插件目录中任意文本资源（UTF-8）
     */
    fun readResourceText(
        pluginId: String,
        relPath: String,
    ): String? {
        val bytes = readResource(pluginId, relPath) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * ★ 列出插件目录中指定子目录下所有文件（递归）
     * @param pluginId 插件 ID
     * @param subDir 子目录路径（""=插件根目录，"lib/arm64-v8a"=指定子目录）
     * @return 相对路径列表（POSIX 风格），失败返回空列表
     */
    fun listResources(
        pluginId: String,
        subDir: String,
    ): List<String> {
        return try {
            val pluginDir = File(baseDir, pluginId)
            if (!pluginDir.isDirectory) return emptyList()

            val safeSub = subDir.replace("\\", "/").trimStart('/')
            if (safeSub.contains("..")) return emptyList()

            val targetDir = if (safeSub.isEmpty()) pluginDir else File(pluginDir, safeSub)
            if (!targetDir.isDirectory) return emptyList()

            val canonicalPluginDir = pluginDir.canonicalPath
            val result = mutableListOf<String>()
            targetDir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val rel =
                        f.canonicalPath
                            .removePrefix(canonicalPluginDir + File.separator)
                            .replace("\\", "/")
                    if (!rel.startsWith("..")) result.add(rel)
                }
            }
            result.sorted()
        } catch (e: Exception) {
            Log.e(TAG, "listResources 失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * ★ 获取插件目录的绝对路径（用于 native .so 加载等场景）
     * @return 插件目录的绝对路径字符串，不存在返回 null
     */
    fun getPluginDir(pluginId: String): String? {
        val dir = File(baseDir, pluginId)
        return if (dir.isDirectory) dir.absolutePath else null
    }

    /**
     * ★ 获取插件目录中某个资源的绝对路径（用于 native .so 加载等场景）
     * @param pluginId 插件 ID
     * @param relPath 相对路径
     * @return 绝对路径字符串，不存在返回 null
     */
    fun getResourcePath(
        pluginId: String,
        relPath: String,
    ): String? {
        return try {
            val pluginDir = File(baseDir, pluginId)
            if (!pluginDir.isDirectory) return null

            val safePath = relPath.replace("\\", "/").trimStart('/')
            if (safePath.contains("..")) return null

            val resFile = File(pluginDir, safePath)
            if (!resFile.exists()) return null
            resFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** 启用/禁用插件 */
    fun setEnabled(
        pluginId: String,
        enabled: Boolean,
    ): Boolean {
        val dir = File(baseDir, pluginId)
        if (!dir.exists()) return false
        val state = readState()
        state.put(pluginId, enabled)
        writeState(state)
        Log.i(TAG, "插件 ${if (enabled) "启用" else "禁用"}: $pluginId")
        return true
    }

    /** 卸载插件 */
    fun uninstall(pluginId: String): Boolean {
        val dir = File(baseDir, pluginId)
        if (!dir.exists()) return false
        val ok = dir.deleteRecursively()
        if (ok) {
            val state = readState()
            state.remove(pluginId)
            writeState(state)
            Log.i(TAG, "插件已卸载: $pluginId")

            // ★ v2.2.7 桌面萌宠插件卸载时，同步清理自定义模型目录
            //   用户反馈：删除插件后自定义模型仍在加载，因为 custom_pet_model
            //   存放在 app filesDir 而非插件目录，必须额外清理
            if (pluginId == "desktop-pet") {
                try {
                    val customModelDir = File(context.filesDir, "custom_pet_model")
                    if (customModelDir.exists()) {
                        val deleted = customModelDir.deleteRecursively()
                        Log.i(TAG, "卸载 desktop-pet: 清理 custom_pet_model ($deleted)")
                    }
                    // 同时清理 pet_floating_prefs（位置/大小/状态等）
                    val prefs = context.getSharedPreferences("pet_floating_prefs", Context.MODE_PRIVATE)
                    prefs.edit().clear().apply()
                    Log.i(TAG, "卸载 desktop-pet: 清理 pet_floating_prefs")
                } catch (e: Exception) {
                    Log.w(TAG, "清理 desktop-pet 自定义模型失败: ${e.message}")
                }
            }
        }
        return ok
    }

    /** 列出已启用的插件 ID（供运行时加载用） */
    fun listEnabledPluginIds(): List<String> {
        val state = readState()
        val out = mutableListOf<String>()
        baseDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory || dir.name == "_tmp") return@forEach
            if (state.optBoolean(dir.name, true) && File(dir, FILE_MAIN_JS).exists()) {
                out.add(dir.name)
            }
        }
        return out
    }
}
