package com.mineradio.app.manager

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云更新状态
 */
data class CloudUpdateState(
    val checking: Boolean = false,
    val updateAvailable: Boolean = false,
    val latestVersion: String = "",
    val downloadUrl: String = "",
    val releaseNote: String = "",
    val downloading: Boolean = false,
    val progress: Int = 0,
    val receivedBytes: Long = 0,
    val totalBytes: Long = 0,
    val speedBps: Long = 0,
    val etaSeconds: Int = 0,
    val error: String = "",
    val done: Boolean = false,
)

/**
 * 云更新管理器
 *
 * 提供版本检查、APK 下载、调用系统安装包管理器安装的功能。
 * 与横屏 HTML 中的云更新逻辑等价，但以原生 Kotlin 实现，
 * 供竖屏 Compose UI 直接调用。
 */
class CloudUpdateManager(
    private val appContext: Context,
) {
    /**
     * 云端更新 API 基础地址（腾讯云 CloudBase）
     */
    private val CLOUD_UPDATE_API_BASE =
        "https://plugin-market-d2gpn5vfb44d821b8.service.tcloudbase.com/plugin-api"

    /**
     * 云端更新文本拉取地址（与横屏 JS 中 UPDATE_URL 一致）
     * ★ 已换回 QQ 收藏链接作为更新数据源
     *   格式：[1]版本[1][2]下载地址[2][3]插件开关[3][4]公告[4][5]默认模型[5][6]登录插件[6]
     */
    private val UPDATE_URL = "https://sharechain.qq.com/79f7d5cf2654b3acc8e4e55e9bf024f6?qq_aio_chat_type=2"

    /**
     * 当前版本号（直接使用 APK BuildConfig.VERSION_NAME，确保与 APK 版本一致）
     */
    val CURRENT_VERSION: String =
        try {
            com.mineradio.app.BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            "1.1.4"
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow(CloudUpdateState())
    val state: StateFlow<CloudUpdateState> = _state.asStateFlow()

    /**
     * APK 保存路径：公共 Download 目录，卸载后仍保留
     */
    private val apkFile: File
        get() =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SPICaMusic_update.apk",
            )

    /**
     * 检查是否已下载过 APK（文件存在且大小 > 0）
     */
    fun hasDownloadedApk(): Boolean = apkFile.exists() && apkFile.length() > 0

    /**
     * 拉取云端更新信息并比较版本
     */
    fun checkForUpdate() {
        if (_state.value.checking) return
        _state.value = _state.value.copy(checking = true, error = "")
        scope.launch(Dispatchers.IO) {
            try {
                val text = fetchUpdateText(UPDATE_URL)
                // ★ 优先尝试 JSON 解析（新后端 /api/update 返回 JSON）
                val parsed = tryParseJson(text) ?: parseUpdateText(text)
                // ★ 只要版本号不一致即可更新（不再要求必须大于当前版本）
                val hasUpdate = compareVersion(parsed.version, CURRENT_VERSION) != 0
                // ★ 如果 APK 已下载完成，保留 done 状态
                val alreadyDone = hasDownloadedApk()
                _state.value =
                    _state.value.copy(
                        checking = false,
                        updateAvailable = hasUpdate,
                        latestVersion = parsed.version,
                        downloadUrl = parsed.downloadUrl,
                        releaseNote = parsed.releaseNote,
                        done = alreadyDone,
                        progress = if (alreadyDone) 100 else 0,
                    )
            } catch (e: Exception) {
                Timber.e(e, "checkForUpdate error")
                _state.value =
                    _state.value.copy(
                        checking = false,
                        error = e.message ?: "检查更新失败",
                    )
            }
        }
    }

    /**
     * 尝试解析后端返回的 JSON 格式更新信息
     * 返回 null 表示不是 JSON 格式，需要回退到文本解析
     */
    private fun tryParseJson(text: String): ParsedUpdate? {
        return try {
            val json = org.json.JSONObject(text)
            if (!json.optBoolean("ok") || !json.has("data")) return null
            val data = json.getJSONObject("data")
            val version = data.optString("version", "")
            // download_url 是相对路径，需要拼接 API_BASE
            var downloadUrl = data.optString("download_url", "")
            if (downloadUrl.isNotEmpty() && !downloadUrl.startsWith("http", ignoreCase = true)) {
                downloadUrl = CLOUD_UPDATE_API_BASE + downloadUrl
            }
            val announcement = data.optString("announcement", "")
            ParsedUpdate(version, downloadUrl, announcement)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 强制重新下载（删除已有 APK 后重新下载）
     */
    fun forceReDownload(activity: Activity) {
        try {
            if (apkFile.exists()) apkFile.delete()
        } catch (_: Exception) {
        }
        _state.value = _state.value.copy(done = false, progress = 0, error = "")
        startDownload(activity)
    }

    /**
     * ★ 蓝奏云直链解析（APP 端解析，手机网络不被 WAF 拦截）
     * CloudBase IP 被蓝奏云 WAF 持续拦截，无法在服务端解析直链
     * 改为 APP 端直接访问蓝奏云分享页解析直链
     */
    private fun resolveLanzouDirectUrl(shareUrl: String): String? {
        try {
            val conn =
                (URL(shareUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                    )
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                    instanceFollowRedirects = true
                }
            conn.connect()
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val finalUrl = conn.url.toString()
            conn.disconnect()

            // WAF 页面检测
            if (html.contains("acw_sc__v2")) {
                Timber.w("[lanzou] WAF page detected, cannot resolve on APP side")
                return null
            }

            // 新版分享页：提取 <a id="downurl" href="...">
            val downUrlPattern1 = Regex("""<a[^>]*id=["']downurl["'][^>]*href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val downUrlPattern2 = Regex("""<a[^>]*href=["']([^"']+)["'][^>]*id=["']downurl["']""", RegexOption.IGNORE_CASE)
            val downUrlMatch = downUrlPattern1.find(html) ?: downUrlPattern2.find(html)
            if (downUrlMatch != null) {
                var downloadUrl = downUrlMatch.groupValues[1]
                if (downloadUrl.startsWith("/")) {
                    val u = URL(finalUrl)
                    downloadUrl = "${u.protocol}://${u.host}$downloadUrl"
                }
                Timber.i("[lanzou] Found downurl: ${downloadUrl.take(80)}")
                return resolveLanzouMidPage(downloadUrl, finalUrl)
            }

            // 旧版分享页：提取 sign + veid
            val signMatch = Regex("""var\s+sign\s*=\s*['"]([^'"]+)['"]""").find(html)
            val veidMatch = Regex("""var\s+veid\s*=\s*['"]([^'"]+)['"]""").find(html)
            if (signMatch != null && veidMatch != null) {
                Timber.i("[lanzou] Found sign=${signMatch.groupValues[1]}, veid=${veidMatch.groupValues[1]}")
                return resolveLanzouAjax(finalUrl, signMatch.groupValues[1], veidMatch.groupValues[1])
            }

            Timber.w("[lanzou] No download params found, html_len=${html.length}")
            return null
        } catch (e: Exception) {
            Timber.e(e, "[lanzou] resolveLanzouDirectUrl error")
            return null
        }
    }

    /** 解析蓝奏云中间下载页，提取真正直链 */
    private fun resolveLanzouMidPage(
        midUrl: String,
        referer: String,
    ): String? {
        try {
            val conn =
                (URL(midUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                    )
                    setRequestProperty("Referer", referer)
                    instanceFollowRedirects = true
                }
            conn.connect()
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()

            // 提取 vkjxld + hyggid + lanosso
            val vkjxldMatch = Regex("""var\s+vkjxld\s*=\s*['"]([^'"]+)['"]""").find(html)
            val hyggidMatch = Regex("""var\s+hyggid\s*=\s*['"]([^'"]+)['"]""").find(html)
            val lanossoMatch = Regex("""var\s+lanosso\s*=\s*['"]([^'"]*)['"]""").find(html)
            if (vkjxldMatch != null && hyggidMatch != null) {
                val pseudoUrl = vkjxldMatch.groupValues[1] + hyggidMatch.groupValues[1] + (lanossoMatch?.groupValues?.get(1) ?: "")
                Timber.i("[lanzou] Found pseudo URL: ${pseudoUrl.take(80)}")
                return pseudoUrl
            }
            // 兜底：查找 /file/ 路径
            val fileUrlMatch = Regex("""https?://[^"'\s<>]+/file/[^"'\s<>]+""").find(html)
            if (fileUrlMatch != null) return fileUrlMatch.value

            return null
        } catch (e: Exception) {
            Timber.e(e, "[lanzou] resolveLanzouMidPage error")
            return null
        }
    }

    /** 通过 ajaxm.php 获取蓝奏云下载直链（旧版分享页） */
    private fun resolveLanzouAjax(
        shareUrl: String,
        sign: String,
        veid: String,
    ): String? {
        try {
            val u = URL(shareUrl)
            val ajaxUrl = "${u.protocol}://${u.host}/ajaxm.php"
            val conn =
                (URL(ajaxUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                    )
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    setRequestProperty("Referer", shareUrl)
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                }
            val params = "action=downprocess&sign=$sign&veid=$veid"
            conn.outputStream.use { it.write(params.toByteArray()) }
            conn.connect()
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val jsonStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()

            val json = JSONObject(jsonStr)
            if (json.optInt("zt", 0) != 1) return null
            val dom = json.optString("dom", "")
            val urlPart = json.optString("url", "")
            if (dom.isNotEmpty() && urlPart.isNotEmpty()) return "$dom/file/$urlPart"
            return null
        } catch (e: Exception) {
            Timber.e(e, "[lanzou] resolveLanzouAjax error")
            return null
        }
    }

    /**
     * 开始下载 APK（完成后不自动安装，由用户手动点击安装）
     */
    fun startDownload(activity: Activity) {
        val url = _state.value.downloadUrl
        if (url.isBlank()) {
            _state.value = _state.value.copy(error = "下载地址无效")
            return
        }
        if (downloadJob?.isActive == true) return
        // ★ 如果 APK 已存在，直接标记为已下载，不重新下载
        if (hasDownloadedApk()) {
            _state.value =
                _state.value.copy(
                    downloading = false,
                    done = true,
                    progress = 100,
                    error = "",
                )
            return
        }
        _state.value =
            _state.value.copy(
                downloading = true,
                progress = 0,
                receivedBytes = 0,
                totalBytes = 0,
                speedBps = 0,
                etaSeconds = 0,
                error = "",
                done = false,
            )
        downloadJob =
            scope.launch(Dispatchers.IO) {
                // ★ 保存到公共 Download 目录，卸载后仍保留
                val target = apkFile
                target.parentFile?.mkdirs()
                try {
                    // ★ 蓝奏云分享链接检测：如果是蓝奏云链接，先在 APP 端解析直链
                    //   CloudBase IP 被蓝奏云 WAF 持续拦截，服务端无法解析
                    var downloadUrl = url
                    val isLanzou =
                        url.contains("lanzoup.com") ||
                            url.contains("lanzou") ||
                            Regex("lanzou[a-z]\\.").containsMatchIn(url)
                    if (isLanzou) {
                        Timber.i("[update] Detected Lanzou URL, resolving direct link: ${url.take(80)}")
                        val directUrl = resolveLanzouDirectUrl(url)
                        if (directUrl != null) {
                            downloadUrl = directUrl
                            Timber.i("[update] Lanzou resolved: ${downloadUrl.take(80)}")
                        } else {
                            // 解析失败，用浏览器打开分享页让用户手动下载
                            Timber.w("[update] Lanzou resolve failed, opening browser")
                            try {
                                val intent =
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                activity.startActivity(intent)
                            } catch (_: Exception) {
                            }
                            _state.value =
                                _state.value.copy(
                                    downloading = false,
                                    done = true,
                                    error = "蓝奏云直链解析失败，已打开浏览器请手动下载",
                                )
                            return@launch
                        }
                    }

                    val urlObj = URL(downloadUrl)
                    val conn = urlObj.openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.setRequestProperty("User-Agent", "Mineradio-Android")
                    conn.instanceFollowRedirects = true
                    conn.connect()
                    if (conn.responseCode != 200) {
                        _state.value =
                            _state.value.copy(
                                downloading = false,
                                done = true,
                                error = "HTTP ${conn.responseCode}",
                            )
                        conn.disconnect()
                        return@launch
                    }
                    val total = conn.contentLengthLong
                    val input = conn.inputStream
                    val output = FileOutputStream(target)
                    val buffer = ByteArray(8192)
                    var received = 0L
                    var lastReportTime = System.currentTimeMillis()
                    val startTime = lastReportTime
                    try {
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            received += read
                            val now = System.currentTimeMillis()
                            if (now - lastReportTime >= 300) {
                                val elapsed = (now - startTime).coerceAtLeast(1)
                                val speedBps = if (elapsed > 0) (received * 1000 / elapsed) else 0L
                                val progress = if (total > 0) ((received * 100 / total).toInt()) else 0
                                val eta = if (speedBps > 0 && total > 0) ((total - received) / speedBps).toInt() else 0
                                _state.value =
                                    _state.value.copy(
                                        progress = progress,
                                        receivedBytes = received,
                                        totalBytes = total,
                                        speedBps = speedBps,
                                        etaSeconds = eta,
                                    )
                                lastReportTime = now
                            }
                        }
                        output.flush()
                    } finally {
                        try {
                            output.close()
                        } catch (_: Exception) {
                        }
                        try {
                            input.close()
                        } catch (_: Exception) {
                        }
                        conn.disconnect()
                    }
                    // ★ 下载完成后不自动安装，仅更新状态
                    _state.value =
                        _state.value.copy(
                            downloading = false,
                            done = true,
                            progress = 100,
                            receivedBytes = received,
                            totalBytes = total,
                        )
                } catch (e: Exception) {
                    Timber.e(e, "downloadUpdateApk error")
                    _state.value =
                        _state.value.copy(
                            downloading = false,
                            done = true,
                            error = e.message ?: "下载失败",
                        )
                }
            }
    }

    /**
     * 取消下载（删除部分下载的文件）
     */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        // 删除部分下载的文件
        try {
            if (apkFile.exists()) apkFile.delete()
        } catch (_: Exception) {
        }
        _state.value =
            _state.value.copy(
                downloading = false,
                done = false,
                progress = 0,
                receivedBytes = 0,
                totalBytes = 0,
                speedBps = 0,
                etaSeconds = 0,
                error = "",
            )
    }

    /**
     * 安装已下载的 APK（手动触发）
     */
    fun installDownloadedApk(activity: Activity) {
        installApkSafely(activity, apkFile)
    }

    /**
     * 调用系统安装包管理器安装 APK
     */
    private fun installApkSafely(
        activity: Activity,
        apkFile: File,
    ) {
        try {
            if (!apkFile.exists()) {
                Toast.makeText(activity, "APK 文件不存在", Toast.LENGTH_SHORT).show()
                return
            }
            val uri: Uri =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        apkFile,
                    )
                } else {
                    Uri.fromFile(apkFile)
                }
            val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            if (intent.resolveActivity(activity.packageManager) != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!activity.packageManager.canRequestPackageInstalls()) {
                        val permIntent =
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:${activity.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        activity.startActivity(permIntent)
                        Toast.makeText(activity, "请先允许安装未知来源应用，然后再次尝试", Toast.LENGTH_LONG).show()
                        return
                    }
                }
                activity.startActivity(intent)
            } else {
                Toast.makeText(activity, "未找到可用的安装程序", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Timber.e(e, "installApk error")
            Toast.makeText(activity, "安装失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 重置状态（取消下载中任务，但保留已下载完成的 APK 状态）
     */
    fun resetState() {
        downloadJob?.cancel()
        downloadJob = null
        // ★ 如果 APK 已下载完成，保留 done 状态；否则清空
        if (hasDownloadedApk()) {
            _state.value =
                _state.value.copy(
                    checking = false,
                    downloading = false,
                    progress = 100,
                    done = true,
                    error = "",
                )
        } else {
            _state.value = CloudUpdateState()
        }
    }

    // ── 内部工具方法（移植自 JS 全局状态与配置.js）──

    private fun fetchUpdateText(url: String): String {
        val urlObj = URL(url)
        val conn = urlObj.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "Mineradio-Android/1.1")
        conn.instanceFollowRedirects = true
        conn.connect()
        if (conn.responseCode != 200) {
            conn.disconnect()
            throw RuntimeException("HTTP ${conn.responseCode}")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return text
    }

    private data class ParsedUpdate(
        val version: String = "",
        val downloadUrl: String = "",
        val releaseNote: String = "",
    )

    /**
     * 解析云端文本：兼容 [1]ver[1] [2]url[2] 与 [版本号]..[链接].. 等格式
     * 完全对标横屏 JS 的 parseUpdateText 逻辑
     */
    private fun parseUpdateText(text: String): ParsedUpdate {
        var t = text
        // 剥离 HTML 标签和实体（与 JS 一致）
        t =
            t
                .replace(Regex("<[^>]*>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")

        var version = ""
        var downloadUrl = ""
        var releaseNote = ""

        // [1]版本[1]（与 JS 一致：\s* 匹配首尾空格）
        Regex("""\[1]\s*(.+?)\s*\[1]""", RegexOption.DOT_MATCHES_ALL).find(t)?.let {
            version = it.groupValues[1].trim()
        }
        // [版本号]...[版本号]
        if (version.isBlank()) {
            Regex("""\[版本号]\s*(.+?)\s*\[版本号]""", RegexOption.DOT_MATCHES_ALL).find(t)?.let {
                version = it.groupValues[1].trim()
            }
        }
        // [版本]...[版本]
        if (version.isBlank()) {
            Regex("""\[版本]\s*(.+?)\s*\[版本]""", RegexOption.DOT_MATCHES_ALL).find(t)?.let {
                version = it.groupValues[1].trim()
            }
        }

        // [2]下载链接[2]
        Regex("""\[2]\s*(.+?)\s*\[2]""", RegexOption.DOT_MATCHES_ALL).find(t)?.let {
            downloadUrl = it.groupValues[1].trim()
        }
        // [链接]...[链接]
        if (downloadUrl.isBlank()) {
            Regex("""\[链接]\s*(.+?)\s*\[链接]""", RegexOption.DOT_MATCHES_ALL).find(t)?.let {
                downloadUrl = it.groupValues[1].trim()
            }
        }

        // 拼接下载基础前缀：云端的 [2] 只放 token，本地固定前缀（与 JS 一致）
        if (downloadUrl.isNotBlank() && !downloadUrl.startsWith("http", ignoreCase = true)) {
            downloadUrl = "http://wpan.cdndns.site/down/$downloadUrl"
        }

        // [公告]...[公告]
        Regex("""\[公告]\s*([\s\S]*?)\s*\[公告]""").find(t)?.let {
            releaseNote = it.groupValues[1].trim()
        }

        return ParsedUpdate(version, downloadUrl, releaseNote)
    }

    /**
     * 版本号比较：a > b 返回 1，a < b 返回 -1，相等返回 0
     */
    private fun compareVersion(
        a: String,
        b: String,
    ): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
