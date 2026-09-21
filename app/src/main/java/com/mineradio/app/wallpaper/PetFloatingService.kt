package com.mineradio.app.wallpaper

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class PetFloatingService : Service() {
    companion object {
        private const val TAG = "PetFloating"
        private const val NOTIFICATION_ID = 0x77A2
        private const val CHANNEL_ID = "mineradio_pet_floating"

        // 大小档位: 1-10, 线性映射到宽高 (60dp ~ 285dp)
        // 宽 = 60 + (idx-1) * 25, 高 = 宽 * 1.4
        private const val MIN_SIZE = 1
        private const val MAX_SIZE = 10
        private const val DEFAULT_SIZE = 2

        // ★ 自定义模型目录名（位于 filesDir 下）
        private const val CUSTOM_MODEL_DIR_NAME = "custom_pet_model"

        // ★ 自定义模型标记文件（用于识别目录是有效模型）
        private const val MODEL3_JSON_SUFFIX = ".model3.json"

        @Volatile private var currentSizeIndex = DEFAULT_SIZE

        // ★ v2.2 新增：屏幕高度百分比大小 (0.10-0.50)，与 currentSizeIndex 并存
        //   优先使用百分比，重启/删除后台后保持一致
        @Volatile @JvmStatic
        private var currentSizePercent: Float = 0.20f

        // ★ 当前加载的模型名称（默认 null=使用内置 Hiyori）
        @Volatile @JvmStatic
        private var currentModelName: String? = null

        @Volatile @JvmStatic
        var isRunning = false

        // 触摸穿透开关: true=萌宠只显示不接收触摸, false=正常交互
        @Volatile @JvmStatic
        var touchPassthrough = false

        // ★ 位置固定开关: true=萌宠无法拖拽，固定在当前位置
        @Volatile @JvmStatic
        var isPinned = false

        @Volatile private var instance: PetFloatingService? = null

        fun start(context: Context) {
            try {
                // ★ 修复：启动前检查开关状态，若已关闭则不启动（防止 BootReceiver、LandscapeWebActivity 等自动启动时忽略开关）
                //   根因：用户关闭萌宠后，外部存储写入可能失败导致 getDesktopPetEnabled 误读为 true
                //   已通过 WallpaperManager 优先使用 SharedPreferences 修复，但这里再加一道防线
                val petEnabled =
                    try {
                        com.mineradio.app.wallpaper.WallpaperManager
                            .get(context)
                            .getDesktopPetEnabled()
                    } catch (_: Exception) {
                        true // 读取失败时不拦截（保留原行为）
                    }
                if (!petEnabled) {
                    Log.i(TAG, "start: pet disabled, skip starting service")
                    return
                }
                // ★ 启动前清理 custom_pet_model 下的无效残留目录（如之前 RAR 当 ZIP 导入失败遗留的空目录）
                cleanupInvalidCustomModels(context)
                val intent = Intent(context, PetFloatingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
            }
        }

        /** ★ 清理 custom_pet_model 下没有 .model3.json 的无效目录，避免 native 加载到空目录 */
        @JvmStatic
        fun cleanupInvalidCustomModels(context: Context) {
            try {
                val customDir = getCustomModelDir(context)
                if (!customDir.isDirectory) return
                customDir.listFiles()?.forEach { subDir ->
                    if (subDir.isDirectory) {
                        val hasModel3 = findModel3Json(subDir)
                        if (hasModel3 == null) {
                            Log.w(TAG, "cleanupInvalidCustomModels: 删除无效目录 ${subDir.name}")
                            subDir.deleteRecursively()
                        }
                    }
                }
                // ★ 同时清理任何残留的临时 .zip 文件
                customDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".zip")) {
                        f.delete()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "cleanupInvalidCustomModels failed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, PetFloatingService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "stop failed", e)
            }
        }

        // ★ 计算指定档位对应的尺寸 (dp) - 旧 API，保留向后兼容
        //   尺寸约为原始的 1.05 倍（1.5倍缩小到70%）：宽 = 63 + (idx-1) * 26，高 = 宽 * 1.4
        private fun sizeForIndex(idx: Int): Pair<Int, Int> {
            val clamped = idx.coerceIn(MIN_SIZE, MAX_SIZE)
            val widthDp = 63 + (clamped - 1) * 26
            val heightDp = (widthDp * 1.4f).toInt()
            return Pair(widthDp, heightDp)
        }

        /** ★ v2.2 新增：基于屏幕分辨率百分比计算尺寸 (px)
         *  @param percent 屏幕高度百分比 (0.10-0.50)
         *  @return (widthPx, heightPx) 像素，按 0.71 宽高比计算
         *
         *  ★ 关键：使用真实屏幕分辨率（含导航栏/状态栏），不依赖 dp 转换
         *    这样不管重启还是删除后台，只要屏幕分辨率不变，萌宠大小就保持一致
         */
        @JvmStatic
        fun sizeForPercent(
            context: Context,
            percent: Float,
        ): Pair<Int, Int> {
            val pct = percent.coerceIn(0.10f, 0.50f)
            // ★ 获取真实屏幕分辨率（包含系统栏）
            val (screenW, screenH) = getRealScreenSizeForContext(context)
            // 高度 = 屏幕高度 × 百分比
            val heightPx = (screenH * pct).toInt().coerceAtLeast(120)
            // 宽度 = 高度 × 0.71 (萌宠宽高比，保持原始比例)
            val widthPx = (heightPx * 0.71f).toInt().coerceAtLeast(84)
            return Pair(widthPx, heightPx)
        }

        /** ★ v2.2 新增：获取真实屏幕尺寸（包含系统栏） */
        @JvmStatic
        fun getRealScreenSizeForContext(context: Context): Pair<Int, Int> =
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val display = wm.defaultDisplay
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                Pair(metrics.widthPixels, metrics.heightPixels)
            } catch (e: Exception) {
                Log.w(TAG, "getRealScreenSizeForContext failed", e)
                Pair(1920, 1080) // 兜底
            }

        /** ★ v2.2 新增：设置大小（屏幕高度百分比 0.10-0.50），实时生效
         *  - 持久化为 String（支持浮点），兼容旧版 Int
         *  - 重启后从 SharedPreferences 恢复
         */
        @JvmStatic
        fun setSizePercent(
            context: Context,
            percent: Float,
        ) {
            val pct = percent.coerceIn(0.10f, 0.50f)
            currentSizePercent = pct
            // ★ 持久化为 String（浮点），同时写一个旧版 Int 字段兼容
            try {
                val prefs = context.getSharedPreferences("pet_floating_prefs", Context.MODE_PRIVATE)
                prefs
                    .edit()
                    .putString("size_percent", pct.toString())
                    .putInt("size_index", (pct * 10).toInt()) // 兼容旧版（0.20 → 2）
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "persist size_percent failed", e)
            }
            instance?.applySize()
        }

        /** ★ v2.2 新增：获取大小百分比（从 SharedPreferences 恢复） */
        @JvmStatic
        fun getSizePercent(context: Context): Float {
            return try {
                val prefs = context.getSharedPreferences("pet_floating_prefs", Context.MODE_PRIVATE)
                // 优先读取新版 size_percent
                val str = prefs.getString("size_percent", null)
                if (str != null) {
                    val f = str.toFloatOrNull()
                    if (f != null) return f.coerceIn(0.10f, 0.50f)
                }
                // 回退到旧版 size_index，转换为百分比
                val idx = prefs.getInt("size_index", DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE)
                (idx / 10.0f).coerceIn(0.10f, 0.50f)
            } catch (e: Exception) {
                currentSizePercent
            }
        }

        /** 设置大小档位 (1-10)，实时生效 - 旧 API，保留向后兼容 */
        @JvmStatic
        fun setSize(
            context: Context,
            idx: Int,
        ) {
            val clamped = idx.coerceIn(MIN_SIZE, MAX_SIZE)
            currentSizeIndex = clamped
            // ★ v2.2 同时更新百分比（idx/10.0）
            currentSizePercent = (clamped / 10.0f).coerceIn(0.10f, 0.50f)
            // ★ 持久化 size 到 SharedPreferences（统一使用 pet_floating_prefs），防止应用重启后丢失
            try {
                val prefs = context.getSharedPreferences("pet_floating_prefs", Context.MODE_PRIVATE)
                prefs
                    .edit()
                    .putInt("size_index", clamped)
                    .putString("size_percent", currentSizePercent.toString())
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "persist size failed", e)
            }
            instance?.applySize()
        }

        /** 旧 API - 保留向后兼容 */
        @JvmStatic
        fun getSize(context: Context): Int {
            // ★ 从 SharedPreferences 读取持久化的 size
            return try {
                val prefs = context.getSharedPreferences("pet_floating_prefs", Context.MODE_PRIVATE)
                prefs.getInt("size_index", currentSizeIndex)
            } catch (e: Exception) {
                currentSizeIndex
            }
        }

        /** 设置触摸穿透: true=只显示不接收触摸, false=正常交互 */
        @JvmStatic
        fun setTouchPassthrough(
            context: Context,
            on: Boolean,
        ) {
            touchPassthrough = on
            // ★ v2.2.4 持久化到 SharedPreferences，重启后恢复
            try {
                context
                    .getSharedPreferences("pet_floating_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("touch_passthrough", on)
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "persist touch_passthrough failed", e)
            }
            instance?.applyTouchPassthrough()
        }

        @JvmStatic
        fun getTouchPassthrough(context: Context): Boolean = touchPassthrough

        /** ★ 设置位置固定: true=萌宠无法拖拽，固定到当前位置；false=正常可拖拽 */
        @JvmStatic
        fun setPinned(
            context: Context,
            on: Boolean,
        ) {
            isPinned = on
            // ★ v2.2.4 持久化到 SharedPreferences，重启后恢复
            try {
                context
                    .getSharedPreferences("pet_floating_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("is_pinned", on)
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "persist is_pinned failed", e)
            }
            // 固定时不需要改变窗口 flags，只需在触摸事件中拦截拖拽即可
        }

        @JvmStatic
        fun getPinned(context: Context): Boolean = isPinned

        // ═══════════════════════════════════════════════════════════
        //  ★★★ v2.2 扩展：悬浮窗完整控制（位置/大小/透明度/状态）★★★
        // ═══════════════════════════════════════════════════════════

        /** ★ 设置悬浮窗位置（屏幕坐标，单位 px） */
        @JvmStatic
        fun setPosition(
            context: Context,
            x: Int,
            y: Int,
        ) {
            try {
                instance?.let { svc ->
                    svc.layoutParams.x = x
                    svc.layoutParams.y = y
                    try {
                        svc.windowManager.updateViewLayout(svc.petView, svc.layoutParams)
                    } catch (e: Exception) {
                        Log.w(TAG, "setPosition: updateViewLayout failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "setPosition failed", e)
            }
        }

        /** ★ 设置悬浮窗大小（像素，会自动按比例缩放） */
        @JvmStatic
        fun setWindowSize(
            context: Context,
            width: Int,
            height: Int,
        ) {
            try {
                instance?.let { svc ->
                    svc.layoutParams.width = width.coerceAtLeast(60)
                    svc.layoutParams.height = height.coerceAtLeast(84)
                    try {
                        svc.windowManager.updateViewLayout(svc.petView, svc.layoutParams)
                    } catch (e: Exception) {
                        Log.w(TAG, "setWindowSize: updateViewLayout failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "setWindowSize failed", e)
            }
        }

        /** ★ 设置悬浮窗透明度 (0.0-1.0) */
        @JvmStatic
        fun setOpacity(
            context: Context,
            alpha: Float,
        ) {
            try {
                instance?.let { svc ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            svc.petView.alpha = alpha.coerceIn(0f, 1f)
                        } catch (e: Exception) {
                            Log.w(TAG, "setOpacity: petView.alpha failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "setOpacity failed", e)
            }
        }

        /** ★ 获取悬浮窗信息：返回四元组 (x, y, width, height) */
        @JvmStatic
        fun getWindowInfo(context: Context): WindowInfo =
            try {
                val svc = instance
                if (svc != null) {
                    WindowInfo(
                        svc.layoutParams.x,
                        svc.layoutParams.y,
                        svc.layoutParams.width,
                        svc.layoutParams.height,
                    )
                } else {
                    WindowInfo(0, 0, 0, 0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "getWindowInfo failed", e)
                WindowInfo(0, 0, 0, 0)
            }

        /** 悬浮窗信息数据类 */
        data class WindowInfo(
            val x: Int,
            val y: Int,
            val width: Int,
            val height: Int,
        )

        // ═══════════════════════════════════════════════════════════
        //  ★★★ 表情管理
        // ═══════════════════════════════════════════════════════════

        /** 获取当前模型所有表情名称（JSON 数组字符串，如 ["呆","哭",...]） */
        @JvmStatic
        fun getExpressionList(context: Context): String {
            return try {
                // ★ 守卫：萌宠服务未运行时，Live2D 引擎未初始化，直接返回空数组
                // 避免触发 native 层 GetInstance() 构造链导致 SIGSEGV
                if (!isRunning) {
                    Log.w(TAG, "getExpressionList: 萌宠未运行，返回空列表")
                    return "[]"
                }
                val raw = JniBridgePet.nativeGetExpressionNames()
                if (raw.isBlank()) return "[]"
                // 用 \n 分隔转成 JSON 数组
                val items = raw.split("\n").filter { it.isNotBlank() }
                val sb = StringBuilder("[")
                items.forEachIndexed { idx, name ->
                    if (idx > 0) sb.append(",")
                    sb.append("\"").append(name.replace("\"", "\\\"")).append("\"")
                }
                sb.append("]")
                sb.toString()
            } catch (e: Exception) {
                Log.w(TAG, "getExpressionList failed: ${e.message}")
                "[]"
            }
        }

        /** 触发指定表情 */
        @JvmStatic
        fun triggerExpression(
            context: Context,
            name: String,
        ) {
            try {
                // ★ 守卫：萌宠未运行时静默忽略，避免 native 崩溃
                if (!isRunning) {
                    Log.w(TAG, "triggerExpression: 萌宠未运行，忽略触发")
                    return
                }
                JniBridgePet.nativeTriggerExpression(name)
            } catch (e: Exception) {
                Log.w(TAG, "triggerExpression failed: ${e.message}")
            }
        }

        // ═══════════════════════════════════════════════════════════
        //  ★★★ 音乐状态推送入口（静态，供主程序调用）
        // ═══════════════════════════════════════════════════════════

        /** 主程序推送音乐播放状态 */
        @JvmStatic
        fun notifyMusicState(
            playing: Boolean,
            title: String,
            bpm: Int,
        ) {
            instance?.handleMusicState(playing, title, bpm)
        }

        /** 主程序推送节拍事件 */
        @JvmStatic
        fun notifyBeat(intensity: Float) {
            instance?.handleBeat(intensity)
        }

        // ═══════════════════════════════════════════════════════════
        //  ★★★ 自定义模型管理
        // ═══════════════════════════════════════════════════════════

        /** 获取自定义模型根目录（filesDir/custom_pet_model） */
        @JvmStatic
        fun getCustomModelDir(context: Context): File = File(context.filesDir, CUSTOM_MODEL_DIR_NAME)

        /**
         * ★ 导入自定义模型：将 base64 编码的 zip 解压到 filesDir/custom_pet_model/<modelName>/
         * @param fileName 原始文件名（用于推导模型名，必须 .zip 结尾）
         * @param base64Data zip 文件的 base64 编码
         * @return "1"=成功, 其他=错误信息
         */
        @JvmStatic
        fun importModel(
            context: Context,
            fileName: String,
            base64Data: String,
        ): String {
            return try {
                // 1. 推导模型名：去掉 .zip 后缀
                val modelName = fileName.substringBeforeLast(".zip").ifEmpty { "custom_model" }
                // 清理非法字符
                val safeName = modelName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifEmpty { "custom_model" }

                val customDir = getCustomModelDir(context)
                val targetDir = File(customDir, safeName)

                // 2. 删除旧的同名模型目录
                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                }
                customDir.mkdirs()
                targetDir.mkdirs()

                // 3. 解码 base64 并写入临时 zip
                val tempZip = File(customDir, "$safeName.zip")
                val zipBytes = Base64.decode(base64Data, Base64.DEFAULT)
                FileOutputStream(tempZip).use { it.write(zipBytes) }

                // 4. 解压 zip 到 targetDir
                unzipToDirectory(tempZip, targetDir)

                // 5. 删除临时 zip
                tempZip.delete()

                // 6. 校验解压结果：递归查找 model3.json 文件
                val model3Json = findModel3Json(targetDir)
                if (model3Json == null) {
                    Log.e(TAG, "importModel: 未找到 .model3.json 文件")
                    targetDir.deleteRecursively()
                    return "未找到 model3.json，请检查压缩包格式"
                }

                // 7. 标准化目录结构：把 model3.json 所在目录的所有内容提升到模型根目录
                val effectiveDir = model3Json.parentFile ?: targetDir
                if (effectiveDir != targetDir) {
                    // 把 model3.json 所在目录的内容移动到 targetDir 根
                    effectiveDir.listFiles()?.forEach { file ->
                        val dest = File(targetDir, file.name)
                        if (file.isDirectory) {
                            file.copyRecursively(dest, overwrite = true)
                        } else {
                            file.copyTo(dest, overwrite = true)
                        }
                    }
                    // 删除原始解压的嵌套结构（保留 targetDir）
                    // 先删除 effectiveDir 的父级链（不包括 targetDir）
                    var delDir: File? = effectiveDir
                    while (delDir != null && delDir != targetDir) {
                        val parent = delDir.parentFile
                        if (parent == targetDir) {
                            // 到达 targetDir 直接子目录，删除整个 effectiveDir
                            delDir.deleteRecursively()
                            break
                        }
                        delDir = parent
                    }
                    // ★ 修复：只删除"包含 .model3.json 文件的其他顶层目录"（多模型 ZIP 场景，如 hiyori_pro/hiyori_free）
                    // 不能删除纹理目录（如 .4096/, .2048/）和动作目录（motion/），否则会丢失资源导致模型空白
                    val finalModel3Name = model3Json.name
                    targetDir.listFiles()?.forEach { f ->
                        if (f.isDirectory) {
                            val hasModel3 = findModel3Json(f)
                            // ★ 只有当目录下有 .model3.json 且不是当前模型时才删除（避免误删纹理/动作目录）
                            if (hasModel3 != null && hasModel3.name != finalModel3Name) {
                                Log.i(TAG, "importModel: 删除其他模型目录 ${f.name}")
                                f.deleteRecursively()
                            }
                        }
                    }
                }

                // 7.5 校验标准化后的目录
                val finalModel3Json = findModel3Json(targetDir)
                if (finalModel3Json == null) {
                    Log.e(TAG, "importModel: 标准化后未找到 .model3.json")
                    targetDir.deleteRecursively()
                    return "模型标准化失败，请检查压缩包结构"
                }

                Log.i(TAG, "importModel: 标准化完成，model3.json=${finalModel3Json.name}")

                // 8. 设置当前模型名
                currentModelName = safeName
                // ★ 持久化到 SharedPreferences
                try {
                    context
                        .getSharedPreferences("pet_floating_prefs", MODE_PRIVATE)
                        .edit()
                        .putString("current_model_name", safeName)
                        .apply()
                } catch (e: Exception) {
                    Log.w(TAG, "importModel: save prefs failed", e)
                }
                Log.i(TAG, "importModel: 成功导入模型 $safeName")

                // ★ v2.2.7 关键修复：删除其他模型目录，确保 native 层只加载用户导入的模型
                //   之前 native 层可能加载到 desktop-pet（内置模型）而非用户导入的模型
                try {
                    val customDir = getCustomModelDir(context)
                    customDir.listFiles()?.forEach { f ->
                        if (f.isDirectory && f.name != safeName) {
                            Log.i(TAG, "importModel: 删除其他模型目录: ${f.name}")
                            f.deleteRecursively()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "importModel: 清理其他模型目录失败: ${e.message}")
                }

                // 9. 重新加载模型（重启服务）
                reloadModel(context)
                "1"
            } catch (e: Exception) {
                Log.e(TAG, "importModel failed", e)
                e.message ?: "导入失败"
            }
        }

        /** ★ 重置为默认模型（删除自定义模型目录） */
        @JvmStatic
        fun resetModel(context: Context): String =
            try {
                currentModelName = null
                // ★ 清除持久化的模型名
                try {
                    context
                        .getSharedPreferences("pet_floating_prefs", MODE_PRIVATE)
                        .edit()
                        .remove("current_model_name")
                        .apply()
                } catch (e: Exception) {
                    Log.w(TAG, "resetModel: clear prefs failed", e)
                }
                val customDir = getCustomModelDir(context)
                if (customDir.exists()) {
                    customDir.deleteRecursively()
                }
                Log.i(TAG, "resetModel: 已重置为默认模型")
                reloadModel(context)
                "1"
            } catch (e: Exception) {
                Log.e(TAG, "resetModel failed", e)
                e.message ?: "重置失败"
            }

        /** ★ 获取当前模型名称（用于 UI 显示） */
        @JvmStatic
        fun getCurrentModelName(context: Context): String = currentModelName ?: "默认"

        /** ★ 获取当前模型在 live2d 目录中的目录名 */
        @JvmStatic
        fun getCurrentModelDirName(): String? = currentModelName

        /**
         * ★ v2.2.4 设置当前模型目录名（不触发 reloadModel）
         * 用于 renderFloatWindow 中复制模型后直接设置，由调用方统一调用 start
         * @param context 上下文
         * @param modelName 模型目录名（位于 filesDir/custom_pet_model/ 下）
         * @return "1"=成功, 其他=错误信息
         */
        @JvmStatic
        fun setCurrentModelDirNameNoReload(
            context: Context,
            modelName: String,
        ): String {
            return try {
                val safeName = modelName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifEmpty { "plugin_model" }
                val modelDir = File(getCustomModelDir(context), safeName)
                if (!modelDir.isDirectory) {
                    return "模型目录不存在: $safeName"
                }
                // 校验目录下必须有 .model3.json
                val model3Json = findModel3Json(modelDir)
                if (model3Json == null) {
                    return "目录 $safeName 下未找到 .model3.json"
                }
                currentModelName = safeName
                // 持久化
                try {
                    context
                        .getSharedPreferences("pet_floating_prefs", MODE_PRIVATE)
                        .edit()
                        .putString("current_model_name", safeName)
                        .apply()
                } catch (e: Exception) {
                    Log.w(TAG, "setCurrentModelDirNameNoReload: save prefs failed", e)
                }
                Log.i(TAG, "setCurrentModelDirNameNoReload: 已设置模型 $safeName（不重启）")
                "1"
            } catch (e: Exception) {
                Log.e(TAG, "setCurrentModelDirNameNoReload failed", e)
                e.message ?: "设置失败"
            }
        }

        /**
         * ★ 设置当前模型目录名（v2.1 扩展，供插件 API 调用）
         * 用于插件指定一个已存在于 custom_pet_model 下的模型目录名
         * 设置后立即重载萌宠服务以应用新模型
         * @param context 上下文
         * @param modelName 模型目录名（位于 filesDir/custom_pet_model/ 下）
         * @return "1"=成功, 其他=错误信息
         */
        @JvmStatic
        fun setCurrentModelDirName(
            context: Context,
            modelName: String,
        ): String {
            return try {
                val safeName = modelName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifEmpty { "plugin_model" }
                val modelDir = File(getCustomModelDir(context), safeName)
                if (!modelDir.isDirectory) {
                    return "模型目录不存在: $safeName"
                }
                // 校验目录下必须有 .model3.json
                val model3Json = findModel3Json(modelDir)
                if (model3Json == null) {
                    return "目录 $safeName 下未找到 .model3.json"
                }
                currentModelName = safeName
                // 持久化
                try {
                    context
                        .getSharedPreferences("pet_floating_prefs", MODE_PRIVATE)
                        .edit()
                        .putString("current_model_name", safeName)
                        .apply()
                } catch (e: Exception) {
                    Log.w(TAG, "setCurrentModelDirName: save prefs failed", e)
                }
                Log.i(TAG, "setCurrentModelDirName: 已切换到插件模型 $safeName")
                reloadModel(context)
                "1"
            } catch (e: Exception) {
                Log.e(TAG, "setCurrentModelDirName failed", e)
                e.message ?: "设置失败"
            }
        }

        /** ★ 判断是否使用自定义模型 */
        @JvmStatic
        fun isUsingCustomModel(): Boolean = currentModelName != null

        /** 重新加载模型：重启 PetFloatingService（在主线程执行） */
        @JvmStatic
        private fun reloadModel(context: Context) {
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        // 先停止当前服务
                        stop(context)
                        // ★ 延长延迟到 1000ms，确保旧服务完全销毁（避免 GL 资源竞争闪退）
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try {
                                start(context)
                            } catch (e: Exception) {
                                Log.e(TAG, "reloadModel restart failed", e)
                            }
                        }, 1000)
                    } catch (e: Exception) {
                        Log.e(TAG, "reloadModel stop failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "reloadModel failed", e)
            }
        }

        /** ★ v2.2.4 公共版 reloadModel，供 renderFloatWindow 调用
         *   与 importModel 后的 reload 行为一致，确保服务重新创建 */
        @JvmStatic
        fun reloadModelPublic(context: Context) {
            reloadModel(context)
        }

        /** ★ v2.2.5 更新对话气泡文字（供 JS 层调用，显示当前歌词）
         *  - text 为空时隐藏对话气泡
         *  - text 非空时显示对话气泡并更新文字
         */
        @JvmStatic
        fun updateSpeechBubbleText(
            context: Context,
            text: String,
        ) {
            try {
                val svc = instance ?: return
                if (text.isBlank()) {
                    svc.hideSpeechBubblePublic()
                } else {
                    // ★ v2.2.6 解析 "text|charIdx" 格式：显示前 N 个字
                    val pipeIdx = text.lastIndexOf('|')
                    if (pipeIdx > 0) {
                        val realText = text.substring(0, pipeIdx)
                        val charIdxStr = text.substring(pipeIdx + 1)
                        val charIdx = charIdxStr.toIntOrNull() ?: -1
                        if (realText.isBlank()) {
                            svc.hideSpeechBubblePublic()
                        } else {
                            svc.showSpeechBubbleWithCharIdx(realText, charIdx)
                        }
                    } else {
                        svc.showSpeechBubblePublic(text)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "updateSpeechBubbleText failed", e)
            }
        }

        /** 解压 zip 到目标目录
         *  ★ 增强：校验文件头魔术字节，识别并拒绝 RAR/7z 等非 ZIP 格式
         *  - ZIP: 50 4B 03 04 (PK\x03\x04)
         *  - RAR5: 52 61 72 21 1A 07 (Rar!\x1a\x07)
         *  - RAR4: 52 61 72 21 1A 07 00 (Rar!\x1a\x07\x00)（同前 6 字节）
         *  - 7z: 37 7A BC AF 27 1C
         */
        private fun unzipToDirectory(
            zipFile: File,
            targetDir: File,
        ) {
            // ★ 1. 校验文件头，拒绝非 ZIP 格式（如把 .rar 改名为 .zip）
            val headerBytes = ByteArray(8)
            zipFile.inputStream().use { it.read(headerBytes) }
            val isZip =
                headerBytes[0] == 0x50.toByte() &&
                    headerBytes[1] == 0x4B.toByte() &&
                    (
                        headerBytes[2] == 0x03.toByte() ||
                            headerBytes[2] == 0x05.toByte() ||
                            headerBytes[2] == 0x07.toByte()
                    )
            val isRar =
                headerBytes[0] == 0x52.toByte() &&
                    headerBytes[1] == 0x61.toByte() &&
                    headerBytes[2] == 0x72.toByte() &&
                    headerBytes[3] == 0x21.toByte()
            val is7z =
                headerBytes[0] == 0x37.toByte() &&
                    headerBytes[1] == 0x7A.toByte() &&
                    headerBytes[2] == 0xBC.toByte() &&
                    headerBytes[3] == 0xAF.toByte()
            if (!isZip) {
                val fmt =
                    when {
                        isRar -> "RAR"
                        is7z -> "7z"
                        else -> "未知"
                    }
                throw IllegalArgumentException("文件不是 ZIP 格式（检测到 $fmt 格式），请用解压软件重新压缩为 .zip 后再导入")
            }

            // ★ 2. 用 ZipInputStream 解压
            var extractedCount = 0
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // 防止 zip slip 攻击
                        val outputFile = File(targetDir, entry.name)
                        val canonicalPath = outputFile.canonicalPath
                        val targetCanonical = targetDir.canonicalPath + File.separator
                        if (!canonicalPath.startsWith(targetCanonical)) {
                            Log.w(TAG, "unzipToDirectory: 跳过可疑路径 ${entry.name}")
                            entry = zis.nextEntry
                            continue
                        }
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { fos ->
                            var count: Int
                            while (zis.read(buffer).also { count = it } != -1) {
                                fos.write(buffer, 0, count)
                            }
                        }
                        extractedCount++
                    }
                    entry = zis.nextEntry
                }
            }
            // ★ 3. 校验：解压出的文件数必须 > 0，否则视为无效压缩包
            if (extractedCount == 0) {
                throw IllegalArgumentException("压缩包内没有任何文件，可能是损坏或格式不支持")
            }
            Log.i(TAG, "unzipToDirectory: 成功解压 $extractedCount 个文件到 $targetDir")
        }

        /** 递归查找 .model3.json 文件（支持任意深度嵌套） */
        private fun findModel3Json(dir: File): File? {
            if (!dir.isDirectory) return null
            // BFS 递归查找
            val queue = ArrayDeque<File>()
            queue.add(dir)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                current.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(MODEL3_JSON_SUFFIX)) {
                        return file
                    } else if (file.isDirectory) {
                        queue.add(file)
                    }
                }
            }
            return null
        }

        private data class SizePreset(
            val widthDp: Int,
            val heightDp: Int,
        )
    }

    private lateinit var windowManager: WindowManager
    private lateinit var petView: Live2DPetView
    private lateinit var layoutParams: WindowManager.LayoutParams

    // ★ v2.2.5 对话气泡（显示在萌宠右上方）
    private var speechBubbleView: android.widget.TextView? = null
    private var speechBubbleParams: WindowManager.LayoutParams? = null
    private var speechBubbleHandler: android.os.Handler? = null
    private var speechBubbleHideRunnable: Runnable? = null

    // ★ v2.2.7 单字丝滑弹出动画状态
    //   使用 ValueAnimator + 每帧重建 RelativeSizeSpan 实现弹性缩放
    //   对应 cubic-bezier(0.34, 1.56, 0.64, 1) 弹性曲线
    private var charAnimator: android.animation.ValueAnimator? = null
    private var animSpannable: android.text.SpannableStringBuilder? = null
    private var animCharStart: Int = 0
    private var animCharEnd: Int = 0

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isDragging: Boolean = false
    private var touchDownTime: Long = 0L

    private val clickTimeThreshold = 300L
    private val clickDistanceThreshold = 10f

    // ═══════════════════════════════════════════════════════════
    //  ★★★ 萌宠交互状态机 v3
    //  - 快速拖动（距离>800px 且 速度>2.5px/ms） → 晕眩
    //  - 连续快速点击 ≥9 次 → 立即晕眩（在 ACTION_DOWN 触发，不等 UP）
    //  - 晕眩状态下继续点击 → 黑化
    //  - 随机动作定时器（5-15秒随机间隔，随机表情）
    //  - 夜间（0:00-8:00）20秒无交互 → 长时间睡眠（直到下次触碰唤醒）
    //  - 白天长时间无交互 → 打哈欠
    // ═══════════════════════════════════════════════════════════

    /** 萌宠当前情绪状态 */
    private enum class PetMood {
        NORMAL, // 正常
        DIZZY, // 晕眩
        DARKIFIED, // 黑化
        SLEEPING, // 睡眠（夜间长时间无交互）
        YAWN, // 打哈欠（白天长时间无交互）
    }

    @Volatile private var currentMood: PetMood = PetMood.NORMAL

    // 连续快速点击计数
    @Volatile private var rapidClickCount: Int = 0

    @Volatile private var lastClickTime: Long = 0L
    private val clickResetMs = 1200L // 1.2秒内无再次点击则归零
    private val dizzyTriggerClickCount = 9 // 连续快速点击 9 次触发晕眩
    private val dizzyExpireMs = 4000L // 晕眩状态自动恢复时间
    private val darkifiedExpireMs = 8000L // 黑化状态自动恢复时间

    // 拖动检测：累计移动距离 + 速度（严格阈值，避免误触）
    private var dragStartTime: Long = 0L
    private var dragDistanceAccum: Float = 0f
    private var lastMoveX: Float = 0f
    private var lastMoveY: Float = 0f
    private var lastMoveTime: Long = 0L

    // ★ 严格阈值：必须快速拖出且距离过远才触发晕眩
    private val fastDragDistanceThreshold = 800f // 累计距离 > 800px
    private val fastDragSpeedThreshold = 2.5f // 平均速度 > 2.5 px/ms

    // 闲置/睡眠检测
    @Volatile private var lastInteractTime: Long = System.currentTimeMillis()
    private val nightIdleThresholdMs = 20_000L // 夜间 20 秒无交互 → 睡眠
    private val dayYawnThresholdMs = 60_000L // 白天 60 秒无交互 → 打哈欠
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleCheckRunnable =
        object : Runnable {
            override fun run() {
                if (!isRunning) return
                val now = System.currentTimeMillis()
                val idleDuration = now - lastInteractTime
                val hour =
                    java.util.Calendar
                        .getInstance()
                        .get(java.util.Calendar.HOUR_OF_DAY)
                val isNight = hour in 0..7 // 0:00-8:00

                // 只在 NORMAL 状态触发闲置反应
                if (currentMood == PetMood.NORMAL) {
                    if (isNight && idleDuration > nightIdleThresholdMs) {
                        // 夜间 20 秒无交互 → 进入长时间睡眠
                        enterSleepMode()
                    } else if (!isNight && idleDuration > dayYawnThresholdMs) {
                        // 白天 60 秒无交互 → 打哈欠
                        triggerYawn()
                    }
                }
                // 每 5 秒检查一次
                idleHandler.postDelayed(this, 5_000L)
            }
        }

    // 情绪自动恢复定时器
    private val moodResetHandler = Handler(Looper.getMainLooper())
    private val moodResetRunnable =
        Runnable {
            // 睡眠状态不自动恢复，必须由用户触碰唤醒
            if (currentMood != PetMood.SLEEPING) {
                currentMood = PetMood.NORMAL
                Log.d(TAG, "情绪自动恢复为 NORMAL")
            }
        }

    // ★ 单击延时确认：单击 1.2 秒后无后续点击才触发"抱抱"
    //   避免连续快速点击时抱抱和晕眩叠加
    private val singleClickHandler = Handler(Looper.getMainLooper())
    private val singleClickRunnable =
        Runnable {
            // 确认是单击（count 仍为 1）且仍在 NORMAL 状态 → 触发抱抱
            if (rapidClickCount == 1 && currentMood == PetMood.NORMAL) {
                try {
                    if (::petView.isInitialized) {
                        petView.queueEvent {
                            try {
                                JniBridgePet.nativeTriggerExpression("抱抱")
                            } catch (e: Exception) {
                            }
                        }
                        Log.i(TAG, "单击 → 抱抱")
                    }
                } catch (e: Exception) {
                }
            }
        }

    // ★★★ 随机动作定时器：5-15 秒随机间隔触发随机表情
    private val randomActionHandler = Handler(Looper.getMainLooper())
    private val randomActionRunnable =
        object : Runnable {
            override fun run() {
                if (!isRunning) return
                // 只在 NORMAL 状态触发随机动作
                if (currentMood == PetMood.NORMAL) {
                    triggerRandomAction()
                }
                // 下次触发：5-15 秒随机间隔
                val nextDelay = (5_000L + (Math.random() * 10_000L).toLong())
                randomActionHandler.postDelayed(this, nextDelay)
            }
        }

    // 随机动作候选池（不含晕/呆/黑化/抱抱等专属状态表情，避免冲突）
    private val randomActionPool =
        listOf(
            "吐舌",
            "呲牙",
            "嘟嘴",
            "鼓嘴",
            "波浪嘴",
            "咬嘴",
            "脸红",
            "可怜",
            "横眼",
            "豆豆眼",
            "尖角",
            "剪刀手",
            "抱猫",
            "捂嘴",
            "纸飞机",
            "食指",
            "猫耳",
            "翅膀",
            "海豹",
            "剑",
            "手柄",
            "左键",
            "右键",
        )

    /** 触发随机动作 */
    private fun triggerRandomAction() {
        val exp = randomActionPool.random()
        try {
            if (::petView.isInitialized) {
                petView.queueEvent {
                    try {
                        JniBridgePet.nativeTriggerExpression(exp)
                    } catch (e: Exception) {
                    }
                }
                Log.d(TAG, "随机动作: $exp")
            }
        } catch (e: Exception) {
        }
    }

    /** 进入睡眠模式（长时间，直到下次触碰唤醒） */
    private fun enterSleepMode() {
        currentMood = PetMood.SLEEPING
        moodResetHandler.removeCallbacks(moodResetRunnable)
        // ★ v2.2.5 移除"呆"表情，改用"可怜"作为睡眠表现
        try {
            if (::petView.isInitialized) {
                petView.queueEvent {
                    try {
                        JniBridgePet.nativeTriggerExpression("可怜")
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
        }
        Log.i(TAG, "进入睡眠模式（夜间20秒无交互）")
    }

    /** 打哈欠（白天长时间无交互） */
    private fun triggerYawn() {
        currentMood = PetMood.YAWN
        // ★ v2.2.5 移除"呆"表情，改用"可怜"或"波浪嘴"作为打哈欠表现
        val exp = listOf("可怜", "波浪嘴").random()
        try {
            if (::petView.isInitialized) {
                petView.queueEvent {
                    try {
                        JniBridgePet.nativeTriggerExpression(exp)
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
        }
        // 5 秒后恢复
        moodResetHandler.removeCallbacks(moodResetRunnable)
        moodResetHandler.postDelayed(moodResetRunnable, 5_000L)
        Log.i(TAG, "打哈欠: $exp")
    }

    /** 用户交互时调用：重置闲置计时 + 唤醒睡眠 */
    private fun onUserInteracted() {
        lastInteractTime = System.currentTimeMillis()
        // 如果在睡眠状态，被触碰唤醒
        if (currentMood == PetMood.SLEEPING) {
            currentMood = PetMood.NORMAL
            moodResetHandler.removeCallbacks(moodResetRunnable)
            Log.i(TAG, "睡眠被触碰唤醒 → NORMAL")
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ★★★ 音乐播放状态接收（一首歌只触发一次动作，限定呲牙/嘟嘴/吐舌）
    // ═══════════════════════════════════════════════════════════
    @Volatile private var musicPlaying: Boolean = false

    @Volatile private var musicTitle: String = ""

    @Volatile private var lastSongTriggered: String = "" // 已触发过动作的歌曲标题
    private val musicReactHandler = Handler(Looper.getMainLooper())

    // ═══════════════════════════════════════════════════════════
    //  ★★★ 陀螺仪视角控制（姿态版本 v2）
    //  - 使用 TYPE_ROTATION_VECTOR 获取设备绝对姿态
    //  - 通过 remapCoordinateSystem 适配横竖屏切换
    //  - 基准为"手机垂直使用姿态"（重力方向），永不漂移
    //  - 所有 native 调用通过 queueEvent 转到 GL 线程，避免线程竞争
    // ═══════════════════════════════════════════════════════════
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var gyroEnabled: Boolean = false
    private val gyroSmoothing = 0.25f // 低通滤波平滑系数（越大越灵敏）
    private val gyroMaxOffset = 1.0f // 最大视角偏移（满范围）
    private var smoothGyroX: Float = 0f
    private var smoothGyroY: Float = 0f

    // 旋转矩阵和方向数组（避免每次回调重复创建）
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)

    private val sensorListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || !gyroEnabled || !isRunning) return
                if (!::petView.isInitialized) return
                if (event.values.size < 3) return

                try {
                    // ★ 步骤1: 从旋转矢量计算设备原始姿态矩阵（相对于自然方向）
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                    // ★ 步骤2: 根据屏幕显示方向重映射坐标轴
                    // 这样无论横屏竖屏，pitch/roll 都是相对于"当前屏幕方向"
                    val (axisX, axisY) = getScreenAxisMapping()
                    SensorManager.remapCoordinateSystem(
                        rotationMatrix,
                        axisX,
                        axisY,
                        remappedMatrix,
                    )

                    // ★ 步骤3: 从重映射后的矩阵提取 pitch/roll（弧度）
                    SensorManager.getOrientation(remappedMatrix, orientationValues)
                    // pitch: 屏幕前后倾斜（屏幕远离/靠近身体）
                    // roll: 屏幕左右倾斜
                    // 基准 = 手机垂直使用姿态时 pitch≈0, roll≈0（重力沿 -Z 轴）
                    val pitch = orientationValues[1]
                    val roll = orientationValues[2]

                    // ★ 归一化到 [-1, 1]：倾斜约 45° 时达到满偏移
                    val rawX = (roll / (Math.PI / 4.0f).toFloat()).coerceIn(-1.0f, 1.0f) * gyroMaxOffset
                    val rawY = (pitch / (Math.PI / 4.0f).toFloat()).coerceIn(-1.0f, 1.0f) * gyroMaxOffset

                    // 低通滤波平滑（避免抖动）
                    smoothGyroX = smoothGyroX * (1 - gyroSmoothing) + rawX * gyroSmoothing
                    smoothGyroY = smoothGyroY * (1 - gyroSmoothing) + rawY * gyroSmoothing

                    // ★ 通过 queueEvent 把 native 调用转到 GL 线程，避免线程竞争
                    val x = smoothGyroX
                    val y = -smoothGyroY
                    try {
                        petView.queueEvent {
                            try {
                                JniBridgePet.nativeSetViewOffset(x, y)
                            } catch (e: Exception) {
                                // 静默忽略
                            }
                        }
                    } catch (e: Exception) {
                        // 静默忽略
                    }
                } catch (e: Exception) {
                    // 旋转矩阵计算失败，忽略本次回调
                }
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) {}
        }

    /**
     * ★ 根据当前屏幕显示方向返回重映射坐标轴
     * 让 pitch/roll 在横屏竖屏下都对应"屏幕的前后/左右倾斜"
     */
    @SuppressLint("WrongConstant")
    private fun getScreenAxisMapping(): Pair<Int, Int> {
        val rotation =
            try {
                (getSystemService(WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: 0
            } catch (e: Exception) {
                0
            }
        return when (rotation) {
            android.view.Surface.ROTATION_90 ->
                Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            android.view.Surface.ROTATION_180 ->
                Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            android.view.Surface.ROTATION_270 ->
                Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // ★ 从 SharedPreferences 恢复当前模型名
        try {
            val prefs = getSharedPreferences("pet_floating_prefs", MODE_PRIVATE)
            val savedName = prefs.getString("current_model_name", null)
            if (!savedName.isNullOrBlank()) {
                // 校验该模型目录是否仍然存在
                val modelDir = File(getCustomModelDir(this), savedName)
                if (modelDir.isDirectory) {
                    currentModelName = savedName
                    Log.i(TAG, "onCreate: restored model=$savedName")
                } else {
                    // 模型目录不存在了，清除偏好
                    prefs.edit().remove("current_model_name").apply()
                    currentModelName = null
                }
            }
            // ★ 从 SharedPreferences 恢复用户上次设置的档位；首次启动用 DEFAULT_SIZE
            val savedIdx =
                prefs
                    .getInt("size_index", DEFAULT_SIZE)
                    .coerceIn(MIN_SIZE, MAX_SIZE)
            currentSizeIndex = savedIdx
            prefs.edit().putInt("size_index", savedIdx).apply()
            Log.i(TAG, "onCreate: restored size_index=$currentSizeIndex")
            // ★ v2.2 恢复 size_percent（优先使用百分比）
            val savedPercentStr = prefs.getString("size_percent", null)
            currentSizePercent =
                if (savedPercentStr != null) {
                    savedPercentStr.toFloatOrNull()?.coerceIn(0.10f, 0.50f) ?: 0.20f
                } else {
                    (savedIdx / 10.0f).coerceIn(0.10f, 0.50f)
                }
            Log.i(TAG, "onCreate: restored size_percent=$currentSizePercent")
            // ★ v2.2.4 恢复触摸穿透和位置固定状态
            touchPassthrough = prefs.getBoolean("touch_passthrough", false)
            isPinned = prefs.getBoolean("is_pinned", false)
            Log.i(TAG, "onCreate: restored touchPassthrough=$touchPassthrough isPinned=$isPinned")
        } catch (e: Exception) {
            Log.w(TAG, "onCreate: restore model failed", e)
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createFloatWindow()
        // ★★★ 延迟 2 秒启动陀螺仪，等 GL surface 完全就绪后再注册传感器
        // 避免在 GL 未就绪时调用 native 方法导致萌宠不显示
        Handler(Looper.getMainLooper()).postDelayed({
            if (isRunning) initGyroscope()
        }, 2000L)
        // ★ 启动闲置检测（每 5 秒检查一次）
        lastInteractTime = System.currentTimeMillis()
        idleHandler.postDelayed(idleCheckRunnable, 5_000L)
        // ★ 启动随机动作定时器（5-15 秒随机间隔）
        randomActionHandler.postDelayed(randomActionRunnable, 8_000L)
    }

    /** ★★★ 初始化陀螺仪视角控制（使用旋转矢量传感器获取绝对姿态） */
    private fun initGyroscope() {
        try {
            sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
            // ★ 优先 ROTATION_VECTOR（含磁场，绝对参考，不会漂移）
            // fallback 到 GAME_ROTATION_VECTOR（仅陀螺仪+加速度，不受磁场干扰但会缓慢校准）
            rotationSensor =
                sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                    ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            if (rotationSensor != null) {
                sensorManager?.registerListener(
                    sensorListener,
                    rotationSensor,
                    SensorManager.SENSOR_DELAY_GAME,
                )
                gyroEnabled = true
                // 重置平滑值，避免从上次残留值开始
                smoothGyroX = 0f
                smoothGyroY = 0f
                Log.i(TAG, "陀螺仪已注册: ${rotationSensor?.name} type=${rotationSensor?.type}")
            } else {
                Log.w(TAG, "设备无旋转矢量传感器，陀螺仪功能不可用")
            }
        } catch (e: Exception) {
            Log.w(TAG, "initGyroscope failed: ${e.message}", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatWindow() {
        Log.i(TAG, "createFloatWindow: 开始创建悬浮窗")
        petView = Live2DPetView(this)

        // ★ v2.2 优先使用屏幕分辨率百分比（重启/删除后台都保持一致）
        val (petWidth, petHeight) = sizeForPercent(this, currentSizePercent)
        Log.i(TAG, "createFloatWindow: size_percent=$currentSizePercent → ${petWidth}x${petHeight}px")

        layoutParams =
            WindowManager.LayoutParams().apply {
                width = petWidth
                height = petHeight
                type =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    }
                flags = buildWindowFlags(touchPassthrough)
                format = PixelFormat.TRANSLUCENT
                // ★ 改为左上角，避开底部 Taskbar（平板设备底部有任务栏会遮挡）
                gravity = Gravity.TOP or Gravity.START
                // ★ v2.2.4 优先从 SharedPreferences 恢复上次位置，否则使用默认位置
                val savedX = getSharedPreferences("pet_floating_prefs", MODE_PRIVATE).getInt("position_x", -1)
                val savedY = getSharedPreferences("pet_floating_prefs", MODE_PRIVATE).getInt("position_y", -1)
                if (savedX >= 0 && savedY >= 0) {
                    x = savedX
                    y = savedY
                    Log.i(TAG, "createFloatWindow: restored position=($x,$y)")
                } else {
                    // 默认左上角位置 (留 10dp 边距，避开顶部状态栏)
                    x = dpToPx(10)
                    y = dpToPx(60)
                    Log.i(TAG, "createFloatWindow: default position=($x,$y)")
                }
            }

        petView.setOnTouchListener { _, event ->
            handleTouchEvent(event)
            true
        }

        try {
            windowManager.addView(petView, layoutParams)
            Log.i(TAG, "createFloatWindow: addView 成功")
            // ★ 延迟启动引擎，确保 GL surface 完全就绪后再调用 native
            // 避免 GL 未就绪时调用 native 方法导致萌宠不显示
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    petView.startEngine()
                    Log.i(TAG, "createFloatWindow: startEngine 已调用")
                } catch (e: Exception) {
                    Log.e(TAG, "createFloatWindow: startEngine failed", e)
                }
            }, 300L)
            // ★ 确保 size 立即应用（避免前端初始化时覆盖大小）
            applySize()
            // ★ v2.2.5 创建对话气泡（默认隐藏，由歌词驱动显示）
            createSpeechBubble()
        } catch (e: Exception) {
            Log.e(TAG, "createFloatWindow: addView failed", e)
        }
    }

    /* ═══════════════════════════════════════════════════════════
     *  ★★★ v2.2.5 对话气泡功能 ★★★
     *  - 显示在萌宠右上方
     *  - 跟随萌宠位置移动
     *  - 支持自动隐藏（duration > 0 时）或持久显示（duration = 0）
     * ═══════════════════════════════════════════════════════════ */
    @SuppressLint("InflateParams")
    private fun createSpeechBubble() {
        try {
            if (speechBubbleView != null) return // 已创建
            speechBubbleHandler = android.os.Handler(android.os.Looper.getMainLooper())

            val tv = android.widget.TextView(this)
            tv.text = "你好呀你好呀"
            tv.setTextColor(android.graphics.Color.parseColor("#E8F4FF"))
            // ★ v2.2.6 歌词文字大小按屏幕分辨率比例调整（屏幕高度 × 0.012）
            //   这样不同分辨率设备上文字大小比例一致
            val (_, screenHeight) = getRealScreenSizeForContext(this)
            val textSizePx = (screenHeight * 0.012f).coerceIn(16f, 64f)
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx)
            // 阴影也按比例调整，保持清晰度
            val shadowRadius = (textSizePx * 0.18f).coerceIn(3f, 12f)
            tv.setShadowLayer(shadowRadius, 0f, 0f, android.graphics.Color.parseColor("#CC000000"))
            // ★ v2.2.5 纯文字气泡：无背景、无边框
            tv.background = null
            tv.alpha = 0f

            val params =
                WindowManager.LayoutParams().apply {
                    width = WindowManager.LayoutParams.WRAP_CONTENT
                    height = WindowManager.LayoutParams.WRAP_CONTENT
                    type =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        } else {
                            @Suppress("DEPRECATION")
                            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                        }
                    flags =
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    format = PixelFormat.TRANSLUCENT
                    gravity = Gravity.TOP or Gravity.START
                    // 初始位置（后面会通过 updateSpeechBubblePosition 更新）
                    x = layoutParams.x + layoutParams.width + dpToPx(6)
                    y = layoutParams.y + dpToPx(2)
                }

            windowManager.addView(tv, params)
            speechBubbleView = tv
            speechBubbleParams = params
            Log.i(TAG, "createSpeechBubble: 对话气泡已创建")
        } catch (e: Exception) {
            Log.e(TAG, "createSpeechBubble failed", e)
        }
    }

    /** 显示对话气泡（duration=0 持久显示，>0 自动隐藏毫秒数） */
    fun showSpeechBubble(
        text: String,
        duration: Long,
    ) {
        try {
            val tv = speechBubbleView ?: return
            val params = speechBubbleParams ?: return
            speechBubbleHandler?.post {
                try {
                    tv.text = text
                    updateSpeechBubblePosition()
                    // 入场动画
                    tv
                        .animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(280)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                        .start()
                    // 自动隐藏
                    speechBubbleHideRunnable?.let { speechBubbleHandler?.removeCallbacks(it) }
                    if (duration > 0) {
                        val hideRunnable =
                            Runnable {
                                tv
                                    .animate()
                                    .alpha(0f)
                                    .scaleX(0.8f)
                                    .scaleY(0.8f)
                                    .setDuration(220)
                                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                                    .start()
                            }
                        speechBubbleHideRunnable = hideRunnable
                        speechBubbleHandler?.postDelayed(hideRunnable, duration)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "showSpeechBubble update failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "showSpeechBubble failed", e)
        }
    }

    /** 更新对话气泡位置（跟随萌宠位置） */
    private fun updateSpeechBubblePosition() {
        try {
            val tv = speechBubbleView ?: return
            val params = speechBubbleParams ?: return
            // ★ v2.2.6 气泡位于萌宠右上方：x 紧贴萌宠右侧，y 略向下
            // ★ v2.2.7 歌词位置单独向左移动 100 像素点（用户要求）
            //   原位置：layoutParams.x + layoutParams.width + dpToPx(6)
            //   新位置：再减去 100 px，让歌词更靠近萌宠左侧
            params.x = layoutParams.x + layoutParams.width + dpToPx(6) - 100
            params.y = layoutParams.y + dpToPx(2)
            windowManager.updateViewLayout(tv, params)
        } catch (e: Exception) {
            // 静默失败：拖动时频繁更新可能偶发异常
        }
    }

    /** ★ v2.2.5 公共方法：显示对话气泡（持久显示，直到调用 hide 或更新为空） */
    fun showSpeechBubblePublic(text: String) {
        try {
            val tv = speechBubbleView ?: return
            speechBubbleHandler?.post {
                try {
                    tv.text = text
                    updateSpeechBubblePosition()
                    if (tv.alpha < 1f) {
                        tv
                            .animate()
                            .alpha(1f)
                            .setDuration(200)
                            .start()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "showSpeechBubblePublic failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "showSpeechBubblePublic outer failed", e)
        }
    }

    /** ★ v2.2.7 按字索引显示歌词：显示前 charIdx 个字，新字出现时做丝滑弹性缩放动画
     *   使用 ValueAnimator + RelativeSizeSpan 实现单字从小到大的弹性放大
     *   对应 cubic-bezier(0.34, 1.56, 0.64, 1) 弹性曲线（OvershootInterpolator）
     *   动画时长 220ms，每帧重建 RelativeSizeSpan 实现 size 平滑过渡
     */
    fun showSpeechBubbleWithCharIdx(
        fullText: String,
        charIdx: Int,
    ) {
        try {
            val tv = speechBubbleView ?: return
            speechBubbleHandler?.post {
                try {
                    // ★ 计算要显示的子串
                    val showCount = charIdx.coerceIn(0, fullText.length)
                    val displayText = if (showCount <= 0) " " else fullText.substring(0, showCount)
                    // ★ 检测是否新增了字符（需要触发新字动画）
                    val oldLen = tv.text.length
                    val newLen = displayText.length

                    // ★ v2.2.7 取消之前未完成的字符动画
                    charAnimator?.cancel()
                    charAnimator = null

                    // ★ 构造 SpannableStringBuilder
                    val spannable = android.text.SpannableStringBuilder(displayText)
                    animSpannable = spannable

                    // 先设置文本，确保文字立即显示
                    tv.text = spannable

                    if (newLen > oldLen && newLen > 0) {
                        // ★ 新增了字符，对该字符做丝滑弹性缩放动画
                        animCharStart = newLen - 1
                        animCharEnd = newLen

                        // 启动 ValueAnimator (OvershootInterpolator 模拟 cubic-bezier 弹性曲线)
                        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
                        animator.duration = 220
                        animator.interpolator = android.view.animation.OvershootInterpolator(2.5f)
                        animator.addUpdateListener { anim ->
                            try {
                                val progress = anim.animatedValue as Float
                                // size 从 0.3 弹性增长到 1.0
                                // OvershootInterpolator 会让 progress 超过 1.0 再回弹，
                                // 自动实现"放大后回弹"的弹性效果
                                val scale = 0.3f + (1.0f - 0.3f) * progress

                                // 移除旧的 RelativeSizeSpan
                                animSpannable
                                    ?.getSpans<android.text.style.RelativeSizeSpan>(
                                        animCharStart,
                                        animCharEnd,
                                        android.text.style.RelativeSizeSpan::class.java,
                                    )?.forEach {
                                        animSpannable?.removeSpan(it)
                                    }
                                // 添加新的 RelativeSizeSpan（当前帧的 scale 值）
                                animSpannable?.setSpan(
                                    android.text.style.RelativeSizeSpan(scale),
                                    animCharStart,
                                    animCharEnd,
                                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                                )
                                // 加粗新字（保持视觉效果）
                                animSpannable?.setSpan(
                                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                    animCharStart,
                                    animCharEnd,
                                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                                )
                                // 触发 TextView 重绘 spans
                                tv.text = animSpannable
                            } catch (e: Exception) {
                                // 静默：动画过程中可能偶发异常
                            }
                        }
                        animator.addListener(
                            object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    charAnimator = null
                                }
                            },
                        )
                        animator.start()
                        charAnimator = animator
                    }

                    updateSpeechBubblePosition()
                    if (tv.alpha < 1f) {
                        tv
                            .animate()
                            .alpha(1f)
                            .setDuration(200)
                            .start()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "showSpeechBubbleWithCharIdx failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "showSpeechBubbleWithCharIdx outer failed", e)
        }
    }

    /** ★ v2.2.5 公共方法：隐藏对话气泡 */
    fun hideSpeechBubblePublic() {
        try {
            val tv = speechBubbleView ?: return
            speechBubbleHandler?.post {
                try {
                    tv
                        .animate()
                        .alpha(0f)
                        .setDuration(180)
                        .start()
                } catch (e: Exception) {
                    Log.w(TAG, "hideSpeechBubblePublic failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "hideSpeechBubblePublic outer failed", e)
        }
    }

    /** 实时应用当前大小（v2.2 优先使用屏幕分辨率百分比） */
    private fun applySize() {
        if (!::petView.isInitialized) return
        val (petWidth, petHeight) = sizeForPercent(this, currentSizePercent)
        layoutParams.width = petWidth
        layoutParams.height = petHeight
        try {
            windowManager.updateViewLayout(petView, layoutParams)
        } catch (e: Exception) {
            Log.w(TAG, "applySize: updateViewLayout failed", e)
        }
    }

    /** 实时切换触摸穿透模式 */
    private fun applyTouchPassthrough() {
        if (!::petView.isInitialized) return
        layoutParams.flags = buildWindowFlags(touchPassthrough)
        try {
            windowManager.updateViewLayout(petView, layoutParams)
        } catch (e: Exception) {
            Log.w(TAG, "applyTouchPassthrough: updateViewLayout failed", e)
        }
    }

    /** 根据触摸穿透模式构造窗口 flags */
    private fun buildWindowFlags(passthrough: Boolean): Int {
        val base =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        return if (passthrough) {
            // 触摸穿透: 整个窗口不接收触摸事件,只显示
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            // 正常交互: 只处理窗口内的触摸,其他区域穿透到下层
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                touchDownTime = System.currentTimeMillis()
                isDragging = false
                // ★ 拖动检测初始化
                dragStartTime = touchDownTime
                dragDistanceAccum = 0f
                lastMoveX = event.rawX
                lastMoveY = event.rawY
                lastMoveTime = touchDownTime
                petView.onTouchBegan(event.x, event.y)

                // ★★ 连续快速点击检测：在 ACTION_DOWN 时立即触发（不等 UP）
                // - 睡眠状态下点一下唤醒
                // - 晕眩状态下继续点击 → 黑化
                // - 连续 ≥9 次快速点击 → 立即晕眩
                handleRapidClick(touchDownTime)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // ★ 位置固定时，禁止拖拽（只允许点击检测）
                if (isPinned) {
                    petView.onTouchMoved(event.x, event.y)
                    return true
                }
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                // ★ 累计拖动距离 + 计算瞬时速度（用于快速拖动检测）
                val now = System.currentTimeMillis()
                val segDx = event.rawX - lastMoveX
                val segDy = event.rawY - lastMoveY
                val segDist = Math.sqrt((segDx * segDx + segDy * segDy).toDouble()).toFloat()
                val segDt = (now - lastMoveTime).coerceAtLeast(1L)
                dragDistanceAccum += segDist
                lastMoveX = event.rawX
                lastMoveY = event.rawY
                lastMoveTime = now

                if (Math.abs(dx) > clickDistanceThreshold || Math.abs(dy) > clickDistanceThreshold) {
                    isDragging = true
                    val newX = initialX + dx.toInt()
                    // ★ v2.2 修复横屏触摸 Y 反向 bug
                    //   旧代码: newY = initialY - dy (适用于 gravity=BOTTOM，y 向上为正)
                    //   当前布局: gravity=TOP or START，y 向下为正（与 dy 同向）
                    //   所以应该用 + 而不是 -
                    val newY = initialY + dy.toInt()
                    // ★ 屏幕边缘限制: 使用真实屏幕尺寸
                    val (screenW, screenH) = getRealScreenSize()
                    val halfW = layoutParams.width / 2
                    val halfH = layoutParams.height / 2
                    val minX = -halfW
                    val maxX = screenW - halfW
                    val minY = -halfH
                    val maxY = screenH - halfH
                    var fixedX = newX
                    var fixedY = newY
                    if (fixedX < minX) fixedX = minX
                    if (fixedX > maxX) fixedX = maxX
                    if (fixedY < minY) fixedY = minY
                    if (fixedY > maxY) fixedY = maxY
                    layoutParams.x = fixedX
                    layoutParams.y = fixedY
                    try {
                        windowManager.updateViewLayout(petView, layoutParams)
                        // ★ v2.2.5 拖动时实时更新对话气泡位置
                        updateSpeechBubblePosition()
                    } catch (e: Exception) {
                        Log.w(TAG, "updateViewLayout failed", e)
                    }
                    petView.onTouchMoved(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                petView.onTouchEnded(event.x, event.y)
                val upTime = System.currentTimeMillis()

                // ★ 拖动结束：判断是否为快速拖动 → 触发晕眩
                if (isDragging) {
                    applySize()
                    isDragging = false
                    val dragDuration = (upTime - dragStartTime).coerceAtLeast(1L)
                    val avgSpeed = dragDistanceAccum / dragDuration
                    // ★ 严格阈值：必须快速拖出且距离过远才触发
                    if (dragDistanceAccum > fastDragDistanceThreshold &&
                        avgSpeed > fastDragSpeedThreshold
                    ) {
                        Log.i(TAG, "快速拖动: dist=$dragDistanceAccum avgSpeed=$avgSpeed → 晕眩")
                        setMood(PetMood.DIZZY, "晕")
                    } else {
                        // 普通拖动也算交互，重置闲置计时
                        onUserInteracted()
                    }
                    // ★ v2.2.4 持久化当前位置，重启后可恢复
                    try {
                        getSharedPreferences("pet_floating_prefs", MODE_PRIVATE)
                            .edit()
                            .putInt("position_x", layoutParams.x)
                            .putInt("position_y", layoutParams.y)
                            .apply()
                        Log.i(TAG, "拖动结束: 已保存位置=(${layoutParams.x},${layoutParams.y})")
                    } catch (e: Exception) {
                        Log.w(TAG, "拖动结束: 保存位置失败", e)
                    }
                    // ★ v2.2.5 更新对话气泡位置（跟随萌宠移动）
                    updateSpeechBubblePosition()
                    return true
                }

                // 单击已在 ACTION_DOWN 处理，UP 时不重复触发
                return true
            }
        }
        return false
    }

    /**
     * ★★ 处理连续快速点击（在 ACTION_DOWN 时调用，立即触发）
     * - 睡眠状态：唤醒，不计入点击次数
     * - 晕眩状态：继续点击 → 黑化
     * - 第1次点击：延时 1.2 秒确认，若期间无后续点击 → 触发"抱抱"
     * - 第2-8次点击：取消抱抱，继续累计
     * - 连续 ≥9 次快速点击 → 立即晕眩（取消抱抱）
     */
    private fun handleRapidClick(now: Long) {
        onUserInteracted()

        // 睡眠状态：唤醒即可，不计入点击
        if (currentMood == PetMood.SLEEPING) {
            rapidClickCount = 0
            singleClickHandler.removeCallbacks(singleClickRunnable)
            return
        }

        // 晕眩状态：继续点击 → 黑化
        if (currentMood == PetMood.DIZZY) {
            singleClickHandler.removeCallbacks(singleClickRunnable)
            Log.i(TAG, "晕眩状态下继续点击 → 黑化")
            setMood(PetMood.DARKIFIED, "黑化")
            rapidClickCount = 0
            return
        }

        // 超时重置计数
        if (now - lastClickTime > clickResetMs) {
            rapidClickCount = 0
        }
        rapidClickCount++
        lastClickTime = now
        Log.d(TAG, "快速点击 count=$rapidClickCount mood=$currentMood")

        // 取消之前的单击延时确认（因为有了新的点击）
        singleClickHandler.removeCallbacks(singleClickRunnable)

        when {
            rapidClickCount == 1 && currentMood == PetMood.NORMAL -> {
                // 第1次点击：延时 1.2 秒确认是否为单击
                singleClickHandler.postDelayed(singleClickRunnable, clickResetMs)
            }
            rapidClickCount >= dizzyTriggerClickCount && currentMood == PetMood.NORMAL -> {
                // 连续 ≥9 次 → 立即晕眩（抱抱已被取消）
                Log.i(TAG, "连续 $rapidClickCount 次快速点击 → 立即晕眩")
                setMood(PetMood.DIZZY, "晕")
                rapidClickCount = 0
            }
            // 第2-8次：仅累计，不触发任何动作（避免叠加）
        }
    }

    /** 设置情绪状态并触发表情，同时启动自动恢复定时器 */
    private fun setMood(
        mood: PetMood,
        expression: String,
    ) {
        currentMood = mood
        moodResetHandler.removeCallbacks(moodResetRunnable)
        val expire =
            when (mood) {
                PetMood.DIZZY -> dizzyExpireMs
                PetMood.DARKIFIED -> darkifiedExpireMs
                else -> 0L
            }
        // 触发表情（在 GL 线程执行避免竞争）
        try {
            if (::petView.isInitialized) {
                petView.queueEvent {
                    try {
                        JniBridgePet.nativeTriggerExpression(expression)
                    } catch (e: Exception) {
                        Log.w(TAG, "setMood triggerExpression failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "setMood queueEvent failed", e)
        }
        // 启动自动恢复（睡眠状态不自动恢复，等用户触碰唤醒）
        if (expire > 0 && mood != PetMood.SLEEPING) {
            moodResetHandler.postDelayed(moodResetRunnable, expire)
        }
        Log.i(TAG, "情绪切换 → $mood (表情=$expression, ${expire}ms 后恢复)")
    }

    /**
     * ★★★ 主程序推送音乐状态
     * - 一首歌只触发一次动作（呲牙/嘟嘴/吐舌 三选一）
     * - 切歌时如果新歌未触发过，会触发一次
     */
    fun handleMusicState(
        playing: Boolean,
        title: String,
        bpm: Int,
    ) {
        val wasPlaying = musicPlaying
        val oldTitle = musicTitle
        musicPlaying = playing
        musicTitle = title
        Log.d(TAG, "notifyMusicState: playing=$playing title=$title")

        // ★ 切歌时重置触发标记：新歌可以再次触发一次动作
        if (title != oldTitle && title.isNotEmpty()) {
            lastSongTriggered = ""
        }

        // ★ 开始播放 或 切到新歌 且 这首歌还没触发过 → 触发一次
        if (playing && title.isNotEmpty() && lastSongTriggered != title) {
            if (currentMood == PetMood.NORMAL) {
                // 一首歌只触发一次：呲牙/嘟嘴/吐舌 三选一
                val exp = listOf("呲牙", "嘟嘴", "吐舌").random()
                try {
                    if (::petView.isInitialized) {
                        petView.queueEvent {
                            try {
                                JniBridgePet.nativeTriggerExpression(exp)
                            } catch (e: Exception) {
                            }
                        }
                        Log.i(TAG, "音乐动作（$title）→ $exp")
                    }
                } catch (e: Exception) {
                }
                lastSongTriggered = title
            }
        }
    }

    /** 节拍事件：保留接口兼容（不再触发动作） */
    fun handleBeat(intensity: Float) {
        // 不再依赖节拍触发
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "桌面萌宠",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "桌面萌宠悬浮窗保活"
                    setShowBadge(false)
                }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle("桌面萌宠")
            .setContentText("萌宠正在桌面上玩耍")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    /** 获取真实屏幕尺寸(包含状态栏/导航栏) */
    @SuppressLint("Deprecated")
    private fun getRealScreenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay

            @Suppress("DEPRECATION")
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            Pair(point.x, point.y)
        }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        // ★ 修复：检查桌面萌宠开关，若已关闭则停止自身，避免系统自动重启后再次显示
        val petEnabled =
            try {
                com.mineradio.app.wallpaper.WallpaperManager
                    .get(applicationContext)
                    .getDesktopPetEnabled()
            } catch (_: Exception) {
                true
            }
        if (!petEnabled) {
            Log.i(TAG, "onStartCommand: pet disabled, stop self and skip sticky restart")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ★ 用户从最近任务列表划掉 APP 时触发：自动重启 service，让萌宠不被杀
    // ★ 修复：若用户已关闭萌宠开关，则不调度重启，避免"关闭后被自动拉起"
    override fun onTaskRemoved(rootIntent: Intent?) {
        val petEnabled =
            try {
                com.mineradio.app.wallpaper.WallpaperManager
                    .get(applicationContext)
                    .getDesktopPetEnabled()
            } catch (_: Exception) {
                true
            }
        if (!petEnabled) {
            Log.i(TAG, "onTaskRemoved: pet disabled, skip restart schedule")
            super.onTaskRemoved(rootIntent)
            return
        }
        Log.i(TAG, "onTaskRemoved: schedule restart")
        try {
            val restartIntent = Intent(applicationContext, PetFloatingService::class.java)
            val pendingIntent =
                android.app.PendingIntent.getService(
                    this,
                    1,
                    restartIntent,
                    android.app.PendingIntent.FLAG_ONE_SHOT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.set(
                android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 1000,
                pendingIntent,
            )
        } catch (e: Exception) {
            Log.w(TAG, "onTaskRemoved: schedule restart failed", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        // ★ 注销陀螺仪传感器，避免内存泄漏
        try {
            sensorManager?.unregisterListener(sensorListener)
            gyroEnabled = false
        } catch (e: Exception) {
            Log.w(TAG, "unregister sensor failed", e)
        }
        // ★ 清理闲置检测、随机动作、情绪、单击确认定时器
        idleHandler.removeCallbacks(idleCheckRunnable)
        randomActionHandler.removeCallbacks(randomActionRunnable)
        moodResetHandler.removeCallbacks(moodResetRunnable)
        singleClickHandler.removeCallbacks(singleClickRunnable)
        try {
            if (::petView.isInitialized) {
                // ★ 修复闪退：先在 GL 线程释放 native 资源，再从 WindowManager 移除
                // 避免主线程直接调用 nativeOnStop 引起 GL 资源竞争
                try {
                    petView.queueEvent {
                        try {
                            JniBridgePet.nativeOnStop()
                        } catch (e: Exception) {
                            Log.w(TAG, "nativeOnStop (GL thread) failed", e)
                        }
                    }
                    // 等待 GL 线程执行完毕（最多 300ms）
                    Thread.sleep(300)
                } catch (e: Exception) {
                    Log.w(TAG, "queueEvent nativeOnStop failed", e)
                }
                windowManager.removeView(petView)
            }
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy removeView failed", e)
        }
        // ★ v2.2.5 移除对话气泡
        try {
            // ★ v2.2.7 取消未完成的单字动画，避免内存泄漏
            charAnimator?.cancel()
            charAnimator = null
            animSpannable = null

            speechBubbleHideRunnable?.let { speechBubbleHandler?.removeCallbacks(it) }
            speechBubbleView?.let { bv ->
                if (bv.isAttachedToWindow) windowManager.removeView(bv)
            }
            speechBubbleView = null
            speechBubbleParams = null
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy removeSpeechBubble failed", e)
        }
        super.onDestroy()
    }
}
