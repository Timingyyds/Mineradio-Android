package com.mineradio.android

import android.net.Uri
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * 扩展插件原生层（对应参考工程 manager/PluginManager.kt 的简化安全版本）。
 *
 * 目录：files/plugins/<pluginId>/
 *   manifest.json / main.js / entry.json / 资源文件
 * 状态：files/plugins/.state.json  { "<id>": true/false }
 *
 * 只做本地 zip 安装、启停、卸载与资源读取；加密 .mr 与联网插件市场不在本版本范围。
 */
@CapacitorPlugin(name = "MineradioPlugins")
class PluginsPlugin : Plugin() {

    private val io = Executors.newSingleThreadExecutor()

    private val rootDir: File
        get() = File(context.filesDir, "plugins").apply { mkdirs() }

    private val stateFile: File
        get() = File(rootDir, ".state.json")

    private fun readState(): JSONObject {
        return try {
            if (stateFile.exists()) JSONObject(stateFile.readText()) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun writeState(state: JSONObject) {
        try {
            stateFile.writeText(state.toString())
        } catch (e: Exception) {
            android.util.Log.w("PluginsPlugin", "writeState failed", e)
        }
    }

    private fun isEnabled(id: String): Boolean {
        val state = readState()
        return if (state.has(id)) state.optBoolean(id, true) else true
    }

    private fun setEnabledState(id: String, enabled: Boolean) {
        val state = readState()
        state.put(id, enabled)
        writeState(state)
    }

    private fun sanitizeId(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9._-]"), "")
        return cleaned.ifBlank { "plugin_" + System.currentTimeMillis() }
    }

    private fun pluginDir(id: String): File = File(rootDir, sanitizeId(id))

    private fun sanitizeFileName(raw: String): String {
        var n = raw.split("?")[0].split("#")[0].replace("/", "").replace("\\", "")
        n = n.replace(Regex("[^a-zA-Z0-9._-]"), "")
        return n.ifBlank { "plugin.zip" }
    }

    private fun manifestOf(dir: File): JSONObject? {
        val manifest = File(dir, "manifest.json")
        if (!manifest.exists()) return null
        return try {
            JSONObject(manifest.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun pluginInfo(dir: File, manifest: JSONObject?): JSObject {
        val id = dir.name
        val m = manifest
        val out = JSObject()
        out.put("id", id)
        out.put("name", m?.optString("name", id) ?: id)
        out.put("version", m?.optString("version", "1.0.0") ?: "1.0.0")
        out.put("author", m?.optString("author", "unknown") ?: "unknown")
        out.put("description", m?.optString("description", "") ?: "")
        out.put("entry", m?.optString("entry", "main.js") ?: "main.js")
        out.put("installed", true)
        out.put("enabled", isEnabled(id))
        return out
    }

    @PluginMethod
    fun list(call: PluginCall) {
        io.execute {
            try {
                val arr = JSArray()
                rootDir.listFiles()?.forEach { dir ->
                    if (!dir.isDirectory) return@forEach
                    val manifest = manifestOf(dir)
                    if (manifest == null) return@forEach
                    arr.put(pluginInfo(dir, manifest))
                }
                val out = JSObject()
                out.put("plugins", arr)
                call.resolve(out)
            } catch (e: Exception) {
                call.reject("list failed: " + e.message, e)
            }
        }
    }

    @PluginMethod
    fun setEnabled(call: PluginCall) {
        val id = sanitizeId(call.getString("id") ?: "")
        val enabled = call.getBoolean("enabled", true) ?: true
        if (!pluginDir(id).exists()) { call.reject("插件不存在: $id"); return }
        setEnabledState(id, enabled)
        val out = JSObject()
        out.put("ok", true)
        call.resolve(out)
    }

    @PluginMethod
    fun uninstall(call: PluginCall) {
        val id = sanitizeId(call.getString("id") ?: "")
        try {
            pluginDir(id).deleteRecursively()
            val state = readState()
            state.remove(id)
            writeState(state)
            val out = JSObject()
            out.put("ok", true)
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("uninstall failed: " + e.message, e)
        }
    }

    private fun safeChild(base: File, relPath: String): File? {
        return try {
            val child = File(base, relPath).canonicalFile
            val root = base.canonicalFile
            if (!child.path.startsWith(root.path + File.separator) && child.path != root.path) null else child
        } catch (e: Exception) {
            null
        }
    }

    @PluginMethod
    fun readMainJs(call: PluginCall) {
        val id = sanitizeId(call.getString("id") ?: "")
        val dir = pluginDir(id)
        val manifest = manifestOf(dir)
        val entry = manifest?.optString("entry", "main.js") ?: "main.js"
        val file = File(dir, "main.js").takeIf { it.exists() }
            ?: if (entry.endsWith(".js")) safeChild(dir, entry) else null
        val out = JSObject()
        out.put("text", if (file != null && file.exists()) file.readText() else "")
        call.resolve(out)
    }

    @PluginMethod
    fun writeMainJs(call: PluginCall) {
        val id = sanitizeId(call.getString("id") ?: "")
        val dir = pluginDir(id).apply { mkdirs() }
        try {
            File(dir, "main.js").writeText(call.getString("content") ?: "")
            val out = JSObject()
            out.put("ok", true)
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("writeMainJs failed: " + e.message, e)
        }
    }

    @PluginMethod
    fun readManifest(call: PluginCall) {
        val id = sanitizeId(call.getString("id") ?: "")
        val file = File(pluginDir(id), "manifest.json")
        val out = JSObject()
        out.put("text", if (file.exists()) file.readText() else "")
        call.resolve(out)
    }

    @PluginMethod
    fun readEntry(call: PluginCall) {
        val id = sanitizeId(call.getString("id") ?: "")
        val file = File(pluginDir(id), "entry.json")
        val out = JSObject()
        out.put("text", if (file.exists()) file.readText() else "")
        call.resolve(out)
    }

    @PluginMethod
    fun readResourceText(call: PluginCall) {
        val id = sanitizeId(call.getString("id") ?: "")
        val rel = call.getString("path") ?: ""
        val target = safeChild(pluginDir(id), rel)
        val out = JSObject()
        out.put("text", if (target != null && target.exists() && target.length() < 8_000_000L) target.readText() else "")
        call.resolve(out)
    }

    @PluginMethod
    fun getResourcePath(call: PluginCall) {
        val id = sanitizeId(call.getString("id") ?: "")
        val rel = call.getString("path") ?: ""
        val target = safeChild(pluginDir(id), rel)
        if (target == null || !target.exists()) {
            val out = JSObject()
            out.put("ok", false)
            call.resolve(out)
            return
        }
        val out = JSObject()
        out.put("path", target.absolutePath)
        call.resolve(out)
    }

    @PluginMethod
    fun listResources(call: PluginCall) {
        val id = sanitizeId(call.getString("id") ?: "")
        val dir = call.getString("dir") ?: ""
        val target = if (dir.isBlank()) pluginDir(id) else safeChild(pluginDir(id), dir)
        val arr = JSArray()
        target?.listFiles()?.forEach { f ->
            val item = JSObject()
            item.put("name", f.name)
            item.put("dir", f.isDirectory)
            item.put("size", if (f.isFile) f.length() else 0)
            arr.put(item)
        }
        call.resolve(JSObject().put("list", arr))
    }

    // ── 安装（SAF 选 zip） ─────────────────────────────

    @PluginMethod
    fun pickAndInstall(call: PluginCall) {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            // 部分文件管理器对 zip 的 MIME 识别不准，这里放宽到 */*
            setType("*/*")
        }
        try {
            startActivityForResult(call, intent, "onPluginZipPicked")
        } catch (e: Exception) {
            call.reject("无法打开文件选择器: " + e.message, e)
        }
    }

    @ActivityCallback
    private fun onPluginZipPicked(call: PluginCall, result: ActivityResult) {
        if (call == null) return
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            val out = JSObject()
            out.put("ok", false)
            out.put("message", "已取消")
            call.resolve(out)
            return
        }
        val uri: Uri = result.data?.data ?: run {
            val out = JSObject(); out.put("ok", false); out.put("message", "未选择文件"); call.resolve(out); return
        }
        io.execute {
            try {
                val out = installZip(uri)
                call.resolve(out)
            } catch (e: Exception) {
                android.util.Log.e("PluginsPlugin", "install failed", e)
                val out = JSObject()
                out.put("ok", false)
                out.put("message", e.message ?: "安装失败")
                call.resolve(out)
            }
        }
    }

    private fun installZip(uri: Uri): JSObject {
        val tempZip = File(context.cacheDir, "plugin_${System.currentTimeMillis()}.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempZip.outputStream().use { input.copyTo(it) }
        } ?: throw IOException("无法读取所选文件")

        val extractDir = File(context.cacheDir, "plugin_extract_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            unzip(tempZip, extractDir)
            val manifestFile = findManifest(extractDir)
                ?: throw IOException("压缩包缺少 manifest.json")
            val manifest = JSONObject(manifestFile.readText())
            val id = sanitizeId(manifest.optString("id").ifBlank { tempZip.nameWithoutExtension })
            val sourceDir = manifestFile.parentFile ?: extractDir
            val target = pluginDir(id)
            if (target.exists()) target.deleteRecursively()
            target.mkdirs()
            sourceDir.listFiles()?.forEach { child ->
                copyRecursively(child, File(target, child.name))
            }
            setEnabledState(id, true)
            val out = JSObject()
            out.put("ok", true)
            out.put("pluginId", id)
            out.put("name", manifest.optString("name", id))
            return out
        } finally {
            tempZip.delete()
            extractDir.deleteRecursively()
        }
    }

    private class IOException(message: String) : Exception(message)

    private fun findManifest(dir: File): File? {
        val direct = File(dir, "manifest.json")
        if (direct.exists()) return direct
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val nested = findManifest(child)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                if (name.contains("..")) { zis.closeEntry(); continue }
                val outFile = File(targetDir, name)
                val canonical = outFile.canonicalFile
                if (!canonical.path.startsWith(targetDir.canonicalFile.path + File.separator)
                    && canonical.path != targetDir.canonicalFile.path) {
                    zis.closeEntry(); continue
                }
                if (entry.isDirectory) {
                    canonical.mkdirs()
                } else {
                    canonical.parentFile?.mkdirs()
                    canonical.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }

    private fun copyRecursively(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyRecursively(it, File(dst, it.name)) }
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }

    // ═══════════════════════════════════════════════════════
    //  在线插件：读取蓝奏云文件夹，列出名称并下载安装
    //  文件夹：由前端传入 folderUrl（发布版不内置任何网盘地址）
    // ═══════════════════════════════════════════════════════

    companion object {
        private const val ONLINE_UA =
            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        @Volatile
        var onlineProgress: Int = 0

        @Volatile
        var onlineStatus: String = ""
    }

    @PluginMethod
    fun listOnlinePlugins(call: PluginCall) {
        val folderUrl = call.getString("folderUrl") ?: ""
        if (folderUrl.isEmpty()) { call.reject("folderUrl required"); return }
        io.execute {
            try {
                val files = fetchLanzouFolder(folderUrl)
                val arr = JSArray()
                files.forEach { item ->
                    val o = JSObject()
                    o.put("name", item.name)
                    o.put("size", item.size)
                    o.put("time", item.time)
                    o.put("url", item.pageUrl)
                    arr.put(o)
                }
                val out = JSObject()
                out.put("ok", true)
                out.put("files", arr)
                out.put("total", arr.length())
                call.resolve(out)
            } catch (e: Exception) {
                android.util.Log.e("PluginsPlugin", "listOnlinePlugins failed", e)
                val out = JSObject()
                out.put("ok", false)
                out.put("error", e.message ?: "读取失败")
                call.resolve(out)
            }
        }
    }

    @PluginMethod
    fun getOnlineInstallProgress(call: PluginCall) {
        val out = JSObject()
        out.put("progress", onlineProgress)
        out.put("status", onlineStatus)
        call.resolve(out)
    }

    @PluginMethod
    fun installOnlinePlugin(call: PluginCall) {
        val pageUrl = call.getString("url") ?: ""
        val name = call.getString("name") ?: "plugin.zip"
        if (pageUrl.isEmpty()) { call.reject("url required"); return }
        io.execute {
            try {
                onlineProgress = 0
                onlineStatus = "正在解析下载地址..."
                val direct = resolveLanzouDirectUrl(pageUrl)
                    ?: throw IOException("无法解析下载地址（链接可能失效或需要密码）")
                val zipName = sanitizeFileName(name).let {
                    if (it.lowercase().endsWith(".zip")) it else "$it.zip"
                }
                val zip = File(context.cacheDir, "online_${System.currentTimeMillis()}_$zipName")

                // 蓝奏云 CDN 可能返回反爬挑战页 /「验证并下载」页，
                // downloadBinary 内部会算 Cookie 或换真实地址后重试。
                val cookies = HashMap<String, String>()
                onlineStatus = "正在下载..."
                downloadBinary(direct, zip, pageUrl, cookies) { pct ->
                    onlineProgress = pct
                    onlineStatus = "正在下载 $pct%"
                }
                onlineStatus = "正在安装..."
                onlineProgress = 99
                val result = installZip(Uri.fromFile(zip))
                zip.delete()
                onlineProgress = 100
                if (result.optBoolean("ok", false)) {
                    onlineStatus = "安装完成"
                } else {
                    onlineStatus = "安装失败：" + result.optString("message", "未知错误")
                    result.put("ok", false)
                }
                call.resolve(result)
            } catch (e: Exception) {
                android.util.Log.e("PluginsPlugin", "installOnlinePlugin failed", e)
                onlineStatus = "安装失败：" + (e.message ?: "未知错误")
                val out = JSObject()
                out.put("ok", false)
                out.put("message", e.message ?: "安装失败")
                call.resolve(out)
            }
        }
    }

    private data class OnlineFile(val name: String, val size: String, val time: String, val pageUrl: String)

    /** 文件夹接口偶尔会短暂返回「没有文件」，这里最多重试 3 次，避免一次抖动就显示空列表。 */
    private fun fetchLanzouFolder(folderUrl: String): List<OnlineFile> {
        repeat(3) { attempt ->
            val files = try { fetchLanzouFolderOnce(folderUrl) } catch (e: Exception) {
                if (attempt == 2) throw e else emptyList()
            }
            if (files.isNotEmpty()) return files
            try { Thread.sleep(500 + attempt * 500L) } catch (e: Exception) { }
        }
        return emptyList()
    }

    /** 读取蓝奏云「文件夹分享」页：先抓页面里的 fid/uid/puid/t/k，再 POST filemoreajax.php 分页取列表。 */
    private fun fetchLanzouFolderOnce(folderUrl: String): List<OnlineFile> {
        val base = java.net.URL(folderUrl)
        val root = "${base.protocol}://${base.host}"
        val cookies = HashMap<String, String>()
        val html = httpGetChallengeAware(folderUrl, null, cookies)
        fun pick(pattern: String): String? =
            Regex(pattern).find(html)?.groupValues?.getOrNull(1)

        val fid = pick("""'fid'\s*:\s*(\d+)""") ?: pick("""file=(\d+)""")
            ?: throw IOException("分享页里找不到文件夹 ID")
        val uid = pick("""'uid'\s*:\s*'([^']*)'""") ?: ""
        val puid = pick("""'puid'\s*:\s*'([^']*)'""") ?: ""

        // 蓝奏云会轮换变量名（ibh4az/_hfgcs、ibj9gr/_hhlik…），
        // 从 ajax 参数里拿到变量名，再在页面里查对应的值。
        fun varRef(param: String): String? =
            pick("""'$param'\s*:\s*(\w+)""")?.let { name ->
                pick("""var\s+${Regex.escape(name)}\s*=\s*'([^']*)'""")
            }
        val t = varRef("t") ?: pick("""var\s+ibh4az\s*=\s*'([^']*)'""") ?: ""
        val k = varRef("k") ?: pick("""var\s+_hfgcs\s*=\s*'([^']*)'""") ?: ""

        val files = mutableListOf<OnlineFile>()
        for (pg in 1..20) {
            val body = "lx=2&fid=$fid&uid=$uid&puid=$puid&pg=$pg&rep=0&t=$t&k=$k" +
                "&up=1&vip=1&webfoldersign="
            val json = httpPostChallengeAware("$root/filemoreajax.php?file=$fid", body, folderUrl, cookies)
            val obj = JSONObject(json)
            if (obj.optInt("zt", 0) != 1) break
            val arr = obj.optJSONArray("text") ?: break
            var added = 0
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optInt("t", 0) == 1) continue
                val id = item.optString("id", "")
                if (id.isEmpty() || id == "-1") continue
                val rawName = item.optString("name_all", item.optString("name", ""))
                val name = cleanHtml(rawName)
                if (name.isBlank()) continue
                val pageUrl = if (id.startsWith("http")) id else "$root/" + id.trimStart('/')
                files.add(OnlineFile(name, item.optString("size", ""), item.optString("time", ""), pageUrl))
                added++
            }
            if (added < 50) break
        }
        return files
    }

