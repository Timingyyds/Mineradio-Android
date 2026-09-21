package com.mineradio.app.wallpaper

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 在线壁纸 API - 解析 moewalls.com / haowallpaper.com / bizhihui.com
 *
 * moewalls.com 是 WordPress 站点，使用 bimber 主题（视频壁纸源）。
 *
 * 主要路径：
 * - 首页分页：/page/N/
 * - 搜索分页：/page/N/?s=keyword
 * - 详情页：/{category}/{slug}/
 * - 缩略图：/wp-content/uploads/{year}/{month}/{slug}-thumb.jpg (1920x1080)
 * - 预览视频：/wp-content/uploads/preview/{year}/{slug}-preview.webm (低画质预览)
 * - 高清下载：https://go.moewalls.com/download.php?video={data-url} (4K 原画 ~17MB)
 *
 * 详情页中的下载按钮：
 *   <a id="moe-download" data-id="20755" data-url="ENCRYPTED">Download Wallpaper</a>
 * JS 解密（custom-wall.js 反混淆后）：
 *   finalDL = "https://go.moewalls.com/download.php?video=" + data-url
 *
 * bizhihui.com 是自定义 PHP 站点（图片壁纸源），无 JSON API，全部走 HTML 抓取。
 * - 分类首页：/{slug}/ 或 /{slug}/?order=hot
 * - 分类分页：/{slug}/{N}/?order=hot（N 从 2 开始）
 * - 搜索：/?s={keyword} 或 /page/{N}/?s={keyword}
 * - 详情页：/p/{id}.html（注意：实际是 /p/，不是 /show/）
 * - 缩略图 CDN：https://aka.doubaocdn.com/s/{hash}.jpg
 *
 * moewalls.com 还支持 WordPress REST API：
 * - 分类列表：/wp-json/wp/v2/categories?per_page=100
 * - 文章列表：/wp-json/wp/v2/posts?categories={catId}&per_page=100&page={N}
 *   响应头 X-WP-Total / X-WP-TotalPages 分别为总文章数与总页数
 */
