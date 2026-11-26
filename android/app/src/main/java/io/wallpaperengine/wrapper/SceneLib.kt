package io.wallpaperengine.wrapper

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Point
import android.graphics.PointF
import io.wallpaperengine.weutil.VirtualFileRange
import io.wallpaperengine.weutil.WallpaperInfoSparse

/**
 * Wallpaper Engine 场景库的 JNI 封装（libscenejni.so）。
 *
 * 由桌面源码的 SceneLib.java 等价转写为 Kotlin：
 * 不是为了风格，而是这台设备上 AGP 8.13 + Kotlin 2.x 的混编 stub 生成
 * 偶发漏掉 io 包，导致 Kotlin 报 Unresolved reference 'wallpaperengine'。
 * 同模块 Kotlin↔Java 混编在这台机器上不可靠，所以 JNI 封装直接用 Kotlin 写，
 * 编译顺序问题就不存在了。native 方法签名与 Java 版逐一对齐，so 库不变。
 */
object SceneLib {
    const val TAG = "SceneLib"

    @JvmStatic
    var isInitialized = false
        private set

    @JvmStatic
    var sharedAssetManager: AssetManager? = null
        private set

    init {
        try {
            // ★ 设置时区为 Asia/Shanghai，确保 native 层 localtime() 使用正确时区
            //   不设置的话 native 层可能使用 UTC，导致场景壁纸时间显示不对（日期对但时间差8小时）
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            System.setProperty("user.timezone", "Asia/Shanghai")
            // ★ 同时设置 TZ 环境变量，影响 native C++ 层的 localtime()（CST-8 = 中国标准时间 UTC+8）
            try {
                android.system.Os.setenv("TZ", "CST-8", true)
            } catch (ignore: Throwable) {
            }
            System.loadLibrary("scenejni")
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed to load scenejni", t)
        }
    }

    @JvmStatic
    fun setInitialized(z: Boolean) {
        isInitialized = z
    }

    @JvmStatic
    fun setSharedAssetManager(assetManager: AssetManager?) {
        sharedAssetManager = assetManager
    }

    private external fun init(str: String, str2: String, assetManager: AssetManager)

    @Synchronized
    fun initLibrary(context: Context) {
        if (isInitialized) {
            return
        }
        try {
            sharedAssetManager = context.assets
            val filesDir = context.filesDir.absolutePath
            // ★ 使用当前系统时间戳（秒级）替代硬编码 buildTime
            //   原版使用硬编码的 APK 构建时间，会导致 V8 引擎 Date 对象时间不随系统时间更新
            //   改用当前时间戳后，V8 的 new Date() 能正确获取当前系统时间
            val currentTimeSec = System.currentTimeMillis() / 1000L
            init(filesDir, currentTimeSec.toString(), sharedAssetManager!!)
            isInitialized = true
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "initLibrary failed", t)
        }
    }

    external fun initContext(context: Context): Int

    external fun destroyContext(i: Int)

    external fun initScene(i: Int, str: String)

    external fun shutdownScene(i: Int)

    external fun cancelLoadingScene(i: Int)

    external fun updateScene(i: Int)

    external fun resizeScene(i: Int, i2: Int, i3: Int)

    external fun sendTouchInput(i: Int, z: Boolean, f: Float, f2: Float)

    external fun sendGravityInput(i: Int, f: Float, f2: Float, f3: Float)

    external fun sendAudioData(i: Int, fArr: FloatArray?)

    external fun sendForceInput(i: Int, f: Float, f2: Float)

    external fun sendFreeAlignmentXForm(i: Int, fArr: FloatArray?)

    external fun sendNormalizedParallaxOffset(i: Int, z: Boolean, f: Float, f2: Float, f3: Float, f4: Float)

    external fun getSceneProperties(i: Int): String?

    external fun applySceneProperties(i: Int, str: String?)

    external fun getSceneFeatureFlags(i: Int): Int

    external fun getSceneCanvasSize(i: Int, pointF: PointF?): Boolean

    external fun getWallpaperType(str: String?): String?

    external fun getWallpaperInfoSparse(str: String?): WallpaperInfoSparse?

    external fun getWallpaperInfoPreviewBitmap(str: String?): ByteArray?

    external fun getWallpaperResolution(str: String?): Point?

    external fun getWallpaperProjectString(str: String?, str2: String?): String?

    external fun getWallpaperFileVirtualOffset(str: String?): VirtualFileRange?

    external fun isWallpaperVersionValid(str: String?): Boolean

    external fun enumerateWallpapers(): WallpaperInfoSparse?

    external fun getLocalization(str: String?): String?

    external fun getUserLocalizations(str: String?): String?

    external fun setLanguage(str: String?)

    external fun setLogToFileEnabled(z: Boolean)
}
