package com.mineradio.app.wallpaper

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 壁纸管理工具类
 * 负责壁纸的增删改查、持久化和当前壁纸切换
 * 移植自 project_lw 的 WallpaperUtils + MyApplication 偏好逻辑
 *
 * ★ 重要：使用文件存储（JSON）替代 SharedPreferences
 *   原因：LWService 运行在 :wallpaper 独立进程，与主进程不共享 SharedPreferences
 *   文件存储可跨进程共享（内部存储同一包名下文件各进程均可见可读写）
 *
 * ★★★ 进一步优化：配置文件和壁纸资源均存放到外部公共目录
 *   路径：/sdcard/Documents/Mineradio/Wallpaper/
 *   原因：用户在系统设置中"清除数据"会清空 /data/data/<package>/ 整个目录
 *         包括 wallpaper_config.json 和壁纸资源文件
 *         移到外部公共目录后，清理应用数据不影响壁纸配置
 *         BootReceiver 仍能读取配置并重新激活壁纸
 *   需要 MANAGE_EXTERNAL_STORAGE 权限（Android 11+）
 */
class WallpaperManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "WallpaperManager"
        private const val PREF_NAME = "mineradio_wallpaper"
        private const val KEY_CURRENT_WALLPAPER = "current_wallpaper"
        private const val KEY_WALLPAPER_LIST = "wallpaper_list"
        private const val KEY_SLIDE_WALLPAPER = "slide_wallpaper"
        private const val KEY_APP_BG_WALLPAPER = "app_bg_wallpaper"

        /** ★★★ 强制系统壁纸开关 key（多层防护：同时存 SharedPreferences 和外部存储配置文件）
         *   存外部存储配置文件的原因：用户在系统设置中"清除数据"会清空 SharedPreferences，
         *   导致 BootReceiver/KeepAlive 读不到开关而跳过自动激活，壁纸无法恢复。
         *   外部存储配置文件不受"清除数据"影响，能保证开关持久化。 */
        private const val KEY_FORCE_SYSTEM_WALLPAPER = "force_system_wallpaper"

        /** ★★★ 桌面萌宠开关 key（与 force_system_wallpaper 同样的多层防护策略）
         *   启用后 LWService 返回 PetWallpaperEngine，在桌面绘制萌宠角色。
         *   复用壁纸的所有保活机制（10层防护）和自启动机制（BootReceiver）。 */
        private const val KEY_DESKTOP_PET_ENABLED = "desktop_pet_enabled"

        /** 文件存储文件名（位于外部公共目录） */
        private const val CONFIG_FILE_NAME = "wallpaper_config.json"

        /** 外部存储根目录名 */
        private const val EXTERNAL_DIR_NAME = "Mineradio/Wallpaper"

        /** ★ 内部存储标记文件名（位于 context.filesDir，"清理数据"会清空此目录） */
        private const val APP_BG_MARKER_NAME = "app_bg_active_marker"

        /** SharedPreferences 名（与 LandscapeWebActivity JS 桥接保持一致） */
        private const val FORCE_PREF_NAME = "mineradio_wallpaper_prefs"

        @Volatile
        private var instance: WallpaperManager? = null

        fun get(context: Context): WallpaperManager =
            instance ?: synchronized(this) {
                instance ?: WallpaperManager(context.applicationContext).also { instance = it }
            }
    }

    /** 跨进程读写锁（仅本实例，进程内有效；跨进程由文件原子写保证） */
    private val ioLock = ReentrantLock()

    /**
     * 外部公共存储目录：/sdcard/Documents/Mineradio/Wallpaper/
     * 清理应用数据不会删除此目录
     */
    private val externalStorageDir: File by lazy {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        File(docsDir, EXTERNAL_DIR_NAME).apply {
            try {
                if (!exists()) {
                    mkdirs()
                }
            } catch (e: Exception) {
                Log.e(TAG, "create external storage dir failed", e)
            }
        }
    }

    /**
     * 配置文件路径：/sdcard/Documents/Mineradio/Wallpaper/wallpaper_config.json
     * 清理应用数据不会删除此文件
     */
    private val configFile: File by lazy { File(externalStorageDir, CONFIG_FILE_NAME) }

    /**
     * ★★★ 内部存储标记文件（位于 context.filesDir，"清理数据"会清空此目录）
     *
     * 用于区分"清理数据"和"正常重启/应用内清理按钮"两种场景：
     * - setAppBackgroundWallpaper(非 null) 时创建/刷新此标记文件
     * - init 时检查此文件：
     *   - 若标记缺失（说明刚"清理数据"或全新安装），从外部配置中删除 KEY_APP_BG_WALLPAPER，
     *     避免清理数据后应用背景仍被恢复
     *   - 若标记存在（说明正常重启），保留外部配置不动
     * - wallpaperClearAppBg() 调用 setAppBackgroundWallpaper(null)，不删此标记
     *   （让"清理按钮"不清标记，仅"清理数据"才清）
     * - 注意：KEY_CURRENT_WALLPAPER（系统桌面壁纸）不在此机制内，保留 BootReceiver 恢复能力
     */
    private val appBgMarkerFile: File by lazy { File(context.filesDir, APP_BG_MARKER_NAME) }

    init {
        cleanupAppBgIfMarkerMissing()
    }

    /**
     * 获取壁纸资源目录（按壁纸 ID 分目录存放）
     * 路径：/sdcard/Documents/Mineradio/Wallpaper/<wallpaperId>/
     */
    private fun wallpaperResourceDir(wallpaperId: String): File =
        File(externalStorageDir, wallpaperId).apply {
            try {
                if (!exists()) mkdirs()
            } catch (e: Exception) {
                Log.e(TAG, "create wallpaper resource dir failed: $wallpaperId", e)
            }
        }

    /** 旧版 SharedPreferences（仅用于一次性迁移） */
    private val legacyPrefs by lazy {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 读取整个配置 JSON
     * 若文件不存在，尝试从旧版 SharedPreferences 迁移
     */
    private fun readConfig(): JSONObject =
        ioLock.withLock {
            try {
                if (configFile.exists()) {
                    FileInputStream(configFile).use { fis ->
                        val bytes = fis.readBytes()
                        if (bytes.isNotEmpty()) {
                            return@withLock JSONObject(String(bytes, Charsets.UTF_8))
                        }
                    }
                }
                // 文件不存在，尝试从旧版 SharedPreferences 迁移
                migrateFromLegacyPrefs()
            } catch (e: Exception) {
                Log.e(TAG, "readConfig failed, returning empty", e)
                try {
                    migrateFromLegacyPrefs()
                } catch (_: Exception) {
                    JSONObject()
                }
            }
        }

    /**
     * 从旧版 SharedPreferences 迁移数据到文件存储
     */
    private fun migrateFromLegacyPrefs(): JSONObject {
        val json = JSONObject()
        try {
            legacyPrefs?.let { prefs ->
                prefs.getString(KEY_CURRENT_WALLPAPER, null)?.let {
                    json.put(KEY_CURRENT_WALLPAPER, it)
                }
                prefs.getString(KEY_WALLPAPER_LIST, null)?.let {
                    json.put(KEY_WALLPAPER_LIST, it)
                }
                prefs.getString(KEY_APP_BG_WALLPAPER, null)?.let {
                    json.put(KEY_APP_BG_WALLPAPER, it)
                }
                if (prefs.contains(KEY_SLIDE_WALLPAPER)) {
                    json.put(KEY_SLIDE_WALLPAPER, prefs.getBoolean(KEY_SLIDE_WALLPAPER, false))
                }
                // 迁移完成后写入文件
                if (json.length() > 0) {
                    writeConfigLocked(json)
                    Log.d(TAG, "migrated from legacy SharedPreferences to file storage")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "migrateFromLegacyPrefs failed", e)
        }
        return json
    }

    /**
     * 写入整个配置 JSON（调用方需已持有 ioLock）
     */
    private fun writeConfigLocked(json: JSONObject) {
        try {
            configFile.parentFile?.mkdirs()
            // 原子写：先写临时文件，再重命名
            val tmpFile = File(configFile.parentFile, "$CONFIG_FILE_NAME.tmp")
            FileOutputStream(tmpFile).use { fos ->
                fos.write(json.toString().toByteArray(Charsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Exception) {
                }
            }
            // 重命名为正式文件（原子操作）
            if (configFile.exists()) {
                configFile.delete()
            }
            if (!tmpFile.renameTo(configFile)) {
                // 重命名失败，直接复制
                tmpFile.copyTo(configFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeConfigLocked failed", e)
        }
    }

    /**
     * 更新配置中的单个字段
     */
    private fun updateField(
        key: String,
        value: Any?,
    ) {
        ioLock.withLock {
            val json =
                try {
                    if (configFile.exists()) {
                        FileInputStream(configFile).use { fis ->
                            val bytes = fis.readBytes()
                            if (bytes.isNotEmpty()) JSONObject(String(bytes, Charsets.UTF_8)) else JSONObject()
                        }
                    } else {
                        JSONObject()
                    }
                } catch (e: Exception) {
                    JSONObject()
                }
            if (value == null) {
                json.remove(key)
            } else {
                json.put(key, value)
            }
            writeConfigLocked(json)
        }
    }

    /**
     * 将 WallpaperEntity 序列化为 JSON
     */
    private fun WallpaperEntity.toJson(): JSONObject =
        JSONObject().apply {
            put("author", author)
            put("description", description)
            put("id", id)
            put("name", name)
            put("path", path)
            put("versionCode", versionCode)
            put("versionName", versionName)
            put("wallpaperType", wallpaperType)
        }

    /**
     * 从 JSON 反序列化为 WallpaperEntity
     */
    private fun JSONObject.toWallpaperEntity(): WallpaperEntity =
        WallpaperEntity(
            author = optString("author", ""),
            description = optString("description", ""),
            id = optString("id", ""),
            name = optString("name", ""),
            path = optString("path", ""),
            versionCode = optInt("versionCode", 0),
            versionName = optString("versionName", ""),
            wallpaperType = optInt("wallpaperType", WallpaperEntity.TYPE_HTML),
        )

    /**
     * 获取当前系统桌面壁纸
     */
    fun getCurrentWallpaper(): WallpaperEntity? {
        val json = readConfig()
        val raw = if (json.has(KEY_CURRENT_WALLPAPER)) json.getString(KEY_CURRENT_WALLPAPER) else return null
        if (raw.isEmpty()) return null
        return try {
            JSONObject(raw).toWallpaperEntity()
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentWallpaper: ", e)
            null
        }
    }

    /**
     * 设置当前系统桌面壁纸
     */
    fun setCurrentWallpaper(wallpaper: WallpaperEntity?) {
        val value = wallpaper?.let { it.toJson().toString() }
        updateField(KEY_CURRENT_WALLPAPER, value)
    }

    /**
     * ★ 内部存储标记检查：在 init 时调用，区分"清理数据"和"正常重启"
     *
     * - 标记文件位于 context.filesDir，"清理数据"会清空此目录
     * - 若标记缺失：说明刚"清理数据"或全新安装 → 从外部配置中删除 KEY_APP_BG_WALLPAPER
     *   避免清理数据后应用背景仍被恢复（KEY_CURRENT_WALLPAPER 不删，保留系统壁纸恢复能力）
     * - 若标记存在：说明正常重启，保留外部配置不动
     */
    private fun cleanupAppBgIfMarkerMissing() {
        try {
            if (!appBgMarkerFile.exists()) {
                ioLock.withLock {
                    val json =
                        try {
                            if (configFile.exists()) {
                                FileInputStream(configFile).use { fis ->
                                    val bytes = fis.readBytes()
                                    if (bytes.isNotEmpty()) JSONObject(String(bytes, Charsets.UTF_8)) else JSONObject()
                                }
                            } else {
                                return@withLock // 外部配置也不存在，无需清理
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "cleanupAppBgIfMarkerMissing: read config failed", e)
                            return@withLock
                        }
                    if (json.has(KEY_APP_BG_WALLPAPER)) {
                        json.remove(KEY_APP_BG_WALLPAPER)
                        writeConfigLocked(json)
                        Log.d(TAG, "cleanupAppBgIfMarkerMissing: marker missing, removed KEY_APP_BG_WALLPAPER from external config")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "cleanupAppBgIfMarkerMissing failed", e)
        }
    }

    /**
     * 获取当前应用背景壁纸
     */
    fun getAppBackgroundWallpaper(): WallpaperEntity? {
        val json = readConfig()
        val raw = if (json.has(KEY_APP_BG_WALLPAPER)) json.getString(KEY_APP_BG_WALLPAPER) else return null
        if (raw.isEmpty()) return null
        return try {
            JSONObject(raw).toWallpaperEntity()
        } catch (e: Exception) {
            Log.e(TAG, "getAppBackgroundWallpaper: ", e)
            null
        }
    }

    /**
     * 设置当前应用背景壁纸
     *
     * ★ 内部存储标记机制：
     *   - 写入非 null 壁纸时同步创建/刷新标记文件（位于 context.filesDir，"清理数据"会清空）
     *   - 写入 null（被 wallpaperClearAppBg 调用）时不删标记，让"清理按钮"不清标记，
     *     仅"清理数据"才会清标记，触发 init 中的 cleanupAppBgIfMarkerMissing()
     */
    fun setAppBackgroundWallpaper(wallpaper: WallpaperEntity?) {
        val value = wallpaper?.let { it.toJson().toString() }
        updateField(KEY_APP_BG_WALLPAPER, value)
        if (wallpaper != null) {
            try {
                appBgMarkerFile.parentFile?.mkdirs()
                appBgMarkerFile.writeText(System.currentTimeMillis().toString())
            } catch (e: Exception) {
                Log.e(TAG, "write appBgMarkerFile failed", e)
            }
        }
    }

    /**
     * 是否允许壁纸滑动偏移
     */
    fun isSlideWallpaperEnabled(): Boolean = readConfig().optBoolean(KEY_SLIDE_WALLPAPER, false)

    fun setSlideWallpaperEnabled(enabled: Boolean) {
        updateField(KEY_SLIDE_WALLPAPER, enabled)
    }

    /**
     * 获取壁纸库列表
     */
    fun getWallpaperList(): List<WallpaperEntity> {
        val json = readConfig()
        val raw = if (json.has(KEY_WALLPAPER_LIST)) json.getString(KEY_WALLPAPER_LIST) else return emptyList()
        if (raw.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { idx ->
                array.getJSONObject(idx).toWallpaperEntity()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getWallpaperList: ", e)
            emptyList()
        }
    }

    /**
     * 保存壁纸库列表
     */
    fun saveWallpaperList(list: List<WallpaperEntity>) {
        val array = JSONArray()
        list.forEach { w -> array.put(w.toJson()) }
        updateField(KEY_WALLPAPER_LIST, array.toString())
    }

    /**
     * 添加壁纸到壁纸库
     */
    fun addWallpaper(wallpaper: WallpaperEntity) {
        ioLock.withLock {
            val list = getWallpaperList().toMutableList()
            // 去重：若 id 相同则替换
            list.removeAll { it.id == wallpaper.id }
            list.add(wallpaper)
            val array = JSONArray()
            list.forEach { w -> array.put(w.toJson()) }
            updateField(KEY_WALLPAPER_LIST, array.toString())
        }
    }

    /**
     * 从壁纸库移除壁纸
     */
    fun removeWallpaper(wallpaperId: String) {
        ioLock.withLock {
            val list = getWallpaperList().toMutableList()
            list.removeAll { it.id == wallpaperId }
            val array = JSONArray()
            list.forEach { w -> array.put(w.toJson()) }
            updateField(KEY_WALLPAPER_LIST, array.toString())
            // 同时删除本地文件（外部存储目录）
            val dir = wallpaperResourceDir(wallpaperId)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            // ★ 同时删除 app 私有目录中的 MPKG 副本
            try {
                val mpkgDir = File(context.filesDir, "mpkg")
                if (mpkgDir.exists()) {
                    mpkgDir.listFiles()?.forEach { f ->
                        if (f.name.startsWith("${wallpaperId}_")) {
                            f.delete()
                            Log.d(TAG, "deleted private MPKG: ${f.name}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "delete private MPKG failed", e)
            }
            // 若移除的正是当前壁纸，则清空当前壁纸
            if (getCurrentWallpaper()?.id == wallpaperId) {
                setCurrentWallpaper(null)
                // ★ 同步关闭强制系统壁纸开关，避免重启后 BootReceiver/KeepAlive 再次激活旧壁纸导致黑屏
                try {
                    setForceSystemWallpaper(false)
                } catch (e: Exception) {
                    Log.e(TAG, "removeWallpaper: setForceSystemWallpaper(false) failed", e)
                }
                // ★ 恢复系统默认壁纸，避免桌面停留在空壁纸引擎画面
                try {
                    android.app.WallpaperManager
                        .getInstance(context)
                        .clear()
                    Log.d(TAG, "removeWallpaper: system wallpaper cleared to default")
                } catch (e: Exception) {
                    Log.e(TAG, "removeWallpaper: clear system wallpaper failed", e)
                }
            }
            if (getAppBackgroundWallpaper()?.id == wallpaperId) {
                setAppBackgroundWallpaper(null)
            }
        }
    }

    /**
     * 根据文件扩展名推断壁纸类型
     * - .html/.htm → TYPE_HTML
     * - .mp4/.avi/.mov/.mkv/.webm/.3gp/.flv → TYPE_VIDEO
     * - .jpg/.jpeg/.png/.bmp/.webp/.gif → TYPE_IMAGE
     * - 其他 → TYPE_IMAGE（默认）
     */
    fun guessTypeFromFileName(fileName: String): Int {
        val lower = fileName.substringAfterLast('.', "").lowercase()
        return when (lower) {
            "html", "htm" -> WallpaperEntity.TYPE_HTML
            "mp4", "avi", "mov", "mkv", "webm", "3gp", "flv", "ts", "m4v" -> WallpaperEntity.TYPE_VIDEO
            "mpkg" -> WallpaperEntity.TYPE_MPKG_WEBGL
            else -> WallpaperEntity.TYPE_IMAGE
        }
    }

    /**
     * 内置 HTML 壁纸导入已删除（用户要求删除内置 HTML 壁纸）
     * 壁纸库现在只显示用户手动导入或在线下载的壁纸
     */

    /**
     * 从本地文件导入壁纸
     * @param sourcePath 源文件路径
     * @param name 壁纸名称
     * @param type 壁纸类型（HTML / VIDEO / IMAGE）
     * @return 创建的 WallpaperEntity
     */
    fun importFromFile(
        sourcePath: String,
        name: String,
        type: Int,
    ): WallpaperEntity? {
        return try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) return null

            val wallpaperId = "wp_" + System.currentTimeMillis()
            val targetDir = wallpaperResourceDir(wallpaperId)
            val targetFile = File(targetDir, sourceFile.name)
            sourceFile.copyTo(targetFile, overwrite = true)

            // ★ MPKG 类型：额外复制到 app 私有目录，native 库需要读取私有目录下的文件
            if (type == WallpaperEntity.TYPE_MPKG_WEBGL) {
                try {
                    val mpkgDir = File(context.filesDir, "mpkg")
                    if (!mpkgDir.exists()) mpkgDir.mkdirs()
                    val privateFile = File(mpkgDir, "${wallpaperId}_${sourceFile.name}")
                    sourceFile.copyTo(privateFile, overwrite = true)
                    Log.d(TAG, "MPKG copied to private dir: ${privateFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "copy MPKG to private dir failed", e)
                }
            }

            val wallpaper =
                WallpaperEntity(
                    author = "本地导入",
                    description = name,
                    id = wallpaperId,
                    name = name,
                    path = sourceFile.name,
                    versionCode = 1,
                    versionName = "1.0",
                    wallpaperType = type,
                )
            addWallpaper(wallpaper)
            wallpaper
        } catch (e: Exception) {
            Log.e(TAG, "importFromFile: ", e)
            null
        }
    }

    /**
     * 从内容 URI 导入壁纸（系统文件选择器返回的 Uri）
     * 自动根据文件扩展名推断壁纸类型
     * @param context 上下文（用于 ContentResolver）
     * @param uri 内容 Uri
     * @return 创建的 WallpaperEntity
     */
    fun importFromFile(
        context: Context,
        uri: android.net.Uri,
    ): WallpaperEntity? {
        return try {
            val wallpaperId = "wp_" + System.currentTimeMillis()
            val targetDir = wallpaperResourceDir(wallpaperId)

            // 从 Uri 获取文件名
            val fileName = queryFileName(context, uri) ?: "wallpaper_$wallpaperId"
            val targetFile = File(targetDir, fileName)

            // 通过 ContentResolver 复制文件
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            // 根据文件扩展名推断类型
            val type = guessTypeFromFileName(fileName)
            val displayName = fileName.substringBeforeLast('.')
            val wallpaper =
                WallpaperEntity(
                    author = "本地导入",
                    description = displayName,
                    id = wallpaperId,
                    name = displayName,
                    path = fileName,
                    versionCode = 1,
                    versionName = "1.0",
                    wallpaperType = type,
                )
            // ★ MPKG 类型：额外复制到 app 私有目录，native 库需要读取私有目录下的文件
            if (type == WallpaperEntity.TYPE_MPKG_WEBGL) {
                try {
                    val mpkgDir = File(context.filesDir, "mpkg")
                    if (!mpkgDir.exists()) mpkgDir.mkdirs()
                    val privateFile = File(mpkgDir, "${wallpaperId}_$fileName")
                    targetFile.copyTo(privateFile, overwrite = true)
                    Log.d(TAG, "MPKG copied to private dir: ${privateFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "copy MPKG to private dir failed", e)
                }
            }
            addWallpaper(wallpaper)
            // 设置为当前壁纸，方便用户立即使用
            setCurrentWallpaper(wallpaper)
            Log.d(TAG, "importFromFile(Uri): imported $displayName, type=$type")
            wallpaper
        } catch (e: Exception) {
            Log.e(TAG, "importFromFile(Uri): ", e)
            null
        }
    }

    /**
     * 从内容 URI 导入壁纸（系统文件选择器返回的 Uri）- 旧版兼容方法
     * @param context 上下文（用于 ContentResolver）
     * @param uri 内容 Uri
     * @param type 壁纸类型（HTML / VIDEO / IMAGE）
     * @return 创建的 WallpaperEntity
     */
    fun importFromFile(
        context: Context,
        uri: android.net.Uri,
        type: Int,
    ): WallpaperEntity? {
        return try {
            val wallpaperId = "wp_" + System.currentTimeMillis()
            val targetDir = wallpaperResourceDir(wallpaperId)

            // 从 Uri 获取文件名
            val fileName = queryFileName(context, uri) ?: "wallpaper_$wallpaperId"
            val targetFile = File(targetDir, fileName)

            // 通过 ContentResolver 复制文件
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            val displayName = fileName.substringBeforeLast('.')
            val wallpaper =
                WallpaperEntity(
                    author = "本地导入",
                    description = displayName,
                    id = wallpaperId,
                    name = displayName,
                    path = fileName,
                    versionCode = 1,
                    versionName = "1.0",
                    wallpaperType = type,
                )
            addWallpaper(wallpaper)
            // 设置为当前壁纸，方便用户立即使用
            setCurrentWallpaper(wallpaper)
            wallpaper
        } catch (e: Exception) {
            Log.e(TAG, "importFromFile(Uri): ", e)
            null
        }
    }

    /**
     * 从 Uri 查询文件名
     */
    private fun queryFileName(
        context: Context,
        uri: android.net.Uri,
    ): String? =
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) it.getString(nameIndex) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryFileName: ", e)
            null
        }

    /**
     * 创建网络壁纸（HTML 类型）
     */
    fun createWebWallpaper(
        url: String,
        name: String,
    ): WallpaperEntity {
        val wallpaperId = "wp_net_" + System.currentTimeMillis()
        val wallpaper =
            WallpaperEntity(
                author = "网络",
                description = "网络壁纸",
                id = wallpaperId,
                name = name,
                path = url,
                versionCode = 1,
                versionName = "1.0",
                wallpaperType = WallpaperEntity.TYPE_HTML,
            )
        addWallpaper(wallpaper)
        return wallpaper
    }

    // ============================================================
    // ★★★ 强制系统壁纸开关：多层持久化（外部存储 + SharedPreferences）
    // ============================================================

    /**
     * 读取「强制系统壁纸」开关
     *
     * ★ 多层防护读取顺序：
     *   1. 优先读外部存储配置文件（不受"清除数据"影响）
     *   2. 若外部存储没有，读 SharedPreferences（LandscapeWebActivity JS 桥接写入的）
     *   3. 若都没有，返回 false（默认关闭）
     *
     * ★ 自动同步：若外部存储有值但 SharedPreferences 没有（或反之），互相同步，保证两边一致
     */
    fun getForceSystemWallpaper(): Boolean {
        try {
            // 1. 读外部存储配置文件
            val externalValue = readForceFlagFromExternal()

            // 2. 读 SharedPreferences
            val prefsValue =
                try {
                    val prefs = context.getSharedPreferences(FORCE_PREF_NAME, Context.MODE_PRIVATE)
                    if (prefs.contains(KEY_FORCE_SYSTEM_WALLPAPER)) {
                        prefs.getBoolean(KEY_FORCE_SYSTEM_WALLPAPER, false)
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }

            // 3. 合并：外部存储优先，否则用 SharedPreferences，否则 false
            val result = externalValue ?: prefsValue ?: false

            // 4. 同步：把结果写回两边（保证一致，防止下次某一边丢失）
            if (externalValue == null || externalValue != result) {
                writeForceFlagToExternal(result)
            }
            if (prefsValue == null || prefsValue != result) {
                try {
                    context
                        .getSharedPreferences(FORCE_PREF_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_FORCE_SYSTEM_WALLPAPER, result)
                        .apply()
                } catch (_: Exception) {
                }
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "getForceSystemWallpaper failed", e)
            return false
        }
    }

    /**
     * 设置「强制系统壁纸」开关（双写：外部存储 + SharedPreferences）
     */
    fun setForceSystemWallpaper(enabled: Boolean) {
        try {
            // 1. 写外部存储配置文件
            writeForceFlagToExternal(enabled)
            // 2. 写 SharedPreferences（兼容现有 JS 桥接和 KeepAlive 读取逻辑）
            try {
                context
                    .getSharedPreferences(FORCE_PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_FORCE_SYSTEM_WALLPAPER, enabled)
                    .apply()
            } catch (_: Exception) {
            }
            Log.d(TAG, "setForceSystemWallpaper: $enabled (dual-write done)")
        } catch (e: Exception) {
            Log.e(TAG, "setForceSystemWallpaper failed", e)
        }
    }

    /**
     * 从外部存储配置文件读取 force_system_wallpaper
     * @return 值；若配置文件不存在或字段缺失返回 null
     */
    private fun readForceFlagFromExternal(): Boolean? =
        try {
            val json = readConfig()
            if (json.has(KEY_FORCE_SYSTEM_WALLPAPER)) {
                json.getBoolean(KEY_FORCE_SYSTEM_WALLPAPER)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "readForceFlagFromExternal failed", e)
            null
        }

    /**
     * 写入 force_system_wallpaper 到外部存储配置文件
     */
    private fun writeForceFlagToExternal(enabled: Boolean) {
        try {
            updateField(KEY_FORCE_SYSTEM_WALLPAPER, enabled)
        } catch (e: Exception) {
            Log.e(TAG, "writeForceFlagToExternal failed", e)
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////
    // 桌面萌宠（Desktop Pet）开关
    // //////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 获取「桌面萌宠」开关（与 force_system_wallpaper 相同的多层防护读取策略）
     *
     * 优先级：
     * 1. 外部存储配置文件（抗"清除数据"）
     * 2. SharedPreferences（FORCE_PREF_NAME，与 JS 桥接保持一致）
     * 3. 默认 false
     *
     * 自动同步两边，保证一致性
     */
    fun getDesktopPetEnabled(): Boolean {
        try {
            // ★ 修复：优先使用 SharedPreferences（更可靠），外部存储作为备份
            //   原实现 externalValue ?: prefsValue 会导致：
            //   如果外部存储写入失败但 SharedPreferences 写入成功（用户关闭萌宠），
            //   externalValue 仍是旧值 true，结果错误返回 true，导致萌宠被自动重启
            val prefsValue =
                try {
                    val prefs = context.getSharedPreferences(FORCE_PREF_NAME, Context.MODE_PRIVATE)
                    if (prefs.contains(KEY_DESKTOP_PET_ENABLED)) {
                        prefs.getBoolean(KEY_DESKTOP_PET_ENABLED, false)
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
            val externalValue = readPetFlagFromExternal()
            // ★ SharedPreferences 优先；只有 SharedPreferences 没值时才用外部存储
            val result = prefsValue ?: externalValue ?: false

            // 同步两边
            if (externalValue == null || externalValue != result) {
                writePetFlagToExternal(result)
            }
            if (prefsValue == null || prefsValue != result) {
                try {
                    context
                        .getSharedPreferences(FORCE_PREF_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_DESKTOP_PET_ENABLED, result)
                        .apply()
                } catch (_: Exception) {
                }
            }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "getDesktopPetEnabled failed", e)
            return false
        }
    }

    /**
     * 设置「桌面萌宠」开关（双写：外部存储 + SharedPreferences）
     *
     * ★ 互斥逻辑：启用萌宠时自动关闭 force_system_wallpaper（避免与系统壁纸冲突）；
     *   调用方在启用其他壁纸时应主动调用 setDesktopPetEnabled(false)
     */
    fun setDesktopPetEnabled(enabled: Boolean) {
        try {
            writePetFlagToExternal(enabled)
            try {
                context
                    .getSharedPreferences(FORCE_PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_DESKTOP_PET_ENABLED, enabled)
                    .apply()
            } catch (_: Exception) {
            }
            // ★ 不再自动互斥关闭强制系统壁纸：让用户独立控制两个开关
            Log.d(TAG, "setDesktopPetEnabled: $enabled (dual-write done)")
        } catch (e: Exception) {
            Log.e(TAG, "setDesktopPetEnabled failed", e)
        }
    }

    private fun readPetFlagFromExternal(): Boolean? =
        try {
            val json = readConfig()
            if (json.has(KEY_DESKTOP_PET_ENABLED)) {
                json.getBoolean(KEY_DESKTOP_PET_ENABLED)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "readPetFlagFromExternal failed", e)
            null
        }

    private fun writePetFlagToExternal(enabled: Boolean) {
        try {
            updateField(KEY_DESKTOP_PET_ENABLED, enabled)
        } catch (e: Exception) {
            Log.e(TAG, "writePetFlagToExternal failed", e)
        }
    }
}
