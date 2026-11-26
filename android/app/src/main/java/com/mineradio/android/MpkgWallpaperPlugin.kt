package com.mineradio.android

import androidx.activity.result.ActivityResult
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.mineradio.android.wallpaper.WallpaperEntity
import com.mineradio.android.wallpaper.WallpaperManager

/**
 * 壁纸桥：把 WallpaperManager 的能力暴露给 WebView 里的壁纸面板。
 *
 * 背景：前端 03-wallpaper-engine-library.js 面板调用的是桌面版的
 * window.desktopWindow.listWallpaperEngineProjects 等方法（Electron 专有），
 * 原生工程里这些方法全部是空实现（capacitor-bridge.js 注释「移动端完全禁用」），
 * 原生工程的壁纸实际走 AndroidBridge.launchMpkgPreview 一条路。
 *
 * 这里把 WallpaperManager 的列表/导入/设为当前/删除接成 Capacitor 插件，
 * 再由 public/js/android-shim/40-wallpaper-bridge.js 适配成面板期望的形状。
 */
@CapacitorPlugin(name = "MpkgWallpaper")
class MpkgWallpaperPlugin : Plugin() {

    private fun entityToJson(w: WallpaperEntity): JSObject {
        val o = JSObject()
        o.put("id", w.id)
        o.put("name", w.name)
        o.put("path", w.path)
        o.put("author", w.author)
        o.put("description", w.description)
        o.put("versionCode", w.versionCode)
        o.put("versionName", w.versionName)
        o.put("wallpaperType", w.wallpaperType)
        // 面板用它区分 mpkg / video / image / html
        o.put("type", w.wallpaperType)
        // 实际文件是否存在、mpkg 私有路径（native 库只能读私有目录）
        val real = java.io.File(w.getRealPath(context))
        o.put("fileExists", real.exists())
        if (w.wallpaperType == WallpaperEntity.TYPE_MPKG_WEBGL) {
            o.put("mpkgPrivatePath", w.getMpkgPrivatePath(context))
        }
        return o
    }

    @PluginMethod
    fun listWallpapers(call: PluginCall) {
        try {
            val manager = WallpaperManager.get(context)
            // 打开面板/查询列表时顺手清理重复壁纸（同名+同类型+同大小），只做一次
            try { manager.dedupeIfNeeded() } catch (e: Exception) { }
            val list = manager.getWallpaperList()
            val arr = com.getcapacitor.JSArray()
            list.forEach { arr.put(entityToJson(it)) }
            val current = manager.getCurrentWallpaper()
            val result = JSObject()
            result.put("wallpapers", arr)
            result.put("currentId", current?.id ?: "")
            call.resolve(result)
        } catch (e: Exception) {
            call.reject("list failed: " + e.message, e)
        }
    }

    @PluginMethod
    fun getCurrentWallpaper(call: PluginCall) {
        try {
            val current = WallpaperManager.get(context).getCurrentWallpaper()
            val result = JSObject()
            result.put("wallpaper", current?.let { entityToJson(it) })
            call.resolve(result)
        } catch (e: Exception) {
            call.reject("getCurrent failed: " + e.message, e)
        }
    }

    private var pendingImportCall: PluginCall? = null
    private var pendingImportType = WallpaperEntity.TYPE_MPKG_WEBGL

    /**
     * 弹系统文件选择器（SAF），选中后原生侧直接导入。
     *
     * 为什么不用前端 input[type=file]：MPKG 动辄几十 MB，
     * 前端读成 base64 过 Capacitor 桥（一坨 JS 字符串）时 OOM：
     * 实测 27MB 文件在 Bridge.callPluginMethod 的 StringBuilder 上分配 72MB 直接崩
     * （crash 栈：OutOfMemoryError at Bridge.callPluginMethod:841）。
     * SAF 里文件由 ContentResolver 流式复制，字节全程不过 JS。
     */
    private var pendingDirectoryCall: PluginCall? = null

    /**
     * 「导入目录」：用 SAF 目录选择器授权一个文件夹，递归导入里面的壁纸文件。
     * 与「导入项目」（pickAndImport 单文件）分开，互不干扰。
     */
    @PluginMethod
    fun pickDirectoryAndImport(call: PluginCall) {
        if (pendingDirectoryCall != null) {
            call.reject("已有目录导入在进行")
            return
        }
        pendingDirectoryCall = call
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        try {
            startActivityForResult(call, intent, "onDirectoryPicked")
        } catch (e: Exception) {
            pendingDirectoryCall = null
            call.reject("无法打开目录选择器: " + e.message, e)
        }
    }

    @ActivityCallback
    private fun onDirectoryPicked(call: PluginCall, result: ActivityResult) {
        val pending = pendingDirectoryCall
        pendingDirectoryCall = null
        if (pending == null || call == null || result.resultCode != android.app.Activity.RESULT_OK) {
            return
        }
        val treeUri = result.data?.data
        if (treeUri == null) {
            call.reject("未选择目录")
            return
        }
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: Exception) { }