class OnlineWallpaperApi(
    private val context: Context,
) {
    companion object {
        private const val TAG = "OnlineWallpaperApi"
        private const val BASE_URL = "https://moewalls.com"
        private const val DOWNLOAD_BASE = "https://go.moewalls.com"
        private const val HAO_BASE_URL = "https://haowallpaper.com"
        private const val BIZHIHUI_BASE_URL = "https://www.bizhihui.com"

        // ★ 用户上传 .mpkg 壁纸服务端 API 基础 URL（CloudBase 云函数）
        private const val SERVER_API_BASE = "https://plugin-market-d2gpn5vfb44d821b8.service.tcloudbase.com/plugin-api"
        private const val UA = "Mozilla/5.0 (Linux; Android 10; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        // 当前下载进度（0-100），供前端轮询查询
        @Volatile
        @JvmStatic
        var currentProgress: Int = 0

        // 当前下载状态文本
        @Volatile
        @JvmStatic
        var currentStatus: String = ""

        // bizhihui.com 分类 slug → 中文名 映射（图片壁纸源）
        // 来源：站点首页侧栏分类导航
        private val BIZHIHUI_CATEGORIES =
            linkedMapOf(
                "dongman" to "卡通动漫",
                "renwu" to "人物画照",
                "fengjing" to "风景静物",
                "yingshi" to "影视体育",
                "youxi" to "游戏视觉",
                "meishi" to "美食果蔬",
                "weimei" to "唯美治愈",
                "mengchong" to "动物萌宠",
                "yishu" to "艺术绘画",
                "yuzhou" to "宇宙星空",
                "keji" to "军事科技",
                "jianyue" to "简约主义",
                "jiche" to "机车器械",
                "qita" to "其它风格",
                "tuijian" to "精选推荐",
            )

        // bizhihui.com 尺寸标签 slug → 中文名 映射
        private val BIZHIHUI_SIZES =
            linkedMapOf(
                "1080P" to "1080P 壁纸",
                "2Kbizhi" to "2K 壁纸",
                "4Kbizhi" to "4K 壁纸",
                "8Kbizhi" to "8K 壁纸",
            )

        // moewalls.com WP REST API 分类 slug → 中文名 映射
        // 站点分类名为英文，这里翻译为中文供前端展示
        private val MOEWALLS_CATEGORY_CN =
            linkedMapOf(
                "abstract" to "抽象",
                "animal" to "动物",
                "anime" to "动漫",
                "fantasy" to "奇幻",
                "games" to "游戏",
                "landscape" to "风景",
                "nature" to "自然",
                "space" to "太空",
                "neon" to "霓虹",
                "cyberpunk" to "赛博朋克",
                "girls" to "女孩",
                "boys" to "男孩",
                "movies" to "影视",
                "cars" to "汽车",
                "flowers" to "花卉",
                "water" to "水流",
                "fire" to "火焰",
                "rain" to "雨天",
                "snow" to "雪景",
                "holiday" to "节日",
            )
    }

    /**
     * 获取壁纸列表
     * @param page 页码（从 1 开始）
     * @param keyword 搜索关键词，为空时获取首页推荐
     * @param source 壁纸源（moewalls / haowallpaper / bizhihui）
     * @param category 分类筛选（可选）。moewalls 传分类 ID（数字字符串）；bizhihui 传 slug；
     *                haowallpaper 暂不支持分类筛选，传入会被忽略。
     * @return JSON: {list: [...], page: N, hasMore: bool, totalPages: Int, total: Int}
     */
    fun getList(
        page: Int,
        keyword: String?,
        source: String = "moewalls",
        category: String? = null,
        resolution: String? = null,
    ): JSONObject {
        // ★ 用户上传 .mpkg 壁纸源（场景壁纸）
        if (source == "usermpkg") {
            return getUserMpkgList(page, keyword)
        }
        if (source == "haowallpaper") {
            return getHaoList(page, keyword)
        }
        if (source == "bizhihui") {
            return getBizhihuiList(page, keyword, category)
        }
        // moewalls：当传入分类 ID 时走 WordPress REST API 查询，否则保持原 HTML 抓取逻辑
        if (!category.isNullOrBlank()) {
            return getMoewallsListByCategory(page, category, resolution)
        }
        // ★ 分辨率独立筛选（无种类时也可单独按分辨率筛选）
        if (!resolution.isNullOrBlank()) {
            return getMoewallsListByCategory(page, category, resolution)
        }
        val url =
            if (keyword.isNullOrBlank()) {
                if (page <= 1) "$BASE_URL/" else "$BASE_URL/page/$page/"
            } else {
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                if (page <= 1) "$BASE_URL/?s=$encoded" else "$BASE_URL/page/$page/?s=$encoded"
            }
        Log.i(TAG, "getList: page=$page, keyword=$keyword, url=$url")

        val html = fetchHtml(url)
        val list = parseWallpaperList(html)
        return JSONObject().apply {
            put("list", list)
            put("page", page)
            put("hasMore", list.length() >= 6)
            put("totalPages", if (list.length() >= 6) -1 else 1) // -1 表示未知，前端用 hasMore 判断
            put("total", list.length())
        }
    }

    /**
     * 获取指定源的可用分类列表
     * @param source 壁纸源（moewalls / bizhihui）
     * @return JSONArray：每项 {id, name, slug, count}
     *         - moewalls：id 为 WP REST API 分类 ID（数字字符串），name 为中文翻译
     *         - bizhihui：id 与 slug 相同，name 为中文分类名
     */
    fun getCategories(source: String): JSONArray =
        when (source) {
            "bizhihui" -> {
                val arr = JSONArray()
                BIZHIHUI_CATEGORIES.forEach { (slug, cn) ->
                    arr.put(
                        JSONObject().apply {
                            put("id", slug)
                            put("slug", slug)
                            put("name", cn)
                            put("count", 0)
                        },
                    )
                }
                arr
            }
            "haowallpaper" -> JSONArray() // haowallpaper 暂无分类列表
            "usermpkg" -> JSONArray() // 用户上传源无分类筛选
            else -> getMoewallsCategories()
        }

    /**
     * 获取指定源的可用分辨率列表
     * @param source 壁纸源
     * @return JSONArray：每项 {id, name, slug, count}
     *         - moewalls：尝试 WP REST API resolution 自定义分类法；失败则返回内置分辨率
     *         - bizhihui：返回尺寸标签（4K/8K/1080P/2K）
     */
    fun getResolutions(source: String): JSONArray =
        when (source) {
            "bizhihui" -> {
                val arr = JSONArray()
                BIZHIHUI_SIZES.forEach { (slug, cn) ->
                    arr.put(
                        JSONObject().apply {
                            put("id", slug)
                            put("slug", slug)
                            put("name", cn)
                            put("count", 0)
                        },
                    )
                }
                arr
            }
            "haowallpaper" -> JSONArray()
            "usermpkg" -> JSONArray() // 用户上传源无分辨率筛选
            else -> getMoewallsResolutions()
        }

    /**
     * 获取壁纸详情页（提取下载用的 data-url）
     * @param detailUrl 详情页完整 URL（例如 https://moewalls.com/anime/xxx-live-wallpaper/）
     * @param source 壁纸源（moewalls / haowallpaper）
     * @return JSON: {detailUrl, downloadUrl, title, resolution, type, fileSize}
     */
    fun getDetail(
        detailUrl: String,
        source: String = "moewalls",
    ): JSONObject {
        if (source == "haowallpaper") {
            return getHaoDetail(detailUrl)
        }
        if (source == "bizhihui") {
            return getBizhihuiDetail(detailUrl)
        }
        // ★ 用户上传源无需详情页，下载直接用 id 作为参数
        if (source == "usermpkg") {
            return JSONObject().apply {
                put("detailUrl", detailUrl)
                put("type", "scene")
            }
        }
        Log.i(TAG, "getDetail: url=$detailUrl")
        val html = fetchHtml(detailUrl)
        return parseWallpaperDetail(html, detailUrl)
    }

    /**
     * 下载壁纸到本地并导入壁纸库
     *
     * 流程：
     * 1. 获取详情页解析出 data-url 加密串
     * 2. 拼接 https://go.moewalls.com/download.php?video={data-url}
     * 3. 下载 MP4 文件（4K 原画，~17MB）
     * 4. 导入壁纸库
     *
     * @param detailUrl 详情页 URL（作为壁纸 ID）
     * @param name 壁纸名称
     * @param type 类型（保留兼容，实际从详情页判断）
     * @param onProgress 进度回调（0-100）
     * @return 下载成功后的本地 WallpaperEntity，失败返回 null
     */
    fun downloadAndImport(
        detailUrl: String,
        name: String,
        type: Int,
        source: String = "moewalls",
        fileIdParam: String = "",
        onProgress: ((Int) -> Unit)? = null,
    ): com.mineradio.app.wallpaper.WallpaperEntity? {
        // ★ 用户上传 .mpkg 壁纸下载分支
        if (source == "usermpkg") {
            return downloadUserMpkgAndImport(detailUrl, name, type, fileIdParam, onProgress)
        }
        if (source == "haowallpaper") {
            return downloadHaoAndImport(detailUrl, name, type, fileIdParam, onProgress)
        }
        if (source == "bizhihui") {
            return downloadBizhihuiAndImport(detailUrl, name, type, fileIdParam, onProgress)
        }
        // 重置进度
        currentProgress = 0
        currentStatus = "正在获取详情页"
        // 1. 获取详情页提取 data-url
        val detail = getDetail(detailUrl)
        val dataUrl = detail.optString("dataUrl", "")
        val title = detail.optString("title", name)
        val resolution = detail.optString("resolution", "")
        val fileSize = detail.optString("fileSize", "")

        if (dataUrl.isEmpty()) {
            Log.e(TAG, "downloadAndImport: no data-url found in detail page: $detailUrl")
            currentStatus = "失败：未找到下载链接"
            return null
        }

        // 2. 拼接下载 URL
        val downloadUrl = "$DOWNLOAD_BASE/download.php?video=$dataUrl"
        Log.i(TAG, "downloadAndImport: title=$title, resolution=$resolution, fileSize=$fileSize, downloadUrl=${downloadUrl.take(120)}")

        return try {
            currentStatus = "正在下载 $title"
            // 3. 下载到本地（mp4 文件）
            val wallpaperIdLocal = "wp_online_" + System.currentTimeMillis()
            // ★ 存到外部公共目录：清理应用数据不会删除壁纸
            val docsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
            val targetDir = File(docsDir, "Mineradio/Wallpaper/$wallpaperIdLocal").apply { mkdirs() }
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "wallpaper" }
            val fileName = "$safeName.mp4"
            val targetFile = File(targetDir, fileName)

            // 合并进度回调：同时更新静态变量和外部回调
            val mergedProgress: ((Int) -> Unit)? = { p ->
                currentProgress = p
                onProgress?.invoke(p)
            }
            downloadFile(downloadUrl, targetFile, mergedProgress)
            onProgress?.invoke(100)
            currentProgress = 100
            currentStatus = "下载完成，正在导入..."

            // 4. 创建 WallpaperEntity 并加入壁纸库
            val wallpaper =
                WallpaperEntity(
                    author = "moewalls.com",
                    description = "在线下载 $resolution $fileSize",
                    id = wallpaperIdLocal,
                    name = safeName,
                    path = fileName,
                    versionCode = 1,
                    versionName = "1.0",
                    wallpaperType = WallpaperEntity.TYPE_VIDEO,
                )
            val wm = WallpaperManager.get(context)
            wm.addWallpaper(wallpaper)
            wm.setCurrentWallpaper(wallpaper)
            Log.i(TAG, "downloadAndImport: imported $safeName, file size=${targetFile.length()}")
            currentStatus = "导入完成"
            wallpaper
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndImport failed: ${e.message}", e)
            currentStatus = "下载失败：${e.message}"
            null
        }
    }

    /**
     * 请求 HTML 页面
     *
     * Referer 根据请求域名自动选择，避免 haowallpaper 因 Referer 不匹配而拒绝服务
     */
    private fun fetchHtml(urlStr: String): String {
        val referer =
            when {
                urlStr.contains("haowallpaper.com") -> "$HAO_BASE_URL/"
                urlStr.contains("bizhihui.com") -> "$BIZHIHUI_BASE_URL/"
                urlStr.contains("moewalls.com") -> "$BASE_URL/"
                else -> "$BASE_URL/"
            }
        val conn =
            (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                setRequestProperty("Referer", referer)
                instanceFollowRedirects = true
            }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "fetchHtml: HTTP $code for $urlStr")
                return ""
            }
            val encoding = conn.contentEncoding ?: "utf-8"
            val charset =
                try {
                    java.nio.charset.Charset
                        .forName(encoding)
                } catch (_: Exception) {
                    Charsets.UTF_8
                }
            return conn.inputStream.bufferedReader(charset).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载文件到本地（支持进度回调）
     *
     * ★ 手动处理 302 重定向，避免跨域名时 Referer 头导致蓝奏云 CDN 拒绝下载
     */
    // ★ 蓝奏云直链解析（APP 端解析，手机网络不被 WAF 拦截）
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
            if (html.contains("acw_sc__v2")) return null

            // 新版分享页：提取 <a id="downurl" href="...">
            val p1 = Regex("""<a[^>]*id=["']downurl["'][^>]*href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val p2 = Regex("""<a[^>]*href=["']([^"']+)["'][^>]*id=["']downurl["']""", RegexOption.IGNORE_CASE)
            val m = p1.find(html) ?: p2.find(html)
            if (m != null) {
                var dl = m.groupValues[1]
                if (dl.startsWith("/")) {
                    val u = URL(finalUrl)
                    dl = "${u.protocol}://${u.host}$dl"
                }
                return resolveLanzouMidPage(dl, finalUrl)
            }
            // 旧版分享页：sign + veid
            val sm = Regex("""var\s+sign\s*=\s*['"]([^'"]+)['"]""").find(html)
            val vm = Regex("""var\s+veid\s*=\s*['"]([^'"]+)['"]""").find(html)
            if (sm != null && vm != null) return resolveLanzouAjax(finalUrl, sm.groupValues[1], vm.groupValues[1])
            return null
        } catch (e: Exception) {
            Log.e(TAG, "resolveLanzouDirectUrl error", e)
            return null
        }
    }

    private fun resolveLanzouMidPage(
        midUrl: String,
        referer: String,
    ): String? {
        try {
            val conn =
                (URL(midUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
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
            val v = Regex("""var\s+vkjxld\s*=\s*['"]([^'"]+)['"]""").find(html)
            val h = Regex("""var\s+hyggid\s*=\s*['"]([^'"]+)['"]""").find(html)
            val l = Regex("""var\s+lanosso\s*=\s*['"]([^'"]*)['"]""").find(html)
            if (v != null && h != null) return v.groupValues[1] + h.groupValues[1] + (l?.groupValues?.get(1) ?: "")
            val f = Regex("""https?://[^"'\s<>]+/file/[^"'\s<>]+""").find(html)
            return f?.value
        } catch (e: Exception) {
            return null
        }
    }

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
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    setRequestProperty("Referer", shareUrl)
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                }
            conn.outputStream.use { it.write("action=downprocess&sign=$sign&veid=$veid".toByteArray()) }
            conn.connect()
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val j = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            conn.disconnect()
            if (j.optInt("zt", 0) != 1) return null
            val dom = j.optString("dom", "")
            val url = j.optString("url", "")
            return if (dom.isNotEmpty() && url.isNotEmpty()) "$dom/file/$url" else null
        } catch (e: Exception) {
            return null
        }
    }

    private fun downloadFile(
        urlStr: String,
        targetFile: File,
        onProgress: ((Int) -> Unit)?,
    ) {
        var currentUrl = urlStr
        var redirectCount = 0
        val maxRedirects = 10

        while (redirectCount < maxRedirects) {
            // Referer 根据当前域名自动选择
            val referer = refererForUrl(currentUrl)
            val conn =
                (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 30000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", UA)
                    if (referer.isNotEmpty()) setRequestProperty("Referer", referer)
                    setRequestProperty("Accept", "video/webm,video/ogg,video/mp4,video/*;q=0.9,*/*;q=0.5")
                    setRequestProperty("Connection", "keep-alive")
                    setRequestProperty("Accept-Encoding", "identity")
                    instanceFollowRedirects = false
                    useCaches = false
                }
            try {
                val code = conn.responseCode
                // 处理重定向
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrEmpty()) {
                        throw java.io.IOException("HTTP $code 但缺少 Location 头")
                    }
                    currentUrl = if (location.startsWith("http")) location else URL(URL(currentUrl), location).toString()
                    redirectCount++
                    Log.i(TAG, "downloadFile: redirect ($code) -> $currentUrl")
                    continue
                }
                if (code !in 200..299) {
                    throw java.io.IOException("HTTP $code for $currentUrl")
                }
                val total = conn.contentLengthLong
                Log.i(TAG, "downloadFile: HTTP $code, Content-Length=$total, url=${currentUrl.take(120)}")
                java.io.BufferedInputStream(conn.inputStream, 256 * 1024).use { bis ->
                    java.io.BufferedOutputStream(FileOutputStream(targetFile), 256 * 1024).use { fos ->
                        val buffer = ByteArray(256 * 1024)
                        var read: Int
                        var downloaded = 0L
                        var lastPct = -1
                        var lastReportTime = System.currentTimeMillis()
                        while (true) {
                            read = bis.read(buffer)
                            if (read <= 0) break
                            fos.write(buffer, 0, read)
                            downloaded += read
                            if (onProgress != null) {
                                if (total > 0) {
                                    // 已知总长度：按百分比上报
                                    val pct = (downloaded * 100 / total).toInt().coerceIn(0, 99)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        onProgress(pct)
                                    }
                                } else {
                                    // 未知总长度（蓝奏云 CDN 常见）：每 500ms 基于已下载字节数上报
                                    // 使用"不确定进度"模式：进度在 1-95 之间循环递增，让用户看到下载在进行
                                    val now = System.currentTimeMillis()
                                    if (now - lastReportTime >= 500) {
                                        lastReportTime = now
                                        // 基于已下载量缓慢递增，上限 95（留 5% 给完成）
                                        val mb = downloaded / (1024.0 * 1024.0)
                                        // 每 1MB 增加 5%，上限 95
                                        val pct = (5 + (mb * 5).toInt()).coerceIn(1, 95)
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            onProgress(pct)
                                        }
                                    }
                                }
                            }
                        }
                        fos.flush()
                        Log.i(TAG, "downloadFile: DONE, downloaded=$downloaded bytes, file=${targetFile.name}")
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
        throw java.io.IOException("重定向次数过多（>$maxRedirects）")
    }

    /**
     * 根据域名返回合适的 Referer
     *   - 蓝奏云 CDN 域名不传 Referer（避免被拒）
     *   - 自家服务器不传 Referer
     *   - 其他壁纸源保持原 Referer 策略
     */
    private fun refererForUrl(urlStr: String): String =
        when {
            // 蓝奏云相关域名（CDN + 分享页）
            urlStr.contains("lanzou") ||
                urlStr.contains("woozooo") ||
                urlStr.contains("lanzoui") ||
                urlStr.contains("lanzoup") ||
                urlStr.contains("lanzoux") ||
                urlStr.contains("lanzoue") ||
                urlStr.contains("lanzouw") ||
                urlStr.contains("lanzouk") ||
                urlStr.contains("lanzoum") ||
                urlStr.contains("lanzouj") ||
                urlStr.contains("lanzouy") ||
                urlStr.contains("lanzoub") ||
                urlStr.contains("lanzoun") ||
                urlStr.contains("lanzouf") ||
                urlStr.contains("dmpdmp.com") ||
                // 蓝奏云 CDN 下载域名
                urlStr.contains("bakstotre.com") ||
                // 蓝奏云最终下载域名（webgetstore.com / webstore.dl）
                urlStr.contains("webgetstore.com") ||
                urlStr.contains("webstore.dl") ||
                // 蓝奏云静态资源域名
                urlStr.contains("ilanzou.com") -> "" // 蓝奏云 API 域名
            // 自家服务器
            urlStr.contains(SERVER_API_BASE) -> ""
            // 壁纸源
            urlStr.contains("haowallpaper.com") -> "$HAO_BASE_URL/"
            urlStr.contains("bizhihui.com") -> "$BIZHIHUI_BASE_URL/"
            urlStr.contains("doubaocdn.com") -> "$BIZHIHUI_BASE_URL/"
            urlStr.contains("moewalls.com") -> "$BASE_URL/"
            else -> "$BASE_URL/"
        }

    /**
     * 解析壁纸列表 HTML
     *
     * 列表项结构（每个 <article> 为一张壁纸）：
     * <article class="entry-tpl-grid ... post-XXXXX ... category-XXX ... resolutions-WxH">
     *   <div class="entry-featured-media">
     *     <a title="标题" href="详情URL">
     *       <img src="缩略图URL" srcset="..." />
     *     </a>
     *   </div>
     *   <div class="entry-resolutions"><a>3840x2160</a></div>
     *   <div class="entry-body">
     *     <header>
     *       <span class="entry-votes"><strong>5</strong> Votes</span>
     *       <a>分类名</a>
     *       <h3><a href="详情URL">标题</a></h3>
     *     </header>
     *   </div>
     * </article>
     */
    private fun parseWallpaperList(html: String): JSONArray {
        val list = JSONArray()
        if (html.isEmpty()) return list

        // 找到所有 <article class="entry-tpl-grid ... post-XXXXX ..."> 块
        val articlePattern =
            Regex(
                """<article[^>]*class="entry-tpl-grid[^"]*post-(\d+)[^"]*"[^>]*>""",
                RegexOption.IGNORE_CASE,
            )
        val articles = articlePattern.findAll(html).toList()
        Log.d(TAG, "parseWallpaperList: found ${articles.size} articles")
        if (articles.isEmpty()) return list

        val seenIds = HashSet<String>()
        for (articleMatch in articles) {
            val postId = articleMatch.groupValues[1]
            if (postId in seenIds) continue
            seenIds.add(postId)

            val start = articleMatch.range.first
            // 截取该 article 块内容（直到 </article>）
            val endIdx = html.indexOf("</article>", start, ignoreCase = true)
            val end = if (endIdx > 0) endIdx + "</article>".length else html.length
            val segment = html.substring(start, end)

            try {
                val item = parseWallpaperListItem(segment, postId)
                if (item != null) list.put(item)
            } catch (e: Exception) {
                Log.w(TAG, "parseWallpaperList: failed for post-$postId: ${e.message}")
            }
        }
        Log.d(TAG, "parseWallpaperList: parsed ${list.length()} items")
        return list
    }

    /**
     * 解析单个壁纸列表项
     */
    private fun parseWallpaperListItem(
        segment: String,
        postId: String,
    ): JSONObject? {
        // 提取标题与详情URL（优先 entry-featured-media 的 <a title>）
        val titlePattern =
            Regex(
                """<div class="entry-featured-media[^"]*"[^>]*>\s*<a[^>]*title="([^"]*)"[^>]*href="([^"]+)""",
                RegexOption.IGNORE_CASE,
            )
        val titleMatch = titlePattern.find(segment)
        var title = titleMatch?.groupValues?.get(1) ?: ""
        var detailUrl = titleMatch?.groupValues?.get(2) ?: ""

        // 降级：从 <h3> 标签提取
        if (title.isEmpty()) {
            val h3Pattern =
                Regex(
                    """<h3[^>]*entry-title[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>([^<]+)</a>""",
                    RegexOption.IGNORE_CASE,
                )
            val h3Match = h3Pattern.find(segment)
            if (h3Match != null) {
                detailUrl = h3Match.groupValues[1]
                title = h3Match.groupValues[2].trim()
            }
        }

        if (detailUrl.isEmpty()) return null

        // 提取缩略图（优先 728x410 中等尺寸）
        val thumbPattern =
            Regex(
                """srcset="([^"]*thumb-728x410\.jpg[^"]*)"""",
                RegexOption.IGNORE_CASE,
            )
        val thumbSrc = thumbPattern.find(segment)?.groupValues?.get(1) ?: ""
        // 降级：img src
        val imgSrcPattern =
            Regex(
                """<img[^>]*src="(https://moewalls\.com/wp-content/uploads/[^"]*thumb[^"]*)" """,
                RegexOption.IGNORE_CASE,
            )
        val imgSrc = imgSrcPattern.find(segment)?.groupValues?.get(1) ?: thumbSrc
        // 兜底：构造默认缩略图（如果有 srcset）
        val thumbnail = imgSrc.ifEmpty { "" }

        // 提取分辨率
        val resPattern =
            Regex(
                """<div class="entry-resolutions"[^>]*>\s*<a[^>]*>([^<]+)</a>""",
                RegexOption.IGNORE_CASE,
            )
        val resolution =
            resPattern
                .find(segment)
                ?.groupValues
                ?.get(1)
                ?.trim() ?: ""

        // 从 article class 提取 resolutions-WxH 作为备选
        val resolutionFromClasses =
            if (resolution.isEmpty()) {
                val resClassPattern = Regex("""resolutions-(\d+x\d+)""")
                resClassPattern.find(segment)?.groupValues?.get(1) ?: ""
            } else {
                resolution
            }

        // 解析分辨率中的 W 与 H，用于判定手机壁纸（H > W）
        var width = 0
        var height = 0
        if (resolutionFromClasses.isNotEmpty()) {
            val parts = resolutionFromClasses.split("x", ignoreCase = true)
            if (parts.size == 2) {
                width = parts[0].trim().toIntOrNull() ?: 0
                height = parts[1].trim().substringBefore(" ").toIntOrNull() ?: 0
            }
        }
        val isPhone = width > 0 && height > 0 && height > width

        // 提取分类
        val catPattern =
            Regex(
                """<a[^>]*class="entry-category[^"]*"[^>]*>([^<]+)</a>""",
                RegexOption.IGNORE_CASE,
            )
        val category =
            catPattern
                .find(segment)
                ?.groupValues
                ?.get(1)
                ?.trim() ?: ""

        // 提取投票数
        val votesPattern =
            Regex(
                """<span class="entry-votes[^"]*"[^>]*>\s*<strong>(\d+)</strong>""",
                RegexOption.IGNORE_CASE,
            )
        val votes = votesPattern.find(segment)?.groupValues?.get(1) ?: "0"

        return JSONObject().apply {
            put("id", detailUrl) // 使用详情页 URL 作为 ID
            put("postId", postId)
            put("title", title.ifEmpty { "Wallpaper $postId" })
            put("thumbnail", thumbnail)
            put("detailUrl", detailUrl)
            put("resolution", resolutionFromClasses)
            put("width", width)
            put("height", height)
            put("isPhone", isPhone) // 手机壁纸：高 > 宽
            put("category", category)
            put("votes", votes)
            put("type", "video") // moewalls 全是视频壁纸
        }
    }

    /**
     * 解析壁纸详情页
     *
     * 关键信息：
     * - 下载按钮：<a id="moe-download" data-id="20755" data-url="ENCRYPTED">
     * - 分辨率：<li><em>Resolution</em>3840x2160</li>
     * - 文件大小：<li><em>File Size</em>17.4 MB</li>
     */
    private fun parseWallpaperDetail(
        html: String,
        detailUrl: String,
    ): JSONObject {
        val result =
            JSONObject().apply {
                put("detailUrl", detailUrl)
                put("title", "")
                put("dataUrl", "")
                put("resolution", "")
                put("fileSize", "")
                put("type", "video")
            }
        if (html.isEmpty()) return result

        // 1. 提取下载按钮的 data-url（关键）
        val moeDownloadPattern =
            Regex(
                """<a[^>]*id="moe-download"[^>]*data-url="([^"]+)"[^>]*>""",
                RegexOption.IGNORE_CASE,
            )
        moeDownloadPattern.find(html)?.let {
            result.put("dataUrl", it.groupValues[1])
        }

        // 2. 提取标题（og:title 或 <h1>）
        val ogTitlePattern =
            Regex("""<meta property="og:title" content="([^"]+)"""", RegexOption.IGNORE_CASE)
        ogTitlePattern.find(html)?.let {
            result.put("title", it.groupValues[1].trim())
        }
        if (result.optString("title").isEmpty()) {
            val h1Pattern = Regex("""<h1[^>]*>([^<]+)</h1>""", RegexOption.IGNORE_CASE)
            h1Pattern.find(html)?.let {
                result.put("title", it.groupValues[1].trim())
            }
        }

        // 3. 提取分辨率（从 <li><em>Resolution</em>...</li>）
        val resLiPattern =
            Regex(
                """<li>[^<]*<i[^>]*></i><em>Resolution</em>([^<]+)</li>""",
                RegexOption.IGNORE_CASE,
            )
        resLiPattern.find(html)?.let {
            val res = it.groupValues[1].trim()
            if (res.isNotEmpty()) result.put("resolution", res)
        }
        // 降级：从 entry-resolutions
        if (result.optString("resolution").isEmpty()) {
            val resDivPattern =
                Regex(
                    """<div class="entry-resolutions"[^>]*>\s*<a[^>]*>([^<]+)</a>""",
                    RegexOption.IGNORE_CASE,
                )
            resDivPattern.find(html)?.let {
                result.put("resolution", it.groupValues[1].trim())
            }
        }

        // 4. 提取文件大小（<li><em>File Size</em>17.4 MB</li>）
        val fileSizePattern =
            Regex(
                """<li>[^<]*<i[^>]*></i><em>File Size</em>([^<]+)</li>""",
                RegexOption.IGNORE_CASE,
            )
        fileSizePattern.find(html)?.let {
            result.put("fileSize", it.groupValues[1].trim())
        }

        // 5. 提取缩略图（og:image）
        val ogImagePattern =
            Regex("""<meta property="og:image" content="([^"]+)"""", RegexOption.IGNORE_CASE)
        ogImagePattern.find(html)?.let {
            result.put("thumbnail", it.groupValues[1])
        }

        Log.i(
            TAG,
            "parseWallpaperDetail: title=${result.optString("title")}, resolution=${result.optString("resolution")}, " +
                "fileSize=${result.optString("fileSize")}, dataUrl.length=${result.optString("dataUrl").length}",
        )

        return result
    }

    // ============================================================
    // 用户上传 .mpkg 壁纸源（usermpkg，场景壁纸）
    // 服务端 API 基于 CloudBase 云函数，提供列表/封面/下载接口
    // ============================================================

    /**
     * 获取用户上传 .mpkg 壁纸列表
     *
     * GET $SERVER_API_BASE/api/wallpapers?page={page}&limit=20&search={keyword}
     * 返回：{ok, data:{items:[{id,title,description,author_name,file_size,download_count,has_cover,created_at}], total, page, limit, totalPages}}
     *
     * 每个 item 字段映射：
     * - id = item.id（下载时作为参数）
     * - title = item.title
     * - thumbnail = "$SERVER_API_BASE/api/wallpapers/cover?id=${item.id}"（有封面时）
     * - detailUrl = item.id（下载用 id 作为参数）
     * - type = "scene"（场景壁纸）
     * - author = item.author_name
     * - resolution = 格式化文件大小
     */
    private fun getUserMpkgList(
        page: Int,
        keyword: String?,
    ): JSONObject {
        val encoded = if (keyword.isNullOrBlank()) "" else URLEncoder.encode(keyword, "UTF-8")
        val url =
            "$SERVER_API_BASE/api/wallpapers?page=$page&limit=20" +
                (if (encoded.isNotEmpty()) "&search=$encoded" else "")
        Log.i(TAG, "getUserMpkgList: page=$page, keyword=$keyword, url=$url")

        val (body, _) = fetchJsonWithHeaders(url)
        val list = JSONArray()
        var total = 0
        var totalPages = 0
        if (body.isNotEmpty()) {
            try {
                val resp = JSONObject(body)
                val data = resp.optJSONObject("data")
                if (data != null) {
                    total = data.optInt("total", 0)
                    totalPages = data.optInt("totalPages", 0)
                    val items = data.optJSONArray("items")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val id = item.optString("id", "")
                            if (id.isEmpty()) continue
                            val title = item.optString("title", "").ifEmpty { "用户壁纸 $id" }
                            val hasCover = item.optBoolean("has_cover", false)
                            // ★ 使用路径参数方式（/cover/{id}）而非查询参数（/cover?id=xxx），
                            //   避免 CloudBase queryString 在某些情况下未正确传递 id 参数
                            val thumbnail = if (hasCover) "$SERVER_API_BASE/api/wallpapers/cover/$id" else ""
                            val fileSize = item.optLong("file_size", 0)
                            list.put(
                                JSONObject().apply {
                                    put("id", id)
                                    put("title", title)
                                    put("thumbnail", thumbnail)
                                    put("detailUrl", id) // 下载时用 id 作为参数
                                    put("type", "scene") // 场景壁纸
                                    put("author", item.optString("author_name", "用户上传"))
                                    put("resolution", formatFileSize(fileSize))
                                    put("fileSize", formatFileSize(fileSize))
                                    put("downloadCount", item.optInt("download_count", 0))
                                    put("description", item.optString("description", ""))
                                    put("createdAt", item.optString("created_at", ""))
                                    put("source", "usermpkg")
                                    put("width", 0)
                                    put("height", 0)
                                    put("isPhone", false)
                                },
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getUserMpkgList: parse failed: ${e.message}", e)
            }
        }
        val hasMore = if (totalPages > 0) page < totalPages else list.length() >= 20
        Log.i(TAG, "getUserMpkgList: parsed ${list.length()} items, total=$total, totalPages=$totalPages, hasMore=$hasMore")
        return JSONObject().apply {
            put("list", list)
            put("page", page)
            put("hasMore", hasMore)
            put("totalPages", totalPages)
            put("total", total)
        }
    }

    /**
     * 格式化文件大小（字节 → 可读字符串）
     */
    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    /**
     * 下载用户上传的 .mpkg 壁纸到本地并导入壁纸库
     *
     * 流程：
     * 1. 构造下载 URL: $SERVER_API_BASE/api/wallpapers/download?id=$id（302 重定向到 COS）
     * 2. 流式下载 .mpkg 文件到本地
     * 3. 创建 WallpaperEntity(type=TYPE_MPKG_WEBGL, author="用户上传") 并加入壁纸库
     *
     * @param detailUrl 壁纸 id（前端从列表项传入）
     * @param name 壁纸名称
     * @param type 类型（保留兼容，usermpkg 始终为场景壁纸）
     * @param fileIdParam 备用参数（当前未使用）
     * @param onProgress 进度回调（0-100）
     * @return 下载成功后的本地 WallpaperEntity，失败返回 null
     */
    private fun downloadUserMpkgAndImport(
        detailUrl: String,
        name: String,
        type: Int,
        fileIdParam: String,
        onProgress: ((Int) -> Unit)?,
    ): WallpaperEntity? {
        // detailUrl 实际为壁纸 id
        val id = detailUrl.trim()
        Log.i(TAG, "downloadUserMpkgAndImport: id=$id, name=$name")
        currentProgress = 0
        currentStatus = "正在准备下载"

        if (id.isEmpty()) {
            Log.e(TAG, "downloadUserMpkgAndImport: empty wallpaper id")
            currentStatus = "失败：壁纸 ID 为空"
            return null
        }

        val downloadUrl = "$SERVER_API_BASE/api/wallpapers/download?id=$id"
        Log.i(TAG, "downloadUserMpkgAndImport: downloadUrl=${downloadUrl.take(120)}")

        return try {
            currentStatus = "正在下载 $name"
            // 1. 生成本地 ID 与存储路径
            val wallpaperIdLocal = "wp_usermpkg_" + System.currentTimeMillis()
            val docsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
            val targetDir = File(docsDir, "Mineradio/Wallpaper/$wallpaperIdLocal").apply { mkdirs() }
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "wallpaper" }
            val fileName = "$safeName.mpkg"
            val targetFile = File(targetDir, fileName)

            // 2. 流式下载（downloadFile 支持 302 重定向）
            val mergedProgress: ((Int) -> Unit)? = { p ->
                currentProgress = p
                onProgress?.invoke(p)
            }
            downloadFile(downloadUrl, targetFile, mergedProgress)
            onProgress?.invoke(100)
            currentProgress = 100
            currentStatus = "下载完成，正在导入..."

            // 3. 创建 WallpaperEntity 并加入壁纸库（.mpkg 场景壁纸使用 TYPE_MPKG_WEBGL）
            val wallpaper =
                WallpaperEntity(
                    author = "用户上传",
                    description = "用户上传场景壁纸",
                    id = wallpaperIdLocal,
                    name = safeName,
                    path = fileName,
                    versionCode = 1,
                    versionName = "1.0",
                    wallpaperType = WallpaperEntity.TYPE_MPKG_WEBGL,
                )
            val wm = WallpaperManager.get(context)
            wm.addWallpaper(wallpaper)
            wm.setCurrentWallpaper(wallpaper)
            Log.i(TAG, "downloadUserMpkgAndImport: imported $safeName, file size=${targetFile.length()}")
            currentStatus = "导入完成"
            wallpaper
        } catch (e: Exception) {
            Log.e(TAG, "downloadUserMpkgAndImport failed: ${e.message}", e)
            currentStatus = "下载失败：${e.message}"
            null
        }
    }

    // ============================================================
    // haowallpaper.com（地址2）解析
    // ============================================================

    /**
     * 获取 haowallpaper.com 壁纸列表
     * URL: https://haowallpaper.com/homeView?page={page}
     * 搜索: https://haowallpaper.com/homeView?page={page}&lbName={keyword}
     */
    private fun getHaoList(
        page: Int,
        keyword: String?,
    ): JSONObject {
        val url =
            if (keyword.isNullOrBlank()) {
                "$HAO_BASE_URL/homeView?page=$page"
            } else {
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                "$HAO_BASE_URL/homeView?page=$page&lbName=$encoded"
            }
        Log.i(TAG, "getHaoList: page=$page, keyword=$keyword, url=$url")
        val html = fetchHtml(url)
        val list = parseHaoWallpaperList(html)
        return JSONObject().apply {
            put("list", list)
            put("page", page)
            put("hasMore", list.length() >= 6)
            put("totalPages", if (list.length() >= 6) -1 else 1) // -1 表示未知
            put("total", list.length())
        }
    }

    /**
     * 解析 haowallpaper.com 列表 HTML
     */
    private fun parseHaoWallpaperList(html: String): JSONArray {
        val list = JSONArray()
        if (html.isEmpty()) return list

        // 用 <a href="/homeViewLook/{wtId}"> 作为锚点
        val linkPattern = Regex("""<a[^>]*href="/homeViewLook/(\d+)"[^>]*>\s*<span>前往</span>""")
        val matches = linkPattern.findAll(html).toList()
        Log.d(TAG, "parseHaoWallpaperList: found ${matches.size} items")
        if (matches.isEmpty()) return list

        val seenIds = HashSet<String>()
        for (match in matches) {
            val wtId = match.groupValues[1]
            if (wtId in seenIds) continue
            seenIds.add(wtId)
            // 截取整个 .card 块：从当前 card 开始到下一个 card 开始（或字符串末尾）
            // haowallpaper HTML 是单行压缩，没有换行符，不能用 </div>\n 作为结束标志
            val cardStart = html.lastIndexOf("<div class=\"card\"", match.range.first)
            val nextCardStart = html.indexOf("<div class=\"card\"", match.range.last)
            val cardEnd =
                if (nextCardStart > 0) {
                    nextCardStart
                } else if (cardStart >= 0) {
                    Math.min(cardStart + 3000, html.length)
                } else {
                    Math.min(match.range.last + 800, html.length)
                }
            val segment =
                if (cardStart >= 0 && cardEnd > cardStart) {
                    html.substring(cardStart, cardEnd)
                } else {
                    html.substring(match.range.first, Math.min(match.range.last + 800, html.length))
                }
            try {
                val item = parseHaoWallpaperListItem(segment, wtId)
                if (item != null) list.put(item)
            } catch (e: Exception) {
                Log.w(TAG, "parseHaoWallpaperList: failed for wt-$wtId: ${e.message}")
            }
        }
        Log.d(TAG, "parseHaoWallpaperList: parsed ${list.length()} items")
        return list
    }

    /**
     * 解析单个 haowallpaper 列表项
     */
    private fun parseHaoWallpaperListItem(
        segment: String,
        wtId: String,
    ): JSONObject? {
        // 提取缩略图 fileId
        val imgPattern = Regex("""<img[^>]*src="https://haowallpaper\.com/link//common/file/getCroppingImg/(\d+)"""")
        val videoPattern = Regex("""<video[^>]*src="https://haowallpaper\.com/link//common/file/getVideoReduce/(\d+)"?[^>]*>""")
        val imgMatch = imgPattern.find(segment)
        val videoMatch = videoPattern.find(segment)
        val fileId = imgMatch?.groupValues?.get(1) ?: videoMatch?.groupValues?.get(1) ?: ""
        if (fileId.isEmpty()) return null

        val type = if (videoMatch != null) "video" else "image"
        val thumbnail = "https://haowallpaper.com/link//common/file/getCroppingImg/$fileId"

        // 提取标题（img alt 或 video title）
        val titlePattern = Regex("""(?:alt|title)="([^"]+)"""")
        val title =
            titlePattern
                .find(segment)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.ifEmpty { "壁纸 $wtId" }
                ?: "壁纸 $wtId"

        // 提取标签
        val labelPattern = Regex("""<div class="labelDiv"[^>]*>([\s\S]*?)</div>""")
        val labelContent = labelPattern.find(segment)?.groupValues?.get(1) ?: ""
        val tagPattern = Regex("""<span[^>]*>([^<]+)</span>""")
        val tags = tagPattern.findAll(labelContent).map { it.groupValues[1].trim() }.joinToString(" / ")

        // 提取 card-bottom 内 4 个 div（下载量/浏览量/分辨率/文件大小）
        // 每个模式：<div ...><span ...></span>   文字</div>
        val divValues =
            Regex("""<div[^>]*><span[^>]*></span>\s*([^<]+?)</div>""")
                .findAll(segment)
                .map { it.groupValues[1].trim() }
                .toList()
        val downloads = if (divValues.size > 0) divValues[0] else ""
        val views = if (divValues.size > 1) divValues[1] else ""
        val resolution = if (divValues.size > 2) divValues[2] else ""
        val fileSize = if (divValues.size > 3) divValues[3] else ""

        // 解析分辨率判定手机壁纸
        var width = 0
        var height = 0
        if (resolution.isNotEmpty()) {
            val parts = resolution.split("x", ignoreCase = true)
            if (parts.size == 2) {
                width = parts[0].trim().toIntOrNull() ?: 0
                height = parts[1].trim().substringBefore(" ").toIntOrNull() ?: 0
            }
        }
        val isPhone = width > 0 && height > 0 && height > width

        return JSONObject().apply {
            put("id", "https://haowallpaper.com/homeViewLook/$wtId")
            put("wtId", wtId)
            put("fileId", fileId)
            put("title", title)
            put("thumbnail", thumbnail)
            put("detailUrl", "https://haowallpaper.com/homeViewLook/$wtId")
            put("resolution", resolution)
            put("fileSize", fileSize)
            put("downloads", downloads)
            put("views", views)
            put("tags", tags)
            put("width", width)
            put("height", height)
            put("isPhone", isPhone)
            put("type", type)
            put("source", "haowallpaper")
        }
    }

    /**
     * 获取 haowallpaper.com 详情页
     *
     * 下载策略：
     * - 图片：使用 getCroppingImg（公开原图 URL，无需登录）
     * - 视频：使用 getVideoReduce（公开视频 URL，无需登录）
     *
     * 参考 haowallpaper-crawler 项目，这两个端点可直接访问原图/原视频，不受登录限制。
     */
    private fun getHaoDetail(detailUrl: String): JSONObject {
        Log.i(TAG, "getHaoDetail: url=$detailUrl")
        val html = fetchHtml(detailUrl)
        return parseHaoWallpaperDetail(html, detailUrl)
    }

    /**
     * 解析 haowallpaper.com 详情页
     */
    private fun parseHaoWallpaperDetail(
        html: String,
        detailUrl: String,
    ): JSONObject {
        val result =
            JSONObject().apply {
                put("detailUrl", detailUrl)
                put("title", "")
                put("fileId", "")
                put("downloadUrl", "")
                put("resolution", "")
                put("fileSize", "")
                put("type", "image")
                put("source", "haowallpaper")
            }
        if (html.isEmpty()) return result

        // 提取 fileId（从 og:image meta 或 img src）
        val ogImagePattern =
            Regex(
                """<meta property="og:image" content="https://haowallpaper\.com/link//common/file/(?:previewFileImg|getCroppingImg)/(\d+)"""",
            )
        val fileId = ogImagePattern.find(html)?.groupValues?.get(1) ?: ""
        if (fileId.isNotEmpty()) {
            result.put("fileId", fileId)
            // ★ 使用 getCroppingImg（原图，公开端点，无需登录）
            result.put("downloadUrl", "https://haowallpaper.com/link//common/file/getCroppingImg/$fileId")
        }

        // 提取标题
        val ogTitlePattern = Regex("""<meta property="og:title" content="([^"]+)"""")
        ogTitlePattern.find(html)?.let {
            result.put("title", it.groupValues[1].trim())
        }

        // 提取视频元素（如果有，则是动态壁纸）
        val videoPattern =
            Regex("""<video[^>]*src="https://haowallpaper\.com/link//common/file/getVideoReduce/(\d+)"?""")
        videoPattern.find(html)?.let { vMatch ->
            result.put("type", "video")
            val videoFileId = vMatch.groupValues[1]
            if (videoFileId.isNotEmpty()) {
                result.put("fileId", videoFileId)
                // 视频项下载用 getVideoReduce（公开端点，无需登录）
                result.put("downloadUrl", "https://haowallpaper.com/link//common/file/getVideoReduce/$videoFileId")
            }
        }

        // 提取分辨率（从详情页文本 "分辨率：WxH"）
        val resPattern = Regex("""分辨率[：:]\s*(\d+x\d+)""")
        resPattern.find(html)?.let {
            result.put("resolution", it.groupValues[1].trim())
        }

        // 提取文件大小（"大小：xxx MB"）
        val sizePattern = Regex("""大小[：:]\s*([\d.]+\s*[KM]B)""")
        sizePattern.find(html)?.let {
            result.put("fileSize", it.groupValues[1].trim())
        }

        Log.i(
            TAG,
            "parseHaoWallpaperDetail: title=${result.optString("title")}, fileId=${result.optString("fileId")}, " +
                "resolution=${result.optString("resolution")}, fileSize=${result.optString("fileSize")}, type=${result.optString("type")}",
        )
        return result
    }

    /**
     * 下载 haowallpaper.com 壁纸到本地并导入壁纸库
     *
     * ★ 使用公开端点直接下载，无需登录：
     *   - 图片原图：https://haowallpaper.com/link//common/file/getCroppingImg/{fileId}
     *   - 视频文件：https://haowallpaper.com/link//common/file/getVideoReduce/{fileId}
     *
     * 参考 haowallpaper-crawler 项目，这两个端点可任意访问，不受每日下载次数限制。
     *
     * @param detailUrl 详情页 URL（备用，当 fileIdParam 为空时回退到详情页解析）
     * @param fileIdParam 前端从列表项传入的 fileId（首选）
     *                   列表项的 fileId 来自 <img src="...getCroppingImg/{fileId}">
     *                   这是当前壁纸图片/视频的真实文件 ID
     */
    private fun downloadHaoAndImport(
        detailUrl: String,
        name: String,
        type: Int,
        fileIdParam: String,
        onProgress: ((Int) -> Unit)?,
    ): WallpaperEntity? {
        Log.i(TAG, "downloadHaoAndImport: detailUrl=$detailUrl, name=$name, type=$type, fileIdParam=$fileIdParam")
        // 重置进度
        currentProgress = 0
        currentStatus = "正在获取壁纸信息"

        // 1. 优先使用前端传入的 fileId（列表项已提取）
        //    列表中 fileId 来自 <img src="...getCroppingImg/{fileId}">，是当前壁纸真实文件 ID
        var fileId = fileIdParam.trim()
        var detailType = if (type == WallpaperEntity.TYPE_VIDEO) "video" else "image"
        var title = name
        var resolution = ""
        var fileSize = ""

        // 2. 如果前端没传 fileId，回退到详情页解析
        if (fileId.isEmpty()) {
            Log.i(TAG, "downloadHaoAndImport: no fileId from frontend, parsing detail page")
            val detail = getHaoDetail(detailUrl)
            fileId = detail.optString("fileId", "")
            title = detail.optString("title", name)
            resolution = detail.optString("resolution", "")
            fileSize = detail.optString("fileSize", "")
            detailType = detail.optString("type", "image")
        }

        if (fileId.isEmpty()) {
            Log.e(TAG, "downloadHaoAndImport: no fileId found, detailUrl=$detailUrl")
            currentStatus = "失败：未找到文件 ID"
            return null
        }

        // 3. 根据 type 构造下载 URL
        val downloadUrl =
            if (detailType == "video") {
                "https://haowallpaper.com/link//common/file/getVideoReduce/$fileId"
            } else {
                "https://haowallpaper.com/link//common/file/getCroppingImg/$fileId"
            }

        Log.i(
            TAG,
            "downloadHaoAndImport: title=$title, fileId=$fileId, type=$detailType, " +
                "resolution=$resolution, fileSize=$fileSize, downloadUrl=${downloadUrl.take(120)}",
        )

        return try {
            currentStatus = "正在下载 $title"
            // 4. 下载到本地
            val wallpaperIdLocal = "wp_online_" + System.currentTimeMillis()
            // ★ 存到外部公共目录：清理应用数据不会删除壁纸
            val docsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
            val targetDir = File(docsDir, "Mineradio/Wallpaper/$wallpaperIdLocal").apply { mkdirs() }
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "wallpaper" }
            val isVideo = detailType == "video" || type == WallpaperEntity.TYPE_VIDEO
            val ext = if (isVideo) "mp4" else "jpg"
            val fileName = "$safeName.$ext"
            val targetFile = File(targetDir, fileName)

            // 合并进度回调：同时更新静态变量和外部回调
            val mergedProgress: ((Int) -> Unit)? = { p ->
                currentProgress = p
                onProgress?.invoke(p)
            }
            downloadFile(downloadUrl, targetFile, mergedProgress)
            onProgress?.invoke(100)
            currentProgress = 100
            currentStatus = "下载完成，正在导入..."

            // 5. 创建 WallpaperEntity 并加入壁纸库
            val wallpaper =
                WallpaperEntity(
                    author = "haowallpaper.com",
                    description = "在线下载 $resolution $fileSize",
                    id = wallpaperIdLocal,
                    name = safeName,
                    path = fileName,
                    versionCode = 1,
                    versionName = "1.0",
                    wallpaperType = if (isVideo) WallpaperEntity.TYPE_VIDEO else WallpaperEntity.TYPE_IMAGE,
                )
            val wm = WallpaperManager.get(context)
            wm.addWallpaper(wallpaper)
            wm.setCurrentWallpaper(wallpaper)
            Log.i(TAG, "downloadHaoAndImport: imported $safeName, file size=${targetFile.length()}")
            currentStatus = "导入完成"
            wallpaper
        } catch (e: Exception) {
            Log.e(TAG, "downloadHaoAndImport failed: ${e.message}", e)
            currentStatus = "下载失败：${e.message}"
            null
        }
    }

    // ============================================================
    // bizhihui.com（图片壁纸源）解析
    // ============================================================

    /**
     * 获取 bizhihui.com 壁纸列表
     *
     * URL 规则：
     * - 分类首页（第1页）：/{slug}/?order=time
     * - 分类分页（第N页，N>=2）：/{slug}/{N}/?order=time
     * - 搜索：/?s={keyword} 或 /page/{N}/?s={keyword}
     * - 全部推荐（slug 为空）：/ 或 /page/{N}/
     *
     * @param page 页码（从 1 开始）
     * @param keyword 搜索关键词
     * @param category 分类 slug（可选，对应 BIZHIHUI_CATEGORIES 的 key）
     */
    private fun getBizhihuiList(
        page: Int,
        keyword: String?,
        category: String?,
    ): JSONObject {
        val slug = category?.trim()?.ifBlank { null } ?: ""
        val orderParam = "order=time" // 默认按发布时间排序
        val url =
            if (!keyword.isNullOrBlank()) {
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                if (page <= 1) "$BIZHIHUI_BASE_URL/?s=$encoded" else "$BIZHIHUI_BASE_URL/page/$page/?s=$encoded"
            } else if (slug.isEmpty()) {
                // 全部推荐
                if (page <= 1) "$BIZHIHUI_BASE_URL/" else "$BIZHIHUI_BASE_URL/page/$page/"
            } else {
                // 分类
                if (page <= 1) "$BIZHIHUI_BASE_URL/$slug/?$orderParam" else "$BIZHIHUI_BASE_URL/$slug/$page/?$orderParam"
            }
        Log.i(TAG, "getBizhihuiList: page=$page, keyword=$keyword, category=$slug, url=$url")

        val html = fetchHtml(url)
        val list = parseBizhihuiList(html)
        val totalPages = parseBizhihuiTotalPages(html, slug)
        val hasMore = page < totalPages || (totalPages <= 1 && list.length() >= 12)
        return JSONObject().apply {
            put("list", list)
            put("page", page)
            put("hasMore", hasMore)
            put("totalPages", totalPages)
            put("total", list.length())
        }
    }

    /**
     * 解析 bizhihui.com 列表 HTML
     *
     * 列表项锚点：<a href="/p/{id}.html" ...>
     * 缩略图：<img src="https://aka.doubaocdn.com/s/{hash}.jpg" ...>
     *
     * 实际 HTML 经过压缩，列表项可能没有换行。我们以 /p/{id}.html 为锚点，
     * 截取锚点前后 800 字符作为片段，从中提取缩略图和标题。
     */
    private fun parseBizhihuiList(html: String): JSONArray {
        val list = JSONArray()
        if (html.isEmpty()) return list

        // 锚点：<a href="/p/{id}.html" ...>
        val linkPattern = Regex("""<a[^>]*href="/p/(\d+)\.html"[^>]*>""")
        val matches = linkPattern.findAll(html).toList()
        Log.d(TAG, "parseBizhihuiList: found ${matches.size} items")
        if (matches.isEmpty()) return list

        val seenIds = HashSet<String>()
        for (match in matches) {
            val id = match.groupValues[1]
            if (id in seenIds) continue
            seenIds.add(id)

            // 截取锚点前后片段：向前 200 字符（找 <li> 开始），向后 800 字符（找 img 和 title）
            val start = Math.max(0, match.range.first - 200)
            val end = Math.min(html.length, match.range.last + 800)
            val segment = html.substring(start, end)
            try {
                val item = parseBizhihuiListItem(segment, id)
                if (item != null) list.put(item)
            } catch (e: Exception) {
                Log.w(TAG, "parseBizhihuiList: failed for id=$id: ${e.message}")
            }
        }
        Log.d(TAG, "parseBizhihuiList: parsed ${list.length()} items")
        return list
    }

    /**
     * 解析单个 bizhihui 列表项
     */
    private fun parseBizhihuiListItem(
        segment: String,
        id: String,
    ): JSONObject? {
        // 提取缩略图（aka.doubaocdn.com CDN 上的图片）
        val imgPattern =
            Regex("""<img[^>]*src="(https://aka\.doubaocdn\.com/s/[^"'\s]+)"""", RegexOption.IGNORE_CASE)
        val thumbnail = imgPattern.find(segment)?.groupValues?.get(1) ?: ""
        if (thumbnail.isEmpty()) return null // 没有缩略图的项跳过

        // 提取标题（优先 a 标签的 title 属性，其次 img alt，最后用默认）
        val titlePattern = Regex("""<a[^>]*title="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        var title =
            titlePattern
                .find(segment)
                ?.groupValues
                ?.get(1)
                ?.trim() ?: ""
        if (title.isEmpty()) {
            val altPattern = Regex("""<img[^>]*alt="([^"]+)"""", RegexOption.IGNORE_CASE)
            title = altPattern
                .find(segment)
                ?.groupValues
                ?.get(1)
                ?.trim() ?: ""
        }
        if (title.isEmpty()) title = "壁纸 $id"

        // 构造详情页 URL（实际为 /p/{id}.html，不是 /show/）
        val detailUrl = "$BIZHIHUI_BASE_URL/p/$id.html"

        // 提取分辨率（列表项中可能有 "4000 x 2250" 形式的文本）
        val resPattern = Regex("""(\d{3,5})\s*[x×]\s*(\d{3,5})""")
        val resMatch = resPattern.find(segment)
        val width = resMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val height = resMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val resolution = if (width > 0 && height > 0) "${width}x$height" else ""
        val isPhone = width > 0 && height > 0 && height > width

        return JSONObject().apply {
            put("id", id)
            put("postId", id)
            put("title", title)
            put("thumbnail", thumbnail)
            put("detailUrl", detailUrl)
            put("resolution", resolution)
            put("width", width)
            put("height", height)
            put("isPhone", isPhone)
            put("type", "image")
            put("source", "bizhihui")
            put("fileSize", "")
        }
    }

    /**
     * 从 bizhihui 列表页 HTML 解析总页数
     *
     * 分页区域包含形如 /{slug}/{N}/ 或 /page/{N}/ 的链接。
     * 取所有页码数字的最大值作为 totalPages。
     * 无法解析时返回 1。
     */
    private fun parseBizhihuiTotalPages(
        html: String,
        slug: String,
    ): Int {
        if (html.isEmpty()) return 1
        val pattern =
            if (slug.isEmpty()) {
                // 首页分页：/page/{N}/
                Regex("""href="[^"]*/page/(\d+)/[^"]*"""")
            } else {
                // 分类分页：/{slug}/{N}/
                val escaped = Regex.escape(slug)
                Regex("""href="[^"]*/$escaped/(\d+)/[^"]*"""")
            }
        val maxPage =
            pattern
                .findAll(html)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull() ?: 1
        return maxPage.coerceAtLeast(1)
    }

    /**
     * 获取 bizhihui.com 详情页
     *
     * 详情页结构：
     * - 标题：<meta property="og:title" content="...">
     * - 原图：<meta property="og:image" content="https://aka.doubaocdn.com/...">
     * - 分辨率：文本 "图片尺寸：4000 x 2250" 或 "壁纸分辨率：4000 x 2250"
     * - 文件大小：文本 "文件大小：6.26 M"
     * - 分类：文本 "作品分类：卡通动漫"
     *
     * 注意：详情页的"下载"按钮指向夸克网盘（pan.quark.cn），
     * 不能直接下载，因此使用 og:image 作为 downloadUrl（公开 CDN 图片）。
     */
    private fun getBizhihuiDetail(detailUrl: String): JSONObject {
        Log.i(TAG, "getBizhihuiDetail: url=$detailUrl")
        val html = fetchHtml(detailUrl)
        return parseBizhihuiDetail(html, detailUrl)
    }

    /**
     * 解析 bizhihui.com 详情页
     */
    private fun parseBizhihuiDetail(
        html: String,
        detailUrl: String,
    ): JSONObject {
        val result =
            JSONObject().apply {
                put("detailUrl", detailUrl)
                put("title", "")
                put("downloadUrl", "")
                put("thumbnail", "")
                put("resolution", "")
                put("fileSize", "")
                put("type", "image")
                put("source", "bizhihui")
            }
        if (html.isEmpty()) return result

        // 1. 提取标题（og:title）
        val ogTitlePattern = Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
        ogTitlePattern.find(html)?.let {
            result.put("title", it.groupValues[1].trim())
        }
        if (result.optString("title").isEmpty()) {
            val h1Pattern = Regex("""<h1[^>]*>([^<]+)</h1>""", RegexOption.IGNORE_CASE)
            h1Pattern.find(html)?.let {
                result.put("title", it.groupValues[1].trim())
            }
        }

        // 2. 提取 og:image 作为下载 URL（夸克网盘链接不可直接下载，用 CDN 图片代替）
        val ogImagePattern = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
        ogImagePattern.find(html)?.let {
            val img = it.groupValues[1].trim()
            result.put("thumbnail", img)
            result.put("downloadUrl", img)
        }
        // 降级：从详情页正文中找 aka.doubaocdn.com 的大图
        if (result.optString("downloadUrl").isEmpty()) {
            val imgPattern =
                Regex("""<img[^>]*src="(https://aka\.doubaocdn\.com/[^"'\s]+)"""", RegexOption.IGNORE_CASE)
            imgPattern.find(html)?.let {
                result.put("thumbnail", it.groupValues[1])
                result.put("downloadUrl", it.groupValues[1])
            }
        }

        // 3. 提取分辨率（"图片尺寸：4000 x 2250" 或 "壁纸分辨率：4000 x 2250"）
        val resPattern = Regex("""(?:图片尺寸|壁纸分辨率)[：:]\s*(\d+)\s*[x×]\s*(\d+)""")
        resPattern.find(html)?.let {
            val w = it.groupValues[1]
            val h = it.groupValues[2]
            result.put("resolution", "${w}x$h")
        }

        // 4. 提取文件大小（"文件大小：6.26 M"）
        val sizePattern = Regex("""文件大小[：:]\s*([\d.]+\s*[KM]B?)""", RegexOption.IGNORE_CASE)
        sizePattern.find(html)?.let {
            result.put("fileSize", it.groupValues[1].trim())
        }

        Log.i(
            TAG,
            "parseBizhihuiDetail: title=${result.optString("title")}, " +
                "resolution=${result.optString("resolution")}, fileSize=${result.optString("fileSize")}",
        )
        return result
    }

    /**
     * 下载 bizhihui.com 壁纸到本地并导入壁纸库
     *
     * 流程：
     * 1. 调用 getBizhihuiDetail 获取原图 URL（og:image）
     * 2. 直接下载图片到外部公共目录
     * 3. 创建 WallpaperEntity(type=TYPE_IMAGE) 并加入壁纸库
     *
     * @param detailUrl 详情页 URL（/p/{id}.html）
     * @param name 壁纸名称
     * @param type 类型（保留兼容，bizhihui 始终为图片）
     * @param fileIdParam 前端传入的缩略图 URL（可选，用于跳过详情页请求）
     * @param onProgress 进度回调
     */
    private fun downloadBizhihuiAndImport(
        detailUrl: String,
        name: String,
        type: Int,
        fileIdParam: String,
        onProgress: ((Int) -> Unit)?,
    ): WallpaperEntity? {
        Log.i(TAG, "downloadBizhihuiAndImport: detailUrl=$detailUrl, name=$name, fileIdParam=$fileIdParam")
        currentProgress = 0
        currentStatus = "正在获取壁纸信息"

        // 1. 优先使用前端传入的缩略图 URL（列表项已包含），避免额外的详情页请求
        var downloadUrl = fileIdParam.trim()
        var title = name
        var resolution = ""
        var fileSize = ""

        if (downloadUrl.isEmpty()) {
            // 回退到详情页解析
            Log.i(TAG, "downloadBizhihuiAndImport: no fileId from frontend, parsing detail page")
            val detail = getBizhihuiDetail(detailUrl)
            downloadUrl = detail.optString("downloadUrl", "")
            title = detail.optString("title", name)
            resolution = detail.optString("resolution", "")
            fileSize = detail.optString("fileSize", "")
        }

        if (downloadUrl.isEmpty()) {
            Log.e(TAG, "downloadBizhihuiAndImport: no downloadUrl found, detailUrl=$detailUrl")
            currentStatus = "失败：未找到下载链接"
            return null
        }

        Log.i(
            TAG,
            "downloadBizhihuiAndImport: title=$title, resolution=$resolution, fileSize=$fileSize, " +
                "downloadUrl=${downloadUrl.take(120)}",
        )

        return try {
            currentStatus = "正在下载 $title"
            // 2. 下载到本地
            val wallpaperIdLocal = "wp_online_" + System.currentTimeMillis()
            val docsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
            val targetDir = File(docsDir, "Mineradio/Wallpaper/$wallpaperIdLocal").apply { mkdirs() }
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "wallpaper" }
            // 根据 URL 扩展名推断文件类型（aka.doubaocdn.com 图片通常是 .jpg）
            val ext = inferImageExtension(downloadUrl)
            val fileName = "$safeName.$ext"
            val targetFile = File(targetDir, fileName)

            val mergedProgress: ((Int) -> Unit)? = { p ->
                currentProgress = p
                onProgress?.invoke(p)
            }
            downloadFile(downloadUrl, targetFile, mergedProgress)
            onProgress?.invoke(100)
            currentProgress = 100
            currentStatus = "下载完成，正在导入..."

            // 3. 创建 WallpaperEntity 并加入壁纸库（bizhihui 始终为图片类型）
            val wallpaper =
                WallpaperEntity(
                    author = "bizhihui.com",
                    description = "在线下载 $resolution $fileSize",
                    id = wallpaperIdLocal,
                    name = safeName,
                    path = fileName,
                    versionCode = 1,
                    versionName = "1.0",
                    wallpaperType = WallpaperEntity.TYPE_IMAGE,
                )
            val wm = WallpaperManager.get(context)
            wm.addWallpaper(wallpaper)
            wm.setCurrentWallpaper(wallpaper)
            Log.i(TAG, "downloadBizhihuiAndImport: imported $safeName, file size=${targetFile.length()}")
            currentStatus = "导入完成"
            wallpaper
        } catch (e: Exception) {
            Log.e(TAG, "downloadBizhihuiAndImport failed: ${e.message}", e)
            currentStatus = "下载失败：${e.message}"
            null
        }
    }

    /**
     * 从图片 URL 推断扩展名
     * 默认 .jpg，URL 中明确包含 .png/.webp 时使用对应扩展名
     */
    private fun inferImageExtension(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".png") -> "png"
            lower.contains(".webp") -> "webp"
            lower.contains(".gif") -> "gif"
            lower.contains(".bmp") -> "bmp"
            else -> "jpg"
        }
    }

    // ============================================================
    // moewalls.com WordPress REST API 支持
    // ============================================================

    /**
     * 发起 HTTP 请求并返回响应体字符串与响应头映射
     *
     * 用于 WP REST API 调用，需要读取 X-WP-Total / X-WP-TotalPages 响应头
     *
     * @return Pair(body, headers) - body 为响应体，headers 为响应头名到值的映射（键小写）
     */
    private fun fetchJsonWithHeaders(urlStr: String): Pair<String, Map<String, String>> {
        val conn =
            (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json, text/html, */*;q=0.8")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                if (urlStr.contains("moewalls.com")) {
                    setRequestProperty("Referer", "$BASE_URL/")
                }
                instanceFollowRedirects = true
            }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "fetchJsonWithHeaders: HTTP $code for $urlStr")
                return "" to emptyMap()
            }
            val encoding = conn.contentEncoding ?: "utf-8"
            val charset =
                try {
                    java.nio.charset.Charset
                        .forName(encoding)
                } catch (_: Exception) {
                    Charsets.UTF_8
                }
            val body = conn.inputStream.bufferedReader(charset).use { it.readText() }
            // 收集响应头（键统一转小写方便查找）
            val headers = mutableMapOf<String, String>()
            conn.headerFields.forEach { (key, values) ->
                if (key != null && values.isNotEmpty()) {
                    headers[key.lowercase()] = values.joinToString(", ")
                }
            }
            return body to headers
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 获取 moewalls.com 分类列表（WP REST API）
     *
     * GET https://moewalls.com/wp-json/wp/v2/categories?per_page=100
     * 返回 JSON 数组，每项含 id, name, slug, count 等
     * name 会翻译为中文（参考 MOEWALLS_CATEGORY_CN），未命中翻译的保留原英文名
     */
    private fun getMoewallsCategories(): JSONArray {
        val url = "$BASE_URL/wp-json/wp/v2/categories?per_page=100"
        Log.i(TAG, "getMoewallsCategories: url=$url")
        val (body, _) = fetchJsonWithHeaders(url)
        if (body.isEmpty()) return JSONArray()
        return try {
            val raw = JSONArray(body)
            val result = JSONArray()
            for (i in 0 until raw.length()) {
                val item = raw.optJSONObject(i) ?: continue
                val id = item.optInt("id", 0)
                val slug = item.optString("slug", "")
                val count = item.optInt("count", 0)
                // 中文翻译：优先使用映射表，未命中则保留原 name
                val originalName = item.optString("name", "")
                val cnName = MOEWALLS_CATEGORY_CN[slug] ?: originalName
                result.put(
                    JSONObject().apply {
                        put("id", id.toString())
                        put("slug", slug)
                        put("name", cnName)
                        put("count", count)
                    },
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "getMoewallsCategories: parse failed: ${e.message}", e)
            JSONArray()
        }
    }

    /**
     * 获取 moewalls.com 分辨率列表
     *
     * 优先尝试 WP REST API resolution 自定义分类法：
     *   GET https://moewalls.com/wp-json/wp/v2/resolution?per_page=100
     * 若返回 404 或空，则返回内置的常见分辨率列表
     */
    private fun getMoewallsResolutions(): JSONArray {
        val url = "$BASE_URL/wp-json/wp/v2/resolution?per_page=100"
        Log.i(TAG, "getMoewallsResolutions: url=$url")
        val (body, _) = fetchJsonWithHeaders(url)
        if (body.isNotEmpty()) {
            try {
                val raw = JSONArray(body)
                if (raw.length() > 0) {
                    val result = JSONArray()
                    for (i in 0 until raw.length()) {
                        val item = raw.optJSONObject(i) ?: continue
                        val id = item.optInt("id", 0)
                        val slug = item.optString("slug", "")
                        val count = item.optInt("count", 0)
                        val name = item.optString("name", slug)
                        result.put(
                            JSONObject().apply {
                                put("id", id.toString())
                                put("slug", slug)
                                put("name", name)
                                put("count", count)
                            },
                        )
                    }
                    if (result.length() > 0) return result
                }
            } catch (e: Exception) {
                Log.w(TAG, "getMoewallsResolutions: API parse failed, fallback to built-in: ${e.message}")
            }
        }
        // 兜底：返回内置常见分辨率（moewalls 主要是 4K 视频壁纸）
        val builtIn =
            listOf(
                "3840x2160" to "4K (3840x2160)",
                "1920x1080" to "Full HD (1920x1080)",
                "2160x3840" to "4K 竖屏 (2160x3840)",
                "1080x1920" to "Full HD 竖屏 (1080x1920)",
            )
        val result = JSONArray()
        builtIn.forEach { (slug, name) ->
            result.put(
                JSONObject().apply {
                    put("id", slug)
                    put("slug", slug)
                    put("name", name)
                    put("count", 0)
                },
            )
        }
        return result
    }

    /**
     * 按分类查询 moewalls.com 文章列表（WP REST API）
     *
     * GET https://moewalls.com/wp-json/wp/v2/posts?categories={catId}&per_page=100&page={N}
     * 响应头：
     *   X-WP-Total      - 总文章数
     *   X-WP-TotalPages - 总页数
     *
     * 每篇文章转换为列表项结构（与 HTML 抓取保持兼容）：
     *   {id, postId, title, thumbnail, detailUrl, type="video", category, ...}
     *
     * @param page 页码（从 1 开始）
     * @param categoryId WP REST API 分类 ID（数字字符串）
     */
    private fun getMoewallsListByCategory(
        page: Int,
        categoryId: String?,
        resolution: String? = null,
    ): JSONObject {
        // ★ 构造 WP REST API URL：支持 种类（categories）+ 分辨率（resolution）双重筛选
        //   resolution 是 moewalls 的自定义分类法（taxonomy），可通过 &resolution={id} 筛选
        val sb = StringBuilder("$BASE_URL/wp-json/wp/v2/posts?per_page=100&page=$page")
        if (!categoryId.isNullOrBlank()) {
            sb.append("&categories=").append(categoryId)
        }
        if (!resolution.isNullOrBlank()) {
            sb.append("&resolution=").append(resolution)
        }
        val url = sb.toString()
        Log.i(TAG, "getMoewallsListByCategory: page=$page, categoryId=$categoryId, resolution=$resolution, url=${url.take(120)}")
        val (body, headers) = fetchJsonWithHeaders(url)

        val list = JSONArray()
        if (body.isNotEmpty()) {
            try {
                val posts = JSONArray(body)
                for (i in 0 until posts.length()) {
                    val post = posts.optJSONObject(i) ?: continue
                    val postId = post.optInt("id", 0)
                    val titleHtml = post.optJSONObject("title")?.optString("rendered", "") ?: ""
                    val title =
                        android.text.Html
                            .fromHtml(titleHtml, android.text.Html.FROM_HTML_MODE_LEGACY)
                            .toString()
                            .trim()
                    val link = post.optString("link", "")
                    val slug = post.optString("slug", "")

                    // 提取缩略图：从 _links.wp:featuredmedia[0].href 获取
                    var thumbnail = ""
                    val links = post.optJSONObject("_links")
                    if (links != null) {
                        val featured = links.optJSONArray("wp:featuredmedia")
                        if (featured != null && featured.length() > 0) {
                            val mediaHref = featured.optJSONObject(0)?.optString("href", "") ?: ""
                            if (mediaHref.isNotEmpty()) {
                                thumbnail = fetchMediaThumbnail(mediaHref)
                            }
                        }
                    }

                    // 提取分类名（从 _links.category 或 taxonomy 中）
                    val category = ""

                    list.put(
                        JSONObject().apply {
                            put("id", link.ifEmpty { postId.toString() })
                            put("postId", postId.toString())
                            put("title", title.ifEmpty { "Wallpaper $postId" })
                            put("thumbnail", thumbnail)
                            put("detailUrl", link)
                            put("slug", slug)
                            put("type", "video") // moewalls 全是视频壁纸
                            put("category", category)
                            put("source", "moewalls")
                            put("resolution", "")
                            put("width", 0)
                            put("height", 0)
                            put("isPhone", false)
                        },
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "getMoewallsListByCategory: parse failed: ${e.message}", e)
            }
        }

        // 读取响应头中的总文章数与总页数
        val total = headers["x-wp-total"]?.toIntOrNull() ?: list.length()
        val totalPages = headers["x-wp-totalpages"]?.toIntOrNull() ?: if (list.length() >= 100) -1 else 1
        val hasMore = if (totalPages > 0) page < totalPages else list.length() >= 100

        return JSONObject().apply {
            put("list", list)
            put("page", page)
            put("hasMore", hasMore)
            put("totalPages", totalPages)
            put("total", total)
        }
    }

    /**
     * 查询 WP REST API 媒体对象，获取缩略图 URL
     * GET {mediaHref} → JSON: { source_url: "https://moewalls.com/wp-content/uploads/.../thumb.jpg" }
     */
    private fun fetchMediaThumbnail(mediaHref: String): String {
        return try {
            val (body, _) = fetchJsonWithHeaders(mediaHref)
            if (body.isEmpty()) return ""
            val media = JSONObject(body)
            // 优先 source_url；其次 media_details.sizes.medium_large.source_url
            var src = media.optString("source_url", "")
            if (src.isEmpty()) {
                val details = media.optJSONObject("media_details")
                val sizes = details?.optJSONObject("sizes")
                src = sizes?.optJSONObject("medium_large")?.optString("source_url", "") ?: ""
            }
            src
        } catch (e: Exception) {
            Log.w(TAG, "fetchMediaThumbnail: failed: ${e.message}")
            ""
        }
    }
}
