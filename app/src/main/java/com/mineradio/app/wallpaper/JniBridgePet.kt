package com.mineradio.app.wallpaper

import android.app.Activity
import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Live2D JNI 桥接（Kotlin 侧）
 * 对应 C++ 侧 JniBridgeC.cpp 的 native 方法声明
 *
 * 自定义模型支持：
 *   LoadFile 先读 filesDir/custom_pet_model（自定义模型），
 *   再读 assets（默认模型）。
 *   GetAssetList 把自定义模型目录排在前面，sceneIndex=0 优先加载自定义模型。
 */
object JniBridgePet {
    private const val TAG = "JniBridgePet"
    private const val LIBRARY_NAME = "live2d_pet"
    private const val CUSTOM_MODEL_DIR_NAME = "custom_pet_model"
    private const val ASSETS_LIVE2D_ROOT = "live2d"

    init {
        System.loadLibrary(LIBRARY_NAME)
    }

    // Native methods
    @JvmStatic external fun nativeOnStart()

    @JvmStatic external fun nativeOnPause()

    @JvmStatic external fun nativeOnStop()

    @JvmStatic external fun nativeOnDestroy()

    @JvmStatic external fun nativeOnSurfaceCreated()

    @JvmStatic external fun nativeOnSurfaceChanged(
        width: Int,
        height: Int,
    )

    @JvmStatic external fun nativeOnDrawFrame()

    @JvmStatic external fun nativeOnTouchesBegan(
        pointX: Float,
        pointY: Float,
    )

    @JvmStatic external fun nativeOnTouchesEnded(
        pointX: Float,
        pointY: Float,
    )

    @JvmStatic external fun nativeOnTouchesMoved(
        pointX: Float,
        pointY: Float,
    )

    // ★ 获取当前模型所有表情名称（用 \n 分隔）
    @JvmStatic external fun nativeGetExpressionNames(): String

    // ★ 触发指定表情
    @JvmStatic external fun nativeTriggerExpression(name: String)

    // ★★★ 陀螺仪视角控制：设置眼睛视角偏移，x/y 范围 [-1, 1]
    @JvmStatic external fun nativeSetViewOffset(
        x: Float,
        y: Float,
    )

    // Java methods called from C++ via JNI
    @JvmStatic
    private var context: Context? = null

    @JvmStatic
    private var activityInstance: Activity? = null

    @JvmStatic
    fun SetContext(context: Context) {
        JniBridgePet.context = context
    }

    @JvmStatic
    fun SetActivityInstance(activity: Activity) {
        activityInstance = activity
    }

    /**
     * 获取目录下文件/子目录列表
     * 自定义模型目录排在前面（filesDir/custom_pet_model），assets 排在后面
     * 这样 native 端排序后 sceneIndex=0 优先加载自定义模型
     *
     * ★ v2.2.7 修复：当 currentModelName 有值时，优先返回该模型目录
     *   避免多个模型目录时加载到错误的模型（如内置 desktop-pet 而非用户导入的模型）
     */
    @JvmStatic
    fun GetAssetList(dirPath: String): Array<String> {
        val result = LinkedHashSet<String>()

        // ★ v2.2.7 获取当前指定的模型名（如果有）
        val preferredModelName: String? =
            try {
                com.mineradio.app.wallpaper.PetFloatingService
                    .getCurrentModelDirName()
            } catch (e: Exception) {
                null
            }

        // 1. 先扫描 filesDir/custom_pet_model（自定义模型优先）
        try {
            val ctx = context ?: return result.toTypedArray()
            val customDir = File(ctx.filesDir, CUSTOM_MODEL_DIR_NAME)
            if (dirPath == ASSETS_LIVE2D_ROOT) {
                // 列出自定义模型目录下的所有子目录（模型名）
                if (customDir.isDirectory) {
                    // ★ v2.2.7 优先添加 currentModelName 对应的目录
                    if (!preferredModelName.isNullOrBlank()) {
                        val preferredDir = File(customDir, preferredModelName)
                        if (preferredDir.isDirectory) {
                            result.add(preferredDir.name)
                            Log.i(TAG, "GetAssetList: 优先模型目录: ${preferredDir.name}")
                        }
                    }
                    // 添加其他目录
                    customDir.listFiles()?.forEach { f ->
                        if (f.isDirectory && f.name != preferredModelName) {
                            result.add(f.name)
                        }
                    }
                }
            } else if (dirPath.startsWith("$ASSETS_LIVE2D_ROOT/")) {
                // 列出子目录中的文件
                val subName = dirPath.substringAfter("$ASSETS_LIVE2D_ROOT/")
                val subDir = File(customDir, subName)
                if (subDir.isDirectory) {
                    subDir.listFiles()?.forEach { f ->
                        result.add(f.name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GetAssetList custom dir failed: ${e.message}")
        }

        // 2. 再扫描 assets（默认模型）
        try {
            context?.assets?.list(dirPath)?.let { assets ->
                assets.forEach { result.add(it) }
            }
        } catch (e: IOException) {
            // assets 中没有该路径，忽略
        }

        return result.toTypedArray()
    }

    /**
     * 加载文件内容
     * 先读 filesDir/custom_pet_model（自定义模型），失败再读 assets
     */
    @JvmStatic
    fun LoadFile(filePath: String): ByteArray? {
        var fileData: InputStream? = null
        return try {
            // 1. 先尝试 filesDir/custom_pet_model
            val ctx = context
            if (ctx != null && filePath.startsWith("$ASSETS_LIVE2D_ROOT/")) {
                val relativePath = filePath.substringAfter("$ASSETS_LIVE2D_ROOT/")
                val customFile = File(ctx.filesDir, "$CUSTOM_MODEL_DIR_NAME/$relativePath")
                if (customFile.exists() && customFile.isFile) {
                    Log.d(TAG, "LoadFile from custom: $filePath")
                    return customFile.readBytes()
                }
            }
            // 2. 回退到 assets
            // ★ 修复闪退：用 readBytes() 代替 available()+read()
            // 因为 available() 对压缩存储的 assets 返回值可能小于实际文件大小，
            // 导致 buffer 被截断，JSON 解析失败引发空指针崩溃
            fileData = context?.assets?.open(filePath)
            val fileBuffer = fileData!!.readBytes()
            Log.d(TAG, "LoadFile from assets: $filePath size=${fileBuffer.size}")
            fileBuffer
        } catch (e: Exception) {
            Log.w(TAG, "LoadFile failed: $filePath, ${e.message}")
            null
        } finally {
            try {
                fileData?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    @JvmStatic
    fun MoveTaskToBack() {
        activityInstance?.moveTaskToBack(true)
    }
}