    private fun cleanHtml(raw: String): String {
        var s = raw.replace(Regex("<[^>]+>"), "")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        return s.trim()
    }

    /** GET；遇到蓝奏云的 acw_sc__v2 反爬挑战页时自行算出 Cookie 再重试一次。 */
    private fun httpGet(url: String, referer: String?): String {
        val html = httpGetRaw(url, referer, null)
        if (html.contains("arg1=") && html.contains("acw_sc__v2")) {
            val cookie = solveAcwScV2(html)
            if (!cookie.isNullOrEmpty()) {
                return httpGetRaw(url, referer, cookie)
            }
        }
        return html
    }

    private fun httpGetRaw(url: String, referer: String?, cookie: String?): String {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ONLINE_UA)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            if (referer != null) setRequestProperty("Referer", referer)
            if (cookie != null) setRequestProperty("Cookie", cookie)
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return readResponseText(conn)
        } finally {
            conn.disconnect()
        }
    }

    /** 服务器可能无视请求头直接返回 gzip，这里统一按 Content-Encoding 解压。 */
    private fun readResponseText(conn: java.net.HttpURLConnection): String {
        val encoding = conn.getHeaderField("Content-Encoding") ?: ""
        val stream = if (encoding.contains("gzip", ignoreCase = true)) {
            java.util.zip.GZIPInputStream(conn.inputStream)
        } else {
            conn.inputStream
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * 计算蓝奏云 acw_sc__v2 Cookie。
     * 页面里的挑战脚本会按固定置换表重排 arg1，再与固定 key 逐字节 XOR。
     */
    private fun solveAcwScV2(html: String): String? {
        return try {
            val arg1 = Regex("""var\s+arg1\s*=\s*['"]([^'"]+)['"]""").find(html)
                ?.groupValues?.get(1) ?: return null
            val order = intArrayOf(
                0xf, 0x23, 0x1d, 0x18, 0x21, 0x10, 0x1, 0x26, 0xa, 0x9,
                0x13, 0x1f, 0x28, 0x1b, 0x16, 0x17, 0x19, 0xd, 0x6, 0xb,
                0x27, 0x12, 0x14, 0x8, 0xe, 0x15, 0x20, 0x1a, 0x2, 0x1e,
                0x7, 0x4, 0x11, 0x5, 0x3, 0x1c, 0x22, 0x25, 0xc, 0x24,
            )
            val key = "3000176000856006061501533003690027800375"
            val q = CharArray(order.size)
            for (x in arg1.indices) {
                for (z in order.indices) {
                    if (order[z] == x + 1) q[z] = arg1[x]
                }
            }
            val u = q.concatToString()
            val maxLen = minOf(u.length, key.length)
            val sb = StringBuilder()
            var i = 0
            while (i < maxLen) {
                val a = u.substring(i, i + 2).toInt(16)
                val b = key.substring(i, i + 2).toInt(16)
                sb.append((a xor b).toString(16).padStart(2, '0'))
                i += 2
            }
            "acw_sc__v2=$sb"
        } catch (e: Exception) {
            null
        }
    }

    private fun httpPost(url: String, body: String, referer: String): String {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", ONLINE_UA)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Referer", referer)
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return readResponseText(conn)
        } finally {
            conn.disconnect()
        }
    }

    // ── 蓝奏云文件页 → 直链（复用 OnlineWallpaperApi 同一套解析逻辑） ──

    private fun resolveLanzouDirectUrl(shareUrl: String): String? {
        return try {
            // httpGet 会自动处理 acw_sc__v2 反爬挑战并重试
            val html = httpGet(shareUrl, null)
            val base = shareUrl

            // 新版文件页：var urlpt = 'https://xxx/file/'; var link = '?...';
            val urlpt = Regex("""var\s+urlpt\s*=\s*['"]([^'"]+)['"]""").find(html)
            val link = Regex("""var\s+link\s*=\s*['"]([^'"]+)['"]""").find(html)
            if (urlpt != null && link != null) {
                return urlpt.groupValues[1] + link.groupValues[1]
            }

            Regex("""<a[^>]*id=["']downurl["'][^>]*href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.let { m ->
                    var dl = m.groupValues[1]
                    if (dl.startsWith("/")) dl = java.net.URL(base).let { "${it.protocol}://${it.host}$dl" }
                    return resolveLanzouMidPage(dl, base)
                }
            Regex("""var\s+sign\s*=\s*['"]([^'"]+)['"]""").find(html)?.let { sm ->
                val vm = Regex("""var\s+veid\s*=\s*['"]([^'"]+)['"]""").find(html)
                if (vm != null) return resolveLanzouAjax(base, sm.groupValues[1], vm.groupValues[1])
            }
            // 新版 iframe 页
            Regex("""<iframe[^>]*src=["']([^"']*\/fn\?[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.let { m ->
                    var src = m.groupValues[1]
                    if (src.startsWith("/")) src = java.net.URL(base).let { "${it.protocol}://${it.host}$src" }
                    return resolveLanzouFnPage(src, base)
                }
            null
        } catch (e: Exception) {
            android.util.Log.e("PluginsPlugin", "resolveLanzouDirectUrl failed", e)
            null
        }
    }

    private fun resolveLanzouFnPage(fnUrl: String, referer: String): String? {
        return try {
            val html = httpGet(fnUrl, referer)
            val ajaxMatch = Regex("""var\s+ajaxdata\s*=\s*'([^']+)'""").find(html)
            val signMatch = Regex("""'sign'\s*:\s*'([^']+)'""").find(html)
                ?: Regex("""var\s+sign\s*=\s*'([^']+)'""").find(html)
            val sign = signMatch?.groupValues?.get(1)
                ?: ajaxMatch?.let { m ->
                    try {
                        JSONObject(String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT))).optString("sign", "")
                    } catch (e: Exception) { "" }
                } ?: ""
            if (sign.isNullOrEmpty()) return null
            val u = java.net.URL(fnUrl)
            val ajaxUrl = "${u.protocol}://${u.host}/ajaxm.php"
            val body = "action=downprocess&sign=$sign&websign=&websignkey=&ves=1"
            val json = httpPost(ajaxUrl, body, referer)
            val obj = JSONObject(json)
            if (obj.optInt("zt", 0) != 1) return null
            val dom = obj.optString("dom", "")
            val url = obj.optString("url", "")
            if (dom.isNotEmpty() && url.isNotEmpty()) "$dom/file/$url" else null
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveLanzouMidPage(midUrl: String, referer: String): String? {
        return try {
            val html = httpGet(midUrl, referer)
            val v = Regex("""var\s+vkjxld\s*=\s*['"]([^'"]+)['"]""").find(html)
            val h = Regex("""var\s+hyggid\s*=\s*['"]([^'"]+)['"]""").find(html)
            val l = Regex("""var\s+lanosso\s*=\s*['"]([^'"]*)['"]""").find(html)
            if (v != null && h != null) return v.groupValues[1] + h.groupValues[1] + (l?.groupValues?.get(1) ?: "")
            Regex("""https?://[^"'\s<>]+/file/[^"'\s<>]+""").find(html)?.value
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveLanzouAjax(shareUrl: String, sign: String, veid: String): String? {
        return try {
            val u = java.net.URL(shareUrl)
            val json = httpPost("${u.protocol}://${u.host}/ajaxm.php",
                "action=downprocess&sign=$sign&veid=$veid", shareUrl)
            val obj = JSONObject(json)
            if (obj.optInt("zt", 0) != 1) return null
            val dom = obj.optString("dom", "")
            val url = obj.optString("url", "")
            if (dom.isNotEmpty() && url.isNotEmpty()) "$dom/file/$url" else null
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadFile(urlStr: String, target: File, onProgress: ((Int) -> Unit)?) {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 10) {
            val conn = (java.net.URL(currentUrl).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30000
                readTimeout = 60000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", ONLINE_UA)
                setRequestProperty("Accept", "application/zip,application/octet-stream,*/*")
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrEmpty()) throw IOException("HTTP $code 缺少 Location")
                    currentUrl = if (location.startsWith("http")) location
                    else java.net.URL(java.net.URL(currentUrl), location).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                val total = conn.contentLengthLong
                conn.inputStream.buffered(262144).use { input ->
                    target.outputStream().buffered(262144).use { output ->
                        val buffer = ByteArray(262144)
                        var read: Int
                        var downloaded = 0L
                        var lastPct = -1
                        while (true) {
                            read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt().coerceIn(0, 99)
                                if (pct != lastPct) { lastPct = pct; onProgress?.invoke(pct) }
                            }
                        }
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("重定向次数过多")
    }

    // ── CDN 验证下载链路（带 acw_sc__v2 Cookie 的 GET/POST/二进制下载） ──

    private fun hostOf(url: String): String = try { java.net.URL(url).host } catch (e: Exception) { "" }

    private fun httpGetChallengeAware(url: String, referer: String?, cookies: HashMap<String, String>): String {
        val host = hostOf(url)
        var html = httpGetRaw(url, referer, cookies[host])
        if (html.contains("arg1=") && html.contains("acw_sc__v2")) {
            val cookie = solveAcwScV2(html)
            if (!cookie.isNullOrEmpty()) {
                cookies[host] = cookie
                html = httpGetRaw(url, referer, cookie)
            }
        }
        return html
    }

    private fun httpPostChallengeAware(
        url: String,
        body: String,
        referer: String,
        cookies: HashMap<String, String>,
    ): String {
        val host = hostOf(url)
        var html = httpPostRaw(url, body, referer, cookies[host])
        if (html.contains("arg1=") && html.contains("acw_sc__v2")) {
            val cookie = solveAcwScV2(html)
            if (!cookie.isNullOrEmpty()) {
                cookies[host] = cookie
                html = httpPostRaw(url, body, referer, cookie)
            }
        }
        return html
    }

    private fun httpPostRaw(url: String, body: String, referer: String, cookie: String?): String {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", ONLINE_UA)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Referer", referer)
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return readResponseText(conn)
        } finally {
            conn.disconnect()
        }
    }

    /** 解析 CDN「验证并下载」页，POST ajax.php 拿真实下载地址。 */
    private fun resolveCdnVerifyUrl(
        landing: String,
        directUrl: String,
        referer: String,
        cookies: HashMap<String, String>,
    ): String? {
        return try {
            val fileMatch = Regex("""file\s*['"]?\s*[:=]\s*['"]([^'"]+)['"]""").find(landing)
            val signMatch = Regex("""sign\s*['"]?\s*[:=]\s*['"]([^'"]+)['"]""").find(landing)
            if (fileMatch == null || signMatch == null) {
                android.util.Log.d(
                    "PluginsPlugin",
                    "verify page not matched: file=${fileMatch != null} sign=${signMatch != null} " +
                        "cookie=${cookies.values.joinToString(",")} " +
                        "body=${landing.take(3000).replace("\n", " ")}",
                )
                return null
            }
            val file = fileMatch.groupValues[1]
            val sign = signMatch.groupValues[1]
            val base = java.net.URL(directUrl).let { "${it.protocol}://${it.host}" }
            val ajaxUrl = java.net.URL(java.net.URL(directUrl), "ajax.php").toString()
            val body = "file=" + java.net.URLEncoder.encode(file, "UTF-8") +
                "&el=1&sign=" + java.net.URLEncoder.encode(sign, "UTF-8")
            val json = httpPostChallengeAware(ajaxUrl, body, directUrl, cookies)
            val obj = JSONObject(json)
            var url = obj.optString("url", "")
            if (url.isBlank()) return null
            if (url.startsWith("//")) url = "https:$url"
            else if (url.startsWith("/")) url = base + url
            url
        } catch (e: Exception) {
            android.util.Log.w("PluginsPlugin", "resolveCdnVerifyUrl failed", e)
            null
        }
    }

    private fun downloadBinary(
        urlStr: String,
        target: File,
        referer: String,
        cookies: HashMap<String, String>,
        onProgress: ((Int) -> Unit)?,
    ) {
        var currentUrl = urlStr
        var redirects = 0
        var softRetries = 0
        while (redirects < 10) {
            val host = hostOf(currentUrl)
            val cookie = cookies[host]
            val conn = (java.net.URL(currentUrl).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30000
                readTimeout = 60000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", ONLINE_UA)
                setRequestProperty("Accept", "application/zip,application/octet-stream,*/*")
                setRequestProperty("Accept-Encoding", "identity")
                if (referer.isNotEmpty()) setRequestProperty("Referer", referer)
                if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
            }
            var handled = false
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrEmpty()) throw IOException("HTTP $code 缺少 Location")
                    currentUrl = if (location.startsWith("http")) location
                    else java.net.URL(java.net.URL(currentUrl), location).toString()
                    redirects++
                    handled = true
                    continue
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                val type = conn.getHeaderField("Content-Type") ?: ""
                if (type.contains("text/html", ignoreCase = true)) {
                    val html = readResponseText(conn)
                    handled = true
                    android.util.Log.d(
                        "PluginsPlugin",
                        "downloadBinary html: url=${currentUrl.take(90)} code=$code len=${html.length} " +
                            "cookie=${if (cookie.isNullOrEmpty()) "none" else cookie.take(16)} " +
                            "headers=${conn.headerFields.entries.joinToString("|") { (k, v) -> "$k=${v.joinToString(";")}" }} " +
                            "head=${html.take(60).replace("\n", " ")}",
                    )
                    // 1) 反爬挑战：算出 Cookie 后重试
                    val solved = solveAcwScV2(html)
                    if (!solved.isNullOrEmpty()) {
                        cookies[host] = solved
                        redirects++
                        continue
                    }
                    // 2)「验证并下载」页：POST ajax.php 换真实地址
                    val verified = resolveCdnVerifyUrl(html, currentUrl, referer, cookies)
                    if (!verified.isNullOrEmpty()) {
                        currentUrl = verified
                        redirects++
                        continue
                    }
                    // 3) CDN 的「加载中」页里直接写着真实下载地址（window.location.href / 立即下载 a 标签）
                    val jump = Regex("""window\.location\.href\s*=\s*["']([^"']+)["']""").find(html)
                        ?.groupValues?.get(1)
                        ?: Regex("""id=["']ding["'][^>]*href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            .find(html)?.groupValues?.get(1)
                        ?: Regex("""href=["'](https?://[^"']+\.zip[^"']*)["']""", RegexOption.IGNORE_CASE)
                            .find(html)?.groupValues?.get(1)
                    if (!jump.isNullOrEmpty()) {
                        currentUrl = jump.replace("&amp;", "&")
                        redirects++
                        continue
                    }
                    // 4) 等 CDN 校验状态就绪后再请求一次
                    if (softRetries < 3) {
                        softRetries++
                        try { Thread.sleep(600) } catch (e: Exception) { }
                        continue
                    }
                    throw IOException("下载验证失败（CDN 拒绝）")
                }
                val total = conn.contentLengthLong
                conn.inputStream.buffered(262144).use { input ->
                    target.outputStream().buffered(262144).use { output ->
                        val buffer = ByteArray(262144)
                        var read: Int
                        var downloaded = 0L
                        var lastPct = -1
                        while (true) {
                            read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt().coerceIn(0, 99)
                                if (pct != lastPct) { lastPct = pct; onProgress?.invoke(pct) }
                            }
                        }
                    }
                }
                return
            } finally {
                if (!handled) { /* 已 return 或 throw */ }
                conn.disconnect()
            }
        }
        throw IOException("下载重定向次数过多")
    }

    override fun handleOnDestroy() {
        io.shutdown()
        super.handleOnDestroy()
    }
}
