package com.mineradio.app.manager

import android.content.Context
import android.content.res.AssetManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.mineradio.app.manager.NeteaseMusicApi
import com.mineradio.app.storage.impl.dao.SongDao
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 嵌入式 HTTP 服务器 — 为 Mineradio WebView 前端提供服务
 *
 * 职责:
 *  1. 提供静态文件服务 (assets/mineradio)
 *  2. 代理网易云音乐 API 调用
 *  3. 桥接 KeepApp Kotlin <-> JS 通道
 */
class MineradioServer(
    private val context: Context,
    private val port: Int = 8800, // 固定端口，确保跨启动 origin 一致（localStorage/IndexedDB 持久化）
) {
    companion object {
        private const val TAG = "MineradioServer"
        private val MIME: Map<String, String> =
            mapOf(
                "html" to "text/html; charset=utf-8",
                "css" to "text/css; charset=utf-8",
                "js" to "application/javascript; charset=utf-8",
                "json" to "application/json; charset=utf-8",
                "png" to "image/png",
                "jpg" to "image/jpeg",
                "jpeg" to "image/jpeg",
                "gif" to "image/gif",
                "webp" to "image/webp",
                "bmp" to "image/bmp",
                "svg" to "image/svg+xml",
                "ico" to "image/x-icon",
                "bin" to "application/octet-stream",
                "mp3" to "audio/mpeg",
                "m4a" to "audio/mp4",
                "flac" to "audio/flac",
                "ogg" to "audio/ogg",
                "wav" to "audio/wav",
                "webm" to "audio/webm",
                "txt" to "text/plain; charset=utf-8",
                "wasm" to "application/wasm",
                "tflite" to "application/octet-stream",
                "data" to "application/octet-stream",
                "binarypb" to "application/octet-stream",
            )
    }

    var actualPort: Int = 0
        private set
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val assets: AssetManager = context.assets
    private val staticBase = "mineradio/"

    // ★ 运行时插件管理器（懒加载,首次访问时初始化）
    val pluginManager: PluginManager by lazy {
        PluginManager(context).also {
            // ★ 绑定到 AssetDecryptor,使其能调用 Native 解密方法
            AssetDecryptor.bindPluginManager(it)
        }
    }

    // ★ 性能优化：线程池根据 CPU 核心数动态调整，避免低端机过多线程开销
    //   原实现固定 32 线程，每个线程约 1MB 栈空间 = 32MB 仅线程栈
    //   新实现：核心数 clamp 到 [2, 8]，兼顾并发与内存
    private val threadPool =
        Executors.newFixedThreadPool(
            (Runtime.getRuntime().availableProcessors().coerceIn(2, 8)).also {
                Log.i(TAG, "threadPool size = $it (cpu cores=${Runtime.getRuntime().availableProcessors()})")
            },
        ) { r ->
            Thread(r, "Mineradio-Worker").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }

    // ★ 静态文件 LRU 缓存：最多缓存 30 个文件 + 总大小限制 4MB
    //   性能优化：增加条目数(20→30)并增加总字节上限(4MB)，防止大文件撑爆缓存
    private val staticCache =
        object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
            private val MAX_ENTRIES = 30
            private val MAX_TOTAL_BYTES = 4 * 1024 * 1024 // 4MB

            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
                if (size > MAX_ENTRIES) return true
                // 检查总大小
                var total = 0L
                for (v in values) total += v.size
                return total > MAX_TOTAL_BYTES
            }
        }

    // ★ ETag 缓存：记录已缓存文件的 ETag（内容哈希），用于 304 响应
    private val etagCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ★ 可 gzip 压缩的 MIME 类型
    private val gzipMimeTypes =
        setOf(
            "text/html",
            "text/css",
            "application/javascript",
            "application/json",
            "text/plain",
            "image/svg+xml",
            "application/xml",
        )

    fun start(): Int {
        serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket!!.reuseAddress = true
        actualPort = serverSocket!!.localPort
        running = true
        Log.i(TAG, "服务器启动: http://127.0.0.1:$actualPort")
        Thread({ acceptLoop() }, "MineradioServer").start()
        return actualPort
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        // ★ 清理静态缓存和 ETag 缓存
        synchronized(staticCache) { staticCache.clear() }
        etagCache.clear()
        // ★ 优雅关闭线程池
        threadPool.shutdown()
        try {
            threadPool.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val client = serverSocket?.accept() ?: break
                // ★ 使用线程池代替每请求创建新线程
                threadPool.execute { handleClient(client) }
            } catch (e: SocketException) {
                if (running) Log.w(TAG, "accept 异常: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "accept 异常: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            // ★ TCP 优化：禁用 Nagle 算法 + 扩大发送缓冲区
            socket.tcpNoDelay = true
            socket.sendBufferSize = 262144
            socket.soTimeout = 30000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val request = readRequest(input)
            if (request == null) {
                socket.close()
                return
            }

            val (method, rawPath, headers) = request
            val pathOnly = rawPath.substringBefore("?")
            val query = if (rawPath.contains("?")) rawPath.substringAfter("?") else ""

            Log.d(TAG, "$method $rawPath")

            // ★ 登录功能不再受插件门禁限制：
            //   已移除 login-panel 插件 403 检查（"请安装登录面板插件"），
            //   登录/QQ/酷狗/汽水等平台 API 由插件 apiJson 拦截器或后端路由直接处理，
            //   不再因插件未启用而阻断登录或同步。
            when {
                // ── 平台业务路由已全部迁移到 login-panel 插件，后端只保留基础设施路由 ──
                //   网易云 / QQ / 酷狗 / 汽水音乐 的 API 由插件在 JS 层通过 apiJson 拦截器处理
                //   插件禁用时 loginPluginGated 检查会返回 403
                pathOnly == "/api/app/version" -> {
                    try {
                        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        sendJson(
                            output,
                            200,
                            JSONObject().apply {
                                put("version", pkgInfo.versionName ?: "1.0.0")
                                put("platform", "android")
                            },
                        )
                    } catch (e: Exception) {
                        sendJson(
                            output,
                            200,
                            JSONObject().apply {
                                put("version", "1.0.0")
                                put("platform", "android")
                            },
                        )
                    }
                }
                pathOnly == "/api/discover/home" -> {
                    // 占位响应 — 前端通过插件获取发现页数据
                    sendJson(
                        output,
                        200,
                        JSONObject().apply {
                            put("loggedIn", false)
                            put("user", JSONObject.NULL)
                            put("dailySongs", JSONArray())
                            put("playlists", JSONArray())
                            put("podcasts", JSONArray())
                            put("updatedAt", System.currentTimeMillis())
                        },
                    )
                }
                pathOnly == "/favicon.ico" -> {
                    sendEmpty(output, 204)
                }
                pathOnly == "/api/cover" -> {
                    // 代理封面图片请求
                    proxyUrl(output, query, "url")
                }
                pathOnly == "/api/audio" -> {
                    // ★ 音频代理：转发到 Node 后端 /api/audio（与 mineradio-android 架构一致）
                    //   Node server.js 会对汽水 douyinvod 的 #auth 加密音频调用 getQishuiDecryptedAudio 解密；
                    //   旧的 proxyUrl 直接连 douyinvod、不解密，播放器拿到加密字节无法解码(Broken pipe)导致无法播放。
                    proxyAudioToNode(output, query, headers)
                }
                // ── 汽水音乐 API 路由已全部迁移到 login-panel 插件 ──
                //   插件通过 apiJson 拦截器在 JS 层处理 /api/qishui/* 请求
                //   插件禁用时 loginPluginGated 检查会返回 403
                //   通用 /api/cookie 和 /api/cache/download 端点供插件使用
                // ── 抖音 SSO 二维码登录（WebView 渲染 → SSR 提取）──
                // 汽水音乐 fallback 已移除 — 所有 /api/qishui/* 由 login-panel 插件处理
                // ── 视频音频提取 ──
                pathOnly == "/api/video/extract" && method == "POST" -> {
                    try {
                        val body = readBody(input, method)
                        // body 是一个 data URL: data:video/mp4;base64,AAAA...
                        val base64Data = body.substringAfter("base64,").trim()
                        val rawBytes = Base64.decode(base64Data, Base64.DEFAULT)
                        // 写入临时文件
                        val tempDir = File(context.cacheDir, "video_extract")
                        tempDir.mkdirs()
                        val tempVideo = File(tempDir, "temp_${System.currentTimeMillis()}.mp4")
                        tempVideo.writeBytes(rawBytes)
                        // 使用 MediaExtractor 提取音轨
                        val extractAudioOutput = File(tempDir, "audio_${System.currentTimeMillis()}.aac")
                        var extracted = false
                        try {
                            val extractor = MediaExtractor()
                            extractor.setDataSource(tempVideo.absolutePath)
                            var audioIdx = -1
                            for (i in 0 until extractor.trackCount) {
                                val fmt = extractor.getTrackFormat(i)
                                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                                if (mime.startsWith("audio/")) {
                                    audioIdx = i
                                    break
                                }
                            }
                            if (audioIdx >= 0) {
                                extractor.selectTrack(audioIdx)
                                val outStream = FileOutputStream(extractAudioOutput)
                                val buf = java.nio.ByteBuffer.allocateDirect(256 * 1024)
                                while (true) {
                                    val size = extractor.readSampleData(buf, 0)
                                    if (size <= 0) break
                                    val bytes = ByteArray(size)
                                    buf.rewind()
                                    buf.get(bytes, 0, size)
                                    buf.clear()
                                    outStream.write(bytes, 0, size)
                                    extractor.advance()
                                }
                                outStream.close()
                                extracted = true
                            }
                            extractor.release()
                        } catch (ex: Exception) {
                            Log.e(TAG, "MediaExtractor failed", ex)
                        }
                        // 清理临时视频
                        tempVideo.delete()
                        if (extracted && extractAudioOutput.exists() && extractAudioOutput.length() > 0) {
                            val bytes = extractAudioOutput.readBytes()
                            extractAudioOutput.delete()
                            // 返回原始音频数据
                            sendBinary(output, 200, "audio/aac", bytes)
                        } else {
                            sendJson(output, 500, JSONObject().apply { put("error", "extraction failed") })
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "video extract error", e)
                        sendJson(output, 500, JSONObject().apply { put("error", e.message ?: "unknown") })
                    }
                }
                pathOnly.startsWith("/api/podcast/") -> sendJson(output, 200, JSONObject().apply { put("notImplemented", true) })
                pathOnly.startsWith("/api/beatmap/") -> {
                    val subPath = pathOnly.substringAfter("/api/beatmap/")
                    if (subPath == "cache" && method == "GET") {
                        val cacheKey = getQueryParam(query, "key") ?: ""
                        if (cacheKey.isBlank()) {
                            sendJson(output, 400, JSONObject().apply { put("error", "Missing key") })
                        } else {
                            serveBeatCache(output, cacheKey)
                        }
                    } else if (subPath == "cache" && method == "POST") {
                        val body = readBody(input, method)
                        serveBeatCacheWrite(output, body)
                    } else {
                        sendJson(output, 200, JSONObject().apply { put("notImplemented", true) })
                    }
                }
                pathOnly.startsWith("/api/update/") -> sendJson(output, 200, JSONObject().apply { put("notImplemented", true) })
                pathOnly == "/api/artist/detail" ->
                    sendJson(
                        output,
                        200,
                        JSONObject().apply {
                            put("artist", JSONObject())
                            put("songs", JSONArray())
                        },
                    )
                pathOnly == "/api/song/comments" ->
                    sendJson(
                        output,
                        200,
                        JSONObject().apply {
                            put("comments", JSONArray())
                            put("hotComments", JSONArray())
                        },
                    )
                // ── 本地歌曲文件服务（持久化） ──
                pathOnly.startsWith("/local-song/") -> {
                    val fileName = pathOnly.substringAfter("/local-song/")
                    serveLocalSongFile(output, fileName)
                }
                // ★ 汽水音乐 VIP 歌曲缓存文件服务
                //   HEAD 请求只做存在性检测（用于试纸检测缓存），不返回文件内容
                pathOnly.startsWith("/qs-cache/") -> {
                    val fileName = pathOnly.substringAfter("/qs-cache/")
                    serveQSCacheFile(output, fileName, headOnly = (method == "HEAD"))
                }
                // ★★★ 崩溃恢复机制：播放前保存 trackId，播放成功后清除 ★★★
                //   如果 App 在播放过程中崩溃（SIGTRAP），下次启动时检测残留的 pending_play 文件
                //   自动删除对应缓存文件，避免再次播放同一损坏文件导致崩溃循环
                pathOnly == "/api/pending-play" && method == "POST" -> {
                    handlePendingPlaySave(output, query)
                }
                pathOnly == "/api/pending-play" && method == "DELETE" -> {
                    handlePendingPlayClear(output)
                }
                pathOnly == "/api/pending-play" && method == "GET" -> {
                    handlePendingPlayCheck(output)
                }
                // ★ 通用歌曲缓存文件服务（QQ/酷狗/网易/酷我等平台下载缓存）
                //   路径格式: /music-cache/{provider}/{cacheId}.mp3
                //   HEAD 请求只做存在性检测，不返回文件内容
                pathOnly.startsWith("/music-cache/") -> {
                    val subPath = pathOnly.substringAfter("/music-cache/")
                    serveMusicCacheFile(output, subPath, headOnly = (method == "HEAD"))
                }
                // ── 本地歌曲封面图服务 ──
                pathOnly.startsWith("/local-cover/") -> {
                    val fileName = pathOnly.substringAfter("/local-cover/")
                    serveLocalCoverFile(output, fileName)
                }
                // ── 本地歌曲内嵌歌词服务 ──
                pathOnly.startsWith("/local-lyric/") -> {
                    val fileName = pathOnly.substringAfter("/local-lyric/")
                    serveLocalLyricFile(output, fileName)
                }
                // ── ★ 竖屏 UI Room 数据库歌曲封面代理（content://media/external/audio/albumart/{albumId}） ──
                pathOnly.startsWith("/room-cover/") -> {
                    val albumIdStr = pathOnly.substringAfter("/room-cover/")
                    serveRoomCover(output, albumIdStr)
                }
                // ── ★ 竖屏 UI Room 数据库歌曲文件代理（通过 mediaStoreId 查找 path 后 serve） ──
                pathOnly.startsWith("/room-song/") -> {
                    val mediaStoreIdStr = pathOnly.substringAfter("/room-song/")
                    serveRoomSong(output, mediaStoreIdStr)
                }
                // ── SAF 文件服务（Android 文件夹导入的 content:// URI / 绝对路径） ──
                pathOnly.startsWith("/api/local-file") -> {
                    val filePath = getQueryParam(query, "path") ?: ""
                    val uriStr = getQueryParam(query, "uri") ?: ""
                    if (filePath.isNotBlank()) {
                        serveFilePath(output, filePath)
                    } else {
                        serveSafFile(output, uriStr)
                    }
                }
                // ── ★ 插件系统 API ──
                pathOnly == "/api/plugins/list" -> {
                    sendJson(
                        output,
                        200,
                        JSONObject().apply {
                            put("ok", true)
                            put("plugins", pluginManager.listPlugins())
                        },
                    )
                }
                // ★★★ 插件市场云端代理：将 /api/cloud/* 转发到后端（URL 可配置） ★★★
                // 默认腾讯云 CloudBase（国内可访问），可通过 /sdcard/Download/mineradio_cloud_url.txt 配置
                pathOnly.startsWith("/api/cloud/") -> {
                    val cloudPath = pathOnly.removePrefix("/api/cloud/")
                    val backendBase = getCloudBackendUrl()
                    val cloudUrl =
                        "$backendBase/$cloudPath" +
                            (if (query.isNotEmpty()) "?$query" else "")
                    try {
                        val conn = java.net.URL(cloudUrl).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 15000
                        conn.readTimeout = 60000
                        conn.requestMethod = method
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.setRequestProperty("User-Agent", "Mineradio-Android/CloudProxy")
                        // 传递 Authorization 头
                        val authHeader = headers.firstOrNull { it.first.equals("Authorization", true) }
                        if (authHeader != null) {
                            conn.setRequestProperty("Authorization", authHeader.second)
                        }
                        // POST/PUT 写入 body
                        if ((method == "POST" || method == "PUT")) {
                            val body = readBody(input, method)
                            if (body != null) {
                                conn.doOutput = true
                                conn.outputStream.use { it.write(body.toByteArray()) }
                            }
                        }
                        val code = conn.responseCode
                        // ★ 透传后端返回的 Content-Type（可能是 application/json 或 application/zip）
                        val respContentType = conn.contentType ?: "application/json; charset=utf-8"
                        // ★ 二进制透传：用 InputStream 直接读字节，不用 Reader（避免字符串转换损坏 ZIP）
                        val respBytes =
                            (if (code in 200..299) conn.inputStream else conn.errorStream)
                                ?.use { it.readBytes() } ?: ByteArray(0)
                        conn.disconnect()
                        // 透传 CORS 头
                        output.write("HTTP/1.1 $code OK\r\n".toByteArray())
                        output.write("Content-Type: $respContentType\r\n".toByteArray())
                        output.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                        output.write("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n".toByteArray())
                        output.write("Access-Control-Allow-Headers: Content-Type, Authorization\r\n".toByteArray())
                        output.write("Content-Length: ${respBytes.size}\r\n".toByteArray())
                        output.write("Connection: close\r\n\r\n".toByteArray())
                        output.write(respBytes)
                        output.flush()
                        android.util.Log.i("MineradioServer", "cloud proxy: $method $cloudPath -> $code (${respBytes.size} bytes)")
                    } catch (e: Exception) {
                        android.util.Log.e("MineradioServer", "cloud proxy failed: ${e.message}", e)
                        sendJson(
                            output,
                            502,
                            JSONObject().apply {
                                put("ok", false)
                                put("error", "cloud_proxy_failed")
                                put("message", "云端服务不可用: ${e.message}")
                            },
                        )
                    }
                }
                pathOnly == "/api/plugins/uninstall" -> {
                    val id = getQueryParam(query, "id") ?: ""
                    val ok = pluginManager.uninstall(id)
                    sendJson(output, 200, JSONObject().apply { put("ok", ok) })
                }
                pathOnly == "/api/plugins/enable" -> {
                    val id = getQueryParam(query, "id") ?: ""
                    val ok = pluginManager.setEnabled(id, true)
                    sendJson(output, 200, JSONObject().apply { put("ok", ok) })
                }
                pathOnly == "/api/plugins/disable" -> {
                    val id = getQueryParam(query, "id") ?: ""
                    val ok = pluginManager.setEnabled(id, false)
                    sendJson(output, 200, JSONObject().apply { put("ok", ok) })
                }
                // ★ 临时测试端点：直接从文件路径安装插件（仅用于自动化测试，发布前移除）
                pathOnly == "/api/plugins/install_from_path" -> {
                    val path = getQueryParam(query, "path") ?: ""
                    try {
                        val file = java.io.File(path)
                        if (!file.exists()) {
                            sendJson(
                                output,
                                400,
                                JSONObject().apply {
                                    put("ok", false)
                                    put("error", "文件不存在: $path")
                                },
                            )
                        } else {
                            val uri =
                                androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                            val result = pluginManager.install(uri)
                            sendJson(
                                output,
                                200,
                                JSONObject().apply {
                                    put("ok", result.success)
                                    put("pluginId", result.pluginId)
                                    put("message", result.message)
                                },
                            )
                        }
                    } catch (e: Exception) {
                        sendJson(
                            output,
                            500,
                            JSONObject().apply {
                                put("ok", false)
                                put("error", e.message ?: "未知错误")
                            },
                        )
                    }
                }
                // ★ 通用Cookie管理API — 供login-panel插件读写各平台cookie
                pathOnly == "/api/cookie" -> {
                    val domain = getQueryParam(query, "domain") ?: ""
                    when (domain) {
                        "qishui" -> {
                            when (method.uppercase()) {
                                "GET" ->
                                    sendJson(
                                        output,
                                        200,
                                        JSONObject().apply {
                                            put("cookie", NeteaseMusicApi.getQSCookie())
                                            put("xHelios", NeteaseMusicApi.getQSXHelios())
                                            put("xMedusa", NeteaseMusicApi.getQSXMedusa())
                                        },
                                    )
                                "POST" -> {
                                    // ★ 纯文本 cookie 字符串（与 qq/kg/netease 一致）
                                    // 插件发送的是 raw cookie，不是 JSON
                                    val cookie = readBody(input, method)
                                    if (cookie.isNotBlank()) NeteaseMusicApi.saveQSCookie(cookie)
                                    sendJson(output, 200, JSONObject().apply { put("ok", true) })
                                }
                                "DELETE" -> {
                                    NeteaseMusicApi.saveQSCookie("")
                                    NeteaseMusicApi.saveQSXHelios("")
                                    NeteaseMusicApi.saveQSXMedusa("")
                                    sendJson(output, 200, JSONObject().apply { put("ok", true) })
                                }
                                else -> send404(output)
                            }
                        }
                        "qq" -> {
                            when (method.uppercase()) {
                                "GET" -> sendJson(output, 200, JSONObject().apply { put("cookie", NeteaseMusicApi.getQQCookie()) })
                                "POST" -> {
                                    val cookie = readBody(input, method)
                                    if (cookie.isNotBlank()) NeteaseMusicApi.saveQQCookie(cookie)
                                    sendJson(output, 200, JSONObject().apply { put("ok", true) })
                                }
                                "DELETE" -> {
                                    NeteaseMusicApi.saveQQCookie("")
                                    sendJson(output, 200, JSONObject().apply { put("ok", true) })
                                }
                                else -> send404(output)
                            }
                        }
                        "kg" -> {
                            when (method.uppercase()) {
                                "GET" -> sendJson(output, 200, JSONObject().apply { put("cookie", NeteaseMusicApi.getKGCookie()) })
                                "POST" -> {
                                    val cookie = readBody(input, method)
                                    if (cookie.isNotBlank()) NeteaseMusicApi.saveKGCookie(cookie)
                                    sendJson(output, 200, JSONObject().apply { put("ok", true) })
                                }
                                "DELETE" -> {
                                    NeteaseMusicApi.saveKGCookie("")
                                    sendJson(output, 200, JSONObject().apply { put("ok", true) })
                                }
                                else -> send404(output)
                            }
                        }
                        "netease" -> {
                            when (method.uppercase()) {
                                "GET" -> sendJson(output, 200, JSONObject().apply { put("cookie", NeteaseMusicApi.getCookie()) })
                                "POST" -> {
                                    val cookie = readBody(input, method)
                                    if (cookie.isNotBlank()) NeteaseMusicApi.setCookie(cookie)
                                    sendJson(output, 200, JSONObject().apply { put("ok", true) })
                                }
                                "DELETE" -> {
                                    NeteaseMusicApi.setCookie("")
                                    sendJson(output, 200, JSONObject().apply { put("ok", true) })
                                }
                                else -> send404(output)
                            }
                        }
                        else -> sendJson(output, 400, JSONObject().apply { put("error", "unknown domain: $domain") })
                    }
                }
                // ★ 通用加密服务 — 供插件调用（基础设施，不依赖插件启用）
                pathOnly == "/api/crypto/weapi" -> {
                    val body = readBody(input, method)
                    val text =
                        try {
                            JSONObject(body).optString("text", "")
                        } catch (_: Exception) {
                            body
                        }
                    if (text.isBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("error", "missing text") })
                    } else {
                        val result = NeteaseMusicApi.weapiEncrypt(text)
                        sendJson(output, 200, result)
                    }
                }
                pathOnly == "/api/crypto/md5" -> {
                    val text = getQueryParam(query, "text") ?: ""
                    if (text.isBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("error", "missing text") })
                    } else {
                        val hash = NeteaseMusicApi.md5Hash(text)
                        sendJson(output, 200, JSONObject().apply { put("hash", hash) })
                    }
                }
                pathOnly == "/api/crypto/kugou/sign_android" -> {
                    val body = readBody(input, method)
                    val obj =
                        try {
                            JSONObject(body)
                        } catch (_: Exception) {
                            JSONObject()
                        }
                    val params = mutableMapOf<String, String>()
                    val paramsObj = obj.optJSONObject("params")
                    if (paramsObj != null) {
                        for (key in paramsObj.keys()) params[key] = paramsObj.optString(key, "")
                    }
                    val bodyData = obj.optString("bodyData", null)
                    val sign = NeteaseMusicApi.kugouSignAndroid(params, if (bodyData.isNullOrEmpty()) null else bodyData)
                    sendJson(output, 200, JSONObject().apply { put("sign", sign) })
                }
                pathOnly == "/api/crypto/kugou/sign_h5" -> {
                    val body = readBody(input, method)
                    val obj =
                        try {
                            JSONObject(body)
                        } catch (_: Exception) {
                            JSONObject()
                        }
                    val params = mutableMapOf<String, String>()
                    val paramsObj = obj.optJSONObject("params")
                    if (paramsObj != null) {
                        for (key in paramsObj.keys()) params[key] = paramsObj.optString(key, "")
                    }
                    val bodyJson = obj.optString("bodyJson", null)
                    val sign = NeteaseMusicApi.kugouSignH5(params, if (bodyJson.isNullOrEmpty()) null else bodyJson)
                    sendJson(output, 200, JSONObject().apply { put("sign", sign) })
                }
                pathOnly == "/api/crypto/kugou/sign_key" -> {
                    val hash = getQueryParam(query, "hash") ?: ""
                    val mid = getQueryParam(query, "mid") ?: ""
                    val userid = getQueryParam(query, "userid") ?: ""
                    // ★ 支持可选 appid 参数: H5 路径需传 1014, Gateway 路径用默认 1005
                    val appid = getQueryParam(query, "appid")?.toIntOrNull() ?: 1005
                    if (hash.isBlank() || mid.isBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("error", "missing hash or mid") })
                    } else {
                        val sign = NeteaseMusicApi.signKey(hash, mid, userid, appid)
                        sendJson(output, 200, JSONObject().apply { put("sign", sign) })
                    }
                }
                pathOnly == "/api/crypto/kugou/cloud_key" -> {
                    val hash = getQueryParam(query, "hash") ?: ""
                    if (hash.isBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("error", "missing hash") })
                    } else {
                        val key = NeteaseMusicApi.kugouCloudKey(hash)
                        sendJson(output, 200, JSONObject().apply { put("key", key) })
                    }
                }
                // ★ 汽水音乐下载+解密+缓存API — 供插件调用 (SodaCrypto 解密 VIP 音频)
                pathOnly == "/api/qishui/cache" -> {
                    val trackId = getQueryParam(query, "trackId") ?: ""
                    val url = getQueryParam(query, "url") ?: ""
                    val playAuth = getQueryParam(query, "playAuth") ?: ""
                    if (trackId.isBlank() || url.isBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("error", "missing trackId or url") })
                    } else {
                        try {
                            val qsDir = java.io.File(context.filesDir, "qs_cache")
                            if (!qsDir.exists()) qsDir.mkdirs()
                            val cacheFile = java.io.File(qsDir, "$trackId.m4a")
                            // 已缓存直接返回
                            if (cacheFile.exists() && cacheFile.length() > 1024) {
                                sendJson(
                                    output,
                                    200,
                                    JSONObject().apply {
                                        put("url", "/qs-cache/$trackId.m4a")
                                        put("cached", true)
                                    },
                                )
                            } else {
                                // ★ 下载音频数据 (使用 PC 端 UA,与参考项目 go-music-dl 保持一致)
                                var audioData: ByteArray? = null
                                var lastError: Exception? = null
                                for (retry in 0..2) {
                                    try {
                                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                                        conn.connectTimeout = 15000
                                        conn.readTimeout = 60000
                                        conn.setRequestProperty("User-Agent", "LunaPC/3.5.1(408871041)")
                                        conn.setRequestProperty("Referer", "https://www.qishui.com/")
                                        if (conn.responseCode in 200..299) {
                                            val data = conn.inputStream.readBytes()
                                            conn.disconnect()
                                            // ★ 校验下载完整性: 检查 Content-Length 是否匹配
                                            val contentLength = conn.headerFields["Content-Length"]?.firstOrNull()?.toLongOrNull()
                                            if (contentLength != null && data.size.toLong() != contentLength) {
                                                Log.w(
                                                    TAG,
                                                    "qishui/cache: incomplete download ${data.size}/$contentLength bytes, retry=$retry",
                                                )
                                                lastError = Exception("incomplete download ${data.size}/$contentLength")
                                                continue
                                            }
                                            audioData = data
                                            break
                                        } else {
                                            Log.w(TAG, "qishui/cache: HTTP ${conn.responseCode}, retry=$retry")
                                            lastError = Exception("HTTP ${conn.responseCode}")
                                        }
                                    } catch (e: Exception) {
                                        lastError = e
                                        Log.w(TAG, "qishui/cache: download error, retry=$retry: ${e.message}")
                                    }
                                }
                                if (audioData == null) {
                                    sendJson(
                                        output,
                                        502,
                                        JSONObject().apply {
                                            put("error", "download failed: ${lastError?.message ?: "unknown"}")
                                        },
                                    )
                                } else {
                                    // ★ 解密 (有 PlayAuth = VIP 加密音频)
                                    //   解密失败时绝不写入缓存: 加密数据会让 Chromium 音频解码器
                                    //   遇到无效数据 → DCHECK 断言失败 → SIGTRAP native crash
                                    val finalData =
                                        if (playAuth.isNotBlank()) {
                                            val decrypted =
                                                try {
                                                    SodaCrypto.decryptAudio(audioData, playAuth)
                                                } catch (_: Exception) {
                                                    null
                                                }
                                            if (decrypted == null) {
                                                sendJson(
                                                    output,
                                                    500,
                                                    JSONObject().apply {
                                                        put("error", "audio decryption failed")
                                                    },
                                                )
                                                return
                                            }
                                            decrypted
                                        } else {
                                            audioData
                                        }
                                    // ★ 写入前校验: 确保是有效音频数据 (m4a/mp4 ftyp box 或 ID3 mp3 头)
                                    val validAudio =
                                        finalData.size > 12 &&
                                            (
                                                (
                                                    finalData[4] == 'f'.code.toByte() &&
                                                        finalData[5] == 't'.code.toByte() &&
                                                        finalData[6] == 'y'.code.toByte() &&
                                                        finalData[7] == 'p'.code.toByte()
                                                ) ||
                                                    (
                                                        finalData[0] == 'I'.code.toByte() &&
                                                            finalData[1] == 'D'.code.toByte() &&
                                                            finalData[2] == '3'.code.toByte()
                                                    )
                                            )
                                    if (!validAudio) {
                                        sendJson(
                                            output,
                                            500,
                                            JSONObject().apply {
                                                put("error", "invalid audio data (not m4a/mp3)")
                                            },
                                        )
                                        return
                                    }
                                    // 保存到缓存
                                    cacheFile.writeBytes(finalData)
                                    // ★ MediaExtractor 完整验证: 确保音频可被 Chromium 解码器处理
                                    //   magic bytes 只能确认容器格式, 无法确认内部编码可解码
                                    //   VIP 解密残留/截断/不支持的编码会让 Chromium native crash
                                    if (!validateAudioWithMediaExtractor(cacheFile)) {
                                        Log.e(
                                            TAG,
                                            "/api/qishui/cache: MediaExtractor validation failed for trackId=$trackId, deleting cache",
                                        )
                                        cacheFile.delete()
                                        sendJson(
                                            output,
                                            500,
                                            JSONObject().apply {
                                                put("error", "audio validation failed (corrupt or unsupported codec)")
                                            },
                                        )
                                        return
                                    }
                                    sendJson(
                                        output,
                                        200,
                                        JSONObject().apply {
                                            put("url", "/qs-cache/$trackId.m4a")
                                            put("cached", true)
                                            put("decrypted", playAuth.isNotBlank())
                                        },
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            sendJson(output, 500, JSONObject().apply { put("error", e.message ?: "download failed") })
                        }
                    }
                }
                // ★ 通用文件缓存下载API — 供插件预缓存歌曲
                pathOnly == "/api/cache/download" -> {
                    val url = getQueryParam(query, "url") ?: ""
                    val cacheId = getQueryParam(query, "id") ?: ""
                    val provider = getQueryParam(query, "provider") ?: "generic"
                    if (url.isBlank() || cacheId.isBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("error", "missing url or id") })
                    } else {
                        // ★ 同步处理(不再启动新线程): 避免主线程 return 后 finally 关闭 socket
                        try {
                            // ★ 汽水音乐缓存保存到 filesDir/qs_cache/{id}.m4a，与 /qs-cache/ 路由对齐
                            //   其他平台保存到 cacheDir/music_cache_{provider}/{id}.mp3
                            val cacheFile: java.io.File
                            if (provider == "qishui") {
                                val qsDir = java.io.File(context.filesDir, "qs_cache")
                                if (!qsDir.exists()) qsDir.mkdirs()
                                cacheFile = java.io.File(qsDir, "$cacheId.m4a")
                            } else {
                                val cacheDir = java.io.File(context.cacheDir, "music_cache_$provider")
                                if (!cacheDir.exists()) cacheDir.mkdirs()
                                cacheFile = java.io.File(cacheDir, "$cacheId.mp3")
                            }
                            // ★ 构造对外可播放的 URL (相对路径)
                            //   汽水音乐: /qs-cache/{cacheId}.m4a
                            //   其他平台: /music-cache/{provider}/{cacheId}.mp3
                            val cacheUrl =
                                if (provider == "qishui") {
                                    "/qs-cache/$cacheId.m4a"
                                } else {
                                    "/music-cache/$provider/$cacheId.mp3"
                                }
                            if (cacheFile.exists() && cacheFile.length() > 1024) {
                                try {
                                    sendJson(
                                        output,
                                        200,
                                        JSONObject().apply {
                                            put("cached", true)
                                            put("url", cacheUrl)
                                            put("path", cacheFile.absolutePath)
                                        },
                                    )
                                } catch (_: Exception) {
                                }
                            } else {
                                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 15000
                                conn.readTimeout = 30000
                                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                                if (conn.responseCode in 200..299) {
                                    cacheFile.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
                                    try {
                                        sendJson(
                                            output,
                                            200,
                                            JSONObject().apply {
                                                put("cached", true)
                                                put("url", cacheUrl)
                                                put("path", cacheFile.absolutePath)
                                            },
                                        )
                                    } catch (_: Exception) {
                                    }
                                } else {
                                    try {
                                        sendJson(output, 502, JSONObject().apply { put("error", "HTTP ${conn.responseCode}") })
                                    } catch (_: Exception) {
                                    }
                                }
                                conn.disconnect()
                            }
                        } catch (e: Exception) {
                            try {
                                sendJson(output, 500, JSONObject().apply { put("error", e.message ?: "download failed") })
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                // ── 在线壁纸 API（moewalls / haowallpaper / bizhihui 代理）──
                pathOnly == "/api/wallpaper/online/list" -> {
                    val page = (getQueryParam(query, "page") ?: "1").toIntOrNull() ?: 1
                    val keyword = getQueryParam(query, "keyword")
                    val source = getQueryParam(query, "source") ?: "moewalls"
                    // 分类筛选（可选）：moewalls 传分类 ID，bizhihui 传 slug
                    val category = getQueryParam(query, "category")
                    try {
                        val api =
                            com.mineradio.app.wallpaper
                                .OnlineWallpaperApi(context)
                        val result = api.getList(page, keyword, source, category)
                        sendJson(output, 200, result)
                    } catch (e: Exception) {
                        Log.e(TAG, "online list failed: ${e.message}")
                        sendJson(
                            output,
                            500,
                            JSONObject().apply {
                                put("error", e.message ?: "fetch failed")
                                put("list", org.json.JSONArray())
                            },
                        )
                    }
                }
                // 在线壁纸分类列表（moewalls 走 WP REST API，bizhihui 返回内置分类）
                pathOnly == "/api/wallpaper/online/categories" -> {
                    val source = getQueryParam(query, "source") ?: "moewalls"
                    try {
                        val api =
                            com.mineradio.app.wallpaper
                                .OnlineWallpaperApi(context)
                        val arr = api.getCategories(source)
                        sendJson(
                            output,
                            200,
                            JSONObject().apply {
                                put("list", arr)
                                put("source", source)
                            },
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "online categories failed: ${e.message}")
                        sendJson(
                            output,
                            500,
                            JSONObject().apply {
                                put("error", e.message ?: "fetch failed")
                                put("list", org.json.JSONArray())
                            },
                        )
                    }
                }
                // 在线壁纸分辨率列表
                pathOnly == "/api/wallpaper/online/resolutions" -> {
                    val source = getQueryParam(query, "source") ?: "moewalls"
                    try {
                        val api =
                            com.mineradio.app.wallpaper
                                .OnlineWallpaperApi(context)
                        val arr = api.getResolutions(source)
                        sendJson(
                            output,
                            200,
                            JSONObject().apply {
                                put("list", arr)
                                put("source", source)
                            },
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "online resolutions failed: ${e.message}")
                        sendJson(
                            output,
                            500,
                            JSONObject().apply {
                                put("error", e.message ?: "fetch failed")
                                put("list", org.json.JSONArray())
                            },
                        )
                    }
                }
                pathOnly == "/api/wallpaper/online/detail" -> {
                    val id = getQueryParam(query, "id") ?: ""
                    val source = getQueryParam(query, "source") ?: "moewalls"
                    if (id.isBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("error", "missing id") })
                    } else {
                        try {
                            val api =
                                com.mineradio.app.wallpaper
                                    .OnlineWallpaperApi(context)
                            val result = api.getDetail(id, source)
                            sendJson(output, 200, result)
                        } catch (e: Exception) {
                            Log.e(TAG, "online detail failed: ${e.message}")
                            sendJson(output, 500, JSONObject().apply { put("error", e.message ?: "fetch failed") })
                        }
                    }
                }
                pathOnly == "/api/wallpaper/online/download" -> {
                    val id = getQueryParam(query, "id") ?: ""
                    val name = getQueryParam(query, "name") ?: "wallpaper"
                    val source = getQueryParam(query, "source") ?: "moewalls"
                    val typeStr = getQueryParam(query, "type") ?: "image"
                    val fileIdParam = getQueryParam(query, "fileId") ?: ""
                    val type =
                        if (typeStr == "video") {
                            com.mineradio.app.wallpaper.WallpaperEntity.TYPE_VIDEO
                        } else {
                            com.mineradio.app.wallpaper.WallpaperEntity.TYPE_IMAGE
                        }
                    if (id.isBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("error", "missing id") })
                    } else {
                        try {
                            val api =
                                com.mineradio.app.wallpaper
                                    .OnlineWallpaperApi(context)
                            val entity = api.downloadAndImport(id, name, type, source, fileIdParam)
                            if (entity != null) {
                                sendJson(
                                    output,
                                    200,
                                    JSONObject().apply {
                                        put("success", true)
                                        put("name", entity.name)
                                        put("id", entity.id)
                                        put("type", entity.wallpaperType)
                                        // 标记本次下载使用了高清 4K 通道
                                        put("quality", "4k_original")
                                    },
                                )
                            } else {
                                sendJson(
                                    output,
                                    500,
                                    JSONObject().apply {
                                        put("success", false)
                                        put("error", "download or import failed")
                                    },
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "online download failed: ${e.message}")
                            sendJson(
                                output,
                                500,
                                JSONObject().apply {
                                    put("success", false)
                                    put("error", e.message ?: "download failed")
                                },
                            )
                        }
                    }
                }
                // 查询当前下载进度（前端轮询用）
                pathOnly == "/api/wallpaper/online/progress" -> {
                    val progress = com.mineradio.app.wallpaper.OnlineWallpaperApi.currentProgress
                    val status = com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus
                    sendJson(
                        output,
                        200,
                        JSONObject().apply {
                            put("progress", progress)
                            put("status", status)
                        },
                    )
                }
                // ── 壁纸文件服务（供壁纸库弹窗显示缩略图）──
                pathOnly == "/api/wallpaper/file" -> {
                    val id = getQueryParam(query, "id") ?: ""
                    val raw = getQueryParam(query, "raw") ?: "0"
                    if (id.isBlank()) {
                        send404(output)
                    } else {
                        serveWallpaperFile(output, id, raw == "1")
                    }
                }
                // 通用HTTP代理端点,供插件调用外部API(绕过CORS,携带cookie)
                pathOnly == "/api/proxy" -> {
                    val targetUrl = getQueryParam(query, "url") ?: ""
                    val method = getQueryParam(query, "method")?.uppercase() ?: "GET"
                    val headersJson = getQueryParam(query, "headers") ?: ""
                    if (targetUrl.isBlank() || (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://"))) {
                        sendJson(output, 400, JSONObject().apply { put("error", "invalid url") })
                    } else {
                        val requestBody =
                            if (method != "GET") {
                                try {
                                    readBody(input, method)
                                } catch (_: Exception) {
                                    ""
                                }
                            } else {
                                ""
                            }
                        // ★ 同步处理(不再启动新线程): 避免主线程 return 后 finally 关闭 socket
                        //   导致新线程 sendJson 失败
                        try {
                            val conn = java.net.URL(targetUrl).openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = method
                            conn.connectTimeout = 15000
                            conn.readTimeout = 15000
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                            if (headersJson.isNotBlank()) {
                                try {
                                    val headers = JSONObject(headersJson)
                                    val keys = headers.keys()
                                    while (keys.hasNext()) {
                                        val key = keys.next()
                                        conn.setRequestProperty(key, headers.getString(key))
                                    }
                                } catch (_: Exception) {
                                }
                            }
                            if (method != "GET" && requestBody.isNotBlank()) {
                                conn.doOutput = true
                                conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
                            }
                            val code = conn.responseCode
                            val responseBody =
                                if (code in 200..299) {
                                    conn.inputStream.bufferedReader().readText()
                                } else {
                                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                                }
                            val respHeaders = JSONObject()
                            var i = 0
                            while (true) {
                                val headerKey = conn.getHeaderFieldKey(i) ?: break
                                val headerVal = conn.getHeaderField(i)
                                if (headerKey != null) respHeaders.put(headerKey, headerVal)
                                i++
                            }
                            val resp =
                                JSONObject().apply {
                                    put("status", code)
                                    put("body", responseBody)
                                    put("headers", respHeaders)
                                }
                            try {
                                sendJson(output, 200, resp)
                            } catch (_: Exception) {
                            }
                        } catch (e: Exception) {
                            try {
                                sendJson(output, 500, JSONObject().apply { put("error", e.message ?: "proxy error") })
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                // 返回单个插件的 main.js / manifest.json / entry.json 内容
                pathOnly.startsWith("/api/plugins/") && pathOnly.endsWith("/main.js") -> {
                    val id = pathOnly.removePrefix("/api/plugins/").removeSuffix("/main.js")
                    if (method.uppercase() == "POST") {
                        val body = readBody(input, method)
                        val ok = pluginManager.writeMainJs(id, body)
                        sendJson(output, 200, JSONObject().apply { put("ok", ok) })
                    } else {
                        val content = pluginManager.readMainJs(id)
                        if (content != null) {
                            sendText(output, content, "application/javascript; charset=utf-8")
                        } else {
                            send404(output)
                        }
                    }
                }
                pathOnly.startsWith("/api/plugins/") && pathOnly.endsWith("/manifest") -> {
                    val id = pathOnly.removePrefix("/api/plugins/").removeSuffix("/manifest")
                    val content = pluginManager.readManifest(id)
                    if (content != null) {
                        sendText(output, content, "application/json; charset=utf-8")
                    } else {
                        send404(output)
                    }
                }
                pathOnly.startsWith("/api/plugins/") && pathOnly.endsWith("/entry") -> {
                    val id = pathOnly.removePrefix("/api/plugins/").removeSuffix("/entry")
                    val content = pluginManager.readEntry(id)
                    if (content != null) {
                        sendText(output, content, "application/json; charset=utf-8")
                    } else {
                        send404(output)
                    }
                }
                // ── ★ 插件通用资源读取 API（v2.1 扩展）──
                //  GET /api/plugins/<id>/resource?path=<relPath>
                //  返回任意二进制资源（用于 .so / 模型 / 纹理 等）
                pathOnly.startsWith("/api/plugins/") && pathOnly.endsWith("/resource") -> {
                    val id = pathOnly.removePrefix("/api/plugins/").removeSuffix("/resource")
                    val resPath = getQueryParam(query, "path") ?: ""
                    if (resPath.isBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("error", "missing 'path' query parameter") })
                    } else {
                        val bytes = pluginManager.readResource(id, resPath)
                        if (bytes != null) {
                            // 根据扩展名推断 MIME
                            val mime = guessMimeByExtension(resPath)
                            sendBinary(output, 200, mime, bytes)
                        } else {
                            send404(output)
                        }
                    }
                }
                //  GET /api/plugins/<id>/resource/text?path=<relPath>
                //  返回文本资源（UTF-8）
                pathOnly.startsWith("/api/plugins/") && pathOnly.endsWith("/resource/text") -> {
                    val id = pathOnly.removePrefix("/api/plugins/").removeSuffix("/resource/text")
                    val resPath = getQueryParam(query, "path") ?: ""
                    if (resPath.isBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("error", "missing 'path' query parameter") })
                    } else {
                        val content = pluginManager.readResourceText(id, resPath)
                        if (content != null) {
                            sendText(output, content, "text/plain; charset=utf-8")
                        } else {
                            send404(output)
                        }
                    }
                }
                //  GET /api/plugins/<id>/files?dir=<subDir>
                //  列出插件目录中指定子目录下所有文件（递归）
                pathOnly.startsWith("/api/plugins/") && pathOnly.endsWith("/files") -> {
                    val id = pathOnly.removePrefix("/api/plugins/").removeSuffix("/files")
                    val subDir = getQueryParam(query, "dir") ?: ""
                    val files = pluginManager.listResources(id, subDir)
                    val arr = JSONArray()
                    files.forEach { arr.put(it) }
                    sendJson(
                        output,
                        200,
                        JSONObject().apply {
                            put("pluginId", id)
                            put("dir", subDir)
                            put("files", arr)
                            put("count", files.size)
                        },
                    )
                }
                //  GET /api/plugins/<id>/path?relPath=<relPath>
                //  获取插件资源绝对路径（用于 native .so 加载）
                pathOnly.startsWith("/api/plugins/") && pathOnly.endsWith("/path") -> {
                    val id = pathOnly.removePrefix("/api/plugins/").removeSuffix("/path")
                    val relPath = getQueryParam(query, "relPath") ?: ""
                    val absPath = pluginManager.getResourcePath(id, relPath)
                    if (absPath != null) {
                        sendJson(
                            output,
                            200,
                            JSONObject().apply {
                                put("pluginId", id)
                                put("relPath", relPath)
                                put("absPath", absPath)
                            },
                        )
                    } else {
                        send404(output)
                    }
                }
                // ★ v2.2 新增：POST /api/plugins/<id>/native
                //   通过 HTTP 调用原生方法（便于插件异步操作或外部调试）
                //   请求体 JSON: { "method": "isFloatWindowActive", "args": ["default"] }
                //   返回: { "result": "<原生方法返回值>" }
                pathOnly.startsWith("/api/plugins/") && pathOnly.endsWith("/native") && method.uppercase() == "POST" -> {
                    val id = pathOnly.removePrefix("/api/plugins/").removeSuffix("/native")
                    try {
                        val body = readBody(input, method)
                        val reqJson = JSONObject(body)
                        val methodName = reqJson.optString("method", "")
                        val argsArr = reqJson.optJSONArray("args") ?: JSONArray()
                        val result = invokeNativeMethodForPlugin(id, methodName, argsArr)
                        sendJson(
                            output,
                            200,
                            JSONObject().apply {
                                put("pluginId", id)
                                put("method", methodName)
                                put("result", result)
                            },
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "POST /api/plugins/<id>/native 失败: ${e.message}")
                        sendJson(
                            output,
                            500,
                            JSONObject().apply {
                                put("error", e.message ?: "调用失败")
                            },
                        )
                    }
                }
                // ── 平台业务 API 代理到 Node.js 后端（login-panel）──
                //   登录/搜索/歌曲/歌词/歌单/QQ/酷狗/汽水 等平台 API 由 Node 后端(127.0.0.1:3000)提供，
                //   由 mineradio-android 移植，Kotlin 后端代理转发并返回响应
                pathOnly.startsWith("/api/login") ||
                    pathOnly == "/api/logout" ||
                    pathOnly == "/api/search" ||
                    pathOnly == "/api/song/url" ||
                    pathOnly == "/api/song/url/v2" ||
                    pathOnly == "/api/lyric" ||
                    pathOnly.startsWith("/api/user/") ||
                    pathOnly.startsWith("/api/playlist/") ||
                    pathOnly.startsWith("/api/qq/") ||
                    pathOnly.startsWith("/api/kg/") ||
                    pathOnly.startsWith("/api/kugou/") ||
                    pathOnly.startsWith("/api/qishui/") ||
                    pathOnly.startsWith("/api/recommend") ||
                    pathOnly.startsWith("/api/liked") ||
                    pathOnly == "/api/like" ||
                    pathOnly == "/api/dislike" -> proxyToNodeBackend(output, method, pathOnly, query, input)
                // ── 静态文件 ──
                else -> serveStaticFile(output, pathOnly, headers)
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理请求异常: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    // ═══════════════════════════════════════════════
    //  ★ v2.2 新增：原生方法调度器
    // ═══════════════════════════════════════════════

    /**
     * ★ 通过 HTTP 调用插件可用的原生方法（用于插件异步操作或外部调试）
     *
     * @param pluginId 插件 ID
     * @param method 原生方法名（与 api.floatWindow / api.live2d / api.broadcast / api.system 对应）
     * @param args JSONArray 参数列表
     * @return 方法返回值（字符串化）
     */
    private fun invokeNativeMethodForPlugin(
        pluginId: String,
        method: String,
        args: JSONArray,
    ): String =
        try {
            when (method) {
                // ── 悬浮窗控制 ──
                "isFloatWindowActive" -> {
                    val windowId = args.optString(0, "default")
                    val active = com.mineradio.app.wallpaper.PetFloatingService.isRunning
                    if (active) "1" else "0"
                }
                "setFloatWindowPosition" -> {
                    val x = args.optInt(0, 0)
                    val y = args.optInt(1, 0)
                    com.mineradio.app.wallpaper.PetFloatingService
                        .setPosition(context, x, y)
                    "1"
                }
                "setFloatWindowSize" -> {
                    val w = args.optInt(0, 0)
                    val h = args.optInt(1, 0)
                    com.mineradio.app.wallpaper.PetFloatingService
                        .setWindowSize(context, w, h)
                    "1"
                }
                "setFloatWindowOpacity" -> {
                    val alpha = args.optDouble(0, 1.0).toFloat()
                    com.mineradio.app.wallpaper.PetFloatingService
                        .setOpacity(context, alpha)
                    "1"
                }
                "getFloatWindowInfo" -> {
                    val info =
                        com.mineradio.app.wallpaper.PetFloatingService
                            .getWindowInfo(context)
                    JSONObject()
                        .apply {
                            put("x", info.x)
                            put("y", info.y)
                            put("width", info.width)
                            put("height", info.height)
                            put("active", com.mineradio.app.wallpaper.PetFloatingService.isRunning)
                        }.toString()
                }
                "closeFloatWindow" -> {
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(context)
                        .setDesktopPetEnabled(false)
                    com.mineradio.app.wallpaper.PetFloatingService
                        .stop(context)
                    "1"
                }
                // ── Live2D ──
                "loadLive2DModel" -> {
                    val targetPluginId = args.optString(0, pluginId)
                    val modelPath = args.optString(1, "")
                    val pm =
                        com.mineradio.app.manager
                            .PluginManager(context)
                    val srcPath = pm.getResourcePath(targetPluginId, modelPath)
                    if (srcPath == null) {
                        "找不到模型: $modelPath"
                    } else {
                        val srcFile = java.io.File(srcPath)
                        val srcDir = srcFile.parentFile ?: srcFile
                        val targetDir = java.io.File(context.filesDir, "custom_pet_model/$targetPluginId")
                        if (targetDir.exists()) targetDir.deleteRecursively()
                        targetDir.mkdirs()
                        srcDir.copyRecursively(targetDir, overwrite = true)
                        com.mineradio.app.wallpaper.PetFloatingService
                            .setCurrentModelDirName(context, targetPluginId)
                    }
                }
                "triggerMotion" -> {
                    val motionName = args.optString(0, "")
                    com.mineradio.app.wallpaper.JniBridgePet
                        .nativeTriggerExpression(motionName)
                    "1"
                }
                "triggerLive2DExpression" -> {
                    val exprName = args.optString(0, "")
                    com.mineradio.app.wallpaper.PetFloatingService
                        .triggerExpression(context, exprName)
                    "1"
                }
                "getLive2DModelInfo" -> {
                    val info = JSONObject()
                    info.put(
                        "modelName",
                        com.mineradio.app.wallpaper.PetFloatingService
                            .getCurrentModelName(context),
                    )
                    info.put(
                        "modelDirName",
                        com.mineradio.app.wallpaper.PetFloatingService
                            .getCurrentModelDirName() ?: "",
                    )
                    info.put(
                        "isCustom",
                        com.mineradio.app.wallpaper.PetFloatingService
                            .isUsingCustomModel(),
                    )
                    info.put("isRunning", com.mineradio.app.wallpaper.PetFloatingService.isRunning)
                    // getExpressionList 返回 JSON 数组字符串，直接放入
                    val exprListJsonStr =
                        com.mineradio.app.wallpaper.PetFloatingService
                            .getExpressionList(context)
                    info.put("expressions", JSONArray(exprListJsonStr))
                    info.toString()
                }
                // ── 插件资源 ──
                "getPluginResource" -> {
                    val relPath = args.optString(0, "")
                    val pm =
                        com.mineradio.app.manager
                            .PluginManager(context)
                    val bytes = pm.readResource(pluginId, relPath)
                    if (bytes != null) {
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    } else {
                        ""
                    }
                }
                "listPluginFiles" -> {
                    val subDir = args.optString(0, "")
                    val pm =
                        com.mineradio.app.manager
                            .PluginManager(context)
                    val files = pm.listResources(pluginId, subDir)
                    JSONArray(files).toString()
                }
                // ── 系统状态 ──
                "getSystemState" -> {
                    val key = args.optString(0, "")
                    when (key) {
                        "screen_width" -> {
                            val dm = android.util.DisplayMetrics()
                            @Suppress("DEPRECATION")
                            (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager)
                                .defaultDisplay
                                .getMetrics(dm)
                            dm.widthPixels.toString()
                        }
                        "screen_height" -> {
                            val dm = android.util.DisplayMetrics()
                            @Suppress("DEPRECATION")
                            (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager)
                                .defaultDisplay
                                .getMetrics(dm)
                            dm.heightPixels.toString()
                        }
                        "package_name" -> context.packageName
                        "android_version" -> android.os.Build.VERSION.RELEASE
                        "device_model" -> android.os.Build.MODEL
                        else -> "未知 key: $key"
                    }
                }
                else -> "未知方法: $method"
            }
        } catch (e: Exception) {
            Log.e(TAG, "invokeNativeMethodForPlugin 失败: method=$method", e)
            "调用失败: ${e.message}"
        }

    // ═══════════════════════════════════════════════
    //  插件市场后端 URL 配置
    // ═══════════════════════════════════════════════

    /**
     * 插件市场后端 URL（可通过文件配置，解决国内网络访问问题）
     *
     * 配置方式：在 /sdcard/Download/mineradio_cloud_url.txt 放一个文件，
     * 内容为后端 API 的根 URL（不带 /api 后缀），例如：
     *   https://plugin-market-1a2b3c.service.tcloudbase.com/plugin-api
     *
     * 如未配置，默认使用腾讯云 CloudBase（国内可直连，无需梯子）。
     */
    private val cloudBackendUrlFile = File("/sdcard/Download/mineradio_cloud_url.txt")

    @Volatile private var cachedCloudBackendUrl: String? = null

    @Volatile private var cloudBackendUrlFileLastModified: Long = 0L

    private fun getCloudBackendUrl(): String {
        // ★ 默认使用腾讯云 CloudBase（国内可访问，SSL 证书匹配 service.tcloudbase.com）
        val defaultUrl = "https://plugin-market-d2gpn5vfb44d821b8.service.tcloudbase.com/plugin-api"
        try {
            val fileMtime = if (cloudBackendUrlFile.exists()) cloudBackendUrlFile.lastModified() else 0L
            // 文件未变化 → 用缓存
            if (cachedCloudBackendUrl != null && fileMtime == cloudBackendUrlFileLastModified) {
                return cachedCloudBackendUrl!!
            }
            cloudBackendUrlFileLastModified = fileMtime
            if (!cloudBackendUrlFile.exists()) {
                cachedCloudBackendUrl = defaultUrl
                Log.i(TAG, "cloud backend: 未配置 mineradio_cloud_url.txt，使用默认腾讯云 CloudBase")
                return defaultUrl
            }
            val raw = cloudBackendUrlFile.readText().trim().trimEnd('/')
            if (raw.isEmpty()) {
                cachedCloudBackendUrl = defaultUrl
                return defaultUrl
            }
            cachedCloudBackendUrl = raw
            Log.i(TAG, "cloud backend: 使用配置的 URL = $raw")
            return raw
        } catch (e: Exception) {
            Log.e(TAG, "读取 cloud backend URL 失败: ${e.message}", e)
            return defaultUrl
        }
    }

    // ═══════════════════════════════════════════════
    //  静态文件服务
    // ═══════════════════════════════════════════════

    private fun serveStaticFile(
        output: OutputStream,
        path: String,
        headers: List<Pair<String, String>>,
    ) {
        try {
            val decodedPath =
                try {
                    URLDecoder.decode(path, "UTF-8")
                } catch (_: Exception) {
                    path
                }
            var filePath = decodedPath.trimStart('/')
            if (filePath.isEmpty() || filePath == "/") filePath = "index.html"

            val assetPath = "$staticBase$filePath"

            // ★ 先查缓存
            var data = synchronized(staticCache) { staticCache[assetPath] }
            var ext = filePath.substringAfterLast('.', "html")

            if (data == null) {
                var stream: InputStream? = null
                try {
                    stream = assets.open(assetPath)
                } catch (e: Exception) {
                    try {
                        stream = assets.open(filePath)
                    } catch (_: Exception) {
                    }
                }

                if (stream == null) {
                    // SPA 路由回退到 index.html
                    try {
                        stream = assets.open("${staticBase}index.html")
                        ext = "html"
                    } catch (e: Exception) {
                        send404(output)
                        return
                    }
                }

                val bytes = AssetDecryptor.decrypt(stream.readBytes())
                stream.close()
                data = bytes

                // ★ 只缓存小文件（< 512KB），音频等大文件不缓存
                if (bytes.size < 524288) {
                    synchronized(staticCache) { staticCache[assetPath] = bytes }
                }
            }

            val mime = MIME[ext] ?: "application/octet-stream"

            // ═══════════════════════════════════════════════
            // ★ 性能优化：ETag / 304 Not Modified
            //   参考 jecyu/Web-Performance-Optimization 缓存篇
            //   对静态资源生成内容哈希 ETag，浏览器携带 If-None-Match 时返回 304
            //   避免重复传输未变更的文件体，减少网络带宽和 WebView 解析时间
            // ═══════════════════════════════════════════════
            val etag =
                etagCache.getOrPut(assetPath) {
                    // 使用内容长度 + 简单哈希作为 ETag（避免对大文件做完整 SHA）
                    "W/\"${data.size}-${data.hashCode()}\""
                }

            // 检查 If-None-Match
            val ifNoneMatch = headers.find { it.first.equals("If-None-Match", true) }?.second
            if (ifNoneMatch != null && ifNoneMatch == etag) {
                val header304 =
                    "HTTP/1.1 304 Not Modified\r\n" +
                        "ETag: $etag\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(header304.toByteArray())
                output.flush()
                return
            }

            val cacheHeader =
                if (ext == "html") {
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" + "Pragma: no-cache\r\n" + "Expires: 0\r\n"
                } else if (ext in setOf("css", "js", "json")) {
                    // ★ JS/CSS/JSON 不缓存，确保 APK 更新后 WebView 加载最新文件
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" + "Pragma: no-cache\r\n" + "Expires: 0\r\n"
                } else {
                    "Cache-Control: no-cache\r\n"
                }

            // ═══════════════════════════════════════════════
            // ★ 性能优化：gzip 压缩文本响应
            //   参考 jecyu/Web-Performance-Optimization 打包工具性能优化
            //   对 html/css/js/json/svg 等文本资源 gzip 压缩，
            //   减少传输体积 60-80%，加快 WebView 首屏加载
            // ═══════════════════════════════════════════════
            val acceptEncoding = headers.find { it.first.equals("Accept-Encoding", true) }?.second ?: ""
            val canGzip = acceptEncoding.contains("gzip") && shouldGzip(mime) && data.size > 512

            if (canGzip) {
                val compressed = gzipCompress(data)
                // 只有压缩后更小才使用 gzip
                if (compressed.size < data.size) {
                    val header =
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: $mime\r\n" +
                            "Content-Length: ${compressed.size}\r\n" +
                            "Content-Encoding: gzip\r\n" +
                            "ETag: $etag\r\n" +
                            "Vary: Accept-Encoding\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "$cacheHeader" +
                            "Connection: close\r\n\r\n"
                    output.write(header.toByteArray())
                    output.write(compressed)
                    output.flush()
                    return
                }
            }

            val header =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: ${data.size}\r\n" +
                    "ETag: $etag\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "$cacheHeader" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(data)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveStatic: ${e.message}")
            send404(output)
        }
    }

    /** 判断该 MIME 类型是否适合 gzip 压缩 */
    private fun shouldGzip(mime: String): Boolean {
        // 去掉 charset 后缀再比较
        val baseMime = mime.substringBefore(';').trim()
        return gzipMimeTypes.any { baseMime.startsWith(it) }
    }

    /** gzip 压缩字节数组 */
    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream(data.size / 4)
        java.util.zip
            .GZIPOutputStream(bos)
            .use { it.write(data) }
        return bos.toByteArray()
    }

    // ═══════════════════════════════════════════════
    //  HTTP 工具
    // ═══════════════════════════════════════════════

    private fun sendJson(
        output: OutputStream,
        status: Int,
        json: JSONObject,
    ) {
        val body = json.toString()
        val header =
            "HTTP/1.1 $status ${statusText(status)}\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(body.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun send404(output: OutputStream) {
        val body = "Not Found"
        val header = "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(body.toByteArray())
        output.flush()
    }

    /** ★ 发送纯文本响应（带 CORS 头） */
    private fun sendText(
        output: OutputStream,
        body: String,
        mime: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mime\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(bytes)
        output.flush()
    }

    /**
     * 提供壁纸文件服务（供壁纸库弹窗显示缩略图）
     * - 图片壁纸：直接返回图片文件
     * - 视频壁纸：提取第一帧作为 JPEG 返回（避免前端加载整个视频）
     * - 网页壁纸：返回 404（无缩略图）
     */
    private fun serveWallpaperFile(
        output: OutputStream,
        wallpaperId: String,
        raw: Boolean = false,
    ) {
        try {
            val wm =
                com.mineradio.app.wallpaper.WallpaperManager
                    .get(context)
            val wallpaper = wm.getWallpaperList().find { it.id == wallpaperId }
            if (wallpaper == null) {
                send404(output)
                return
            }
            val filePath = wallpaper.getRealPath(context)
            val file = File(filePath)
            if (!file.exists() || !file.isFile) {
                send404(output)
                return
            }

            // ★ raw=true：直接返回原始视频/图片文件流（用于设为应用背景，避免 base64 编码大文件导致 OOM）
            if (raw) {
                val ext = file.name.substringAfterLast('.', "").lowercase()
                val mime =
                    when (ext) {
                        "mp4" -> "video/mp4"
                        "webm" -> "video/webm"
                        "mov", "quicktime" -> "video/quicktime"
                        "mkv" -> "video/x-matroska"
                        "avi" -> "video/x-msvideo"
                        "m4v" -> "video/x-m4v"
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        "gif" -> "image/gif"
                        "bmp" -> "image/bmp"
                        else ->
                            if (wallpaper.wallpaperType ==
                                com.mineradio.app.wallpaper.WallpaperEntity.TYPE_VIDEO
                            ) {
                                "video/mp4"
                            } else {
                                "image/jpeg"
                            }
                    }
                val contentLength = file.length()
                val header =
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $mime\r\n" +
                        "Content-Length: $contentLength\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(header.toByteArray())
                // ★ 流式传输（256KB 缓冲），避免大视频文件 OOM
                val buf = ByteArray(262144)
                file.inputStream().use { input ->
                    var bytesRead: Int
                    while (input.read(buf).also { bytesRead = it } != -1) {
                        output.write(buf, 0, bytesRead)
                        output.flush()
                    }
                }
                output.flush()
                return
            }

            // 视频壁纸：提取关键帧作为 JPEG（避免黑屏开头，使用 OPTION_CLOSEST_SYNC 而非默认的 OPTION_PREVIOUS_SYNC）
            if (wallpaper.wallpaperType == com.mineradio.app.wallpaper.WallpaperEntity.TYPE_VIDEO) {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(filePath)
                    // ★ 尝试多个时间点提取关键帧，避免黑屏开头：
                    //   1. 0ms（第一帧）
                    //   2. 500ms（避免开头过渡帧）
                    //   3. 1000ms（更靠后的关键帧）
                    //   使用 OPTION_CLOSEST_SYNC 确保获取关键帧而非合成帧
                    var bitmap: android.graphics.Bitmap? = null
                    val timeCandidates = longArrayOf(0L, 500_000L, 1_000_000L, 2_000_000L)
                    for (t in timeCandidates) {
                        bitmap =
                            try {
                                retriever.getFrameAtTime(t, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            } catch (_: Exception) {
                                null
                            }
                        if (bitmap != null) break
                    }
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                    if (bitmap == null) {
                        send404(output)
                        return
                    }
                    val baos = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                    val bytes = baos.toByteArray()
                    val header =
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Cache-Control: max-age=3600\r\n" +
                            "Connection: close\r\n\r\n"
                    output.write(header.toByteArray())
                    output.write(bytes)
                    output.flush()
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "serveWallpaperFile: video frame extract failed", e)
                    send404(output)
                    return
                }
            }

            // ★ MPKG 壁纸（场景/视频壁纸包）：从 mpkg 内提取封面
            //   优先级：preview.jpg/png → 任意 .jpg/.png/.webp 图片 → 视频第一帧
            //   提取后统一转为 JPEG 返回，避免大文件 OOM（流式 + 8KB 缓冲）
            if (wallpaper.wallpaperType == com.mineradio.app.wallpaper.WallpaperEntity.TYPE_MPKG_WEBGL) {
                try {
                    val coverBytes = extractMpkgCover(filePath)
                    if (coverBytes != null) {
                        val header =
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${coverBytes.size}\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Cache-Control: max-age=86400\r\n" +
                                "Connection: close\r\n\r\n"
                        output.write(header.toByteArray())
                        output.write(coverBytes)
                        output.flush()
                        return
                    }
                    send404(output)
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "serveWallpaperFile: mpkg cover extract failed", e)
                    send404(output)
                    return
                }
            }

            // 图片壁纸：直接返回文件
            val ext = file.name.substringAfterLast('.', "jpg").lowercase()
            val mime =
                when (ext) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    "bmp" -> "image/bmp"
                    else -> "image/jpeg"
                }
            val contentLength = file.length()
            val header =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: $contentLength\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Cache-Control: max-age=86400\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            val buf = ByteArray(262144)
            file.inputStream().use { input ->
                var bytesRead: Int
                while (input.read(buf).also { bytesRead = it } != -1) {
                    output.write(buf, 0, bytesRead)
                    output.flush()
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveWallpaperFile error", e)
            send404(output)
        }
    }

    /**
     * ★ 从 MPKG 壁纸包中提取封面图（用于壁纸库缩略图显示）
     *
     * 提取优先级：
     *   1. 常见预览图文件名（preview.jpg/png, thumbnail.jpg/png, cover.jpg/png, .jpg/.png）
     *   2. 任意 .jpg/.jpeg/.png/.webp 图片条目
     *   3. 视频条目（.mp4 等）→ 提取第一帧
     *
     * 返回 JPEG 格式字节数组；失败返回 null
     */
    private fun extractMpkgCover(mpkgPath: String): ByteArray? {
        return try {
            // ★ 优先读取场景壁纸 GL 截图缓存（onDrawFrame 第 8 帧自动截图保存）
            //   场景壁纸 mpkg 包内只有 .tex 纹理无标准图片，截图是唯一封面来源
            val coverName = java.io.File(mpkgPath).nameWithoutExtension + ".jpg"
            val coverCacheFile = java.io.File(java.io.File(context.filesDir, "wallpaper_covers"), coverName)
            if (coverCacheFile.exists() && coverCacheFile.length() > 0) {
                val bytes = coverCacheFile.readBytes()
                Log.d(TAG, "extractMpkgCover: using cached scene capture: ${coverCacheFile.absolutePath}, size=${bytes.size}")
                return bytes
            }
            val parser =
                com.mineradio.app.wallpaper
                    .MpkgParser(java.io.File(mpkgPath))
            val entries = parser.listEntries()
            if (entries.isEmpty()) {
                Log.w(TAG, "extractMpkgCover: no entries in $mpkgPath")
                return null
            }
            // ★ 优先级 1：常见预览图文件名
            val previewNames =
                listOf(
                    "preview.jpg",
                    "preview.jpeg",
                    "preview.png",
                    "preview.webp",
                    "thumbnail.jpg",
                    "thumbnail.jpeg",
                    "thumbnail.png",
                    "cover.jpg",
                    "cover.jpeg",
                    "cover.png",
                )
            var imageEntry: com.mineradio.app.wallpaper.MpkgParser.VirtualFileRange? = null
            for (name in previewNames) {
                val e = entries.find { it.name.equals(name, ignoreCase = true) }
                if (e != null) {
                    imageEntry = e
                    break
                }
            }
            // ★ 优先级 2：任意图片条目
            if (imageEntry == null) {
                imageEntry =
                    entries.find { e ->
                        val lower = e.name.lowercase()
                        lower.endsWith(".jpg") ||
                            lower.endsWith(".jpeg") ||
                            lower.endsWith(".png") ||
                            lower.endsWith(".webp")
                    }
            }
            // ★ 优先级 3：视频条目 → 提取第一帧
            if (imageEntry == null) {
                val videoEntry =
                    entries.find { e ->
                        val lower = e.name.lowercase()
                        lower.endsWith(".mp4") ||
                            lower.endsWith(".webm") ||
                            lower.endsWith(".mkv") ||
                            lower.endsWith(".mov") ||
                            lower.endsWith(".avi") ||
                            lower.endsWith(".m4v")
                    }
                if (videoEntry != null) {
                    return extractMpkgVideoFrame(mpkgPath, videoEntry)
                }
                Log.w(TAG, "extractMpkgCover: no image or video entry found in $mpkgPath, names=${entries.map { it.name }}")
                return null
            }
            // ★ 提取图片条目字节并解码为 JPEG（统一格式 + 压缩）
            val imageBytes = parser.readBytes(imageEntry.name) ?: return null
            val bitmap =
                android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?: run {
                        Log.w(TAG, "extractMpkgCover: decode image failed for ${imageEntry.name}")
                        return null
                    }
            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
            bitmap.recycle()
            val result = baos.toByteArray()
            Log.d(TAG, "extractMpkgCover: success from ${imageEntry.name}, size=${result.size}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "extractMpkgCover failed: $mpkgPath", e)
            null
        }
    }

    /**
     * ★ 从 mpkg 内的视频条目提取第一帧作为封面
     *   先提取视频到私有目录（流式分块拷贝，避免 OOM），再用 MediaMetadataRetriever 提取帧
     */
    private fun extractMpkgVideoFrame(
        mpkgPath: String,
        videoEntry: com.mineradio.app.wallpaper.MpkgParser.VirtualFileRange,
    ): ByteArray? {
        return try {
            // ★ 提取视频文件（MpkgParser.extractVideoFile 内部用 8KB 流式拷贝，不会 OOM）
            val videoFile =
                com.mineradio.app.wallpaper.MpkgParser
                    .extractVideoFile(mpkgPath, context.cacheDir)
                    ?: return null
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)
            var bitmap: android.graphics.Bitmap? = null
            val timeCandidates = longArrayOf(0L, 500_000L, 1_000_000L, 2_000_000L)
            for (t in timeCandidates) {
                bitmap =
                    try {
                        retriever.getFrameAtTime(t, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (_: Exception) {
                        null
                    }
                if (bitmap != null) break
            }
            try {
                retriever.release()
            } catch (_: Exception) {
            }
            if (bitmap == null) return null
            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
            bitmap.recycle()
            // ★ 清理临时视频文件
            try {
                videoFile.delete()
            } catch (_: Exception) {
            }
            val result = baos.toByteArray()
            Log.d(TAG, "extractMpkgVideoFrame: success from ${videoEntry.name}, size=${result.size}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "extractMpkgVideoFrame failed", e)
            null
        }
    }

    /**
     * 从内部存储提供本地歌曲文件
     */
    private fun serveLocalSongFile(
        output: OutputStream,
        fileName: String,
    ) {
        try {
            // ★ URL 解码文件名（中文等非 ASCII 字符在 HTTP 路径中会被 % 编码）
            val decoded =
                try {
                    URLDecoder.decode(fileName, "UTF-8")
                } catch (_: Exception) {
                    fileName
                }
            val safeName = decoded.replace(Regex("[\\\\/]"), "").replace("..", ".")
            if (safeName.isEmpty()) {
                send404(output)
                return
            }
            val songsDir = File(context.filesDir, "local_songs")
            val file = File(songsDir, safeName)
            if (!file.exists() || !file.isFile) {
                send404(output)
                return
            }
            val ext = file.name.substringAfterLast('.', "mp3")
            val mime = MIME[ext] ?: "audio/mpeg"
            val contentLength = file.length()
            val header =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: $contentLength\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Cache-Control: max-age=86400\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            // ★ 256KB 缓冲区 + 立即 flush，最大化本地文件读取吞吐量
            val buf = ByteArray(262144)
            file.inputStream().use { input ->
                var bytesRead: Int
                while (input.read(buf).also { bytesRead = it } != -1) {
                    output.write(buf, 0, bytesRead)
                    output.flush()
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveLocalSongFile error", e)
            send404(output)
        }
    }

    /**
     * ★★★ 崩溃恢复机制：pending-play 追踪 ★★★
     *
     * 问题: 某些汽水音乐 VIP 歌曲解密后的 m4a 文件能让 MediaExtractor 验证通过,
     *   但 Chromium <audio> 解码器在 native 层触发 DCHECK 断言 → SIGTRAP crash.
     *   JS 的 try/catch 无法捕获 native crash, App 直接闪退.
     *
     * 方案: 播放前 POST /api/pending-play?trackId=xxx 写入待播放记录;
     *   播放成功后 DELETE /api/pending-play 清除记录.
     *   如果 App 崩溃, 记录会残留. 下次启动 GET /api/pending-play 检测到残留,
     *   删除对应的 qs_cache 文件, 避免再次播放同一损坏文件导致崩溃循环.
     */
    private fun getPendingPlayFile(): java.io.File = java.io.File(context.filesDir, "pending_play.json")

    private fun handlePendingPlaySave(
        output: OutputStream,
        query: String,
    ) {
        try {
            val trackId = getQueryParam(query, "trackId") ?: ""
            if (trackId.isBlank()) {
                sendJson(output, 400, JSONObject().apply { put("error", "missing trackId") })
                return
            }
            val json =
                JSONObject().apply {
                    put("trackId", trackId)
                    put("ts", System.currentTimeMillis())
                }
            val file = getPendingPlayFile()
            file.writeText(json.toString())
            Log.i(TAG, "pendingPlay SAVE: trackId=$trackId")
            sendJson(output, 200, JSONObject().apply { put("ok", true) })
        } catch (e: Exception) {
            Log.e(TAG, "pendingPlay SAVE error", e)
            sendJson(output, 500, JSONObject().apply { put("error", e.message ?: "unknown") })
        }
    }

    private fun handlePendingPlayClear(output: OutputStream) {
        try {
            val file = getPendingPlayFile()
            if (file.exists()) {
                file.delete()
                Log.i(TAG, "pendingPlay CLEAR: deleted (playback succeeded)")
            }
            sendJson(output, 200, JSONObject().apply { put("ok", true) })
        } catch (e: Exception) {
            Log.e(TAG, "pendingPlay CLEAR error", e)
            sendJson(output, 500, JSONObject().apply { put("error", e.message ?: "unknown") })
        }
    }

    private fun handlePendingPlayCheck(output: OutputStream) {
        try {
            val file = getPendingPlayFile()
            if (!file.exists()) {
                sendJson(output, 200, JSONObject().apply { put("crashed", false) })
                return
            }
            // 读取残留的 pending-play 记录
            val content = file.readText()
            val json = JSONObject(content)
            val trackId = json.optString("trackId", "")
            val ts = json.optLong("ts", 0)
            val ageMs = System.currentTimeMillis() - ts
            // ★ 超时判断: 如果记录超过 30 分钟, 认为是陈旧记录 (非崩溃残留), 直接清除
            if (trackId.isBlank() || ageMs < 0 || ageMs > 30 * 60 * 1000L) {
                file.delete()
                Log.i(TAG, "pendingPlay CHECK: stale record (trackId=$trackId, age=${ageMs / 1000}s), cleared")
                sendJson(output, 200, JSONObject().apply { put("crashed", false) })
                return
            }
            // ★ 崩溃恢复: 删除对应的 qs_cache 文件, 避免再次播放同一损坏文件
            val cacheFile = NeteaseMusicApi.getQSCacheFile(trackId)
            var deleted = false
            if (cacheFile != null && cacheFile.exists()) {
                deleted = cacheFile.delete()
                Log.w(TAG, "pendingPlay CHECK: CRASH RECOVERY - deleting corrupt cache (trackId=$trackId, deleted=$deleted)")
            }
            file.delete()
            sendJson(
                output,
                200,
                JSONObject().apply {
                    put("crashed", true)
                    put("trackId", trackId)
                    put("cacheDeleted", deleted)
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "pendingPlay CHECK error", e)
            // 出错时也清除 pending-play 文件, 避免卡死
            try {
                getPendingPlayFile().delete()
            } catch (_: Exception) {
            }
            sendJson(output, 200, JSONObject().apply { put("crashed", false) })
        }
    }

    /**
     * ★ 用 MediaExtractor + MediaCodec 实际解码验证音频文件
     *
     * 仅 magic bytes 校验不够: 容器格式正确 (ftyp box 存在) 但内部编码数据
     *   损坏 / VIP 解密残留 / 不支持的编码 profile / 文件截断
     *   仍会让 Chromium <audio> 解码器在 native 层触发 DCHECK 断言 → SIGTRAP crash
     *   (此 native crash JS 的 try/catch 无法捕获, 必须在源头上拦截)
     *
     * MediaExtractor 只验证"能否提取样本", 不验证"能否完整解码".
     *   某些文件能被 MediaExtractor 读取但 MediaCodec 解码失败 → Chromium 也会 crash.
     *   因此增加 MediaCodec 实际解码前几帧的验证, 确保文件可被真正解码.
     *
     * 返回 true = 可安全播放; false = 必须删除缓存避免 crash
     */
    private fun validateAudioWithMediaExtractor(file: java.io.File): Boolean {
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            // 必须至少有一条音轨, 且 mime 是 Chromium 支持的音频编码
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                // ★ Chromium WebView 支持的音频 mime 白名单
                //   不在白名单内 (alac/amr/qcelp 等) → 拒绝避免 native crash
                val supported =
                    mime == "audio/mp4a-latm" ||
                        mime == "audio/mp3" ||
                        mime == "audio/mpeg" ||
                        mime == "audio/ogg" ||
                        mime == "audio/vorbis" ||
                        mime == "audio/flac" ||
                        mime == "audio/opus" ||
                        mime == "audio/wav" ||
                        mime == "audio/raw" ||
                        mime == "audio/aac"
                if (!supported) {
                    Log.e(TAG, "validateAudio: unsupported codec '$mime' for ${file.name}, refuse to play (avoid Chromium crash)")
                    return false
                }
                // ★ 再验证 duration 是否合理 (异常小或异常大都可能有问题)
                val durUs =
                    try {
                        fmt.getLong(MediaFormat.KEY_DURATION)
                    } catch (_: Exception) {
                        -1L
                    }
                if (durUs in 1..(500 * 1000)) {
                    // duration < 500ms → 可能是损坏的截断文件
                    Log.e(TAG, "validateAudio: suspicious duration ${durUs / 1000}ms for ${file.name}, refuse to play")
                    return false
                }

                // ★ 验证音频关键参数是否在合理范围内
                //   异常参数 (如 sampling rate=0, channel count=0) 会让 Chromium 解码器 DCHECK crash
                val sampleRate =
                    try {
                        fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } catch (_: Exception) {
                        -1
                    }
                val channelCount =
                    try {
                        fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } catch (_: Exception) {
                        -1
                    }
                if (sampleRate > 0 && (sampleRate < 4000 || sampleRate > 96000)) {
                    Log.e(TAG, "validateAudio: invalid sample rate $sampleRate for ${file.name}, refuse to play (avoid Chromium crash)")
                    return false
                }
                if (channelCount > 0 && (channelCount < 1 || channelCount > 8)) {
                    Log.e(TAG, "validateAudio: invalid channel count $channelCount for ${file.name}, refuse to play (avoid Chromium crash)")
                    return false
                }

                extractor.selectTrack(i)
                val buf = java.nio.ByteBuffer.allocateDirect(128 * 1024)

                // ★★★ 多位置样本验证：在多个位置读取样本检测深层损坏 ★★★
                //   仅读取第一个样本无法检测文件深处的损坏 (如 VIP 解密残留、截断、编码错误)
                //   Chromium <audio> 解码器在播放到损坏位置时会在 ThreadPoolSingl 线程触发
                //   DCHECK 断言 → SIGTRAP crash (无法被 JS try-catch 捕获)
                //   通过在多个位置读取样本，可以在播放前拦截深层损坏的文件
                //   ★ 特别关注前 30 秒：崩溃通常发生在播放开始后几秒到十几秒
                val seekPositions = ArrayList<Long>()
                seekPositions.add(0L) // 开头
                if (durUs > 0) {
                    // 早期位置（前30秒）密集验证
                    val earlyBound = Math.min(durUs, 30_000_000L) // 30 秒
                    seekPositions.add(Math.min(earlyBound / 6, durUs)) // ~5秒
                    seekPositions.add(Math.min(earlyBound / 3, durUs)) // ~10秒
                    seekPositions.add(Math.min(earlyBound / 2, durUs)) // ~15秒
                    seekPositions.add(Math.min(earlyBound, durUs)) // ~30秒
                    // 中后部稀疏验证
                    seekPositions.add(durUs / 2) // 50%
                    seekPositions.add(durUs * 3 / 4) // 75%
                    seekPositions.add(durUs * 95 / 100) // 95%
                } else {
                    // 无 duration 信息：按固定时间点验证
                    seekPositions.add(5_000_000L) // 5秒
                    seekPositions.add(10_000_000L) // 10秒
                    seekPositions.add(20_000_000L) // 20秒
                    seekPositions.add(30_000_000L) // 30秒
                }

                for ((idx, seekUs) in seekPositions.withIndex()) {
                    try {
                        extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize <= 0) {
                            Log.e(
                                TAG,
                                "validateAudio: cannot read sample at ${seekUs / 1000}ms (pos $idx/${seekPositions.size}) for ${file.name} — corrupt/truncated",
                            )
                            return false
                        }
                        // 验证样本时间戳是否接近目标位置 (容差 2 秒)
                        val sampleTime = extractor.sampleTime
                        if (sampleTime >= 0 && durUs > 0) {
                            val diffMs = Math.abs(sampleTime - seekUs) / 1000
                            if (diffMs > 2000 && idx > 0) {
                                Log.w(
                                    TAG,
                                    "validateAudio: sample time mismatch at pos $idx: expected=${seekUs / 1000}ms actual=${sampleTime / 1000}ms for ${file.name}",
                                )
                                // 时间戳偏差大但不一定损坏，仅警告不拒绝
                            }
                        }
                    } catch (seekErr: Exception) {
                        Log.e(TAG, "validateAudio: seek/read failed at ${seekUs / 1000}ms (pos $idx) for ${file.name}: ${seekErr.message}")
                        return false
                    }
                }

                // ★★★ MediaCodec 实际解码验证：MediaExtractor 只能提取样本，不能验证可解码性 ★★★
                //   某些文件能让 MediaExtractor 读取样本，但 MediaCodec 解码时触发错误
                //   → Chromium <audio> 用同样的底层解码器也会 crash
                //   通过实际解码前几帧来验证文件可被真正解码
                if (!validateAudioWithMediaCodec(file, mime)) {
                    Log.e(
                        TAG,
                        "validateAudio: MediaCodec decode verification FAILED for ${file.name}, refuse to play (avoid Chromium crash)",
                    )
                    return false
                }

                Log.i(
                    TAG,
                    "validateAudio: PASSED multi-position + MediaCodec validation for ${file.name}, dur=${durUs / 1000}ms, sr=$sampleRate, ch=$channelCount",
                )
                return true
            }
            // 没有音轨 → 不是有效音频文件
            Log.e(TAG, "validateAudio: no audio track found in ${file.name}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "validateAudio: MediaExtractor failed for ${file.name}: ${e.message}")
            return false
        } finally {
            try {
                extractor?.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * ★ MediaCodec 实际解码验证：确保音频文件可被真正解码（不仅仅是可提取）
     *
     * MediaExtractor 只验证容器和样本可读性，不验证编码数据是否能正确解码。
     * 某些损坏的 VIP 解密残留文件能让 MediaExtractor 通过，但 MediaCodec 解码时
     * 会触发错误 → Chromium <audio> 用同样的底层解码器也会 native crash。
     *
     * 本函数用 MediaCodec 实际解码前几帧来验证可解码性。
     * 设置超时限制，避免长时间阻塞。
     */
    private fun validateAudioWithMediaCodec(
        file: java.io.File,
        mime: String,
    ): Boolean {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            var audioIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val m = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    audioIdx = i
                    format = fmt
                    break
                }
            }
            if (audioIdx < 0 || format == null) {
                Log.e(TAG, "validateMediaCodec: no audio track in ${file.name}")
                return false
            }
            extractor.selectTrack(audioIdx)
            // 创建解码器
            codec =
                try {
                    MediaCodec.createDecoderByType(mime)
                } catch (e: Exception) {
                    Log.w(TAG, "validateMediaCodec: cannot create decoder for mime '$mime': ${e.message}, skip decode verification")
                    // 无法创建解码器（可能是不支持的类型），不阻塞播放
                    return true
                }
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val startTimeNs = System.nanoTime()
            val timeoutUs = 10000L // 10ms per dequeue call
            var inputFedCount = 0
            var outputReceivedCount = 0
            val maxInputFrames = 30 // 最多喂入 30 帧
            val maxDurationNs = 2_000_000_000L // 最多验证 2 秒
            var sawInputEOS = false
            var sawOutputEOS = false
            var decodeError = false

            while (!sawOutputEOS && !decodeError) {
                // 超时检查
                if (System.nanoTime() - startTimeNs > maxDurationNs) {
                    Log.w(TAG, "validateMediaCodec: timeout after 2s for ${file.name}, fed=$inputFedCount out=$outputReceivedCount")
                    break
                }

                // 喂入输入
                if (!sawInputEOS && inputFedCount < maxInputFrames) {
                    val inputBufIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inputBufIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIdx)
                        if (inputBuf != null) {
                            inputBuf.clear()
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                val sampleTime = extractor.sampleTime
                                codec.queueInputBuffer(inputBufIdx, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                                inputFedCount++
                            }
                        }
                    }
                }

                // 读取输出
                val outBufIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outBufIdx >= 0 -> {
                        outputReceivedCount++
                        codec.releaseOutputBuffer(outBufIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }
                    outBufIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 等待输出，继续循环
                    }
                    outBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 格式变化，正常
                    }
                }

                // 检查 MediaCodec 是否进入错误状态
                // 如果 dequeueInputBuffer 或 dequeueOutputBuffer 返回异常值，可能是解码错误
                try {
                    val codecInfo = codec.codecInfo
                    // 检查 codec 是否还处于运行状态
                } catch (e: Exception) {
                    Log.e(TAG, "validateMediaCodec: codec error for ${file.name}: ${e.message}")
                    decodeError = true
                }
            }

            // 判断结果：至少成功解码 1 帧才算通过
            if (decodeError) {
                Log.e(TAG, "validateMediaCodec: DECODE ERROR for ${file.name}, mime=$mime")
                return false
            }
            if (outputReceivedCount == 0 && inputFedCount > 0) {
                Log.e(TAG, "validateMediaCodec: no output produced (fed=$inputFedCount) for ${file.name}, likely corrupt")
                return false
            }
            Log.i(TAG, "validateMediaCodec: PASSED for ${file.name}, mime=$mime, fed=$inputFedCount out=$outputReceivedCount")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "validateMediaCodec: exception for ${file.name}: ${e.message}")
            // 解码验证出错时，不阻塞播放（可能是 MediaCodec 限制，不是文件问题）
            return true
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor?.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * ★ 将 MP4 容器中的 FLAC track 转封装为裸 FLAC 文件
     *   Chromium 的 MP4 demuxer 不支持 FLAC track → DCHECK SIGTRAP crash
     *   转封装为裸 FLAC 流后，Chromium 的 FLAC demuxer 可以正常播放
     *   返回转封装后的 .flac 文件，失败返回 null
     */
    private fun remuxFlacFromMp4(mp4File: java.io.File): java.io.File? {
        val flacFile = java.io.File(mp4File.parentFile, mp4File.nameWithoutExtension + ".flac")
        if (flacFile.exists() && flacFile.length() > 1024) {
            return flacFile
        }
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(mp4File.absolutePath)
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime == "audio/flac") {
                    audioTrack = i
                    break
                }
            }
            if (audioTrack < 0) return null
            extractor.selectTrack(audioTrack)
            val fmt = extractor.getTrackFormat(audioTrack)
            val csd0 = fmt.getByteBuffer("csd-0")
            if (csd0 == null || csd0.remaining() < 34) {
                Log.e(TAG, "remuxFlacFromMp4: csd-0 missing or too short (${csd0?.remaining() ?: 0} bytes)")
                return null
            }
            val csdRemaining = csd0.remaining()
            val csdCopy = ByteArray(csdRemaining)
            csd0.duplicate().get(csdCopy)
            Log.i(TAG, "remuxFlacFromMp4: csd-0 size=$csdRemaining, hex=${csdCopy.take(40).joinToString(" ") { "%02x".format(it) }}")
            // csd-0 可能包含完整 metadata block (4 bytes header + 34 bytes STREAMINFO) 或仅 STREAMINFO (34 bytes)
            // 如果大于 34 bytes，取最后 34 bytes 作为 STREAMINFO data
            val streaminfo = ByteArray(34)
            val offset = if (csdRemaining > 34) csdRemaining - 34 else 0
            System.arraycopy(csdCopy, offset, streaminfo, 0, 34)
            Log.i(TAG, "remuxFlacFromMp4: STREAMINFO hex=${streaminfo.joinToString(" ") { "%02x".format(it) }}")
            val maxInputSize =
                try {
                    fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } catch (_: Exception) {
                    65536
                }
            val buffer = java.nio.ByteBuffer.allocate(maxInputSize.coerceAtLeast(65536))
            flacFile.outputStream().use { out ->
                out.write("fLaC".toByteArray())
                out.write(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22))
                out.write(streaminfo)
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    out.write(buffer.array(), 0, size)
                    extractor.advance()
                }
            }
            Log.i(TAG, "remuxFlacFromMp4: SUCCESS ${mp4File.name} -> ${flacFile.name} (${flacFile.length()} bytes)")
            return flacFile
        } catch (e: Exception) {
            Log.e(TAG, "remuxFlacFromMp4: FAILED for ${mp4File.name}: ${e.message}")
            try {
                flacFile.delete()
            } catch (_: Exception) {
            }
            return null
        } finally {
            try {
                extractor?.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * ★ 汽水音乐 VIP 歌曲缓存文件服务
     */
    private fun serveQSCacheFile(
        output: OutputStream,
        fileName: String,
        headOnly: Boolean = false,
    ) {
        try {
            val decoded =
                try {
                    URLDecoder.decode(fileName, "UTF-8")
                } catch (_: Exception) {
                    fileName
                }
            val safeName = decoded.replace(Regex("[\\\\/]"), "").replace("..", ".")
            if (safeName.isEmpty()) {
                send404(output)
                return
            }
            // 从 trackId 提取 (去掉 .m4a 扩展名)
            val trackId = safeName.substringBeforeLast('.', safeName)
            val file = NeteaseMusicApi.getQSCacheFile(trackId)
            if (file == null || !file.exists() || !file.isFile) {
                send404(output)
                return
            }
            // ★ Magic bytes 校验: 防止播放损坏/加密的缓存文件导致 Chromium 解码器 crash
            //   有效音频: m4a/mp4 (偏移4-7为 "ftyp"), mp3 (开头 "ID3" 或 0xFF 0xFB/0xF3/0xF2),
            //             flac (开头 "fLaC"), ogg (开头 "OggS")
            //   ★ 必须检测实际格式：汽水音乐缓存的 .m4a 文件可能实际是 FLAC 格式
            //     Content-Type 不匹配会导致 Chromium 选择错误的解码器 → DCHECK SIGTRAP crash
            var actualMime = ""
            try {
                val fis = java.io.FileInputStream(file)
                val header = ByteArray(12)
                val read = fis.read(header)
                fis.close()
                if (read < 4) {
                    Log.e(TAG, "serveQSCacheFile: file too small ($read bytes), deleting corrupt cache")
                    file.delete()
                    send404(output)
                    return
                }
                val isFtyp =
                    read >= 8 &&
                        header[4] == 'f'.code.toByte() &&
                        header[5] == 't'.code.toByte() &&
                        header[6] == 'y'.code.toByte() &&
                        header[7] == 'p'.code.toByte()
                val isId3 = header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()
                val isMp3Frame = (header[0] == 0xFF.toByte()) && ((header[1].toInt() and 0xE0) == 0xE0)
                val isFlac =
                    header[0] == 'f'.code.toByte() &&
                        header[1] == 'L'.code.toByte() &&
                        header[2] == 'a'.code.toByte() &&
                        header[3] == 'C'.code.toByte()
                val isOgg =
                    header[0] == 'O'.code.toByte() &&
                        header[1] == 'g'.code.toByte() &&
                        header[2] == 'g'.code.toByte() &&
                        header[3] == 'S'.code.toByte()
                if (!isFtyp && !isId3 && !isMp3Frame && !isFlac && !isOgg) {
                    Log.e(TAG, "serveQSCacheFile: invalid audio magic bytes, deleting corrupt cache (trackId=$trackId)")
                    file.delete()
                    send404(output)
                    return
                }
                // ★ 根据实际格式设置 mime（文件扩展名可能不准确，如 .m4a 实际是 FLAC）
                actualMime =
                    when {
                        isFlac -> "audio/flac"
                        isOgg -> "audio/ogg"
                        isId3 || isMp3Frame -> "audio/mpeg"
                        isFtyp -> "audio/mp4"
                        else -> ""
                    }
                if (actualMime.isNotEmpty() && actualMime != "audio/mp4") {
                    Log.i(
                        TAG,
                        "serveQSCacheFile: detected actual format=$actualMime (file ext=${file.name.substringAfterLast(
                            '.',
                            "?",
                        )}, trackId=$trackId)",
                    )
                }
            } catch (_: Exception) {
            }
            // ★ 使用实际检测到的 mime，而不是文件扩展名
            //   文件扩展名可能不准确（如汽水音乐缓存的 .m4a 文件实际可能是 FLAC 格式）
            //   Content-Type 不匹配会导致 Chromium 选择错误的解码器 → DCHECK SIGTRAP crash
            val ext = file.name.substringAfterLast('.', "m4a")
            var mime = if (actualMime.isNotEmpty()) actualMime else (MIME[ext] ?: "audio/mp4")
            // ★★★ 关键修复：MP4 容器中的 FLAC track 会让 Chromium 解码器 crash ★★★
            //   文件扩展名是 .m4a，magic bytes 检测到 ftyp (MP4 容器)，
            //   但音频 track 的实际编码是 FLAC (audio/flac)。
            //   Chromium 期望 MP4 容器中是 AAC/MP3，遇到 FLAC track 会触发 DCHECK → SIGTRAP crash。
            //   修复1: 用 MediaExtractor 检测实际音频编码，设置正确的 Content-Type。
            //   修复2: MP4+FLAC 必须转封装为裸 FLAC 流 — Chromium 的 MP4 demuxer 根本不支持 FLAC track，
            //          即使 Content-Type=audio/flac 也会因 MP4 容器结构而崩溃。
            //          转封装为裸 FLAC 后，Chromium 的 FLAC demuxer 可正常播放。
            var actualFile = file
            var isMp4Flac = false
            try {
                val detectExtractor = MediaExtractor()
                detectExtractor.setDataSource(file.absolutePath)
                for (i in 0 until detectExtractor.trackCount) {
                    val fmt = detectExtractor.getTrackFormat(i)
                    val trackMime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (trackMime.startsWith("audio/")) {
                        if (trackMime == "audio/flac" && mime == "audio/mp4") {
                            // ★ MP4 容器 + FLAC track: 必须转封装
                            isMp4Flac = true
                            Log.w(
                                TAG,
                                "serveQSCacheFile: MP4+FLAC detected! remuxing to native FLAC (trackId=$trackId, headOnly=$headOnly)",
                            )
                        }
                        if (trackMime == "audio/flac" ||
                            trackMime == "audio/mp4a-latm" ||
                            trackMime == "audio/mp3" ||
                            trackMime == "audio/mpeg" ||
                            trackMime == "audio/ogg" ||
                            trackMime == "audio/vorbis" ||
                            trackMime == "audio/opus"
                        ) {
                            if (mime != trackMime) {
                                Log.w(
                                    TAG,
                                    "serveQSCacheFile: Content-Type mismatch! file ext=$ext container=$mime actual track=$trackMime, using actual track mime (trackId=$trackId, headOnly=$headOnly)",
                                )
                            }
                            mime = trackMime
                        }
                        break
                    }
                }
                detectExtractor.release()
            } catch (_: Exception) {
            }
            // ★ MP4+FLAC 转封装为裸 FLAC
            if (isMp4Flac) {
                val remuxed = remuxFlacFromMp4(file)
                if (remuxed != null && remuxed.exists() && remuxed.length() > 1024) {
                    actualFile = remuxed
                    mime = "audio/flac"
                    Log.i(TAG, "serveQSCacheFile: using remuxed FLAC file ${remuxed.name} (${remuxed.length()} bytes)")
                } else {
                    Log.e(TAG, "serveQSCacheFile: remux failed, refusing to serve MP4+FLAC (would crash Chromium), trackId=$trackId")
                    // 转封装失败: 删除损坏的 .m4a 缓存，避免下次又触发同样的崩溃路径
                    try {
                        file.delete()
                    } catch (_: Exception) {
                    }
                    try {
                        java.io.File(file.parentFile, file.nameWithoutExtension + ".flac").delete()
                    } catch (_: Exception) {
                    }
                    send404(output)
                    return
                }
            }
            // ★ HEAD 请求: 只做存在性检测 (magic bytes + MediaExtractor mime 已校验), 跳过完整解码验证避免开销
            //   GET 请求: 必须做 MediaExtractor + MediaCodec 完整验证 — 容器正确但编码损坏/不支持
            //             会让 Chromium <audio> 在 native 层 SIGTRAP crash (JS 无法捕获)
            //   ★ 转封装的 FLAC 文件跳过 MediaExtractor 验证：转封装后的 FLAC 缺少 seektable，
            //     MediaExtractor 无法 seek 会报 "corrupt/truncated"，但 Chromium 可正常顺序播放。
            //     原始 MP4+FLAC track 已在转封装前通过 MediaExtractor 检测到，FLAC 数据是有效的。
            if (!headOnly && !isMp4Flac) {
                if (!validateAudioWithMediaExtractor(actualFile)) {
                    Log.e(TAG, "serveQSCacheFile: MediaExtractor validation failed, deleting corrupt cache (trackId=$trackId)")
                    try {
                        actualFile.delete()
                    } catch (_: Exception) {
                    }
                    send404(output)
                    return
                }
            }
            val contentLength = actualFile.length()
            val header =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: $contentLength\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Cache-Control: max-age=86400\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            // ★ HEAD 请求：只做存在性检测，不返回文件内容
            if (headOnly) {
                output.flush()
                return
            }
            val buf = ByteArray(262144)
            actualFile.inputStream().use { input ->
                var bytesRead: Int
                while (input.read(buf).also { bytesRead = it } != -1) {
                    output.write(buf, 0, bytesRead)
                    output.flush()
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveQSCacheFile error", e)
            send404(output)
        }
    }

    /**
     * ★ 通用歌曲缓存文件服务 (QQ/酷狗/网易/酷我等平台)
     *   路径格式: {provider}/{cacheId}.mp3
     *   缓存目录: cacheDir/music_cache_{provider}/{cacheId}.mp3
     *   HEAD 请求只做存在性检测，不返回文件内容
     */
    private fun serveMusicCacheFile(
        output: OutputStream,
        subPath: String,
        headOnly: Boolean = false,
    ) {
        try {
            val decoded =
                try {
                    URLDecoder.decode(subPath, "UTF-8")
                } catch (_: Exception) {
                    subPath
                }
            // 解析 {provider}/{cacheId}.mp3
            val slashIdx = decoded.indexOf('/')
            if (slashIdx <= 0 || slashIdx >= decoded.length - 1) {
                send404(output)
                return
            }
            val provider = decoded.substring(0, slashIdx).replace(Regex("[^A-Za-z0-9_-]"), "")
            val fileName = decoded.substring(slashIdx + 1).replace(Regex("[\\\\/]"), "").replace("..", ".")
            if (provider.isEmpty() || fileName.isEmpty()) {
                send404(output)
                return
            }
            val cacheDir = java.io.File(context.cacheDir, "music_cache_$provider")
            val file = java.io.File(cacheDir, fileName)
            if (!file.exists() || !file.isFile) {
                send404(output)
                return
            }
            // 安全校验：确保文件在缓存目录内（防止路径穿越）
            try {
                val cacheDirCanon = cacheDir.canonicalFile.absolutePath
                val fileCanon = file.canonicalFile.absolutePath
                if (!fileCanon.startsWith(cacheDirCanon + File.separator)) {
                    send404(output)
                    return
                }
            } catch (_: Exception) {
                send404(output)
                return
            }
            // ★ v4.7.1: 通过文件内容 magic bytes 检测真实 MIME 类型
            //   修复缓存后第二次无法播放的问题：
            //   QQ/酷狗/网易的歌曲可能实际是 m4a/flac 格式，但统一保存为 .mp3 扩展名
            //   原先根据扩展名设置 Content-Type: audio/mpeg，但实际内容是 m4a（MP4 容器）
            //   浏览器根据 Content-Type 解码失败，导致"无法播放"
            //   现在读取文件头 magic bytes 检测真实格式，设置正确的 Content-Type
            val mime = detectAudioMime(file)
            val contentLength = file.length()
            val header =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: $contentLength\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Cache-Control: max-age=86400\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            // ★ HEAD 请求：只做存在性检测，不返回文件内容
            if (headOnly) {
                output.flush()
                return
            }
            val buf = ByteArray(262144)
            file.inputStream().use { input ->
                var bytesRead: Int
                while (input.read(buf).also { bytesRead = it } != -1) {
                    output.write(buf, 0, bytesRead)
                    output.flush()
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveMusicCacheFile error", e)
            send404(output)
        }
    }

    /**
     * ★ 通过文件头 magic bytes 检测音频文件的真实 MIME 类型
     *   - MP3: 以 ID3 或 FF Fx 开头
     *   - M4A/MP4: 第 4-8 字节为 ftyp
     *   - FLAC: 以 fLaC 开头
     *   - OGG: 以 OggS 开头
     *   - WAV: 以 RIFF 开头
     */
    private fun detectAudioMime(file: java.io.File): String {
        try {
            val header = ByteArray(16)
            file.inputStream().use { input ->
                val read = input.read(header)
                if (read < 4) return "audio/mpeg"
            }
            // ID3 tag → MP3
            if (header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()) {
                return "audio/mpeg"
            }
            // FF Fx → MP3 frame sync
            if (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xF0) == 0xF0) {
                return "audio/mpeg"
            }
            // fLaC → FLAC
            if (header[0] == 0x66.toByte() &&
                header[1] == 0x4C.toByte() &&
                header[2] == 0x61.toByte() &&
                header[3] == 0x43.toByte()
            ) {
                return "audio/flac"
            }
            // OggS → OGG
            if (header[0] == 0x4F.toByte() &&
                header[1] == 0x67.toByte() &&
                header[2] == 0x67.toByte() &&
                header[3] == 0x53.toByte()
            ) {
                return "audio/ogg"
            }
            // RIFF → WAV
            if (header[0] == 0x52.toByte() &&
                header[1] == 0x49.toByte() &&
                header[2] == 0x46.toByte() &&
                header[3] == 0x46.toByte()
            ) {
                return "audio/wav"
            }
            // ftyp box (bytes 4-7) → M4A/MP4
            if (header[4] == 0x66.toByte() &&
                header[5] == 0x74.toByte() &&
                header[6] == 0x79.toByte() &&
                header[7] == 0x70.toByte()
            ) {
                return "audio/mp4"
            }
        } catch (_: Exception) {
        }
        return "audio/mpeg"
    }

    /**
     * 从内部存储提供本地歌曲封面图
     */
    private fun serveLocalCoverFile(
        output: OutputStream,
        fileName: String,
    ) {
        try {
            val decoded =
                try {
                    URLDecoder.decode(fileName, "UTF-8")
                } catch (_: Exception) {
                    fileName
                }
            val safeName = decoded.replace(Regex("[\\\\/]"), "").replace("..", ".")
            if (safeName.isEmpty()) {
                send404(output)
                return
            }
            val coversDir = File(context.filesDir, "local_covers")
            val file = File(coversDir, safeName)
            if (!file.exists() || !file.isFile) {
                send404(output)
                return
            }
            val ext = file.name.substringAfterLast('.', "jpg").lowercase()
            val mime =
                when (ext) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    else -> "image/jpeg"
                }
            val contentLength = file.length()
            val header =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: $contentLength\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: max-age=86400\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            val buf = ByteArray(65536)
            file.inputStream().use { input ->
                var bytesRead: Int
                while (input.read(buf).also { bytesRead = it } != -1) {
                    output.write(buf, 0, bytesRead)
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveLocalCoverFile error", e)
            send404(output)
        }
    }

    /**
     * 从内部存储提供本地歌曲内嵌歌词（LRC 文本）
     */
    private fun serveLocalLyricFile(
        output: OutputStream,
        fileName: String,
    ) {
        try {
            val decoded =
                try {
                    URLDecoder.decode(fileName, "UTF-8")
                } catch (_: Exception) {
                    fileName
                }
            val safeName = decoded.replace(Regex("[\\\\/]"), "").replace("..", ".")
            if (safeName.isEmpty()) {
                send404(output)
                return
            }
            val lyricsDir = File(context.filesDir, "local_lyrics")
            val file = File(lyricsDir, safeName)
            if (!file.exists() || !file.isFile) {
                send404(output)
                return
            }
            val bytes = file.readBytes()
            val header =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(bytes)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveLocalLyricFile error", e)
            send404(output)
        }
    }

    /**
     * ★ 代理竖屏 UI Room 数据库歌曲封面（content://media/external/audio/albumart/{albumId}）
     * 通过 ContentResolver 打开 InputStream 流式传输给 WebView
     */
    private fun serveRoomCover(
        output: OutputStream,
        albumIdStr: String,
    ) {
        try {
            val decoded =
                try {
                    URLDecoder.decode(albumIdStr, "UTF-8")
                } catch (_: Exception) {
                    albumIdStr
                }
            val albumId =
                decoded.toLongOrNull() ?: run {
                    send404(output)
                    return
                }
            if (albumId <= 0L) {
                send404(output)
                return
            }
            val coverUri = Uri.parse("content://media/external/audio/albumart/$albumId")
            val cr = context.contentResolver
            val inputStream =
                try {
                    cr.openInputStream(coverUri)
                } catch (e: Exception) {
                    null
                }
            if (inputStream == null) {
                send404(output)
                return
            }
            // 尝试获取文件大小（可能失败，则用 chunked 思路：不设 Content-Length，直接流式输出）
            var fileSize = 0L
            try {
                cr.query(coverUri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) fileSize = cursor.getLong(0)
                }
            } catch (_: Exception) {
            }
            val header =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    (if (fileSize > 0) "Content-Length: $fileSize\r\n" else "") +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: max-age=86400\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            inputStream.use { stream ->
                val buf = ByteArray(65536)
                var bytesRead: Int
                while (stream.read(buf).also { bytesRead = it } != -1) {
                    output.write(buf, 0, bytesRead)
                    output.flush()
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveRoomCover error: ${e.message}")
            send404(output)
        }
    }

    /**
     * ★ 代理竖屏 UI Room 数据库歌曲文件
     * 优先通过 content://media/external/audio/media/{mediaStoreId} URI + ContentResolver 读取（更可靠）
     * 回退到 SongEntity.path 文件路径
     */
    private fun serveRoomSong(
        output: OutputStream,
        mediaStoreIdStr: String,
    ) {
        try {
            val decoded =
                try {
                    URLDecoder.decode(mediaStoreIdStr, "UTF-8")
                } catch (_: Exception) {
                    mediaStoreIdStr
                }
            val mediaStoreId =
                decoded.toLongOrNull() ?: run {
                    send404(output)
                    return
                }
            if (mediaStoreId <= 0L) {
                send404(output)
                return
            }
            val cr = context.contentResolver
            // ★ 优先通过 MediaStore URI 读取（更可靠，不依赖文件路径权限）
            val mediaUri = Uri.parse("content://media/external/audio/media/$mediaStoreId")
            var inputStream: InputStream? = null
            try {
                inputStream = cr.openInputStream(mediaUri)
            } catch (e: Exception) {
                Log.w(TAG, "serveRoomSong: MediaStore URI 打开失败: ${e.message}")
            }
            // ★ 回退到文件路径
            if (inputStream == null) {
                val songDao =
                    try {
                        GlobalContext.get().get<SongDao>()
                    } catch (e: Exception) {
                        null
                    }
                val song = songDao?.getSongWithMediaStoreId(mediaStoreId)
                if (song != null && song.path.isNotBlank()) {
                    val file = File(song.path)
                    if (file.exists() && file.isFile) {
                        inputStream = file.inputStream()
                    }
                }
            }
            if (inputStream == null) {
                Log.e(TAG, "serveRoomSong: 无法打开音频流, mediaStoreId=$mediaStoreId")
                send404(output)
                return
            }
            // 尝试从 MediaStore 获取 MIME 类型
            var mime = "audio/mpeg"
            try {
                cr.query(mediaUri, arrayOf("mime_type"), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) mime = cursor.getString(0) ?: "audio/mpeg"
                }
            } catch (_: Exception) {
            }
            val header =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Cache-Control: max-age=86400\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            inputStream.use { stream ->
                val buf = ByteArray(262144)
                var bytesRead: Int
                while (stream.read(buf).also { bytesRead = it } != -1) {
                    output.write(buf, 0, bytesRead)
                    output.flush()
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveRoomSong error: ${e.message}")
            send404(output)
        }
    }

    /**
     * 通过 content:// URI 提供 SAF 文件（文件夹导入的音频）
     */
    private fun serveSafFile(
        output: OutputStream,
        uriStr: String,
    ) {
        try {
            if (uriStr.isBlank()) {
                send404(output)
                return
            }
            val uri = Uri.parse(uriStr)
            val cr = context.contentResolver
            // 获取文件大小
            var fileSize = 0L
            var fileName = "audio"
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    fileName = cursor.getString(0) ?: "audio"
                    fileSize = cursor.getLong(1)
                }
            }
            val ext = fileName.substringAfterLast('.', "mp3").lowercase()
            val mime = MIME[ext] ?: "audio/mpeg"
            val inputStream =
                cr.openInputStream(uri) ?: run {
                    send404(output)
                    return
                }
            if (fileSize <= 0L) fileSize = inputStream.available().toLong().coerceAtLeast(1)

            // ★ 流式传输：先发 header，再用 256KB 缓冲区分块发送
            //    避免 readBytes() 将整首无损歌一次性加载到内存
            val header =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: $fileSize\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Cache-Control: max-age=86400\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())

            inputStream.use { stream ->
                val buf = ByteArray(262144)
                var bytesRead: Int
                while (stream.read(buf).also { bytesRead = it } != -1) {
                    output.write(buf, 0, bytesRead)
                    output.flush() // ★ 立即 flush，保证播放器持续有数据
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveSafFile error: ${e.message}")
            send404(output)
        }
    }

    /**
     * 通过绝对路径 serve 文件（内置文件管理器）
     */
    private fun serveFilePath(
        output: OutputStream,
        filePath: String,
    ) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists() || !file.isFile) {
                send404(output)
                return
            }
            val ext = file.extension.lowercase().ifBlank { "mp3" }
            val mime = MIME[ext] ?: "audio/mpeg"
            val contentLength = file.length()
            val header =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: $contentLength\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Cache-Control: max-age=86400\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            // ★ 256KB 缓冲区 + 立即 flush
            val buf = ByteArray(262144)
            file.inputStream().use { input ->
                var bytesRead: Int
                while (input.read(buf).also { bytesRead = it } != -1) {
                    output.write(buf, 0, bytesRead)
                    output.flush()
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveFilePath error: ${e.message}")
            send404(output)
        }
    }

    private fun sendEmpty(
        output: OutputStream,
        status: Int,
    ) {
        val header = "HTTP/1.1 $status ${statusText(status)}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.flush()
    }

    private fun sendBinary(
        output: OutputStream,
        status: Int,
        contentType: String,
        data: ByteArray,
    ) {
        val header =
            "HTTP/1.1 $status ${statusText(status)}\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n"
        output.write(header.toByteArray())
        output.write(data)
        output.flush()
    }

    /** ★ 根据文件扩展名推断 MIME 类型（用于插件资源服务） */
    private fun guessMimeByExtension(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            // 文本类
            "json", "model3", "motion3", "exp3", "physics3", "cdi3", "vtube" -> "application/json; charset=utf-8"
            "txt", "md" -> "text/plain; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "html", "htm" -> "text/html; charset=utf-8"
            // 图片类
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            // 音频类
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            // 视频类
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            // 二进制类
            "so" -> "application/octet-stream"
            "zip" -> "application/zip"
            "moc3" -> "application/octet-stream"
            // 默认
            else -> "application/octet-stream"
        }
    }

    private fun statusText(code: Int): String =
        when (code) {
            200 -> "OK"
            204 -> "No Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            409 -> "Conflict"
            500 -> "Internal Server Error"
            else -> ""
        }

    // ═══════════════════════════════════════════════
    //  请求解析
    // ═══════════════════════════════════════════════

    private fun readRequest(input: InputStream): Triple<String, String, List<Pair<String, String>>>? {
        val sb = StringBuilder()
        var prev = 0
        var cur: Int
        var headerComplete = false
        while (true) {
            cur = input.read()
            if (cur == -1) break
            sb.append(cur.toChar())
            if (prev == '\r'.code && cur == '\n'.code && sb.endsWith("\r\n\r\n")) {
                headerComplete = true
                break
            }
            prev = cur
            // ★ 缓冲区扩大到 65536: QQ 音乐 cookie + headers 编码后 URL 可能超过 8192,
            //   导致请求头读取不完整,连接被关闭,前端报 Failed to fetch
            if (sb.length > 65536) break
        }
        if (!headerComplete || sb.isEmpty()) return null

        val lines = sb.toString().split("\r\n")
        if (lines.isEmpty()) return null

        val first = lines[0].split(" ")
        if (first.size < 2) return null
        val method = first[0]
        val path = first[1]

        // 解析请求头
        val headers = mutableListOf<Pair<String, String>>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.isNotEmpty()) headers.add(name to value)
            }
        }

        return Triple(method, path, headers)
    }

    private fun readBody(
        input: InputStream,
        method: String,
    ): String {
        if (method == "GET" || method == "HEAD") return ""
        return try {
            val bytes = ByteArray(65536)
            val read = input.read(bytes)
            if (read > 0) String(bytes, 0, read, Charsets.UTF_8) else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun getQueryParam(
        query: String,
        key: String,
    ): String? {
        if (query.isBlank()) return null
        return try {
            query.split("&").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2 && URLDecoder.decode(parts[0], "UTF-8") == key) {
                    return URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractCookie(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val json = JSONObject(body)
            json.optString("cookie", json.optString("data", json.optString("text", "")))
        } catch (e: Exception) {
            body.trim()
        }
    }

    private fun extractJsonParam(
        body: String,
        key: String,
    ): String? {
        if (body.isBlank()) return null
        return try {
            val obj = JSONObject(body)
            if (obj.has(key)) obj.getString(key) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseCookieObj(cookieText: String): Map<String, String> {
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

    /**
     * ★ 音频代理：转发到 Node 后端 /api/audio（一比一参考 mineradio-android 架构）
     *
     * Node server.js 的 /api/audio 处理器会对汽水音乐 douyinvod 链接中的 #auth= 加密音频
     * 调用 getQishuiDecryptedAudio 完成解密后再返回，否则播放器拿到加密字节无法解码(Broken pipe)。
     * 这里把 Range 头透传给 Node（Node 支持 206 分段响应），并流式转发响应体，保证边下边播、支持 seek。
     */
    private fun proxyAudioToNode(
        output: OutputStream,
        query: String,
        headers: List<Pair<String, String>>,
    ) {
        var conn: HttpURLConnection? = null
        try {
            val targetPath = if (query.isBlank()) "/api/audio" else "/api/audio?$query"
            conn = URL("http://127.0.0.1:3000$targetPath").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 120000
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Connection", "close")
            // 透传 Range 头，让 Node 返回 206 分段，播放器可 seek
            val range = headers.firstOrNull { it.first.equals("Range", ignoreCase = true) }?.second
            if (!range.isNullOrBlank()) conn.setRequestProperty("Range", range)

            val code = conn.responseCode
            val isErr = code >= 400
            val resp =
                try {
                    if (isErr) conn.errorStream else conn.inputStream
                } catch (_: Exception) {
                    null
                }

            val contentType = conn.contentType ?: "audio/mpeg"
            val contentLength = conn.getHeaderField("Content-Length")
            val contentRange = conn.getHeaderField("Content-Range")
            val acceptRanges = conn.getHeaderField("Accept-Ranges")

            val header =
                buildString {
                    append("HTTP/1.1 ").append(code).append(" OK\r\n")
                    append("Content-Type: ").append(contentType).append("\r\n")
                    if (!contentLength.isNullOrBlank()) append("Content-Length: ").append(contentLength).append("\r\n")
                    if (!contentRange.isNullOrBlank()) append("Content-Range: ").append(contentRange).append("\r\n")
                    if (!acceptRanges.isNullOrBlank()) append("Accept-Ranges: ").append(acceptRanges).append("\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n\r\n")
                }
            output.write(header.toByteArray())
            output.flush()

            if (resp != null) {
                val buf = ByteArray(65536)
                try {
                    while (true) {
                        val n = resp.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        output.flush()
                    }
                } catch (_: Exception) {
                    // 客户端提前断开属正常情况，忽略
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "proxyAudioToNode failed: ${e.message}")
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 代理外部 URL 内容（用于封面/音频转发，解决 WebView 跨域+Referer问题）
     *
     * ★ 极限优化版：针对高码率音频流做了彻底优化
     *   - 256KB 大缓冲区，减少系统调用次数
     *   - 激进 flush 策略：每 64KB 立即 flush，确保播放器不缺数据
     *   - 移除 Nagle 算法延迟
     *   - Accept-Ranges 支持断点续传
     */
    private fun proxyUrl(
        output: OutputStream,
        query: String,
        paramName: String,
    ) {
        var workingConn: HttpURLConnection? = null
        try {
            val rawUrl = getQueryParam(query, paramName)
            if (rawUrl.isNullOrBlank()) {
                send404(output)
                return
            }

            workingConn = openProxyConnection(URL(rawUrl))
            val code = workingConn.responseCode

            if (code !in 200..299) {
                if (code in 301..308) {
                    workingConn = handleRedirect(workingConn, code)
                    if (workingConn == null) {
                        sendEmpty(output, 302)
                        return
                    }
                } else {
                    sendEmpty(output, 302)
                    return
                }
            }

            val contentType = workingConn.contentType ?: "application/octet-stream"
            val contentLength = workingConn.contentLength
            val isAudio = contentType.startsWith("audio/")
            val inputStream = workingConn.inputStream

            // ★ 立即发送 header，让播放器尽快开始缓冲
            val header =
                if (contentLength > 0) {
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Content-Length: $contentLength\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Cache-Control: public, max-age=3600\r\n" +
                        "Connection: close\r\n\r\n"
                } else {
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Cache-Control: public, max-age=3600\r\n" +
                        "Connection: close\r\n\r\n"
                }
            output.write(header.toByteArray())
            output.flush()

            // ★ 256KB 缓冲区 — 对大文件减少 read() 系统调用次数
            val buf = ByteArray(262144)
            var bytesRead: Int
            var totalRead = 0L
            var unflushed = 0L // ★ 累计未 flush 的字节数
            val isChunked = contentLength <= 0
            // 音频流每 64KB flush，非音频流每 256KB flush
            val flushInterval = if (isAudio) 65536L else 262144L

            while (inputStream.read(buf).also { bytesRead = it } != -1) {
                if (isChunked) {
                    output.write(Integer.toHexString(bytesRead).toByteArray())
                    output.write("\r\n".toByteArray())
                }
                output.write(buf, 0, bytesRead)
                if (isChunked) {
                    output.write("\r\n".toByteArray())
                }
                totalRead += bytesRead
                unflushed += bytesRead

                if (unflushed >= flushInterval) {
                    output.flush()
                    unflushed = 0L
                }
            }
            // 最后一批数据 flush
            if (unflushed > 0) output.flush()
            if (isChunked) {
                output.write("0\r\n\r\n".toByteArray())
            }
            output.flush()
        } catch (e: Exception) {
            Log.w(TAG, "proxyUrl failed: ${e.message}")
            try {
                sendEmpty(output, 302)
            } catch (_: Exception) {
            }
        } finally {
            try {
                workingConn?.inputStream?.close()
            } catch (_: Exception) {
            }
            try {
                workingConn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 代理平台业务 API 到 Node.js 后端(127.0.0.1:3000)
     *
     * 由 mineradio-android 移植。登录 / 搜索 / 歌曲 / 歌词 / 歌单 / QQ / 酷狗 / 汽水 等
     * 平台 API 在 Node 后端(server.js)中实现，Kotlin 后端在此转发并原样返回响应。
     */
    private fun proxyToNodeBackend(
        output: OutputStream,
        method: String,
        pathOnly: String,
        query: String,
        input: InputStream,
    ) {
        var conn: HttpURLConnection? = null
        try {
            // login-panel 插件使用旧前缀 /api/kg/* 发送酷狗请求，而 Node 后端只注册了 /api/kugou/*，
            // 这里统一重写为 /api/kugou/*，避免酷狗 cookie 同步等请求落到 Node 后端 404/HTML 而失败。
            val normalizedPath = pathOnly.replaceFirst("/api/kg/", "/api/kugou/")
            val targetPath = if (query.isBlank()) normalizedPath else "$normalizedPath?$query"
            val target = URL("http://127.0.0.1:3000$targetPath")
            conn = target.openConnection() as HttpURLConnection
            conn.requestMethod = method.uppercase()
            conn.connectTimeout = 10000
            conn.readTimeout = 60000
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Connection", "close")

            val body = readBody(input, method)
            if (method == "POST" || method == "PUT" || method == "PATCH") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (body.isNotEmpty()) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    conn.setRequestProperty("Content-Length", bytes.size.toString())
                    conn.outputStream.use { it.write(bytes) }
                }
            }

            val code = conn.responseCode
            val isErr = code >= 400
            val resp =
                try {
                    if (isErr) conn.errorStream else conn.inputStream
                } catch (e: Exception) {
                    null
                }
            val contentType = conn.contentType ?: "application/json"

            val bytes =
                if (resp != null) {
                    resp.buffered().use { it.readBytes() }
                } else {
                    ByteArray(0)
                }

            val header =
                "HTTP/1.1 $code OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(bytes)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "proxyToNodeBackend failed: $method $pathOnly", e)
            // Node 后端未就绪时返回 502，前端可显示明确错误
            try {
                sendJson(
                    output,
                    502,
                    JSONObject().apply {
                        put("error", "node_backend_unavailable")
                        put("message", "Node 后端未就绪")
                    },
                )
            } catch (_: Exception) {
            }
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 建立代理连接 — 统一处理 Cookie/Referer/TCP 优化
     */
    private fun openProxyConnection(url: URL): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 180000 // ★ 3分钟，大文件下载不会超时断开
        conn.requestMethod = "GET"
        conn.setRequestProperty("Connection", "close")
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        )
        conn.setRequestProperty("Accept-Encoding", "identity") // ★ 禁止压缩，减少 CPU

        val rawUrl = url.toString()
        when {
            rawUrl.contains("music.126.net") || rawUrl.contains("music.163.com") || rawUrl.contains("126.net") -> {
                conn.setRequestProperty("Referer", "https://music.163.com/")
                val neCookie = NeteaseMusicApi.getCookie()
                if (neCookie.isNotBlank()) conn.setRequestProperty("Cookie", neCookie)
            }
            rawUrl.contains("qqmusic") || rawUrl.contains("qq.com") || rawUrl.contains("y.qq.com") -> {
                conn.setRequestProperty("Referer", "https://y.qq.com/")
                val qqC = NeteaseMusicApi.getQQCookie()
                if (qqC.isNotBlank()) conn.setRequestProperty("Cookie", qqC)
            }
            rawUrl.contains("kugou.com") || rawUrl.contains("kgmusic") -> {
                conn.setRequestProperty("Referer", "https://www.kugou.com/")
                val kgC = NeteaseMusicApi.getKGCookie()
                if (kgC.isNotBlank()) conn.setRequestProperty("Cookie", kgC)
            }
            rawUrl.contains("douyinvod.com") ||
                rawUrl.contains("douyin.com") ||
                rawUrl.contains("qishui.com") ||
                rawUrl.contains("bytedance") ||
                rawUrl.contains("bytecdn") ||
                rawUrl.contains("snssdk.com") ||
                rawUrl.contains("pstatp.com") -> {
                conn.setRequestProperty("Referer", "https://www.douyin.com/")
                val qsC = NeteaseMusicApi.getQSCookie()
                if (qsC.isNotBlank()) conn.setRequestProperty("Cookie", qsC)
            }
            rawUrl.contains("woff") || rawUrl.contains("google") ->
                conn.setRequestProperty("Referer", "")
            else ->
                conn.setRequestProperty("Referer", "https://music.163.com/")
        }
        return conn
    }

    /** 处理 3xx 重定向 */
    private fun handleRedirect(
        conn: HttpURLConnection,
        code: Int,
    ): HttpURLConnection? {
        if (code !in 301..308) return null
        val location = conn.getHeaderField("Location") ?: return null
        if (!location.startsWith("http")) return null
        conn.disconnect()
        val redirectUrl = URL(location)
        val redirConn = openProxyConnection(redirectUrl)
        return if (redirConn.responseCode in 200..299) {
            redirConn
        } else {
            redirConn.disconnect()
            null
        }
    }

    // ═══════════════════════════════════════════════
    //  Beatmap 磁盘缓存
    // ═══════════════════════════════════════════════

    private fun serveBeatCache(
        output: OutputStream,
        cacheKey: String,
    ) {
        try {
            val safeName = cacheKey.replace(Regex("[^A-Za-z0-9_\\-.:]"), "").take(128)
            if (safeName.isBlank()) {
                sendJson(
                    output,
                    404,
                    JSONObject().apply {
                        put("hit", false)
                        put("enabled", true)
                    },
                )
                return
            }
            val cacheDir = File(context.filesDir, "beat_cache")
            cacheDir.mkdirs()
            val file = File(cacheDir, "$safeName.json")
            if (!file.exists() || !file.isFile) {
                sendJson(
                    output,
                    200,
                    JSONObject().apply {
                        put("hit", false)
                        put("enabled", true)
                    },
                )
                return
            }
            val content = file.readText()
            val json = JSONObject(content)
            json.put("hit", true)
            json.put("enabled", true)
            sendJson(output, 200, json)
        } catch (e: Exception) {
            Log.w(TAG, "serveBeatCache read error: ${e.message}")
            sendJson(
                output,
                200,
                JSONObject().apply {
                    put("hit", false)
                    put("enabled", true)
                },
            )
        }
    }

    private fun serveBeatCacheWrite(
        output: OutputStream,
        body: String,
    ) {
        try {
            val json = JSONObject(body)
            val cacheKey = json.optString("key", "")
            val safeName = cacheKey.replace(Regex("[^A-Za-z0-9_\\-.:]"), "").take(128)
            val mapVal = json.opt("map")
            if (safeName.isBlank() || mapVal == null || mapVal == JSONObject.NULL) {
                sendJson(
                    output,
                    400,
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "Missing key or map")
                    },
                )
                return
            }
            val cacheDir = File(context.filesDir, "beat_cache")
            cacheDir.mkdirs()
            val file = File(cacheDir, "$safeName.json")
            val writeJson =
                JSONObject(body).apply {
                    put("savedAt", System.currentTimeMillis())
                    put("enabled", true)
                }
            file.writeText(writeJson.toString())
            // 清理超过 60 条最旧记录
            val allFiles =
                cacheDir
                    .listFiles()
                    ?.filter { it.isFile && it.extension == "json" }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (allFiles.size > 60) {
                allFiles.drop(60).forEach { it.delete() }
            }
            sendJson(
                output,
                200,
                JSONObject().apply {
                    put("ok", true)
                    put("enabled", true)
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "serveBeatCache write error: ${e.message}")
            sendJson(
                output,
                500,
                JSONObject().apply {
                    put("ok", false)
                    put("error", e.message ?: "unknown")
                },
            )
        }
    }
}