        // 目录可能包含几十个文件：整段导入放到后台线程，避免 ANR
        Thread {
            try {
                val wm = com.mineradio.android.wallpaper.WallpaperManager.get(context)
                val imported = ArrayList<com.mineradio.android.wallpaper.WallpaperEntity>()
                var failed = 0
                val rootDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                walkDocuments(treeUri, rootDocId, 0) { uri, name ->
                    if (!isWallpaperFileName(name)) return@walkDocuments
                    val entity = try { wm.importFromFile(context, uri) } catch (e: Exception) { null }
                    if (entity != null) imported.add(entity) else failed += 1
                }
                val arr = com.getcapacitor.JSArray()
                imported.forEach { arr.put(entityToJson(it)) }
                val out = JSObject()
                out.put("ok", true)
                out.put("imported", imported.size)
                out.put("failed", failed)
                out.put("wallpapers", arr)
                call.resolve(out)
            } catch (e: Exception) {
                call.reject("目录导入失败: " + e.message, e)
            }
        }.start()
    }

    private fun isWallpaperFileName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mpkg") || lower.endsWith(".pkg") || lower.endsWith(".pmkg") ||
            lower.endsWith(".pkm") || lower.endsWith(".mp4") || lower.endsWith(".webm") ||
            lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(".m4v") ||
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".gif")
    }

    /** DocumentsContract 递归遍历（最多 3 层），不引入额外依赖。 */
    private fun walkDocuments(
        treeUri: android.net.Uri,
        parentDocId: String,
        depth: Int,
        onFile: (android.net.Uri, String) -> Unit,
    ) {
        if (depth > 3) return
        val childrenUri = android.provider.DocumentsContract
            .buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                        walkDocuments(treeUri, docId, depth + 1, onFile)
                    } else {
                        val docUri = android.provider.DocumentsContract
                            .buildDocumentUriUsingTree(treeUri, docId)
                        onFile(docUri, displayName)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MpkgWallpaper", "walk directory failed", e)
        }
    }

    @PluginMethod
    fun pickAndImport(call: PluginCall) {
        // ★ 默认「自动识别类型」：type 缺省/为负 → 交给 WallpaperManager.guessTypeFromFileName
        //   按扩展名判（.mpkg/.pkg/.pmkg/.pkm → 场景包；.mp4 等 → 视频；图片 → 图片）。
        //   之前硬编码 TYPE_MPKG_WEBGL，用户在手机文件管理器里选 .pkg 或 .mp4 都会被当成
        //   MPKG 场景包 → 解析不出 scene.json → 黑屏「导入了但不显示」。
        val type = call.getInt("type", -1) ?: -1
        if (pendingImportCall != null) {
            call.reject("已有导入在进行")
            return
        }
        pendingImportCall = call
        pendingImportType = type

        // 手机端用 ACTION_OPEN_DOCUMENT（比 GET_CONTENT 更规范，能拿到持久读取权限，
        // 且国产 ROM 的文件管理器对它支持更好）
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            setType("*/*")
            // 支持一次选多个壁纸文件（用户要求「直接选择多个文件导入」）
            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(call, intent, "onImportPicked")
        } catch (e: Exception) {
            pendingImportCall = null
            call.reject("无法打开文件选择器: " + e.message, e)
        }
    }

    @ActivityCallback
    private fun onImportPicked(call: PluginCall, result: ActivityResult) {
        pendingImportCall = null
        if (call == null || result.resultCode != android.app.Activity.RESULT_OK) {
            return
        }
        // 支持单选与多选：多选时 Uri 在 ClipData 里，单选在 data.data
        val uris = ArrayList<android.net.Uri>()
        val resultData = result.data
        val clip = resultData?.clipData
        if (clip != null && clip.itemCount > 0) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { uris.add(it) }
            }
        } else if (resultData?.data != null) {
            uris.add(resultData.data!!)
        }
        if (uris.isEmpty()) {
            call.reject("未选择文件")
            return
        }
        /*
         * 【修复·ANR】导入复制必须离开主线程！
         * ANR 堆栈（anr_26779）实锤：importFromFile → ContentResolver.openInputStream
         * 在主线程上同步复制大文件（MPKG 动辄几十 MB），输入事件 5 秒无响应 →
         * 系统判 ANR → 用户点关闭 → 表现为「导入直接闪退」。
         * 参考工程（原生 Kotlin 版）就是 Thread { ... }.start() 的做法。
         */
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val wm = com.mineradio.android.wallpaper.WallpaperManager.get(context)
                val imported = ArrayList<com.mineradio.android.wallpaper.WallpaperEntity>()
                var failed = 0
                for (u in uris) {
                    val entity = if (pendingImportType >= 0) {
                        wm.importFromFile(context, u, pendingImportType)
                    } else {
                        wm.importFromFile(context, u)
                    }
                    if (entity != null) imported.add(entity) else failed += 1
                }
                android.os.Handler(context.mainLooper).post {
                    if (imported.isEmpty()) {
                        call.reject("导入失败：文件无法解析")
                        return@post
                    }
                    val arr = com.getcapacitor.JSArray()
                    imported.forEach { arr.put(entityToJson(it)) }
                    val out = JSObject()
                    out.put("ok", true)
                    out.put("imported", imported.size)
                    out.put("failed", failed)
                    out.put("wallpapers", arr)
                    // 单文件仍返回 wallpaper，兼容旧调用方
                    if (imported.size == 1) out.put("wallpaper", entityToJson(imported[0]))
                    call.resolve(out)
                }
            } catch (e: Exception) {
                android.os.Handler(context.mainLooper).post {
                    call.reject("导入失败: " + e.message, e)
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    /**
     * 导入本地 mpkg/视频/图片。sourcePath 是 App 可读的绝对路径。
     *
     * 为什么不直接把 sourcePath 交给 WallpaperManager.importFromFile：
     * 它内部会把文件复制到 Documents/Mineradio/Wallpaper/（公共外部存储），
     * 而 targetSdk 36 + 无存储权限下写公共目录必然失败 —— 这就是「导入一直不成功」。
     * 所以这里先把文件收到 App 私有目录（files/imports，一定能写），
     * 再从私有路径走标准导入；并把 WallpaperManager 的资源目录也留在私有区。
     */
    @PluginMethod
    fun importFromFile(call: PluginCall) {
        val sourcePath = call.getString("sourcePath") ?: ""
        val name = call.getString("name") ?: ""
        val type = call.getInt("type", WallpaperEntity.TYPE_MPKG_WEBGL) ?: WallpaperEntity.TYPE_MPKG_WEBGL
        if (sourcePath.isEmpty()) {
            call.reject("sourcePath required")
            return
        }
        try {
            val source = java.io.File(sourcePath)
            if (!source.exists()) {
                call.reject("import failed: 源文件不存在: $sourcePath")
                return
            }
            // 1) 收进 App 私有 imports 目录
            val importsDir = java.io.File(context.filesDir, "imports").apply { mkdirs() }
            val localFile = java.io.File(importsDir, source.name)
            source.copyTo(localFile, overwrite = true)

            // 2) 从私有路径走 WallpaperManager（它写公共 Documents 的那步仍可能失败，
            //    但 addWallpaper 只依赖内存列表+配置文件；资源目录失败不影响播放，
            //    因为 MPKG 渲染读的是 files/mpkg 私有副本。）
            val imported = WallpaperManager.get(context).importFromFile(localFile.absolutePath, name, type)
            if (imported == null) {
                call.reject("import failed: WallpaperManager 拒绝（文件不可读或格式不支持）")
                return
            }
            val result = JSObject()
            result.put("wallpaper", entityToJson(imported))
            call.resolve(result)
        } catch (e: Exception) {
            call.reject("import failed: " + e.message, e)
        }
    }

    /** 把某张壁纸设为当前（系统动态壁纸引擎 LWService 会立刻切过去）。 */
    @PluginMethod
    fun setCurrentWallpaper(call: PluginCall) {
        val id = call.getString("id") ?: ""
        if (id.isEmpty()) {
            call.reject("id required")
            return
        }
        try {
            val manager = WallpaperManager.get(context)
            val target = manager.getWallpaperList().firstOrNull { it.id == id }
            if (target == null) {
                call.reject("wallpaper not found: $id")
                return
            }
            manager.setCurrentWallpaper(target)
            // 尝试让系统壁纸服务也切过去（用户若没把 Mineradio 设为系统壁纸则静默忽略）
            try {
                com.mineradio.android.wallpaper.WallpaperHelper.applyAsSystemWallpaper(context, target)
            } catch (ignored: Exception) {
            }
            call.resolve()
        } catch (e: Exception) {
            call.reject("setCurrent failed: " + e.message, e)
        }
    }

    @PluginMethod
    fun removeWallpaper(call: PluginCall) {
        val id = call.getString("id") ?: ""
        if (id.isEmpty()) {
            call.reject("id required")
            return
        }
        try {
            WallpaperManager.get(context).removeWallpaper(id)
            call.resolve()
        } catch (e: Exception) {
            call.reject("remove failed: " + e.message, e)
        }
    }

    /**
     * 提取 MPKG 封面为 data:image/... URL（照原生工程 getWallpaperPreviewData 的思路，
     * 但不依赖 lastPickedFileUri——直接用已导入壁纸在私有目录的副本）。
     * 用 SceneLib.getWallpaperInfoPreviewBitmap 取官方预览帧；失败再退回空白。
     */
    @PluginMethod
    fun getPreview(call: PluginCall) {
        val nativeId = call.getString("nativeId") ?: ""
        if (nativeId.isEmpty()) { call.reject("nativeId required"); return }
        try {
            val wm = com.mineradio.android.wallpaper.WallpaperManager.get(context)
            val w = wm.getWallpaperList().firstOrNull { it.id == nativeId }
            if (w == null) { call.reject("wallpaper not found"); return }
            val dataUrl: String
            if (w.wallpaperType == WallpaperEntity.TYPE_MPKG_WEBGL) {
                // ★★★ 绝对不要在这里（插件线程）调 SceneLib 的任何 native 方法！
                //   崩溃栈证据：
                //     #02 AAssetManager_open+68
                //     #03 libscenejni.so AssetInputFile::AssetInputFile(AAssetManager*, ...)
                //     Cause: null pointer dereference, x0=0x0
                //   SceneLib 的 AssetManager 只在 GL 线程的 initLibrary 里设过，
                //   从 CapacitorPlugin 线程再调 native 就是空指针 → SIGSEGV 闪退。
                //   所以 MPKG 封面改为纯 Kotlin/Java 实现：
                //   视频型 mpkg → 解出 mp4 抽帧；场景型 → 暂不提供封面（返回空，面板显示占位）。
                dataUrl = try {
                    val path = w.getMpkgPrivatePath(context)
                    val f = java.io.File(path)
                    if (!f.exists()) {
                        val src = java.io.File(w.getRealPath(context))
                        if (src.exists()) {
                            val mpkgDir = java.io.File(context.filesDir, "mpkg").apply { mkdirs() }
                            src.copyTo(java.io.File(mpkgDir, "${w.id}_${src.name}"), overwrite = true)
                        }
                    }
                    // ★ 优先读引擎实际渲染帧（cover saved），保证「封面 = 后台画面」；
                    //   还没渲染过时才退回包内自带的 preview.jpg/gif。
                    val builtin = extractMpkgBuiltinPreview(w)
                    if (isVideoMpkg(w)) {
                        val videoFrame = extractVideoFrameDataUrl(w)
                        if (videoFrame.isNotEmpty()) videoFrame else builtin
                    } else {
                        val sceneCover = extractSceneCoverDataUrl(w)
                        if (sceneCover.isNotEmpty()) sceneCover else builtin
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("MpkgWallpaper", "preview extract failed", e)
                    ""
                }
            } else if (w.wallpaperType == WallpaperEntity.TYPE_IMAGE) {
                // 图片壁纸直接把自己当封面（缩小）
                dataUrl = try {
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    val real = w.getRealPath(context)
                    android.graphics.BitmapFactory.decodeFile(real, opts)
                    val sample = Math.max(1, Math.max(opts.outWidth, opts.outHeight) / 320)
                    val opts2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp0 = android.graphics.BitmapFactory.decodeFile(real, opts2)
                    if (bmp0 != null) {
                        val out = java.io.ByteArrayOutputStream()
                        bmp0.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, out)
                        "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                    } else ""
                } catch (e: Throwable) { "" }
            } else {
                // ★ 视频型壁纸：用 MediaMetadataRetriever 抽首帧作封面。
                //   视频型 MPKG 不能用 SceneLib.getWallpaperInfoPreviewBitmap（场景库 API，
                //   对视频包返回空）——这正是「背景有了但没预览图」的原因。
                dataUrl = extractVideoFrameDataUrl(w)
            }
            val out = JSObject()
            out.put("dataUrl", dataUrl)
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("preview failed: " + e.message, e)
        }
    }

    /**
     * 从 mpkg 里读内置预览图作封面。
     *
     * 关键发现（实测日志）：mpkg 的条目是
     *   [preview.gif, project.json, scene.json, wallpaper.mp4]
     * 包内本来就有 preview.gif —— 直接读它当封面即可，
     * 不需要 SceneLib（那会在插件线程崩）、也不需要给视频抽帧。
     *
     * 读取顺序：preview.gif > preview.jpg/png/webp > 其它含 preview 的图片，
     * 最后才退回视频抽帧。
     */
    private fun extractMpkgBuiltinPreview(w: WallpaperEntity): String {
        return try {
            val path = w.getMpkgPrivatePath(context)
            val f = java.io.File(path)
            if (!f.exists()) { android.util.Log.w("MpkgWallpaper", "builtin preview: mpkg missing"); return "" }
            val parser = com.mineradio.android.wallpaper.MpkgParser(f)
            val names = parser.listEntries().map { it.name }
            android.util.Log.d("MpkgWallpaper", "builtin preview candidates: $names")

            val lowered = names.map { it.lowercase() }
            // 优先级：preview.* > 含 preview 的图片 > 任意图片
            val pick = lowered.firstOrNull { it == "preview.gif" }
                ?: lowered.firstOrNull { it == "preview.png" }
                ?: lowered.firstOrNull { it == "preview.jpg" || it == "preview.jpeg" }
                ?: lowered.firstOrNull { it == "preview.webp" }
                ?: lowered.firstOrNull { it.contains("preview") && (it.endsWith(".gif") || it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp")) }
            if (pick == null) {
                android.util.Log.d("MpkgWallpaper", "builtin preview: no image entry")
                return ""
            }
            val originalName = names[lowered.indexOf(pick)]
            val bytes = parser.readBytes(originalName)
            if (bytes == null || bytes.isEmpty()) {
                android.util.Log.w("MpkgWallpaper", "builtin preview: readBytes null for $originalName")
                return ""
            }
            android.util.Log.d("MpkgWallpaper", "builtin preview: $originalName ${bytes.size}B")

            // GIF/PNG/JPEG 都先解码再统一转 JPEG（WebView 显示最稳）
            val opts = android.graphics.BitmapFactory.Options()
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (bmp == null) {
                android.util.Log.w("MpkgWallpaper", "builtin preview: decode failed")
                return ""
            }
            val maxW = 480
            val scaled = if (bmp.width > maxW) {
                val h = (bmp.height.toFloat() * maxW / bmp.width).toInt().coerceAtLeast(1)
                android.graphics.Bitmap.createScaledBitmap(bmp, maxW, h, true).also { if (it !== bmp) bmp.recycle() }
            } else bmp
            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
            scaled.recycle()
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Throwable) {
            android.util.Log.e("MpkgWallpaper", "builtin preview failed", e)
            ""
        }
    }

    /** 场景包封面：渲染线程在第 8 帧把画面存到 files/wallpaper_covers/<mpkg名>.jpg。 */
    private fun extractSceneCoverDataUrl(w: WallpaperEntity): String {
        return try {
            val mpkg = java.io.File(w.getMpkgPrivatePath(context))
            if (!mpkg.exists()) return ""
            val cover = java.io.File(
                java.io.File(context.filesDir, "wallpaper_covers"),
                mpkg.nameWithoutExtension + ".jpg",
            )
            if (!cover.exists() || cover.length() <= 0) {
                android.util.Log.d("MpkgWallpaper", "scene cover not captured yet: ${cover.name}")
                return ""
            }
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(
                cover.readBytes(), android.util.Base64.NO_WRAP,
            )
        } catch (e: Throwable) {
            android.util.Log.w("MpkgWallpaper", "scene cover read failed", e)
            ""
        }
    }

    /** 判断 mpkg 是否为视频型（内有 mp4/webm 等条目）。 */
    private fun isVideoMpkg(w: WallpaperEntity): Boolean {
        return try {
            if (w.wallpaperType != WallpaperEntity.TYPE_MPKG_WEBGL) return false
            val f = java.io.File(w.getMpkgPrivatePath(context))
            if (!f.exists()) return false
            com.mineradio.android.wallpaper.MpkgParser(f).listEntries().any { e ->
                val n = e.name.lowercase()
                n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mkv") ||
                    n.endsWith(".mov") || n.endsWith(".avi") || n.endsWith(".m4v")
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 从视频型壁纸抽一帧作封面。
     *
     * 为什么不用 SceneLib：getWallpaperInfoPreviewBitmap 是场景库 API，
     * 对视频型 mpkg 返回空 —— 背景能播但封面空白就是这个原因。
     * 这里提取内嵌视频后用 MediaMetadataRetriever 取帧，思路对应原版
     * captureSceneCover（第 8 帧截图），适配视频路径。
     */
    private fun extractVideoFrameDataUrl(w: WallpaperEntity): String {
        return try {
            // 1. 拿视频文件：视频型 mpkg 先提取内嵌视频；普通视频直接用原文件
            val videoFile: java.io.File? = if (w.wallpaperType == WallpaperEntity.TYPE_MPKG_WEBGL) {
                val src = java.io.File(w.getMpkgPrivatePath(context))
                if (src.exists()) com.mineradio.android.wallpaper.MpkgParser.extractVideoFile(src.absolutePath, context.filesDir) else null
            } else {
                val f = java.io.File(w.getRealPath(context))
                if (f.exists()) f else null
            }
            if (videoFile == null || !videoFile.exists()) {
                android.util.Log.w("MpkgWallpaper", "extractVideoFrame: no video file")
                return ""
            }
            android.util.Log.d("MpkgWallpaper", "extractVideoFrame: ${videoFile.absolutePath} ${videoFile.length()}B")

            // 2. MediaMetadataRetriever 取第 1 秒的帧（避开开头可能存在的黑帧）
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoFile.absolutePath)
                val frame = retriever.getFrameAtTime(
                    1_000_000L,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: retriever.frameAtTime
                if (frame == null) {
                    android.util.Log.w("MpkgWallpaper", "extractVideoFrame: no frame")
                    return ""
                }
                // 缩放到最大 480 宽
                val maxW = 480
                val scale = if (frame.width > maxW) maxW.toFloat() / frame.width else 1f
                val outW = (frame.width * scale).toInt().coerceAtLeast(1)
                val outH = (frame.height * scale).toInt().coerceAtLeast(1)
                val scaled = android.graphics.Bitmap.createScaledBitmap(frame, outW, outH, true)
                if (scaled !== frame) frame.recycle()
                val baos = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, baos)
                scaled.recycle()
                android.util.Log.d("MpkgWallpaper", "extractVideoFrame ok: ${outW}x$outH ${baos.size()}B")
                "data:image/jpeg;base64," + android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            } finally {
                try { retriever.release() } catch (e: Throwable) { }
            }
        } catch (e: Throwable) {
            android.util.Log.e("MpkgWallpaper", "extractVideoFrame failed", e)
            ""
        }
    }

    /**
     * 设为「应用内背景」（区别于系统壁纸）：
     * 写 WallpaperManager.setAppBackgroundWallpaper + 拷 mpkg 到私有目录。
     * 前端背景层（wallpaper-engine-layer）下次启动时从这里恢复。
     */
    @PluginMethod
    fun setAppBackground(call: PluginCall) {
        val nativeId = call.getString("nativeId") ?: ""
        if (nativeId.isEmpty()) { call.reject("nativeId required"); return }
        try {
            val wm = com.mineradio.android.wallpaper.WallpaperManager.get(context)
            val w = wm.getWallpaperList().firstOrNull { it.id == nativeId }
            if (w == null) { call.reject("wallpaper not found"); return }
            wm.setAppBackgroundWallpaper(w)
            // MPKG：保证私有副本存在（native 层场景初始化要读）
            if (w.wallpaperType == WallpaperEntity.TYPE_MPKG_WEBGL) {
                val privatePath = w.getMpkgPrivatePath(context)
                val pf = java.io.File(privatePath)
                if (!pf.exists()) {
                    val src = java.io.File(w.getRealPath(context))
                    if (src.exists()) {
                        java.io.File(pf.parent).mkdirs()
                        src.copyTo(pf, overwrite = true)
                    }
                }
                // ★ 启动程序内背景渲染（GLSurfaceView 垫在 WebView 下）
                com.mineradio.android.wallpaper.MpkgBackgroundManager.start(privatePath)
                android.util.Log.d("MpkgWallpaper", "setAppBackground: started GL layer with " + privatePath)
            }
            call.resolve()
        } catch (e: Exception) {
            call.reject("setAppBackground failed: " + e.message, e)
        }
    }

    /** 启动程序内背景渲染（用于重启后恢复）。 */
    @PluginMethod
    fun startBackground(call: PluginCall) {
        try {
            val wm = com.mineradio.android.wallpaper.WallpaperManager.get(context)
            val bg = wm.getAppBackgroundWallpaper()
            if (bg == null) { call.reject("没有应用内背景"); return }
            val path = if (bg.wallpaperType == WallpaperEntity.TYPE_MPKG_WEBGL)
                bg.getMpkgPrivatePath(context) else bg.getRealPath(context)
            com.mineradio.android.wallpaper.MpkgBackgroundManager.start(path)
            call.resolve()
        } catch (e: Exception) {
            call.reject("startBackground failed: " + e.message, e)
        }
    }

    /** 停止程序内背景渲染。 */
    @PluginMethod
    fun stopBackground(call: PluginCall) {
        com.mineradio.android.wallpaper.MpkgBackgroundManager.stop()
        call.resolve()
    }

    /** 读取应用内背景（重启恢复用）。 */
    @PluginMethod
    fun getAppBackground(call: PluginCall) {
        try {
            val wm = com.mineradio.android.wallpaper.WallpaperManager.get(context)
            val bg = wm.getAppBackgroundWallpaper()
            val out = JSObject()
            out.put("wallpaper", bg?.let { entityToJson(it) })
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("getAppBackground failed: " + e.message, e)
        }
    }

    /** 场景参数：是否正在运行场景壁纸（前端据此显示可调参数）。 */
    @PluginMethod
    fun isSceneActive(call: PluginCall) {
        val out = JSObject()
        out.put("active", com.mineradio.android.wallpaper.MpkgBackgroundManager.isSceneActive())
        call.resolve(out)
    }

    /** 场景参数：读取当前场景的可调参数（JSON 数组字符串）。 */
    @PluginMethod
    fun getSceneProperties(call: PluginCall) {
        try {
            val out = JSObject()
            out.put("json", com.mineradio.android.wallpaper.MpkgBackgroundManager.getScenePropertiesArrayJson())
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("getSceneProperties failed: " + e.message, e)
        }
    }

    /** 场景参数：实时应用单个参数（滑块 / 开关 / 选项 / 颜色）。 */
    @PluginMethod
    fun applySceneProperty(call: PluginCall) {
        val key = call.getString("key") ?: ""
        val value = call.getString("value") ?: ""
        if (key.isEmpty()) { call.reject("key required"); return }
        try {
            com.mineradio.android.wallpaper.MpkgBackgroundManager.applySceneProperty(key, value)
            call.resolve()
        } catch (e: Exception) {
            call.reject("applySceneProperty failed: " + e.message, e)
        }
    }

    /** 场景参数：重置为默认值，返回新的参数数组。 */
    @PluginMethod
    fun resetSceneProperties(call: PluginCall) {
        try {
            val out = JSObject()
            out.put("json", com.mineradio.android.wallpaper.MpkgBackgroundManager.resetSceneProperties())
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("resetSceneProperties failed: " + e.message, e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  在线壁纸（对接 OnlineWallpaperApi，照参考工程
    //  LandscapeWebActivity 的 @JavascriptInterface 一段移植）
    // ═══════════════════════════════════════════════════════════

    /** 在线壁纸列表：{list:[...], page, hasMore, totalPages, total} */
    @PluginMethod
    fun onlineWpGetList(call: PluginCall) {
        val page = call.getInt("page", 1) ?: 1
        val keyword = call.getString("keyword") ?: ""
        val source = call.getString("source") ?: "moewalls"
        val category = call.getString("category") ?: ""
        val resolution = call.getString("resolution") ?: ""
        wallpaperIo.execute {
            try {
                val data = com.mineradio.android.wallpaper.OnlineWallpaperApi(context).getList(
                    page,
                    if (keyword.isBlank()) null else keyword,
                    source,
                    if (category.isBlank()) null else category,
                    if (resolution.isBlank()) null else resolution,
                )
                call.resolve(JSObject.fromJSONObject(data))
            } catch (e: Exception) {
                android.util.Log.e("MpkgWallpaper", "onlineWpGetList failed", e)
                call.reject("onlineWpGetList failed: " + e.message, e)
            }
        }
    }

    /** 启动在线壁纸下载（后台线程执行，进度用 onlineWpGetProgress 轮询）。 */
    @PluginMethod
    fun onlineWpDownload(call: PluginCall) {
        val detailUrl = call.getString("detailUrl") ?: ""
        val name = call.getString("name") ?: ""
        val type = call.getInt("type", 4) ?: 4
        val source = call.getString("source") ?: "moewalls"
        val fileId = call.getString("fileId") ?: ""
        if (detailUrl.isEmpty()) { call.reject("detailUrl required"); return }
        wallpaperIo.execute {
            try {
                com.mineradio.android.wallpaper.OnlineWallpaperApi(context)
                    .downloadAndImport(detailUrl, name, type, source, fileId, null)
            } catch (e: Exception) {
                android.util.Log.e("MpkgWallpaper", "onlineWpDownload failed", e)
                com.mineradio.android.wallpaper.OnlineWallpaperApi.currentStatus = "下载失败：" + e.message
            }
        }
        val out = JSObject()
        out.put("started", true)
        call.resolve(out)
    }

    /** 当前下载进度 {progress:0-100, status} */
    @PluginMethod
    fun onlineWpGetProgress(call: PluginCall) {
        val out = JSObject()
        out.put("progress", com.mineradio.android.wallpaper.OnlineWallpaperApi.currentProgress)
        out.put("status", com.mineradio.android.wallpaper.OnlineWallpaperApi.currentStatus)
        call.resolve(out)
    }

    // ═══════════════════════════════════════════════════════════
    //  本地壁纸库（列表 / 预览 / 设背景 / 设系统壁纸 / 删除）
    // ═══════════════════════════════════════════════════════════

    /**
     * 壁纸库列表。
     * category: all / video / image / phone / local
     * 视频抽首帧、图片缩放后都转 data URL，前端 <img>/<video> 直接可显示。
     */
    @PluginMethod
    fun wpLibraryList(call: PluginCall) {
        try {
            val category = call.getString("category") ?: "all"
            val wm = WallpaperManager.get(context)
            // ★ 视频抽帧 / 图片缩略图比较重，统一在 IO 线程做完再 resolve
            wallpaperIo.execute {
                try {
                    val arr = com.getcapacitor.JSArray()
                    wm.getWallpaperList().forEach { w ->
                        val realPath = w.getRealPath(context)
                        if (!realPath.startsWith("http")) {
                            val f = java.io.File(realPath)
                            // MPKG 可能只保留了 files/mpkg 私有副本，也要算存在
                            val mpkgPrivateExists = w.wallpaperType == WallpaperEntity.TYPE_MPKG_WEBGL &&
                                java.io.File(w.getMpkgPrivatePath(context)).exists()
                            if (!f.exists() && !mpkgPrivateExists) return@forEach
                        }
                        val dims = detectWallpaperSize(w, realPath)
                        val isPhone = dims.second > dims.first && dims.first > 0
                        val isOnline = ONLINE_ID_PREFIXES.any { w.id.startsWith(it) }
                        val match = when (category) {
                            "video" -> w.wallpaperType == WallpaperEntity.TYPE_VIDEO
                            "image" -> w.wallpaperType == WallpaperEntity.TYPE_IMAGE
                            "phone" -> isPhone
                            "local" -> !isOnline
                            else -> true
                        }
                        if (!match) return@forEach
                        val item = JSObject()
                        item.put("id", w.id)
                        item.put("name", w.name)
                        item.put("type", w.wallpaperType)
                        item.put("width", dims.first)
                        item.put("height", dims.second)
                        item.put("author", w.author)
                        item.put("isPhone", isPhone)
                        item.put("isOnline", isOnline)
                        item.put("path", realPath)
                        item.put("url", contentUriForFile(realPath))
                        when (w.wallpaperType) {
                            WallpaperEntity.TYPE_VIDEO -> item.put("thumbnail", cachedThumb("v|${w.id}|$realPath") { videoThumbDataUrl(w.id, realPath) })
                            WallpaperEntity.TYPE_IMAGE -> item.put("thumbnail", cachedThumb("i|$realPath|${fileStamp(realPath)}") { imageThumbDataUrl(realPath) })
                            WallpaperEntity.TYPE_MPKG_WEBGL -> item.put("thumbnail", cachedThumb("m|${w.id}|${fileStamp(w.getMpkgPrivatePath(context))}") { sceneOrMpkgPreview(w) })
                        }
                        arr.put(item)
                    }
                    val out = JSObject()
                    out.put("list", arr)
                    out.put("total", arr.length())
                    call.resolve(out)
                } catch (e: Exception) {
                    android.util.Log.e("MpkgWallpaper", "wpLibraryList failed", e)
                    val out = JSObject()
                    out.put("list", com.getcapacitor.JSArray())
                    out.put("total", 0)
                    out.put("error", e.message ?: "unknown")
                    call.resolve(out)
                }
            }
        } catch (e: Exception) {
            call.reject("wpLibraryList failed: " + e.message, e)
        }
    }

    /** 壁纸预览：{ok,url,path,type,name,id,thumbnail} */
    @PluginMethod
    fun wpLibraryPreview(call: PluginCall) {
        val id = call.getString("id") ?: ""
        if (id.isEmpty()) { call.reject("id required"); return }
        try {
            val w = WallpaperManager.get(context).getWallpaperList().firstOrNull { it.id == id }
            if (w == null) {
                val out = JSObject()
                out.put("ok", false)
                out.put("error", "not found")
                call.resolve(out)
                return
            }
            wallpaperIo.execute {
                try {
                    val realPath = w.getRealPath(context)
                    val out = JSObject()
                    out.put("ok", true)
                    out.put("id", w.id)
                    out.put("name", w.name)
                    out.put("type", w.wallpaperType)
                    out.put("path", realPath)
                    out.put("url", contentUriForFile(realPath))
                    when (w.wallpaperType) {
                        WallpaperEntity.TYPE_VIDEO -> out.put("thumbnail", cachedThumb("v|${w.id}|$realPath") { videoThumbDataUrl(w.id, realPath) })
                        WallpaperEntity.TYPE_IMAGE -> out.put("thumbnail", cachedThumb("i|$realPath|${fileStamp(realPath)}") { imageThumbDataUrl(realPath) })
                        WallpaperEntity.TYPE_MPKG_WEBGL -> out.put("thumbnail", cachedThumb("m|${w.id}|${fileStamp(w.getMpkgPrivatePath(context))}") { sceneOrMpkgPreview(w) })
                    }
                    call.resolve(out)
                } catch (e: Exception) {
                    call.reject("wpLibraryPreview failed: " + e.message, e)
                }
            }
        } catch (e: Exception) {
            call.reject("wpLibraryPreview failed: " + e.message, e)
        }
    }

    /** 设为应用内背景：{ok,name,path,type,uri} */
    @PluginMethod
    fun wpLibrarySetAppBg(call: PluginCall) {
        val id = call.getString("id") ?: ""
        if (id.isEmpty()) { call.reject("id required"); return }
        try {
            val wm = WallpaperManager.get(context)
            val w = wm.getWallpaperList().firstOrNull { it.id == id }
            if (w == null) {
                val out = JSObject()
                out.put("ok", false)
                out.put("error", "not found")
                call.resolve(out)
                return
            }
            wm.setAppBackgroundWallpaper(w)
            val realPath = w.getRealPath(context)
            if (w.wallpaperType == WallpaperEntity.TYPE_MPKG_WEBGL) {
                // 保证私有副本存在，再启动 GL 背景层
                val pf = java.io.File(w.getMpkgPrivatePath(context))
                if (!pf.exists()) {
                    val src = java.io.File(realPath)
                    if (src.exists()) {
                        pf.parentFile?.mkdirs()
                        src.copyTo(pf, overwrite = true)
                    }
                }
                com.mineradio.android.wallpaper.MpkgBackgroundManager.start(pf.absolutePath)
            } else {
                com.mineradio.android.wallpaper.MpkgBackgroundManager.stop()
            }
            val out = JSObject()
            out.put("ok", true)
            out.put("name", w.name)
            out.put("path", realPath)
            out.put("type", w.wallpaperType)
            out.put("uri", contentUriForFile(realPath))
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("wpLibrarySetAppBg failed: " + e.message, e)
        }
    }

    /** 【修复】清除原生登记的应用内背景 + 停 GL 层。
     *  之前前端「清除背景」只清前端变量,从不清登记、不停 MpkgBackgroundManager,
     *  导致壁纸一直在渲染、管理界面也显示不出「使用中」状态。 */
    @PluginMethod
    fun clearAppBackground(call: PluginCall) {
        try {
            com.mineradio.android.wallpaper.MpkgBackgroundManager.stop()
            com.mineradio.android.wallpaper.WallpaperManager.get(context).setAppBackgroundWallpaper(null)
            call.resolve()
        } catch (e: Exception) {
            call.reject("clearAppBackground failed: " + e.message, e)
        }
    }

    /** 设为系统壁纸：{ok,result:'direct'|'NEED_ACTIVATE'|'failed'} */
    @PluginMethod
    fun wpLibrarySetSystem(call: PluginCall) {
        val id = call.getString("id") ?: ""
        if (id.isEmpty()) { call.reject("id required"); return }
        try {
            val wm = WallpaperManager.get(context)
            val w = wm.getWallpaperList().firstOrNull { it.id == id }
            if (w == null) {
                val out = JSObject()
                out.put("ok", false)
                out.put("error", "not found")
                call.resolve(out)
                return
            }
            val result = com.mineradio.android.wallpaper.WallpaperHelper.applyAsSystemWallpaper(context, w)
            val out = JSObject()
            out.put("ok", result != com.mineradio.android.wallpaper.WallpaperHelper.ApplyResult.Failed)
            out.put(
                "result",
                when (result) {
                    com.mineradio.android.wallpaper.WallpaperHelper.ApplyResult.SuccessDirect -> "direct"
                    com.mineradio.android.wallpaper.WallpaperHelper.ApplyResult.NeedSystemActivate -> "NEED_ACTIVATE"
                    else -> "failed"
                },
            )
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("wpLibrarySetSystem failed: " + e.message, e)
        }
    }

    /** 删除壁纸库中的壁纸。 */
    @PluginMethod
    fun wpLibraryDelete(call: PluginCall) {
        val id = call.getString("id") ?: ""
        if (id.isEmpty()) { call.reject("id required"); return }
        try {
            WallpaperManager.get(context).removeWallpaper(id)
            val out = JSObject()
            out.put("ok", true)
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("wpLibraryDelete failed: " + e.message, e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  MPKG 导出为系统壁纸（分块写临时文件，避免一次性 Base64 OOM）
    // ═══════════════════════════════════════════════════════════

    @PluginMethod
    fun wpWriteTempFileStart(call: PluginCall) {
        val fileName = sanitizeFileName(call.getString("fileName") ?: "wallpaper.mpkg")
        try {
            val temp = java.io.File(context.cacheDir, "mpkg_temp_${System.currentTimeMillis()}_$fileName")
            if (temp.exists()) temp.delete()
            temp.createNewFile()
            val out = JSObject()
            out.put("ok", true)
            out.put("path", temp.absolutePath)
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("wpWriteTempFileStart failed: " + e.message, e)
        }
    }

    @PluginMethod
    fun wpWriteTempFileChunk(call: PluginCall) {
        val tempPath = call.getString("path") ?: ""
        val base64Chunk = call.getString("base64Chunk") ?: ""
        if (tempPath.isEmpty()) { call.reject("path required"); return }
        try {
            val content = if (base64Chunk.contains(",")) base64Chunk.substring(base64Chunk.indexOf(",") + 1) else base64Chunk
            val bytes = android.util.Base64.decode(content, android.util.Base64.DEFAULT)
            java.io.FileOutputStream(java.io.File(tempPath), true).use { it.write(bytes) }
            val out = JSObject()
            out.put("ok", true)
            out.put("written", bytes.size)
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("wpWriteTempFileChunk failed: " + e.message, e)
        }
    }

    @PluginMethod
    fun wpWriteTempFileFinish(call: PluginCall) {
        val tempPath = call.getString("path") ?: ""
        try {
            val f = java.io.File(tempPath)
            val out = JSObject()
            if (!f.exists()) {
                out.put("ok", false)
                out.put("error", "临时文件不存在")
            } else {
                out.put("ok", true)
                out.put("path", f.absolutePath)
                out.put("size", f.length())
            }
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("wpWriteTempFileFinish failed: " + e.message, e)
        }
    }

    /** MPKG 从临时文件注册并设为系统壁纸。 */
    @PluginMethod
    fun wpLibrarySetSystemMpkgFromFile(call: PluginCall) {
        val mpkgId = call.getString("mpkgId") ?: ""
        val name = call.getString("name") ?: "Wallpaper Engine"
        val fileName = sanitizeFileName(call.getString("fileName") ?: "$mpkgId.mpkg")
        val tempPath = call.getString("tempPath") ?: ""
        if (mpkgId.isEmpty() || tempPath.isEmpty()) { call.reject("mpkgId/tempPath required"); return }
        try {
            val src = java.io.File(tempPath)
            if (!src.exists()) {
                val out = JSObject()
                out.put("ok", false)
                out.put("error", "临时文件不存在: $tempPath")
                call.resolve(out)
                return
            }
            val safeName = if (fileName.lowercase().endsWith(".mpkg")) fileName else "$fileName.mpkg"
            val wallpaperId = "wp_usermpkg_$mpkgId"
            val wm = WallpaperManager.get(context)
            var wallpaper = wm.getWallpaperList().firstOrNull { it.id == wallpaperId }
            if (wallpaper == null) {
                wallpaper = WallpaperEntity(
                    author = "Wallpaper Engine",
                    description = name,
                    id = wallpaperId,
                    name = name,
                    path = safeName,
                    versionCode = 1,
                    versionName = "1.0",
                    wallpaperType = WallpaperEntity.TYPE_MPKG_WEBGL,
                )
                // 私有副本（native 引擎只读 files/mpkg）
                val privateFile = java.io.File(wallpaper.getMpkgPrivatePath(context))
                privateFile.parentFile?.mkdirs()
                src.copyTo(privateFile, overwrite = true)
                // 外部副本（getRealPath 用，保证 fileExists）
                val realFile = java.io.File(wallpaper.getRealPath(context))
                realFile.parentFile?.mkdirs()
                src.copyTo(realFile, overwrite = true)
                wm.addWallpaper(wallpaper)
            }
            val result = com.mineradio.android.wallpaper.WallpaperHelper.applyAsSystemWallpaper(context, wallpaper)
            val out = JSObject()
            out.put("ok", result != com.mineradio.android.wallpaper.WallpaperHelper.ApplyResult.Failed)
            out.put(
                "result",
                when (result) {
                    com.mineradio.android.wallpaper.WallpaperHelper.ApplyResult.SuccessDirect -> "direct"
                    com.mineradio.android.wallpaper.WallpaperHelper.ApplyResult.NeedSystemActivate -> "NEED_ACTIVATE"
                    else -> "failed"
                },
            )
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("wpLibrarySetSystemMpkgFromFile failed: " + e.message, e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  系统壁纸相关小方法
    // ═══════════════════════════════════════════════════════════

    @PluginMethod
    fun wallpaperClearSystem(call: PluginCall) {
        try {
            com.mineradio.android.wallpaper.WallpaperHelper.clearSystemWallpaper(context)
            call.resolve()
        } catch (e: Exception) {
            call.reject("wallpaperClearSystem failed: " + e.message, e)
        }
    }

    @PluginMethod
    fun setForceSystemWallpaper(call: PluginCall) {
        val value = call.getString("value") ?: "0"
        try {
            WallpaperManager.get(context).setForceSystemWallpaper(value == "1" || value.equals("true", true))
            call.resolve()
        } catch (e: Exception) {
            call.reject("setForceSystemWallpaper failed: " + e.message, e)
        }
    }

    @PluginMethod
    fun getForceSystemWallpaper(call: PluginCall) {
        val out = JSObject()
        out.put("value", if (WallpaperManager.get(context).getForceSystemWallpaper()) "1" else "0")
        call.resolve(out)
    }

    @PluginMethod
    fun gotoSystemWallpaperSettings(call: PluginCall) {
        try {
            com.mineradio.android.wallpaper.WallpaperHelper.gotoSystemWallpaperSettings(context)
            call.resolve()
        } catch (e: Exception) {
            call.reject("gotoSystemWallpaperSettings failed: " + e.message, e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  私有辅助
    // ═══════════════════════════════════════════════════════════

    private val wallpaperIo = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 缩略图内存缓存（key 带 id / 路径 / 文件大小）。
     *
     * 之前每次打开壁纸库、每次切分类都会把整库缩略图重新算一遍：
     * 场景包要解 MPKG 里的 preview.gif 并重新编码 JPEG，图片要重新解码缩放，
     * 列表一多就表现为「每次都要重新加载所有壁纸」。这里进程内命中直接返回。
     */
    private val thumbMemoryCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun cachedThumb(key: String, compute: () -> String): String {
        val hit = thumbMemoryCache[key]
        if (hit != null) return hit
        val value = try { compute() } catch (e: Throwable) {
            android.util.Log.w("MpkgWallpaper", "thumb compute failed", e)
            ""
        }
        if (value.isNotEmpty()) {
            if (thumbMemoryCache.size > 48) thumbMemoryCache.clear()
            thumbMemoryCache[key] = value
        }
        return value
    }

    private fun fileStamp(path: String): String {
        val f = java.io.File(path)
        return f.length().toString() + "_" + f.lastModified().toString()
    }

    private fun sanitizeFileName(raw: String): String {
        var n = raw.split("?")[0].split("#")[0].replace("/", "").replace("\\", "")
        n = n.replace(Regex("[^a-zA-Z0-9._-]"), "")
        return n.ifBlank { "wallpaper.mpkg" }
    }

    /** file:// → content://（FileProvider 已声明，file_paths 覆盖 external/cache/files）。 */
    private fun contentUriForFile(path: String): String {
        if (path.startsWith("http")) return path
        return try {
            val f = java.io.File(path)
            if (!f.exists()) return "file://$path"
            androidx.core.content.FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                f,
            ).toString()
        } catch (e: Exception) {
            "file://$path"
        }
    }

    /** 宽高：图片/视频能读到的都读，读不到的返回 0。 */
    private fun detectWallpaperSize(w: WallpaperEntity, realPath: String): Pair<Int, Int> {
        return try {
            if (realPath.startsWith("http")) return Pair(0, 0)
            val f = java.io.File(realPath)
            if (!f.exists()) return Pair(0, 0)
            when (w.wallpaperType) {
                WallpaperEntity.TYPE_IMAGE -> {
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(realPath, opts)
                    Pair(opts.outWidth, opts.outHeight)
                }
                WallpaperEntity.TYPE_VIDEO -> {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(realPath)
                        val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        if (rotation == 90 || rotation == 270) Pair(height, width) else Pair(width, height)
                    } finally {
                        try { retriever.release() } catch (ignored: Throwable) { }
                    }
                }
                else -> Pair(0, 0)
            }
        } catch (e: Throwable) {
            Pair(0, 0)
        }
    }

    /** 视频首帧缩略图（缓存到 cache/wp_thumbs/<id>.jpg）。 */
    private fun videoThumbDataUrl(id: String, realPath: String): String {
        return try {
            if (realPath.startsWith("http")) return ""
            val thumbDir = java.io.File(context.cacheDir, "wp_thumbs").apply { mkdirs() }
            val thumbFile = java.io.File(thumbDir, "$id.jpg")
            if (!thumbFile.exists() || thumbFile.length() <= 0) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(realPath)
                    val bitmap = retriever.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                    if (bitmap != null) {
                        java.io.FileOutputStream(thumbFile).use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        bitmap.recycle()
                    }
                } finally {
                    try { retriever.release() } catch (ignored: Throwable) { }
                }
            }
            if (!thumbFile.exists()) return ""
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(thumbFile.readBytes(), android.util.Base64.NO_WRAP)
        } catch (e: Throwable) {
            android.util.Log.w("MpkgWallpaper", "videoThumb failed", e)
            ""
        }
    }

    /** 图片缩略图（≤480 宽 JPEG data URL）。 */
    private fun imageThumbDataUrl(realPath: String): String {
        return try {
            if (realPath.startsWith("http")) return ""
            val f = java.io.File(realPath)
            if (!f.exists()) return ""
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(realPath, opts)
            val sample = Math.max(1, Math.max(opts.outWidth, opts.outHeight) / 480)
            val opts2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeFile(realPath, opts2) ?: return ""
            val scaled = if (bmp.width > 480) {
                val h = (bmp.height.toFloat() * 480 / bmp.width).toInt().coerceAtLeast(1)
                android.graphics.Bitmap.createScaledBitmap(bmp, 480, h, true).also { if (it !== bmp) bmp.recycle() }
            } else bmp
            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
            scaled.recycle()
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Throwable) {
            android.util.Log.w("MpkgWallpaper", "imageThumb failed", e)
            ""
        }
    }

    /** MPKG 封面（包内 preview 图优先，其次渲染线程截的第 8 帧）。 */
    private fun sceneOrMpkgPreview(w: WallpaperEntity): String {
        val builtin = extractMpkgBuiltinPreview(w)
        return if (builtin.isNotEmpty()) builtin else extractSceneCoverDataUrl(w)
    }

    companion object {
        private val ONLINE_ID_PREFIXES = listOf("wp_online_", "wp_hao_", "wp_bizhihui_", "wp_usermpkg_")
    }
}
