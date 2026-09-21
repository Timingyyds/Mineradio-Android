package com.mineradio.app.wallpaper

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.io.File
import java.io.IOException
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 壁纸服务
 * 移植自 project_lw 的 LWService，适配 Media3
 *
 * 支持：
 * - HTML 壁纸（WebView 渲染）
 * - 视频壁纸（OpenGL ES + ExoPlayer 渲染）
 * - 图片壁纸（系统 WallpaperManager 直接设置）
 */
@UnstableApi
class LWService : WallpaperService() {
    companion object {
        private const val TAG = "LWService"
        var nextEngineId = 1

        /**
         * ★ LWService 是否正在运行的静态标志位
         *
         * 用途：WallpaperKeepAliveService 在被 ZUI 等系统清理器杀死后，
         * 系统 1 秒内自动重启 KeepAlive 服务，但 LWService 需要 11 秒才重启（系统强制延迟）。
         *
         * KeepAlive 服务启动时检查此标志位：
         * - false（进程刚被杀重启，静态变量重置）→ 主动调用 setWallpaperComponent 强制立即重新绑定 LWService
         * - true（LWService 正常运行）→ 跳过，避免反复重置导致壁纸闪烁
         */
        @Volatile
        @JvmStatic
        var isRunning: Boolean = false

        // ===== 桌面萌宠 PetWallpaperEngine 常量 =====
        /** 萌宠引擎日志 TAG */
        const val PET_TAG = "PetEngine"

        /** 帧率 20fps，约 50ms 一帧 */
        const val PET_FRAME_INTERVAL_MS = 50L

        /** 跳跃最大高度（px） */
        const val PET_JUMP_MAX_HEIGHT = 180f

        /** 跳跃持续帧数 */
        const val PET_JUMP_FRAMES = 18

        /** 待机时长（帧）后切换到新动作 */
        const val PET_IDLE_FRAMES_BEFORE_ACTION = 40

        /** 走路速度（px/帧） */
        const val PET_WALK_SPEED = 4f
    }

    /** 桌面萌宠动作类型（定义在 LWService 类级别，inner class 中不能声明 enum） */
    enum class PetAction {
        IDLE,
        BLINK,
        SHAKE_HEAD,
        NOD,
        WIGGLE,
        WALK,
        JUMP,
        DRAG,
    }

    private val receiver = WallpaperReceiver()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "onCreate: ThreadId: ${Thread.currentThread().id}")
        val intentFilter = IntentFilter()
        intentFilter.addAction(WallpaperReceiver.ACTION_TEST)
        intentFilter.addAction(WallpaperReceiver.ACTION_REFRESH)
        try {
            // 使用 ContextCompat 注册非导出接收器（兼容 Android 14+）
            androidx.core.content.ContextCompat.registerReceiver(
                applicationContext,
                receiver,
                intentFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: registerReceiver failed", e)
        }
    }

    override fun onCreateEngine(): Engine? {
        // 启动保活前台服务（确保进程不会被系统杀掉，壁纸能在后台持续运行）
        WallpaperKeepAliveService.start(this)

        val manager = WallpaperManager.get(this)

        // ★ 桌面萌宠已改为悬浮窗方式（PetFloatingService），不再使用壁纸引擎。
        //   这里始终返回用户选择的壁纸，避免萌宠开关覆盖用户壁纸设置。

        val wallpaperObj =
            manager.getCurrentWallpaper()
                ?: return EmptyWallpaperEngine()

        return when (wallpaperObj.wallpaperType) {
            WallpaperEntity.TYPE_HTML -> WebWallpaperEngine(this, wallpaperObj)
            WallpaperEntity.TYPE_VIDEO -> GLWallpaperEngine(this, wallpaperObj)
            WallpaperEntity.TYPE_IMAGE -> ImageWallpaperEngine(this, wallpaperObj)
            WallpaperEntity.TYPE_MPKG_WEBGL ->
                // ★ 视频型 MPKG（内含 mp4/webm/mkv 等）用 SceneLib 无法渲染 → 黑屏
                //   必须走视频引擎（提取视频 + ExoPlayer 播放），与 Scene 场景壁纸区分
                if (isVideoMpkg(wallpaperObj)) {
                    VideoMpkgWallpaperEngine(this, wallpaperObj)
                } else {
                    NativeSceneWallpaperEngine(this, wallpaperObj)
                }
            else -> EmptyWallpaperEngine()
        }
    }

    /**
     * 检测 MPKG 是否为视频型壁纸（条目表含视频文件）
     * 快速解析文件头 + 条目表（几 KB），不阻塞
     */
    private fun isVideoMpkg(wallpaper: WallpaperEntity): Boolean {
        return try {
            val f = java.io.File(wallpaper.getRealPath(this))
            if (!f.exists() || f.length() <= 0) {
                Log.e(TAG, "isVideoMpkg: file not exists: ${f.absolutePath}")
                return false
            }
            val parser = MpkgParser(f)
            if (!parser.parse()) return false
            val names = parser.listEntries().map { it.name.lowercase() }
            names.any {
                it.endsWith(".mp4") ||
                    it.endsWith(".webm") ||
                    it.endsWith(".mkv") ||
                    it.endsWith(".mov") ||
                    it.endsWith(".m4v") ||
                    it.endsWith(".avi")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "isVideoMpkg: check failed", e)
            false
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: ")
        isRunning = false
        try {
            applicationContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy: unregisterReceiver failed", e)
        }
        super.onDestroy()
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 空壁纸引擎
     */
    inner class EmptyWallpaperEngine : Engine() {
        private val myId: Int = nextEngineId

        init {
            nextEngineId++
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 图片壁纸引擎
     * 通过 BitmapFactory 解码图片后，由系统的 WallpaperManager 显示
     */
    inner class ImageWallpaperEngine(
        private val context: Context,
        private val wallpaper: WallpaperEntity,
    ) : Engine() {
        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // 图片壁纸依赖系统 WallpaperManager，这里仅占位
            setTouchEventsEnabled(false)
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 视频（OpenGL）壁纸引擎
     * 使用 ExoPlayer + GLSurfaceView 渲染视频壁纸
     */
    @SuppressLint("ClickableViewAccessibility")
    internal inner class GLWallpaperEngine(
        private val context: Context,
        private val wallpaper: WallpaperEntity,
    ) : Engine() {
        private var glSurfaceView: GLWallpaperSurfaceView? = null
        private var exoPlayer: ExoPlayer? = null
        private var trackSelector: DefaultTrackSelector? = null
        private var renderer: GLWallpaperRenderer? = null
        private var allowSlide = false
        private var videoRotation = 0
        private var videoWidth = 0
        private var videoHeight = 0
        private var progress: Long = 0

        /**
         * 自定义 GLSurfaceView，将 Surface 替换为 WallpaperEngine 的 Surface
         */
        private inner class GLWallpaperSurfaceView(
            context: Context?,
        ) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder = surfaceHolder

            fun onDestroy() {
                super.onDetachedFromWindow()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            allowSlide = WallpaperManager.get(context).isSlideWallpaperEnabled()
        }

        override fun onSurfaceCreated(surfaceHolder: SurfaceHolder) {
            super.onSurfaceCreated(surfaceHolder)
            createGLSurfaceView()
            val width = surfaceHolder.surfaceFrame.width()
            val height = surfaceHolder.surfaceFrame.height()
            renderer!!.setScreenSize(width, height)
            startPlayer()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (renderer != null) {
                if (visible) {
                    allowSlide = WallpaperManager.get(context).isSlideWallpaperEnabled()
                    glSurfaceView!!.onResume()
                    startPlayer()
                } else {
                    stopPlayer()
                    glSurfaceView!!.onPause()
                    allowSlide = false
                }
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            super.onOffsetsChanged(
                xOffset,
                yOffset,
                xOffsetStep,
                yOffsetStep,
                xPixelOffset,
                yPixelOffset,
            )
            if (allowSlide && !isPreview) {
                renderer!!.setOffset(0.5f - xOffset, 0.5f - yOffset)
            }
        }

        override fun onSurfaceChanged(
            surfaceHolder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(surfaceHolder, format, width, height)
            renderer!!.setScreenSize(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            stopPlayer()
            glSurfaceView?.onDestroy()
        }

        private fun createGLSurfaceView() {
            if (glSurfaceView != null) {
                glSurfaceView!!.onDestroy()
                glSurfaceView = null
            }
            glSurfaceView = GLWallpaperSurfaceView(context)
            val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val configInfo = activityManager.deviceConfigurationInfo
            renderer =
                when {
                    configInfo.reqGlEsVersion >= 0x30000 -> {
                        Log.d(TAG, "Support GLESv3")
                        glSurfaceView!!.setEGLContextClientVersion(3)
                        GLES30WallpaperRenderer(context)
                    }
                    configInfo.reqGlEsVersion >= 0x20000 -> {
                        Log.d(TAG, "Fallback to GLESv2")
                        glSurfaceView!!.setEGLContextClientVersion(2)
                        GLES20WallpaperRenderer(context)
                    }
                    else -> {
                        Toast.makeText(context, "需要支持 GLESv2 或更高版本", Toast.LENGTH_LONG).show()
                        throw RuntimeException("Needs GLESv2 or higher")
                    }
                }
            glSurfaceView!!.preserveEGLContextOnPause = true
            glSurfaceView!!.setRenderer(renderer)
            // 持续渲染避免黑屏
            glSurfaceView!!.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        @Throws(IOException::class)
        private fun getVideoMetadata(path: String) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(path)
                val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                val width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                videoRotation = rotation?.toInt() ?: 0
                videoWidth = width?.toInt() ?: 1920
                videoHeight = height?.toInt() ?: 1080
            } finally {
                mmr.release()
            }
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun startPlayer() {
            if (exoPlayer != null) {
                stopPlayer()
            }
            try {
                getVideoMetadata(wallpaper.getRealPath(context))
            } catch (e: IOException) {
                Log.e(TAG, "getVideoMetadata failed", e)
                return
            }

            trackSelector = DefaultTrackSelector(context)
            exoPlayer = ExoPlayer.Builder(context).setTrackSelector(trackSelector!!).build()
            exoPlayer!!.volume = 0.0f

            // 禁用音频解码器
            val count = exoPlayer!!.rendererCount
            for (i in 0 until count) {
                if (exoPlayer!!.getRendererType(i) == C.TRACK_TYPE_AUDIO) {
                    trackSelector!!.setParameters(
                        trackSelector!!.buildUponParameters().setRendererDisabled(i, true),
                    )
                }
            }
            exoPlayer!!.repeatMode = Player.REPEAT_MODE_ALL

            val dataSourceFactory = DefaultDataSource.Factory(context)
            val videoSource =
                ProgressiveMediaSource
                    .Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.fromFile(File(wallpaper.getRealPath(context)))))

            // 设置视频尺寸和旋转
            renderer!!.setVideoSizeAndRotation(videoWidth, videoHeight, videoRotation)
            renderer!!.setSourcePlayer(exoPlayer!!)

            exoPlayer!!.setMediaSource(videoSource)
            exoPlayer!!.prepare()
            exoPlayer!!.playWhenReady = true
        }

        private fun stopPlayer() {
            exoPlayer?.let {
                if (it.playWhenReady) {
                    it.playWhenReady = false
                    progress = it.currentPosition
                    it.stop()
                }
                it.release()
            }
            exoPlayer = null
            trackSelector = null
        }

        init {
            setTouchEventsEnabled(false)
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 视频型 MPKG 壁纸引擎
     *
     * MPKG 包内含 mp4/webm/mkv 视频 → 需先提取视频文件，再用 ExoPlayer 播放到壁纸 Surface
     * （SceneLib 只能渲染 Scene 场景，视频型 MPKG 用它渲染会黑屏）
     *
     * 流程：
     *   onCreate：后台线程用 MpkgParser.extractVideoFile 提取视频到私有目录
     *   onSurfaceCreated：创建 GLSurfaceView + 渲染器，视频提取完成后播放
     */
    @SuppressLint("ClickableViewAccessibility")
    internal inner class VideoMpkgWallpaperEngine(
        private val context: Context,
        private val wallpaper: WallpaperEntity,
    ) : Engine() {
        private var glSurfaceView: VideoMpkgGLSurfaceView? = null
        private var exoPlayer: ExoPlayer? = null
        private var trackSelector: DefaultTrackSelector? = null
        private var renderer: GLWallpaperRenderer? = null
        private var allowSlide = false
        private var videoRotation = 0
        private var videoWidth = 0
        private var videoHeight = 0
        private var progress: Long = 0

        /** 提取出的视频文件（后台线程写入，主线程读取） */
        @Volatile
        private var extractedVideoFile: java.io.File? = null

        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        /**
         * 自定义 GLSurfaceView，将 Surface 替换为 WallpaperEngine 的 Surface
         */
        private inner class VideoMpkgGLSurfaceView(
            context: Context?,
        ) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder = surfaceHolder

            fun onDestroy() {
                super.onDetachedFromWindow()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            allowSlide = WallpaperManager.get(context).isSlideWallpaperEnabled()
            setTouchEventsEnabled(false)

            // ★ 后台线程提取视频（不阻塞壁纸引擎创建）
            val mpkgPath = wallpaper.getRealPath(context)
            Thread {
                try {
                    val outDir = java.io.File(context.filesDir, "mpkg_video")
                    val extracted = MpkgParser.extractVideoFile(mpkgPath, outDir)
                    extractedVideoFile = extracted
                    Log.d(TAG, "VideoMpkgWallpaperEngine: extracted=${extracted?.absolutePath}")
                    if (extracted != null) {
                        mainHandler.post {
                            tryStartPlayer()
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "VideoMpkgWallpaperEngine: extract failed", e)
                }
            }.start()
        }

        override fun onSurfaceCreated(surfaceHolder: SurfaceHolder) {
            super.onSurfaceCreated(surfaceHolder)
            createGLSurfaceView()
            val width = surfaceHolder.surfaceFrame.width()
            val height = surfaceHolder.surfaceFrame.height()
            renderer?.setScreenSize(width, height)
            tryStartPlayer()
        }

        /** 视频提取完成且 surface 就绪时启动播放 */
        private fun tryStartPlayer() {
            val vf = extractedVideoFile ?: return
            if (glSurfaceView == null || renderer == null) return
            if (exoPlayer != null) return
            startPlayer(vf.absolutePath)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (renderer != null) {
                if (visible) {
                    allowSlide = WallpaperManager.get(context).isSlideWallpaperEnabled()
                    glSurfaceView?.onResume()
                    tryStartPlayer()
                } else {
                    stopPlayer()
                    glSurfaceView?.onPause()
                    allowSlide = false
                }
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            super.onOffsetsChanged(
                xOffset,
                yOffset,
                xOffsetStep,
                yOffsetStep,
                xPixelOffset,
                yPixelOffset,
            )
            if (allowSlide && !isPreview) {
                renderer?.setOffset(0.5f - xOffset, 0.5f - yOffset)
            }
        }

        override fun onSurfaceChanged(
            surfaceHolder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(surfaceHolder, format, width, height)
            renderer?.setScreenSize(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            stopPlayer()
            glSurfaceView?.onDestroy()
        }

        private fun createGLSurfaceView() {
            if (glSurfaceView != null) {
                glSurfaceView!!.onDestroy()
                glSurfaceView = null
            }
            glSurfaceView = VideoMpkgGLSurfaceView(context)
            val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val configInfo = activityManager.deviceConfigurationInfo
            renderer =
                when {
                    configInfo.reqGlEsVersion >= 0x30000 -> {
                        Log.d(TAG, "VideoMpkg: Support GLESv3")
                        glSurfaceView!!.setEGLContextClientVersion(3)
                        GLES30WallpaperRenderer(context)
                    }
                    configInfo.reqGlEsVersion >= 0x20000 -> {
                        Log.d(TAG, "VideoMpkg: Fallback to GLESv2")
                        glSurfaceView!!.setEGLContextClientVersion(2)
                        GLES20WallpaperRenderer(context)
                    }
                    else -> {
                        Toast.makeText(context, "需要支持 GLESv2 或更高版本", Toast.LENGTH_LONG).show()
                        throw RuntimeException("Needs GLESv2 or higher")
                    }
                }
            glSurfaceView!!.preserveEGLContextOnPause = true
            glSurfaceView!!.setRenderer(renderer)
            // 持续渲染避免黑屏
            glSurfaceView!!.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        @Throws(IOException::class)
        private fun getVideoMetadata(path: String) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(path)
                val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                val width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                videoRotation = rotation?.toInt() ?: 0
                videoWidth = width?.toInt() ?: 1920
                videoHeight = height?.toInt() ?: 1080
            } finally {
                mmr.release()
            }
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun startPlayer(videoPath: String) {
            if (exoPlayer != null) {
                stopPlayer()
            }
            try {
                getVideoMetadata(videoPath)
            } catch (e: IOException) {
                Log.e(TAG, "VideoMpkg: getVideoMetadata failed", e)
                return
            }

            trackSelector = DefaultTrackSelector(context)
            exoPlayer = ExoPlayer.Builder(context).setTrackSelector(trackSelector!!).build()
            exoPlayer!!.volume = 0.0f

            // 禁用音频解码器
            val count = exoPlayer!!.rendererCount
            for (i in 0 until count) {
                if (exoPlayer!!.getRendererType(i) == C.TRACK_TYPE_AUDIO) {
                    trackSelector!!.setParameters(
                        trackSelector!!.buildUponParameters().setRendererDisabled(i, true),
                    )
                }
            }
            exoPlayer!!.repeatMode = Player.REPEAT_MODE_ALL

            val dataSourceFactory = DefaultDataSource.Factory(context)
            val videoSource =
                ProgressiveMediaSource
                    .Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.fromFile(java.io.File(videoPath))))

            // 设置视频尺寸和旋转
            renderer?.setVideoSizeAndRotation(videoWidth, videoHeight, videoRotation)
            renderer?.setSourcePlayer(exoPlayer!!)

            exoPlayer!!.setMediaSource(videoSource)
            exoPlayer!!.prepare()
            exoPlayer!!.playWhenReady = true
            Log.d(TAG, "VideoMpkg: startPlayer $videoPath ${videoWidth}x$videoHeight rot=$videoRotation")
        }

        private fun stopPlayer() {
            exoPlayer?.let {
                if (it.playWhenReady) {
                    it.playWhenReady = false
                    progress = it.currentPosition
                    it.stop()
                }
                it.release()
            }
            exoPlayer = null
            trackSelector = null
        }

        override fun onDestroy() {
            Log.d(TAG, "VideoMpkgWallpaperEngine onDestroy")
            stopPlayer()
            glSurfaceView?.onDestroy()
            glSurfaceView = null
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * HTML（WebView）壁纸引擎
     * 使用 WebView 渲染 HTML 壁纸，通过 VirtualDisplay 投射到壁纸 Surface
     */
    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    inner class MpkgWebWallpaperEngine(
        private val context: Context,
        private val wallpaper: WallpaperEntity,
    ) : Engine() {
        private var myWebView: WebView? = null
        private val myId: Int = nextEngineId
        private val mDisplayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        private var mpkgParser: MpkgParser? = null

        init {
            nextEngineId++
        }

        private fun getMpkgFile(): java.io.File? {
            val path = wallpaper.getRealPath(this@LWService) ?: return null
            val f = java.io.File(path)
            return if (f.exists()) f else null
        }

        private fun getMimeType(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "json" -> "application/json"
                "html", "htm" -> "text/html"
                "vert", "frag" -> "text/plain"
                "tex" -> "application/octet-stream"
                "mdl" -> "application/octet-stream"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                else -> "application/octet-stream"
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            setTouchEventsEnabled(true)
            Log.d(TAG, "MpkgWebWallpaperEngine $myId On Create")

            if (myWebView != null) {
                myWebView!!.destroy()
            }

            val mpkgPath = wallpaper.getRealPath(this@LWService)
            Log.d(TAG, "MpkgWebWallpaperEngine: mpkgPath=$mpkgPath")
            val mpkgFile = java.io.File(mpkgPath)
            Log.d(TAG, "MpkgWebWallpaperEngine: mpkgFile exists=${mpkgFile.exists()}, size=${mpkgFile.length()}")
            if (mpkgFile.exists()) {
                mpkgParser = MpkgParser(mpkgFile)
                val parseOk = mpkgParser?.parse() ?: false
                Log.d(TAG, "MpkgWebWallpaperEngine: parseOk=$parseOk, entries=${mpkgParser?.listEntries()?.size}")
            } else {
                Log.e(TAG, "MpkgWebWallpaperEngine: mpkg file not found!")
            }

            WebView.setWebContentsDebuggingEnabled(true)

            myWebView =
                WebView(context).apply {
                    setInitialScale(1)
                    setBackgroundColor(Color.BLACK)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    webChromeClient =
                        object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                                Log.d(
                                    TAG,
                                    "WebConsole: ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
                                )
                                return super.onConsoleMessage(consoleMessage)
                            }
                        }
                    webViewClient =
                        object : WebViewClient() {
                            override fun onPageFinished(
                                view: WebView?,
                                url: String?,
                            ) {
                                super.onPageFinished(view, url)
                                Log.d(TAG, "onPageFinished: $url")
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: android.webkit.WebResourceError?,
                            ) {
                                super.onReceivedError(view, request, error)
                                Log.e(TAG, "onReceivedError: ${request?.url} -> ${error?.description}")
                            }
                        }

                    addJavascriptInterface(MpkgFileSystemJs(), "MpkgFS")

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = true
                        setSupportZoom(true)
                        layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                        loadWithOverviewMode = true
                        allowContentAccess = true
                        allowFileAccess = true
                        @Suppress("DEPRECATION")
                        allowFileAccessFromFileURLs = true
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = true
                        blockNetworkLoads = true
                        builtInZoomControls = false
                        displayZoomControls = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setGeolocationEnabled(false)
                        if (Build.VERSION.SDK_INT >= 26) {
                            safeBrowsingEnabled = false
                        }
                    }
                }

            val htmlUrl = "file:///android_asset/wallpapers/mpkg_scene.html"
            Log.d(TAG, "MpkgWebWallpaperEngine: loading $htmlUrl")
            myWebView!!.loadUrl(htmlUrl)
        }

        inner class MpkgFileSystemJs {
            @android.webkit.JavascriptInterface
            fun readFile(path: String): String? {
                return try {
                    Log.d(TAG, "MpkgFS.readFile: $path")
                    val parser = mpkgParser ?: return null
                    val data = parser.readBytes(path)
                    if (data == null) {
                        Log.w(TAG, "MpkgFS.readFile: not found: $path")
                        return null
                    }
                    val b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
                    Log.d(TAG, "MpkgFS.readFile: $path (${data.size} bytes -> ${b64.length} chars)")
                    b64
                } catch (e: Exception) {
                    Log.e(TAG, "MpkgFS.readFile failed: $path", e)
                    null
                }
            }
        }

        override fun onDestroy() {
            Log.d(TAG, "MpkgWebWallpaperEngine $myId On Destroy")
            myWebView?.destroy()
            myWebView = null
            mpkgParser = null
        }

        override fun onVisibilityChanged(visible: Boolean) {
            Log.d(TAG, "MpkgWebWallpaperEngine On Visibility Changed $visible")
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            Log.d(TAG, "MpkgWebWallpaperEngine On Surface Create")
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            Log.d(TAG, "MpkgWebWallpaperEngine On Surface Destroy")
            myWebView?.let {
                it.destroy()
                myWebView = null
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            Log.d(TAG, "MpkgWebWallpaperEngine On Surface Changed $format, $width, $height")
            if (myWebView == null) return

            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val density = DisplayMetrics.DENSITY_DEFAULT

            val virtualDisplay =
                mDisplayManager.createVirtualDisplay(
                    "MpkgVirtualDisplay",
                    width,
                    height,
                    density,
                    holder.surface,
                    flags,
                )

            try {
                val myPresentation = Presentation(this@LWService, virtualDisplay.display)
                val params = ViewGroup.LayoutParams(width, height)
                myPresentation.setContentView(myWebView!!, params)
                myPresentation.show()
            } catch (e: Exception) {
                Log.e(TAG, "MpkgWebWallpaperEngine onSurfaceChanged: Presentation failed", e)
            }
        }

        override fun onTouchEvent(event: MotionEvent?) {
            myWebView?.onTouchEvent(event)
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 使用 SceneLib 原生渲染 MPKG 场景壁纸
     * 基于壁纸引擎的 libscenejni.so 原生库
     *
     * ★ 支持陀螺仪视差（mobileparallax=1）和音频可视化（audioprocessing=true）
     *   参照 LandscapeWebActivity 的实现，移植到 WallpaperService 中
     *   这样设置为系统背景后也能正常使用陀螺仪和音频
     */
    @SuppressLint("ClickableViewAccessibility")
    inner class NativeSceneWallpaperEngine(
        private val context: Context,
        private val wallpaper: WallpaperEntity,
    ) : Engine() {
        private var glSurfaceView: SceneGLSurfaceView? = null
        private var sceneLib: io.wallpaperengine.wrapper.SceneLib? = null
        private var contextId = -1
        private val myId: Int = nextEngineId

        // ★ 陀螺仪相关字段（参照 LandscapeWebActivity.startMpkgBgSensor）
        private var sensorManager: android.hardware.SensorManager? = null
        private var gyroscope: android.hardware.Sensor? = null
        private var sensorListener: android.hardware.SensorEventListener? = null
        private var sensorMatrix = floatArrayOf(1.0f, 1.0f, -1.0f, 0.0f, 0.0f, 0.0f)

        @Volatile private var sceneInitialized = false

        // ★ 当前场景是否原生支持 parallax（视差/视角移动）
        //   只对支持 parallax 的场景启用陀螺仪，避免不支持的场景显示异常
        @Volatile private var parallaxSupported = false

        // ★ 视口垂直拉伸方案：左右保持原生比例，上下拉伸填满屏幕
        //   resizeScene 传入按壁纸原生宽高比计算的尺寸 (width, nativeH)
        //   glViewport 设置为全屏 (width, height)，垂直拉伸到屏幕
        @Volatile private var viewportStretch = false

        @Volatile private var wallpaperRatio = 0f

        @Volatile private var fullscreenW = 0

        @Volatile private var fullscreenH = 0

        // ★ 音频相关字段（参照 LandscapeWebActivity.startMpkgBgAudioRecording）
        private var visualizer: android.media.audiofx.Visualizer? = null
        private val audioFftBuffer = FloatArray(64)

        @Volatile private var audioEnabled = false

        private inner class SceneGLSurfaceView(
            context: Context?,
        ) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder = surfaceHolder

            fun onDestroy() {
                super.onDetachedFromWindow()
            }
        }

        /** ★ 根据屏幕旋转更新 sensorMatrix（参照原版 SceneWallpaperView） */
        private fun updateSensorMatrix() {
            try {
                val display = (getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
                when (display.rotation) {
                    0 -> sensorMatrix = floatArrayOf(1.0f, 1.0f, -1.0f, 0.0f, 0.0f, 0.0f)
                    1 -> sensorMatrix = floatArrayOf(0.0f, 0.0f, -1.0f, 1.0f, -1.0f, 0.0f)
                    2 -> sensorMatrix = floatArrayOf(-1.0f, -1.0f, -1.0f, 0.0f, 0.0f, 0.0f)
                    3 -> sensorMatrix = floatArrayOf(0.0f, 0.0f, -1.0f, -1.0f, 1.0f, 0.0f)
                }
            } catch (_: Throwable) {
            }
        }

        /** ★ 启动陀螺仪传感器（参照原版 ParallaxController） */
        private fun startSensor() {
            if (sensorListener != null) return
            try {
                val sm = getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
                val gyro = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) ?: return
                updateSensorMatrix()
                val parallaxStrength = 1.0f
                val factor = (parallaxStrength * 0.125f) + 0.1f
                val maxDistance = 0.5f
                val speedFactor = (parallaxStrength * 0.05f) + 0.01f
                val listener =
                    object : android.hardware.SensorEventListener {
                        override fun onSensorChanged(event: android.hardware.SensorEvent) {
                            if (contextId < 0 || !sceneInitialized) return
                            if (event.values.size < 2) return
                            try {
                                // ★ 原版公式：x = values[1]*factor, y = -values[0]*factor
                                //   Y 轴额外乘 0.5 缩小上下移动范围
                                val rawX = event.values[1] * factor
                                val rawY = -event.values[0] * factor * 0.5f
                                val ox = (rawX * sensorMatrix[0]) + (rawY * sensorMatrix[4])
                                val oy = (rawY * sensorMatrix[1]) + (rawX * sensorMatrix[3])
                                glSurfaceView?.queueEvent {
                                    try {
                                        sceneLib?.sendNormalizedParallaxOffset(contextId, true, ox, oy, maxDistance, speedFactor)
                                    } catch (_: Throwable) {
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                        }

                        override fun onAccuracyChanged(
                            sensor: android.hardware.Sensor?,
                            accuracy: Int,
                        ) {}
                    }
                sm.registerListener(listener, gyro, 2)
                sensorManager = sm
                gyroscope = gyro
                sensorListener = listener
            } catch (_: Throwable) {
            }
        }

        private fun stopSensor() {
            try {
                val sm = sensorManager
                val listener = sensorListener
                if (sm != null && listener != null) {
                    sm.unregisterListener(listener)
                }
            } catch (_: Throwable) {
            }
            sensorListener = null
            gyroscope = null
            sensorManager = null
        }

        /** ★ 启动音频捕获（参照 LandscapeWebActivity.startMpkgBgAudioRecording）
         *   只用 Visualizer(0) 捕获系统混音，不依赖应用内 IMusicPlayer
         */
        private fun startAudio() {
            if (audioEnabled) return
            audioEnabled = true
            try {
                val viz = android.media.audiofx.Visualizer(0)
                viz.setEnabled(false)
                viz.setCaptureSize(512)
                try {
                    viz.setScalingMode(0)
                } catch (_: Throwable) {
                }
                try {
                    viz.setMeasurementMode(0)
                } catch (_: Throwable) {
                }
                viz.setDataCaptureListener(
                    object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            v: android.media.audiofx.Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int,
                        ) {}

                        override fun onFftDataCapture(
                            v: android.media.audiofx.Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int,
                        ) {
                            if (fft == null || !audioEnabled || contextId < 0 || !sceneInitialized) return
                            try {
                                // ★ 原版 AudioRecorder.onFftDataCapture 算法
                                val result = audioFftBuffer
                                for (i in 0 until 64) {
                                    val i2 = (i + 2) * 2
                                    if (i2 + 1 >= fft.size) {
                                        result[i] = 0f
                                        continue
                                    }
                                    val re = fft[i2].toFloat()
                                    val im = fft[i2 + 1].toFloat()
                                    val mag = (re * re) + (im * im)
                                    var v2 = 0f
                                    if (mag > 0f) v2 = (Math.log10(mag.toDouble()).toFloat()) * 0.35f
                                    val decay = (2.0f - Math.exp((((1.0f - i / 63.0f) * 1.0f) - 0.5f).toDouble()).toFloat())
                                    result[i] = Math.min(1.0f, v2 * decay)
                                }
                                val data = result.copyOf()
                                glSurfaceView?.queueEvent {
                                    try {
                                        if (contextId >= 0 && sceneInitialized) {
                                            sceneLib?.sendAudioData(contextId, data)
                                        }
                                    } catch (_: Throwable) {
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    },
                    android.media.audiofx.Visualizer
                        .getMaxCaptureRate(),
                    false,
                    true,
                )
                visualizer = viz
                viz.setEnabled(true)
            } catch (_: Throwable) {
            }
        }

        private fun stopAudio() {
            audioEnabled = false
            try {
                visualizer?.setEnabled(false)
                visualizer?.release()
            } catch (_: Throwable) {
            }
            visualizer = null
        }

        /** ★ 应用默认场景属性（参照 LandscapeWebActivity.applyDefaultSceneProperties）
         *   启用 mobileparallax=1, mobileparallaxstrength=100, audioprocessing=true, alignment=1
         */
        private fun applyDefaultSceneProperties() {
            try {
                if (contextId < 0) return
                val featureFlags =
                    try {
                        sceneLib?.getSceneFeatureFlags(contextId) ?: 0
                    } catch (_: Throwable) {
                        0
                    }
                val supportsAudio = (featureFlags and 1) != 0
                // ★ bit 2 (4): 场景是否原生支持 parallax（视差/视角移动）
                val supportsParallax = (featureFlags and 4) != 0
                parallaxSupported = supportsParallax
                Log.d(TAG, "applyDefaultSceneProperties: featureFlags=$featureFlags, audio=$supportsAudio, parallax=$supportsParallax")

                // ★ 获取壁纸原生分辨率，计算宽高比
                //   用于 onSurfaceChanged 中计算渲染高度：按宽度填满屏幕后，上下拉伸填满
                //   左右保持原生比例（不变形），上下强制填满屏幕
                try {
                    val resolution = sceneLib?.getWallpaperResolution(wallpaper.getRealPath(context))
                    if (resolution != null && resolution.x > 0 && resolution.y > 0) {
                        wallpaperRatio = resolution.x.toFloat() / resolution.y.toFloat()
                        viewportStretch = true
                        Log.d(TAG, "wallpaper resolution: ${resolution.x}x${resolution.y}, ratio=$wallpaperRatio, viewportStretch=true")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "getWallpaperResolution failed", e)
                }
                // ★ fallback: getWallpaperResolution 失败时，用 getSceneCanvasSize 获取场景画布尺寸
                //   大多数场景壁纸通过此方法获取比例
                if (wallpaperRatio <= 0f) {
                    try {
                        val canvasSize = android.graphics.PointF()
                        val ok = sceneLib?.getSceneCanvasSize(contextId, canvasSize) ?: false
                        if (ok && canvasSize.x > 0 && canvasSize.y > 0) {
                            wallpaperRatio = canvasSize.x / canvasSize.y
                            viewportStretch = true
                            Log.d(TAG, "scene canvas size: ${canvasSize.x}x${canvasSize.y}, ratio=$wallpaperRatio, viewportStretch=true")
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "getSceneCanvasSize failed", e)
                    }
                }
                // ★ fallback 2: 仍无法获取比例时，使用默认 16:9 比例（场景壁纸常见比例）
                if (wallpaperRatio <= 0f) {
                    wallpaperRatio = 16f / 9f
                    viewportStretch = true
                    Log.d(TAG, "use default ratio 16:9, viewportStretch=true")
                }

                val props = sceneLib?.getSceneProperties(contextId) ?: return
                if (props.isEmpty()) return
                val json = org.json.JSONObject(props)
                // ★ 只对原生支持 parallax 的场景启用陀螺仪视差
                //   不支持 parallax 的场景强制启用会导致视角异常移动、画面显示错乱
                val parallaxValue = if (supportsParallax) 1 else 0
                json.put(
                    "mobileparallax",
                    org.json.JSONObject().apply {
                        put("type", "combo")
                        put("value", parallaxValue)
                        put("order", 4)
                    },
                )
                json.put(
                    "mobileparallaxstrength",
                    org.json.JSONObject().apply {
                        put("type", "slider")
                        put("value", 100.0)
                        put("min", 1.0)
                        put("max", 200.0)
                        put("order", 5)
                        put("condition", "mobileparallax.value")
                    },
                )
                // ★ 强制启用音频处理
                if (supportsAudio) {
                    json.put(
                        "audioprocessing",
                        org.json.JSONObject().apply {
                            put("type", "bool")
                            put("value", true)
                            put("order", 2)
                        },
                    )
                }
                // ★ 不覆盖场景自己的 alignment 属性
                //   每个场景壁纸有自己的最佳对齐模式，强制覆盖会导致显示问题
                //   只在场景没有 alignment 属性时设置默认值 0（填满屏幕）
                if (!json.has("alignment")) {
                    json.put(
                        "alignment",
                        org.json.JSONObject().apply {
                            put("type", "combo")
                            put("value", 0)
                            put("order", 2147483647)
                        },
                    )
                }
                sceneLib?.applySceneProperties(contextId, json.toString())
            } catch (_: Throwable) {
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            setTouchEventsEnabled(true)
            Log.d(TAG, "NativeSceneWallpaperEngine $myId On Create")

            try {
                sceneLib = io.wallpaperengine.wrapper.SceneLib()
                sceneLib!!.initLibrary(context)
                Log.d(TAG, "NativeSceneWallpaperEngine: initLibrary ok")
            } catch (e: Throwable) {
                Log.e(TAG, "NativeSceneWallpaperEngine: initLibrary failed", e)
            }
        }

        override fun onSurfaceCreated(surfaceHolder: SurfaceHolder) {
            super.onSurfaceCreated(surfaceHolder)
            Log.d(TAG, "NativeSceneWallpaperEngine $myId On Surface Create")

            if (glSurfaceView != null) {
                glSurfaceView!!.onDestroy()
                glSurfaceView = null
            }

            glSurfaceView = SceneGLSurfaceView(context)
            glSurfaceView!!.setEGLContextClientVersion(3)
            glSurfaceView!!.preserveEGLContextOnPause = true
            glSurfaceView!!.setRenderer(
                object : GLSurfaceView.Renderer {
                    override fun onSurfaceCreated(
                        gl: javax.microedition.khronos.opengles.GL10?,
                        config: javax.microedition.khronos.egl.EGLConfig?,
                    ) {
                        Log.d(TAG, "SceneRenderer: onSurfaceCreated")
                        try {
                            contextId = sceneLib?.initContext(context) ?: -1
                            Log.d(TAG, "SceneRenderer: contextId=$contextId")
                            if (contextId >= 0) {
                                val path = wallpaper.getRealPath(context)
                                Log.d(TAG, "SceneRenderer: initScene with $path")
                                sceneLib?.initScene(contextId, path)
                                sceneLib?.setLogToFileEnabled(false)
                                // ★ 应用场景属性（启用陀螺仪、音频、fill 对齐）
                                applyDefaultSceneProperties()
                                sceneInitialized = true
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "SceneRenderer: onSurfaceCreated failed", e)
                        }
                    }

                    override fun onSurfaceChanged(
                        gl: javax.microedition.khronos.opengles.GL10?,
                        width: Int,
                        height: Int,
                    ) {
                        Log.d(TAG, "SceneRenderer: onSurfaceChanged ${width}x$height")
                        try {
                            // ★ 保存全屏尺寸
                            fullscreenW = width
                            fullscreenH = height

                            // ★ resizeScene 传入屏幕尺寸
                            //   native 层在 updateScene 中会将 viewport 设为 resizeScene 的尺寸，
                            //   传入屏幕尺寸后 viewport = (0,0,screenW,screenH)，画面填满整个屏幕
                            android.opengl.GLES20.glViewport(0, 0, width, height)
                            if (contextId >= 0) {
                                sceneLib?.resizeScene(contextId, width, height)
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "SceneRenderer: onSurfaceChanged failed", e)
                        }
                    }

                    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
                        try {
                            if (contextId >= 0) {
                                sceneLib?.updateScene(contextId)
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "SceneRenderer: onDrawFrame failed", e)
                        }
                    }
                },
            )
            glSurfaceView!!.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        override fun onSurfaceChanged(
            surfaceHolder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(surfaceHolder, format, width, height)
            Log.d(TAG, "NativeSceneWallpaperEngine On Surface Changed $format, $width, $height")
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            Log.d(TAG, "NativeSceneWallpaperEngine $myId On Surface Destroy")
            // ★ 先停止传感器和音频，避免竞态
            stopSensor()
            stopAudio()
            try {
                if (contextId >= 0) {
                    sceneLib?.shutdownScene(contextId)
                    sceneLib?.destroyContext(contextId)
                    contextId = -1
                    sceneInitialized = false
                }
            } catch (e: Throwable) {
                Log.e(TAG, "NativeSceneWallpaperEngine: surface destroy cleanup failed", e)
            }
            glSurfaceView?.onDestroy()
            glSurfaceView = null
        }

        override fun onDestroy() {
            Log.d(TAG, "NativeSceneWallpaperEngine $myId On Destroy")
            stopSensor()
            stopAudio()
            try {
                if (contextId >= 0) {
                    sceneLib?.shutdownScene(contextId)
                    sceneLib?.destroyContext(contextId)
                    contextId = -1
                    sceneInitialized = false
                }
            } catch (e: Throwable) {
                Log.e(TAG, "NativeSceneWallpaperEngine: destroy cleanup failed", e)
            }
            glSurfaceView?.onDestroy()
            glSurfaceView = null
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            Log.d(TAG, "NativeSceneWallpaperEngine On Visibility Changed $visible")
            if (glSurfaceView != null) {
                if (visible) {
                    glSurfaceView!!.onResume()
                    // ★ 可见时启动传感器和音频
                    //   只对原生支持 parallax 的场景启用陀螺仪
                    if (parallaxSupported) startSensor()
                    startAudio()
                } else {
                    // ★ 不可见时停止，节省电量
                    stopSensor()
                    stopAudio()
                    glSurfaceView!!.onPause()
                }
            }
        }

        override fun onTouchEvent(event: android.view.MotionEvent?) {
            super.onTouchEvent(event)
            if (event != null && contextId >= 0 && sceneInitialized) {
                // ★ 必须通过 queueEvent 在 GL 线程调用（参照原版 SceneWallpaperView）
                val action = event.actionMasked
                val x = event.x
                val y = event.y
                val glv = glSurfaceView ?: return
                when (action) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE,
                    -> {
                        glv.queueEvent {
                            try {
                                sceneLib?.sendTouchInput(contextId, true, x, y)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL,
                    -> {
                        glv.queueEvent {
                            try {
                                sceneLib?.sendTouchInput(contextId, false, x, y)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 使用 WebView 渲染 HTML 壁纸，通过 VirtualDisplay 投射到壁纸 Surface
     */
    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    inner class WebWallpaperEngine(
        private val context: Context,
        private val wallpaper: WallpaperEntity,
    ) : Engine() {
        private var myWebView: WebView? = null
        private val myId: Int = nextEngineId
        private val mDisplayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager

        init {
            nextEngineId++
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            setTouchEventsEnabled(true)
            Log.d(TAG, "MyEngine $myId On Create")

            if (myWebView != null) {
                myWebView!!.destroy()
            }

            WebView.setWebContentsDebuggingEnabled(true)

            myWebView =
                WebView(context).apply {
                    setInitialScale(1)
                    setBackgroundColor(Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    webChromeClient = WebChromeClient()
                    webViewClient =
                        object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val url = request.url
                                return try {
                                    if (url.scheme == "http" || url.scheme == "https") {
                                        view.loadUrl(url.toString())
                                    } else {
                                        val intent = Intent(Intent.ACTION_VIEW, url)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(intent)
                                    }
                                    true
                                } catch (e: Exception) {
                                    Log.d(TAG, "shouldOverrideUrlLoading: $e")
                                    true
                                }
                            }
                        }

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = true
                        setSupportZoom(true)
                        layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                        loadWithOverviewMode = true
                        allowContentAccess = true
                        allowFileAccess = true
                        @Suppress("DEPRECATION")
                        allowFileAccessFromFileURLs = true
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = true
                        blockNetworkLoads = false
                        builtInZoomControls = false
                        displayZoomControls = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setGeolocationEnabled(true)
                        if (Build.VERSION.SDK_INT >= 26) {
                            safeBrowsingEnabled = true
                        }
                    }
                }

            val path = wallpaper.getRealPath(this@LWService)
            Log.d(TAG, "onCreate: $path")
            if (path.startsWith("http")) {
                myWebView!!.loadUrl(path)
            } else {
                myWebView!!.loadUrl("file://$path")
            }
        }

        override fun onDestroy() {
            Log.d(TAG, "MyEngine $myId On Destroy")
            myWebView?.destroy()
            myWebView = null
        }

        override fun onVisibilityChanged(visible: Boolean) {
            Log.d(TAG, "On Visibility Changed $visible")
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            Log.d(TAG, "On Surface Create")
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            Log.d(TAG, "On Surface Destroy")
            myWebView?.let {
                it.destroy()
                myWebView = null
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            Log.d(TAG, "On Surface Changed $format, $width, $height")
            if (myWebView == null) return

            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val density = DisplayMetrics.DENSITY_DEFAULT

            val virtualDisplay =
                mDisplayManager.createVirtualDisplay(
                    "MyVirtualDisplay",
                    width,
                    height,
                    density,
                    holder.surface,
                    flags,
                )

            try {
                val myPresentation = Presentation(this@LWService, virtualDisplay.display)
                val params = ViewGroup.LayoutParams(width, height)
                myPresentation.setContentView(myWebView!!, params)
                myPresentation.show()
            } catch (e: Exception) {
                Log.e(TAG, "onSurfaceChanged: Presentation failed", e)
            }
        }

        override fun onTouchEvent(event: MotionEvent?) {
            myWebView?.onTouchEvent(event)
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 桌面萌宠壁纸引擎
     *
     * 功能：
     * - 在桌面绘制一个 Q 萌的卡通角色（圆头、椭圆身、可摆动的四肢与表情）
     * - 20fps 帧率（约 50ms 一帧）
     * - 随机触发动作：眨眼、摇头、点头、摇头晃脑、跳、走路
     * - 拖拽交互：手指拖动时跟随手指，眼睛紧闭、双手猛烈摇晃、身体颤抖（像要掉下去）
     * - 复用 LWService 的 10 层保活机制和 BootReceiver 自启动
     *
     * 设计要点：
     * - 角色由纯 Canvas 绘制（无需图片资源，抗"清除数据"）
     * - 周围背景透明，只保留萌宠本体（"将周围抠掉只保留人物"）
     */
    inner class PetWallpaperEngine : Engine() {
        // 渲染线程
        private var renderThread: Thread? = null

        @Volatile private var isRunning = false

        @Volatile private var surfaceValid = false

        private var screenW = 0
        private var screenH = 0
        private var petSize = 220f

        // 萌宠状态
        private var petX = 0f
        private var petY = 0f
        private var currentAction = PetAction.IDLE
        private var actionFrame = 0
        private var idleCount = 0
        private var walkDir = 1
        private var jumpStartY = 0f
        private val rng = Random(System.currentTimeMillis())

        // 表情参数
        private var eyeOpenRatio = 1f
        private var headAngle = 0f
        private var headOffsetX = 0f
        private var headOffsetY = 0f
        private var armSwing = 0f
        private var legSwing = 0f
        private var bodyTilt = 0f
        private var bodyShake = 0f

        // 拖拽状态
        @Volatile private var isDragging = false
        private var dragOffsetX = 0f
        private var dragOffsetY = 0f

        // 画笔
        private val bodyPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 196, 206)
                style = Paint.Style.FILL
            }
        private val bodyStrokePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(180, 120, 140)
                style = Paint.Style.STROKE
                strokeWidth =
                    3f
            }
        private val headPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 224, 230)
                style = Paint.Style.FILL
            }
        private val earPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 180, 195)
                style = Paint.Style.FILL
            }
        private val eyeWhitePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
        private val eyePupilPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(60, 50, 80)
                style = Paint.Style.FILL
            }
        private val eyeClosedPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(80, 60, 90)
                style = Paint.Style.STROKE
                strokeWidth =
                    4f
                strokeCap = Paint.Cap.ROUND
            }
        private val mouthPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(120, 80, 100)
                style = Paint.Style.STROKE
                strokeWidth =
                    3f
                strokeCap = Paint.Cap.ROUND
            }
        private val cheekPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(110, 255, 130, 150)
                style = Paint.Style.FILL
            }
        private val limbPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 200, 210)
                style = Paint.Style.FILL
            }
        private val limbStrokePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(180, 120, 140)
                style = Paint.Style.STROKE
                strokeWidth =
                    3f
            }
        private val hlPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            Log.d(PET_TAG, "PetWallpaperEngine onCreate")
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            super.onSurfaceCreated(holder)
            try {
                holder?.setFormat(PixelFormat.TRANSLUCENT)
            } catch (_: Exception) {
            }
            surfaceValid = true
            startRender()
            Log.d(PET_TAG, "onSurfaceCreated")
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder?,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            screenW = width
            screenH = height
            petSize = (width * 0.28f).coerceIn(140f, 320f)
            if (petX == 0f && petY == 0f) {
                randomizePosition()
            } else {
                petX = petX.coerceIn(petSize, screenW - petSize)
                petY = petY.coerceIn(petSize, screenH - petSize * 0.5f)
            }
            Log.d(PET_TAG, "onSurfaceChanged: ${width}x$height, petSize=$petSize")
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            super.onSurfaceDestroyed(holder)
            surfaceValid = false
            stopRender()
            Log.d(PET_TAG, "onSurfaceDestroyed")
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) startRender() else stopRender()
        }

        override fun onDestroy() {
            stopRender()
            super.onDestroy()
            Log.d(PET_TAG, "onDestroy")
        }

        override fun onTouchEvent(event: MotionEvent?) {
            val e = event ?: return
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isPointOnPet(e.x, e.y)) {
                        isDragging = true
                        dragOffsetX = petX - e.x
                        dragOffsetY = petY - e.y
                        currentAction = PetAction.DRAG
                        actionFrame = 0
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        petX = (e.x + dragOffsetX).coerceIn(petSize * 0.4f, screenW - petSize * 0.4f)
                        petY = (e.y + dragOffsetY).coerceIn(petSize * 0.4f, screenH - petSize * 0.4f)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        currentAction = PetAction.IDLE
                        actionFrame = 0
                        idleCount = 0
                    }
                }
            }
        }

        private fun isPointOnPet(
            x: Float,
            y: Float,
        ): Boolean {
            val dx = x - petX
            val dy = y - petY
            val r = petSize * 0.55f
            return dx * dx + dy * dy <= r * r
        }

        // 渲染循环
        private fun startRender() {
            if (isRunning) return
            isRunning = true
            renderThread =
                Thread({
                    while (isRunning) {
                        val frameStart = SystemClock.elapsedRealtime()
                        try {
                            if (surfaceValid) {
                                updateState()
                                drawFrame()
                            }
                        } catch (e: Exception) {
                            Log.e(PET_TAG, "render error", e)
                        }
                        val sleep = PET_FRAME_INTERVAL_MS - (SystemClock.elapsedRealtime() - frameStart)
                        if (sleep > 0) {
                            try {
                                Thread.sleep(sleep)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                }, "PetRenderThread").also { it.isDaemon = true }
            renderThread?.start()
        }

        private fun stopRender() {
            isRunning = false
            renderThread?.let {
                try {
                    it.interrupt()
                    it.join(100)
                } catch (_: Exception) {
                }
            }
            renderThread = null
        }

        // 状态更新
        private fun updateState() {
            if (currentAction == PetAction.DRAG) {
                updateDragState()
                return
            }
            actionFrame++
            when (currentAction) {
                PetAction.IDLE -> updateIdle()
                PetAction.BLINK -> updateBlink()
                PetAction.SHAKE_HEAD -> updateShakeHead()
                PetAction.NOD -> updateNod()
                PetAction.WIGGLE -> updateWiggle()
                PetAction.WALK -> updateWalk()
                PetAction.JUMP -> updateJump()
                PetAction.DRAG -> updateDragState()
            }
            if (currentAction != PetAction.IDLE && isActionFinished()) {
                currentAction = PetAction.IDLE
                actionFrame = 0
                idleCount = 0
            }
        }

        private fun isActionFinished(): Boolean =
            when (currentAction) {
                PetAction.BLINK -> actionFrame >= 8
                PetAction.SHAKE_HEAD -> actionFrame >= 30
                PetAction.NOD -> actionFrame >= 30
                PetAction.WIGGLE -> actionFrame >= 40
                PetAction.WALK -> actionFrame >= 60
                PetAction.JUMP -> actionFrame >= PET_JUMP_FRAMES * 2
                PetAction.IDLE, PetAction.DRAG -> false
            }

        private fun updateIdle() {
            eyeOpenRatio = 0.92f + 0.08f * sin(actionFrame * 0.15f)
            headAngle = 0f
            headOffsetX = 0f
            headOffsetY = 0f
            armSwing = 0f
            legSwing = 0f
            bodyTilt = 0f
            bodyShake = 0f
            idleCount++
            if (idleCount > PET_IDLE_FRAMES_BEFORE_ACTION) pickRandomAction()
        }

        private fun pickRandomAction() {
            currentAction =
                when (rng.nextInt(6)) {
                    0 -> PetAction.BLINK
                    1 -> PetAction.SHAKE_HEAD
                    2 -> PetAction.NOD
                    3 -> PetAction.WIGGLE
                    4 -> PetAction.WALK
                    else -> PetAction.JUMP
                }
            actionFrame = 0
            if (currentAction == PetAction.WALK) walkDir = if (rng.nextBoolean()) 1 else -1
            if (currentAction == PetAction.JUMP) jumpStartY = petY
        }

        private fun updateBlink() {
            eyeOpenRatio =
                when {
                    actionFrame < 3 -> 1f - actionFrame / 3f
                    actionFrame < 6 -> (actionFrame - 3) / 3f
                    else -> 1f
                }
        }

        private fun updateShakeHead() {
            headAngle = 18f * sin(actionFrame * 0.35f)
            eyeOpenRatio = 1f
        }

        private fun updateNod() {
            headOffsetY = 12f * sin(actionFrame * 0.4f)
            headAngle = 0f
            eyeOpenRatio = 1f
        }

        private fun updateWiggle() {
            headAngle = 14f * sin(actionFrame * 0.3f)
            headOffsetX = 6f * sin(actionFrame * 0.5f)
            headOffsetY = 6f * cos(actionFrame * 0.4f)
            eyeOpenRatio = 1f
        }

        private fun updateWalk() {
            petX += walkDir * PET_WALK_SPEED
            if (petX < petSize * 0.4f) {
                petX = petSize * 0.4f
                walkDir = 1
            } else if (petX > screenW - petSize * 0.4f) {
                petX = screenW - petSize * 0.4f
                walkDir = -1
            }
            legSwing = 25f * sin(actionFrame * 0.4f)
            armSwing = 18f * sin(actionFrame * 0.4f)
            bodyTilt = 2f * sin(actionFrame * 0.8f)
            eyeOpenRatio = 1f
            headAngle = walkDir * 4f
        }

        private fun updateJump() {
            val totalFrames = PET_JUMP_FRAMES * 2
            val t = actionFrame.toFloat() / totalFrames
            val jumpOffset = -4f * PET_JUMP_MAX_HEIGHT * t * (t - 1)
            petY = jumpStartY - jumpOffset
            legSwing = if (actionFrame < PET_JUMP_FRAMES) -20f else 20f
            armSwing = 30f
            eyeOpenRatio = if (actionFrame in PET_JUMP_FRAMES - 2..PET_JUMP_FRAMES + 2) 0.3f else 1f
            if (actionFrame >= totalFrames) petY = jumpStartY
        }

        private fun updateDragState() {
            eyeOpenRatio = 0f
            armSwing = 60f * sin(actionFrame * 1.2f)
            bodyShake = 6f * rng.nextFloat() - 3f
            headAngle = 12f * sin(actionFrame * 1.0f)
            bodyTilt = 6f * sin(actionFrame * 0.9f)
        }

        // 绘制
        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas() ?: return
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                canvas.save()
                canvas.translate(petX + bodyShake, petY)
                canvas.rotate(bodyTilt)
                drawPet(canvas)
                canvas.restore()
            } catch (_: Exception) {
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        private fun drawPet(c: Canvas) {
            val s = petSize
            val bodyW = s * 0.5f
            val bodyH = s * 0.5f
            val headR = s * 0.22f
            val headCenterY = -bodyH * 0.5f - headR * 0.5f

            drawLegs(c, bodyH, legSwing)
            val bodyRect = RectF(-bodyW * 0.5f, -bodyH * 0.5f, bodyW * 0.5f, bodyH * 0.5f)
            c.drawRoundRect(bodyRect, bodyW * 0.3f, bodyW * 0.3f, bodyPaint)
            c.drawRoundRect(bodyRect, bodyW * 0.3f, bodyW * 0.3f, bodyStrokePaint)
            drawArms(c, bodyW, bodyH, armSwing)

            c.save()
            c.translate(headOffsetX, headCenterY + headOffsetY)
            c.rotate(headAngle)
            drawHead(c, headR)
            c.restore()
        }

        private fun drawHead(
            c: Canvas,
            r: Float,
        ) {
            val earR = r * 0.32f
            c.drawCircle(-r * 0.65f, -r * 0.7f, earR, earPaint)
            c.drawCircle(r * 0.65f, -r * 0.7f, earR, earPaint)
            c.drawCircle(0f, 0f, r, headPaint)
            c.drawCircle(0f, 0f, r, bodyStrokePaint)
            c.drawCircle(-r * 0.55f, r * 0.25f, r * 0.18f, cheekPaint)
            c.drawCircle(r * 0.55f, r * 0.25f, r * 0.18f, cheekPaint)

            val eyeOffsetX = r * 0.35f
            val eyeOffsetY = -r * 0.1f
            val eyeR = r * 0.18f
            if (eyeOpenRatio > 0.15f) {
                val eyeRectL =
                    RectF(
                        -eyeOffsetX - eyeR,
                        eyeOffsetY - eyeR * eyeOpenRatio,
                        -eyeOffsetX + eyeR,
                        eyeOffsetY + eyeR * eyeOpenRatio,
                    )
                val eyeRectR =
                    RectF(
                        eyeOffsetX - eyeR,
                        eyeOffsetY - eyeR * eyeOpenRatio,
                        eyeOffsetX + eyeR,
                        eyeOffsetY + eyeR * eyeOpenRatio,
                    )
                c.drawOval(eyeRectL, eyeWhitePaint)
                c.drawOval(eyeRectR, eyeWhitePaint)
                val pupilR = eyeR * 0.55f
                c.drawCircle(-eyeOffsetX, eyeOffsetY, pupilR * eyeOpenRatio, eyePupilPaint)
                c.drawCircle(eyeOffsetX, eyeOffsetY, pupilR * eyeOpenRatio, eyePupilPaint)
                c.drawCircle(-eyeOffsetX + pupilR * 0.3f, eyeOffsetY - pupilR * 0.3f, pupilR * 0.3f * eyeOpenRatio, hlPaint)
                c.drawCircle(eyeOffsetX + pupilR * 0.3f, eyeOffsetY - pupilR * 0.3f, pupilR * 0.3f * eyeOpenRatio, hlPaint)
            } else {
                val eyeY = eyeOffsetY
                c.drawLine(-eyeOffsetX - eyeR, eyeY, -eyeOffsetX + eyeR, eyeY, eyeClosedPaint)
                c.drawLine(eyeOffsetX - eyeR, eyeY, eyeOffsetX + eyeR, eyeY, eyeClosedPaint)
            }
            val mouthY = r * 0.45f
            val mouthW = r * 0.18f
            c.drawArc(RectF(-mouthW, mouthY - mouthW, mouthW, mouthY + mouthW), 0f, 180f, false, mouthPaint)
        }

        private fun drawArms(
            c: Canvas,
            bodyW: Float,
            bodyH: Float,
            swing: Float,
        ) {
            val armLen = bodyH * 0.45f
            val armW = bodyW * 0.18f
            c.save()
            c.translate(-bodyW * 0.5f, -bodyH * 0.2f)
            c.rotate(-30f + swing)
            val lRect = RectF(-armW * 0.5f, 0f, armW * 0.5f, armLen)
            c.drawRoundRect(lRect, armW * 0.5f, armW * 0.5f, limbPaint)
            c.drawRoundRect(lRect, armW * 0.5f, armW * 0.5f, limbStrokePaint)
            c.restore()
            c.save()
            c.translate(bodyW * 0.5f, -bodyH * 0.2f)
            c.rotate(30f - swing)
            val rRect = RectF(-armW * 0.5f, 0f, armW * 0.5f, armLen)
            c.drawRoundRect(rRect, armW * 0.5f, armW * 0.5f, limbPaint)
            c.drawRoundRect(rRect, armW * 0.5f, armW * 0.5f, limbStrokePaint)
            c.restore()
        }

        private fun drawLegs(
            c: Canvas,
            bodyH: Float,
            swing: Float,
        ) {
            val legLen = bodyH * 0.4f
            val legW = bodyH * 0.18f
            c.save()
            c.translate(-bodyH * 0.18f, bodyH * 0.5f)
            c.rotate(-swing * 0.5f)
            val lRect = RectF(-legW * 0.5f, 0f, legW * 0.5f, legLen)
            c.drawRoundRect(lRect, legW * 0.5f, legW * 0.5f, limbPaint)
            c.drawRoundRect(lRect, legW * 0.5f, legW * 0.5f, limbStrokePaint)
            c.restore()
            c.save()
            c.translate(bodyH * 0.18f, bodyH * 0.5f)
            c.rotate(swing * 0.5f)
            val rRect = RectF(-legW * 0.5f, 0f, legW * 0.5f, legLen)
            c.drawRoundRect(rRect, legW * 0.5f, legW * 0.5f, limbPaint)
            c.drawRoundRect(rRect, legW * 0.5f, legW * 0.5f, limbStrokePaint)
            c.restore()
        }

        private fun randomizePosition() {
            if (screenW <= 0 || screenH <= 0) return
            val minX = petSize * 0.5f
            val maxX = screenW - petSize * 0.5f
            val minY = screenH * 0.3f
            val maxY = screenH - screenH * 0.08f - petSize * 0.3f
            petX = minX + rng.nextFloat() * (maxX - minX)
            petY = minY + rng.nextFloat() * (maxY - minY).coerceAtLeast(0f)
        }
    }
}
