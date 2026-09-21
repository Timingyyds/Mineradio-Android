package com.mineradio.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mineradio.app.audio.AudioCapture
import com.mineradio.app.inference.GestureRecognizerHelper
import com.mineradio.app.manager.MineradioServer
import com.mineradio.app.manager.NeteaseMusicApi
import com.mineradio.app.service.MediaNotificationState
import com.mineradio.app.service.MusicPlaybackService
import com.mineradio.app.storage.impl.dao.ExtraInfoDao
import com.mineradio.app.storage.impl.dao.PlaylistDao
import com.mineradio.app.storage.impl.dao.SongDao
import com.mineradio.app.storage.impl.entity.ExtraInfoEntity
import com.mineradio.app.storage.impl.entity.PlaylistEntity
import com.mineradio.app.view.GestureOverlayView
import com.mineradio.app.wallpaper.WallpaperEntity
import com.mineradio.app.wallpaper.WallpaperManager
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.Executors

/**
 * 横屏 HTML UI Activity
 * 启动嵌入式 HTTP 服务器，强制横屏并加载 mineradio HTML UI
 */
class LandscapeWebActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var server: MineradioServer? = null
    private var serverPort = 0
    private var bridgeInjected = false

    // ★ 汽水音乐安全签名 WebView（加载 bdms.js 生成 a_bogus 签名）
    //   前端通过 AndroidExternal.signedRequest 发起签名请求，签名 WebView 中执行
    //   __qishuiRequest 注入 a_bogus，结果经 AndroidSignerCallback 回传主 WebView。
    private var signerWebView: WebView? = null

    @Volatile private var signerReady = false
    private var signerReadyProbe = 0
    private val signerMainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ★ 桌面歌词浮窗（Java TextView 版，从 mineradio-android 完整移植）
    //   前端通过 KeepApp.toggleDesktopLyric / updateDesktopLyricsState /
    //   updateDesktopLyricProgressFrac 调用，内部转调 DesktopLyricsManager
    //   原生 TextView 渲染（无 WebView 方框，纯文字 + 阴影发光 + 进入动画）
    @Volatile
    private var desktopLyricsManager: com.mineradio.desktop.DesktopLyricsManager? = null

    // ★ 桌面歌词悬浮窗权限：已请求但尚未授予（授权页返回后 onResume 中重试显示）
    private var pendingDesktopLyricEnable = false

    // 权限请求码
    private val CAMERA_PERMISSION_REQUEST = 7002
    private val RECORD_AUDIO_PERMISSION_REQUEST = 7010
    private val STORAGE_PERMISSION_REQUEST = 7007
    private val NOTIFICATION_PERMISSION_REQUEST = 7004

    // ★ 系统文件选择器请求码
    private val FILE_CHOOSER_REQUEST = 7101
    private val FOLDER_PICK_REQUEST = 7102
    private val PLUGIN_ZIP_REQUEST = 7103
    private val WALLPAPER_IMAGE_REQUEST = 7104
    private val WALLPAPER_VIDEO_REQUEST = 7105
    private val WALLPAPER_UNIFIED_REQUEST = 7106

    // ★ 用户上传壁纸：.mpkg 文件选择 / 封面图选择
    private val WALLPAPER_UPLOAD_MPKG_REQUEST = 7107
    private val WALLPAPER_UPLOAD_COVER_REQUEST = 7108
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var folderPickCallbackId: String? = null
    private var pluginZipCallbackId: String? = null

    // ★ 用于 PMKG 壁纸原生预览：保存最近一次 <input type="file"> 选中的 URI
    //   前端 File 对象在 Android WebView 没有 path 属性，无法直接传递文件路径给原生层，
    //   因此原生层在 onShowFileChooser 回调时保存 URI，供 KAppBridge.launchMpkgPreview 使用。
    @Volatile private var lastPickedFileUri: Uri? = null

    // ★ Steam 壁纸扫码连接：扫码结果 launcher（使用 zxing-android-embedded 的 ScanContract）
    private val steamWpScanLauncher =
        registerForActivityResult(
            com.journeyapps.barcodescanner.ScanContract(),
        ) { result ->
            val content = result.contents ?: ""
            if (content.isNotEmpty()) {
                // 验证是否为有效的 URL（http://IP:port 格式）
                val url = if (content.startsWith("http://") || content.startsWith("https://")) content else "http://$content"
                val resultJson =
                    org.json
                        .JSONObject()
                        .apply {
                            put("ok", true)
                            put("url", url)
                        }.toString()
                runOnUiThread {
                    webView?.evaluateJavascript("if(window.onSteamWpScanResult){window.onSteamWpScanResult('$resultJson');}", null)
                }
            } else {
                val resultJson =
                    org.json
                        .JSONObject()
                        .apply {
                            put("ok", false)
                            put("error", "扫码结果为空")
                        }.toString()
                runOnUiThread {
                    webView?.evaluateJavascript("if(window.onSteamWpScanResult){window.onSteamWpScanResult('$resultJson');}", null)
                }
            }
        }

    // ★ 上传壁纸：待上传文件的 content URI 暂存（供 uploadWallpaper 桥接读取）
    private var pendingUploadMpkgUri: Uri? = null
    private var pendingUploadCoverUri: Uri? = null

    private var wakeLock: PowerManager.WakeLock? = null

    // ★ 音域回响：原生音频采集（Visualizer/AudioRecord + FFT）
    private var sonicAudioCapture: AudioCapture? = null

    @Volatile private var sonicAudioCaptureRunning = false

    // ★ 手势推理 (Google MediaPipe HandLandmarker + CameraX)
    private var gestureRecognizerHelper: GestureRecognizerHelper? = null
    private var gestureCameraExecutor = Executors.newSingleThreadExecutor()

    // ★ MPKG 场景壁纸应用内背景渲染层
    //   当用户在壁纸库点击"设为应用背景"且壁纸类型为 MPKG 时，
    //   在根布局最底层添加 GLSurfaceView 渲染 MPKG 场景，WebView 背景设为透明
    private var mpkgBgGLSurfaceView: android.opengl.GLSurfaceView? = null
    private var mpkgBgSceneLib: io.wallpaperengine.wrapper.SceneLib? = null
    private var mpkgBgContextId = -1

    // ★ 标记 GLSurfaceView 是否已创建过一次 EGL surface（onSurfaceCreated 已触发）
    //   用于判断"恢复原背景后再次启动场景壁纸"时是否需要重建 GLSurfaceView：
    //   若 surface 已存在但 context 已被 stopMpkgBackground 销毁（contextId == -1），
    //   仅 onResume() 不会再次触发 onSurfaceCreated，场景将无法重新加载（需重启程序）。
    @Volatile private var mpkgBgGlvEverHadSurface = false

    @Volatile private var mpkgBgActive = false

    // ★ 标记 scene 是否已初始化（resizeScene 必须在 initScene 之后调用，否则 SIGSEGV）
    @Volatile private var mpkgBgSceneInitialized = false

    // ★ 当前场景是否原生支持 parallax（视差/视角移动）
    //   通过 getSceneFeatureFlags 的 bit 2 判定
    //   只对支持 parallax 的场景启用陀螺仪，避免不支持的场景显示异常
    @Volatile private var mpkgBgParallaxSupported = false

    // ★ 视口拉伸标志和全屏尺寸
    //   当壁纸宽高比与屏幕不匹配时，resizeScene 传入按壁纸宽高比计算的尺寸
    //   onDrawFrame 中每帧设置 glViewport 为全屏尺寸，拉伸画面填满屏幕
    @Volatile private var mpkgBgViewportStretch = false

    @Volatile private var mpkgBgFullscreenW = 0

    @Volatile private var mpkgBgFullscreenH = 0

    // ★ 场景画面拉伸渲染器
    //   native 层在 updateScene 中会将 viewport 设为 resizeScene 的尺寸（renderW x renderH），
    //   导致画面只在底部 renderH 高度内显示，上方空白。
    //   SceneStretchRenderer 在 updateScene 后将 Framebuffer 内容复制到纹理，
    //   然后以全屏 viewport 绘制全屏四边形，将纹理垂直拉伸到屏幕高度。
    private var mpkgBgStretchRenderer: SceneStretchRenderer? = null

    // ★ 壁纸原生宽高比（width / height）
    //   用于计算上下拉伸：按宽度填满屏幕后，如果原生高度 < 屏幕高度，则垂直拉伸
    //   左右保持原生比例（不变形），上下强制填满屏幕
    @Volatile private var mpkgBgWallpaperRatio = 0f

    // ★ 待加载的壁纸路径（surface 创建前用户点击了"设为应用背景"，缓存路径等 surface 好了再加载）
    @Volatile private var mpkgBgPendingPath: String? = null

    // ★ 当前已加载的场景壁纸路径（用于持久化保存/加载场景属性配置）
    //   mpkgBgPendingPath 在 initScene 后会被清空，所以用这个变量保存当前路径
    @Volatile private var mpkgBgCurrentScenePath: String? = null

    // ★ 视频壁纸支持（TextureView + MediaPlayer）
    //   用 TextureView 而非 SurfaceView，不挖洞，不遮挡 UI
    //   位于 WebView 之下（WebView 背景透明可见视频）
    @Volatile private var mpkgBgIsVideo = false

    @Volatile private var mpkgBgVideoFilePath: String? = null
    private var mpkgBgVideoTextureView: android.view.TextureView? = null
    private var mpkgBgMediaPlayer: android.media.MediaPlayer? = null
    private var mpkgBgVideoFileStream: java.io.FileInputStream? = null

    // ★ 场景壁纸封面截图（用于壁纸库显示封面）
    //   场景壁纸 mpkg 包内只有 .tex 纹理，无标准图片，
    //   在 onDrawFrame 第 5 帧用 glReadPixels 截图保存为 JPEG，
    //   MineradioServer 优先读取此缓存文件作为封面
    @Volatile private var mpkgBgCoverCaptureFile: java.io.File? = null

    @Volatile private var mpkgBgCoverCaptured = false

    // ★ 音频可视化（优先用 fftProcessor.bands 拦截应用内 ExoPlayer PCM，备选 Visualizer(0)）
    //   Visualizer(0) 在 Android 10+ 上抓全局混音常返回全 0，fftProcessor 直接拦截播放器 PCM 更可靠
    //   原版用 Visualizer(0) 捕获系统音频输出的 FFT 数据，处理成 64 频段后发送给场景
    private var mpkgBgVisualizer: android.media.audiofx.Visualizer? = null
    private var mpkgBgAudioFftBuffer: ByteArray? = null
    private var mpkgBgAudioResult64 = FloatArray(64)

    // ★ 陀螺仪控制场景视角（参照原版 SceneWallpaperView sensorMatrix）
    //   mobileparallax=1（陀螺仪模式）时，通过 sendNormalizedParallaxOffset 发送传感器数据
    //   sensorMatrix 根据屏幕旋转角度转换坐标
    private var mpkgBgSensorManager: android.hardware.SensorManager? = null
    private var mpkgBgAccelerometer: android.hardware.Sensor? = null
    private var mpkgBgSensorMatrix = floatArrayOf(1.0f, 1.0f, -1.0f, 0.0f, 0.0f, 0.0f)
    private var mpkgBgSensorListener: android.hardware.SensorEventListener? = null

    // ★ 低通滤波器平滑值（避免一卡一卡）
    private var mpkgBgSmoothX = 0f
    private var mpkgBgSmoothY = 0f

    @Volatile private var mpkgBgAudioEnabled = false

    // ★ 场景音频注入 Runnable：从 fftProcessor.bands（31段）线性插值映射到 64 段，通过 sendAudioData 发送给场景
    //   每 33ms（约 30fps）采样一次，与原版 Visualizer onFftDataCapture 频率接近
    private val mpkgBgAudioHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var mpkgBgAudioRunnable: Runnable? = null
    private var mpkgBgAudioLastBandsHash = 0

    // ★ 场景属性周期性更新 Handler（仅用于日志监控，不再调用 applySceneProperties 避免重置场景状态）
    //   原版壁纸引擎只在 initScene 后调用一次 applySceneProperties，之后由 native 库的 updateScene 自动推进场景时间
    //   周期性调用 applySceneProperties 会导致场景"定格"——每次都把属性重置回当前值，场景时钟无法前进
    private val mpkgBgPropsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val mpkgBgPropsRunnable =
        object : Runnable {
            override fun run() {
                try {
                    if (mpkgBgContextId >= 0 && mpkgBgSceneInitialized && mpkgBgActive) {
                        mpkgBgGLSurfaceView?.queueEvent {
                            try {
                                val flags = mpkgBgSceneLib?.getSceneFeatureFlags(mpkgBgContextId)
                                Log.d("MpkgBg", "periodic check: featureFlags=$flags, contextId=$mpkgBgContextId, frameActive=true")
                            } catch (_: Throwable) {
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
                // ★ 性能优化：从 60 秒改为 5 分钟检查一次，减少 Handler 开销
                mpkgBgPropsHandler.postDelayed(this, 300000L)
            }
        }

    // ★ 场景参数面板（齿轮按钮 + 可滚动属性列表）
    //   点击左上角齿轮按钮展开/收起参数面板，实时调整场景属性
    private var mpkgBgGearButton: android.widget.ImageButton? = null
    private var mpkgBgPropsPanel: android.widget.ScrollView? = null
    private var mpkgBgPropsContainer: android.widget.LinearLayout? = null

    @Volatile private var mpkgBgPropsPanelVisible = false
    private val mpkgBgCurrentPropsJson = org.json.JSONObject()

    @Volatile private var mpkgBgPropsReady = false

    @Volatile private var isNativeGestureActive = false

    // ★ 粒子模式标志：粒子模式下不启动 MusicPlaybackService（通知栏只有竖屏的）
    @Volatile private var isParticleMode = false

    // ★ 粒子模式频谱注入器：从竖屏 fftProcessor.bands 读取频谱注入 WebView
    private val particleSpectrumHandler = Handler(Looper.getMainLooper())
    private var particleSpectrumRunnable: Runnable? = null
    private val particleSpectrumIntervalMs = 120L // ~8fps，驱动动态效果（降低频率防白屏）
    private var gesturePreviewActive = false
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var gesturePreviewContainer: FrameLayout
    private lateinit var gesturePreviewView: PreviewView
    private lateinit var gestureOverlay: GestureOverlayView

    // ★ 独立登录 Dialog（QQ/酷狗/网易云/汽水）
    private var loginDialog: android.app.Dialog? = null
    private var loginWebView: WebView? = null
    private var loginDetectionCount = 0
    private var loginCompleted = false
    private var loginPollProvider: String? = null
    private var loginPollTimer: android.os.Handler? = null
    private var loginPollRunnable: Runnable? = null

    // ★ 汽水音乐登录 baseline 检测（一比一移植 mineradio-android MainActivity）
    //   music.douyin.com 匿名访问也会设置 sessionid/sid_guard/uid_tt, 无法靠字段名区分
    //   打开后记录初始 sessionid 作为 baseline, 之后 sessionid 值变化才认定登录成功
    private var loginBrowserOpenTime = 0L
    private var qishuiBaselineSessionId: String? = null

    // ★ haowallpaper 在线壁纸下载 Dialog（用户在 WebView 中扫码登录后下载真实文件）
    private var haoDownloadDialog: android.app.Dialog? = null
    private var haoDownloadWebView: WebView? = null
    private var haoDownloadOverlay: android.view.View? = null
    private var haoDownloadCompleted = false

    // ★ 用户点击"隐藏到后台"标志：用于区分是隐藏（保留 WebView 运行）还是真正关闭（销毁 WebView）
    private var haoDownloadHiding = false

    /**
     * ★ 清理后台运行的下载 WebView（用于用户已隐藏窗口后，下载完成/失败时调用）
     *   把 WebView 从 decorView 移除并 destroy
     *   ★ 关键：销毁前 flush CookieManager，确保扫码登录后的 cookies 被持久化
     *     这样下次创建新 WebView 时会自动加载这些 cookies，无需再次扫码登录
     */
    private fun cleanupHaoBackgroundWebView() {
        try {
            haoDownloadWebView?.let { wv ->
                // ★ 销毁前 flush cookies，确保持久化登录态
                try {
                    CookieManager.getInstance().flush()
                } catch (_: Exception) {
                }
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        } catch (_: Exception) {
        }
        haoDownloadWebView = null
        haoDownloadHiding = false
    }

    private var haoDownloadFile: File? = null
    private var haoDownloadOutputStream: FileOutputStream? = null
    private var haoDownloadMimeType: String = ""
    private var haoDownloadTotalSize: Long = 0L
    private var haoDownloadWallpaperName: String = ""
    private var haoDownloadWallpaperType: String = "image"

    // ★ 下载重试计数（用于每日上限时自动切换新环境重试）
    private var haoDownloadRetryCount = 0

    // ★ haowallpaper 登录 Dialog（首次下载需要登录，登录后 token 持久化复用）
    private var haoLoginDialog: android.app.Dialog? = null
    private var haoLoginWebView: WebView? = null
    private var haoLoginDetectionCount = 0
    private var haoLoginCompleted = false
    private var haoLoginPollTimer: android.os.Handler? = null
    private var haoLoginPollRunnable: Runnable? = null

    // 待登录完成后重试下载的参数缓存
    private var haoPendingDetailUrl: String? = null
    private var haoPendingName: String = ""
    private var haoPendingType: String = "image"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★ 显式设置时区为 Asia/Shanghai，确保 native 层 localtime() 和 V8 Date 对象使用正确时区
        //   场景壁纸的时钟只显示日期不显示时间，通常是 native C 库 localtime() 时区错误导致
        //   在 SceneLib 加载前设置 TZ 环境变量，确保 native 库初始化时时区已正确
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            System.setProperty("user.timezone", "Asia/Shanghai")
            android.system.Os.setenv("TZ", "CST-8", true)
        } catch (_: Throwable) {
        }

        // ★ 设置窗口背景为透明，确保 GLSurfaceView（场景壁纸）不会被窗口背景遮挡
        //   主题 Theme.Material.Light 默认有白色窗口背景，会覆盖 GLSurfaceView
        window.setBackgroundDrawableResource(android.R.color.transparent)

        // 强制横屏
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // ★ 标记横屏存活 + 保存状态：最后显示的是横屏 Activity
        isAlive = true
        try {
            getSharedPreferences("mineradio_activity_state", MODE_PRIVATE)
                .edit()
                .putBoolean("last_was_landscape", true)
                .apply()
        } catch (_: Exception) {
        }

        // ★ 沉浸式全屏：隐藏状态栏和导航栏（游戏级边到边显示）
        applyImmersiveFullscreen()

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ★★★ 启动动画期间优先并行加载所有壁纸功能（与启动动画并行进行）
        //   WallpaperPreloader 在后台 IO 线程中并行执行 8 项预加载，不阻塞 UI 线程
        //   ★ 多次调用安全：如果 App/MainActivity 已调用过，这里会自动跳过
        //   ★ 即便如此仍然调用，确保即使从 BootReceiver 直接拉起 LandscapeWebActivity 也能并行预加载
        com.mineradio.app.wallpaper.WallpaperPreloader
            .preloadDuringSplash(this, "LandscapeWebActivity")

        // ★★★ 启动萌宠音乐状态推送：定期把播放状态推给 PetFloatingService
        //   延迟 3 秒启动，确保 player 已初始化
        petMusicStateHandler.postDelayed({
            try {
                startPetMusicStatePush()
            } catch (e: Exception) {
                Log.w(TAG, "startPetMusicStatePush failed: ${e.message}")
            }
        }, 3000L)

        // 初始化网易云 API（Cookie 持久化等）
        NeteaseMusicApi.init(this)

        // ★ 启动时自动请求所有运行时权限
        requestAllRuntimePermissions()

        // ★ v2.2.7 已删除"检测后台自启动"弹窗（用户要求不再弹出）
        //   原调用：checkAutoStartStatus()
        //   现已彻底移除，不再检测、不再弹窗引导

        // ★★★ v2.2.7 桌面萌宠：删除后台重启后自动恢复显示
        //   ★ 问题：删除后台后开关状态持久化（仍为 true），但 PetFloatingService 已被杀死
        //   ★ 现象：JS 显示"已启用"但萌宠没显示，歌词仍显示，位置固定失效
        //   ★ 修复：App 启动时检测开关状态，若开启但服务未运行则自动启动
        try {
            val petEnabled =
                com.mineradio.app.wallpaper.WallpaperManager
                    .get(this)
                    .getDesktopPetEnabled()
            val petRunning = com.mineradio.app.wallpaper.PetFloatingService.isRunning
            if (petEnabled && !petRunning) {
                Log.i(TAG, "onCreate: petEnabled=true but service not running, auto-starting...")
                if (android.provider.Settings.canDrawOverlays(this)) {
                    com.mineradio.app.wallpaper.PetFloatingService
                        .start(this)
                    Log.i(TAG, "onCreate: PetFloatingService auto-started")
                } else {
                    Log.w(TAG, "onCreate: overlay permission not granted, cannot auto-start pet")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onCreate: auto-restart PetFloatingService failed", e)
        }

        // ★ 已删除内置 HTML 壁纸导入逻辑（用户要求删除内置 HTML 壁纸）

        // ★ 壁纸功能已在前面（early init）处理，这里不再重复

        // 启动嵌入式 HTTP 服务器
        server = MineradioServer(this, 8800)
        serverPort = server!!.start()

        // ★ 启动 Node.js 后端（login-panel：提供登录/搜索/歌曲等平台 API）
        //   由 mineradio-android 移植，监听 127.0.0.1:3000，Kotlin 后端 8800 会把平台 API 代理到它
        try {
            com.mineradio.desktop.nodejs.NodeJSMobile
                .start(this, null)
        } catch (e: Throwable) {
            Log.w(TAG, "NodeJSMobile start failed", e)
        }

        // ★ 清除 WebView GPU/Shader 缓存 — 防止损坏的 GPU 缓存导致 Chromium DCHECK 崩溃 (SIGTRAP)
        //   损坏的 GrShaderCache/GPUCache 会在启动时触发 Chromium ThreadPoolSingl 线程的 DCHECK 断言
        //   导致 App 在启动后 14-24 秒崩溃 (signal 5 SIGTRAP)
        try {
            val webviewDir = File(getDataDir(), "app_webview")
            if (webviewDir.exists()) {
                listOf("GPUCache", "GrShaderCache", "GraphiteDawnCache", "ShaderCache").forEach { sub ->
                    val dir = File(webviewDir, sub)
                    if (dir.exists()) {
                        dir.deleteRecursively()
                        Log.i("GpuCompat", "Cleared WebView cache: $sub")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("GpuCompat", "Failed to clear WebView GPU caches", e)
        }

        webView =
            WebView(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                // ★ GPU 兼容性策略 v2：始终使用硬件加速层
                //   之前的方案是在问题设备上切到 LAYER_TYPE_SOFTWARE，但软件层
                //   在 3D 粒子背景下极度卡顿，体验远差于硬件层偶发白屏。
                //   新策略：硬件层 + JS 侧 GpuMemoryGuard 循环释放 GPU 内存
                //   (见 视觉与交互.js softResetGpuMemory)，把 GPU 显存占用
                //   控制在 ~200MB 以内，避免 SharedImage 创建失败导致白屏。
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                Log.i("GpuCompat", "Using hardware layer (GPU memory managed by JS GpuMemoryGuard)")

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    // ★ 允许 file:// URI 直接加载资源（壁纸应用背景需要读取本地文件）
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = true
                    // ★★ 允许 http(s) 页面访问 file:// URI
                    //   壁纸预览的"设为应用背景"对视频壁纸走 file:// URI，
                    //   主 WebView 从 http://localhost:port 加载，必须开启此项才能加载 file:// 视频
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // ★ 支持 window.open 在当前 WebView 内打开（避免启动系统浏览器导致返回键回到竖屏UI）
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                }
                // ★ 保存默认 User-Agent，便于登录后恢复
                // (不再需要 — 登录页在独立 Dialog 中加载)

                // 添加 KeepApp JS 桥接接口
                addJavascriptInterface(KAppBridge(), "KeepApp")

                // ★ 注册 AndroidExternal 签名桥接接口
                //   前端登录面板通过它调用原生签名 WebView 生成 a_bogus 签名、打开应用内浏览器登录页
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun open(url: String) {
                            runOnUiThread {
                                val target = if (url.isBlank()) "https://www.baidu.com/" else url
                                // 根据 URL 自动推断登录 provider，使 checkLoginCookie 能正确捕获并回传 cookie
                                val provider =
                                    when {
                                        target.contains("douyin.com") -> "qishui"
                                        target.contains("y.qq.com") || target.contains("qq.com") -> "qq"
                                        target.contains("kugou") -> "kg"
                                        target.contains("163.com") -> "netease"
                                        else -> "generic"
                                    }
                                showLoginDialog(target, provider)
                            }
                        }

                        @JavascriptInterface
                        fun openQishuiMusicLogin(url: String) {
                            val target = if (url.isBlank()) "https://music.douyin.com/" else url
                            runOnUiThread { showLoginDialog(target, "qishui") }
                        }

                        @JavascriptInterface
                        fun startQishuiQRLogin() {
                            runOnUiThread {
                                showLoginDialog(
                                    "https://sso.douyin.com/login_qr/?aid=2906&service=https://www.douyin.com",
                                    "qishui",
                                )
                            }
                        }

                        @JavascriptInterface
                        fun cancelQishuiQRLogin() {
                            runOnUiThread {
                                try {
                                    loginDialog?.dismiss()
                                } catch (_: Exception) {
                                }
                            }
                        }

                        @JavascriptInterface
                        fun signedRequest(
                            payloadJson: String,
                            callbackId: String,
                        ) {
                            signerMainHandler.post { dispatchSignedRequest(payloadJson, callbackId) }
                        }

                        @JavascriptInterface
                        fun isSignerReady(): Boolean = signerReady

                        @JavascriptInterface
                        fun showSignerMfa(decisionJson: String) {
                            signerMainHandler.post { showSignerMfaPanel(decisionJson) }
                        }

                        @JavascriptInterface
                        fun hideSignerMfa() {
                            signerMainHandler.post { hideSignerMfaPanel() }
                        }
                    },
                    "AndroidExternal",
                )

                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = false

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val url = request.url.toString()
                            if (url.contains("__mpkg_bg_video__")) {
                                val path = mpkgBgVideoFilePath
                                if (path != null) {
                                    val file = java.io.File(path)
                                    if (file.exists()) {
                                        Log.d("MpkgBg", "shouldInterceptRequest: serving video file $path")
                                        return WebResourceResponse(
                                            "video/mp4",
                                            null,
                                            java.io.FileInputStream(file),
                                        )
                                    }
                                }
                                Log.w("MpkgBg", "shouldInterceptRequest: video file not available")
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(
                            view: WebView?,
                            url: String?,
                        ) {
                            if (!bridgeInjected) {
                                bridgeInjected = true
                                view?.post { view.evaluateJavascript(BRIDGE_JS, null) }
                                // ★ 注册通知栏按钮回调（MediaNotificationState → WebView JS）
                                view?.let { registerNotificationCallbacks(it) }
                            }
                            // ★ 粒子模式：注入当前播放歌曲的 mediaStoreId
                            val mediaStoreId = intent?.getLongExtra("currentMediaStoreId", -1L) ?: -1L
                            if (mediaStoreId > 0) {
                                view?.postDelayed({
                                    view.evaluateJavascript(
                                        "window.__PARTICLE_MEDIA_STORE_ID__ = $mediaStoreId;",
                                        null,
                                    )
                                }, 500)
                            }
                            // ★ 粒子模式：同步竖屏音效预设（0=关闭, 1=3D环绕, 3=360环绕）
                            val soundPreset = intent?.getIntExtra("soundPreset", 0) ?: 0
                            view?.postDelayed({
                                view.evaluateJavascript(
                                    "window.__PARTICLE_SOUND_PRESET__ = $soundPreset;",
                                    null,
                                )
                            }, 600)
                            // 修复黑屏：强制刷新
                            view?.postDelayed({
                                view.evaluateJavascript(
                                    "try{if(document.body&&!document.body.classList.contains('loaded'))document.body.classList.add('loaded')}catch(e){}",
                                    null,
                                )
                            }, 300)
                            // ★ 粒子模式：启动频谱注入，为动态效果提供频谱数据
                            //   audio 元素被静音不播放，analyser 读不到数据，
                            //   通过 fftProcessor.bands（ExoPlayer PCM 拦截）读取竖屏频谱，
                            //   经 window.__feedAudio 注入，再 patch analyser 填充 frequencyData
                            if (isParticleMode) {
                                view?.postDelayed({
                                    startParticleSpectrumInjection()
                                }, 1500)
                            }
                        }

                        // ★★★ v4.4: 渲染进程崩溃处理 — 永不白屏，永不重启到启动动画
                        //   当 Chromium 渲染进程崩溃（GPU 驱动 bug 导致）时：
                        //   - 不 reload（会触发启动动画）
                        //   - 带 skipSplash=1 参数 reload，JS 端检测到此参数跳过启动动画
                        //   - 返回 true 表示我们自己处理（不让 WebView 默认销毁）
                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: android.webkit.RenderProcessGoneDetail?,
                        ): Boolean {
                            Log.e("GpuCompat", "★★★ Render process gone! crash=${detail?.didCrash()}")
                            // ★ 带 skipSplash=1 参数 reload，跳过启动动画
                            view?.post {
                                try {
                                    var reloadUrl = view?.url ?: "http://127.0.0.1:$serverPort/"
                                    if (reloadUrl.contains("skipSplash=1")) {
                                        // already has skipSplash
                                    } else {
                                        reloadUrl += if (reloadUrl.contains("?")) "&skipSplash=1" else "?skipSplash=1"
                                    }
                                    Log.w("GpuCompat", "Reloading after render process gone (skipSplash): $reloadUrl")
                                    view?.loadUrl(reloadUrl)
                                } catch (e: Throwable) {
                                    Log.e("GpuCompat", "Failed to reload after render process gone", e)
                                }
                            }
                            return true // ★ 返回 true：我们自己处理，不让 WebView 默认销毁
                        }
                    }

                webChromeClient =
                    object : WebChromeClient() {
                        // ★ 将 JS console.log 转发到 logcat（方便调试粒子模式）
                        //   同时检测 GPU 错误，通知 JS 端 GpuMemoryGuard
                        private var gpuErrorNotifyCount = 0
                        private var lastGpuErrorNotifyAt = 0L

                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            val msg = consoleMessage?.message() ?: ""
                            val src = consoleMessage?.sourceId()?.substringAfterLast('/') ?: ""
                            val line = consoleMessage?.lineNumber() ?: 0
                            val level = consoleMessage?.messageLevel() ?: android.webkit.ConsoleMessage.MessageLevel.DEBUG
                            // GPU 错误检测：chromium GPU 进程错误会通过 console.error 转发
                            if (level == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                if (msg.contains("non-existent mailbox") ||
                                    msg.contains("SharedImageFormat") ||
                                    msg.contains("GPU state invalid") ||
                                    msg.contains("AHardwareBuffer") ||
                                    msg.contains("SharedImageStub")
                                ) {
                                    gpuErrorNotifyCount++
                                    val now = System.currentTimeMillis()
                                    // 限流：每 2 秒最多通知一次（避免大量日志淹没 JS）
                                    if (now - lastGpuErrorNotifyAt > 2000) {
                                        lastGpuErrorNotifyAt = now
                                        val count = gpuErrorNotifyCount
                                        webView?.post {
                                            webView?.evaluateJavascript(
                                                "try{if(typeof GpuMemoryGuard!=='undefined'&&GpuMemoryGuard.onGpuError){for(var i=0;i<$count;i++)GpuMemoryGuard.onGpuError();}}catch(e){}",
                                                null,
                                            )
                                        }
                                        Log.w("GpuCompat", "GPU error detected (count=$count), notified JS GpuMemoryGuard")
                                    }
                                }
                            }
                            Log.d("WebViewConsole", "[$src:$line] $msg")
                            return true
                        }

                        // ★ 实现 onShowFileChooser：让 <input type="file"> 调用系统文件管理器
                        override fun onShowFileChooser(
                            webView: WebView?,
                            cb: ValueCallback<Array<Uri>>?,
                            params: FileChooserParams?,
                        ): Boolean {
                            filePathCallback?.onReceiveValue(null)
                            filePathCallback = cb
                            try {
                                val intent =
                                    params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "*/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                    }
                                @Suppress("DEPRECATION")
                                startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                            } catch (e: Exception) {
                                filePathCallback = null
                                Toast.makeText(this@LandscapeWebActivity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                                return false
                            }
                            return true
                        }

                        // ★ 拦截 window.open 请求，在当前 WebView 中加载 URL（避免启动系统浏览器）
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?,
                        ): Boolean {
                            // 创建临时 WebView 拦截目标 URL
                            val targetWebView = WebView(this@LandscapeWebActivity)
                            targetWebView.webViewClient =
                                object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        v: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        // 将新窗口 URL 在主 WebView 中加载
                                        webView.loadUrl(request.url.toString())
                                        targetWebView.destroy()
                                        return true
                                    }
                                }
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            transport?.webView = targetWebView
                            resultMsg?.sendToTarget()
                            return true
                        }

                        // ★ 拦截 JS confirm() 弹窗，改为原生 AlertDialog
                        override fun onJsConfirm(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            result: android.webkit.JsResult?,
                        ): Boolean {
                            android.app.AlertDialog
                                .Builder(this@LandscapeWebActivity)
                                .setMessage(message ?: "")
                                .setPositiveButton("确定") { _, _ -> result?.confirm() }
                                .setNegativeButton("取消") { _, _ -> result?.cancel() }
                                .setOnCancelListener { result?.cancel() }
                                .show()
                            return true
                        }
                    }

                // 通过 HTTP 服务器加载页面（解决 API 请求和 CORS 问题）
                // ★ 支持 mode=particle 参数：加载 index.html + URL query，JS 端隐藏不需要的 UI
                val mode = intent?.getStringExtra("mode") ?: "default"
                isParticleMode = (mode == "particle")
                val targetPath = if (mode == "particle") "/?mode=particle" else "/"
                loadUrl("http://127.0.0.1:$serverPort$targetPath")
            }

        // ★★ 启动 logcat GPU 错误监控
        //   chromium 原生 GPU 错误（SharedImage/AHardwareBuffer 创建失败）通过 logcat 输出
        //   不会走 JS console，所以 onConsoleMessage 检测不到
        //   通过 logcat 监控这些错误，转发给 JS GpuMemoryGuard.onGpuError()
        startGpuErrorLogcatMonitor()

        // ★ 初始化手势识别 UI 组件（动态添加到 FrameLayout）
        gesturePreviewContainer =
            FrameLayout(this).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                visibility = View.GONE
            }
        gesturePreviewView =
            PreviewView(this).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
            }
        gestureOverlay =
            GestureOverlayView(this).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                visibility = View.GONE
            }
        gesturePreviewContainer.addView(gesturePreviewView)
        gesturePreviewContainer.addView(gestureOverlay)

        // ★ MPKG 应用背景 GLSurfaceView（初始隐藏，壁纸类型为 MPKG 时才显示）
        //   位于根布局最底层，WebView 在其上方（WebView 背景需设为透明才能看到）
        mpkgBgGLSurfaceView = createMpkgBgGLSurfaceView()

        // ★ 触摸事件转发已移到 Activity.dispatchTouchEvent 中
        //   原因：GLSurfaceView 在 WebView 之下，setOnTouchListener 收不到触摸事件
        //   现在在 Activity 级别拦截，确保触摸能转发给场景壁纸（视差移动+触摸输入）

        // ★ 视频壁纸 TextureView（初始隐藏，视频壁纸类型时才显示）
        //   用 TextureView 而非 SurfaceView，不挖洞，不遮挡 UI
        //   放在 WebView 之下，通过 WebView 透明背景显示
        mpkgBgVideoTextureView =
            android.view.TextureView(this).apply {
                visibility = android.view.View.GONE
                surfaceTextureListener =
                    object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: android.graphics.SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            Log.d("MpkgBg", "Video TextureView available: ${width}x$height")
                            val pendingPath = mpkgBgPendingPath
                            if (mpkgBgIsVideo && pendingPath != null) {
                                startMpkgVideoPlayback(surface, pendingPath)
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: android.graphics.SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                        }

                        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                            stopMpkgVideoPlayback()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                        }
                    }
            }

        // 使用 FrameLayout 包裹 webView 和手势预览层
        val rootLayout =
            FrameLayout(this).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
            }
        // ★ 顺序：GL背景层(底) → 视频TextureView → WebView(中间,背景透明) → 手势预览层(顶)
        //   TextureView 是普通 View 不挖洞，放 WebView 之下，通过 WebView 透明背景显示
        rootLayout.addView(mpkgBgGLSurfaceView)
        rootLayout.addView(mpkgBgVideoTextureView)
        rootLayout.addView(webView)
        rootLayout.addView(gesturePreviewContainer)

        // ★ 场景参数面板齿轮按钮（已废弃 — 改用 HTML 外观面板中的"场景参数"折叠区）
        //   保留空 View 占位以兼容旧代码引用，实际不显示
        mpkgBgGearButton =
            android.widget.ImageButton(this).apply {
                visibility = android.view.View.GONE
                layoutParams = FrameLayout.LayoutParams(0, 0)
            }
        rootLayout.addView(mpkgBgGearButton)

        // ★ 场景参数面板（已废弃 — 改用 HTML 外观面板中的"场景参数"折叠区）
        //   保留空 View 占位以兼容旧代码引用，实际不显示
        mpkgBgPropsContainer =
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
        mpkgBgPropsPanel =
            android.widget.ScrollView(this).apply {
                visibility = android.view.View.GONE
                layoutParams = FrameLayout.LayoutParams(0, 0)
                addView(mpkgBgPropsContainer)
            }
        rootLayout.addView(mpkgBgPropsPanel)

        setContentView(rootLayout)

        // ★ 初始化汽水音乐安全签名 WebView（bdms.js 生成 a_bogus 签名）
        //   放在主 webView 创建之后，确保 checkSignerReady 能安全访问 webView
        initQishuiSignerOuter()

        // ★ 启动时检查应用背景配置：如果是 MPKG 类型，自动启动原生背景渲染
        checkAndStartMpkgBackground()
    }

    /**
     * ★ 检查应用背景配置，如果是 MPKG 类型则自动启动原生背景渲染
     *   在 onCreate 末尾调用，确保用户之前设置的 MPKG 应用背景能在重启后恢复
     */
    private fun checkAndStartMpkgBackground() {
        try {
            val wm =
                com.mineradio.app.wallpaper.WallpaperManager
                    .get(this)
            val appBg = wm.getAppBackgroundWallpaper()
            if (appBg != null && appBg.wallpaperType == com.mineradio.app.wallpaper.WallpaperEntity.TYPE_MPKG_WEBGL) {
                val realPath = appBg.getRealPath(this)
                val file = java.io.File(realPath)
                if (file.exists()) {
                    Log.d("MpkgBg", "checkAndStartMpkgBackground: auto start for ${appBg.name}, path=$realPath")
                    // 延迟启动，确保 GLSurfaceView 已完成 surfaceCreated
                    mpkgBgGLSurfaceView?.postDelayed({ startMpkgBackground(realPath) }, 800)
                } else {
                    Log.w("MpkgBg", "checkAndStartMpkgBackground: file not found: $realPath")
                }
            }
        } catch (e: Throwable) {
            Log.e("MpkgBg", "checkAndStartMpkgBackground failed", e)
        }
    }

    /**
     * ★ 创建 MPKG 应用背景 GLSurfaceView
     *   提取为独立函数，首次启动和切换场景时都调用此函数
     *   切换场景时完全重新创建 GLSurfaceView，确保与首次启动流程完全一致
     *   避免销毁 context 后 native GL 状态不完整导致画面缩在左下角
     */
    private fun createMpkgBgGLSurfaceView(): android.opengl.GLSurfaceView =
        android.opengl.GLSurfaceView(this).apply {
            // ★ 与 LWService 一致：使用 ES 3.0
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            visibility = android.view.View.GONE
            // ★ 显式设置全屏 LayoutParams，确保 resizeScene 传入正确的屏幕尺寸
            //   缺少显式 LayoutParams 时某些机型 FrameLayout 默认尺寸不正确
            layoutParams =
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                )
            setRenderer(
                object : android.opengl.GLSurfaceView.Renderer {
                    override fun onSurfaceCreated(
                        gl: javax.microedition.khronos.opengles.GL10?,
                        config: javax.microedition.khronos.egl.EGLConfig?,
                    ) {
                        try {
                            mpkgBgSceneLib = io.wallpaperengine.wrapper.SceneLib()
                            mpkgBgSceneLib!!.initLibrary(this@LandscapeWebActivity)
                            mpkgBgContextId = mpkgBgSceneLib!!.initContext(this@LandscapeWebActivity)
                            mpkgBgGlvEverHadSurface = true
                            Log.d("MpkgBg", "onSurfaceCreated: contextId=$mpkgBgContextId")
                            // ★ 初始化场景画面拉伸渲染器
                            mpkgBgStretchRenderer = SceneStretchRenderer()
                            // ★ 如果 surface 创建前用户已经点击了"设为应用背景"，立即加载场景
                            val pendingPath = mpkgBgPendingPath
                            if (mpkgBgContextId >= 0 && pendingPath != null) {
                                try {
                                    // ★ 保存当前场景路径（用于持久化保存/加载场景属性）
                                    mpkgBgCurrentScenePath = pendingPath
                                    mpkgBgSceneLib?.initScene(mpkgBgContextId, pendingPath)
                                    mpkgBgSceneLib?.setLogToFileEnabled(false)
                                    mpkgBgSceneInitialized = true
                                    mpkgBgPendingPath = null
                                    Log.d("MpkgBg", "onSurfaceCreated: initScene ok for pending path=$pendingPath")
                                    // ★★★ 不调用 applyDefaultSceneProperties（会导致画面放大）
                                    //   只单独设置 parallax 属性启用陀螺仪
                                    try {
                                        applyParallaxOnly()
                                    } catch (e: Throwable) {
                                        Log.e("MpkgBg", "onSurfaceCreated applyParallaxOnly failed", e)
                                    }
                                    // ★★★ 应用用户保存的场景属性（恢复用户上次调整的参数效果）
                                    //   跳过 alignment（避免画面放大）和 mobileparallax/strength（由 applyParallaxOnly 控制）
                                    //   修复：按钮状态保存了但实际效果没保存
                                    try {
                                        applyUserSavedScenePropsOnly()
                                    } catch (e: Throwable) {
                                        Log.e("MpkgBg", "onSurfaceCreated applyUserSavedScenePropsOnly failed", e)
                                    }
                                    // ★ initScene 后不调用 resizeScene
                                    //   与 LWService 一致，resizeScene 由 onSurfaceChanged 负责
                                    //   避免传入错误尺寸导致画面显示不全
                                    // ★ 启动周期性属性更新（仅日志监控）
                                    //   性能优化：从 30 秒改为 5 分钟首次检查
                                    mpkgBgPropsHandler.postDelayed(mpkgBgPropsRunnable, 300000L)
                                    // ★ 启动陀螺仪传感器（驱动场景视角移动）
                                    //   只对原生支持 parallax 的场景启用
                                    if (mpkgBgParallaxSupported) {
                                        startMpkgBgSensor()
                                    } else {
                                        Log.d("MpkgBg", "skip startMpkgBgSensor: scene does NOT support parallax")
                                    }
                                    // ★ 启动音频录制（发送给场景用于音频可视化）
                                    startMpkgBgAudioRecording()
                                    // ★ 通知 HTML 面板刷新场景参数
                                    runOnUiThread {
                                        webView.evaluateJavascript(
                                            "try{if(typeof refreshScenePropsPanel==='function')refreshScenePropsPanel();}catch(e){}",
                                            null,
                                        )
                                    }
                                } catch (e: Throwable) {
                                    Log.e("MpkgBg", "onSurfaceCreated: initScene failed", e)
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e("MpkgBg", "onSurfaceCreated failed", e)
                        }
                    }

                    override fun onSurfaceChanged(
                        gl: javax.microedition.khronos.opengles.GL10?,
                        width: Int,
                        height: Int,
                    ) {
                        try {
                            Log.d(
                                "MpkgBg",
                                "onSurfaceChanged: gl=$width x $height, ratio=$mpkgBgWallpaperRatio",
                            )

                            // ★ 保存全屏尺寸
                            mpkgBgFullscreenW = width
                            mpkgBgFullscreenH = height

                            // ★ 100%比例：传入屏幕尺寸给 resizeScene（与 LWService 一致）
                            android.opengl.GLES20.glViewport(0, 0, width, height)
                            if (mpkgBgContextId >= 0 && mpkgBgSceneInitialized) {
                                mpkgBgSceneLib?.resizeScene(mpkgBgContextId, width, height)
                            }
                        } catch (e: Throwable) {
                            Log.e("MpkgBg", "onSurfaceChanged failed", e)
                        }
                    }

                    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
                        try {
                            if (mpkgBgContextId >= 0 && mpkgBgSceneInitialized && mpkgBgActive) {
                                mpkgBgSceneLib?.updateScene(mpkgBgContextId)
                                frameCount++
                                // ★ 性能优化：减少日志输出，从每 120 帧改为每 600 帧（10秒@60fps）
                                if (frameCount % 600 == 1) {
                                    Log.d("MpkgBg", "onDrawFrame: frame=$frameCount, contextId=$mpkgBgContextId")
                                }
                                // ★ 第 8 帧截图保存为封面（场景已渲染稳定）
                                //   只截一次，避免每帧截图影响性能
                                if (frameCount == 8 && !mpkgBgCoverCaptured) {
                                    captureSceneCover(gl)
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e("MpkgBg", "onDrawFrame failed", e)
                        }
                    }

                    /**
                     * ★ 用 glReadPixels 截取当前 GL 帧保存为 JPEG 封面
                     *   场景壁纸 mpkg 包内只有 .tex 纹理无标准图片，
                     *   截图后 MineradioServer 读取此文件作为壁纸库封面
                     */
                    private fun captureSceneCover(gl: javax.microedition.khronos.opengles.GL10?) {
                        try {
                            val glv = mpkgBgGLSurfaceView ?: return
                            val w = glv.width
                            val h = glv.height
                            if (w <= 0 || h <= 0) return
                            val coverFile = mpkgBgCoverCaptureFile ?: return
                            if (coverFile.exists()) {
                                mpkgBgCoverCaptured = true
                                return
                            }
                            // ★ 降采样截图（避免全屏 3040x1904 像素 buffer 过大）
                            //   目标宽度 480px，按比例缩放高度
                            val targetW = 480
                            val targetH = (h.toFloat() / w.toFloat() * targetW).toInt().coerceAtLeast(1)
                            // 创建降采样 buffer（rgba）
                            val buf = java.nio.ByteBuffer.allocateDirect(targetW * targetH * 4)
                            // 设置 viewport 为降采样尺寸并渲染一帧
                            try {
                                mpkgBgSceneLib?.resizeScene(mpkgBgContextId, targetW, targetH)
                                mpkgBgSceneLib?.updateScene(mpkgBgContextId)
                            } catch (_: Throwable) {
                            }
                            android.opengl.GLES20.glReadPixels(
                                0,
                                0,
                                targetW,
                                targetH,
                                android.opengl.GLES20.GL_RGBA,
                                android.opengl.GLES20.GL_UNSIGNED_BYTE,
                                buf,
                            )
                            // 恢复原 viewport（使用渲染尺寸）
                            try {
                                val rH =
                                    if (mpkgBgWallpaperRatio > 0f) {
                                        (w / mpkgBgWallpaperRatio).toInt().coerceAtLeast(1)
                                    } else {
                                        h
                                    }
                                mpkgBgSceneLib?.resizeScene(mpkgBgContextId, w, rH)
                            } catch (_: Throwable) {
                            }
                            // 转为 Bitmap（GL 像素原点在左下，需要垂直翻转）
                            val bmp = android.graphics.Bitmap.createBitmap(targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888)
                            buf.rewind()
                            bmp.copyPixelsFromBuffer(buf)
                            // 垂直翻转
                            val matrix = android.graphics.Matrix()
                            matrix.preScale(1f, -1f)
                            val flipped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, targetW, targetH, matrix, true)
                            bmp.recycle()
                            // 保存为 JPEG
                            coverFile.parentFile?.mkdirs()
                            java.io.FileOutputStream(coverFile).use { fos ->
                                flipped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, fos)
                            }
                            flipped.recycle()
                            mpkgBgCoverCaptured = true
                            Log.d("MpkgBg", "captureSceneCover: saved to ${coverFile.absolutePath}, ${targetW}x$targetH")
                        } catch (e: Throwable) {
                            Log.e("MpkgBg", "captureSceneCover failed", e)
                        }
                    }

                    private var frameCount = 0
                },
            )
            renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

    /**
     * ★ 启动 MPKG 应用背景渲染
     *   当用户设置 MPKG 壁纸为应用背景时调用
     *   - 自动将 mpkg 复制到 app 私有目录（native 库需要）
     *   - 场景壁纸：GLSurfaceView + SceneLib 渲染
     *   - 视频壁纸：SurfaceView + MediaPlayer 渲染
     */
    fun startMpkgBackground(wallpaperPath: String) {
        runOnUiThread {
            try {
                Log.d("MpkgBg", "startMpkgBackground: path=$wallpaperPath, contextId=$mpkgBgContextId, sceneInit=$mpkgBgSceneInitialized")
                mpkgBgActive = true

                // ★ 先停止旧的传感器和音频录制（避免切换场景时竞态崩溃）
                stopMpkgBgSensor()
                stopMpkgBgAudioRecording()

                // ★ 不再复制到私有目录，直接用原文件路径（参照原版壁纸引擎）
                //   native 层 initScene 内部用虚拟文件系统按需 seek 读取，支持任意可读路径
                //   之前复制到私有目录会浪费存储空间且增加切换延迟
                val privatePath = wallpaperPath
                Log.d("MpkgBg", "using original path directly: $privatePath")

                // ★ 检测壁纸类型（场景 or 视频）
                //   ★ 用 MpkgParser 快速检测，不调用 native getWallpaperType（对 200MB+ 文件会阻塞 UI 线程）
                //   MpkgParser 只读取头部条目表（几 KB），不加载整个文件
                var wType: String? = null
                try {
                    val parser =
                        com.mineradio.app.wallpaper
                            .MpkgParser(java.io.File(privatePath))
                    val entries = parser.listEntries()
                    // ★ 检测所有常见视频格式（与 extractVideoFile 的判定保持一致）
                    //   之前只判 .mp4 会导致 .webm/.mkv/.mov 等视频壁纸被误识别为场景壁纸
                    val hasVideo =
                        entries.any { e ->
                            val lower = e.name.lowercase()
                            lower.endsWith(".mp4") ||
                                lower.endsWith(".webm") ||
                                lower.endsWith(".mkv") ||
                                lower.endsWith(".mov") ||
                                lower.endsWith(".avi") ||
                                lower.endsWith(".m4v")
                        }
                    wType = if (hasVideo) "Video" else "Scene"
                    Log.d("MpkgBg", "MpkgParser detected type=$wType, entries=${entries.size}, names=${entries.map { it.name }}")
                } catch (e: Throwable) {
                    Log.e("MpkgBg", "MpkgParser type detection failed", e)
                }

                if (wType == "Video") {
                    // ★ 视频壁纸：用 TextureView + MediaPlayer 播放（不走 initScene）
                    //   native 库 initScene 不支持纯视频壁纸（返回空场景属性 → 黑屏）
                    //   原版用 VideoWallpaperView(GLSurfaceView+SurfaceTexture+GLSL)，
                    //   这里简化用 TextureView + MediaPlayer 直接播放提取的 mp4
                    Log.d("MpkgBg", "Video wallpaper, using TextureView + MediaPlayer")
                    mpkgBgIsVideo = true
                    // 隐藏 GLSurfaceView（视频壁纸不用 GL 渲染）
                    mpkgBgGLSurfaceView?.visibility = android.view.View.GONE
                    mpkgBgGLSurfaceView?.onPause()
                    // 显示 TextureView 用于视频播放
                    mpkgBgVideoTextureView?.visibility = android.view.View.VISIBLE
                    stopMpkgVideoPlayback()
                    // ★ 从 mpkg 提取 mp4 到私有目录
                    try {
                        val videoFile =
                            com.mineradio.app.wallpaper.MpkgParser
                                .extractVideoFile(privatePath, filesDir)
                        Log.d("MpkgBg", "extracted video: ${videoFile?.absolutePath}")
                        if (videoFile != null && videoFile.exists()) {
                            mpkgBgVideoFilePath = videoFile.absolutePath
                            mpkgBgPendingPath = privatePath
                            // ★ TextureView 的 onSurfaceTextureAvailable 会触发 startMpkgVideoPlayback
                            //   如果 TextureView 已经 available，直接启动播放
                            val st = mpkgBgVideoTextureView?.surfaceTexture
                            if (st != null) {
                                startMpkgVideoPlayback(st, privatePath)
                            }
                            // 注入 JS 让 WebView 透明（视频在 WebView 之下）
                            injectTransparentJs()
                        } else {
                            Log.e("MpkgBg", "extracted video file is null or not exists")
                        }
                    } catch (e: Throwable) {
                        Log.e("MpkgBg", "extract video failed", e)
                    }
                    return@runOnUiThread
                }

                // ★ 场景壁纸：用 GLSurfaceView + SceneLib
                mpkgBgIsVideo = false
                // 隐藏视频 TextureView
                mpkgBgVideoTextureView?.visibility = android.view.View.GONE
                stopMpkgVideoPlayback()
                // ★ 设置封面截图缓存文件（onDrawFrame 第 8 帧自动截图保存）
                //   文件名用 mpkg 文件名（去扩展名），避免不同壁纸冲突
                val coverName = java.io.File(privatePath).nameWithoutExtension + ".jpg"
                mpkgBgCoverCaptureFile = java.io.File(java.io.File(filesDir, "wallpaper_covers"), coverName)
                mpkgBgCoverCaptured = mpkgBgCoverCaptureFile?.exists() == true
                // ★ GLSurfaceView 在 WebView 之下（普通 View 层级，WebView 背景透明可见）
                val glv = mpkgBgGLSurfaceView
                Log.d(
                    "MpkgBg",
                    "GLSurfaceView: isNull=${glv == null}, visibility=${glv?.visibility}, width=${glv?.width}, height=${glv?.height}, isAttached=${glv?.isAttachedToWindow}",
                )
                // 注入 JS 让 WebView 透明
                injectTransparentJs()
                // ★ 性能优化：从 3 次延迟调用减为 1 次（500ms 足够应对 DOM 重建）
                webView.postDelayed({ if (mpkgBgActive) injectTransparentJs() }, 800)
                // 显示 GLSurfaceView
                // ★ ★ ★ 关键修复：切换场景时完全重新创建 GLSurfaceView
                //   旧方案（destroyContext + initContext + initScene + resizeScene）会导致画面缩在左下角
                //   原因：destroyContext 后 native GL 状态不完整，resizeScene 无法正确设置 viewport
                //   新方案：移除旧 GLSurfaceView，创建新的，触发完整的 onSurfaceCreated → onSurfaceChanged
                //   与首次启动流程完全一致，不会出现画面缩放问题
                // ★ 修复恢复原背景后再次启动场景壁纸：仅 contextId >= 0 判断不够，
                //   恢复原背景时 stopMpkgBackground 会在 GL 线程把 contextId 置为 -1，
                //   但 surface 仍已存在；此时走"首次启动"分支仅 onResume() 不会再次触发
                //   onSurfaceCreated，导致场景无法重新加载（需重启程序）。
                //   只要 GLSurfaceView 曾创建过 surface，就一律重建，确保干净触发 surface 生命周期。
                val needRecreate = mpkgBgGLSurfaceView != null && (mpkgBgContextId >= 0 || mpkgBgGlvEverHadSurface)
                if (needRecreate) {
                    Log.d("MpkgBg", "switching scene: recreating GLSurfaceView (old contextId=$mpkgBgContextId)")
                    // 1. 在旧 GL 线程销毁旧 context（必须在 GL 线程调用）
                    val oldGlv = mpkgBgGLSurfaceView
                    oldGlv?.queueEvent {
                        try {
                            if (mpkgBgSceneInitialized) {
                                try {
                                    mpkgBgSceneLib?.shutdownScene(mpkgBgContextId)
                                } catch (_: Throwable) {
                                }
                                mpkgBgSceneInitialized = false
                            }
                            if (mpkgBgContextId >= 0) {
                                try {
                                    mpkgBgSceneLib?.destroyContext(mpkgBgContextId)
                                } catch (_: Throwable) {
                                }
                            }
                            mpkgBgContextId = -1
                            Log.d("MpkgBg", "old context destroyed for GLSurfaceView recreation")
                        } catch (_: Throwable) {
                        }
                    }
                    // 2. 在 UI 线程移除旧 GLSurfaceView
                    oldGlv?.onPause()
                    (oldGlv?.parent as? android.view.ViewGroup)?.removeView(oldGlv)
                    // 3. 创建新 GLSurfaceView
                    mpkgBgGLSurfaceView = createMpkgBgGLSurfaceView()
                    // 4. 设置 pendingPath，onSurfaceCreated 会读取并 initScene
                    mpkgBgPendingPath = privatePath
                    // 5. 添加到布局最底层（index 0）— 与 WebView 同一个 FrameLayout
                    val parentVg = webView.parent as? android.view.ViewGroup
                    parentVg?.addView(mpkgBgGLSurfaceView, 0)
                    // 6. 注入 JS 让 WebView 透明
                    injectTransparentJs()
                    webView.postDelayed({ if (mpkgBgActive) injectTransparentJs() }, 800)
                    // 7. 显示 GLSurfaceView，触发 onSurfaceCreated → onSurfaceChanged
                    mpkgBgGLSurfaceView?.visibility = android.view.View.VISIBLE
                    // ★ 关键修复：必须调用 onResume() 启动渲染线程
                    //   不带 onResume() 时 GLSurfaceView 的 GLThread 保持 mPaused=true，
                    //   onSurfaceCreated/onSurfaceChanged 永远不会触发，场景无法渲染。
                    //   这正解释了"恢复原背景后再选场景壁纸需重启程序"的问题：
                    //   恢复背景走 stopMpkgBackground 后 mpkgBgGlvEverHadSurface 仍为 true，
                    //   再选场景会走本 recreate 分支，而旧代码漏掉 onResume() 导致黑屏。
                    mpkgBgGLSurfaceView?.onResume()
                    Log.d("MpkgBg", "new GLSurfaceView created, set VISIBLE + onResume, pendingPath=$privatePath")
                } else {
                    // ★ 首次启动：GLSurfaceView 已在 onCreate 中创建，但 context 还没初始化
                    //   设置 pendingPath，等 onSurfaceCreated 触发时自动加载
                    injectTransparentJs()
                    webView.postDelayed({ if (mpkgBgActive) injectTransparentJs() }, 800)
                    mpkgBgPendingPath = privatePath
                    mpkgBgGLSurfaceView?.visibility = android.view.View.VISIBLE
                    mpkgBgGLSurfaceView?.onResume()
                    Log.d("MpkgBg", "first start: GLSurfaceView set VISIBLE, pendingPath=$privatePath")
                }
            } catch (e: Throwable) {
                Log.e("MpkgBg", "startMpkgBackground failed", e)
            }
        }
    }

    /**
     * ★ 注入 JS 让 WebView 透明，让底层 GLSurfaceView/SurfaceView 可见
     *   纯场景壁纸在应用中不可见的根因：
     *   1) WebView HTML 页面有 #custom-bg / #app / #root 等背景层不透明
     *   2) 旧版只逐元素设 inline style，无法覆盖 CSS !important 规则
     *   3) JS 动态重建 DOM 后透明效果丢失
     *   修复策略：注入持久 <style> 标签 + body.mpkg-scene-active 类，
     *   用 !important 强制所有背景层透明，并用 MutationObserver 持续维护
     */
    private fun injectTransparentJs() {
        webView.evaluateJavascript(
            """(function(){
                try{
                    // ★ 1. 注入持久 <style>（只注入一次，用 id 去重）
                    if(!document.getElementById('mpkg-scene-transparent-style')){
                        var st=document.createElement('style');
                        st.id='mpkg-scene-transparent-style';
                        st.textContent =
                            'body.mpkg-scene-active, body.mpkg-scene-active html,' +
                            'body.mpkg-scene-active #app, body.mpkg-scene-active #root,' +
                            'body.mpkg-scene-active #app-container, body.mpkg-scene-active .app,' +
                            'body.mpkg-scene-active #main, body.mpkg-scene-active #main-view,' +
                            'body.mpkg-scene-active #content, body.mpkg-scene-active #wrapper,' +
                            'body.mpkg-scene-active #background, body.mpkg-scene-active #bg,' +
                            'body.mpkg-scene-active #bg-layer, body.mpkg-scene-active .background,' +
                            'body.mpkg-scene-active .bg, body.mpkg-scene-active .bg-layer' +
                            '{ background:transparent!important; background-color:transparent!important; background-image:none!important; }' +
                            'body.mpkg-scene-active #custom-bg,' +
                            'body.mpkg-scene-active #custom-bg-video,' +
                            'body.mpkg-scene-active .custom-bg,' +
                            'body.mpkg-scene-active #particle-bg,' +
                            'body.mpkg-scene-active #particle-canvas,' +
                            'body.mpkg-scene-active canvas.bg,' +
                            'body.mpkg-scene-active .particle-bg' +
                            '{ display:none!important; visibility:hidden!important; opacity:0!important; }' +
                            'body.mpkg-scene-active #custom-bg::before,' +
                            'body.mpkg-scene-active #custom-bg::after' +
                            '{ opacity:0!important; background:transparent!important; background-image:none!important; }';
                        document.head.appendChild(st);
                    }
                    // ★ 2. 添加 body 类触发透明 CSS
                    document.body.classList.add('mpkg-scene-active');
                    document.documentElement.classList.add('mpkg-scene-active');
                    document.documentElement.style.background='transparent';
                    document.body.style.background='transparent';
                    // ★ 3. 隐藏 #custom-bg（CSS !important 已处理，这里只做兜底）
                    var cbs=document.querySelectorAll('#custom-bg, #custom-bg-video, #particle-bg, #particle-canvas');
                    for(var i=0;i<cbs.length;i++){
                        cbs[i].style.display='none';
                    }
                    // ★★★ 性能优化：移除 MutationObserver（监控整个 body subtree+attributes 会导致严重卡顿）
                    //   改用轻量 setInterval 每 2 秒检查一次 body 类是否被移除，成本极低
                    if(!window.__mpkgSceneBgTimer){
                        window.__mpkgSceneBgTimer=setInterval(function(){
                            if(!document.body.classList.contains('mpkg-scene-active')){
                                document.body.classList.add('mpkg-scene-active');
                                document.documentElement.classList.add('mpkg-scene-active');
                            }
                        },2000);
                    }
                }catch(e){console.warn('[MpkgBg] set transparent bg failed:',e)}
            })();""",
            null,
        )
    }

    /**
     * ★ 移除 WebView 透明效果，恢复正常背景
     *   在 stopMpkgBackground 时调用
     */
    private fun removeTransparentJs() {
        webView.evaluateJavascript(
            """(function(){
                try{
                    document.body.classList.remove('mpkg-scene-active');
                    document.documentElement.classList.remove('mpkg-scene-active');
                    // 清理 setInterval 定时器
                    if(window.__mpkgSceneBgTimer){
                        clearInterval(window.__mpkgSceneBgTimer);
                        window.__mpkgSceneBgTimer=null;
                    }
                    // 恢复 #custom-bg 等元素显示
                    var nds=document.querySelectorAll('#custom-bg, #custom-bg-video, #splash');
                    for(var i=0;i<nds.length;i++){
                        nds[i].style.display='';
                        nds[i].style.visibility='';
                        nds[i].style.opacity='';
                    }
                }catch(e){console.warn('[MpkgBg] remove transparent bg failed:',e)}
            })();""",
            null,
        )
    }

    /**
     * ★ 视频壁纸播放（TextureView + MediaPlayer）
     *   从 mpkg 中提取视频文件后用 MediaPlayer 播放
     */
    private fun startMpkgVideoPlayback(
        surface: android.graphics.SurfaceTexture,
        path: String,
    ) {
        try {
            stopMpkgVideoPlayback()
            // ★ 从 mpkg 中提取视频文件
            val videoFile =
                com.mineradio.app.wallpaper.MpkgParser
                    .extractVideoFile(path, filesDir)
            Log.d("MpkgBg", "startMpkgVideoPlayback: videoFile=${videoFile?.absolutePath}")

            mpkgBgMediaPlayer =
                android.media.MediaPlayer().apply {
                    setSurface(android.view.Surface(surface))
                    setLooping(true)
                    setVolume(0f, 0f)
                    if (videoFile != null && videoFile.exists()) {
                        // 用 FileDescriptor 方式避免中文文件名问题
                        mpkgBgVideoFileStream = java.io.FileInputStream(videoFile)
                        setDataSource(mpkgBgVideoFileStream!!.fd)
                    } else {
                        setDataSource(path)
                    }
                    setOnPreparedListener { mp ->
                        Log.d("MpkgBg", "MediaPlayer prepared, starting playback")
                        mp.start()
                    }
                    setOnErrorListener { mp, what, extra ->
                        Log.e("MpkgBg", "MediaPlayer error: what=$what extra=$extra")
                        true
                    }
                    prepareAsync()
                }
        } catch (e: Throwable) {
            Log.e("MpkgBg", "startMpkgVideoPlayback failed", e)
        }
    }

    /**
     * ★ 停止视频壁纸播放
     */
    private fun stopMpkgVideoPlayback() {
        try {
            mpkgBgMediaPlayer?.let {
                it.stop()
                it.release()
            }
            mpkgBgMediaPlayer = null
            mpkgBgVideoFileStream?.close()
            mpkgBgVideoFileStream = null
        } catch (e: Throwable) {
            Log.e("MpkgBg", "stopMpkgVideoPlayback failed", e)
        }
    }

    /**
     * ★ 停止 MPKG 应用背景渲染
     *   - 隐藏 GLSurfaceView 和 SurfaceView
     *   - WebView 恢复不透明背景
     *   - 销毁 SceneLib 场景和 MediaPlayer
     */
    fun stopMpkgBackground() {
        runOnUiThread {
            try {
                Log.d("MpkgBg", "stopMpkgBackground")
                mpkgBgActive = false
                mpkgBgPendingPath = null
                mpkgBgIsVideo = false
                // 隐藏所有背景层
                mpkgBgGLSurfaceView?.visibility = android.view.View.GONE
                mpkgBgVideoTextureView?.visibility = android.view.View.GONE
                mpkgBgGLSurfaceView?.onPause()
                // ★ 隐藏齿轮按钮和参数面板（已废弃，HTML 面板替代）
                mpkgBgGearButton?.visibility = android.view.View.GONE
                mpkgBgPropsPanel?.visibility = android.view.View.GONE
                mpkgBgPropsPanelVisible = false
                mpkgBgPropsReady = false
                mpkgBgPropsContainer?.removeAllViews()
                // ★ 恢复 WebView 不透明背景（移除 mpkg-scene-active 类）
                removeTransparentJs()
                // ★ 通知 HTML 面板隐藏场景参数折叠区（场景已停止）
                webView.evaluateJavascript(
                    "try{if(typeof refreshScenePropsPanel==='function')refreshScenePropsPanel();}catch(e){}",
                    null,
                )
                // 停止视频播放
                stopMpkgVideoPlayback()
                // ★ 停止传感器和音频录制
                stopMpkgBgSensor()
                stopMpkgBgAudioRecording()
                // ★ 停止周期性属性更新
                mpkgBgPropsHandler.removeCallbacks(mpkgBgPropsRunnable)
                // 销毁场景（在 GL 线程中执行）
                mpkgBgGLSurfaceView?.queueEvent {
                    try {
                        if (mpkgBgContextId >= 0) {
                            if (mpkgBgSceneInitialized) {
                                try {
                                    mpkgBgSceneLib?.shutdownScene(mpkgBgContextId)
                                } catch (_: Throwable) {
                                }
                                mpkgBgSceneInitialized = false
                            }
                            try {
                                mpkgBgSceneLib?.destroyContext(mpkgBgContextId)
                            } catch (_: Throwable) {
                            }
                            mpkgBgContextId = -1
                        }
                    } catch (e: Throwable) {
                        Log.e("MpkgBg", "stopMpkgBackground cleanup failed", e)
                    }
                }
            } catch (e: Throwable) {
                Log.e("MpkgBg", "stopMpkgBackground failed", e)
            }
        }
    }

    /**
     * ★ Activity 级别触摸事件分发：转发触摸给场景壁纸（视差移动 + 触摸输入）
     *   原因：GLSurfaceView 在 WebView 之下，触摸事件被 WebView 拦截，GLSurfaceView 收不到
     *   方案：在 Activity 分发触摸事件时，先转发给场景，再正常分发给子 View
     *   不消费事件，确保 WebView 和其他 UI 控件仍能正常响应
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        try {
            if (mpkgBgContextId >= 0 && mpkgBgSceneInitialized && mpkgBgActive && !mpkgBgIsVideo) {
                val action = ev.actionMasked
                val x = ev.x
                val y = ev.y
                // ★ 参照原版 SceneWallpaperView.sendTouchInput：
                //   必须通过 queueEvent 在 GL 线程调用！之前在 UI 线程直接调用导致触摸失效
                //   ACTION_DOWN/MOVE → sendTouchInput(true)
                //   ACTION_UP/CANCEL → sendTouchInput(false)
                val glv = mpkgBgGLSurfaceView
                if (glv != null) {
                    when (action) {
                        android.view.MotionEvent.ACTION_DOWN,
                        android.view.MotionEvent.ACTION_MOVE,
                        -> {
                            glv.queueEvent {
                                try {
                                    mpkgBgSceneLib?.sendTouchInput(mpkgBgContextId, true, x, y)
                                } catch (e: Throwable) {
                                    Log.e("MpkgBg", "sendTouchInput(down) failed", e)
                                }
                            }
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL,
                        -> {
                            glv.queueEvent {
                                try {
                                    mpkgBgSceneLib?.sendTouchInput(mpkgBgContextId, false, x, y)
                                } catch (e: Throwable) {
                                    Log.e("MpkgBg", "sendTouchInput(up) failed", e)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("MpkgBg", "dispatchTouchEvent forward failed", e)
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * ★★★ 场景属性持久化保存/加载
     *   保存路径：filesDir/scene_props/<mpkg文件名>.json
     *   每个壁纸独立配置，重启后自动恢复用户调整的参数
     */

    /** 获取当前场景壁纸的配置文件 */
    private fun getScenePropsFile(): java.io.File? {
        val path = mpkgBgCurrentScenePath ?: return null
        val name = java.io.File(path).nameWithoutExtension
        val dir = java.io.File(filesDir, "scene_props")
        if (!dir.exists()) dir.mkdirs()
        return java.io.File(dir, "$name.json")
    }

    /** ★★★ 节流：上次保存调度时间，避免拖动滑块时创建大量写文件线程 */
    private var lastSaveScenePropsDispatchTime = 0L
    private val saveScenePropsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveScenePropsRunnable =
        Runnable {
            doSaveScenePropsToFile()
        }

    /** 保存当前场景属性到文件（节流异步，不阻塞 GL 线程）
     *   ★★★ 节流：拖动滑块时每 500ms 最多保存一次，避免创建大量线程导致卡顿 */
    private fun saveScenePropsToFile() {
        try {
            val now = android.os.SystemClock.uptimeMillis()
            // 节流：500ms 内只调度一次
            if (now - lastSaveScenePropsDispatchTime < 500L) {
                // 已经有调度在等待，跳过
                return
            }
            lastSaveScenePropsDispatchTime = now
            // 延迟 500ms 执行，期间如果有新调用会覆盖（取最新值）
            saveScenePropsHandler.removeCallbacks(saveScenePropsRunnable)
            saveScenePropsHandler.postDelayed(saveScenePropsRunnable, 500L)
        } catch (e: Throwable) {
            Log.e("MpkgBg", "saveScenePropsToFile dispatch failed", e)
        }
    }

    /** 实际执行文件写入（在工作线程） */
    private fun doSaveScenePropsToFile() {
        try {
            val file = getScenePropsFile() ?: return
            val jsonStr: String
            synchronized(mpkgBgCurrentPropsJson) {
                jsonStr = mpkgBgCurrentPropsJson.toString()
            }
            Thread {
                try {
                    file.writeText(jsonStr)
                    Log.d("MpkgBg", "saveScenePropsToFile: saved ${jsonStr.length} bytes to ${file.absolutePath}")
                } catch (e: Throwable) {
                    Log.e("MpkgBg", "saveScenePropsToFile failed", e)
                }
            }.start()
        } catch (e: Throwable) {
            Log.e("MpkgBg", "doSaveScenePropsToFile failed", e)
        }
    }

    /** 从文件加载用户保存的场景属性，合并到 json 中（用于 initScene 后恢复用户配置） */
    private fun loadScenePropsFromFile(json: org.json.JSONObject) {
        try {
            val file = getScenePropsFile() ?: return
            if (!file.exists()) return
            val savedStr = file.readText()
            if (savedStr.isEmpty()) return
            val saved = org.json.JSONObject(savedStr)
            val keys = saved.keys()
            var merged = 0
            while (keys.hasNext()) {
                val key = keys.next()
                // ★ 跳过 alignment，保留强制设置的 alignment=0（拉伸铺满）
                //   否则用户之前保存的 alignment 值会覆盖强制设置，导致上下显示不全
                if (key == "alignment") continue
                // ★★★ 跳过 mobileparallax 和 mobileparallaxstrength
                //   parallax 机制会导致画面放大，禁用后画面不放大
                //   否则用户之前保存的 mobileparallax=1 会覆盖禁用设置
                if (key == "mobileparallax" || key == "mobileparallaxstrength") continue
                try {
                    val savedProp = saved.getJSONObject(key)
                    if (json.has(key)) {
                        val targetProp = json.getJSONObject(key)
                        if (savedProp.has("value")) {
                            targetProp.put("value", savedProp.get("value"))
                            merged++
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            Log.d("MpkgBg", "loadScenePropsFromFile: merged $merged user-saved properties from ${file.absolutePath}")
        } catch (e: Throwable) {
            Log.e("MpkgBg", "loadScenePropsFromFile failed", e)
        }
    }

    /**
     * ★ 应用默认场景属性（参照原版壁纸引擎 SceneWallpaperView.Renderer.loadScene 流程）
     *   原版流程：
     *   1. getSceneFeatureFlags 判断场景支持的特性（音频/对齐/视差/物理）
     *   2. setDefaultProperties(PropertyList.defaultSceneProperties) 设置基础默认属性
     *   3. 根据 feature flags 扩展属性（audioprocessing 等）
     *   4. getSceneProperties → addPropertyOrUpdateValue（场景自身属性覆盖默认值）
     *   5. readWallpaperProperties → updatePropertyValues（用户保存的属性）
     *   6. applySceneProperties 应用到场景
     *
     *   ★ 缺少 applySceneProperties 会导致场景不响应音频、时钟不自动更新
     *   ★ 时钟不显示时间、音频不跳动 的根因就是缺少这一步
     *
     *   简化实现：只设置场景支持的属性，避免强制设置场景不支持的属性导致渲染失败
     */

    /**
     * ★★★ 只设置 parallax 属性（启用陀螺仪视角移动）
     *   不调用 applyDefaultSceneProperties（会导致画面放大）
     *   只单独设置 mobileparallax=1 和 mobileparallaxstrength=100
     *   保持场景默认属性不变，避免画面放大
     */
    private fun applyParallaxOnly() {
        try {
            if (mpkgBgContextId < 0 || !mpkgBgSceneInitialized) return
            val sceneLib = mpkgBgSceneLib ?: return

            // ★ 获取场景特性标志，检查是否支持 parallax 和 audio
            val featureFlags =
                try {
                    sceneLib.getSceneFeatureFlags(mpkgBgContextId)
                } catch (_: Throwable) {
                    0
                }
            val supportsAudio = (featureFlags and 1) != 0
            val supportsParallax = (featureFlags and 4) != 0
            mpkgBgParallaxSupported = supportsParallax
            Log.d("MpkgBg", "applyParallaxOnly: featureFlags=$featureFlags, audio=$supportsAudio, parallax=$supportsParallax")

            // ★★★ 关键修复：设置 audioprocessing=true（音频可视化的开关）
            //   缺少此设置，场景会忽略 sendAudioData 发送的所有音频数据
            //   原代码因"不调用 applyDefaultSceneProperties（会导致画面放大）"而跳过了这一步
            //   现在单独设置 audioprocessing，不影响 alignment（避免画面放大）
            val json = org.json.JSONObject()
            if (supportsAudio) {
                json.put(
                    "audioprocessing",
                    org.json.JSONObject().apply {
                        put("type", "bool")
                        put("value", true)
                        put("order", 2)
                    },
                )
                Log.d("MpkgBg", "applyParallaxOnly: set audioprocessing=true")
            }

            // ★ 只设置 parallax 属性（如果场景支持），不设置其他属性（避免画面放大）
            if (supportsParallax) {
                json.put(
                    "mobileparallax",
                    org.json.JSONObject().apply {
                        put("type", "combo")
                        put("value", 1)
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
            }

            // ★ 只有有属性需要设置时才调用 applySceneProperties
            if (json.length() > 0) {
                sceneLib.applySceneProperties(mpkgBgContextId, json.toString())
                Log.d("MpkgBg", "applyParallaxOnly done: audio=$supportsAudio, parallax=$supportsParallax")
            } else {
                Log.d("MpkgBg", "applyParallaxOnly: no properties to set, skip")
            }
        } catch (e: Throwable) {
            Log.e("MpkgBg", "applyParallaxOnly failed", e)
        }
    }

    /**
     * ★★★ 应用用户保存的场景属性（恢复用户上次调整的参数效果）
     *   修复：重开壁纸后按钮状态保存了但实际效果没保存
     *
     *   流程：
     *   1. getSceneProperties 获取场景默认属性
     *   2. loadScenePropsFromFile 合并用户保存的属性值（自动跳过 alignment 和 mobileparallax/strength）
     *   3. applySceneProperties 应用到场景
     *
     *   ★ 不设置 alignment（避免画面放大）
     *   ★ 不设置 mobileparallax/strength（由 applyParallaxOnly 控制）
     *   ★ 同步更新 mpkgBgCurrentPropsJson 缓存
     */
    private fun applyUserSavedScenePropsOnly() {
        try {
            if (mpkgBgContextId < 0 || !mpkgBgSceneInitialized) return
            val sceneLib = mpkgBgSceneLib ?: return

            // ★ 步骤1：获取场景默认属性
            val propsJson = sceneLib.getSceneProperties(mpkgBgContextId) ?: return
            if (propsJson.isEmpty()) return
            val json = org.json.JSONObject(propsJson)

            // ★ 步骤2：合并用户保存的属性值（loadScenePropsFromFile 自动跳过 alignment 和 mobileparallax/strength）
            loadScenePropsFromFile(json)

            // ★ 步骤3：同步更新缓存
            synchronized(mpkgBgCurrentPropsJson) {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    mpkgBgCurrentPropsJson.put(key, json.getJSONObject(key))
                }
            }

            // ★ 步骤4：移除 alignment、mobileparallax、mobileparallaxstrength（避免覆盖 applyParallaxOnly 的设置）
            json.remove("alignment")
            json.remove("mobileparallax")
            json.remove("mobileparallaxstrength")

            // ★★★ 关键修复：强制确保 audioprocessing=true
            //   用户保存的属性可能包含 audioprocessing=false（默认值），会覆盖 applyParallaxOnly 的设置
            //   导致音频可视化失效，必须强制为 true
            val featureFlags2 =
                try {
                    sceneLib.getSceneFeatureFlags(mpkgBgContextId)
                } catch (_: Throwable) {
                    0
                }
            if ((featureFlags2 and 1) != 0) {
                try {
                    val audioObj = json.optJSONObject("audioprocessing")
                    if (audioObj != null) {
                        audioObj.put("value", true)
                    } else {
                        json.put(
                            "audioprocessing",
                            org.json.JSONObject().apply {
                                put("type", "bool")
                                put("value", true)
                                put("order", 2)
                            },
                        )
                    }
                } catch (_: Throwable) {
                }
            }

            // ★ 步骤5：应用到场景
            sceneLib.applySceneProperties(mpkgBgContextId, json.toString())
            Log.d("MpkgBg", "applyUserSavedScenePropsOnly done")
        } catch (e: Throwable) {
            Log.e("MpkgBg", "applyUserSavedScenePropsOnly failed", e)
        }
    }

    private fun applyDefaultSceneProperties() {
        try {
            if (mpkgBgContextId < 0 || !mpkgBgSceneInitialized) return
            val sceneLib = mpkgBgSceneLib ?: return

            // ★ 步骤1：获取场景特性标志
            //   bit 0 (1): supportsAudioProcessing
            //   bit 1 (2): supportsAlignment
            //   bit 2 (4): parallax (z)
            //   bit 3 (8): environmentPhysics
            val featureFlags =
                try {
                    sceneLib.getSceneFeatureFlags(mpkgBgContextId)
                } catch (_: Throwable) {
                    0
                }
            val supportsAudio = (featureFlags and 1) != 0
            val supportsAlignment = (featureFlags and 2) != 0
            // ★ bit 2 (4): 场景是否原生支持 parallax（视差/视角移动）
            //   只对支持 parallax 的场景启用陀螺仪，避免不支持的场景出现显示异常
            val supportsParallax = (featureFlags and 4) != 0
            // ★ 保存到字段，供 startMpkgBgSensor 调用前检查
            mpkgBgParallaxSupported = supportsParallax
            Log.d(
                "MpkgBg",
                "applyDefaultSceneProperties: featureFlags=$featureFlags, audio=$supportsAudio, align=$supportsAlignment, parallax=$supportsParallax",
            )

            // ★ 获取壁纸原生分辨率，计算宽高比
            //   用于 onSurfaceChanged 中计算渲染高度：按宽度填满屏幕后，上下拉伸填满
            //   左右保持原生比例（不变形），上下强制填满屏幕
            try {
                val path = mpkgBgCurrentScenePath
                if (path != null) {
                    val resolution = sceneLib.getWallpaperResolution(path)
                    if (resolution != null && resolution.x > 0 && resolution.y > 0) {
                        mpkgBgWallpaperRatio = resolution.x.toFloat() / resolution.y.toFloat()
                        mpkgBgViewportStretch = true
                        Log.d(
                            "MpkgBg",
                            "wallpaper resolution: ${resolution.x}x${resolution.y}, ratio=$mpkgBgWallpaperRatio, viewportStretch=true",
                        )
                    }
                }
            } catch (e: Throwable) {
                Log.w("MpkgBg", "getWallpaperResolution failed", e)
            }
            // ★ fallback: getWallpaperResolution 失败时，用 getSceneCanvasSize 获取场景画布尺寸
            if (mpkgBgWallpaperRatio <= 0f && mpkgBgContextId >= 0) {
                try {
                    val canvasSize = android.graphics.PointF()
                    val ok = sceneLib.getSceneCanvasSize(mpkgBgContextId, canvasSize)
                    if (ok && canvasSize.x > 0 && canvasSize.y > 0) {
                        mpkgBgWallpaperRatio = canvasSize.x / canvasSize.y
                        mpkgBgViewportStretch = true
                        Log.d(
                            "MpkgBg",
                            "scene canvas size: ${canvasSize.x}x${canvasSize.y}, ratio=$mpkgBgWallpaperRatio, viewportStretch=true",
                        )
                    }
                } catch (e: Throwable) {
                    Log.w("MpkgBg", "getSceneCanvasSize failed", e)
                }
            }
            // ★ fallback 2: 仍无法获取比例时，使用默认 16:9 比例（场景壁纸常见比例）
            if (mpkgBgWallpaperRatio <= 0f) {
                mpkgBgWallpaperRatio = 16f / 9f
                mpkgBgViewportStretch = true
                Log.d("MpkgBg", "use default ratio 16:9, viewportStretch=true")
            }

            // ★ 步骤2：获取场景属性（包含场景自定义属性）
            val props = sceneLib.getSceneProperties(mpkgBgContextId) ?: return
            Log.d("MpkgBg", "getSceneProperties: " + props)
            if (props.isEmpty()) return

            val json = org.json.JSONObject(props)

            // ★ 步骤3：合并默认属性（参照 PropertyList.defaultSceneProperties）
            //   只补齐缺失的属性，不覆盖场景已有的值（与原版 addPropertyOrUpdateValue 一致）
            fun ensureProp(
                key: String,
                value: Any,
                type: String,
                extra: org.json.JSONObject.() -> Unit = {},
            ) {
                if (!json.has(key)) {
                    json.put(
                        key,
                        org.json.JSONObject().apply {
                            put("type", type)
                            put("value", value)
                            extra()
                        },
                    )
                }
            }

            // 基础默认属性（原版 defaultSceneProperties，condition=false 的属性 native 层会忽略）
            // ★★★ 恢复默认值，不强制设置 alignmentposition（与 LWService 一致）
            ensureProp("alignmentposition", 50.0, "slider") {
                put("min", 0.0)
                put("max", 100.0)
                put("condition", "false")
                put("order", -100)
            }
            ensureProp("alignmentpositionlandscape", 50.0, "slider") {
                put("min", 0.0)
                put("max", 100.0)
                put("condition", "false")
                put("order", -100)
            }
            ensureProp("alignmentorientation", "portrait", "combo") {
                put("condition", "false")
                put("order", -100)
            }
            ensureProp("alignmentfliph", false, "bool") { put("order", 1) }
            // ★★★ 禁用 parallax 测试是否是 parallax 导致放大
            //   用户反馈系统预设正常，设置为壁纸后画面放大
            //   parallax 机制本身需要留白导致画面放大
            //   先禁用 parallax 测试，确认是否是 parallax 导致的放大
            json.put(
                "mobileparallax",
                org.json.JSONObject().apply {
                    put("type", "combo")
                    put("value", 0)
                    put("order", 4)
                },
            )
            json.put(
                "mobileparallaxstrength",
                org.json.JSONObject().apply {
                    put("type", "slider")
                    put("value", 1.0)
                    put("min", 1.0)
                    put("max", 200.0)
                    put("order", 5)
                    put("condition", "mobileparallax.value")
                },
            )
            ensureProp("rate", 100.0, "slider") {
                put("min", 1.0)
                put("max", 200.0)
                put("order", 6)
            }

            // ★ 步骤4：如果场景支持音频处理，确保 audioprocessing=true
            //   这是音频可视化的关键开关！缺少它场景会忽略 sendAudioData 发送的数据
            if (supportsAudio) {
                if (!json.has("audioprocessing")) {
                    json.put(
                        "audioprocessing",
                        org.json.JSONObject().apply {
                            put("type", "bool")
                            put("value", true)
                            put("order", 2)
                        },
                    )
                } else {
                    // 已有属性，强制启用音频处理
                    try {
                        json.getJSONObject("audioprocessing").put("value", true)
                    } catch (_: Throwable) {
                        json.put(
                            "audioprocessing",
                            org.json.JSONObject().apply {
                                put("type", "bool")
                                put("value", true)
                                put("order", 2)
                            },
                        )
                    }
                }
                Log.d("MpkgBg", "audioprocessing=true (scene supports audio)")
            } else {
                Log.d("MpkgBg", "scene does NOT support audio processing, skipping audioprocessing")
            }

            // ★ 步骤5：不设置 alignment 属性
            //   ★★★ 关键修复：MpkgPreviewActivity（系统预设）不设置 alignment，使用场景默认值，显示正常
            //   之前强制设置 alignment=0（拉伸填满）导致画面放大
            //   跳过 alignment，让场景使用自己的默认 alignment 值
            //   不调用 ensureProp("alignment", ...)

            // ★ 步骤6：合并用户保存的属性（持久化恢复用户配置）
            //   在 applySceneProperties 之前，从文件读取用户之前调整的参数值
            loadScenePropsFromFile(json)

            // ★ 步骤7：应用到场景
            sceneLib.applySceneProperties(mpkgBgContextId, json.toString())
            Log.d("MpkgBg", "applyDefaultSceneProperties done, audio=$supportsAudio")
        } catch (e: Throwable) {
            Log.e("MpkgBg", "applyDefaultSceneProperties failed", e)
        }
    }

    /**
     * ★ 创建场景参数面板
     *   从 GL 线程获取场景属性 JSON，然后在主线程构建 UI 控件
     *   支持 slider(combo)、combo(下拉)、bool(开关)、color(颜色)、textinput(文本)
     *   跳过 condition="false" 的属性（内部隐藏属性）
     */
    private fun createMpkgPropsPanel() {
        mpkgBgGLSurfaceView?.queueEvent {
            try {
                if (mpkgBgContextId < 0 || !mpkgBgSceneInitialized) {
                    Log.w("MpkgBg", "createMpkgPropsPanel: scene not ready")
                    return@queueEvent
                }
                val propsJson = mpkgBgSceneLib?.getSceneProperties(mpkgBgContextId)
                if (propsJson.isNullOrEmpty()) {
                    Log.w("MpkgBg", "createMpkgPropsPanel: empty properties")
                    return@queueEvent
                }
                Log.d("MpkgBg", "createMpkgPropsPanel: got properties, length=${propsJson.length}")

                // ★★★ 合并用户保存的属性值，让 UI 按钮状态恢复用户上次设置
                //   修复：重开参数面板时按钮状态丢失（效果保留但 UI 重置）
                //   原因：getSceneProperties 返回的是场景默认值，需要从持久化文件覆盖
                try {
                    val mergedJson = org.json.JSONObject(propsJson)
                    loadScenePropsFromFile(mergedJson)
                    runOnUiThread { buildMpkgPropsPanelUI(mergedJson.toString()) }
                } catch (_: Throwable) {
                    // 合并失败时回退到原始属性
                    runOnUiThread { buildMpkgPropsPanelUI(propsJson) }
                }
            } catch (e: Throwable) {
                Log.e("MpkgBg", "createMpkgPropsPanel failed", e)
            }
        }
    }

    /**
     * ★ 在主线程构建参数面板 UI
     *   解析属性 JSON，按 order 排序，为每个属性创建对应控件
     */
    private fun buildMpkgPropsPanelUI(propsJson: String) {
        try {
            val json = org.json.JSONObject(propsJson)
            // ★ 同步更新缓存的属性 JSON（用于实时更新时构造完整 JSON）
            synchronized(mpkgBgCurrentPropsJson) {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    mpkgBgCurrentPropsJson.put(key, json.getJSONObject(key))
                }
            }

            val container = mpkgBgPropsContainer ?: return
            container.removeAllViews()

            // 标题
            val title =
                android.widget.TextView(this).apply {
                    text = "场景参数"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 16f
                    setPadding(0, 0, 0, 16)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            container.addView(title)

            // 按 order 排序属性
            val sortedKeys = mutableListOf<String>()
            val keysIter = json.keys()
            while (keysIter.hasNext()) sortedKeys.add(keysIter.next())
            sortedKeys.sortBy { key ->
                try {
                    val prop = json.getJSONObject(key)
                    if (prop.has("order")) prop.getInt("order") else 0
                } catch (_: Throwable) {
                    0
                }
            }

            var builtCount = 0
            for (key in sortedKeys) {
                try {
                    val prop = json.getJSONObject(key)
                    val type = prop.optString("type", "")
                    val condition = prop.optString("condition", "")

                    // ★ 跳过 condition="false" 的属性（内部隐藏属性，不应展示给用户）
                    if (condition == "false") continue
                    // ★ 跳过 alignment，该属性已强制设为 0（拉伸铺满），
                    //   允许用户修改会导致上下显示不全问题再次出现
                    if (key == "alignment") continue

                    // 创建属性行（标签 + 控件）
                    val row = createPropertyRow(key, prop, type)
                    if (row != null) {
                        container.addView(row)
                        builtCount++
                    }
                } catch (_: Throwable) {
                }
            }

            mpkgBgPropsReady = true
            Log.d("MpkgBg", "buildMpkgPropsPanelUI: built $builtCount properties")
        } catch (e: Throwable) {
            Log.e("MpkgBg", "buildMpkgPropsPanelUI failed", e)
        }
    }

    /**
     * ★ 为单个属性创建 UI 行（标签 + 控件）
     *   返回 LinearLayout 包含标签和对应控件
     */
    private fun createPropertyRow(
        key: String,
        prop: org.json.JSONObject,
        type: String,
    ): android.widget.LinearLayout? {
        val row =
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
            }

        // 标签
        val labelText = if (prop.has("text")) prop.getString("text") else key
        val label =
            android.widget.TextView(this).apply {
                text = labelText
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 12f
                setPadding(0, 0, 0, 4)
            }
        row.addView(label)

        when (type) {
            "slider" -> {
                val minVal = prop.optDouble("min", 0.0)
                val maxVal = prop.optDouble("max", 100.0)
                val current = prop.optDouble("value", minVal)
                val valueLabel =
                    android.widget.TextView(this).apply {
                        text = String.format("%.1f", current)
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 11f
                    }
                val seekBar =
                    android.widget.SeekBar(this).apply {
                        max = ((maxVal - minVal) * 10).toInt()
                        progress = ((current - minVal) * 10).toInt()
                        setOnSeekBarChangeListener(
                            object : android.widget.SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(
                                    seekBar: android.widget.SeekBar?,
                                    progress: Int,
                                    fromUser: Boolean,
                                ) {
                                    if (fromUser) {
                                        val newVal = minVal + progress / 10.0
                                        valueLabel.text = String.format("%.1f", newVal)
                                        updateMpkgBgSceneProperty(key, newVal)
                                    }
                                }

                                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

                                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                            },
                        )
                    }
                row.addView(seekBar)
                row.addView(valueLabel)
            }
            "combo" -> {
                val options = prop.optJSONArray("options")
                val optionLabels = mutableListOf<String>()
                val optionValues = mutableListOf<Any>()
                if (options != null) {
                    for (i in 0 until options.length()) {
                        val opt = options.getJSONObject(i)
                        optionLabels.add(opt.optString("label", opt.opt("value").toString()))
                        optionValues.add(opt.opt("value"))
                    }
                }
                val currentValue = prop.opt("value")
                val spinner =
                    android.widget.Spinner(this).apply {
                        adapter =
                            android.widget.ArrayAdapter(
                                this@LandscapeWebActivity,
                                android.R.layout.simple_spinner_dropdown_item,
                                optionLabels,
                            )
                        // 选中当前值
                        var selIdx = 0
                        for (i in optionValues.indices) {
                            if (optionValues[i].toString() == currentValue.toString()) {
                                selIdx = i
                                break
                            }
                        }
                        setSelection(selIdx)
                        onItemSelectedListener =
                            object : android.widget.AdapterView.OnItemSelectedListener {
                                override fun onItemSelected(
                                    parent: android.widget.AdapterView<*>?,
                                    view: android.view.View?,
                                    position: Int,
                                    id: Long,
                                ) {
                                    updateMpkgBgSceneProperty(key, optionValues[position])
                                }

                                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                            }
                    }
                row.addView(spinner)
            }
            "bool" -> {
                val currentValue = prop.optBoolean("value", false)
                val switch =
                    android.widget.Switch(this).apply {
                        isChecked = currentValue
                        setOnCheckedChangeListener { _, isChecked ->
                            updateMpkgBgSceneProperty(key, isChecked)
                        }
                    }
                row.addView(switch)
            }
            "color" -> {
                val currentValue = prop.optString("value", "0 0 0")
                val colorBtn =
                    android.widget.Button(this).apply {
                        text = currentValue
                        try {
                            val parts = currentValue.split(" ")
                            if (parts.size >= 3) {
                                val r = (parts[0].toFloat() * 255).toInt().coerceIn(0, 255)
                                val g = (parts[1].toFloat() * 255).toInt().coerceIn(0, 255)
                                val b = (parts[2].toFloat() * 255).toInt().coerceIn(0, 255)
                                setBackgroundColor(android.graphics.Color.rgb(r, g, b))
                            }
                        } catch (_: Throwable) {
                        }
                        setOnClickListener {
                            // 简化处理：循环切换预设颜色
                            val presets = arrayOf("0 0 0", "1 0 0", "0 1 0", "0 0 1", "1 1 1", "0.5 0.5 0.5")
                            val idx = (presets.indexOf(currentValue) + 1) % presets.size
                            val newColor = presets[idx]
                            text = newColor
                            val parts = newColor.split(" ")
                            if (parts.size >= 3) {
                                val r = (parts[0].toFloat() * 255).toInt().coerceIn(0, 255)
                                val g = (parts[1].toFloat() * 255).toInt().coerceIn(0, 255)
                                val b = (parts[2].toFloat() * 255).toInt().coerceIn(0, 255)
                                setBackgroundColor(android.graphics.Color.rgb(r, g, b))
                            }
                            updateMpkgBgSceneProperty(key, newColor)
                        }
                    }
                row.addView(colorBtn)
            }
            "textinput" -> {
                val currentValue = prop.optString("value", "")
                val editText =
                    android.widget.EditText(this).apply {
                        setText(currentValue)
                        setOnFocusChangeListener { _, hasFocus ->
                            if (!hasFocus) {
                                updateMpkgBgSceneProperty(key, text.toString())
                            }
                        }
                    }
                row.addView(editText)
            }
            else -> {
                // 未知类型：显示为只读文本
                val valueText =
                    android.widget.TextView(this).apply {
                        text = prop.opt("value").toString()
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 12f
                    }
                row.addView(valueText)
            }
        }
        return row
    }

    /**
     * ★ 实时更新场景属性
     *   用户调整控件时调用，在 GL 线程应用更新后的属性 JSON
     *   注意：只在用户主动调整时调用，不周期性调用（避免场景定格）
     */
    private fun updateMpkgBgSceneProperty(
        key: String,
        value: Any,
    ) {
        mpkgBgGLSurfaceView?.queueEvent {
            try {
                if (mpkgBgContextId < 0 || !mpkgBgSceneInitialized) return@queueEvent
                synchronized(mpkgBgCurrentPropsJson) {
                    if (mpkgBgCurrentPropsJson.has(key)) {
                        val prop = mpkgBgCurrentPropsJson.getJSONObject(key)
                        when (value) {
                            is Number -> {
                                // 保持与原值类型一致（int 或 double）
                                val original = prop.opt("value")
                                if (original is Int || original is Long) {
                                    prop.put("value", value.toInt())
                                } else {
                                    prop.put("value", value.toDouble())
                                }
                            }
                            is Boolean -> prop.put("value", value)
                            is String -> prop.put("value", value)
                        }
                    }
                }
                mpkgBgSceneLib?.applySceneProperties(mpkgBgContextId, mpkgBgCurrentPropsJson.toString())
                // ★★★ 持久化保存到文件（异步，不阻塞 GL 线程）
                //   修复：UI 控件修改后未保存，导致重开面板时按钮状态丢失
                saveScenePropsToFile()
                Log.d("MpkgBg", "updateMpkgBgSceneProperty: $key=$value")
            } catch (e: Throwable) {
                Log.e("MpkgBg", "updateMpkgBgSceneProperty failed: $key=$value", e)
            }
        }
    }

/**
     * ★ 启动音频录制，发送给场景壁纸用于音频可视化
     *   ★ 主数据源：Visualizer(0) 捕获系统混音（与原版 Wallpaper Engine 完全一致）
     *     - 原版 AudioRecorder.java 用 Visualizer(0).onFftDataCapture 回调
     *     - 算法：log10(re²+im²) * 0.35 * decay，结果限制到 [0,1]
     *   ★ 备选数据源：fftProcessor.bands（应用内 ExoPlayer PCM 拦截）
     *     - 当 Visualizer 不可用或返回全 0 时（MIUI 等系统限制），用 fftProcessor.bands
     *     - 31 段归一化频谱（0~1），线性插值映射到 64 段
     *   ★ 关键：场景必须支持音频处理（featureFlags & 1），且 audioprocessing=true
     *     缺少 applySceneProperties 设置 audioprocessing=true，场景会忽略所有音频数据
     */

    /**
     * ★ 启动陀螺仪传感器，驱动场景视角移动（参照原版 SceneWallpaperView）
     *   原版用加速度传感器 + sensorMatrix 转换坐标，通过 sendNormalizedParallaxOffset 发送
     *   mobileparallax=1（陀螺仪模式）时场景才响应传感器数据
     */

    /**
     * 根据屏幕旋转更新 sensorMatrix，确保横竖屏 X/Y 轴正确对应
     */
    private fun updateMpkgBgSensorMatrix() {
        try {
            val display = (getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
            when (display.rotation) {
                0 -> mpkgBgSensorMatrix = floatArrayOf(1.0f, 1.0f, -1.0f, 0.0f, 0.0f, 0.0f)
                1 -> mpkgBgSensorMatrix = floatArrayOf(0.0f, 0.0f, -1.0f, 1.0f, -1.0f, 0.0f)
                2 -> mpkgBgSensorMatrix = floatArrayOf(-1.0f, -1.0f, -1.0f, 0.0f, 0.0f, 0.0f)
                3 -> mpkgBgSensorMatrix = floatArrayOf(0.0f, 0.0f, -1.0f, -1.0f, 1.0f, 0.0f)
            }
            Log.d("MpkgBg", "sensorMatrix updated for rotation=${display.rotation}")
        } catch (e: Throwable) {
            Log.e("MpkgBg", "updateMpkgBgSensorMatrix failed", e)
        }
    }

    private fun startMpkgBgSensor() {
        if (mpkgBgSensorListener != null) return
        try {
            val sm = getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
            // ★ 参照原版 ParallaxController.java：必须使用 TYPE_GYROSCOPE（type 4）
            //   之前误用 TYPE_ACCELEROMETER 导致：
            //   1. 顺滑度差（加速度计噪声大，需要重滤波）
            //   2. 漂移问题（手持静止时加速度计仍有 9.8g 重力分量，会持续触发视差）
            //   3. 范围过小（加速度计数值范围 ±9.8，需要除以大数）
            //   陀螺仪返回角速度（弧度/秒），手持静止时数值接近 0，天然无漂移
            val gyro = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
            if (gyro == null) {
                Log.w("MpkgBg", "Gyroscope not available")
                return
            }
            // ★ 根据屏幕旋转更新 sensorMatrix（参照原版 SceneWallpaperView）
            updateMpkgBgSensorMatrix()
            // ★ mobileparallaxstrength 滑块值（1~200），原版映射：
            //   parallaxStrength = sliderValue * 0.01f  → 范围 0.01~2.0
            //   我们设置 sliderValue=100（默认值），即 parallaxStrength=1.0
            val parallaxStrength = 1.0f
            // ★ 参照原版 ParallaxController.onSensorChanged（line 198-202）：
            //   factor = (parallaxStrength * 0.125f) + 0.1f
            //   xOffset = event.values[1] * factor
            //   yOffset = -event.values[0] * factor
            //   maxDistance = 0.5f（固定值，由 native 层负责限制移动范围）
            //   speedFactor = (parallaxStrength * 0.05f) + 0.01f
            val factor = (parallaxStrength * 0.125f) + 0.1f
            val maxDistance = 0.5f
            val speedFactor = (parallaxStrength * 0.05f) + 0.01f
            val listener =
                object : android.hardware.SensorEventListener {
                    override fun onSensorChanged(event: android.hardware.SensorEvent) {
                        if (!mpkgBgActive || mpkgBgContextId < 0 || !mpkgBgSceneInitialized) return
                        if (event.values.size < 2) return
                        try {
                            // ★ 原版公式：x = values[1]*factor, y = -values[0]*factor
                            //   values[0] = 绕X轴角速度（设备前后倾斜，对应Y方向视差）
                            //   values[1] = 绕Y轴角速度（设备左右倾斜，对应X方向视差）
                            //   ★ 上下移动范围太大，Y 轴额外乘 0.5 缩小一半
                            val rawX = event.values[1] * factor
                            val rawY = -event.values[0] * factor * 0.5f
                            // ★ 用 sensorMatrix 转换坐标（参照原版 SceneWallpaperView line 673）
                            //   横竖屏自动切换 X/Y 轴
                            val ox = (rawX * mpkgBgSensorMatrix[0]) + (rawY * mpkgBgSensorMatrix[4])
                            val oy = (rawY * mpkgBgSensorMatrix[1]) + (rawX * mpkgBgSensorMatrix[3])
                            // ★ 在 GL 线程发送视差偏移（参数顺序：context, enabled, x, y, maxDistance, speedFactor）
                            //   之前误把 x,y 当 maxDistance,speedFactor 传入，导致画面放大、漂移
                            val glv = mpkgBgGLSurfaceView ?: return
                            glv.queueEvent {
                                try {
                                    mpkgBgSceneLib?.sendNormalizedParallaxOffset(
                                        mpkgBgContextId,
                                        true,
                                        ox,
                                        oy,
                                        maxDistance,
                                        speedFactor,
                                    )
                                } catch (e: Throwable) {
                                    Log.e("MpkgBg", "sendNormalizedParallaxOffset(sensor) failed", e)
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e("MpkgBg", "sensor onSensorChanged failed", e)
                        }
                    }

                    override fun onAccuracyChanged(
                        sensor: android.hardware.Sensor?,
                        accuracy: Int,
                    ) {}
                }
            // ★ 性能优化：samplingPeriodUs = 33333 (30Hz)
            //   之前用 SENSOR_DELAY_UI(20000μs=50Hz) 频率仍过高，导致 GL 线程队列积压、手机卡顿
            //   降到 30Hz 减少队列积压，同时保持视角移动顺滑度
            sm.registerListener(listener, gyro, 33333)
            mpkgBgSensorManager = sm
            mpkgBgAccelerometer = gyro
            mpkgBgSensorListener = listener
            Log.d(
                "MpkgBg",
                "Gyroscope sensor started for parallax (strength=$parallaxStrength, factor=$factor, maxDist=$maxDistance, speed=$speedFactor)",
            )
        } catch (e: Throwable) {
            Log.e("MpkgBg", "startMpkgBgSensor failed", e)
        }
    }

    private fun stopMpkgBgSensor() {
        try {
            val sm = mpkgBgSensorManager
            val listener = mpkgBgSensorListener
            if (sm != null && listener != null) {
                sm.unregisterListener(listener)
            }
        } catch (_: Throwable) {
        }
        mpkgBgSensorListener = null
        mpkgBgAccelerometer = null
        mpkgBgSensorManager = null
        Log.d("MpkgBg", "Gyroscope sensor stopped")
    }

    private fun startMpkgBgAudioRecording() {
        if (mpkgBgAudioEnabled) return
        mpkgBgAudioEnabled = true

        // ★ 备选数据源：fftProcessor.bands（应用内播放器 PCM 拦截）
        //   启用 fftProcessor，当 Visualizer 不可用时作为备选
        //   ★ 不阻塞：无论 RECORD_AUDIO 权限是否授予，都先启用备选方案，保证应用内播放有音频数据
        try {
            val player = GlobalContext.get().get<com.mineradio.app.player.api.IMusicPlayer>()
            if (!player.fftProcessor.isEnabled.value) {
                player.fftProcessor.enable()
                Log.d("MpkgBg", "FFT processor enabled as backup for scene audio")
            }
        } catch (e: Exception) {
            Log.w("MpkgBg", "Failed to access fftProcessor: ${e.message}")
        }

        // ★ 检查 RECORD_AUDIO 权限：Visualizer(0) 需要此权限捕获系统混音
        //   未授权时不阻塞备选方案，仅请求权限，授权后通过 onRequestPermissionsResult 重试 Visualizer
        val audioGranted =
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!audioGranted) {
            Log.w("MpkgBg", "RECORD_AUDIO not granted, requesting (fftProcessor backup still active)")
            runOnUiThread {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.RECORD_AUDIO),
                    RECORD_AUDIO_PERMISSION_REQUEST,
                )
            }
        }

        // ★ 主数据源：Visualizer(0) 捕获系统混音（与原版 Wallpaper Engine 一致）
        //   仅在权限授予时尝试创建，失败则由 fftProcessor Runnable 兜底
        var visualizerStarted = false
        if (audioGranted) {
            try {
                val visualizer = android.media.audiofx.Visualizer(0)
                visualizer.setEnabled(false)
                visualizer.setCaptureSize(512)
                try {
                    visualizer.setScalingMode(0)
                } catch (_: Throwable) {
                }
                try {
                    visualizer.setMeasurementMode(0)
                } catch (_: Throwable) {
                }
                mpkgBgAudioFftBuffer = ByteArray(visualizer.captureSize)
                visualizer.setDataCaptureListener(
                    object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            v: android.media.audiofx.Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int,
                        ) { /* 不用 waveform */ }

                        override fun onFftDataCapture(
                            v: android.media.audiofx.Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int,
                        ) {
                            if (fft == null || !mpkgBgAudioEnabled) return
                            try {
                                val result = mpkgBgAudioResult64
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
                                mpkgBgGLSurfaceView?.queueEvent {
                                    try {
                                        if (mpkgBgContextId >= 0 && mpkgBgSceneInitialized) {
                                            mpkgBgSceneLib?.sendAudioData(mpkgBgContextId, data)
                                        }
                                    } catch (e: Throwable) {
                                        Log.e("MpkgBg", "sendAudioData (Visualizer) failed", e)
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
                mpkgBgVisualizer = visualizer
                visualizer.setEnabled(true)
                visualizerStarted = true
                Log.d("MpkgBg", "Visualizer(0) started as PRIMARY audio source (same as original WE)")
            } catch (e: Throwable) {
                Log.w("MpkgBg", "Visualizer(0) failed, will use fftProcessor as fallback: ${e.message}")
            }
        } else {
            Log.w("MpkgBg", "RECORD_AUDIO not granted, using fftProcessor as audio source")
        }

        // ★ 备选数据源 Runnable：当 Visualizer 不可用时，用 fftProcessor.bands
        //   每 33ms 采样一次，31 段 → 64 段线性插值
        mpkgBgAudioRunnable =
            object : Runnable {
                override fun run() {
                    if (!mpkgBgAudioEnabled) return
                    // ★ 如果 Visualizer 已启动，不使用 fftProcessor（避免双重发送）
                    //   性能优化：从 100ms 改为 500ms 检查一次，减少 Handler 开销
                    if (visualizerStarted) {
                        mpkgBgAudioHandler.postDelayed(this, 500L)
                        return
                    }
                    try {
                        val player = GlobalContext.get().get<com.mineradio.app.player.api.IMusicPlayer>()
                        val bands = player.fftProcessor.bands.value
                        val result = mpkgBgAudioResult64
                        if (bands != null && bands.isNotEmpty()) {
                            // 检查是否有有效数据
                            var hasData = false
                            for (b in bands) {
                                if (b > 0.01f) {
                                    hasData = true
                                    break
                                }
                            }
                            if (hasData) {
                                // ★ 31 段 → 64 段线性插值
                                val srcLen = bands.size
                                for (i in 0 until 64) {
                                    val srcPos = (i.toFloat() / 63f) * (srcLen - 1)
                                    val srcIdx = srcPos.toInt()
                                    val frac = srcPos - srcIdx
                                    result[i] =
                                        if (srcIdx + 1 < srcLen) {
                                            bands[srcIdx] * (1 - frac) + bands[srcIdx + 1] * frac
                                        } else {
                                            bands[srcIdx]
                                        }.coerceIn(0f, 1f)
                                }
                                // ★ 在 GL 线程发送给场景
                                val data = result.copyOf()
                                mpkgBgGLSurfaceView?.queueEvent {
                                    try {
                                        if (mpkgBgContextId >= 0 && mpkgBgSceneInitialized) {
                                            mpkgBgSceneLib?.sendAudioData(mpkgBgContextId, data)
                                        }
                                    } catch (e: Throwable) {
                                        Log.e("MpkgBg", "sendAudioData (fftProcessor) failed", e)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 静默忽略（player 可能未初始化）
                    }
                    mpkgBgAudioHandler.postDelayed(this, 33L)
                }
            }
        mpkgBgAudioHandler.post(mpkgBgAudioRunnable!!)
        Log.d("MpkgBg", "Scene audio injection started (visualizerStarted=$visualizerStarted)")
    }

    /**
     * ★ 停止音频可视化
     */
    private fun stopMpkgBgAudioRecording() {
        try {
            mpkgBgAudioEnabled = false
            // 停止 fftProcessor 注入 Runnable
            mpkgBgAudioRunnable?.let { mpkgBgAudioHandler.removeCallbacks(it) }
            mpkgBgAudioRunnable = null
            // 停止 Visualizer
            val v = mpkgBgVisualizer
            if (v != null) {
                try {
                    v.setEnabled(false)
                } catch (_: Throwable) {
                }
                try {
                    v.release()
                } catch (_: Throwable) {
                }
            }
            mpkgBgVisualizer = null
            mpkgBgAudioFftBuffer = null
            // 清零结果，避免场景残留跳动
            for (i in 0 until 64) mpkgBgAudioResult64[i] = 0f
            // 发送一次全 0 数据，让场景停止跳动
            val zeroData = FloatArray(64)
            mpkgBgGLSurfaceView?.queueEvent {
                try {
                    if (mpkgBgContextId >= 0 && mpkgBgSceneInitialized) {
                        mpkgBgSceneLib?.sendAudioData(mpkgBgContextId, zeroData)
                    }
                } catch (_: Throwable) {
                }
            }
            Log.d("MpkgBg", "Scene audio injection stopped")
        } catch (e: Throwable) {
            Log.e("MpkgBg", "stopMpkgBgAudioRecording failed", e)
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // ★ 如果登录 Dialog 正在显示，先关闭 Dialog（不影响主 WebView）
            if (loginDialog?.isShowing == true) {
                loginDialog?.dismiss()
                return true
            }
            // 先让 JS 处理返回（逐层关闭面板）
            webView.evaluateJavascript(
                "try{window.handleAndroidBack&&window.handleAndroidBack()}catch(e){}",
            ) { result ->
                if (result == "null" || result == "\"closed\":false" || result?.contains("false") == true) {
                    webView.post {
                        // ★ 退出横屏：竖屏界面已移除，直接结束 App（不再切回竖屏方向）
                        try {
                            getSharedPreferences("mineradio_activity_state", MODE_PRIVATE)
                                .edit()
                                .putBoolean("last_was_landscape", false)
                                .apply()
                        } catch (_: Exception) {
                        }
                        finish()
                    }
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * ★ 显示独立登录 Dialog（QQ/酷狗/网易云/汽水）
     * 在独立 Dialog 中加载 PC 版登录页，不影响主 WebView
     * 登录成功后自动提取 cookie 并回调到 JS
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun showLoginDialog(
        url: String,
        provider: String,
    ) {
        // 先关闭已存在的 Dialog
        try {
            loginDialog?.dismiss()
        } catch (_: Exception) {
        }
        stopLoginPoll()
        loginDialog = null
        loginWebView = null
        loginDetectionCount = 0
        loginCompleted = false
        loginBrowserOpenTime = System.currentTimeMillis()
        qishuiBaselineSessionId = null

        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val container = FrameLayout(this)
        val loginWv = WebView(this)
        container.addView(loginWv)
        dialog.setContentView(container)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            stopLoginPoll()
            if (!loginCompleted) {
                webView.evaluateJavascript(
                    "try{window.showToast&&window.showToast('$provider 登录已取消')}catch(e){}",
                    null,
                )
            }
            try {
                loginWebView?.destroy()
            } catch (_: Exception) {
            }
            loginWebView = null
            loginDialog = null
        }

        loginWv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // ★ 修复白屏：桌面版页面需按宽视口渲染（与 mineradio-android openInAppBrowser 一致）
            useWideViewPort = true
            loadWithOverviewMode = true
            // ★ PC 版 UA，强制加载桌面版网页
            userAgentString = PC_USER_AGENT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(loginWv, true)

        loginWv.webChromeClient = WebChromeClient()
        // ★ 手动授权按钮桥：页面未自动授权时，用户点击官网左上角"授权登录"按钮强制检测登录态
        loginWv.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun forceCheckLogin() {
                    runOnUiThread {
                        if (loginWebView != null && !loginCompleted) {
                            Log.d(TAG, "LoginDialog manual auth button clicked, forcing cookie dispatch")
                            // ★ 重置汽水基线，避免"已登录但基线被锁死"导致检测不到
                            qishuiBaselineSessionId = null
                            // ★ 手动授权：绕过脆弱的基线检测，直接强制提取当前 Cookie 并提交
                            forceManualAuthCheck(loginPollProvider ?: provider)
                        }
                    }
                }
            },
            "MrLoginBridge",
        )
        loginWv.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    if (loginCompleted) return
                    loginDetectionCount++
                    Log.d(TAG, "LoginDialog onPageFinished #$loginDetectionCount url=$url")
                    if (loginDetectionCount <= 40) {
                        checkLoginCookie(provider)
                    }
                    // ★ 每次页面加载完成后注入左上角"授权登录"悬浮按钮
                    injectLoginAuthButton(loginWv)
                }
            }

        loginWv.loadUrl(url)
        Toast.makeText(this, "请在打开的页面中扫码登录，完成后自动返回", Toast.LENGTH_LONG).show()

        loginWebView = loginWv
        loginDialog = dialog
        dialog.show()
        // ★ 启动定时轮询 cookie — 解决 QQ 扫码选头像后无页面跳转导致检测失败的问题
        startLoginPoll(provider)
    }

    /**
     * ★ 在登录页面左上角注入"授权登录"悬浮按钮
     *   当页面已登录但应用未自动授权时，用户点击此按钮强制检测登录态并同步
     */
    private fun injectLoginAuthButton(wv: WebView?) {
        if (wv == null) return
        try {
            wv.evaluateJavascript(
                "(function(){if(window.__mrAuthBtnInjected)return;window.__mrAuthBtnInjected=true;" +
                    "function _mrMakeBtn(){try{" +
                    "var old=document.getElementById('__mr_auth_btn');if(old)old.remove();" +
                    "var d=document.createElement('div');d.id='__mr_auth_btn';" +
                    "d.setAttribute('style','position:fixed;top:12px;left:12px;z-index:2147483647;');" +
                    "var b=document.createElement('button');" +
                    "b.textContent='授权登录';" +
                    "b.setAttribute('style'," +
                    "'background:linear-gradient(135deg,#1d8cf8,#0a6adb);color:#fff;border:none;" +
                    "border-radius:20px;padding:10px 18px;font-size:14px;font-weight:700;" +
                    "font-family:system-ui,-apple-system,sans-serif;cursor:pointer;" +
                    "box-shadow:0 4px 14px rgba(0,0,0,.35);');" +
                    "b.addEventListener('click',function(){" +
                    "b.textContent='授权中…';b.disabled=true;" +
                    "try{window.MrLoginBridge&&window.MrLoginBridge.forceCheckLogin&&" +
                    "window.MrLoginBridge.forceCheckLogin();}catch(e){}" +
                    "setTimeout(function(){b.textContent='授权登录';b.disabled=false;},1800);" +
                    "});" +
                    "d.appendChild(b);(document.body||document.documentElement).appendChild(d);" +
                    "}catch(e){}};" +
                    "_mrMakeBtn();" +
                    "})();",
                null,
            )
        } catch (_: Exception) {
        }
    }

    /**
     * ★ 开始轮询 cookie（每 1.5 秒检查一次）
     */
    private fun startLoginPoll(provider: String) {
        stopLoginPoll()
        loginPollProvider = provider
        loginPollTimer = android.os.Handler(android.os.Looper.getMainLooper())
        loginPollRunnable =
            object : Runnable {
                override fun run() {
                    if (loginCompleted || loginWebView == null) {
                        stopLoginPoll()
                        return
                    }
                    try {
                        checkLoginCookie(provider)
                    } catch (_: Exception) {
                    }
                    loginPollTimer?.postDelayed(this, 1500)
                }
            }
        // 首次延迟 2 秒，避免页面初始加载阶段误检
        loginPollTimer?.postDelayed(loginPollRunnable!!, 2000)
    }

    private fun stopLoginPoll() {
        try {
            loginPollRunnable?.let { loginPollTimer?.removeCallbacks(it) }
        } catch (_: Exception) {
        }
        loginPollRunnable = null
        loginPollTimer = null
        loginPollProvider = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ★ haowallpaper 登录流程
    //    首次下载需要登录获取 token，登录后 token 持久化到 SharedPreferences
    //    后续下载直接复用已保存的 token，无需再次登录
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * ★ 保存 haowallpaper token 到 SharedPreferences
     *   token 来自 cookie 中名为 "token" 的字段
     */
    private fun saveHaoToken(token: String) {
        try {
            getSharedPreferences(HAO_TOKEN_PREFS, MODE_PRIVATE)
                .edit()
                .putString(HAO_TOKEN_KEY, token)
                .apply()
            Log.i(TAG, "HaoLogin: token saved (length=${token.length})")
        } catch (e: Exception) {
            Log.e(TAG, "HaoLogin: save token failed", e)
        }
    }

    /**
     * ★ 读取已保存的 haowallpaper token
     *   返回空字符串表示尚未登录
     */
    private fun loadHaoToken(): String =
        try {
            getSharedPreferences(HAO_TOKEN_PREFS, MODE_PRIVATE)
                .getString(HAO_TOKEN_KEY, "") ?: ""
        } catch (e: Exception) {
            ""
        }

    /**
     * ★ POST JSON 请求（带 JWT 认证头）
     * 用于用户上传壁纸服务端 API 调用
     *
     * @param urlStr 请求 URL
     * @param body JSON 请求体
     * @param token JWT token
     * @return 响应体字符串，失败返回空字符串
     */
    private fun postJsonWithAuth(
        urlStr: String,
        body: String,
        token: String,
    ): String {
        val conn =
            (URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30000
                readTimeout = 60000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", "Mineradio-Android/1.1")
                doOutput = true
                instanceFollowRedirects = true
            }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.e(TAG, "postJsonWithAuth: HTTP $code for $urlStr")
                return ""
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * ★ Multipart 上传壁纸到 VPS 服务器
     * 替代旧的 COS 预签名 URL 上传方式，直接 POST 文件到 /api/wallpapers/upload
     *
     * @param url 上传接口地址
     * @param token JWT token
     * @param title 壁纸标题
     * @param description 壁纸描述
     * @param mpkgUri .mpkg 文件的 content URI
     * @param mpkgName .mpkg 文件名
     * @param coverUri 封面图 URI（可为 null）
     * @param coverName 封面图文件名（可为空）
     * @return 服务器响应 JSON 字符串
     */
    private fun multipartUploadWallpaper(
        url: String,
        token: String,
        title: String,
        description: String,
        mpkgUri: Uri,
        mpkgName: String,
        coverUri: Uri?,
        coverName: String,
    ): String {
        val boundary = "----MineradioBoundary${System.currentTimeMillis()}"

        // ★ 预先计算总大小（用于进度百分比）
        val mpkgSize =
            try {
                contentResolver.openAssetFileDescriptor(mpkgUri, "r")?.length ?: 0L
            } catch (_: Exception) {
                0L
            }
        val coverSize =
            if (coverUri != null && coverName.isNotEmpty()) {
                try {
                    contentResolver.openAssetFileDescriptor(coverUri, "r")?.length ?: 0L
                } catch (_: Exception) {
                    0L
                }
            } else {
                0L
            }
        val totalBytes = mpkgSize + coverSize
        var sentBytes = 0L
        var lastReportTime = System.currentTimeMillis()

        val conn =
            (URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 60000
                readTimeout = 300000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", "Mineradio-Android/1.1")
                doOutput = true
                instanceFollowRedirects = true
                // ★ 使用分块传输（64KB/块），避免 HttpURLConnection 缓存整个请求体到内存（大文件会 OOM）
                setChunkedStreamingMode(65536)
            }
        try {
            conn.outputStream.use { os ->
                val writer = java.io.OutputStreamWriter(os, Charsets.UTF_8)

                fun writeField(
                    name: String,
                    value: String,
                ) {
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    writer.write(value)
                    writer.write("\r\n")
                    writer.flush()
                }

                fun writeFile(
                    fieldName: String,
                    fileName: String,
                    contentType: String,
                    uri: Uri,
                ) {
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n")
                    writer.write("Content-Type: $contentType\r\n\r\n")
                    writer.flush()
                    contentResolver.openInputStream(uri).use { input ->
                        if (input != null) {
                            // ★ 增大缓冲区到 64KB，提升大文件读写速度
                            val buf = ByteArray(65536)
                            var n: Int
                            while (input.read(buf).also { n = it } > 0) {
                                os.write(buf, 0, n)
                                sentBytes += n
                                // ★ 每 500ms 上报一次进度
                                val now = System.currentTimeMillis()
                                if (now - lastReportTime >= 500) {
                                    lastReportTime = now
                                    val percent = if (totalBytes > 0) (sentBytes * 100 / totalBytes).toInt() else 0
                                    runOnUiThread {
                                        webView.evaluateJavascript(
                                            "if(window.__onWallpaperUploadProgress)window.__onWallpaperUploadProgress($percent,$sentBytes,$totalBytes);",
                                            null,
                                        )
                                    }
                                }
                            }
                            os.flush()
                        }
                    }
                    writer.write("\r\n")
                    writer.flush()
                }

                // 普通字段
                writeField("title", title)
                writeField("description", description)

                // .mpkg 文件
                writeFile("mpkg", mpkgName, "application/octet-stream", mpkgUri)

                // 封面图（可选）
                if (coverUri != null && coverName.isNotEmpty()) {
                    writeFile("cover", coverName, "image/jpeg", coverUri)
                }

                // 结束
                writer.write("--$boundary--\r\n")
                writer.flush()
            }

            val code = conn.responseCode
            Log.i(TAG, "multipartUploadWallpaper: HTTP $code")
            if (code !in 200..299) {
                Log.e(TAG, "multipartUploadWallpaper: HTTP $code for $url")
                return ""
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "multipartUploadWallpaper failed", e)
            return ""
        } finally {
            conn.disconnect()
        }
    }

    /**
     * ★ APP 直传蓝奏云（绕过 VPS，解决 VPS→蓝奏云 速度慢的问题）
     *
     * 流程：
     * 1. GET /api/wallpapers/lanzou-ticket 获取上传凭证（cookie, folder_id, ylogin）
     * 2. 直接 multipart POST 文件到 https://pc.woozooo.com/html5up.php
     * 3. POST /api/wallpapers/lanzou-complete 回调服务器保存分享链接和封面
     *
     * @param token JWT token
     * @param title 壁纸标题
     * @param description 壁纸描述
     * @param mpkgUri .mpkg 文件的 content URI
     * @param mpkgName .mpkg 文件名
     * @param coverUri 封面图 URI（可为 null）
     * @param coverName 封面图文件名（可为空）
     * @return 服务器最终保存响应 JSON 字符串
     */
    private fun uploadToLanzouDirect(
        token: String,
        title: String,
        description: String,
        mpkgUri: Uri,
        mpkgName: String,
        coverUri: Uri?,
        coverName: String,
    ): String {
        Log.i(TAG, "★ uploadToLanzouDirect: title=$title, mpkgName=$mpkgName")
        try {
            // ── 1. 获取上传凭证 ──
            com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "正在获取上传凭证..."
            val ticketUrl = "$WALLPAPER_SERVER_API_BASE/api/wallpapers/lanzou-ticket"
            val ticketConn =
                (URL(ticketUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("User-Agent", "Mineradio-Android/1.1")
                }
            val ticketCode = ticketConn.responseCode
            val ticketResp =
                if (ticketCode in 200..299) {
                    ticketConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    Log.e(TAG, "uploadToLanzouDirect: ticket HTTP $ticketCode")
                    // ★ 401 时读取错误响应体，检测认证失败，清除过期 token 并提示重新登录
                    if (ticketCode == 401) {
                        val errBody =
                            try {
                                ticketConn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                            } catch (_: Exception) {
                                ""
                            }
                        Log.w(TAG, "uploadToLanzouDirect: ticket 401 body=${errBody.take(200)}")
                        // 清除过期 token，避免后续请求继续使用失效凭证
                        getSharedPreferences(MINERADIO_TOKEN_PREFS, MODE_PRIVATE).edit().remove(MINERADIO_TOKEN_KEY).apply()
                        return """{"ok":false,"error":"登录已过期，请重新登录","auth_expired":true}"""
                    }
                    return """{"ok":false,"error":"获取上传凭证失败（HTTP $ticketCode）"}"""
                }
            ticketConn.disconnect()
            Log.i(TAG, "uploadToLanzouDirect: ticket resp=${ticketResp.take(200)}")

            val ticketJson = JSONObject(ticketResp)
            if (!ticketJson.optBoolean("ok", false)) {
                val err = ticketJson.optString("error", "unknown")
                val msg = ticketJson.optString("message", "获取上传凭证失败")
                // 蓝奏云未启用或未登录时，回退到原 VPS 上传流程
                if (err == "lanzou_disabled" || err == "lanzou_not_logged_in") {
                    Log.w(TAG, "uploadToLanzouDirect: $err, fallback to VPS upload")
                    return "" // 空字符串表示回退
                }
                return """{"ok":false,"error":"$msg"}"""
            }
            val data = ticketJson.optJSONObject("data") ?: return """{"ok":false,"error":"凭证数据为空"}"""
            val uploadUrl = data.optString("upload_url")
            val cookie = data.optString("cookie")
            val ylogin = data.optString("ylogin")
            val folderId = data.optString("folder_id", "-1")
            val allowedExts =
                data.optJSONArray("allowed_exts")?.let { arr ->
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                    set
                } ?: emptySet()

            if (uploadUrl.isEmpty() || cookie.isEmpty()) {
                return """{"ok":false,"error":"上传凭证不完整"}"""
            }

            // ── 2. 直传文件到蓝奏云 ──
            com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "正在上传到蓝奏云..."
            val origExt = mpkgName.substringAfterLast('.', "").lowercase()
            val uploadFileName = if (allowedExts.contains(origExt)) mpkgName else "$mpkgName.zip"
            Log.i(TAG, "uploadToLanzouDirect: uploading to $uploadUrl, folder=$folderId, fileName=$uploadFileName")

            val mpkgSize =
                try {
                    contentResolver.openAssetFileDescriptor(mpkgUri, "r")?.length ?: 0L
                } catch (_: Exception) {
                    0L
                }

            val boundary = "----LanzouBoundary${System.currentTimeMillis()}"
            val referer = "https://pc.woozooo.com/mydisk.php?item=files&action=index&u=$ylogin"

            val upConn =
                (URL(uploadUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30000
                    readTimeout = 600000 // 10 分钟
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    setRequestProperty("Cookie", cookie)
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                    )
                    setRequestProperty("Referer", referer)
                    setRequestProperty("Origin", "https://pc.woozooo.com")
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                    doOutput = true
                    instanceFollowRedirects = false
                    setChunkedStreamingMode(65536)
                }

            var sentBytes = 0L
            var lastReportTime = System.currentTimeMillis()

            try {
                upConn.outputStream.use { os ->
                    val writer = java.io.OutputStreamWriter(os, Charsets.UTF_8)

                    fun writeField(
                        name: String,
                        value: String,
                    ) {
                        writer.write("--$boundary\r\n")
                        writer.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                        writer.write(value)
                        writer.write("\r\n")
                        writer.flush()
                    }

                    // 蓝奏云上传表单字段
                    writeField("task", "1")
                    writeField("vie", "2")
                    writeField("ve", "2")
                    writeField("folder_id", folderId)

                    // 文件字段
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"upload_file\"; filename=\"$uploadFileName\"\r\n")
                    writer.write("Content-Type: application/octet-stream\r\n\r\n")
                    writer.flush()
                    contentResolver.openInputStream(mpkgUri).use { input ->
                        if (input != null) {
                            val buf = ByteArray(65536)
                            var n: Int
                            while (input.read(buf).also { n = it } > 0) {
                                os.write(buf, 0, n)
                                sentBytes += n
                                val now = System.currentTimeMillis()
                                if (now - lastReportTime >= 500) {
                                    lastReportTime = now
                                    val percent = if (mpkgSize > 0) (sentBytes * 100 / mpkgSize).toInt() else 0
                                    runOnUiThread {
                                        webView.evaluateJavascript(
                                            "if(window.__onWallpaperUploadProgress)window.__onWallpaperUploadProgress($percent,$sentBytes,$mpkgSize);",
                                            null,
                                        )
                                    }
                                }
                            }
                            os.flush()
                        }
                    }
                    writer.write("\r\n")
                    writer.write("--$boundary--\r\n")
                    writer.flush()
                }

                val upCode = upConn.responseCode
                Log.i(TAG, "uploadToLanzouDirect: lanzou HTTP $upCode")
                if (upCode !in 200..299) {
                    return """{"ok":false,"error":"蓝奏云上传失败（HTTP $upCode）"}"""
                }
                val upResp = upConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                Log.i(TAG, "uploadToLanzouDirect: lanzou resp=${upResp.take(300)}")

                val upJson = JSONObject(upResp)
                if (upJson.optInt("zt", 0) != 1) {
                    val info = upJson.optString("info", "上传失败")
                    return """{"ok":false,"error":"蓝奏云上传失败：$info"}"""
                }

                // 解析分享链接：text[0].is_newd + "/" + text[0].f_id
                val textArr = upJson.optJSONArray("text")
                val fileInfo = if (textArr != null && textArr.length() > 0) textArr.getJSONObject(0) else upJson.optJSONObject("text")
                val fileId = fileInfo?.optString("id", "") ?: ""
                val fId = fileInfo?.optString("f_id", "") ?: ""
                val isNewd = fileInfo?.optString("is_newd", "") ?: ""
                val shareUrl = if (isNewd.isNotEmpty() && fId.isNotEmpty()) "$isNewd/$fId" else ""
                Log.i(TAG, "uploadToLanzouDirect: shareUrl=$shareUrl, fileId=$fileId")

                if (shareUrl.isEmpty()) {
                    return """{"ok":false,"error":"蓝奏云上传成功但未返回分享链接"}"""
                }

                // ── 3. 回调服务器保存元数据 ──
                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "正在保存壁纸信息..."
                // ★ 封面图也上传到蓝奏云，避免 CloudBase 云函数 body 大小限制触发 HTTP 413
                var coverShareUrl = ""
                if (coverUri != null && coverName.isNotEmpty()) {
                    try {
                        com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "正在上传封面..."
                        coverShareUrl = uploadCoverToLanzou(coverUri, coverName, cookie, folderId, ylogin)
                        Log.i(TAG, "uploadToLanzouDirect: coverShareUrl=$coverShareUrl")
                    } catch (e: Exception) {
                        Log.w(TAG, "uploadToLanzouDirect: 封面上传蓝奏云失败: ${e.message}")
                    }
                }

                val completeBody =
                    JSONObject().apply {
                        put("title", title)
                        put("description", description)
                        put("mpkg_share_url", shareUrl)
                        put("mpkg_file_id", fileId)
                        put("mpkg_name", mpkgName)
                        put("mpkg_size", mpkgSize)
                        put("cover_share_url", coverShareUrl)
                    }

                val completeConn =
                    (
                        URL(
                            "$WALLPAPER_SERVER_API_BASE/api/wallpapers/lanzou-complete",
                        ).openConnection() as java.net.HttpURLConnection
                    ).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 60000
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        setRequestProperty("User-Agent", "Mineradio-Android/1.1")
                        doOutput = true
                    }
                try {
                    completeConn.outputStream.use { it.write(completeBody.toString().toByteArray(Charsets.UTF_8)) }
                    val cmpCode = completeConn.responseCode
                    val cmpResp =
                        if (cmpCode in 200..299) {
                            completeConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        } else {
                            Log.e(TAG, "uploadToLanzouDirect: complete HTTP $cmpCode")
                            // ★ 401 时读取错误响应体，检测认证失败，清除过期 token 并提示重新登录
                            if (cmpCode == 401) {
                                val errBody =
                                    try {
                                        completeConn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                                    } catch (_: Exception) {
                                        ""
                                    }
                                Log.w(TAG, "uploadToLanzouDirect: complete 401 body=${errBody.take(200)}")
                                getSharedPreferences(MINERADIO_TOKEN_PREFS, MODE_PRIVATE).edit().remove(MINERADIO_TOKEN_KEY).apply()
                                """{"ok":false,"error":"登录已过期，请重新登录","auth_expired":true}"""
                            } else {
                                """{"ok":false,"error":"保存壁纸信息失败（HTTP $cmpCode）"}"""
                            }
                        }
                    Log.i(TAG, "uploadToLanzouDirect: complete resp=${cmpResp.take(200)}")
                    return cmpResp
                } finally {
                    completeConn.disconnect()
                }
            } finally {
                upConn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadToLanzouDirect failed", e)
            return """{"ok":false,"error":"${(e.message ?: "未知错误").replace("\"", "'")}"}"""
        }
    }

    /**
     * ★ 上传封面图到蓝奏云（复用壁纸上传的 cookie 和 folderId）
     * - 先压缩到最大边 800px + JPEG 80%
     * - 用 multipart 上传到蓝奏云指定文件夹
     * - 返回分享链接，失败返回空字符串
     */
    private fun uploadCoverToLanzou(
        coverUri: Uri,
        coverName: String,
        cookie: String,
        folderId: String,
        ylogin: String,
    ): String {
        Log.i(TAG, "★ uploadCoverToLanzou: coverName=$coverName, folderId=$folderId")
        try {
            // 1. 压缩封面图到临时文件
            val compressedFile = java.io.File(cacheDir, "cover_upload_${System.currentTimeMillis()}.jpg")
            try {
                // 解码边界获取尺寸
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(coverUri).use { ins ->
                    if (ins != null) BitmapFactory.decodeStream(ins, null, boundsOpts)
                }
                val w = boundsOpts.outWidth
                val h = boundsOpts.outHeight
                if (w <= 0 || h <= 0) return ""

                // 计算采样率
                val maxSide = 800
                var sample = 1
                while (kotlin.math.max(w / sample, h / sample) > maxSide * 2) sample *= 2

                // 重新打开输入流解码（前面 inJustDecodeBounds 已经消耗了流）
                val bmp =
                    contentResolver.openInputStream(coverUri).use { ins ->
                        val decodeOpts = BitmapFactory.Options()
                        decodeOpts.inSampleSize = sample
                        if (ins == null) null else BitmapFactory.decodeStream(ins, null, decodeOpts)
                    } ?: return ""

                // 等比缩放
                val scaledBmp =
                    if (kotlin.math.max(bmp.width, bmp.height) > 800) {
                        val scale = 800f / kotlin.math.max(bmp.width, bmp.height)
                        android.graphics.Bitmap
                            .createScaledBitmap(
                                bmp,
                                (bmp.width * scale).toInt().coerceAtLeast(1),
                                (bmp.height * scale).toInt().coerceAtLeast(1),
                                true,
                            ).also { if (it != bmp) bmp.recycle() }
                    } else {
                        bmp
                    }

                // 写入临时文件
                java.io.FileOutputStream(compressedFile).use { fos ->
                    scaledBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, fos)
                }
                scaledBmp.recycle()
                Log.i(TAG, "uploadCoverToLanzou: compressed to ${compressedFile.length()} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "uploadCoverToLanzou: compress failed", e)
                return ""
            }

            // 2. multipart 上传到蓝奏云
            val uploadUrl = "https://pc.woozooo.com/html5up.php"
            val boundary = "----CoverBoundary${System.currentTimeMillis()}"
            val referer = "https://pc.woozooo.com/mydisk.php?item=files&action=index&u=$ylogin"
            val coverFileName = "cover_${System.currentTimeMillis()}.jpg"

            val upConn =
                (URL(uploadUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30000
                    readTimeout = 120000
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    setRequestProperty("Cookie", cookie)
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                    )
                    setRequestProperty("Referer", referer)
                    setRequestProperty("Origin", "https://pc.woozooo.com")
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    doOutput = true
                    instanceFollowRedirects = false
                    setChunkedStreamingMode(65536)
                }

            try {
                upConn.outputStream.use { os ->
                    val writer = java.io.OutputStreamWriter(os, Charsets.UTF_8)
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"task\"\r\n\r\n1\r\n")
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"vie\"\r\n\r\n2\r\n")
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"ve\"\r\n\r\n2\r\n")
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"folder_id\"\r\n\r\n$folderId\r\n")
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"upload_file\"; filename=\"$coverFileName\"\r\n")
                    writer.write("Content-Type: image/jpeg\r\n\r\n")
                    writer.flush()
                    compressedFile.inputStream().use { it.copyTo(os) }
                    writer.write("\r\n--$boundary--\r\n")
                    writer.flush()
                }

                val code = upConn.responseCode
                if (code !in 200..299) {
                    Log.e(TAG, "uploadCoverToLanzou: HTTP $code")
                    return ""
                }
                val resp = upConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                Log.i(TAG, "uploadCoverToLanzou: resp=${resp.take(300)}")

                val respJson = JSONObject(resp)
                if (respJson.optInt("zt", 0) != 1) return ""

                val textArr = respJson.optJSONArray("text")
                val fileInfo = if (textArr != null && textArr.length() > 0) textArr.getJSONObject(0) else respJson.optJSONObject("text")
                val fId = fileInfo?.optString("f_id", "") ?: ""
                val isNewd = fileInfo?.optString("is_newd", "") ?: ""
                return if (isNewd.isNotEmpty() && fId.isNotEmpty()) "$isNewd/$fId" else ""
            } finally {
                upConn.disconnect()
                try {
                    compressedFile.delete()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadCoverToLanzou failed", e)
            return ""
        }
    }

    /**
     * ★ 压缩封面图并转为 base64（避免 CloudBase 云函数 body 6MB 限制触发 HTTP 413）
     *
     * - 限制最大边长 800px，等比缩放
     * - JPEG 质量 80 压缩
     * - 返回 (base64, ext)，失败返回 ("", "jpg")
     */
    private fun compressCoverToBase64(uri: Uri): Pair<String, String> {
        return try {
            // 1. 先读边界获取尺寸
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= 0 || h <= 0) return "" to "jpg"

            // 2. 计算采样率（inSampleSize），目标最大边 800
            val maxSide = 800
            var sample = 1
            while (kotlin.math.max(w / sample, h / sample) > maxSide * 2) sample *= 2

            // 3. 解码为 Bitmap（带采样）
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp =
                contentResolver.openInputStream(uri).use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                } ?: return "" to "jpg"

            // 4. 等比缩放到最大边 800
            val scaledBmp =
                if (kotlin.math.max(bmp.width, bmp.height) > maxSide) {
                    val scale = maxSide.toFloat() / kotlin.math.max(bmp.width, bmp.height)
                    android.graphics.Bitmap
                        .createScaledBitmap(
                            bmp,
                            (bmp.width * scale).toInt().coerceAtLeast(1),
                            (bmp.height * scale).toInt().coerceAtLeast(1),
                            true,
                        ).also { if (it != bmp) bmp.recycle() }
                } else {
                    bmp
                }

            // 5. JPEG 质量 80 压缩
            val baos = java.io.ByteArrayOutputStream()
            scaledBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
            scaledBmp.recycle()
            val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            Log.i(TAG, "compressCoverToBase64: ${w}x$h → ${base64.length} chars base64")
            base64 to "jpg"
        } catch (e: Exception) {
            Log.w(TAG, "compressCoverToBase64 failed: ${e.message}")
            "" to "jpg"
        }
    }

    /**
     * ★ PUT 文件到 COS 预签名 URL
     * 用于用户上传壁纸文件到腾讯云 COS
     *
     * @param presignedUrl COS 预签名 URL
     * @param fileUri 文件的 content URI
     * @param contentType MIME 类型
     * @return 上传成功返回 true，失败返回 false
     */
    private fun putFileToCos(
        presignedUrl: String,
        fileUri: Uri,
        contentType: String,
    ): Boolean {
        val conn =
            (URL(presignedUrl).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = 60000
                readTimeout = 120000
                setRequestProperty("Content-Type", contentType)
                doOutput = true
                instanceFollowRedirects = true
            }
        try {
            contentResolver.openInputStream(fileUri).use { input ->
                if (input == null) {
                    Log.e(TAG, "putFileToCos: cannot open input stream for $fileUri")
                    return false
                }
                conn.outputStream.use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (true) {
                        read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.e(TAG, "putFileToCos: HTTP $code for $presignedUrl")
                return false
            }
            Log.i(TAG, "putFileToCos: uploaded to COS, HTTP $code")
            return true
        } finally {
            conn.disconnect()
        }
    }

    /**
     * ★ 将保存的 token 注入到 CookieManager（在 loadUrl 之前调用）
     *   haowallpaper 的 JS 通过 document.cookie 读取 token
     */
    private fun injectHaoTokenToCookieManager() {
        val token = loadHaoToken()
        if (token.isBlank()) return
        try {
            val cookieValue = "token=$token; path=/; domain=.haowallpaper.com; secure; samesite=lax"
            CookieManager.getInstance().setCookie("https://haowallpaper.com", cookieValue)
            CookieManager.getInstance().setCookie("https://haowallpaper.com/", cookieValue)
            Log.i(TAG, "HaoLogin: injected saved token into CookieManager")
        } catch (e: Exception) {
            Log.e(TAG, "HaoLogin: inject token failed", e)
        }
    }

    /**
     * ★ 清空 haowallpaper.com 的所有 cookies（模拟新浏览器会话）
     *   每次下载前调用，让服务器以为是新用户访问，绕过每日下载限制
     *   token 会随后通过 injectHaoTokenToCookieManager 重新注入
     */
    private fun clearHaoCookies() {
        try {
            val cm = CookieManager.getInstance()
            val domains =
                listOf(
                    "https://haowallpaper.com/",
                    "https://haowallpaper.com",
                    "https://www.haowallpaper.com/",
                    "https://www.haowallpaper.com",
                )
            for (domain in domains) {
                val cookieStr = cm.getCookie(domain) ?: continue
                cookieStr.split(";").forEach { cookie ->
                    val name = cookie.trim().substringBefore("=", "").trim()
                    if (name.isNotEmpty()) {
                        // 设置过期时间为过去，删除 cookie
                        cm.setCookie(
                            domain,
                            "$name=; path=/; domain=.haowallpaper.com; expires=Thu, 01 Jan 1970 00:00:00 GMT; max-age=0",
                        )
                    }
                }
            }
            // 同步移除所有 cookie
            cm.removeAllCookies(null)
            cm.flush()
            Log.i(TAG, "HaoCookies: cleared all haowallpaper cookies for new session")
        } catch (e: Exception) {
            Log.e(TAG, "HaoCookies: clear failed", e)
        }
    }

    /**
     * ★ 生成随机 User-Agent（模拟不同设备/浏览器）
     *   每次下载使用不同的 UA，配合清空 cookies 实现真正的"新环境"模拟
     */
    private fun getRandomUserAgent(): String {
        val r = java.util.Random()
        val chromeMajor = 118 + r.nextInt(8) // 118-125
        val chromeFull = "$chromeMajor.0.${r.nextInt(9999)}.${r.nextInt(99)}"
        val platforms =
            listOf(
                // Windows Chrome
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeFull Safari/537.36",
                // Mac Chrome
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeFull Safari/537.36",
                // Linux Chrome
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeFull Safari/537.36",
                // Windows Firefox
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:${121 + r.nextInt(4)}.0) Gecko/20100101 Firefox/${121 + r.nextInt(4)}.0",
                // Mac Safari
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.${r.nextInt(
                    3,
                )} Safari/605.1.15",
                // Windows Edge
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeFull Safari/537.36 Edg/$chromeFull",
                // Linux Firefox
                "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:${121 + r.nextInt(4)}.0) Gecko/20100101 Firefox/${121 + r.nextInt(4)}.0",
            )
        return platforms[r.nextInt(platforms.size)]
    }

    /**
     * ★ 显示 haowallpaper 登录 Dialog
     *   加载 haowallpaper 首页，让用户使用任何方式登录（微信扫码等）
     *   轮询检测 cookie/localStorage 中的 token，发现后自动关闭并重试下载
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun showHaoLoginDialog(
        pendingDetailUrl: String,
        pendingName: String,
        pendingType: String,
    ) {
        // 保存待重试的下载参数
        haoPendingDetailUrl = pendingDetailUrl
        haoPendingName = pendingName
        haoPendingType = pendingType

        // 关闭已存在的下载 WebView（释放资源，登录后会重新创建）
        try {
            haoDownloadWebView?.let {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                it.destroy()
            }
        } catch (_: Exception) {
        }
        haoDownloadWebView = null

        // 关闭已存在的登录 Dialog
        try {
            haoLoginDialog?.dismiss()
        } catch (_: Exception) {
        }
        stopHaoLoginPoll()
        haoLoginDialog = null
        haoLoginWebView = null
        haoLoginDetectionCount = 0
        haoLoginCompleted = false

        com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载中"

        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val container = FrameLayout(this)
        val loginWv = WebView(this)
        container.addView(loginWv)
        dialog.setContentView(container)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            stopHaoLoginPoll()
            if (!haoLoginCompleted) {
                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载失败"
                try {
                    webView.evaluateJavascript(
                        "try{window.onHaoWebDownloadCancel&&window.onHaoWebDownloadCancel('用户取消登录')}catch(e){}",
                        null,
                    )
                } catch (_: Exception) {
                }
                try {
                    haoDownloadDialog?.dismiss()
                } catch (_: Exception) {
                }
                Toast.makeText(this, "登录已取消，下载失败", Toast.LENGTH_SHORT).show()
            }
            try {
                haoLoginWebView?.destroy()
            } catch (_: Exception) {
            }
            haoLoginWebView = null
            haoLoginDialog = null
        }

        loginWv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // ★ PC 版 UA，便于扫码登录界面正常显示
            userAgentString = PC_USER_AGENT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(loginWv, true)

        // ★ 注入已保存的 token（如果有），便于静默自动登录
        injectHaoTokenToCookieManager()

        loginWv.webChromeClient = WebChromeClient()
        loginWv.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    if (haoLoginCompleted) return
                    haoLoginDetectionCount++
                    Log.d(TAG, "HaoLoginDialog onPageFinished #$haoLoginDetectionCount url=$url")
                    if (haoLoginDetectionCount <= 60) {
                        checkHaoLoginToken()
                    }
                }
            }

        // ★ 加载首页，登录按钮通常在右上角
        loginWv.loadUrl("https://haowallpaper.com/")

        Toast.makeText(this, "请在打开的页面中登录 haowallpaper，登录后自动下载", Toast.LENGTH_LONG).show()

        haoLoginWebView = loginWv
        haoLoginDialog = dialog
        dialog.show()
        // ★ 启动定时轮询 token（每 1.5 秒检查一次）
        startHaoLoginPoll()
    }

    /**
     * ★ 启动定时轮询 haowallpaper 登录 token
     *   解决扫码后页面不跳转导致 onPageFinished 不触发的问题
     */
    private fun startHaoLoginPoll() {
        stopHaoLoginPoll()
        haoLoginPollTimer = android.os.Handler(android.os.Looper.getMainLooper())
        haoLoginPollRunnable =
            object : Runnable {
                override fun run() {
                    if (haoLoginCompleted || haoLoginWebView == null) {
                        stopHaoLoginPoll()
                        return
                    }
                    try {
                        checkHaoLoginToken()
                    } catch (_: Exception) {
                    }
                    haoLoginPollTimer?.postDelayed(this, 1500)
                }
            }
        // 首次延迟 2 秒，避免页面初始加载阶段误检
        haoLoginPollTimer?.postDelayed(haoLoginPollRunnable!!, 2000)
    }

    private fun stopHaoLoginPoll() {
        try {
            haoLoginPollRunnable?.let { haoLoginPollTimer?.removeCallbacks(it) }
        } catch (_: Exception) {
        }
        haoLoginPollRunnable = null
        haoLoginPollTimer = null
    }

    /**
     * ★ 检测 haowallpaper 登录 token
     *   优先检查 cookie 中的 "token" 字段，再检查 localStorage.token
     *   检测到 token 后保存、关闭登录 Dialog、自动重试下载
     */
    private fun checkHaoLoginToken() {
        val wv = haoLoginWebView ?: return
        // 1. 检查 cookie 中的 token
        val cookieStr = CookieManager.getInstance().getCookie("https://haowallpaper.com/") ?: ""
        val cookieToken =
            cookieStr
                .split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("token=", ignoreCase = true) && it.length > 6 }
                ?.substringAfter("token=")
                ?.trim()
                ?: ""

        if (cookieToken.isNotBlank()) {
            Log.i(TAG, "HaoLogin: token found in cookie (length=${cookieToken.length})")
            handleHaoLoginSuccess(cookieToken)
            return
        }

        // 2. 检查 localStorage 中的 token（Nuxt SPA 通常存这里）
        wv.evaluateJavascript(
            "(function(){try{return localStorage.getItem('token')||localStorage.getItem('hao_token')||''}catch(e){return ''}})()",
        ) { result ->
            val ls = result?.trim()?.removeSurrounding("\"") ?: ""
            if (ls.isNotBlank() && !ls.equals("null", true) && !ls.equals("undefined", true)) {
                Log.i(TAG, "HaoLogin: token found in localStorage (length=${ls.length})")
                // 同步写入 cookie，方便后续下载 WebView 使用
                try {
                    val cookieValue = "token=$ls; path=/; domain=.haowallpaper.com; secure; samesite=lax"
                    CookieManager.getInstance().setCookie("https://haowallpaper.com", cookieValue)
                    CookieManager.getInstance().setCookie("https://haowallpaper.com/", cookieValue)
                } catch (_: Exception) {
                }
                handleHaoLoginSuccess(ls)
            }
        }
    }

    /**
     * ★ 登录成功处理：保存 token、关闭登录 Dialog、自动重试下载
     */
    private fun handleHaoLoginSuccess(token: String) {
        if (haoLoginCompleted) return
        haoLoginCompleted = true
        saveHaoToken(token)
        stopHaoLoginPoll()
        // 关闭登录 Dialog
        try {
            haoLoginDialog?.dismiss()
        } catch (_: Exception) {
        }
        // 等待 500ms 让 cookie 完全同步后重试下载
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val url = haoPendingDetailUrl
            val name = haoPendingName
            val type = haoPendingType
            haoPendingDetailUrl = null
            if (url != null) {
                Log.i(TAG, "HaoLogin: retry download after login, url=$url")
                showHaoWebDownloadDialog(url, name, type)
            }
        }, 500)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ★ haowallpaper 在线壁纸下载 Dialog
    //    弹出 WebView 加载详情页，用户在页面中点击下载按钮后扫码登录
    //    注入 JS 通过 URL.createObjectURL 拦截大 blob（壁纸文件）
    //    分块 base64 后传回 Android，保存为本地文件并导入壁纸库
    // ═══════════════════════════════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    private fun showHaoWebDownloadDialog(
        detailUrl: String,
        name: String,
        type: String,
    ) {
        // ★ 完全静默模式：不显示任何 Dialog UI，只在后台运行隐藏的 WebView
        //   前端通过现有的进度条轮询显示下载进度（与地址1一致）
        Log.i(TAG, "★ showHaoWebDownloadDialog: detailUrl=$detailUrl, name=$name, type=$type")
        // ★ 保存待重试的下载参数（needLogin 触发时复用）
        haoPendingDetailUrl = detailUrl
        haoPendingName = name
        haoPendingType = type
        // ★ 用户主动点击下载时重置重试计数（避免累积）
        if (haoDownloadRetryCount > 0 && haoPendingDetailUrl != detailUrl) {
            haoDownloadRetryCount = 0
        }
        try {
            haoDownloadDialog?.dismiss()
        } catch (_: Exception) {
        }
        // ★ 移除上一次遗留的嵌入式小窗面板（如有）
        try {
            haoDownloadOverlay?.let { overlay ->
                (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
            }
        } catch (_: Exception) {
        }
        try {
            haoDownloadWebView?.let {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                it.destroy()
            }
        } catch (_: Exception) {
        }
        try {
            haoDownloadOutputStream?.close()
        } catch (_: Exception) {
        }
        haoDownloadOutputStream = null
        haoDownloadFile = null
        haoDownloadWebView = null
        haoDownloadDialog = null
        haoDownloadOverlay = null
        haoDownloadCompleted = false
        haoDownloadMimeType = ""
        haoDownloadTotalSize = 0L
        haoDownloadWallpaperName = name.ifBlank { "wallpaper" }
        haoDownloadWallpaperType = type

        // 重置进度
        com.mineradio.app.wallpaper.OnlineWallpaperApi.currentProgress = 0
        com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载中"

        // ★ 不再清空 cookies：用户要求保存扫码登录态，跨壁纸切换时自动复用登录信息
        //   原代码: clearHaoCookies() — 已移除（会破坏登录态导致每次都要重新扫码）
        // 重新注入已保存的 token（作为 fallback，确保 token cookie 存在）
        injectHaoTokenToCookieManager()
        // 生成随机 UA（每次下载都不同）
        val randomUA = getRandomUserAgent()
        Log.i(TAG, "HaoDownload: using random UA: ${randomUA.take(80)}")

        // ★ 手动操作模式：显示嵌入式玻璃模糊小窗面板（用户在小窗中手动验证、下载、扫码登录）
        //   - 面板尺寸：宽 = 屏幕宽度 * 0.4，高 = 屏幕高度 * 0.7（横屏紧凑，避免过大过拥挤）
        //   - 顶部控制栏（36dp）：URL 显示 TextView（10sp 单行省略）+ 关闭按钮（×）
        //   - WebView 占据剩余空间，VISIBLE，用户可手动操作
        //   - 面板位置：屏幕右侧，垂直居中（Gravity.END | CENTER_VERTICAL）
        //   - 玻璃模糊风格：深蓝色系暗色主题，圆角 12dp，边框 1px rgba(0,245,212,.35)，elevation=8dp
        val dm = resources.displayMetrics
        val density = dm.density
        val panelWidth = (dm.widthPixels * 0.4).toInt()
        val panelHeight = (dm.heightPixels * 0.7).toInt()

        // ★ 面板容器（FrameLayout，玻璃模糊背景 + 圆角 + 边框 + 阴影）
        //   使用 FrameLayout 实现"WebView 在下，关闭按钮浮在右上角"的层叠效果
        val panel =
            FrameLayout(this).apply {
                background =
                    GradientDrawable().apply {
                        orientation = GradientDrawable.Orientation.TL_BR
                        colors =
                            intArrayOf(
                                Color.argb(242, 22, 33, 62), // rgba(22,33,62,.95)
                                Color.argb(242, 26, 42, 72), // rgba(26,42,72,.95)
                            )
                        cornerRadius = 12f * density
                        setStroke((1f * density).toInt(), Color.argb(89, 0, 245, 212)) // 1px rgba(0,245,212,.35)
                    }
                elevation = 8f * density
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }

        // ★ WebView 占据整个面板（不显示 URL 栏，直接显示网页内容）
        val hiddenWv =
            WebView(this).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                visibility = View.VISIBLE
                // 留出顶部状态栏位置，避免内容被关闭按钮遮挡
                setPadding(0, 0, 0, 0)
            }
        panel.addView(hiddenWv)

        // ★ 右上角悬浮圆形关闭按钮（不再显示 URL 栏，按钮浮在 WebView 之上）
        //   尺寸 16dp（缩小 50%），× 字符居中
        val closeButtonSize = (16f * density).toInt()
        val closeButton =
            android.widget.Button(this).apply {
                text = "×"
                setTextColor(Color.argb(255, 255, 130, 130))
                textSize = 10f
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(180, 22, 33, 62))
                        setStroke((1f * density).toInt(), Color.argb(140, 255, 130, 130))
                    }
                // ★ 让 × 字符完全居中（去除 Button 默认 padding 和字体 padding）
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                gravity = Gravity.CENTER
                minWidth = 0
                minHeight = 0
                layoutParams =
                    FrameLayout.LayoutParams(closeButtonSize, closeButtonSize, Gravity.TOP or Gravity.END).apply {
                        marginEnd = (4f * density).toInt()
                        topMargin = (4f * density).toInt()
                    }
                setOnClickListener {
                    Log.i(TAG, "HaoDownload: 关闭按钮被点击，移除嵌入式小窗面板")
                    (panel.parent as? ViewGroup)?.removeView(panel)
                    cleanupHaoBackgroundWebView()
                    haoDownloadOverlay = null
                }
            }
        panel.addView(closeButton)

        // ★ 面板添加到 decorView（屏幕右侧，垂直居中）
        val rootView = window.decorView as? ViewGroup
        panel.layoutParams = FrameLayout.LayoutParams(panelWidth, panelHeight, Gravity.END or Gravity.CENTER_VERTICAL)
        rootView?.addView(panel)

        // ★ 保存面板引用以便后续清理
        haoDownloadOverlay = panel

        hiddenWv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // ★ 使用随机 UA 模拟新设备
            userAgentString = randomUA
            // ★ 启用缓存清理
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        // ★ 清空 WebView 缓存和历史记录
        hiddenWv.clearCache(true)
        hiddenWv.clearHistory()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(hiddenWv, true)

        hiddenWv.webChromeClient =
            object : WebChromeClient() {
                // ★ 转发 JS console.log 到 logcat（调试关键）
                override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                    val msg = cm ?: return super.onConsoleMessage(cm)
                    val level =
                        when (msg.messageLevel()) {
                            android.webkit.ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                            android.webkit.ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                            android.webkit.ConsoleMessage.MessageLevel.DEBUG -> Log.DEBUG
                            android.webkit.ConsoleMessage.MessageLevel.TIP -> Log.INFO
                            else -> Log.INFO
                        }
                    Log.println(
                        level,
                        TAG,
                        "HaoJS: ${msg.message()} (source: ${msg.sourceId()}:${msg.lineNumber()})",
                    )
                    return super.onConsoleMessage(cm)
                }

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: android.webkit.JsResult?,
                ): Boolean {
                    Log.i(TAG, "HaoJS alert: $message")
                    result?.confirm()
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: android.webkit.JsResult?,
                ): Boolean {
                    Log.i(TAG, "HaoJS confirm: $message")
                    result?.confirm()
                    return true
                }
            }
        hiddenWv.webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    super.onPageStarted(view, url, favicon)
                    Log.i(TAG, "HaoDownload: onPageStarted: $url")
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    Log.i(TAG, "HaoDownload: onPageFinished: $url")
                    // ★ 注入已保存的 token 到 localStorage（Nuxt SPA 通过 localStorage.token 读取）
                    val savedToken = loadHaoToken()
                    if (savedToken.isNotBlank()) {
                        view?.evaluateJavascript(
                            "try{localStorage.setItem('token','$savedToken');localStorage.setItem('hao_token','$savedToken')}catch(e){}",
                            null,
                        )
                    }
                    // 注入 JS 拦截壁纸文件下载（手动模式，仅拦截 blob）
                    view?.evaluateJavascript(HAO_DOWNLOAD_JS_INJECTION, null)

                    // ★ 自动保存扫码登录后的 token：读取 document.cookie 和 localStorage.token
                    //   这样用户扫码登录后，下次打开小窗会自动注入 token，无需再次扫码
                    view?.evaluateJavascript(
                        """
                        (function() {
                            try {
                                var lsToken = localStorage.getItem('token') || localStorage.getItem('hao_token') || '';
                                var cookieToken = '';
                                var cookies = document.cookie ? document.cookie.split(';') : [];
                                for (var i = 0; i < cookies.length; i++) {
                                    var c = cookies[i].trim();
                                    if (c.indexOf('token=') === 0) {
                                        cookieToken = c.substring('token='.length);
                                        break;
                                    }
                                }
                                var finalToken = lsToken || cookieToken;
                                if (finalToken && finalToken.length > 10) {
                                    try { AndroidHao.saveToken(finalToken); } catch(e) {}
                                }
                            } catch(e) {
                                console.error('[HaoDownload] save token error: ' + e);
                            }
                        })();
                        """.trimIndent(),
                        null,
                    )
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    Log.e(
                        TAG,
                        "HaoDownload: onReceivedError: ${request?.url} code=${error?.errorCode} desc=${error?.description}",
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    Log.e(
                        TAG,
                        "HaoDownload: onReceivedHttpError: ${request?.url} status=${errorResponse?.statusCode} mime=${errorResponse?.mimeType}",
                    )
                }

                // ★ 完全不拦截 haowallpaper.com 的请求 - 让 WebView 默认处理
                //   之前拦截 getCompleteUrl 破坏了 haowallpaper 的下载流程（JS 接收不到正确响应）
                //   随机 IP 头注入暂时禁用，避免破坏下载功能
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? = super.shouldInterceptRequest(view, request)
            }

        // 添加 JS 桥接接口
        hiddenWv.addJavascriptInterface(HaoDownloadJsBridge(), "AndroidHao")

        // ★ 加载详情页之前注入已保存的 token cookie（让 getCompleteUrl API 能携带 token）
        injectHaoTokenToCookieManager()

        hiddenWv.loadUrl(detailUrl)

        haoDownloadWebView = hiddenWv
    }

    /**
     * ★ haowallpaper 下载 JS 桥接接口
     *    通过 URL.createObjectURL 拦截大 blob（壁纸文件），
     *    分块 base64 后传回 Android，由 Android 端拼装并导入壁纸库。
     */
    private inner class HaoDownloadJsBridge {
        /**
         * ★ JS 主动保存扫码登录后的 token（用户手动扫码登录后调用）
         *   下次打开小窗时自动注入 token，无需再次扫码
         */
        @JavascriptInterface
        fun saveToken(token: String) {
            try {
                if (token.isNotBlank() && token.length > 10) {
                    saveHaoToken(token)
                    Log.i(TAG, "HaoDownload: token saved from JS (length=${token.length})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveToken failed", e)
            }
        }

        @JavascriptInterface
        fun startDownload(
            fileName: String,
            mimeType: String,
            totalSize: Long,
        ) {
            try {
                val safeName = haoDownloadWallpaperName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "wallpaper" }
                val isVideo =
                    mimeType.contains("mp4") ||
                        mimeType.contains("video") ||
                        haoDownloadWallpaperType.equals("video", ignoreCase = true)
                val ext = if (isVideo) "mp4" else "jpg"
                val wallpaperIdLocal = "wp_hao_" + System.currentTimeMillis()
                // ★ 必须使用外部公共存储目录，与 WallpaperEntity.getRealPath() 保持一致
                //   原来用 filesDir（应用私有目录）→ 设为系统/应用壁纸时找不到文件 → 404/闪退
                val docsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
                val targetDir = File(docsDir, "Mineradio/Wallpaper/$wallpaperIdLocal").apply { mkdirs() }
                val targetFile = File(targetDir, "$safeName.$ext")
                haoDownloadFile = targetFile
                haoDownloadMimeType = mimeType
                haoDownloadTotalSize = totalSize
                haoDownloadOutputStream = FileOutputStream(targetFile)
                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentProgress = 0
                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载中"
                Log.i(TAG, "HaoDownload: start $fileName, mime=$mimeType, size=$totalSize, target=${targetFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "HaoDownload startDownload failed", e)
                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载失败"
                runOnUiThread {
                    try {
                        haoDownloadDialog?.dismiss()
                    } catch (_: Exception) {
                    }
                    cleanupHaoBackgroundWebView()
                }
            }
        }

        /**
         * ★ JS 检测到需要登录时调用
         *   haowallpaper.com 真实下载需要 token，未登录时 getCompleteUrl API 返回 401
         *   此处触发登录 Dialog，登录后 token 持久化，自动重试下载
         */
        @JavascriptInterface
        fun needLogin() {
            runOnUiThread {
                try {
                    Log.i(TAG, "HaoDownload: needLogin triggered, switching to login dialog")
                    com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载中"
                    // 触发登录流程，登录完成后自动重试下载
                    val pendingUrl = haoPendingDetailUrl
                    val pendingName = haoPendingName
                    val pendingType = haoPendingType
                    if (pendingUrl.isNullOrEmpty()) {
                        Log.e(TAG, "needLogin: pending URL is null, cannot retry")
                        com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载失败"
                        return@runOnUiThread
                    }
                    showHaoLoginDialog(pendingUrl, pendingName, pendingType)
                } catch (e: Exception) {
                    Log.e(TAG, "needLogin failed", e)
                    com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载失败"
                }
            }
        }

        /**
         * ★ JS 检测到每日下载次数上限时调用
         *   尝试切换到新环境（清空 cookies + 新 UA）后重试下载
         *   如果重试次数过多，通知用户失败
         */
        @JavascriptInterface
        fun dailyLimit(errorMsg: String) {
            runOnUiThread {
                try {
                    Log.w(TAG, "HaoDownload: daily limit reached: $errorMsg")
                    haoDownloadRetryCount++
                    if (haoDownloadRetryCount > 3) {
                        // 重试 3 次仍失败，通知用户
                        com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载失败"
                        try {
                            webView.evaluateJavascript(
                                "try{window.onHaoWebDownloadCancel&&window.onHaoWebDownloadCancel('今日下载次数已达上限，请明日再试或更换账号')}catch(e){}",
                                null,
                            )
                        } catch (_: Exception) {
                        }
                        try {
                            haoDownloadWebView?.let {
                                (it.parent as? android.view.ViewGroup)?.removeView(it)
                                it.destroy()
                            }
                        } catch (_: Exception) {
                        }
                        haoDownloadWebView = null
                        // ★ 失败就静默失败，不弹详细错误提示
                        return@runOnUiThread
                    }
                    Log.i(TAG, "HaoDownload: retrying with new environment (attempt #$haoDownloadRetryCount)")
                    com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载中"
                    // 清空环境并重试
                    val pendingUrl = haoPendingDetailUrl ?: return@runOnUiThread
                    // 延迟 1 秒后重试，避免连续请求
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        showHaoWebDownloadDialog(pendingUrl, haoPendingName, haoPendingType)
                    }, 1000)
                } catch (e: Exception) {
                    Log.e(TAG, "dailyLimit handling failed", e)
                }
            }
        }

        /**
         * ★ JS 检测到其他 API 错误时调用
         *   记录日志并通知用户
         *   ★ 用户要求：只显示"下载中"和"下载失败"
         *     401 表示等待人机验证自动通过 → 保持"下载中"
         *     其他错误（非媒体类型/非真实文件/超时/限流等）→ "下载失败"
         */
        @JavascriptInterface
        fun apiError(errorMsg: String) {
            runOnUiThread {
                try {
                    Log.w(TAG, "HaoDownload: API error: $errorMsg")
                    val isWaitingCaptcha = errorMsg.contains("401") || errorMsg.contains("人机验证") || errorMsg.contains("captcha")
                    com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus =
                        if (isWaitingCaptcha) "下载中" else "下载失败"
                } catch (e: Exception) {
                    Log.e(TAG, "apiError handling failed", e)
                }
            }
        }

        /**
         * ★ JS 主动更新下载状态文案
         *   ★ 用户要求：只接受"下载中"和"下载失败"，其他文案全部忽略
         */
        @JavascriptInterface
        fun updateStatus(status: String) {
            runOnUiThread {
                try {
                    Log.i(TAG, "HaoDownload status: $status")
                    val normalized =
                        when (status) {
                            "下载中", "下载失败" -> status
                            else -> {
                                // 其他文案（如"后台正在下载"/"正在点击人机按钮"等）一律不显示
                                return@runOnUiThread
                            }
                        }
                    com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = normalized
                } catch (e: Exception) {
                    Log.e(TAG, "updateStatus failed", e)
                }
            }
        }

        /**
         * ★ 在 WebView 上指定坐标触发真实触摸事件（ACTION_DOWN + ACTION_UP）
         *   用于点击跨域 iframe 内的"我不是机器人"按钮（reCAPTCHA）
         *   JS 的 .click() 无法穿透跨域 iframe，必须用原生触摸事件
         *
         *   ★ 坐标转换：JS 传入的是 CSS 像素（getBoundingClientRect）
         *     MotionEvent 需要的是 WebView 视图坐标（视图像素）
         *     视图坐标 = CSS 像素 × WebView.scale
         */
        @JavascriptInterface
        fun clickAt(
            x: Float,
            y: Float,
        ) {
            runOnUiThread {
                try {
                    val wv = haoDownloadWebView ?: return@runOnUiThread
                    // ★ 获取 WebView 的缩放比（页面缩放）
                    val scale = wv.scale
                    // ★ CSS 像素 × scale = 视图坐标
                    val viewX = x * scale
                    val viewY = y * scale
                    Log.i(TAG, "HaoDownload clickAt: JS=($x, $y), scale=$scale, view=($viewX, $viewY)")
                    // ★ 静默执行，不显示任何点击位置标记
                    val downTime = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, viewX, viewY, 0)
                    wv.dispatchTouchEvent(down)
                    down.recycle()
                    // 80ms 后 ACTION_UP（模拟真实手指点击）
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            val upTime = SystemClock.uptimeMillis()
                            val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, viewX, viewY, 0)
                            haoDownloadWebView?.dispatchTouchEvent(up)
                            up.recycle()
                            Log.i(TAG, "HaoDownload clickAt UP dispatched")
                        } catch (e: Exception) {
                            Log.e(TAG, "clickAt UP error", e)
                        }
                    }, 80)
                } catch (e: Exception) {
                    Log.e(TAG, "clickAt error", e)
                }
            }
        }

        /**
         * ★ 在 WebView 上方显示红色十字标记（实际点击位置）
         *   用于调试：用户可看到实际 MotionEvent 点击的坐标
         */
        private var clickMarkerView: View? = null

        private fun showClickMarker(
            viewX: Float,
            viewY: Float,
            wv: WebView,
        ) {
            try {
                // 移除旧标记
                clickMarkerView?.let {
                    try {
                        (it.parent as? android.view.ViewGroup)?.removeView(it)
                    } catch (_: Exception) {
                    }
                }
                // 创建红色十字标记
                val marker = android.widget.TextView(this@LandscapeWebActivity)
                marker.text = "✚ 点击($viewX,$viewY)"
                marker.setTextColor(0xFFFF0000.toInt())
                marker.setBackgroundColor(0xAA000000.toInt())
                marker.setPadding(8, 4, 8, 4)
                marker.textSize = 12f
                // 获取 WebView 在屏幕上的位置
                val loc = IntArray(2)
                wv.getLocationOnScreen(loc)
                // 创建 FrameLayout 参数，定位到 WebView 内的 (viewX, viewY) 位置
                val params =
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    )
                // 计算 WebView 在父容器中的位置（相对父容器左上角）
                val wvLocInParent = IntArray(2)
                wv.getLocationInWindow(wvLocInParent)
                // 标记位置 = WebView 在窗口中的位置 + 视图坐标
                params.leftMargin = (wvLocInParent[0] + viewX).toInt() - 60
                params.topMargin = (wvLocInParent[1] + viewY).toInt() - 20
                marker.layoutParams = params
                val rootView = window.decorView as android.view.ViewGroup
                rootView.addView(marker)
                clickMarkerView = marker
                // 2 秒后移除标记
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        clickMarkerView?.let {
                            (it.parent as? android.view.ViewGroup)?.removeView(it)
                            clickMarkerView = null
                        }
                    } catch (_: Exception) {
                    }
                }, 2000)
            } catch (e: Exception) {
                Log.e(TAG, "showClickMarker error", e)
            }
        }

        /**
         * ★ 在 WebView 上指定坐标触发真实点击事件（含 ACTION_DOWN/MOVE/UP 序列）
         *   用于滑动验证码或需要拖动的验证
         *   ★ 坐标转换：JS CSS 像素 × WebView.scale = 视图坐标
         */
        @JavascriptInterface
        fun dragFromTo(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long,
        ) {
            runOnUiThread {
                try {
                    val wv = haoDownloadWebView ?: return@runOnUiThread
                    val scale = wv.scale
                    val vx1 = x1 * scale
                    val vy1 = y1 * scale
                    val vx2 = x2 * scale
                    val vy2 = y2 * scale
                    Log.i(TAG, "HaoDownload dragFromTo: JS=($x1,$y1)->($x2,$y2) scale=$scale view=($vx1,$vy1)->($vx2,$vy2) dur=$durationMs")
                    val downTime = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, vx1, vy1, 0)
                    wv.dispatchTouchEvent(down)
                    down.recycle()
                    val steps = Math.max(5, (durationMs / 16).toInt())
                    val dx = (vx2 - vx1) / steps
                    val dy = (vy2 - vy1) / steps
                    Thread {
                        try {
                            for (i in 1..steps) {
                                val t = SystemClock.uptimeMillis()
                                val mx = vx1 + dx * i
                                val my = vy1 + dy * i
                                val move = MotionEvent.obtain(downTime, t, MotionEvent.ACTION_MOVE, mx, my, 0)
                                Handler(Looper.getMainLooper()).post {
                                    try {
                                        haoDownloadWebView?.dispatchTouchEvent(move)
                                        move.recycle()
                                    } catch (_: Exception) {
                                    }
                                }
                                Thread.sleep(16)
                            }
                            Handler(Looper.getMainLooper()).postDelayed({
                                try {
                                    val upTime = SystemClock.uptimeMillis()
                                    val up =
                                        MotionEvent.obtain(
                                            downTime,
                                            upTime,
                                            MotionEvent.ACTION_UP,
                                            vx2,
                                            vy2,
                                            0,
                                        )
                                    haoDownloadWebView?.dispatchTouchEvent(up)
                                    up.recycle()
                                    Log.i(TAG, "HaoDownload dragFromTo UP dispatched")
                                } catch (e: Exception) {
                                    Log.e(TAG, "dragFromTo UP error", e)
                                }
                            }, durationMs + 30)
                        } catch (_: Exception) {
                        }
                    }.start()
                } catch (e: Exception) {
                    Log.e(TAG, "dragFromTo error", e)
                }
            }
        }

        /**
         * ★ 获取 WebView 在屏幕中的缩放比，供 JS 把 JS 坐标转换为 WebView 坐标
         */
        @JavascriptInterface
        fun getWebViewScale(): Float =
            try {
                haoDownloadWebView?.scale ?: 1f
            } catch (e: Exception) {
                1f
            }

        @JavascriptInterface
        fun appendChunk(base64Data: String) {
            try {
                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                haoDownloadOutputStream?.write(bytes)
                val written = haoDownloadFile?.length() ?: 0
                val progress =
                    if (haoDownloadTotalSize > 0) {
                        (written * 100 / haoDownloadTotalSize).toInt().coerceIn(0, 99)
                    } else {
                        0
                    }
                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentProgress = progress
                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载中"
            } catch (e: Exception) {
                Log.e(TAG, "HaoDownload appendChunk failed", e)
            }
        }

        @JavascriptInterface
        fun finishDownload() {
            try {
                haoDownloadOutputStream?.flush()
                haoDownloadOutputStream?.close()
                haoDownloadOutputStream = null

                val targetFile =
                    haoDownloadFile ?: run {
                        com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载失败"
                        runOnUiThread { haoDownloadDialog?.dismiss() }
                        return
                    }

                if (targetFile.length() == 0L) {
                    targetFile.delete()
                    com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载失败"
                    runOnUiThread { haoDownloadDialog?.dismiss() }
                    return
                }

                // ★ Android 端 magic bytes 二次验证（防止 JS 端漏过的 JSON/HTML 错误响应）
                //   真实视频/图片有固定签名；JSON 以 '{' (0x7B) 开头，HTML 以 '<' (0x3C) 开头
                try {
                    val fis = java.io.FileInputStream(targetFile)
                    val head = ByteArray(16)
                    val read = fis.read(head)
                    fis.close()
                    if (read >= 1) {
                        val b0 = head[0].toInt() and 0xFF
                        // JSON '{' 或 HTML '<'
                        if (b0 == 0x7B || b0 == 0x3C) {
                            val headStr = String(head, 0, read, Charsets.UTF_8).take(200)
                            Log.e(TAG, "HaoDownload: magic bytes check FAILED, content is text: ${headStr.take(100)}")
                            targetFile.delete()
                            com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载失败"
                            runOnUiThread {
                                try {
                                    haoDownloadDialog?.dismiss()
                                } catch (_: Exception) {
                                }
                                cleanupHaoBackgroundWebView()
                            }
                            return
                        }
                        // 校正类型：检测 MP4 / JPEG / PNG / WebM / GIF 签名
                        val isMp4 =
                            read >= 8 &&
                                head[4].toInt() and 0xFF == 0x66 &&
                                head[5].toInt() and 0xFF == 0x74 &&
                                head[6].toInt() and 0xFF == 0x79 &&
                                head[7].toInt() and 0xFF == 0x70 // "ftyp"
                        val isJpeg = read >= 3 && b0 == 0xFF && head[1].toInt() and 0xFF == 0xD8 && head[2].toInt() and 0xFF == 0xFF
                        val isPng =
                            read >= 4 &&
                                b0 == 0x89 &&
                                head[1].toInt() and 0xFF == 0x50 &&
                                head[2].toInt() and 0xFF == 0x4E &&
                                head[3].toInt() and 0xFF == 0x47
                        val isWebm =
                            read >= 4 &&
                                b0 == 0x1A &&
                                head[1].toInt() and 0xFF == 0x45 &&
                                head[2].toInt() and 0xFF == 0xDF &&
                                head[3].toInt() and 0xFF == 0xA3
                        val isGif =
                            read >= 4 &&
                                b0 == 0x47 &&
                                head[1].toInt() and 0xFF == 0x49 &&
                                head[2].toInt() and 0xFF == 0x46 &&
                                head[3].toInt() and 0xFF == 0x38
                        if (!isMp4 && !isJpeg && !isPng && !isWebm && !isGif) {
                            Log.w(TAG, "HaoDownload: unknown magic bytes (b0=0x${b0.toString(16)}), accept as-is")
                        }
                    }
                } catch (ve: Exception) {
                    Log.w(TAG, "HaoDownload: magic bytes verify skipped: ${ve.message}")
                }

                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentProgress = 100
                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载中"

                // 判断类型
                val isVideo =
                    haoDownloadMimeType.contains("mp4") ||
                        haoDownloadMimeType.contains("video") ||
                        haoDownloadWallpaperType.equals("video", ignoreCase = true) ||
                        targetFile.name.endsWith(".mp4")

                // 创建壁纸实体并导入壁纸库
                val wallpaperIdLocal = "wp_hao_" + System.currentTimeMillis()
                // ★ 同步使用外部公共存储目录（与 startDownload 和 getRealPath 一致）
                val docsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
                val finalDir = File(docsDir, "Mineradio/Wallpaper/$wallpaperIdLocal").apply { mkdirs() }
                val finalFile = File(finalDir, targetFile.name)
                targetFile.copyTo(finalFile, overwrite = true)
                targetFile.delete()
                val origDir = targetFile.parentFile
                if (origDir != null && origDir.exists() && origDir.listFiles()?.isEmpty() == true) {
                    origDir.delete()
                }

                val wallpaper =
                    com.mineradio.app.wallpaper.WallpaperEntity(
                        author = "haowallpaper.com",
                        description = "在线下载 (微信扫码)",
                        id = wallpaperIdLocal,
                        name = haoDownloadWallpaperName,
                        path = finalFile.name,
                        versionCode = 1,
                        versionName = "1.0",
                        wallpaperType = if (isVideo) com.mineradio.app.wallpaper.WallpaperEntity.TYPE_VIDEO else com.mineradio.app.wallpaper.WallpaperEntity.TYPE_IMAGE,
                    )
                val wm =
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                wm.addWallpaper(wallpaper)
                // ★ 用户要求：下载完成后不要自动设为当前壁纸，保留用户之前的选择
                //   原: wm.setCurrentWallpaper(wallpaper) — 已移除

                haoDownloadCompleted = true
                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载中"
                Log.i(TAG, "HaoDownload: imported ${finalFile.name}, size=${finalFile.length()}, path=${finalFile.absolutePath}")

                // 通知前端下载成功并关闭 Dialog
                runOnUiThread {
                    webView.evaluateJavascript(
                        "try{window.onHaoWebDownloadSuccess&&window.onHaoWebDownloadSuccess('${haoDownloadWallpaperName.replace(
                            "'",
                            "\\'",
                        )}','${wallpaper.id}')}catch(e){console.warn(e)}",
                        null,
                    )
                    try {
                        haoDownloadDialog?.dismiss()
                    } catch (_: Exception) {
                    }
                    // ★ 清理可能仍在后台运行的 WebView（用户已隐藏的情况）
                    cleanupHaoBackgroundWebView()
                    // ★ 用户要求：不要弹出"自动运行测试结果"对话框，也不要弹出额外的提示
                    //   前端 onHaoWebDownloadSuccess 中会调用 showToast 显示"壁纸已下载"提示
                    //   原代码: showHaoAutoRunConfirmDialog(success = true) — 已移除
                    //   原代码: showHaoDownloadCompleteToast(...) — 已移除（避免重复提示）
                }
            } catch (e: Exception) {
                Log.e(TAG, "HaoDownload finishDownload failed", e)
                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载失败"
                runOnUiThread {
                    try {
                        haoDownloadDialog?.dismiss()
                    } catch (_: Exception) {
                    }
                    cleanupHaoBackgroundWebView()
                }
            }
        }

        @JavascriptInterface
        fun cancelDownload(reason: String) {
            try {
                haoDownloadOutputStream?.close()
            } catch (_: Exception) {
            }
            haoDownloadOutputStream = null
            try {
                haoDownloadFile?.delete()
            } catch (_: Exception) {
            }
            haoDownloadFile = null
            com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载失败"
            Log.w(TAG, "HaoDownload cancelled: $reason")
            runOnUiThread {
                try {
                    haoDownloadDialog?.dismiss()
                } catch (_: Exception) {
                }
                cleanupHaoBackgroundWebView()
            }
        }
    }

    /**
     * 检测登录 cookie，成功后回调到 JS 并关闭 Dialog
     */
    private fun checkLoginCookie(provider: String) {
        val lWv = loginWebView ?: return
        val domains =
            when (provider) {
                "qq" ->
                    listOf(
                        ".qq.com",
                        "https://y.qq.com/",
                        "https://c.y.qq.com/",
                        "https://u.y.qq.com/",
                        "https://i.y.qq.com/",
                        "https://graph.qq.com/",
                        "https://xui.ptlogin2.qq.com/",
                        "https://ssl.ptlogin2.qq.com/",
                    )
                "kg" ->
                    listOf(
                        ".kugou.com",
                        "https://www.kugou.com/",
                        "https://user.kugou.com/",
                        "https://login.kugou.com/",
                        "https://passport.kugou.com/",
                    )
                "netease" -> listOf(".163.com", "https://music.163.com/", "https://music.163.com/#/login")
                "qishui" ->
                    listOf(
                        ".douyin.com",
                        "https://sso.douyin.com/",
                        "https://www.douyin.com/",
                        "https://sso.douyin.com/login_qr/",
                        "https://music.douyin.com/",
                    )
                else -> return
            }
        val all = mutableSetOf<String>()
        // ★ 优先从登录 WebView 当前实时 URL 获取 cookie（一比一移植 mineradio-android getCookie(browserCurrentUrl)）
        //   确保能拿到扫码登录跳转后的完整 cookie（含 sessionid/sid_guard 等登录票据）
        val liveUrl =
            try {
                lWv.url
            } catch (_: Exception) {
                null
            }
        if (!liveUrl.isNullOrBlank()) {
            val live = CookieManager.getInstance().getCookie(liveUrl)
            if (!live.isNullOrBlank()) {
                live
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { all.add(it) }
            }
        }
        for (domain in domains) {
            val c = CookieManager.getInstance().getCookie(domain)
            if (!c.isNullOrBlank()) {
                c
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { all.add(it) }
            }
        }
        val cookies = all.joinToString("; ")
        Log.d(TAG, "checkLoginCookie [$provider] #$loginDetectionCount cookies=${cookies.take(200)}")

        val loggedIn =
            when (provider) {
                "qq" -> {
                    // ★ 一比一移植 mineradio-android：QQ 登录态必须有非空 qm_keyst（匿名访客只有 uin=0 不可靠）
                    val qqKeyst = extractCookieValue(cookies, "qm_keyst")
                    qqKeyst != null && qqKeyst.length >= 10 && qqKeyst != "\"\""
                }
                "kg" -> cookies.contains("KugooID")
                "netease" -> cookies.contains("MUSIC_U") || cookies.contains("__csrf")
                // ★ 汽水音乐(抖音)登录态：仅依赖 sessionid 基线变化检测（一比一移植 mineradio-android）
                //   music.douyin.com 匿名访问也会设置 sessionid/sid_guard/uid_tt, 无法靠字段名区分
                //   若用 sid_guard>50 立即判定，白屏/未渲染页的匿名 cookie 会误判为"已登录"
                //   因此：页面加载初期记录初始 sessionid 作为基线，之后 sessionid 值变化才认定登录成功
                "qishui" -> {
                    var isQishui = false
                    val currentSid = extractCookieValue(cookies, "sessionid")
                    val curSidValid = currentSid != null && currentSid.length >= 20
                    if (curSidValid) {
                        val baseline = qishuiBaselineSessionId
                        if (baseline == null) {
                            qishuiBaselineSessionId = currentSid
                        } else {
                            isQishui = currentSid != baseline
                        }
                    }
                    // ★ 增强：sessionid_ss 仅在真登录后由抖音服务端下发（匿名访问不设置），
                    //   作为基线锁死场景下的强登录指标，配合手动按钮解决"已登录但检测不到"
                    if (!isQishui) {
                        val sidSs = extractCookieValue(cookies, "sessionid_ss")
                        if (sidSs != null && sidSs.length >= 20) {
                            // 有 sessionid 且拿到 sessionid_ss，判定为真实登录态
                            if (curSidValid) isQishui = true
                        }
                    }
                    isQishui
                }
                else -> false
            }

        if (loggedIn) {
            loginCompleted = true
            stopLoginPoll()
            val callback =
                when (provider) {
                    // BRIDGE_JS 已在主 WebView 定义这些回调（window._onQQLoginCookie 等）
                    "qq" -> "_onQQLoginCookie"
                    "kg" -> "_onKugouLoginCookie"
                    "netease" -> "_onNeteaseLoginCookie"
                    "qishui" -> "_onQishuiLoginCookie"
                    else -> return
                }
            val quoted = org.json.JSONObject.quote(cookies)
            webView.evaluateJavascript(
                "if(window.$callback)window.$callback($quoted)",
                null,
            )
            Toast.makeText(this, "登录成功，正在同步…", Toast.LENGTH_SHORT).show()
            loginDialog?.dismiss()
        }
    }

    /**
     * ★ 手动授权：绕过登录态基线检测，直接强制提取当前 Cookie 并回调到 JS
     *   用于用户已登录但自动检测未触发的情况（点击官网左上角"授权登录"按钮）
     *   后端会校验 Cookie 真实性，若确实已登录则同步成功，避免 sessionid 基线误判
     */
    private fun forceManualAuthCheck(provider: String) {
        val lWv = loginWebView ?: return
        val domains =
            when (provider) {
                "qq" ->
                    listOf(
                        ".qq.com",
                        "https://y.qq.com/",
                        "https://c.y.qq.com/",
                        "https://u.y.qq.com/",
                        "https://i.y.qq.com/",
                        "https://graph.qq.com/",
                        "https://xui.ptlogin2.qq.com/",
                        "https://ssl.ptlogin2.qq.com/",
                    )
                "kg" ->
                    listOf(
                        ".kugou.com",
                        "https://www.kugou.com/",
                        "https://user.kugou.com/",
                        "https://login.kugou.com/",
                        "https://passport.kugou.com/",
                    )
                "netease" -> listOf(".163.com", "https://music.163.com/", "https://music.163.com/#/login")
                "qishui" ->
                    listOf(
                        ".douyin.com",
                        "https://sso.douyin.com/",
                        "https://www.douyin.com/",
                        "https://sso.douyin.com/login_qr/",
                        "https://music.douyin.com/",
                    )
                else -> return
            }
        val all = LinkedHashSet<String>()
        val liveUrl =
            try {
                lWv.url
            } catch (_: Exception) {
                null
            }
        if (!liveUrl.isNullOrBlank()) {
            val live = CookieManager.getInstance().getCookie(liveUrl)
            if (!live.isNullOrBlank()) {
                live
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { all.add(it) }
            }
        }
        for (domain in domains) {
            val c = CookieManager.getInstance().getCookie(domain)
            if (!c.isNullOrBlank()) {
                c
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { all.add(it) }
            }
        }
        val cookies = all.joinToString("; ")
        if (cookies.isBlank()) {
            webView.evaluateJavascript(
                "try{window.showToast&&window.showToast('未获取到登录 Cookie，请先登录再点授权')}catch(e){}",
                null,
            )
            return
        }
        val callback =
            when (provider) {
                "qq" -> "_onQQLoginCookie"
                "kg" -> "_onKugouLoginCookie"
                "netease" -> "_onNeteaseLoginCookie"
                "qishui" -> "_onQishuiLoginCookie"
                else -> return
            }
        Log.d(TAG, "forceManualAuthCheck [$provider] cookies=${cookies.take(200)}")
        val quoted = org.json.JSONObject.quote(cookies)
        webView.evaluateJavascript("if(window.$callback)window.$callback($quoted)", null)
        Toast.makeText(this, "已提交登录态，正在同步…", Toast.LENGTH_SHORT).show()
    }

    // 从 Cookie 字符串中提取指定字段值(如 sessionid)。一比一移植 mineradio-android MainActivity.extractCookieValue
    private fun extractCookieValue(
        cookie: String?,
        name: String,
    ): String? {
        if (cookie == null || name == null) return null
        val p =
            java.util.regex.Pattern
                .compile(
                    "(?:^|;\\s*)" +
                        java.util.regex.Pattern
                            .quote(name) + "=([^;]+)",
                )
        val m = p.matcher(cookie)
        return if (m.find()) m.group(1).trim() else null
    }

    // ════════════════════════════════════════════════════════════
    //  ★ 汽水音乐安全签名 WebView（加载 bdms.js 生成 a_bogus 签名）
    //    由 mineradio-android 移植。签名 WebView 加载 qishui-security/security_host.html，
    //    其中 window.__qishuiRequest 会自动注入 a_bogus，返回带签名后的响应。
    // ════════════════════════════════════════════════════════════
    private fun initQishuiSignerOuter() {
        try {
            val signerWv = WebView(this)
            signerWv.setVisibility(View.GONE)
            val sws = signerWv.settings
            sws.javaScriptEnabled = true
            sws.domStorageEnabled = true
            // 绕过 CORS：file:// 页面发起的跨域 XHR 不受同源策略限制
            @Suppress("DEPRECATION")
            sws.allowUniversalAccessFromFileURLs = true
            sws.allowFileAccess = true
            sws.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            sws.cacheMode = WebSettings.LOAD_NO_CACHE
            // 桌面版 UA，避免汽水 API 对移动端 UA 施加更严格的频率限制
            sws.userAgentString = PC_USER_AGENT

            // 注入回调接口：签名请求完成后由 JS 调用，将结果传回主 WebView
            signerWv.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onResult(
                        callbackId: String,
                        resultJson: String,
                    ) {
                        val cid = callbackId
                        val rj = resultJson
                        Log.i(TAG, "Qishui signer onResult: cid=$cid resultLen=${rj?.length ?: 0}")
                        signerMainHandler.post {
                            val js =
                                if ("mfa" == cid) {
                                    "try{if(typeof window.__qishuiMfaHandler==='function'){window.__qishuiMfaHandler($rj)}else{console.log('[QishuiMFA] __qishuiMfaHandler not defined')}}catch(e){console.log('[QishuiMFA] handler ERROR: '+e)}"
                                } else {
                                    "try{window.__qishuiSignCallback(${jsonStr(
                                        cid,
                                    )},$rj)}catch(e){console.log('[QishuiSignCB] evaluateJavascript ERROR: '+e)}"
                                }
                            webView.evaluateJavascript(js, null)
                        }
                    }

                    @JavascriptInterface
                    fun onError(
                        callbackId: String,
                        error: String,
                    ) {
                        val cid = callbackId
                        val err = error
                        Log.e(TAG, "Qishui signer onError: cid=$cid err=$err")
                        signerMainHandler.post {
                            val js =
                                if ("mfa" == cid) {
                                    "try{if(typeof window.__qishuiMfaHandler==='function'){window.__qishuiMfaHandler({status:false,message:${jsonStr(
                                        err,
                                    )}})}else{console.log('[QishuiMFA] __qishuiMfaHandler not defined on error')}}catch(e){console.log('[QishuiMFA] handler ERROR: '+e)}"
                                } else {
                                    "window.__qishuiSignError(${jsonStr(cid)},${jsonStr(err)})"
                                }
                            webView.evaluateJavascript(js, null)
                        }
                    }
                },
                "AndroidSignerCallback",
            )

            signerWv.webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        url: String?,
                    ): Boolean {
                        Log.i(TAG, "Qishui signer shouldOverrideUrlLoading: $url")
                        // 阻止导航离开 security_host.html，防止验证组件重定向后丢失 JS 上下文
                        if (url != null && !url.contains("security_host.html")) {
                            Log.i(TAG, "Qishui signer BLOCKED navigation to: $url")
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        url: String?,
                    ) {
                        Log.i(TAG, "Qishui signer page loaded: $url")
                        signerReadyProbe = 0
                        checkSignerReady()
                    }
                }

            signerWv.loadUrl("file:///android_asset/qishui-security/security_host.html")
            signerWebView = signerWv
            Log.i(TAG, "Qishui signer WebView initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init qishui signer", e)
        }
    }

    // 轮询检查 bdms.js 是否初始化完成（window.__qishuiRequest 就绪）
    private fun checkSignerReady() {
        val signerWv = signerWebView ?: return
        signerReadyProbe++
        signerWv.evaluateJavascript(
            "typeof window.__qishuiRequest === 'function' ? 'ready' : 'pending'",
        ) { result ->
            if ("\"ready\"" == result) {
                signerReady = true
                Log.i(TAG, "Qishui signer ready after $signerReadyProbe probes")
                webView.evaluateJavascript(
                    "window.__qishuiSignerReady = true; if(window.onQishuiSignerReady){window.onQishuiSignerReady()}",
                    null,
                )
            } else if (signerReadyProbe < 30) {
                // 最多探测 30 次（约 15 秒）
                signerMainHandler.postDelayed(
                    { checkSignerReady() },
                    500,
                )
            } else {
                Log.w(TAG, "Qishui signer not ready after $signerReadyProbe probes, giving up")
            }
        }
    }

    // 在签名 WebView 中执行 __qishuiRequest，bdms.js 自动注入 a_bogus
    private fun dispatchSignedRequest(
        payloadJson: String,
        callbackId: String,
    ) {
        val signerWv = signerWebView
        if (!signerReady || signerWv == null) {
            // 签名未就绪，直接返回错误
            webView.evaluateJavascript(
                "window.__qishuiSignError(${jsonStr(callbackId)},${jsonStr("签名组件未就绪,请稍后重试")})",
                null,
            )
            return
        }
        try {
            val js =
                "window.__qishuiRequest($payloadJson)" +
                    ".then(function(r){window.AndroidSignerCallback.onResult(${jsonStr(callbackId)},JSON.stringify(r))})" +
                    ".catch(function(e){window.AndroidSignerCallback.onError(${jsonStr(callbackId)},String(e&&e.message||e))})"
            signerWv.evaluateJavascript(js, null)
        } catch (e: Exception) {
            Log.e(TAG, "dispatchSignedRequest failed", e)
            webView.evaluateJavascript(
                "window.__qishuiSignError(${jsonStr(callbackId)},${jsonStr("签名调度异常: ${e.message}")})",
                null,
            )
        }
    }

    // 显示二次验证面板（2046）：在主 WebView 中创建覆盖层处理 MFA 验证
    private fun showSignerMfaPanel(decisionJson: String) {
        try {
            val b64D = android.util.Base64.encodeToString(decisionJson.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val js =
                "try{" +
                    "window.__qishuiMfaDecisionData=JSON.parse(atob('$b64D'));" +
                    "var ov=document.createElement('div');" +
                    "ov.id='qishui-mfa-overlay';" +
                    "ov.style.cssText='position:fixed;top:0;left:0;width:100%;height:100%;z-index:999999;background:#fff;overflow:auto;-webkit-overflow-scrolling:touch';" +
                    "var f=document.createElement('iframe');" +
                    "f.id='qishui-mfa-frame';" +
                    "f.style.cssText='width:100%;height:100%;border:none';" +
                    "ov.appendChild(f);" +
                    "document.body.appendChild(ov);" +
                    "var b=document.createElement('button');" +
                    "b.textContent='取消验证';" +
                    "b.id='qishui-mfa-close-btn';" +
                    "b.style.cssText='position:fixed;right:14px;bottom:14px;z-index:1000000;padding:8px 14px;border:1px solid #ddd;border-radius:8px;background:#fff;cursor:pointer;font-size:14px';" +
                    "b.onclick=function(){console.log('[QishuiMFA] cancel');" +
                    "  if(typeof window.__qishuiMfaHandler==='function')window.__qishuiMfaHandler({status:false,message:'用户取消二次验证'});" +
                    "  var x=document.getElementById('qishui-mfa-overlay');if(x&&x.parentNode)x.parentNode.removeChild(x);" +
                    "  var y=document.getElementById('qishui-mfa-close-btn');if(y&&y.parentNode)y.parentNode.removeChild(y);" +
                    "};" +
                    "document.body.appendChild(b);" +
                    "var __qishuiMfaMsgHandler=function(e){" +
                    "  if(e.data&&e.data.type==='verify-result'){" +
                    "    console.log('[QishuiMFA] verify-result received');" +
                    "    window.removeEventListener('message',__qishuiMfaMsgHandler);" +
                    "    if(typeof window.__qishuiMfaHandler==='function')window.__qishuiMfaHandler(e.data.result);" +
                    "    var x=document.getElementById('qishui-mfa-overlay');if(x&&x.parentNode)x.parentNode.removeChild(x);" +
                    "    var y=document.getElementById('qishui-mfa-close-btn');if(y&&y.parentNode)y.parentNode.removeChild(y);" +
                    "  }" +
                    "};" +
                    "window.addEventListener('message',__qishuiMfaMsgHandler);" +
                    "f.onload=function(){" +
                    "  console.log('[QishuiMFA] iframe loaded, sending start-verify');" +
                    "  var d=window.__qishuiMfaDecisionData;" +
                    "  if(f.contentWindow)f.contentWindow.postMessage({type:'start-verify',decision:d,options:{}},'*');" +
                    "};" +
                    "f.src='file:///android_asset/qishui-security/security_host.html';" +
                    "console.log('[QishuiMFA] overlay created in main WebView');" +
                    "}catch(e){console.log('[QishuiMFA] overlay error:'+e)}"
            webView.evaluateJavascript(js, null)
            Log.i(TAG, "Qishui MFA panel shown in main WebView overlay (security_host.html)")
        } catch (e: Exception) {
            Log.e(TAG, "showSignerMfaPanel failed", e)
        }
    }

    // 隐藏二次验证面板：移除主 WebView 中的覆盖层
    private fun hideSignerMfaPanel() {
        try {
            val js =
                "try{" +
                    "var ov=document.getElementById('qishui-mfa-overlay');if(ov)ov.parentNode.removeChild(ov);" +
                    "var cb=document.getElementById('qishui-mfa-close-btn');if(cb)cb.parentNode.removeChild(cb);" +
                    "console.log('[QishuiMFA] overlay removed');" +
                    "}catch(e){console.log('[QishuiMFA] remove overlay error:'+e)}"
            webView.evaluateJavascript(js, null)
            Log.i(TAG, "Qishui MFA panel hidden")
        } catch (e: Exception) {
            Log.e(TAG, "hideSignerMfaPanel failed", e)
        }
    }

    // JSON 字符串转义（用于 JS 拼接）
    private fun jsonStr(s: String): String {
        if (s == null) return "null"
        val sb = StringBuilder()
        sb.append('"')
        var i = 0
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u0008' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
            }
            i++
        }
        sb.append('"')
        return sb.toString()
    }

    // ★ 处理系统文件选择器和文件夹选择器的回调
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            FILE_CHOOSER_REQUEST -> {
                // <input type="file"> 选择回调
                val cb = filePathCallback
                filePathCallback = null
                if (cb == null) return
                val results: Array<Uri>? =
                    if (resultCode == RESULT_OK && data != null) {
                        val uri = data.data
                        // 多选
                        val clipData = data.clipData
                        if (clipData != null && clipData.itemCount > 0) {
                            Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                        } else if (uri != null) {
                            arrayOf(uri)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                // ★ 先保存 URI，再回调 onReceiveValue。
                //   因为 onReceiveValue 会触发 WebView 异步派发 <input type="file"> 的 onchange 事件，
                //   若在回调之后才赋值，前端立即调用 getWallpaperPreviewData 时可能读到 null，导致预览封面取不到。
                lastPickedFileUri = results?.firstOrNull()
                Log.d(TAG, "FILE_CHOOSER_REQUEST saved uri=$lastPickedFileUri")
                cb.onReceiveValue(results)
            }
            FOLDER_PICK_REQUEST -> {
                // 多图片背景文件夹选择回调
                val callbackId = folderPickCallbackId
                folderPickCallbackId = null
                if (callbackId == null) return
                if (resultCode == RESULT_OK && data?.data != null) {
                    val treeUri = data.data!!
                    // 获取持久化权限，以便后续读取文件夹内容
                    try {
                        contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (_: Exception) {
                    }
                    val uriStr = treeUri.toString().replace("'", "\\'")
                    webView.post {
                        webView.evaluateJavascript(
                            "if(window._onFolderPicked)window._onFolderPicked('$callbackId','$uriStr')",
                            null,
                        )
                    }
                } else {
                    webView.post {
                        webView.evaluateJavascript(
                            "if(window._onFolderPicked)window._onFolderPicked('$callbackId','')",
                            null,
                        )
                    }
                }
            }
            // ★ 扩展插件 zip 安装回调
            PLUGIN_ZIP_REQUEST -> {
                val callbackId = pluginZipCallbackId
                pluginZipCallbackId = null
                if (callbackId == null) return
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    // 在后台线程执行解压（避免阻塞 UI）
                    Thread {
                        val pm = server?.pluginManager
                        val result = if (pm != null) pm.install(uri) else null
                        val json =
                            JSONObject().apply {
                                put("ok", result?.success ?: false)
                                put("pluginId", result?.pluginId ?: "")
                                put("name", result?.name ?: "")
                                put("message", result?.message ?: "服务器未就绪")
                            }
                        webView.post {
                            webView.evaluateJavascript(
                                "if(window._onPluginZipPicked)window._onPluginZipPicked('$callbackId',$json)",
                                null,
                            )
                        }
                    }.start()
                } else {
                    webView.post {
                        webView.evaluateJavascript(
                            "if(window._onPluginZipPicked)window._onPluginZipPicked('$callbackId',{ok:false,message:'已取消选择'})",
                            null,
                        )
                    }
                }
            }
            // ★ 壁纸图片导入回调
            WALLPAPER_IMAGE_REQUEST -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    Thread {
                        try {
                            val entity =
                                com.mineradio.app.wallpaper.WallpaperManager
                                    .get(this)
                                    .importFromFile(this, uri, com.mineradio.app.wallpaper.WallpaperEntity.TYPE_IMAGE)
                            val name = entity?.name ?: "未命名"
                            webView.post {
                                webView.evaluateJavascript(
                                    "if(window.showToast)window.showToast('已导入图片壁纸: " + name.replace("'", "\\'") + "');",
                                    null,
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("Wallpaper", "import image failed", e)
                        }
                    }.start()
                }
            }
            // ★ 壁纸视频导入回调
            WALLPAPER_VIDEO_REQUEST -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    Thread {
                        try {
                            val entity =
                                com.mineradio.app.wallpaper.WallpaperManager
                                    .get(this)
                                    .importFromFile(this, uri, com.mineradio.app.wallpaper.WallpaperEntity.TYPE_VIDEO)
                            val name = entity?.name ?: "未命名"
                            webView.post {
                                webView.evaluateJavascript(
                                    "if(window.showToast)window.showToast('已导入视频壁纸: " + name.replace("'", "\\'") + "');",
                                    null,
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("Wallpaper", "import video failed", e)
                        }
                    }.start()
                }
            }
            // ★ 壁纸统一导入回调（自动识别类型，支持多选）
            WALLPAPER_UNIFIED_REQUEST -> {
                if (resultCode == RESULT_OK && data != null) {
                    // ★★ 支持多选：优先使用 ClipData，否则用 data.data
                    val uris = ArrayList<android.net.Uri>()
                    val clipData = data.clipData
                    if (clipData != null && clipData.itemCount > 0) {
                        for (i in 0 until clipData.itemCount) {
                            uris.add(clipData.getItemAt(i).uri)
                        }
                    } else if (data.data != null) {
                        uris.add(data.data!!)
                    }

                    if (uris.isEmpty()) {
                        // 没有选择文件
                    } else if (uris.size == 1) {
                        // 单选模式
                        val uri = uris[0]
                        Thread {
                            try {
                                val entity =
                                    com.mineradio.app.wallpaper.WallpaperManager
                                        .get(this)
                                        .importFromFile(this, uri)
                                val name = entity?.name ?: "未命名"
                                val typeLabel =
                                    when (entity?.wallpaperType) {
                                        com.mineradio.app.wallpaper.WallpaperEntity.TYPE_VIDEO -> "视频"
                                        com.mineradio.app.wallpaper.WallpaperEntity.TYPE_HTML -> "HTML"
                                        com.mineradio.app.wallpaper.WallpaperEntity.TYPE_IMAGE -> "图片"
                                        com.mineradio.app.wallpaper.WallpaperEntity.TYPE_MPKG_WEBGL -> "MPKG 场景"
                                        else -> ""
                                    }
                                webView.post {
                                    webView.evaluateJavascript(
                                        "if(window.showToast)window.showToast('已导入${typeLabel}壁纸: " + name.replace("'", "\\'") + "');",
                                        null,
                                    )
                                    // ★ 通知前端刷新壁纸库数据
                                    webView.evaluateJavascript(
                                        "if(window.refreshWallpaperCurrentInfo)window.refreshWallpaperCurrentInfo();",
                                        null,
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e("Wallpaper", "unified import failed", e)
                            }
                        }.start()
                    } else {
                        // ★★ 多选模式：后台逐个导入
                        Thread {
                            var successCount = 0
                            var failCount = 0
                            for (uri in uris) {
                                try {
                                    com.mineradio.app.wallpaper.WallpaperManager
                                        .get(this)
                                        .importFromFile(this, uri)
                                    successCount++
                                } catch (e: Exception) {
                                    Log.e("Wallpaper", "multi import item failed", e)
                                    failCount++
                                }
                            }
                            val msg = "已导入 $successCount 张壁纸" + (if (failCount > 0) "，失败 $failCount 张" else "")
                            webView.post {
                                webView.evaluateJavascript(
                                    "if(window.showToast)window.showToast('" + msg.replace("'", "\\'") + "');",
                                    null,
                                )
                                webView.evaluateJavascript(
                                    "if(window.refreshWallpaperCurrentInfo)window.refreshWallpaperCurrentInfo();",
                                    null,
                                )
                            }
                        }.start()
                    }
                }
            }
            // ★ 用户上传壁纸：.mpkg 文件选择回调
            WALLPAPER_UPLOAD_MPKG_REQUEST -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    Log.i(TAG, "★ WALLPAPER_UPLOAD_MPKG_REQUEST: uri=$uri")
                    // ★ GET_CONTENT 返回的 URI 不支持持久化权限，使用临时权限即可
                    // （上传在当前进程生命周期内完成，临时权限足够）
                    // 从 content URI 查询文件名和大小
                    var name = "upload.mpkg"
                    var size = 0L
                    try {
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("Wallpaper", "query mpkg file info failed: ${e.message}")
                    }
                    // 校验 .mpkg 扩展名
                    if (!name.lowercase().endsWith(".mpkg")) {
                        webView.post {
                            webView.evaluateJavascript(
                                "if(window.showToast)window.showToast('请选择 .mpkg 格式文件');",
                                null,
                            )
                        }
                        return
                    }
                    // 暂存 URI 供后续上传使用
                    pendingUploadMpkgUri = uri
                    // 回调 JS：通知文件已选择
                    val safeName = name.replace("\\", "\\\\").replace("'", "\\'")
                    val uriStr = uri.toString().replace("\\", "\\\\").replace("'", "\\'")
                    webView.post {
                        webView.evaluateJavascript(
                            "if(window.onWallpaperMpkgSelected)window.onWallpaperMpkgSelected('$uriStr','$safeName',$size);",
                            null,
                        )
                    }
                    // ★ 自动提取封面：后台线程从 .mpkg 提取预览图/视频帧，成功后回调 JS 显示封面预览
                    Thread {
                        val bridge = KAppBridge()
                        val autoCoverUri = bridge.extractCoverFromMpkg(uri)
                        if (autoCoverUri != null) {
                            pendingUploadCoverUri = autoCoverUri
                            val coverUriStr = autoCoverUri.toString().replace("\\", "\\\\").replace("'", "\\'")
                            Log.i(TAG, "★ auto extract cover success: $coverUriStr")
                            // ★ 将封面图片转为 base64 数据 URL，避免 WebView 禁止 file:// URI 加载
                            var base64DataUrl = ""
                            try {
                                val coverFile = java.io.File(autoCoverUri.path ?: "")
                                if (coverFile.exists()) {
                                    val bytes = coverFile.readBytes()
                                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                    base64DataUrl = "data:image/jpeg;base64,$base64"
                                    Log.i(TAG, "★ auto extract cover base64 size=${bytes.size}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "★ auto extract cover base64 failed", e)
                            }
                            webView.post {
                                webView.evaluateJavascript(
                                    "if(window.onWallpaperCoverSelected)window.onWallpaperCoverSelected('$coverUriStr','auto_cover.jpg','$base64DataUrl');",
                                    null,
                                )
                            }
                        } else {
                            Log.w(TAG, "★ auto extract cover failed: no image/video entry in mpkg")
                        }
                    }.start()
                }
            }
            // ★ 用户上传壁纸：封面图选择回调
            WALLPAPER_UPLOAD_COVER_REQUEST -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    Log.i(TAG, "★ WALLPAPER_UPLOAD_COVER_REQUEST: uri=$uri")
                    // ★ GET_CONTENT 返回的 URI 不支持持久化权限，使用临时权限即可
                    var name = "cover.jpg"
                    try {
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("Wallpaper", "query cover file info failed: ${e.message}")
                    }
                    pendingUploadCoverUri = uri
                    val safeName = name.replace("\\", "\\\\").replace("'", "\\'")
                    val uriStr = uri.toString().replace("\\", "\\\\").replace("'", "\\'")
                    // ★ 将封面图片转为 base64 数据 URL，避免 WebView 禁止 content:// URI 加载
                    var base64DataUrl = ""
                    try {
                        val inputStream = contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            val bytes = inputStream.readBytes()
                            inputStream.close()
                            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            base64DataUrl = "data:image/jpeg;base64,$base64"
                            Log.i(TAG, "★ manual cover base64 size=${bytes.size}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "★ manual cover base64 failed", e)
                    }
                    webView.post {
                        webView.evaluateJavascript(
                            "if(window.onWallpaperCoverSelected)window.onWallpaperCoverSelected('$uriStr','$safeName','$base64DataUrl');",
                            null,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // ★ 清理视频壁纸播放器（避免 MediaPlayer 泄漏）
        stopMpkgVideoPlayback()
        // ★ 标记横屏已销毁 + 保存状态：最后显示的不是横屏
        isAlive = false
        try {
            getSharedPreferences("mineradio_activity_state", MODE_PRIVATE)
                .edit()
                .putBoolean("last_was_landscape", false)
                .apply()
        } catch (_: Exception) {
        }
        // ★ 销毁 MPKG 背景渲染层
        try {
            mpkgBgActive = false
            mpkgBgPendingPath = null
            mpkgBgGLSurfaceView?.queueEvent {
                try {
                    if (mpkgBgContextId >= 0) {
                        if (mpkgBgSceneInitialized) {
                            try {
                                mpkgBgSceneLib?.shutdownScene(mpkgBgContextId)
                            } catch (_: Throwable) {
                            }
                            mpkgBgSceneInitialized = false
                        }
                        try {
                            mpkgBgSceneLib?.destroyContext(mpkgBgContextId)
                        } catch (_: Throwable) {
                        }
                        mpkgBgContextId = -1
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        // ★★ 停止 logcat GPU 错误监控
        stopGpuErrorLogcatMonitor()
        // ★ 停止登录轮询定时器
        stopLoginPoll()
        releaseWakeLock()
        // ★ 销毁桌面歌词浮窗（Java TextView 版，无 destroy 接口，hide 即可）
        try {
            desktopLyricsManager?.hide()
            desktopLyricsManager = null
        } catch (_: Exception) {
        }
        // ★ 停止手势识别，释放摄像头和模型资源
        try {
            stopNativeHandGesture()
        } catch (_: Exception) {
        }
        try {
            gestureCameraExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        // ★ 停止音域回响原生音频采集
        try {
            stopSonicAudioCapture()
        } catch (_: Exception) {
        }
        sonicAudioCapture = null
        // ★ 停止粒子模式频谱注入
        try {
            stopParticleSpectrumInjection()
        } catch (_: Exception) {
        }
        // ★ 停止萌宠音乐状态推送
        try {
            stopPetMusicStatePush()
        } catch (_: Exception) {
        }
        // ★ 停止前台音乐播放 Service，移除保活通知
        try {
            val intent = Intent(this, MusicPlaybackService::class.java)
            intent.action = "STOP"
            stopService(intent)
        } catch (_: Exception) {
        }
        // 清除通知栏回调，防止持有已销毁的 WebView
        try {
            MediaNotificationState.onPlayPause = null
            MediaNotificationState.onSkipPrevious = null
            MediaNotificationState.onSkipNext = null
            MediaNotificationState.onSeek = null
        } catch (_: Exception) {
        }
        try {
            webView.removeJavascriptInterface("KeepApp")
            webView.destroy()
        } catch (_: Exception) {
        }
        server?.stop()
        // ★ 性能优化：销毁时记录内存状态，便于追踪内存泄漏
        com.mineradio.app.utils.PerformanceMonitor
            .logMemory("LandscapeWeb onDestroy")
        super.onDestroy()
    }

    // ═══════════════════════════════════════════
    //  权限自动请求
    // ═══════════════════════════════════════════

    /**
     * 启动时自动请求所有运行时权限：
     * - 通知权限（Android 13+）
     * - 相机权限
     * - 录音权限
     * - 存储权限（Android 11+ 的 MANAGE_EXTERNAL_STORAGE 需跳系统设置）
     */

    /**
     * ★ GPU 兼容性检测：识别无法正确创建 AHardwareBuffer SharedImage 的设备
     *
     * 这些设备在运行一段时间后会大量报错（来自 chromium 原生日志，不会走 JS console）：
     *   "viz::SharedImageFormat RGBA_8888 can not be used to create a GL texture from AHardwareBuffer"
     *   "SharedImageStub: Unable to create shared image"
     *   "Could not find SharedImageBackingFactory with params: ... format: RGBA_8888 ..."
     *
     * 日志特征：debug_label: CanvasResourceRaster_Pid:0, size: 256x256
     *   → 这是 WebView canvas 的 backing texture 创建失败
     *   → 一旦失败，画面永久白屏，且不会触发 webglcontextlost
     *
     * 已知问题设备：
     *   - OnePlus PKX110 (Adreno 830, Android 16/SDK 36, ColorOS)
     *   - OPPO/OnePlus/Realme/Vivo 多款 ColorOS ROM（不限于 SDK ≤ 34）
     *   - 任何 Adreno 6xx/7xx/8xx + ColorOS 组合
     *
     * 检测策略：
     * 1) 优先检查"运行时白屏标记"（JS 探测到白屏后通过 KeepApp.markGpuProblematic() 持久化）
     * 2) SharedPreferences 缓存（v2 版本，避免老缓存导致漏判）
     * 3) 已知问题厂商 + Adreno GPU 组合（不再限制 SDK 版本，Adreno 830+SDK36 也有此 bug）
     * 4) Runtime.maxMemory() < 256MB 的设备
     * 5) GL_RENDERER 字符串匹配 Adreno 6xx/7xx/8xx
     * 6) 运行时探测：尝试 eglCreateImageKHR + AHardwareBuffer
     */
    private fun isProblematicGpuDevice(): Boolean {
        try {
            val prefs = getSharedPreferences("gpu_compat", MODE_PRIVATE)

            // ★ 0) 运行时白屏标记（最高优先级）：JS 探测到白屏后调用 markGpuProblematic() 持久化
            //    一旦设置，永久使用软件渲染层，避免再次白屏
            if (prefs.getBoolean("whitescreen_detected", false)) {
                prefs.edit().putInt("gpu_problematic_v2", 1).apply()
                return true
            }

            // 1) SharedPreferences 缓存（v2 版本，避免老缓存 v1 导致漏判）
            val cached = prefs.getInt("gpu_problematic_v2", -1)
            if (cached != -1) return cached == 1

            val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
            val brand = (Build.BRAND ?: "").lowercase()
            val model = (Build.MODEL ?: "").lowercase()

            // 2) 已知问题厂商 + Adreno GPU 组合（不再限制 SDK 版本）
            //    实测：OnePlus PKX110 (Adreno 830, SDK 36) 也有此 bug
            val knownProblematicManufacturer =
                manufacturer.contains("oppo") ||
                    manufacturer.contains("oneplus") ||
                    manufacturer.contains("realme") ||
                    manufacturer.contains("vivo") ||
                    brand.contains("oppo") ||
                    brand.contains("oneplus") ||
                    brand.contains("realme") ||
                    brand.contains("vivo")

            // 3) GL_RENDERER 字符串匹配 Adreno 6xx/7xx/8xx
            //    通过 EGL_OPENGL_RENDERER 读取（在主线程安全调用）
            val glRenderer = readGlRendererString()
            val isAdrenoProblematic =
                glRenderer != null &&
                    (
                        glRenderer.contains("Adreno(TM) 6") ||
                            glRenderer.contains("Adreno (TM) 6") ||
                            glRenderer.contains("Adreno(TM) 7") ||
                            glRenderer.contains("Adreno (TM) 7") ||
                            glRenderer.contains("Adreno(TM) 8") ||
                            glRenderer.contains("Adreno (TM) 8") ||
                            glRenderer.contains("Adreno™ 6") ||
                            glRenderer.contains("Adreno™ 7") ||
                            glRenderer.contains("Adreno™ 8")
                    )

            // 厂商匹配 + Adreno GPU → 直接判定为问题设备
            if (knownProblematicManufacturer && isAdrenoProblematic) {
                prefs.edit().putInt("gpu_problematic_v2", 1).apply()
                Log.w(
                    "GpuCompat",
                    "Marked as problematic: manufacturer=$manufacturer brand=$brand model=$model gpu=$glRenderer (Adreno+ColorOS)",
                )
                return true
            }

            // 4) 已知问题设备型号（按需添加）
            //    OnePlus PKX110 = OnePlus Pad 2 (Adreno 830) - 已确认有 AHardwareBuffer 白屏 bug
            val knownProblematicModels =
                setOf(
                    "pkx110", // OnePlus Pad 2 (Adreno 830)
                    "cph2581", // OnePlus 12 (Adreno 750)
                    "cph2651", // OnePlus 13 (Adreno 830)
                    "pjz110", // OnePlus Ace 3 Pro (Adreno 830)
                    "phb110", // OnePlus Ace 5 Pro (Adreno 830)
                )
            if (knownProblematicModels.any { model.contains(it) }) {
                prefs.edit().putInt("gpu_problematic_v2", 1).apply()
                Log.w("GpuCompat", "Marked as problematic: known model=$model gpu=$glRenderer")
                return true
            }

            // 5) Runtime.maxMemory() < 256MB 的设备通常 GPU 驱动也有问题
            val maxHeapMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
            if (maxHeapMb < 256) {
                prefs.edit().putInt("gpu_problematic_v2", 1).apply()
                Log.w("GpuCompat", "Marked as problematic: small heap=${maxHeapMb}MB")
                return true
            }

            // 6) 运行时探测：尝试用 AHardwareBuffer 创建 EGLImage
            //    这是 Chromium 创建 SharedImage 的核心路径，探测失败说明设备有 bug
            if (probeAHardwareBufferEglImage()) {
                // 探测成功 → 设备正常
                prefs.edit().putInt("gpu_problematic_v2", 0).apply()
                return false
            }
            // 探测失败 → 设备有 bug
            prefs.edit().putInt("gpu_problematic_v2", 1).apply()
            Log.w("GpuCompat", "Marked as problematic: AHardwareBuffer EGLImage probe failed (gpu=$glRenderer)")
            return true
        } catch (e: Throwable) {
            Log.e("GpuCompat", "isProblematicGpuDevice error", e)
            return false
        }
    }

    /**
     * 读取 GL_RENDERER 字符串（通过临时 EGL context）
     * 在主线程调用安全（仅创建临时 surfaceless context）
     */
    private fun readGlRendererString(): String? {
        return try {
            val display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            if (display == android.opengl.EGL14.EGL_NO_DISPLAY) return null
            val vers = IntArray(2)
            if (!android.opengl.EGL14.eglInitialize(display, vers, 0, vers, 1)) return null

            val configAttribs =
                intArrayOf(
                    android.opengl.EGL14.EGL_RENDERABLE_TYPE,
                    android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                    android.opengl.EGL14.EGL_RED_SIZE,
                    8,
                    android.opengl.EGL14.EGL_GREEN_SIZE,
                    8,
                    android.opengl.EGL14.EGL_BLUE_SIZE,
                    8,
                    android.opengl.EGL14.EGL_ALPHA_SIZE,
                    8,
                    android.opengl.EGL14.EGL_NONE,
                )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!android.opengl.EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                android.opengl.EGL14.eglTerminate(display)
                return null
            }
            val contextAttribs =
                intArrayOf(
                    android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION,
                    2,
                    android.opengl.EGL14.EGL_NONE,
                )
            val context = android.opengl.EGL14.eglCreateContext(display, configs[0], android.opengl.EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == android.opengl.EGL14.EGL_NO_CONTEXT) {
                android.opengl.EGL14.eglTerminate(display)
                return null
            }
            android.opengl.EGL14.eglMakeCurrent(display, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, context)
            val renderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)
            android.opengl.EGL14.eglMakeCurrent(
                display,
                android.opengl.EGL14.EGL_NO_SURFACE,
                android.opengl.EGL14.EGL_NO_SURFACE,
                android.opengl.EGL14.EGL_NO_CONTEXT,
            )
            android.opengl.EGL14.eglDestroyContext(display, context)
            android.opengl.EGL14.eglTerminate(display)
            renderer
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 运行时探测：尝试用 AHardwareBuffer 创建 EGLImage
     * ★ 简化版：由于 Android Kotlin API 不直接暴露 eglCreateImageKHR/EGL_NO_IMAGE_KHR，
     *   且 AHardwareBuffer EGLImage 探测本身风险较高（可能误伤正常设备），
     *   此处不再做 EGLImage 探测，统一返回 true（视为"无问题"）。
     *
     * 真正的问题设备检测由以下机制兜底：
     *   1) isProblematicGpuDevice() 中的机型 + GL_RENDERER 字符串匹配
     *   2) whitescreen_detected 持久化标记（由 JS/markGpuProblematic 写入）
     *   3) startGpuErrorLogcatMonitor() 实时监控 chromium 原生 GPU 错误，
     *      检测到 AHardwareBuffer SharedImage 创建失败时自动切换软件层
     */
    private fun probeAHardwareBufferEglImage(): Boolean = true

    /**
     * ★ JS 探测到白屏后调用此方法，持久化标记，下次启动强制使用软件渲染层
     * 一旦标记，永久使用软件渲染层，避免再次白屏
     * 可通过清除 SharedPreferences 中的 whitescreen_detected 标记来重置
     */
    fun markGpuProblematic() {
        try {
            val prefs = getSharedPreferences("gpu_compat", MODE_PRIVATE)
            prefs
                .edit()
                .putBoolean("whitescreen_detected", true)
                .putInt("gpu_problematic_v2", 1)
                .apply()
            Log.w("GpuCompat", "Whitescreen detected by JS, marked device as problematic. Next launch will use software layer.")
        } catch (e: Throwable) {
            Log.e("GpuCompat", "markGpuProblematic failed", e)
        }
    }

    /**
     * ★★ logcat GPU 错误监控（后台线程）
     *
     * chromium 原生 GPU 错误通过 logcat 输出（tag=chromium），不会走 JS console.error
     * 所以 onConsoleMessage 中的 GPU 错误检测对这些原生错误无效
     *
     * 监控的错误特征（来自实测日志）：
     *   "viz::SharedImageFormat RGBA_8888 can not be used to create a GL texture from AHardwareBuffer"
     *   "SharedImageStub: Unable to create shared image"
     *   "Could not find SharedImageBackingFactory with params:"
     *
     * 检测到错误后：
     *   1) 调用 JS GpuMemoryGuard.onGpuError() 累加错误计数
     *   2) 错误数超过阈值（10次）→ 自动切换到软件渲染层并持久化标记
     */
    private var gpuErrorMonitorThread: Thread? = null
    private var gpuErrorMonitorRunning = false

    @Volatile private var gpuErrorCountFromLogcat = 0

    @Volatile private var lastGpuErrorLogcatAt = 0L

    private fun startGpuErrorLogcatMonitor() {
        if (gpuErrorMonitorRunning) return
        gpuErrorMonitorRunning = true
        gpuErrorMonitorThread =
            Thread {
                try {
                    // 只过滤当前应用 PID 的 chromium 错误日志
                    val pid = android.os.Process.myPid()
                    val process = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "time", "--pid=$pid"))
                    val reader = process.inputStream.bufferedReader()
                    var consecutiveErrors = 0
                    var lastNotifyAt = 0L

                    while (gpuErrorMonitorRunning) {
                        val line = reader.readLine() ?: break
                        if (!gpuErrorMonitorRunning) break

                        // 检测 chromium GPU 错误特征
                        val isGpuError =
                            (
                                line.contains("SharedImageFormat RGBA_8888") &&
                                    line.contains("AHardwareBuffer")
                            ) ||
                                line.contains("SharedImageStub: Unable to create shared image") ||
                                (
                                    line.contains("Could not find SharedImageBackingFactory") &&
                                        line.contains("CanvasResourceRaster")
                                )

                        if (isGpuError) {
                            gpuErrorCountFromLogcat++
                            lastGpuErrorLogcatAt = System.currentTimeMillis()
                            consecutiveErrors++

                            // 限流：每 2 秒最多通知 JS 一次
                            val now = System.currentTimeMillis()
                            if (now - lastNotifyAt > 2000) {
                                lastNotifyAt = now
                                val count = gpuErrorCountFromLogcat
                                Log.w(
                                    "GpuCompat",
                                    "logcat GPU error detected (total=$count, consecutive=$consecutiveErrors): ${line.take(200)}",
                                )

                                // 通知 JS GpuMemoryGuard
                                runOnUiThread {
                                    try {
                                        webView?.evaluateJavascript(
                                            "try{if(typeof GpuMemoryGuard!=='undefined'&&GpuMemoryGuard.onGpuError){for(var i=0;i<$count;i++)GpuMemoryGuard.onGpuError();}}catch(e){}",
                                            null,
                                        )
                                    } catch (e: Throwable) {
                                        Log.e("GpuCompat", "Failed to notify JS of GPU error", e)
                                    }
                                }
                            }

                            // ★ v4.1: 累计 2 次错误 → 通知 JS 执行 GPU 内存循环释放
                            //   AHardwareBuffer SharedImage 创建失败一旦出现就是致命白屏
                            //   2 次错误就立即触发软重置，不等白屏扩散
                            //   不切换软件层（会严重卡顿），不 reload（会触发启动动画）
                            if (gpuErrorCountFromLogcat >= 2 && consecutiveErrors >= 2) {
                                Log.w(
                                    "GpuCompat",
                                    "GPU error threshold ($gpuErrorCountFromLogcat) reached, requesting JS soft GPU memory reset",
                                )
                                runOnUiThread {
                                    try {
                                        webView?.evaluateJavascript(
                                            "try{if(typeof GpuMemoryGuard!=='undefined'&&GpuMemoryGuard.softResetGpuMemory){GpuMemoryGuard.softResetGpuMemory('logcat-gpu-error');}}catch(e){}",
                                            null,
                                        )
                                    } catch (e: Throwable) {
                                        Log.e("GpuCompat", "Failed to request JS soft GPU reset", e)
                                    }
                                }
                                // 重置计数，让监控继续工作（循环释放，不停止监控）
                                gpuErrorCountFromLogcat = 0
                                consecutiveErrors = 0
                            }
                        } else {
                            // 非错误行，缓慢衰减连续错误计数
                            if (consecutiveErrors > 0 && Math.random() < 0.01) {
                                consecutiveErrors = Math.max(0, consecutiveErrors - 1)
                            }
                        }
                    }

                    try {
                        process.destroy()
                    } catch (_: Throwable) {
                    }
                } catch (e: Throwable) {
                    Log.e("GpuCompat", "GPU error logcat monitor failed", e)
                } finally {
                    gpuErrorMonitorRunning = false
                }
            }.apply {
                name = "GpuErrorLogcatMonitor"
                isDaemon = true
                start()
            }
    }

    private fun stopGpuErrorLogcatMonitor() {
        gpuErrorMonitorRunning = false
        try {
            gpuErrorMonitorThread?.interrupt()
        } catch (_: Throwable) {
        }
        gpuErrorMonitorThread = null
    }

    /**
     * ★★★ 检测自启动状态
     *
     * 如果检测到 BootReceiver 从未被触发（说明自启动被阻止），
     * 弹出对话框引导用户跳转到自启动设置页面
     */
    private fun checkAutoStartStatus() {
        try {
            val prefs = getSharedPreferences("mineradio_autostart_check", MODE_PRIVATE)
            val lastBootCompletedTime = prefs.getLong("last_boot_completed_time", 0L)
            val lastAppStartTime = prefs.getLong("last_app_start_time", 0L)
            val lastPromptTime = prefs.getLong("last_prompt_time", 0L)

            // 记录应用启动
            com.mineradio.app.wallpaper.AutoStartHelper
                .recordAppStart(this)

            // ★ 检测逻辑：
            //   1. 如果 lastBootCompletedTime == 0，说明 BootReceiver 从未被触发
            //   2. 如果 lastAppStartTime > 0 且 lastBootCompletedTime == 0，说明应用启动过但 BootReceiver 没被触发
            //   3. 24 小时内只提示一次（避免频繁打扰）
            val now = System.currentTimeMillis()
            val twentyFourHours = 24 * 60 * 60 * 1000L

            val needPrompt =
                lastAppStartTime > 0 &&
                    lastBootCompletedTime == 0L &&
                    (lastPromptTime == 0L || now - lastPromptTime > twentyFourHours)

            if (needPrompt) {
                // 记录提示时间
                prefs.edit().putLong("last_prompt_time", now).apply()

                // 弹出对话框引导用户开启自启动
                runOnUiThread {
                    try {
                        val brand =
                            com.mineradio.app.wallpaper.AutoStartHelper
                                .getDeviceBrand()
                        val dialog =
                            android.app.AlertDialog
                                .Builder(this)
                                .setTitle("开启自启动权限")
                                .setMessage(
                                    "检测到应用的自启动权限未开启，这会导致重启手机后壁纸无法自动恢复。\n\n" +
                                        "请在${brand}安全中心中开启本应用的自启动权限，以确保壁纸功能正常运行。",
                                ).setPositiveButton("去设置") { _, _ ->
                                    com.mineradio.app.wallpaper.AutoStartHelper
                                        .openAutoStartSettings(this)
                                }.setNegativeButton("稍后提醒") { _, _ ->
                                    // 24 小时后会再次提醒
                                }.setCancelable(false)
                                .create()
                        dialog.show()
                        Log.d("AutoStart", "checkAutoStartStatus: prompted user to enable auto-start")
                    } catch (e: Exception) {
                        Log.e("AutoStart", "checkAutoStartStatus: show dialog failed", e)
                    }
                }
            } else {
                Log.d("AutoStart", "checkAutoStartStatus: auto-start appears enabled (lastBootCompletedTime=$lastBootCompletedTime)")
            }
        } catch (e: Exception) {
            Log.e("AutoStart", "checkAutoStartStatus failed", e)
        }
    }

    private fun requestAllRuntimePermissions() {
        val toRequest = mutableListOf<String>()

        // 通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toRequest.add(Manifest.permission.CAMERA)
        }

        // 录音权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // 读取音频文件权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                toRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            // Android 12 及以下
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                toRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                toRequest.toTypedArray(),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }

        // MANAGE_EXTERNAL_STORAGE 需要跳系统设置页（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent =
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent =
                            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        startActivity(intent)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (::webView.isInitialized) {
                    webView.evaluateJavascript("window._onCameraPermissionResult && window._onCameraPermissionResult($granted)", null)
                }
            }
            RECORD_AUDIO_PERMISSION_REQUEST -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (::webView.isInitialized) {
                    webView.evaluateJavascript(
                        "window._onRecordAudioPermissionResult && window._onRecordAudioPermissionResult($granted)",
                        null,
                    )
                }
                // ★ 修复音频可视化：权限授予后，如果 PMKG 背景壁纸处于活动状态，重启音频采集以启用 Visualizer
                //   首次启动壁纸时权限未授权会导致 Visualizer 未创建（仅 fftProcessor 备选方案运行），
                //   用户授权后需停止再重启音频采集，Visualizer 才能捕获系统混音
                if (granted && mpkgBgActive && mpkgBgAudioEnabled) {
                    Log.d("MpkgBg", "RECORD_AUDIO granted, restarting audio recording to enable Visualizer")
                    stopMpkgBgAudioRecording()
                    startMpkgBgAudioRecording()
                } else if (granted && mpkgBgActive && !mpkgBgAudioEnabled) {
                    Log.d("MpkgBg", "RECORD_AUDIO granted, starting audio recording")
                    startMpkgBgAudioRecording()
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    //  通知栏按钮回调 — MediaNotificationState → WebView JS
    // ═══════════════════════════════════════════

    private fun registerNotificationCallbacks(view: WebView) {
        MediaNotificationState.apply {
            onPlayPause = {
                view.post {
                    view.evaluateJavascript(
                        "if(window._onNotificationPlayPause)_onNotificationPlayPause()",
                        null,
                    )
                }
            }
            onSkipPrevious = {
                view.post {
                    view.evaluateJavascript(
                        "if(window._onNotificationPrev)_onNotificationPrev()",
                        null,
                    )
                }
            }
            onSkipNext = {
                view.post {
                    view.evaluateJavascript(
                        "if(window._onNotificationNext)_onNotificationNext()",
                        null,
                    )
                }
            }
            onSeek = { seekSec ->
                view.post {
                    view.evaluateJavascript(
                        "if(window.handleMediaSeek)handleMediaSeek($seekSec)",
                        null,
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    //  WakeLock 管理
    // ═══════════════════════════════════════════

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock =
                pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Mineradio:MusicPlayback",
                )
            wakeLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "acquireWakeLock failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    // ═══════════════════════════════════════════════════════════
    //  原生手势推理 (CameraX + MediaPipe HandLandmarker)
    // ═══════════════════════════════════════════════════════════

    /** 启动原生手势识别 */
    private fun startNativeHandGesture() {
        if (isNativeGestureActive) return
        Log.i(TAG, "startNativeHandGesture: initializing...")

        // 1. 检查摄像头权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST,
            )
            Log.w(TAG, "Camera permission not granted, requesting...")
            return
        }

        // 2. 初始化 GestureRecognizerHelper
        gestureRecognizerHelper?.release()
        gestureRecognizerHelper = null
        gestureRecognizerHelper =
            GestureRecognizerHelper(
                this,
                object : GestureRecognizerHelper.Listener {
                    override fun onResults(
                        landmarks: List<GestureRecognizerHelper.LandmarkData>,
                        gesture: String?,
                        score: Float,
                    ) {
                        runOnUiThread {
                            if (landmarks.isNotEmpty()) {
                                val sb = StringBuilder("{\"gesture\":\"${gesture ?: ""}\",\"score\":$score,\"lm\":[")
                                landmarks.forEachIndexed { i, lm ->
                                    if (i > 0) sb.append(",")
                                    sb.append("{\"x\":${lm.x},\"y\":${lm.y},\"z\":${lm.z}}")
                                }
                                sb.append("]}")
                                webView.evaluateJavascript(
                                    "if(window._nativeHandCallback)window._nativeHandCallback($sb)",
                                    null,
                                )
                            } else {
                                webView.evaluateJavascript(
                                    "if(window._nativeHandNoop)window._nativeHandNoop()",
                                    null,
                                )
                            }
                        }
                    }

                    override fun onError(error: String) {
                        Log.e(TAG, "GestureRecognizer error: $error")
                    }
                },
            )

        gestureRecognizerHelper?.load { success ->
            if (!success) {
                Log.e(TAG, "Model load failed")
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(window._onNativeGestureFailed)window._onNativeGestureFailed('model');",
                        null,
                    )
                }
                return@load
            }
            Log.i(TAG, "Model loaded, starting CameraX...")
            // ★ 通知 JS 模型已就绪，消除超时定时器
            runOnUiThread {
                webView.evaluateJavascript(
                    "if(window._onNativeGestureReady)window._onNativeGestureReady();",
                    null,
                )
            }
            gesturePreviewActive = false
            setupGestureCamera(includePreview = false)
        }
    }

    /** 配置 CameraX 摄像头绑定 */
    private fun setupGestureCamera(includePreview: Boolean = false) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val cameraSelector =
                    CameraSelector
                        .Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build()

                val useCases = mutableListOf<androidx.camera.core.UseCase>()

                // 安全获取 rotation，防止 display 为 null 导致 NPE
                val displayRotation =
                    gesturePreviewView.display?.rotation
                        ?: windowManager.defaultDisplay.rotation

                // ImageAnalysis: RGBA_8888 格式
                val imageAnalysis =
                    ImageAnalysis
                        .Builder()
                        .setTargetResolution(android.util.Size(640, 480))
                        .setTargetRotation(displayRotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                imageAnalysis.setAnalyzer(gestureCameraExecutor) { imageProxy ->
                    if (!isNativeGestureActive) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    gestureRecognizerHelper?.processFrame(imageProxy)
                }
                useCases.add(imageAnalysis)

                // Preview: 仅当需要预览时才绑定
                if (includePreview) {
                    val preview =
                        Preview
                            .Builder()
                            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                            .setTargetRotation(displayRotation)
                            .build()
                    preview.setSurfaceProvider(gesturePreviewView.surfaceProvider)
                    useCases.add(preview)
                }

                // 先解绑旧绑定，避免冲突
                try {
                    provider.unbindAll()
                } catch (_: Exception) {
                }

                provider.bindToLifecycle(this, cameraSelector, *useCases.toTypedArray())

                gesturePreviewActive = includePreview
                gesturePreviewContainer.visibility = if (includePreview) View.VISIBLE else View.GONE
                gestureOverlay.visibility = if (includePreview) View.VISIBLE else View.GONE
                isNativeGestureActive = true
                Log.i(TAG, "CameraX + GestureRecognizer started (preview=$includePreview)")
            } catch (e: Exception) {
                Log.e(TAG, "setupGestureCamera failed", e)
                // 摄像头绑定失败，通知 JS
                webView.evaluateJavascript(
                    "if(window._onNativeGestureFailed)window._onNativeGestureFailed('camera');",
                    null,
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 停止原生手势识别 */
    private fun stopNativeHandGesture() {
        if (!isNativeGestureActive) return
        isNativeGestureActive = false
        gesturePreviewActive = false
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbindAll failed", e)
        }
        cameraProvider = null
        try {
            gestureRecognizerHelper?.release()
        } catch (e: Exception) {
            Log.w(TAG, "release failed", e)
        }
        gestureRecognizerHelper = null
        gesturePreviewContainer.visibility = View.GONE
        gestureOverlay.visibility = View.GONE
        gestureOverlay.update(null, null, 0f)
        Log.i(TAG, "Native gesture stopped")
    }

    // ═══════════════════════════════════════════
    //  沉浸式全屏（游戏级边到边显示）
    // ═══════════════════════════════════════════

    /**
     * 应用沉浸式全屏：隐藏状态栏和导航栏
     * 使用 WindowInsetsControllerCompat（API 30+）和旧 API 兼容
     */
    private fun applyImmersiveFullscreen() {
        try {
            val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
            // 隐藏状态栏和导航栏
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            // 滑动时短暂显示系统栏，自动重新隐藏（IMMERSIVE_STICKY 行为）
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (e: Exception) {
            Log.w(TAG, "WindowInsetsController failed: ${e.message}")
        }

        // 旧 API 兼容（API 30 以下）
        try {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        } catch (e: Exception) {
            Log.w(TAG, "systemUiVisibility failed: ${e.message}")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 获得焦点时重新应用沉浸式全屏（防止从后台返回时状态栏重新出现）
            applyImmersiveFullscreen()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // ★ 横竖屏切换时更新 sensorMatrix，确保陀螺仪 X/Y 轴正确对应
        if (mpkgBgSensorListener != null) {
            updateMpkgBgSensorMatrix()
        }
    }

    override fun onResume() {
        super.onResume()
        // 恢复时重新应用沉浸式全屏
        applyImmersiveFullscreen()
        // ★ 恢复 MPKG 背景渲染
        if (mpkgBgActive) {
            mpkgBgGLSurfaceView?.onResume()
        }
        // ★ 桌面歌词：授权页返回后若已获得悬浮窗权限，自动显示浮窗并通知 JS
        if (pendingDesktopLyricEnable) {
            pendingDesktopLyricEnable = false
            if (Settings.canDrawOverlays(this@LandscapeWebActivity)) {
                try {
                    showDesktopLyricView()
                    webView.evaluateJavascript(
                        "if(window._onDesktopLyricPermissionGranted)window._onDesktopLyricPermissionGranted(true);",
                        null,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "pendingDesktopLyricEnable show error", e)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // ★ 暂停 MPKG 背景渲染
        if (mpkgBgActive) {
            mpkgBgGLSurfaceView?.onPause()
        }
    }

    // ═══════════════════════════════════════════
    //  音域回响：原生音频采集
    // ═══════════════════════════════════════════

    private fun startSonicAudioCapture(mode: String = "system") {
        // ★ 如果已经在运行，先完全停止再重新启动（解决切换模式不生效问题）
        if (sonicAudioCaptureRunning) {
            Log.w(TAG, "SonicAudioCapture already running, stopping first before restart")
            stopSonicAudioCapture()
        }
        sonicAudioCaptureRunning = true

        if (mode == "player") {
            // ★ 播放器直采模式：基于播放进度+歌词时间戳驱动鼓点节奏
            //   不分析音频本身，通过歌词时间戳（鼓点节奏）触发动画
            //   完全绕过 Visualizer/AudioRecord，不受 MIUI 限制，不受环境噪音影响
            //   实际逻辑由 JS 端 __startPlayerBeat(true) 实现（喂模拟频谱+鼓点）
            runOnUiThread {
                try {
                    if (::webView.isInitialized) {
                        webView.evaluateJavascript("(window.__startPlayerBeat&&window.__startPlayerBeat(true));", null)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "evaluateJavascript __startPlayerBeat failed", e)
                }
            }
            Log.i(TAG, "SonicAudioCapture started (player mode = JS lyric-driven beat)")
            return
        }

        // ★ 系统混音模式：同时启动 player beat 作为底层鼓点驱动（不喂模拟频谱）
        //   原因：MIUI/HyperOS 限制 Visualizer(0) 返回全0数据，需要等30帧才能 fallback
        //   同时启动 player beat(false) 确保鼓点效果立即可用，Visualizer 真实数据仍作为频谱
        if (mode == "system") {
            runOnUiThread {
                try {
                    if (::webView.isInitialized) {
                        webView.evaluateJavascript("(window.__startPlayerBeat&&window.__startPlayerBeat(false));", null)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "evaluateJavascript __startPlayerBeat (system+player) failed", e)
                }
            }
        }

        if (sonicAudioCapture == null) {
            sonicAudioCapture = AudioCapture(this)
        }
        // 麦克风模式需要 RECORD_AUDIO 权限
        if (mode == "mic" && !sonicAudioCapture!!.hasMicPermission()) {
            sonicAudioCaptureRunning = false
            runOnUiThread {
                ActivityCompat.requestPermissions(
                    this@LandscapeWebActivity,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    RECORD_AUDIO_PERMISSION_REQUEST,
                )
            }
            return
        }
        sonicAudioCapture!!.listener =
            AudioCapture.SpectrumListener { bins ->
                // 把 64 段频谱通过 evaluateJavascript 喂给 WebView 的 window.__feedAudio
                val sb = StringBuilder(64 * 4)
                sb.append("(window.__feedAudio&&window.__feedAudio([")
                for (i in bins.indices) {
                    if (i > 0) sb.append(',')
                    sb.append(String.format("%.4f", bins[i]))
                }
                sb.append("]));")
                val js = sb.toString()
                runOnUiThread {
                    try {
                        if (::webView.isInitialized) webView.evaluateJavascript(js, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "evaluateJavascript __feedAudio failed", e)
                    }
                }
            }
        // ★ Visualizer 全0自动 fallback 回调（小米/红米兼容）
        //   MIUI 限制 Visualizer(0) 抓取 STREAM_MUSIC 返回全0数据
        //   fallback 到麦克风模式（用户要求保留麦克风），同时保留 player beat 鼓点驱动
        sonicAudioCapture!!.onVisualizerSilent = {
            Log.w(TAG, "Visualizer silent for too long, auto-fallback to mic mode (MIUI compat)")
            runOnUiThread {
                if (sonicAudioCaptureRunning && sonicAudioCapture?.audioSource == "system") {
                    sonicAudioCapture?.stop()
                    if (sonicAudioCapture!!.hasMicPermission()) {
                        sonicAudioCapture!!.start("mic")
                        Log.i(TAG, "Auto-switched to mic mode (Visualizer silent fallback)")
                        // ★ player beat(false) 仍保持运行，作为鼓点驱动辅助
                        //   麦克风提供真实频谱，player beat 提供鼓点节奏
                    } else {
                        ActivityCompat.requestPermissions(
                            this@LandscapeWebActivity,
                            arrayOf(Manifest.permission.RECORD_AUDIO),
                            RECORD_AUDIO_PERMISSION_REQUEST,
                        )
                    }
                }
            }
        }
        sonicAudioCapture!!.start(mode)
    }

    private fun stopSonicAudioCapture() {
        if (!sonicAudioCaptureRunning) return
        // 停止 JS 端播放器直采节拍（player 模式）
        runOnUiThread {
            try {
                if (::webView.isInitialized) {
                    webView.evaluateJavascript("(window.__stopPlayerBeat&&window.__stopPlayerBeat());", null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "evaluateJavascript __stopPlayerBeat failed", e)
            }
        }
        // 停止 fftProcessor 注入（已弃用，保留清理）
        sonicSpectrumRunnable?.let { sonicSpectrumHandler.removeCallbacks(it) }
        sonicSpectrumRunnable = null
        // 停止 AudioCapture（system/mic 模式）
        sonicAudioCapture?.listener = null
        sonicAudioCapture?.onVisualizerSilent = null
        sonicAudioCapture?.stop()
        sonicAudioCaptureRunning = false
        Log.i(TAG, "SonicAudioCapture stopped")
    }

    // ★ 音域回响频谱注入器（player 模式）：从 fftProcessor.bands 读取频谱注入 WebView
    private val sonicSpectrumHandler = Handler(Looper.getMainLooper())
    private var sonicSpectrumRunnable: Runnable? = null
    private val sonicSpectrumIntervalMs = 60L

    // ═══════════════════════════════════════════
    //  ★★★ 萌宠音乐状态推送器
    //  - 定期把播放状态（是否播放、歌曲标题、频谱强度）推送给 PetFloatingService
    //  - 推送频率约 2 秒一次（节拍反应靠 notifyBeat 节流）
    // ═══════════════════════════════════════════
    private val petMusicStateHandler = Handler(Looper.getMainLooper())
    private var petMusicStateRunnable: Runnable? = null
    private val petMusicStateIntervalMs = 2000L

    @Volatile private var lastPetPlayingState: Boolean = false

    private fun startPetMusicStatePush() {
        if (petMusicStateRunnable != null) return
        petMusicStateRunnable =
            object : Runnable {
                override fun run() {
                    try {
                        val player = GlobalContext.get().get<com.mineradio.app.player.api.IMusicPlayer>()
                        val isPlaying =
                            try {
                                player.isPlaying.value
                            } catch (e: Exception) {
                                false
                            }
                        val title =
                            try {
                                player.currentMediaItem.value
                                    ?.mediaMetadata
                                    ?.title
                                    ?.toString() ?: ""
                            } catch (e: Exception) {
                                ""
                            }
                        // 只在状态变化时推送（减少日志噪音）
                        if (isPlaying != lastPetPlayingState || title.isNotEmpty()) {
                            com.mineradio.app.wallpaper.PetFloatingService
                                .notifyMusicState(isPlaying, title, 0)
                            lastPetPlayingState = isPlaying
                        }
                        // ★ 节拍检测：读取低频段强度，强节拍时推送给萌宠
                        if (isPlaying) {
                            try {
                                val bands = player.fftProcessor.bands.value
                                if (bands != null && bands.isNotEmpty()) {
                                    // 取前 4 段（低频）平均强度作为节拍强度
                                    val lowFreqAvg =
                                        (0 until minOf(4, bands.size))
                                            .map { bands[it] }
                                            .average()
                                            .toFloat()
                                            .coerceIn(0f, 1f)
                                    // 强度阈值 0.6 才推送
                                    if (lowFreqAvg > 0.6f) {
                                        com.mineradio.app.wallpaper.PetFloatingService
                                            .notifyBeat(lowFreqAvg)
                                    }
                                }
                            } catch (e: Exception) {
                                // 静默忽略
                            }
                        }
                    } catch (e: Exception) {
                        // 静默忽略
                    }
                    petMusicStateHandler.postDelayed(this, petMusicStateIntervalMs)
                }
            }
        petMusicStateHandler.post(petMusicStateRunnable!!)
        Log.d(TAG, "PetMusicStatePush started")
    }

    private fun stopPetMusicStatePush() {
        petMusicStateRunnable?.let { petMusicStateHandler.removeCallbacks(it) }
        petMusicStateRunnable = null
    }

    // ★ 节拍事件桥：从前端 JS 推送给萌宠
    //   JS 端检测到节拍时调用 window.KeepApp.notifyPetBeat(intensity)
    @JavascriptInterface
    fun notifyPetBeat(intensity: Float) {
        try {
            com.mineradio.app.wallpaper.PetFloatingService
                .notifyBeat(intensity)
        } catch (e: Exception) {
            // 静默忽略
        }
    }

    private fun startSonicSpectrumInjection() {
        if (sonicSpectrumRunnable != null) {
            Log.w(TAG, "[Sonic-Player] Spectrum injection already running")
            return
        }
        try {
            val player = GlobalContext.get().get<com.mineradio.app.player.api.IMusicPlayer>()
            if (!player.fftProcessor.isEnabled.value) {
                player.fftProcessor.enable()
                Log.d(TAG, "[Sonic-Player] FFT processor enabled")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Sonic-Player] Failed to access fftProcessor: ${e.message}")
        }
        val sb = StringBuilder(64 * 6)
        sonicSpectrumRunnable =
            object : Runnable {
                override fun run() {
                    try {
                        val player = GlobalContext.get().get<com.mineradio.app.player.api.IMusicPlayer>()
                        val bands = player.fftProcessor.bands.value
                        if (bands != null && bands.isNotEmpty()) {
                            sb.setLength(0)
                            sb.append("(window.__feedAudio&&window.__feedAudio([")
                            val targetLen = 64
                            val srcLen = bands.size
                            for (i in 0 until targetLen) {
                                if (i > 0) sb.append(',')
                                val srcPos = (i.toFloat() / (targetLen - 1)) * (srcLen - 1)
                                val srcIdx = srcPos.toInt()
                                val frac = srcPos - srcIdx
                                val v =
                                    if (srcIdx + 1 < srcLen) {
                                        bands[srcIdx] * (1 - frac) + bands[srcIdx + 1] * frac
                                    } else {
                                        bands[srcIdx]
                                    }
                                sb.append(String.format("%.4f", v.coerceIn(0f, 1f)))
                            }
                            sb.append("]));")
                            val js = sb.toString()
                            runOnUiThread {
                                try {
                                    if (::webView.isInitialized) webView.evaluateJavascript(js, null)
                                } catch (e: Exception) {
                                    Log.w(TAG, "[Sonic-Player] feedAudio inject failed: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 静默忽略
                    }
                    sonicSpectrumHandler.postDelayed(this, sonicSpectrumIntervalMs)
                }
            }
        sonicSpectrumHandler.post(sonicSpectrumRunnable!!)
        Log.d(TAG, "[Sonic-Player] Spectrum injection started (${sonicSpectrumIntervalMs}ms)")
    }

    // ★ 粒子模式频谱注入：从竖屏 fftProcessor.bands 读取 31 段频谱 → 映射到 64 段 → 注入 WebView
    //   Visualizer(0) 抓全局混音在某些设备上返回全 0，fftProcessor 直接拦截 ExoPlayer PCM 更可靠
    private fun startParticleSpectrumInjection() {
        if (particleSpectrumRunnable != null) {
            Log.w(TAG, "[Particle] Spectrum injection already running")
            return
        }
        try {
            val player = GlobalContext.get().get<com.mineradio.app.player.api.IMusicPlayer>()
            // 确保 FFT 处理器启用
            if (!player.fftProcessor.isEnabled.value) {
                player.fftProcessor.enable()
                Log.d(TAG, "[Particle] FFT processor enabled for spectrum injection")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Particle] Failed to access fftProcessor: ${e.message}")
        }
        val sb = StringBuilder(64 * 5)
        particleSpectrumRunnable =
            object : Runnable {
                override fun run() {
                    try {
                        val player = GlobalContext.get().get<com.mineradio.app.player.api.IMusicPlayer>()
                        val bands = player.fftProcessor.bands.value
                        if (bands != null && bands.isNotEmpty()) {
                            // 31 段 → 64 段：线性插值映射
                            sb.setLength(0)
                            sb.append("(window.__feedAudio&&window.__feedAudio([")
                            val targetLen = 64
                            val srcLen = bands.size // 31
                            for (i in 0 until targetLen) {
                                if (i > 0) sb.append(',')
                                val srcPos = (i.toFloat() / (targetLen - 1)) * (srcLen - 1)
                                val srcIdx = srcPos.toInt()
                                val frac = srcPos - srcIdx
                                val v =
                                    if (srcIdx + 1 < srcLen) {
                                        bands[srcIdx] * (1 - frac) + bands[srcIdx + 1] * frac
                                    } else {
                                        bands[srcIdx]
                                    }
                                sb.append(String.format("%.4f", v.coerceIn(0f, 1f)))
                            }
                            sb.append("]));")
                            val js = sb.toString()
                            runOnUiThread {
                                try {
                                    if (::webView.isInitialized) webView.evaluateJavascript(js, null)
                                } catch (e: Exception) {
                                    Log.w(TAG, "[Particle] feedAudio inject failed: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 静默忽略，避免刷屏
                    }
                    particleSpectrumHandler.postDelayed(this, particleSpectrumIntervalMs)
                }
            }
        particleSpectrumHandler.post(particleSpectrumRunnable!!)
        Log.d(TAG, "[Particle] Spectrum injection started (${particleSpectrumIntervalMs}ms)")
    }

    private fun stopParticleSpectrumInjection() {
        particleSpectrumRunnable?.let { particleSpectrumHandler.removeCallbacks(it) }
        particleSpectrumRunnable = null
        Log.d(TAG, "[Particle] Spectrum injection stopped")
    }

    // ═══════════════════════════════════════════
    //  KeepApp JavaScript 桥接接口
    // ═══════════════════════════════════════════
    inner class KAppBridge {
        // ★★ PMKG 壁纸应用到程序背景层级
        //   前端 Wallpaper Engine 面板点击 PMKG 项目时调用此方法。
        //   由于 Android WebView 的 File 对象没有 path 属性（这是 Electron 特性），
        //   前端只能传递文件名，原生层通过 onShowFileChooser 回调保存的 URI 来读取文件内容。
        //   流程：
        //     1) 复制 URI 内容到外部公共目录 /sdcard/Documents/Mineradio/Wallpaper/<id>/<filename>
        //        （清理应用数据不会删除此目录，保证 BootReceiver 能自动恢复）
        //     2) 创建 WallpaperEntity 并持久化为应用背景壁纸（WallpaperManager.setAppBackgroundWallpaper）
        //     3) 调用 startMpkgBackground(realPath) 立即在程序背景层级渲染（MpkgBg GLSurfaceView）
        //   注意：@JavascriptInterface 在 WebView 后台线程调用，文件复制在后台线程完成，
        //         持久化和启动渲染切换到 UI 线程。
        @JavascriptInterface
        fun launchMpkgPreview(path: String) {
            // 先从路径提取文件名（可能是完整路径、相对路径或纯文件名）
            var fileName = path
            if (fileName.contains('/')) fileName = fileName.substringAfterLast('/')
            if (fileName.contains('\\')) fileName = fileName.substringAfterLast('\\')

            // ★★★ 修复：切换壁纸时 lastPickedFileUri 仍是旧值会启动错误的 PMKG
            //   优先在 filesDir/mpkg/ 查找已保存的同名文件（导入时 getWallpaperPreviewData 已复制 cover_*_<fileName>）
            val savedFile = findSavedMpkgInPrivateDir(fileName)
            if (savedFile != null) {
                Log.d(TAG, "launchMpkgPreview: using saved file ${savedFile.absolutePath}")
                Thread {
                    try {
                        val wallpaperId = "wp_${System.currentTimeMillis()}"
                        // ★ 修复重启后壁纸不显示：将 savedFile 复制到公共目录
                        //   WallpaperEntity.getRealPath() 返回公共目录路径，
                        //   重启后 checkAndStartMpkgBackground 会查找该路径，必须保证文件存在
                        val docsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
                        val wallpaperRoot = java.io.File(docsDir, "Mineradio/Wallpaper/$wallpaperId").apply { mkdirs() }
                        val targetFile = java.io.File(wallpaperRoot, fileName)
                        if (!targetFile.exists() || targetFile.length() != savedFile.length()) {
                            savedFile.copyTo(targetFile, overwrite = true)
                            Log.d(
                                TAG,
                                "launchMpkgPreview: copied saved file to public dir ${targetFile.absolutePath}, size=${targetFile.length()}",
                            )
                        }
                        val wallpaper =
                            com.mineradio.app.wallpaper.WallpaperEntity(
                                author = "Wallpaper Engine",
                                description = fileName,
                                id = wallpaperId,
                                name = fileName.substringBeforeLast('.'),
                                path = fileName,
                                versionCode = 1,
                                versionName = "1.0",
                                wallpaperType = com.mineradio.app.wallpaper.WallpaperEntity.TYPE_MPKG_WEBGL,
                            )
                        val wm =
                            com.mineradio.app.wallpaper.WallpaperManager
                                .get(this@LandscapeWebActivity)
                        wm.addWallpaper(wallpaper)
                        wm.setAppBackgroundWallpaper(wallpaper)
                        Log.d(TAG, "launchMpkgPreview: persisted as app background, id=$wallpaperId")
                        // ★ 使用公共目录路径，与 WallpaperEntity.getRealPath() 一致，保证重启后能找到
                        val realPath = targetFile.absolutePath
                        runOnUiThread {
                            try {
                                startMpkgBackground(realPath)
                                Toast
                                    .makeText(
                                        this@LandscapeWebActivity,
                                        "已应用为程序背景: ${wallpaper.name}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                webView.evaluateJavascript(
                                    "try{if(typeof onPmkgAppliedToBackground==='function')onPmkgAppliedToBackground('$wallpaperId','${wallpaper.name.replace(
                                        "'",
                                        "\\'",
                                    )}');}catch(e){}",
                                    null,
                                )
                            } catch (e: Throwable) {
                                Log.e(TAG, "launchMpkgPreview: startMpkgBackground failed", e)
                                Toast.makeText(this@LandscapeWebActivity, "应用背景失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "launchMpkgPreview saved file path failed", e)
                        runOnUiThread {
                            Toast.makeText(this@LandscapeWebActivity, "壁纸应用失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
                return
            }

            val uri = lastPickedFileUri
            if (uri == null) {
                // ★ 重启后 lastPickedFileUri 已失效：回退到应用私有目录 filesDir/mpkg 查找同名的已保存 PMKG 文件
                val fallback = findSavedMpkgInPrivateDir(fileName)
                if (fallback != null) {
                    Log.d(TAG, "launchMpkgPreview: fallback to private dir ${fallback.absolutePath}")
                    startMpkgBackground(fallback.absolutePath)
                    runOnUiThread {
                        Toast
                            .makeText(
                                this@LandscapeWebActivity,
                                "已应用为程序背景: ${fallback.name.substringBeforeLast('.')}",
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                    return
                }
                Log.e(TAG, "launchMpkgPreview: lastPickedFileUri is null, path=$path")
                runOnUiThread {
                    Toast.makeText(this@LandscapeWebActivity, "未找到壁纸文件，请重新选择", Toast.LENGTH_SHORT).show()
                }
                return
            }
            Thread {
                try {
                    // 如果前端传递的 path 为空，尝试从 URI 查询文件名
                    if (fileName.isBlank()) {
                        fileName = queryFileNameFromUri(uri) ?: "wallpaper_${System.currentTimeMillis()}.mpkg"
                    }
                    Log.d(TAG, "launchMpkgPreview: fileName=$fileName, uri=$uri")

                    // ★ 1) 复制到外部公共目录 /sdcard/Documents/Mineradio/Wallpaper/<wallpaperId>/<fileName>
                    //   原因：WallpaperManager.getRealPath() 读取此路径，清理应用数据不会删除
                    //   保证 BootReceiver 自动激活时能找到文件
                    val wallpaperId = "wp_${System.currentTimeMillis()}"
                    val docsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
                    val wallpaperRoot = java.io.File(docsDir, "Mineradio/Wallpaper/$wallpaperId").apply { mkdirs() }
                    val targetFile = java.io.File(wallpaperRoot, fileName)
                    this@LandscapeWebActivity.contentResolver.openInputStream(uri)?.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: run {
                        Log.e(TAG, "launchMpkgPreview: openInputStream failed for $uri")
                        runOnUiThread {
                            Toast.makeText(this@LandscapeWebActivity, "无法读取壁纸文件", Toast.LENGTH_SHORT).show()
                        }
                        return@Thread
                    }
                    Log.d(TAG, "launchMpkgPreview: copied to ${targetFile.absolutePath}, size=${targetFile.length()}")

                    // ★ 2) 创建 WallpaperEntity 并持久化为应用背景壁纸
                    val wallpaper =
                        com.mineradio.app.wallpaper.WallpaperEntity(
                            author = "Wallpaper Engine",
                            description = fileName,
                            id = wallpaperId,
                            name = fileName.substringBeforeLast('.'),
                            path = fileName,
                            versionCode = 1,
                            versionName = "1.0",
                            wallpaperType = com.mineradio.app.wallpaper.WallpaperEntity.TYPE_MPKG_WEBGL,
                        )
                    val wm =
                        com.mineradio.app.wallpaper.WallpaperManager
                            .get(this@LandscapeWebActivity)
                    wm.addWallpaper(wallpaper)
                    wm.setAppBackgroundWallpaper(wallpaper)
                    Log.d(TAG, "launchMpkgPreview: persisted as app background, id=$wallpaperId")

                    // ★ 3) 切换到 UI 线程，在程序背景层级渲染（MpkgBg GLSurfaceView）
                    val realPath = targetFile.absolutePath
                    runOnUiThread {
                        try {
                            startMpkgBackground(realPath)
                            Toast.makeText(this@LandscapeWebActivity, "已应用为程序背景: ${wallpaper.name}", Toast.LENGTH_SHORT).show()
                            // 通知前端刷新 Wallpaper Engine 面板状态
                            webView.evaluateJavascript(
                                "try{if(typeof onPmkgAppliedToBackground==='function')onPmkgAppliedToBackground('$wallpaperId','${wallpaper.name.replace(
                                    "'",
                                    "\\'",
                                )}');}catch(e){}",
                                null,
                            )
                        } catch (e: Throwable) {
                            Log.e(TAG, "launchMpkgPreview: startMpkgBackground failed", e)
                            Toast.makeText(this@LandscapeWebActivity, "应用背景失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "launchMpkgPreview failed", e)
                    runOnUiThread {
                        Toast.makeText(this@LandscapeWebActivity, "壁纸应用失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        // ★ 在应用私有目录 filesDir/mpkg 中查找与目标文件名匹配（含 cover_ 前缀）的已保存 PMKG 文件
        //   用于 lastPickedFileUri 失效（如应用重启）时回退启动背景
        private fun findSavedMpkgInPrivateDir(fileName: String): java.io.File? {
            try {
                val mpkgDir = java.io.File(filesDir, "mpkg")
                if (!mpkgDir.exists() || fileName.isBlank()) return null
                val normalized = fileName.trim()
                return mpkgDir
                    .listFiles()
                    ?.filter { it.isFile }
                    ?.filter { normalized.startsWith("cover_") || it.name.endsWith(normalized) }
                    ?.maxByOrNull { it.lastModified() }
            } catch (e: Exception) {
                Log.e(TAG, "findSavedMpkgInPrivateDir failed", e)
                return null
            }
        }

        // ★★ 获取 PMKG 壁纸预览封面（base64 data URL）
        //   参照 mineradio-android 的 getWallpaperPreviewData + getMpkgPreviewBase64 逻辑：
        //     用 SceneLib.getWallpaperInfoPreviewBitmap 提取 MPKG 内嵌预览图，转 base64 供前端面板显示封面。
        //   前端在导入 PMKG 时调用此方法，把封面 data URL 存入项目对象，Wallpaper Engine 面板卡片即显示封面。
        //   注意：@JavascriptInterface 在 WebView 后台线程调用，同步返回 base64。
        @JavascriptInterface
        fun getWallpaperPreviewData(
            id: String,
            name: String,
        ): String {
            try {
                val uri = lastPickedFileUri ?: return ""
                // 提取文件名（name 已是 String，无需再包装）
                var fileName = name
                if (fileName.contains('/')) fileName = fileName.substringAfterLast('/')
                if (fileName.contains('\\')) fileName = fileName.substringAfterLast('\\')
                if (fileName.isBlank()) {
                    fileName = queryFileNameFromUri(uri) ?: "wallpaper_${System.currentTimeMillis()}.mpkg"
                }
                // 复制到 app 私有目录（libscenejni.so 只能读取私有目录文件）
                val mpkgDir = java.io.File(this@LandscapeWebActivity.filesDir, "mpkg")
                if (!mpkgDir.exists()) mpkgDir.mkdirs()
                val targetFile = java.io.File(mpkgDir, "cover_${System.currentTimeMillis()}_$fileName")
                this@LandscapeWebActivity.contentResolver.openInputStream(uri)?.use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return ""
                Log.d(TAG, "getWallpaperPreviewData: copied to ${targetFile.absolutePath}, size=${targetFile.length()}")

                // ★ 用 SceneLib 提取 MPKG 预览封面
                //   参照 mineradio-android：每次新建 SceneLib 实例（isInitialized 为静态，仅首次真正初始化）。
                //   避免复用正在渲染背景的 mpkgBgSceneLib 实例，防止跨线程/运行态干扰导致取不到封面。
                val sceneLib = io.wallpaperengine.wrapper.SceneLib()
                if (!io.wallpaperengine.wrapper.SceneLib
                        .isInitialized()
                ) {
                    sceneLib.initLibrary(this@LandscapeWebActivity)
                }
                val bytes = sceneLib.getWallpaperInfoPreviewBitmap(targetFile.absolutePath)
                if (bytes != null && bytes.isNotEmpty()) {
                    return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }
                // fallback：从 MPKG 内嵌图片提取封面
                val coverUri = extractCoverFromMpkg(uri)
                if (coverUri != null) {
                    val coverBytes = this@LandscapeWebActivity.contentResolver.openInputStream(coverUri)?.use { it.readBytes() }
                    if (coverBytes != null && coverBytes.isNotEmpty()) {
                        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(coverBytes, android.util.Base64.NO_WRAP)
                    }
                }
                return ""
            } catch (e: Exception) {
                Log.e(TAG, "getWallpaperPreviewData failed", e)
                return ""
            }
        }

        // 辅助方法：从 content URI 查询文件显示名（DISPLAY_NAME）
        private fun queryFileNameFromUri(uri: Uri): String? =
            try {
                val cursor = this@LandscapeWebActivity.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) it.getString(nameIndex) else null
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "queryFileNameFromUri failed", e)
                null
            }

        // ── 获取 APK 版本号（让 JS 绑定到 APK 版本信息）──
        @JavascriptInterface
        fun getAppVersionName(): String =
            try {
                val pkgInfo = packageManager.getPackageInfo(packageName, 0)
                pkgInfo.versionName ?: "1.1.7.0"
            } catch (e: Exception) {
                "1.1.7.0"
            }

        // ★★ GPU 白屏恢复：reload WebView
        //   JS 端 GpuMemoryGuard 探测到白屏后调用此方法
        //   重新加载当前页面（保留 Activity 状态，比 recreate() 轻）
        //   ★ v4.4: 自动添加 skipSplash=1 参数，避免 reload 触发启动动画
        //   注意：@JavascriptInterface 在 WebView 后台线程调用，需 post 到 UI 线程
        @JavascriptInterface
        fun reload() {
            runOnUiThread {
                try {
                    Log.w("GpuCompat", "KeepApp.reload() called from JS — reloading WebView (skipSplash)")
                    // 重新加载当前 URL（保留 mode=particle 等 query），添加 skipSplash=1
                    var currentUrl = webView?.url ?: "http://127.0.0.1:$serverPort/"
                    if (!currentUrl.contains("skipSplash=1")) {
                        currentUrl += if (currentUrl.contains("?")) "&skipSplash=1" else "?skipSplash=1"
                    }
                    webView?.loadUrl(currentUrl)
                } catch (e: Throwable) {
                    Log.e("GpuCompat", "reload failed", e)
                }
            }
        }

        // ★★ GPU 白屏持久化标记：JS 探测到白屏后调用此方法
        //   持久化 whitescreen_detected=true，下次启动强制使用 LAYER_TYPE_SOFTWARE
        //   避免再次进入白屏状态
        @JavascriptInterface
        fun markGpuProblematic() {
            try {
                this@LandscapeWebActivity.markGpuProblematic()
            } catch (e: Throwable) {
                Log.e("GpuCompat", "markGpuProblematic bridge failed", e)
            }
        }

        // ★★ GPU 内存循环释放（v2 策略）：
        //   旧版切换到 LAYER_TYPE_SOFTWARE 会导致 3D 粒子背景极度卡顿。
        //   新版改为：保持硬件加速层不变，仅触发 JS 侧 GpuMemoryGuard
        //   主动 dispose 纹理/几何体/shader programs，把显存压回 ~200MB。
        //   保留方法名以兼容旧 JS 调用，但行为已改为软重置。
        @JavascriptInterface
        fun switchToSoftwareLayer() {
            runOnUiThread {
                try {
                    Log.w(
                        "GpuCompat",
                        "KeepApp.switchToSoftwareLayer() called from JS — redirecting to softResetGpuMemory (v2: no software layer)",
                    )
                    webView?.evaluateJavascript(
                        "try{if(typeof GpuMemoryGuard!=='undefined'&&GpuMemoryGuard.softResetGpuMemory){GpuMemoryGuard.softResetGpuMemory('js-bridge');}}catch(e){}",
                        null,
                    )
                } catch (e: Throwable) {
                    Log.e("GpuCompat", "softResetGpuMemory redirect failed", e)
                }
            }
        }

        // ── 云更新：通过 Native HTTP 拉取云端文本（绕过 CORS）──
        @JavascriptInterface
        fun fetchUpdateInfo(url: String) {
            Thread {
                try {
                    val urlObj = URL(url)
                    val conn = urlObj.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("User-Agent", "Mineradio-Android/1.1")
                    conn.instanceFollowRedirects = true
                    conn.connect()

                    if (conn.responseCode != 200) {
                        runOnUiThread {
                            webView.evaluateJavascript(
                                "if(window._onUpdateInfoFetched)window._onUpdateInfoFetched(''," + conn.responseCode + ",'HTTP " +
                                    conn.responseCode +
                                    "')",
                                null,
                            )
                        }
                        conn.disconnect()
                        return@Thread
                    }

                    // ★ 读取字节流并用 UTF-8 解码（避免编码不一致导致中文乱码）
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val contentType = conn.contentType ?: ""
                    var charset = "UTF-8"
                    // 从 ContentType 中提取 charset
                    if (contentType.contains("charset=", ignoreCase = true)) {
                        val csMatch = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(contentType)
                        if (csMatch != null) charset = csMatch.groupValues[1]
                    }
                    val text = String(bytes, charset(charset))
                    conn.disconnect()
                    Log.i(TAG, "fetchUpdateInfo: charset=$charset, textLen=${text.length}, preview=${text.take(100)}")

                    // ★ 使用 JSON.quote 安全传递文本（正确处理 Unicode 中文、换行、引号等）
                    //   之前的手动转义会把 \uXXXX 序列破坏，导致中文显示为 \uXXXX 文本
                    val jsonText = org.json.JSONObject.quote(text)
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if(window._onUpdateInfoFetched)window._onUpdateInfoFetched(" + jsonText + ",200,'')",
                            null,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "fetchUpdateInfo error", e)
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if(window._onUpdateInfoFetched)window._onUpdateInfoFetched('',0,'" +
                                (e.message ?: "error").replace(
                                    "'",
                                    "\\'",
                                ) +
                                "')",
                            null,
                        )
                    }
                }
            }.apply {
                name = "fetch_update_info"
                start()
            }
        }

        // ── 云更新：下载 APK + 安装 ──
        @JavascriptInterface
        fun downloadUpdateApk(url: String) {
            Thread {
                try {
                    val apkFile =
                        File(
                            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                            "Mineradio_Update.apk",
                        )
                    if (apkFile.exists()) apkFile.delete()

                    val urlObj = URL(url)
                    val conn = urlObj.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.setRequestProperty("User-Agent", "Mineradio-Android")
                    conn.instanceFollowRedirects = true
                    conn.connect()

                    if (conn.responseCode != 200) {
                        sendUpdateProgress(true, 0, 0, 0, 0, 0, "HTTP ${conn.responseCode}")
                        return@Thread
                    }

                    val total = conn.contentLengthLong
                    val input = conn.inputStream
                    val output = FileOutputStream(apkFile)
                    val buffer = ByteArray(8192)
                    var received = 0L
                    var lastReportTime = System.currentTimeMillis()
                    var startTime = lastReportTime

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
                                val eta = if (speedBps > 0 && total > 0) ((total - received) / speedBps) else 0L
                                sendUpdateProgress(false, progress, received, total, speedBps, eta.toInt(), "")
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

                    sendUpdateProgress(
                        true,
                        100,
                        received,
                        total,
                        if (received > 0) (received * 1000 / ((System.currentTimeMillis() - startTime).coerceAtLeast(1))) else 0L,
                        0,
                        "",
                    )

                    Thread.sleep(500)
                    runOnUiThread { installApkSafely(apkFile) }
                } catch (e: Exception) {
                    Log.e(TAG, "downloadUpdateApk error", e)
                    sendUpdateProgress(true, 0, 0, 0, 0, 0, e.message ?: "下载失败")
                }
            }.apply {
                name = "apk_download"
                start()
            }
        }

        // ★ 检查是否有已下载的 APK 安装包（cacheDir/update.apk）
        //   返回路径给 JS，如果文件存在且大于 1MB
        @JavascriptInterface
        fun checkDownloadedApk() {
            Thread {
                try {
                    val apkFile =
                        File(
                            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                            "Mineradio_Update.apk",
                        )
                    val path = if (apkFile.exists() && apkFile.length() > 1048576) apkFile.absolutePath else ""
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "if(window._onDownloadedApkChecked)window._onDownloadedApkChecked(${org.json.JSONObject.quote(path)})",
                            null,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "checkDownloadedApk error", e)
                }
            }.start()
        }

        // ★ 安装已下载的 APK 安装包
        @JavascriptInterface
        fun installDownloadedApk() {
            Thread {
                try {
                    val apkFile =
                        File(
                            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                            "Mineradio_Update.apk",
                        )
                    if (!apkFile.exists() || apkFile.length() < 1048576) {
                        runOnUiThread {
                            Toast.makeText(this@LandscapeWebActivity, "安装包不存在或已损坏", Toast.LENGTH_SHORT).show()
                        }
                        return@Thread
                    }
                    runOnUiThread { installApkSafely(apkFile) }
                } catch (e: Exception) {
                    Log.e(TAG, "installDownloadedApk error", e)
                    runOnUiThread {
                        Toast.makeText(this@LandscapeWebActivity, "安装失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        // ── 设置持久化 ──
        // ★ 保存到外部存储 /sdcard/SPICaMusic/，卸载/清理数据后仍保留
        private fun externalSettingsFile(): File {
            val dir = File(android.os.Environment.getExternalStorageDirectory(), "SPICaMusic")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "mineradio_settings.json")
        }

        @JavascriptInterface
        fun saveSettings(settingsJson: String) {
            try {
                // 同时写入外部存储和私有目录（双保险）
                externalSettingsFile().writeText(settingsJson)
                File(filesDir, "mineradio_settings.json").writeText(settingsJson)
            } catch (e: Exception) {
                Log.e(TAG, "saveSettings error", e)
            }
        }

        @JavascriptInterface
        fun loadSettings(): String =
            try {
                // 优先读外部存储，不存在时回退到私有目录
                val extFile = externalSettingsFile()
                if (extFile.exists()) {
                    extFile.readText()
                } else {
                    val privFile = File(filesDir, "mineradio_settings.json")
                    if (privFile.exists()) privFile.readText() else ""
                }
            } catch (e: Exception) {
                ""
            }

        // ── 启动方向（竖屏/横屏）──
        //   ★ 竖屏 Compose 界面已整体移除，App 现在固定横屏启动。
        //     为兼容插件/H5 既有调用，桥接方法保留，但恒返回 "landscape" 且不再写入竖屏设置。
        @JavascriptInterface
        fun getStartupOrientation(): String = "landscape"

        @JavascriptInterface
        fun setStartupOrientation(mode: String) {
            try {
                if (mode != "landscape") {
                    Log.w("StartupOrient", "setStartupOrientation($mode): 竖屏启动已移除，忽略（固定横屏）")
                }
                getSharedPreferences("mineradio_activity_state", MODE_PRIVATE)
                    .edit()
                    .putString("startup_orientation", "landscape")
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "setStartupOrientation error", e)
            }
        }

        // ── 音量控制 ──
        // ★ JS 端滑块范围为 0~1 浮点，Android 系统音量为 0~maxVol 整数，此处做归一化转换
        @JavascriptInterface
        fun setSystemVolume(vol: String) {
            try {
                val v = vol.toFloatOrNull() ?: return // 0~1
                val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                if (maxVol <= 0) return
                val targetVol = (v * maxVol).toInt().coerceIn(0, maxVol)
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, 0)
            } catch (e: Exception) {
                Log.e(TAG, "setSystemVolume error", e)
            }
        }

        @JavascriptInterface
        fun getSystemVolume(): String =
            try {
                val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                if (maxVol <= 0) {
                    "1.0"
                } else {
                    val cur = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    String.format(java.util.Locale.US, "%.2f", cur.toFloat() / maxVol)
                }
            } catch (e: Exception) {
                "1.0"
            }

        // ═══════════════════════════════════════════
        //  ★ 第三方平台登录桥接：独立 Dialog WebView 加载 PC 登录页
        //    在独立 Dialog 中加载，不影响主 WebView，避免返回时重启程序
        // ═══════════════════════════════════════════

        /** 打开 QQ 音乐登录页（独立 Dialog，PC 版） */
        @JavascriptInterface
        fun openQQMusicLogin(url: String) {
            val target = if (url.isBlank()) "https://y.qq.com/" else url
            runOnUiThread { showLoginDialog(target, "qq") }
        }

        /** 打开酷狗音乐登录页（独立 Dialog，PC 版） */
        @JavascriptInterface
        fun openKugouMusicLogin(url: String) {
            val target = if (url.isBlank()) "https://www.kugou.com/" else url
            runOnUiThread { showLoginDialog(target, "kg") }
        }

        /** 打开网易云音乐登录页（独立 Dialog，PC 版） */
        @JavascriptInterface
        fun openNeteaseMusicLogin(url: String) {
            val target = if (url.isBlank()) "https://music.163.com/" else url
            runOnUiThread { showLoginDialog(target, "netease") }
        }

        /** 打开抖音扫码登录页（汽水音乐，独立 Dialog，PC 版） */
        @JavascriptInterface
        fun startQishuiQRLogin() {
            runOnUiThread {
                showLoginDialog(
                    "https://sso.douyin.com/login_qr/?aid=2906&service=https://www.douyin.com",
                    "qishui",
                )
            }
        }

        /** ★ 打开汽水音乐扫码登录页（music.douyin.com，独立 Dialog） */
        @JavascriptInterface
        fun openQishuiMusicLogin(url: String) {
            val target = if (url.isBlank()) "https://music.douyin.com/" else url
            runOnUiThread { showLoginDialog(target, "qishui") }
        }

        /** 取消抖音扫码登录（Dialog 模式，用户可按返回键取消） */
        @JavascriptInterface
        fun cancelQishuiQRLogin() {
            // Dialog 模式下无需额外处理，用户按返回键即可关闭
        }

        // ════════════════════════════════════════════════════════════
        //  ★ 场景壁纸参数面板桥接（驱动 HTML 外观面板中的"场景参数"折叠区）
        //    JS 端 refreshScenePropsPanel() 通过这些方法获取/修改场景参数
        //    之前只有原生 Android 面板（齿轮按钮+ScrollView），现在统一用 HTML 面板
        // ════════════════════════════════════════════════════════════

        /** 场景壁纸是否激活（用于 HTML 面板决定是否显示"场景参数"折叠区） */
        @JavascriptInterface
        fun isMpkgSceneActive(): Boolean = mpkgBgActive && !mpkgBgIsVideo && mpkgBgContextId >= 0 && mpkgBgSceneInitialized

        /**
         * ★ 获取当前场景壁纸的可调参数列表（JSON 字符串）
         *   返回格式：[{key, type, text, value, min, max, ...}, ...]
         *   - text 字段已经过本地化处理（i18n key → 中文）
         *   - 过滤 condition="false" 的内部隐藏属性
         *   - 跳过没有 type 的无效属性
         *   - 同步更新 mpkgBgCurrentPropsJson 缓存
         */
        @JavascriptInterface
        fun getMpkgSceneProperties(): String {
            try {
                if (mpkgBgContextId < 0 || !mpkgBgSceneInitialized) return "[]"
                val sceneLib = mpkgBgSceneLib ?: return "[]"
                // ★ 设置中文语言（让 getLocalization 能返回中文）
                try {
                    sceneLib.setLanguage("zh-CN")
                } catch (_: Throwable) {
                }
                val propsJson = sceneLib.getSceneProperties(mpkgBgContextId) ?: return "[]"
                val json = org.json.JSONObject(propsJson)

                // ★★★ 合并用户保存的属性值，让 HTML 面板按钮状态恢复用户上次设置
                //   修复：重开参数面板时按钮状态丢失（效果保留但 UI 重置）
                //   原因：getSceneProperties 返回的是场景默认值，需要从持久化文件覆盖
                loadScenePropsFromFile(json)

                // ★ 同步更新缓存（applyMpkgSceneProperty 会基于这个缓存修改后回写）
                synchronized(mpkgBgCurrentPropsJson) {
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        mpkgBgCurrentPropsJson.put(key, json.getJSONObject(key))
                    }
                }
                // ★ 转换为数组格式，并本地化 text 字段
                val result = org.json.JSONArray()
                val keys = json.keys()
                val keyList = mutableListOf<String>()
                while (keys.hasNext()) keyList.add(keys.next())
                // 按 order 排序
                keyList.sortBy { key ->
                    try {
                        val prop = json.getJSONObject(key)
                        if (prop.has("order")) prop.getInt("order") else 0
                    } catch (_: Throwable) {
                        0
                    }
                }
                for (key in keyList) {
                    try {
                        val prop = json.getJSONObject(key)
                        val condition = prop.optString("condition", "")
                        // ★ 跳过 condition="false" 的内部隐藏属性
                        if (condition == "false") continue
                        val type = prop.optString("type", "")
                        if (type.isEmpty()) continue
                        val item = org.json.JSONObject()
                        item.put("key", key)
                        item.put("type", type)
                        // ★ 本地化 text 字段（i18n key → 中文）
                        var text = prop.optString("text", "")
                        if (text.isNotEmpty()) {
                            try {
                                val localized = sceneLib.getLocalization(text)
                                if (!localized.isNullOrEmpty()) text = localized
                            } catch (_: Throwable) {
                            }
                        }
                        if (text.isEmpty()) text = key
                        item.put("text", text)
                        // 复制其他字段
                        if (prop.has("value")) item.put("value", prop.get("value"))
                        if (prop.has("min")) item.put("min", prop.get("min"))
                        if (prop.has("max")) item.put("max", prop.get("max"))
                        if (prop.has("step")) item.put("step", prop.get("step"))
                        if (prop.has("precision")) item.put("precision", prop.get("precision"))
                        if (prop.has("options")) item.put("options", prop.get("options"))
                        // ★ 本地化 options 中的 label
                        if (prop.has("options")) {
                            try {
                                val options = item.getJSONArray("options")
                                for (i in 0 until options.length()) {
                                    val opt = options.getJSONObject(i)
                                    var label = opt.optString("label", "")
                                    if (label.isNotEmpty()) {
                                        try {
                                            val locLabel = sceneLib.getLocalization(label)
                                            if (!locLabel.isNullOrEmpty()) {
                                                opt.put("label", locLabel)
                                            }
                                        } catch (_: Throwable) {
                                        }
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                        }
                        result.put(item)
                    } catch (_: Throwable) {
                    }
                }
                return result.toString()
            } catch (e: Throwable) {
                Log.e("MpkgBg", "getMpkgSceneProperties failed", e)
                return "[]"
            }
        }

        /**
         * ★ 实时应用场景属性修改（用户在 HTML 面板调整参数时调用）
         *   @param key 属性名（如 "rate", "wec_brs"）
         *   @param value 属性值（字符串形式，内部自动转换为对应类型）
         *
         * ★★ 持久化保存：修改后立即保存到文件，重启后恢复用户配置
         */
        @JavascriptInterface
        fun applyMpkgSceneProperty(
            key: String,
            value: String,
        ) {
            mpkgBgGLSurfaceView?.queueEvent {
                try {
                    if (mpkgBgContextId < 0 || !mpkgBgSceneInitialized) return@queueEvent
                    synchronized(mpkgBgCurrentPropsJson) {
                        if (mpkgBgCurrentPropsJson.has(key)) {
                            val prop = mpkgBgCurrentPropsJson.getJSONObject(key)
                            // ★ 根据原始值类型转换字符串值
                            val original = prop.opt("value")
                            when {
                                original is Boolean -> prop.put("value", value == "true" || value == "1")
                                original is Int || original is Long -> {
                                    prop.put("value", value.toIntOrNull() ?: value.toDouble().toInt())
                                }
                                original is Number -> prop.put("value", value.toDoubleOrNull() ?: 0.0)
                                else -> prop.put("value", value)
                            }
                        }
                    }
                    mpkgBgSceneLib?.applySceneProperties(mpkgBgContextId, mpkgBgCurrentPropsJson.toString())
                    // ★ 持久化保存到文件（异步，不阻塞 GL 线程）
                    saveScenePropsToFile()
                    Log.d("MpkgBg", "applyMpkgSceneProperty: $key=$value")
                } catch (e: Throwable) {
                    Log.e("MpkgBg", "applyMpkgSceneProperty failed: $key=$value", e)
                }
            }
        }

        /**
         * ★ 重置当前场景壁纸的所有属性到默认值
         *   1. 删除持久化的 scene_props 文件
         *   2. 重新读取场景默认属性（不合并用户保存的值）
         *   3. 应用到运行场景
         *   4. 同步更新 mpkgBgCurrentPropsJson 缓存
         *   5. 重新强制 alignment=0、mobileparallax=0、mobileparallaxstrength=0
         *   返回重置后的属性 JSON 数组（同 getMpkgSceneProperties 格式），前端可据此刷新面板
         */
        @JavascriptInterface
        fun resetMpkgSceneProperties(): String {
            try {
                if (mpkgBgContextId < 0 || !mpkgBgSceneInitialized) return "[]"
                val sceneLib = mpkgBgSceneLib ?: return "[]"
                // ★ 1. 删除持久化文件
                val file = getScenePropsFile()
                if (file != null && file.exists()) {
                    val deleted = file.delete()
                    Log.d("MpkgBg", "resetMpkgSceneProperties: deleted $file (ok=$deleted)")
                }
                // ★ 2. 读取默认属性（不合并用户保存值）
                try {
                    sceneLib.setLanguage("zh-CN")
                } catch (_: Throwable) {
                }
                val propsJson = sceneLib.getSceneProperties(mpkgBgContextId) ?: return "[]"
                val json = org.json.JSONObject(propsJson)
                // ★ 3. 强制 alignment=0、mobileparallax=0、mobileparallaxstrength=0
                //   与 initScene 后的强制设置保持一致（避免 parallax 放大、alignment 不铺满）
                try {
                    if (json.has("alignment")) {
                        json.getJSONObject("alignment").put("value", 0)
                    }
                } catch (_: Throwable) {
                }
                try {
                    if (json.has("mobileparallax")) {
                        json.getJSONObject("mobileparallax").put("value", false)
                    }
                } catch (_: Throwable) {
                }
                try {
                    if (json.has("mobileparallaxstrength")) {
                        json.getJSONObject("mobileparallaxstrength").put("value", 0.0)
                    }
                } catch (_: Throwable) {
                }
                // ★ 4. 应用到运行场景
                mpkgBgGLSurfaceView?.queueEvent {
                    try {
                        mpkgBgSceneLib?.applySceneProperties(mpkgBgContextId, json.toString())
                        Log.d("MpkgBg", "resetMpkgSceneProperties: applied default props")
                    } catch (e: Throwable) {
                        Log.e("MpkgBg", "resetMpkgSceneProperties apply failed", e)
                    }
                }
                // ★ 5. 同步更新缓存（applyMpkgSceneProperty 会基于这个缓存修改后回写）
                synchronized(mpkgBgCurrentPropsJson) {
                    // 清空现有 keys（mpkgBgCurrentPropsJson 是 val，不能重新赋值）
                    val existingKeys = mutableListOf<String>()
                    val existingIter = mpkgBgCurrentPropsJson.keys()
                    while (existingIter.hasNext()) existingKeys.add(existingIter.next())
                    existingKeys.forEach { mpkgBgCurrentPropsJson.remove(it) }
                    // 重新填充默认值
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        mpkgBgCurrentPropsJson.put(key, json.getJSONObject(key))
                    }
                }
                // ★ 6. 转换为数组格式（同 getMpkgSceneProperties 的逻辑）
                val result = org.json.JSONArray()
                val keys = json.keys()
                val keyList = mutableListOf<String>()
                while (keys.hasNext()) keyList.add(keys.next())
                keyList.sortBy { key ->
                    try {
                        val prop = json.getJSONObject(key)
                        if (prop.has("order")) prop.getInt("order") else 0
                    } catch (_: Throwable) {
                        0
                    }
                }
                for (key in keyList) {
                    try {
                        val prop = json.getJSONObject(key)
                        val condition = prop.optString("condition", "")
                        if (condition == "false") continue
                        val type = prop.optString("type", "")
                        if (type.isEmpty()) continue
                        val item = org.json.JSONObject()
                        item.put("key", key)
                        item.put("type", type)
                        var text = prop.optString("text", "")
                        if (text.isNotEmpty()) {
                            try {
                                val localized = sceneLib.getLocalization(text)
                                if (!localized.isNullOrEmpty()) text = localized
                            } catch (_: Throwable) {
                            }
                        }
                        if (text.isEmpty()) text = key
                        item.put("text", text)
                        if (prop.has("value")) item.put("value", prop.get("value"))
                        if (prop.has("min")) item.put("min", prop.get("min"))
                        if (prop.has("max")) item.put("max", prop.get("max"))
                        if (prop.has("step")) item.put("step", prop.get("step"))
                        if (prop.has("precision")) item.put("precision", prop.get("precision"))
                        if (prop.has("options")) item.put("options", prop.get("options"))
                        if (prop.has("options")) {
                            try {
                                val options = item.getJSONArray("options")
                                for (i in 0 until options.length()) {
                                    val opt = options.getJSONObject(i)
                                    var label = opt.optString("label", "")
                                    if (label.isNotEmpty()) {
                                        try {
                                            val locLabel = sceneLib.getLocalization(label)
                                            if (!locLabel.isNullOrEmpty()) {
                                                opt.put("label", locLabel)
                                            }
                                        } catch (_: Throwable) {
                                        }
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                        }
                        result.put(item)
                    } catch (_: Throwable) {
                    }
                }
                return result.toString()
            } catch (e: Throwable) {
                Log.e("MpkgBg", "resetMpkgSceneProperties failed", e)
                return "[]"
            }
        }

        /** ★ haowallpaper 在线壁纸下载（弹出 WebView 让用户扫码登录后下载真实文件）
         *  调用流程：前端检测到 source=haowallpaper 时调用此方法
         *  1. 弹出 WebView 加载 haowallpaper 详情页
         *  2. 用户在页面内点击下载按钮 → 弹出微信扫码登录
         *  3. 扫码登录后页面自动调用 getCompleteUrl API + fetch 下载文件
         *  4. 注入的 JS 通过 URL.createObjectURL 拦截大 blob（壁纸文件）
         *  5. 将 blob 分块 base64 后传回 Android，保存为本地文件
         *  6. 导入壁纸库并设置为当前壁纸
         */
        @JavascriptInterface
        fun startHaoWebDownload(
            detailUrl: String,
            name: String,
            type: String,
        ) {
            Log.i(TAG, "★ startHaoWebDownload called: detailUrl=$detailUrl, name=$name, type=$type")
            runOnUiThread { showHaoWebDownloadDialog(detailUrl, name, type) }
        }

        // ═══════════════════════════════════════════
        //  用户上传 .mpkg 壁纸桥接
        // ═══════════════════════════════════════════

        /** ★ 保存 mineradio JWT token 到 SharedPreferences（JS 登录后同步） */
        @JavascriptInterface
        fun saveMineradioToken(token: String) {
            try {
                getSharedPreferences(MINERADIO_TOKEN_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(MINERADIO_TOKEN_KEY, token)
                    .apply()
                Log.i(TAG, "saveMineradioToken: token saved (length=${token.length})")
            } catch (e: Exception) {
                Log.e(TAG, "saveMineradioToken failed", e)
            }
        }

        /** ★ Steam 壁纸扫码连接 PC 端 */
        @JavascriptInterface
        fun scanSteamWpQrCode() {
            runOnUiThread {
                try {
                    val options =
                        com.journeyapps.barcodescanner.ScanOptions().apply {
                            setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                            setPrompt("将 PC 端显示的二维码对准框中")
                            setBeepEnabled(true)
                            setOrientationLocked(false)
                        }
                    steamWpScanLauncher.launch(options)
                } catch (e: Exception) {
                    Log.e(TAG, "scanSteamWpQrCode failed", e)
                    // 如果扫码库不可用，通知前端
                    val resultJson =
                        org.json
                            .JSONObject()
                            .apply {
                                put("ok", false)
                                put("error", "扫码功能不可用: ${e.message}")
                            }.toString()
                    webView?.evaluateJavascript("if(window.onSteamWpScanResult){window.onSteamWpScanResult('$resultJson');}", null)
                }
            }
        }

        /**
         * ★ 从 PC 端下载 Steam 壁纸并导入壁纸库
         * @param downloadUrl PC 端下载 URL（http://IP:port/api/steam-wp/download/{id}）
         * @param name 壁纸名称
         * @param type 壁纸类型（video / scene）
         * @return JSON 字符串：{ok: true, name: "..."} 或 {ok: false, error: "..."}
         */
        @JavascriptInterface
        fun downloadSteamWp(
            downloadUrl: String,
            name: String,
            type: String,
        ): String {
            Log.i(TAG, "★ downloadSteamWp: url=$downloadUrl, name=$name, type=$type")
            return try {
                val wallpaperIdLocal = "wp_steam_" + System.currentTimeMillis()
                val docsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
                val targetDir = File(docsDir, "Mineradio/Wallpaper/$wallpaperIdLocal").apply { mkdirs() }
                val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "wallpaper" }
                // 根据类型确定文件扩展名
                val ext = if (type == "video") ".mp4" else ".mpkg"
                val fileName = "$safeName$ext"
                val targetFile = File(targetDir, fileName)

                // 下载文件
                val url = java.net.URL(downloadUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 300000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mineradio/1.0")
                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    return org.json
                        .JSONObject()
                        .apply {
                            put("ok", false)
                            put("error", "PC端返回错误: HTTP $responseCode")
                        }.toString()
                }
                val totalSize = conn.contentLengthLong
                var downloadedSize = 0L
                java.io.BufferedInputStream(conn.inputStream).use { input ->
                    java.io.FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedSize += bytesRead
                            // 更新进度
                            if (totalSize > 0) {
                                val progress = (downloadedSize * 100 / totalSize).toInt()
                                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentProgress = progress
                                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载中 $progress%"
                            }
                        }
                    }
                }
                conn.disconnect()
                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentProgress = 100
                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "导入中..."

                // 创建 WallpaperEntity 并加入壁纸库
                val wallpaperType =
                    if (type == "video") {
                        WallpaperEntity.TYPE_VIDEO
                    } else {
                        WallpaperEntity.TYPE_MPKG_WEBGL
                    }
                val wallpaper =
                    WallpaperEntity(
                        author = "Steam Wallpaper Engine",
                        description = "Steam 壁纸下载",
                        id = wallpaperIdLocal,
                        name = safeName,
                        path = fileName,
                        versionCode = 1,
                        versionName = "1.0",
                        wallpaperType = wallpaperType,
                    )
                val wm = WallpaperManager.get(this@LandscapeWebActivity)
                wm.addWallpaper(wallpaper)
                wm.setCurrentWallpaper(wallpaper)
                Log.i(TAG, "★ downloadSteamWp: imported $safeName, file size=${targetFile.length()}")
                org.json
                    .JSONObject()
                    .apply {
                        put("ok", true)
                        put("name", safeName)
                    }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "★ downloadSteamWp failed", e)
                org.json
                    .JSONObject()
                    .apply {
                        put("ok", false)
                        put("error", e.message ?: "下载失败")
                    }.toString()
            }
        }

        /** ★ 选择 .mpkg 文件（启动系统文件选择器） */
        @JavascriptInterface
        fun selectWallpaperMpkgFile() {
            Log.i(TAG, "★ selectWallpaperMpkgFile called")
            runOnUiThread {
                try {
                    // ★ 使用 GET_CONTENT 而非 OPEN_DOCUMENT，兼容性更好，不会卡死
                    val intent =
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    startActivityForResult(Intent.createChooser(intent, "选择 .mpkg 文件"), WALLPAPER_UPLOAD_MPKG_REQUEST)
                } catch (e: Exception) {
                    Log.e(TAG, "selectWallpaperMpkgFile failed", e)
                }
            }
        }

        /** ★ 选择封面图（启动系统文件选择器） */
        @JavascriptInterface
        fun selectWallpaperCoverFile() {
            Log.i(TAG, "★ selectWallpaperCoverFile called")
            runOnUiThread {
                try {
                    val intent =
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "image/*"
                        }
                    startActivityForResult(Intent.createChooser(intent, "选择封面图"), WALLPAPER_UPLOAD_COVER_REQUEST)
                } catch (e: Exception) {
                    Log.e(TAG, "selectWallpaperCoverFile failed", e)
                }
            }
        }

        /**
         * ★ 上传壁纸到服务端
         *
         * 在 WebView 后台线程执行（@JavascriptInterface 默认行为）：
         * a. 读取 JWT token（从 SharedPreferences）
         * b. 调用 /api/wallpapers/upload-token 获取 COS 预签名 URL
         * c. PUT 上传 .mpkg 文件到 COS 预签名 URL
         * d. 如果有封面图，PUT 上传封面图到 COS 预签名 URL
         * e. 调用 /api/wallpapers/create 创建记录
         * f. 返回结果 JSON 字符串给 JS 回调
         *
         * @param title 壁纸标题
         * @param desc 壁纸描述
         * @param mpkgPath .mpkg 文件的 content URI 字符串
         * @param mpkgName .mpkg 文件名
         * @param mpkgSize .mpkg 文件大小（字节）
         * @param coverPath 封面图 content URI 字符串（可为空）
         * @param coverName 封面图文件名（可为空）
         * @return JSON 字符串：{ok: true} 或 {ok: false, error: "..."}
         */
        @JavascriptInterface
        fun uploadWallpaper(
            title: String,
            desc: String,
            mpkgPath: String,
            mpkgName: String,
            mpkgSize: Long,
            coverPath: String,
            coverName: String,
        ): String {
            Log.i(TAG, "★ uploadWallpaper: title=$title, mpkgName=$mpkgName, mpkgSize=$mpkgSize, hasCover=${coverPath.isNotEmpty()}")
            // ★ 异步执行：立即返回，后台线程上传，完成后通过 evaluateJavascript 回调 JS
            // （原同步实现会阻塞 WebView JavaBridge 线程导致整个 UI 卡死）
            Thread {
                try {
                    val resultJson = doUploadWallpaperSync(title, desc, mpkgPath, mpkgName, mpkgSize, coverPath, coverName)
                    Log.i(TAG, "uploadWallpaper async done: $resultJson")
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if(window.__onWallpaperUploadDone)window.__onWallpaperUploadDone($resultJson);",
                            null,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "uploadWallpaper async failed", e)
                    val errObj = org.json.JSONObject()
                    errObj.put("ok", false)
                    errObj.put("error", e.message ?: "未知错误")
                    val errJson = errObj.toString()
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if(window.__onWallpaperUploadDone)window.__onWallpaperUploadDone($errJson);",
                            null,
                        )
                    }
                }
            }.start()
            return """{"ok":true,"async":true,"msg":"上传已开始，请稍候..."}"""
        }

        /**
         * ★ 从 .mpkg 文件中自动提取封面图
         * 复用 MpkgParser 解析能力，查找预览图条目（preview.jpg/thumbnail.jpg/cover.jpg 等）
         * @param mpkgUri .mpkg 文件的 content URI
         * @return 封面图临时文件的 URI，失败返回 null
         */
        internal fun extractCoverFromMpkg(mpkgUri: android.net.Uri): android.net.Uri? {
            var tempMpkg: java.io.File? = null
            var tempCover: java.io.File? = null
            return try {
                // ★★★ 流式提取封面（不复制整个文件，避免大文件卡死）：
                //   1. 从 InputStream 解析 PKGM 头 + 条目表（只有几 KB）
                //   2. 找到图片条目后，跳过流到该偏移，只读取图片字节
                //   3. 这样无论 mpkg 多大，只需读取头部+图片数据，无需复制整个文件
                val streamResult = extractCoverFromMpkgStream(mpkgUri)
                if (streamResult != null) {
                    tempCover = java.io.File.createTempFile("upload_cover_", ".jpg", cacheDir)
                    tempCover.writeBytes(streamResult)
                    Log.i(TAG, "extractCoverFromMpkg: success via stream, cover size=${tempCover.length()}")
                    return android.net.Uri.fromFile(tempCover)
                }

                // ★ 回退方案：流式提取失败（如只有视频条目需要提取帧），才复制整个文件
                Log.w(TAG, "extractCoverFromMpkg: stream extraction failed, falling back to full copy")
                tempMpkg = java.io.File.createTempFile("upload_mpkg_", ".mpkg", cacheDir)
                contentResolver.openInputStream(mpkgUri)?.use { input ->
                    tempMpkg.outputStream().use { output ->
                        val buf = ByteArray(262144) // 256KB
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            output.write(buf, 0, n)
                        }
                        output.flush()
                    }
                } ?: return null
                Log.i(TAG, "extractCoverFromMpkg: mpkg copied to ${tempMpkg.absolutePath}, size=${tempMpkg.length()}")

                val parser =
                    com.mineradio.app.wallpaper
                        .MpkgParser(tempMpkg)
                val entries = parser.listEntries()
                if (entries.isEmpty()) {
                    Log.w(TAG, "extractCoverFromMpkg: no entries in mpkg")
                    return null
                }

                // 视频条目 → 提取第一帧
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
                    Log.i(TAG, "extractCoverFromMpkg: trying video frame from ${videoEntry.name}")
                    val videoCoverBytes = extractVideoFrameFromMpkg(tempMpkg)
                    if (videoCoverBytes != null) {
                        tempCover = java.io.File.createTempFile("upload_cover_", ".jpg", cacheDir)
                        tempCover.writeBytes(videoCoverBytes)
                        Log.i(TAG, "extractCoverFromMpkg: success from video frame, cover size=${tempCover.length()}")
                        return android.net.Uri.fromFile(tempCover)
                    }
                }

                Log.w(TAG, "extractCoverFromMpkg: no image or video entry found")
                null
            } catch (e: Exception) {
                Log.e(TAG, "extractCoverFromMpkg failed", e)
                tempCover?.delete()
                null
            } finally {
                tempMpkg?.delete()
            }
        }

        /**
         * ★★★ 流式提取封面（不复制整个 mpkg 文件，大幅提升大文件速度）
         *   直接从 content URI 的 InputStream 解析 PKGM 头部 + 条目表，
         *   然后跳过流到图片条目偏移处，只读取图片字节。
         *   @return JPEG 字节数组（已压缩），失败返回 null
         */
        private fun extractCoverFromMpkgStream(mpkgUri: android.net.Uri): ByteArray? {
            return try {
                val input = contentResolver.openInputStream(mpkgUri) ?: return null
                val dis = java.io.DataInputStream(input)

                // 1. 读取 headerSize (4B, LE)
                val headerSizeBytes = ByteArray(4)
                dis.readFully(headerSizeBytes)
                val headerSize =
                    java.nio.ByteBuffer
                        .wrap(headerSizeBytes)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .int
                        .toLong() and 0xFFFFFFFFL
                if (headerSize <= 0 || headerSize > 100 * 1024 * 1024) {
                    try {
                        dis.close()
                    } catch (_: Exception) {
                    }
                    return null
                }

                // 2. 读取 magic (8B)
                val magicBytes = ByteArray(8)
                dis.readFully(magicBytes)
                val magic = String(magicBytes, Charsets.US_ASCII)
                val supportedMagics =
                    setOf("PKGM0012", "PKGM0013", "PKGM0014", "PKGM0015", "PKGM0016", "PKGM0017", "PKGM0018", "PKGM0019", "PKGM0020")
                if (magic !in supportedMagics) {
                    Log.w(TAG, "extractCoverFromMpkgStream: unsupported magic=$magic")
                    try {
                        dis.close()
                    } catch (_: Exception) {
                    }
                    return null
                }

                // 3. 读取 entryCount (4B, LE)
                val countBytes = ByteArray(4)
                dis.readFully(countBytes)
                val entryCount =
                    java.nio.ByteBuffer
                        .wrap(countBytes)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .int
                if (entryCount <= 0 || entryCount > 10000) {
                    try {
                        dis.close()
                    } catch (_: Exception) {
                    }
                    return null
                }

                // 4. 读取条目表
                data class StreamEntry(
                    val name: String,
                    val relOffset: Long,
                    val size: Long,
                )
                val entries = mutableListOf<StreamEntry>()
                for (i in 0 until entryCount) {
                    val nameLenBytes = ByteArray(4)
                    dis.readFully(nameLenBytes)
                    val nameLen =
                        java.nio.ByteBuffer
                            .wrap(nameLenBytes)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .int
                    if (nameLen <= 0 || nameLen > 4096) {
                        try {
                            dis.close()
                        } catch (_: Exception) {
                        }
                        return null
                    }
                    val nameBytes = ByteArray(nameLen)
                    dis.readFully(nameBytes)
                    val name = String(nameBytes, Charsets.UTF_8)
                    val offBytes = ByteArray(4)
                    dis.readFully(offBytes)
                    val dataOffset =
                        java.nio.ByteBuffer
                            .wrap(offBytes)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .int
                            .toLong() and 0xFFFFFFFFL
                    val sizeBytes = ByteArray(4)
                    dis.readFully(sizeBytes)
                    val dataSize =
                        java.nio.ByteBuffer
                            .wrap(sizeBytes)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .int
                            .toLong() and 0xFFFFFFFFL
                    entries.add(StreamEntry(name, dataOffset, dataSize))
                }

                Log.i(TAG, "extractCoverFromMpkgStream: entries=${entries.map { it.name }}")

                // 5. 查找图片条目（优先级：预览名 > 任意图片）
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
                var targetEntry: StreamEntry? = null
                for (name in previewNames) {
                    targetEntry = entries.find { it.name.equals(name, ignoreCase = true) }
                    if (targetEntry != null) break
                }
                if (targetEntry == null) {
                    targetEntry =
                        entries.find { e ->
                            val lower = e.name.lowercase()
                            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
                        }
                }
                if (targetEntry == null) {
                    Log.w(TAG, "extractCoverFromMpkgStream: no image entry found")
                    try {
                        dis.close()
                    } catch (_: Exception) {
                    }
                    return null
                }

                Log.i(
                    TAG,
                    "extractCoverFromMpkgStream: target image=${targetEntry.name}, relOffset=${targetEntry.relOffset}, size=${targetEntry.size}",
                )

                // 6. 跳过流到图片数据位置（relOffset 是相对数据区起始的偏移，数据区起始 = 当前流位置）
                var skipped = 0L
                val skipBuf = ByteArray(65536)
                while (skipped < targetEntry.relOffset) {
                    val toRead = minOf(skipBuf.size.toLong(), targetEntry.relOffset - skipped).toInt()
                    val n = dis.read(skipBuf, 0, toRead)
                    if (n <= 0) break
                    skipped += n
                }
                Log.i(TAG, "extractCoverFromMpkgStream: skipped $skipped bytes to image data")

                // 7. 读取图片字节
                val imgSize = targetEntry.size.toInt()
                if (imgSize <= 0 || imgSize > 50 * 1024 * 1024) {
                    Log.w(TAG, "extractCoverFromMpkgStream: image size invalid=$imgSize")
                    try {
                        dis.close()
                    } catch (_: Exception) {
                    }
                    return null
                }
                val imageBytes = ByteArray(imgSize)
                dis.readFully(imageBytes)
                try {
                    dis.close()
                } catch (_: Exception) {
                }

                // 8. 解码为 JPEG（大图采样避免 OOM）
                val bitmap =
                    if (imageBytes.size > 5 * 1024 * 1024) {
                        val tempImgFile = java.io.File.createTempFile("upload_img_", ".img", cacheDir)
                        tempImgFile.writeBytes(imageBytes)
                        val opts = android.graphics.BitmapFactory.Options()
                        opts.inJustDecodeBounds = true
                        android.graphics.BitmapFactory.decodeFile(tempImgFile.absolutePath, opts)
                        opts.inSampleSize = Math.max(1, opts.outWidth / 1000)
                        opts.inJustDecodeBounds = false
                        val bm = android.graphics.BitmapFactory.decodeFile(tempImgFile.absolutePath, opts)
                        tempImgFile.delete()
                        bm
                    } else {
                        android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    }
                if (bitmap == null) {
                    Log.w(TAG, "extractCoverFromMpkgStream: decode bitmap failed")
                    return null
                }
                val baos = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                bitmap.recycle()
                Log.i(TAG, "extractCoverFromMpkgStream: success, jpeg size=${baos.size()}")
                baos.toByteArray()
            } catch (e: Exception) {
                Log.e(TAG, "extractCoverFromMpkgStream failed", e)
                null
            }
        }

        /** ★ 从 .mpkg 内的视频条目提取第一帧作为封面（返回 JPEG 字节数组） */
        internal fun extractVideoFrameFromMpkg(mpkgFile: java.io.File): ByteArray? {
            return try {
                val videoFile =
                    com.mineradio.app.wallpaper.MpkgParser
                        .extractVideoFile(mpkgFile.absolutePath, cacheDir) ?: return null
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
                try {
                    videoFile.delete()
                } catch (_: Exception) {
                }
                baos.toByteArray()
            } catch (e: Exception) {
                Log.e(TAG, "extractVideoFrameFromMpkg failed", e)
                null
            }
        }

        /** ★ 实际执行上传逻辑（在后台线程调用，避免阻塞 WebView JavaBridge 线程） */
        private fun doUploadWallpaperSync(
            title: String,
            desc: String,
            mpkgPath: String,
            mpkgName: String,
            mpkgSize: Long,
            coverPath: String,
            coverName: String,
        ): String {
            Log.i(TAG, "★ doUploadWallpaperSync START: title=$title, mpkgName=$mpkgName, mpkgSize=$mpkgSize")
            return try {
                // ★ 自动提取封面：如果用户未手动选择封面（coverPath 为空），则从 .mpkg 文件中提取预览图作为封面
                var finalCoverPath = coverPath
                var finalCoverName = coverName
                if (finalCoverPath.isEmpty()) {
                    Log.i(TAG, "★ doUploadWallpaperSync: coverPath 为空，尝试从 .mpkg 自动提取封面...")
                    com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "正在提取封面..."
                    // ★ 仅在上传流程中显示提取封面进度
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if(window.__onWallpaperUploadProgress)window.__onWallpaperUploadProgress(-1,0,0);",
                            null,
                        )
                    }
                    try {
                        val mpkgUri = Uri.parse(mpkgPath)
                        val autoCoverUri = extractCoverFromMpkg(mpkgUri)
                        if (autoCoverUri != null) {
                            finalCoverPath = autoCoverUri.toString()
                            finalCoverName = "auto_cover.jpg"
                            Log.i(TAG, "★ doUploadWallpaperSync: 自动提取封面成功, path=$finalCoverPath")
                        } else {
                            Log.w(TAG, "★ doUploadWallpaperSync: 自动提取封面失败（未找到图片条目），将无封面上传")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "★ doUploadWallpaperSync: 自动提取封面异常", e)
                    }
                }

                // a. 读取 JWT token
                val token =
                    getSharedPreferences(MINERADIO_TOKEN_PREFS, MODE_PRIVATE)
                        .getString(MINERADIO_TOKEN_KEY, "") ?: ""
                Log.i(TAG, "★ doUploadWallpaperSync: token length=${token.length}")
                if (token.isEmpty()) {
                    return """{"ok":false,"error":"未登录，请先登录"}"""
                }

                // ★ 新方案：优先直传蓝奏云（绕过 VPS，速度更快），失败时回退到 VPS 上传
                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "正在上传..."
                val mpkgUri = Uri.parse(mpkgPath)
                val coverUri = if (finalCoverPath.isNotEmpty()) Uri.parse(finalCoverPath) else null

                // 先尝试直传蓝奏云
                Log.i(TAG, "★ doUploadWallpaperSync: try uploadToLanzouDirect first")
                val lanzouResp =
                    uploadToLanzouDirect(
                        token = token,
                        title = title,
                        description = desc,
                        mpkgUri = mpkgUri,
                        mpkgName = mpkgName,
                        coverUri = coverUri,
                        coverName = finalCoverName,
                    )
                // 空字符串表示蓝奏云未启用，需要回退到 VPS 上传
                if (lanzouResp.isNotEmpty()) {
                    Log.i(TAG, "★ doUploadWallpaperSync: lanzou direct resp=${lanzouResp.take(200)}")
                    val lanzouJson = JSONObject(lanzouResp)
                    if (lanzouJson.optBoolean("ok", false)) {
                        com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "上传完成"
                        Log.i(TAG, "uploadWallpaper: lanzou direct success, title=$title")
                        return """{"ok":true}"""
                    } else {
                        val err = lanzouJson.optString("error", "上传失败")
                        // ★ 保留 auth_expired 标志，让 JS 端能检测并清除 localStorage 过期 token
                        val authExpired = lanzouJson.optBoolean("auth_expired", false)
                        return if (authExpired) {
                            """{"ok":false,"error":"$err","auth_expired":true}"""
                        } else {
                            """{"ok":false,"error":"$err"}"""
                        }
                    }
                }

                // 回退：直接 multipart 上传到 /api/wallpapers/upload（VPS 本地存储 + 后台蓝奏云同步）
                Log.i(TAG, "★ doUploadWallpaperSync: fallback to VPS multipart upload")
                com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "正在上传到服务器..."
                Log.i(TAG, "★ doUploadWallpaperSync: multipart upload to $WALLPAPER_SERVER_API_BASE/api/wallpapers/upload")
                val uploadResp =
                    multipartUploadWallpaper(
                        url = "$WALLPAPER_SERVER_API_BASE/api/wallpapers/upload",
                        token = token,
                        title = title,
                        description = desc,
                        mpkgUri = mpkgUri,
                        mpkgName = mpkgName,
                        coverUri = coverUri,
                        coverName = finalCoverName,
                    )
                Log.i(TAG, "★ doUploadWallpaperSync: upload response length=${uploadResp.length}")
                if (uploadResp.isEmpty()) {
                    return """{"ok":false,"error":"上传失败（网络错误）"}"""
                }
                val uploadJson = JSONObject(uploadResp)
                if (uploadJson.optBoolean("ok", false)) {
                    com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "上传完成"
                    Log.i(TAG, "uploadWallpaper: success, title=$title")
                    """{"ok":true}"""
                } else {
                    val err = uploadJson.optString("error", "上传失败")
                    """{"ok":false,"error":"$err"}"""
                }
            } catch (e: Exception) {
                Log.e(TAG, "uploadWallpaper failed", e)
                val err = (e.message ?: "未知错误").replace("\"", "'").replace("\n", " ")
                """{"ok":false,"error":"$err"}"""
            }
        }

        // ═══════════════════════════════════════════
        //  权限请求桥接
        // ═══════════════════════════════════════════

        @JavascriptInterface
        fun requestCameraPermission(): Boolean {
            if (ContextCompat.checkSelfPermission(
                    this@LandscapeWebActivity,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return true
            }
            runOnUiThread {
                ActivityCompat.requestPermissions(
                    this@LandscapeWebActivity,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST,
                )
            }
            return false
        }

        @JavascriptInterface
        fun requestRecordAudioPermission(): Boolean {
            if (ContextCompat.checkSelfPermission(
                    this@LandscapeWebActivity,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return true
            }
            runOnUiThread {
                ActivityCompat.requestPermissions(
                    this@LandscapeWebActivity,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    RECORD_AUDIO_PERMISSION_REQUEST,
                )
            }
            return false
        }

        // ★★ 音域回响：启动原生音频采集（Visualizer/AudioRecord + FFT）
        //   mode: "system"=系统混音(Visualizer), "mic"=麦克风(AudioRecord), "player"=播放器直采(fftProcessor)
        //   采集到的 64 段频谱通过 window.__feedAudio(bins) 回调给 JS
        @JavascriptInterface
        fun startSonicAudioCapture(mode: String): Boolean {
            val m =
                when (mode) {
                    "mic" -> "mic"
                    "player" -> "player"
                    else -> "system"
                }
            runOnUiThread { this@LandscapeWebActivity.startSonicAudioCapture(m) }
            return true
        }

        // ★★ 音域回响：停止原生音频采集
        @JavascriptInterface
        fun stopSonicAudioCapture(): Boolean {
            runOnUiThread { this@LandscapeWebActivity.stopSonicAudioCapture() }
            return true
        }

        // ═══════════════════════════════════════════
        //  输出接口：播放输出设备选择（独立于音域回响开关）
        // ═══════════════════════════════════════════

        /** 获取可用音频输出设备列表（JSON 格式：[{id, name, type}]） */
        @JavascriptInterface
        fun getAudioOutputDevices(): String =
            try {
                val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                val sb = StringBuilder("[")
                for (i in devices.indices) {
                    if (i > 0) sb.append(',')
                    val d = devices[i]
                    val name = describeDevice(d, i)
                    sb.append("{\"id\":\"").append(d.id).append("\",")
                    sb.append("\"name\":\"").append(name.replace("\"", "'")).append("\",")
                    sb.append("\"type\":\"").append(deviceTypeString(d.type)).append("\"}")
                }
                sb.append("]")
                sb.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getAudioOutputDevices failed", e)
                "[]"
            }

        /** 设置输出设备（通过 AudioOutputManager 转发给 PlaybackService 的 ExoPlayer） */
        @JavascriptInterface
        fun setAudioOutputDevice(deviceId: String): Boolean =
            try {
                val id = deviceId.trim()
                if (id == "__none__") {
                    // ★ 无设备：歌曲继续播放，但音频数据不输出到任何设备
                    //   - 竖屏 ExoPlayer：volume=0（继续播放推进进度，但无声音）
                    //   - 横屏 WebView audio：muted=true（不 pause，继续推进）
                    //   - 不暂停播放、不关闭程序，只是音频数据被丢弃
                    com.mineradio.app.audio.AudioOutputManager
                        .setPreferredDevice(null)
                    com.mineradio.app.audio.AudioOutputManager
                        .setOutputMuted(true)
                    // 横屏 WebView audio 静音（不 pause）
                    runOnUiThread {
                        try {
                            if (::webView.isInitialized) {
                                webView.evaluateJavascript(
                                    "try{if(typeof audio!=='undefined'&&audio){audio.muted=true;audio.volume=0;}}catch(e){}",
                                    null,
                                )
                            }
                        } catch (e: Exception) {
                        }
                    }
                    Log.i(TAG, "Audio output set to NONE (muted, playback continues)")
                } else if (id.isEmpty()) {
                    com.mineradio.app.audio.AudioOutputManager
                        .setPreferredDevice(null)
                    com.mineradio.app.audio.AudioOutputManager
                        .setOutputMuted(false)
                    // 取消静音
                    runOnUiThread {
                        try {
                            if (::webView.isInitialized) {
                                webView.evaluateJavascript(
                                    "try{if(typeof audio!=='undefined'&&audio){audio.muted=false;audio.volume=1;}}catch(e){}",
                                    null,
                                )
                            }
                        } catch (e: Exception) {
                        }
                    }
                    Log.i(TAG, "Audio output reset to default")
                } else {
                    val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                    val target = devices.firstOrNull { it.id.toString() == id }
                    if (target != null) {
                        com.mineradio.app.audio.AudioOutputManager
                            .setPreferredDevice(target)
                        com.mineradio.app.audio.AudioOutputManager
                            .setOutputMuted(false)
                        // 取消静音
                        runOnUiThread {
                            try {
                                if (::webView.isInitialized) {
                                    webView.evaluateJavascript(
                                        "try{if(typeof audio!=='undefined'&&audio){audio.muted=false;audio.volume=1;}}catch(e){}",
                                        null,
                                    )
                                }
                            } catch (e: Exception) {
                            }
                        }
                        Log.i(TAG, "Audio output set to: ${describeDevice(target, 0)} (id=$id)")
                    } else {
                        Log.w(TAG, "Audio output device not found: $id")
                        return false
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "setAudioOutputDevice failed", e)
                false
            }

        /** 将 AudioDeviceInfo.type 转为可读字符串 */
        private fun deviceTypeString(type: Int): String =
            when (type) {
                android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
                android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
                android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
                android.media.AudioDeviceInfo.TYPE_DOCK -> "dock"
                android.media.AudioDeviceInfo.TYPE_HDMI -> "hdmi"
                else -> "other"
            }

        /** 生成设备可读名称 */
        private fun describeDevice(
            d: android.media.AudioDeviceInfo,
            index: Int,
        ): String {
            val typeStr =
                when (d.type) {
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙(A2DP)"
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙(SCO)"
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机(带麦)"
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB耳机"
                    android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB设备"
                    android.media.AudioDeviceInfo.TYPE_DOCK -> "底座"
                    android.media.AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                    else -> "设备${index + 1}"
                }
            // 蓝牙设备尝试获取名称
            val productName =
                try {
                    d.productName
                } catch (e: Exception) {
                    null
                }
            return if (!productName.isNullOrBlank() && productName != "null") "$typeStr ($productName)" else typeStr
        }

        /** ★ 镜像监听：复制播放流到另一输出（实验功能） */
        @JavascriptInterface
        fun setAudioMirror(deviceId: String): Boolean =
            try {
                val id = deviceId.trim()
                if (id.isEmpty()) {
                    com.mineradio.app.audio.AudioOutputManager
                        .setMirrorDevice(null)
                    Log.i(TAG, "Audio mirror disabled")
                } else {
                    val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                    val target = devices.firstOrNull { it.id.toString() == id }
                    if (target != null) {
                        com.mineradio.app.audio.AudioOutputManager
                            .setMirrorDevice(target)
                        Log.i(TAG, "Audio mirror set to: ${describeDevice(target, 0)} (id=$id)")
                    } else {
                        Log.w(TAG, "Mirror device not found: $id")
                        return false
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "setAudioMirror failed", e)
                false
            }

        /** ★ 虚拟麦克风桥接（实验功能，安卓上提示性质） */
        @JavascriptInterface
        fun setVirtualMicBridge(enabled: Boolean): Boolean {
            Log.i(TAG, "Virtual mic bridge: $enabled (experimental, Android has no virtual sound card)")
            return true
        }

        // ═══════════════════════════════════════════
        //  手势推理桥接
        // ═══════════════════════════════════════════

        @JavascriptInterface
        fun startNativeHandGesture(): String =
            try {
                this@LandscapeWebActivity.startNativeHandGesture()
                "{\"ok\":true}"
            } catch (e: Exception) {
                Log.e(TAG, "startNativeHandGesture failed", e)
                "{\"ok\":false,\"error\":\"${e.message}\"}"
            }

        @JavascriptInterface
        fun stopNativeHandGesture(): String =
            try {
                this@LandscapeWebActivity.stopNativeHandGesture()
                "{\"ok\":true}"
            } catch (e: Exception) {
                Log.e(TAG, "stopNativeHandGesture failed", e)
                "{\"ok\":false,\"error\":\"${e.message}\"}"
            }

        @JavascriptInterface
        fun toggleGesturePreview(): String =
            try {
                if (!isNativeGestureActive || cameraProvider == null) {
                    this@LandscapeWebActivity.startNativeHandGesture()
                }
                runOnUiThread {
                    val newMode = !gesturePreviewActive
                    setupGestureCamera(includePreview = newMode)
                }
                "{\"ok\":true,\"preview\":${!gesturePreviewActive}}"
            } catch (e: Exception) {
                Log.e(TAG, "toggleGesturePreview failed", e)
                "{\"ok\":false,\"error\":\"${e.message}\"}"
            }

        // ═══════════════════════════════════════════
        //  通知栏媒体控制桥接
        // ═══════════════════════════════════════════

        @JavascriptInterface
        fun setMusicPlaying(playing: Boolean) {
            MediaNotificationState.updatePlayState(playing)
            // ★ 粒子模式：不启动 MusicPlaybackService（通知栏只有竖屏的）
            if (isParticleMode) {
                Log.d(TAG, "[Particle] setMusicPlaying($playing) skipped — no MusicPlaybackService")
                return
            }
            if (playing) {
                acquireWakeLock()
                // 启动前台 Service
                val intent = Intent(this@LandscapeWebActivity, MusicPlaybackService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        startForegroundService(intent)
                    } catch (e: Exception) {
                        Log.w(TAG, "startForegroundService failed: ${e.message}")
                        try {
                            startService(intent)
                        } catch (_: Exception) {
                        }
                    }
                } else {
                    startService(intent)
                }
            } else {
                releaseWakeLock()
            }
        }

        @JavascriptInterface
        fun updateMediaTrack(jsonStr: String) {
            try {
                val json = JSONObject(jsonStr)
                val title = json.optString("title", "Mineradio")
                val artist = json.optString("artist", "")
                val cover = json.optString("cover", "")
                val duration = json.optLong("duration", 0L)
                MediaNotificationState.updateMeta(title, artist, cover, duration)
                Log.d(TAG, "updateMediaTrack: $title - $artist | dur=${duration}ms")
            } catch (e: Exception) {
                Log.w(TAG, "updateMediaTrack failed: ${e.message}")
            }
        }

        @JavascriptInterface
        fun updateMediaMetadata(
            title: String,
            artist: String,
            cover: String,
        ) {
            try {
                MediaNotificationState.updateMeta(title, artist, cover, 0L)
            } catch (e: Exception) {
                Log.w(TAG, "updateMediaMetadata failed: ${e.message}")
            }
        }

        @JavascriptInterface
        fun updateMediaPosition(
            positionMs: Long,
            durationMs: Long,
        ) {
            try {
                MediaNotificationState.updatePosition(positionMs, durationMs)
            } catch (e: Exception) {
                Log.w(TAG, "updateMediaPosition failed: ${e.message}")
            }
        }

        @JavascriptInterface
        fun updateMediaPlayState(playing: Boolean) {
            try {
                MediaNotificationState.updatePlayState(playing)
            } catch (e: Exception) {
                Log.w(TAG, "updateMediaPlayState failed: ${e.message}")
            }
        }

        // ═══════════════════════════════════════════
        //  本地音乐扫描与文件管理器
        // ═══════════════════════════════════════════

        @JavascriptInterface
        fun scanLocalSongs(): String {
            return try {
                val songsDir = File(filesDir, "local_songs")
                if (!songsDir.exists()) return "[]"
                val files = songsDir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
                val result = JSONArray()
                for (f in files.sortedByDescending { it.lastModified() }.take(50)) {
                    val obj = JSONObject()
                    obj.put("fileName", f.name)
                    obj.put("size", f.length())
                    obj.put("lastModified", f.lastModified())
                    val name =
                        f.nameWithoutExtension
                            .replace(Regex("_\\d{13}$"), "")
                            .replace("_", " ")
                    obj.put("name", name)
                    result.put(obj)
                }
                result.toString()
            } catch (e: Exception) {
                "[]"
            }
        }

        @JavascriptInterface
        fun deleteLocalSong(fileName: String): String =
            try {
                val songsDir = File(filesDir, "local_songs")
                val safeName = fileName.replace(Regex("[\\\\/]"), "").replace("..", ".")
                val file = File(songsDir, safeName)
                if (file.exists() && file.delete()) "ok" else "error"
            } catch (e: Exception) {
                "error:${e.message}"
            }

        @JavascriptInterface
        fun getLocalSongsDir(): String = File(filesDir, "local_songs").absolutePath

        @JavascriptInterface
        fun getStorageRoots(): String {
            val list = JSONArray()
            try {
                val roots = mutableSetOf<String>()
                roots.add(Environment.getExternalStorageDirectory().absolutePath)
                val dirs = ContextCompat.getExternalFilesDirs(this@LandscapeWebActivity, null)
                for (d in dirs) {
                    if (d == null) continue
                    var p = d.absolutePath
                    val idx = p.indexOf("/Android/data/")
                    if (idx > 0) p = p.substring(0, idx)
                    if (p.isNotBlank()) roots.add(p)
                }
                for (r in roots) {
                    val f = File(r)
                    val obj = JSONObject()
                    obj.put("name", if (r.contains("emulated")) "内部存储" else f.name)
                    obj.put("path", r)
                    list.put(obj)
                }
            } catch (e: Exception) {
                Log.e(TAG, "getStorageRoots", e)
            }
            return list.toString()
        }

        @JavascriptInterface
        fun listDirectory(dirPath: String): String {
            val result = JSONObject()
            val dirs = JSONArray()
            val files = JSONArray()
            result.put("path", dirPath)
            try {
                val dir = File(dirPath)
                if (!dir.exists() || !dir.isDirectory) {
                    result.put("error", "目录不存在")
                    return result.toString()
                }
                val children = dir.listFiles() ?: emptyArray()
                val sorted = children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                for (f in sorted) {
                    val obj = JSONObject()
                    obj.put("name", f.name)
                    obj.put("path", f.absolutePath)
                    if (f.isDirectory) {
                        obj.put("type", "dir")
                        dirs.put(obj)
                    } else {
                        val ext = f.extension.lowercase()
                        if (ext in
                            setOf(
                                "mp3",
                                "flac",
                                "wav",
                                "ogg",
                                "m4a",
                                "wma",
                                "aac",
                                "opus",
                                "webm",
                                "ape",
                                "wv",
                                "aiff",
                            )
                        ) {
                            obj.put("type", "file")
                            obj.put("size", f.length())
                            files.put(obj)
                        }
                    }
                }
                result.put("dirs", dirs)
                result.put("files", files)
                val parent = dir.parentFile
                result.put("parent", if (parent != null) parent.absolutePath else "")
            } catch (e: Exception) {
                Log.e(TAG, "listDirectory", e)
                result.put("error", e.message ?: "读取失败")
            }
            return result.toString()
        }

        // ★ 列出指定文件夹下所有图片文件（递归），用于多图片背景
        //   支持 File 路径和 SAF tree URI 两种方式
        @JavascriptInterface
        fun listImageFiles(dirPath: String): String {
            val result = JSONObject()
            val images = JSONArray()
            result.put("path", dirPath)
            try {
                // ★ 如果以 content:// 开头，使用 SAF 方式遍历
                if (dirPath.startsWith("content://")) {
                    val treeUri = Uri.parse(dirPath)
                    val cr = contentResolver
                    val imageExts = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
                    val childrenUri =
                        DocumentsContract.buildChildDocumentsUriUsingTree(
                            treeUri,
                            DocumentsContract.getTreeDocumentId(treeUri),
                        )
                    val collected = mutableListOf<Pair<String, String>>() // (name, uri)
                    cr
                        .query(
                            childrenUri,
                            arrayOf(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_MIME_TYPE,
                            ),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            while (cursor.moveToNext()) {
                                val docId = cursor.getString(0) ?: continue
                                val name = cursor.getString(1) ?: continue
                                val mime = cursor.getString(2) ?: ""
                                val ext = name.substringAfterLast('.', "").lowercase()
                                // 只收集图片文件（MIME 或扩展名匹配）
                                if (mime.startsWith("image/") || ext in imageExts) {
                                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                    collected.add(Pair(name, fileUri.toString()))
                                }
                            }
                        }
                    collected.sortBy { it.first.lowercase() }
                    for ((name, uri) in collected) {
                        val obj = JSONObject()
                        obj.put("name", name)
                        obj.put("path", uri) // 使用 URI 作为路径
                        images.put(obj)
                    }
                    result.put("images", images)
                    result.put("count", collected.size)
                    return result.toString()
                }
                // ★ 普通文件路径方式
                val dir = File(dirPath)
                if (!dir.exists() || !dir.isDirectory) {
                    result.put("error", "目录不存在")
                    return result.toString()
                }
                val imageExts = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
                val queue = ArrayDeque<File>()
                queue.add(dir)
                val collected = mutableListOf<File>()
                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    val children = cur.listFiles() ?: continue
                    for (f in children) {
                        if (f.isDirectory) {
                            queue.add(f)
                        } else if (f.extension.lowercase() in imageExts) {
                            collected.add(f)
                        }
                    }
                }
                collected.sortBy { it.name.lowercase() }
                for (f in collected) {
                    val obj = JSONObject()
                    obj.put("name", f.name)
                    obj.put("path", f.absolutePath)
                    obj.put("size", f.length())
                    images.put(obj)
                }
                result.put("images", images)
                result.put("count", collected.size)
            } catch (e: Exception) {
                Log.e(TAG, "listImageFiles", e)
                result.put("error", e.message ?: "读取失败")
            }
            return result.toString()
        }

        // ★ 调用系统文件管理器选择文件夹（ACTION_OPEN_DOCUMENT_TREE）
        @JavascriptInterface
        fun pickImageFolder(callbackId: String) {
            runOnUiThread {
                folderPickCallbackId = callbackId
                try {
                    val intent =
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, FOLDER_PICK_REQUEST)
                } catch (e: Exception) {
                    Log.e(TAG, "pickImageFolder", e)
                    folderPickCallbackId = null
                    webView.post {
                        webView.evaluateJavascript(
                            "if(window._onFolderPicked)window._onFolderPicked('$callbackId','')",
                            null,
                        )
                    }
                }
            }
        }

        // ★ 扩展插件：调用系统文件选择器选择 .zip 压缩包
        @JavascriptInterface
        fun pickPluginZip(callbackId: String) {
            runOnUiThread {
                pluginZipCallbackId = callbackId
                try {
                    val intent =
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                            // ★ 同时支持 .zip 和 .mr 插件包
                            putExtra(
                                Intent.EXTRA_MIME_TYPES,
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                    "application/mr",
                                    "application/x-mr",
                                ),
                            )
                        }
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, PLUGIN_ZIP_REQUEST)
                } catch (e: Exception) {
                    Log.e(TAG, "pickPluginZip", e)
                    pluginZipCallbackId = null
                    webView.post {
                        webView.evaluateJavascript(
                            "if(window._onPluginZipPicked)window._onPluginZipPicked('$callbackId',{ok:false,message:'无法打开文件选择器'})",
                            null,
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun scanFolderPath(dirPath: String): String {
            val list = JSONArray()
            try {
                val dir = File(dirPath)
                if (!dir.exists() || !dir.isDirectory) return "[]"
                val audioExts =
                    setOf(
                        "mp3",
                        "flac",
                        "wav",
                        "ogg",
                        "m4a",
                        "wma",
                        "aac",
                        "opus",
                        "webm",
                        "ape",
                        "wv",
                        "aiff",
                    )
                val results = mutableListOf<File>()
                val queue = ArrayDeque<File>()
                queue.add(dir)
                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()
                    val children = current.listFiles() ?: continue
                    for (f in children) {
                        if (f.isDirectory) {
                            queue.add(f)
                        } else if (f.extension.lowercase() in audioExts) {
                            results.add(f)
                        }
                    }
                }
                results.sortByDescending { it.length() }
                for (f in results) {
                    val obj = JSONObject()
                    obj.put("name", f.name)
                    obj.put("path", f.absolutePath)
                    obj.put("size", f.length())
                    obj.put("lastModified", f.lastModified())
                    list.put(obj)
                }
            } catch (e: Exception) {
                Log.e(TAG, "scanFolderPath error", e)
            }
            return list.toString()
        }

        @JavascriptInterface
        fun importAndAnalyzeFile(filePath: String): String {
            return try {
                val src = File(filePath)
                if (!src.exists() || !src.isFile) return """{"error":"文件不存在"}"""
                val songsDir = File(filesDir, "local_songs")
                songsDir.mkdirs()
                val coversDir = File(filesDir, "local_covers")
                coversDir.mkdirs()
                val lyricsDir = File(filesDir, "local_lyrics")
                lyricsDir.mkdirs()
                val ext = src.extension.lowercase()
                val baseName =
                    src.nameWithoutExtension
                        .replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_\\-]"), "_")
                        .take(40)
                val safeName = "${baseName}_${System.currentTimeMillis()}.${ext.ifBlank { "mp3" }}"
                val destFile = File(songsDir, safeName)
                src.copyTo(destFile, overwrite = true)
                var duration = 0L
                var artist = ""
                var title = ""
                var coverName = ""
                var lyrics = ""
                var lyricName = ""
                try {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(destFile.absolutePath)
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                        title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                        // 提取嵌入封面图（同步竖屏 UI 的封面逻辑）
                        val embeddedPic = retriever.embeddedPicture
                        if (embeddedPic != null && embeddedPic.isNotEmpty()) {
                            coverName = "${baseName}_${System.currentTimeMillis()}.jpg"
                            val coverFile = File(coversDir, coverName)
                            coverFile.writeBytes(embeddedPic)
                        }
                        // 提取内嵌歌词（同步竖屏 UI 的歌词逻辑）
                        // METADATA_KEY_LYRICS 是隐藏 API 常量，值为 24
                        lyrics = retriever.extractMetadata(24) ?: ""
                        // 将内嵌歌词保存到独立文件（避免 JS 字符串转义问题）
                        if (lyrics.isNotBlank()) {
                            lyricName = "${baseName}_${System.currentTimeMillis()}.lrc"
                            val lyricFile = File(lyricsDir, lyricName)
                            lyricFile.writeText(lyrics)
                        }
                    } finally {
                        try {
                            retriever.release()
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
                val originalName = src.name
                webView.post {
                    webView.evaluateJavascript(
                        "if(window.__addLocalSongWithCachedFile)window.__addLocalSongWithCachedFile('$safeName','${originalName.replace(
                            "'",
                            "\\'",
                        )}','$safeName','$coverName','$lyricName')",
                        null,
                    )
                }
                val result = JSONObject()
                result.put("cachedFile", safeName)
                result.put("duration", duration)
                result.put("artist", artist)
                result.put("title", title)
                result.put("cover", coverName)
                result.put("lyric", lyricName)
                result.toString()
            } catch (e: Exception) {
                Log.e(TAG, "importAndAnalyzeFile error", e)
                """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
            }
        }

        /**
         * 获取本地歌曲封面 URL
         * @param coverName 封面文件名（在 local_covers 目录下）
         * @return 可直接用于 <img>/background-image 的 URL，无封面返回空串
         */
        @JavascriptInterface
        fun getLocalCoverUrl(coverName: String): String {
            if (coverName.isBlank()) return ""
            val coverFile = File(filesDir, "local_covers/$coverName")
            return if (coverFile.exists()) "/local-cover/$coverName" else ""
        }

        /**
         * 获取本地歌曲内嵌歌词 URL（用于 fetch 加载）
         * @param lyricName 歌词文件名（在 local_lyrics 目录下）
         * @return 可直接 fetch 的相对 URL，无歌词返回空串
         */
        @JavascriptInterface
        fun getLocalSongLyricUrl(lyricName: String): String {
            if (lyricName.isBlank()) return ""
            val lyricFile = File(filesDir, "local_lyrics/$lyricName")
            return if (lyricFile.exists()) "/local-lyric/$lyricName" else ""
        }

        /**
         * ★ 同步读取本地歌词文件文本内容（避免 JS 端异步 fetch 的时序问题）
         */
        @JavascriptInterface
        fun getLocalSongLyricText(lyricName: String): String {
            return try {
                if (lyricName.isBlank()) return ""
                val lyricFile = File(filesDir, "local_lyrics/$lyricName")
                if (lyricFile.exists()) lyricFile.readText() else ""
            } catch (e: Exception) {
                Log.w(TAG, "getLocalSongLyricText error: ${e.message}")
                ""
            }
        }

        /**
         * ★ 导入竖屏 UI Room 数据库中的歌曲到横屏 HTML 本地歌曲目录
         * 通过 MediaStore URI 复制文件到 local_songs，并完整提取封面（内嵌+albumId）和歌词（内嵌+ExtraInfoEntity）
         * 返回 JSON: {cachedFile, duration, artist, title, cover, lyric, displayName, mediaStoreId, albumId}
         */
        @JavascriptInterface
        fun importRoomSong(mediaStoreId: Long): String {
            return try {
                if (mediaStoreId <= 0L) return """{"error":"无效ID"}"""
                // 从 Room 数据库读取歌曲信息
                val songDao =
                    try {
                        GlobalContext.get().get<SongDao>()
                    } catch (e: Exception) {
                        null
                    }
                val song =
                    songDao?.getSongWithMediaStoreId(mediaStoreId)
                        ?: return """{"error":"歌曲不存在"}"""

                val songsDir = File(filesDir, "local_songs").apply { mkdirs() }
                val coversDir = File(filesDir, "local_covers").apply { mkdirs() }
                val lyricsDir = File(filesDir, "local_lyrics").apply { mkdirs() }

                val baseName =
                    (song.displayName ?: "song")
                        .replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_\\-]"), "_")
                        .take(40)
                val ext = (song.path.substringAfterLast('.', "mp3")).lowercase()
                val safeName = "${baseName}_${System.currentTimeMillis()}.${ext.ifBlank { "mp3" }}"
                val destFile = File(songsDir, safeName)

                // ★ 优先通过 MediaStore URI 复制文件（更可靠）
                val mediaUri = Uri.parse("content://media/external/audio/media/$mediaStoreId")
                var copied = false
                try {
                    contentResolver.openInputStream(mediaUri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                        copied = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "importRoomSong: MediaStore URI 复制失败: ${e.message}")
                }
                // ★ 回退到文件路径
                if (!copied && song.path.isNotBlank()) {
                    val srcFile = File(song.path)
                    if (srcFile.exists() && srcFile.isFile) {
                        srcFile.copyTo(destFile, overwrite = true)
                        copied = true
                    }
                }
                if (!copied) return """{"error":"无法读取文件"}"""

                var duration = song.duration
                var artist = song.artist ?: ""
                var title = song.displayName ?: ""
                var coverName = ""
                var lyricName = ""

                // ★ 用 MediaMetadataRetriever 提取内嵌封面和歌词
                try {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(destFile.absolutePath)
                        if (duration <= 0L) {
                            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        }
                        if (artist.isBlank()) {
                            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                        }
                        if (title.isBlank()) {
                            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                        }
                        // 提取内嵌封面
                        val embeddedPic = retriever.embeddedPicture
                        if (embeddedPic != null && embeddedPic.isNotEmpty()) {
                            coverName = "${baseName}_${System.currentTimeMillis()}.jpg"
                            File(coversDir, coverName).writeBytes(embeddedPic)
                        }
                        // 提取内嵌歌词（METADATA_KEY_LYRICS = 24）
                        val embeddedLyrics = retriever.extractMetadata(24) ?: ""
                        if (embeddedLyrics.isNotBlank()) {
                            lyricName = "${baseName}_${System.currentTimeMillis()}.lrc"
                            File(lyricsDir, lyricName).writeText(embeddedLyrics)
                        }
                    } finally {
                        try {
                            retriever.release()
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }

                // ★ 如果没有内嵌封面，通过 albumId 从 MediaStore 获取封面
                if (coverName.isBlank() && song.albumId > 0L) {
                    try {
                        val coverUri = Uri.parse("content://media/external/audio/albumart/${song.albumId}")
                        contentResolver.openInputStream(coverUri)?.use { input ->
                            coverName = "${baseName}_${System.currentTimeMillis()}.jpg"
                            val coverFile = File(coversDir, coverName)
                            coverFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "importRoomSong: 获取 albumId 封面失败: ${e.message}")
                    }
                }

                // ★ 如果没有内嵌歌词，从 ExtraInfoEntity 读取歌词
                if (lyricName.isBlank()) {
                    try {
                        val extraDao = GlobalContext.get().get<ExtraInfoDao>()
                        val entity = extraDao.getLyricWithMediaId(mediaStoreId)
                        if (entity != null && !entity.lyrics.isNullOrBlank()) {
                            lyricName = "${baseName}_${System.currentTimeMillis()}.lrc"
                            File(lyricsDir, lyricName).writeText(entity.lyrics)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "importRoomSong: 读取 ExtraInfo 歌词失败: ${e.message}")
                    }
                }

                val result = JSONObject()
                result.put("cachedFile", safeName)
                result.put("duration", duration)
                result.put("artist", artist)
                result.put("title", title)
                result.put("cover", coverName)
                result.put("lyric", lyricName)
                result.put("displayName", song.displayName ?: "")
                result.put("mediaStoreId", mediaStoreId)
                result.put("albumId", song.albumId)
                result.put("like", song.like)
                result.toString()
            } catch (e: Exception) {
                Log.e(TAG, "importRoomSong error", e)
                """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
            }
        }

        // ★ 粒子模式专用：获取竖屏当前播放歌曲信息（不复制音频文件）
        @JavascriptInterface
        fun getPortraitSongInfo(): String {
            return try {
                val player = GlobalContext.get().get<com.mineradio.app.player.api.IMusicPlayer>()
                val mediaItem =
                    player.currentMediaItem.value
                        ?: return """{"error":"no media"}"""
                val mediaStoreId = mediaItem.mediaId.toLongOrNull() ?: -1L
                if (mediaStoreId <= 0L) return """{"error":"invalid mediaId"}"""

                val songDao = GlobalContext.get().get<SongDao>()
                val song =
                    songDao.getSongWithMediaStoreId(mediaStoreId)
                        ?: return """{"error":"song not found"}"""

                val coversDir = File(filesDir, "local_covers").apply { mkdirs() }
                val baseName =
                    (song.displayName ?: "song")
                        .replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_\\-]"), "_")
                        .take(40)

                var coverName = ""
                var lyricText = ""

                // 提取内嵌封面和歌词（从源文件，不复制音频）
                try {
                    val retriever = MediaMetadataRetriever()
                    try {
                        if (song.path.isNotBlank()) {
                            retriever.setDataSource(song.path)
                        } else {
                            val mediaUri = Uri.parse("content://media/external/audio/media/$mediaStoreId")
                            retriever.setDataSource(this@LandscapeWebActivity, mediaUri)
                        }
                        val embeddedPic = retriever.embeddedPicture
                        if (embeddedPic != null && embeddedPic.isNotEmpty()) {
                            coverName = "${baseName}_portrait_${System.currentTimeMillis()}.jpg"
                            File(coversDir, coverName).writeBytes(embeddedPic)
                        }
                        val embeddedLyrics = retriever.extractMetadata(24) ?: ""
                        if (embeddedLyrics.isNotBlank()) lyricText = embeddedLyrics
                    } finally {
                        try {
                            retriever.release()
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }

                // albumId 封面回退
                if (coverName.isBlank() && song.albumId > 0L) {
                    try {
                        val coverUri = Uri.parse("content://media/external/audio/albumart/${song.albumId}")
                        contentResolver.openInputStream(coverUri)?.use { input ->
                            coverName = "${baseName}_portrait_${System.currentTimeMillis()}.jpg"
                            File(coversDir, coverName).outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (_: Exception) {
                    }
                }

                // ExtraInfo 歌词回退
                if (lyricText.isBlank()) {
                    try {
                        val extraDao = GlobalContext.get().get<ExtraInfoDao>()
                        val entity = extraDao.getLyricWithMediaId(mediaStoreId)
                        if (entity != null && !entity.lyrics.isNullOrBlank()) lyricText = entity.lyrics
                    } catch (_: Exception) {
                    }
                }

                JSONObject()
                    .apply {
                        put("mediaStoreId", mediaStoreId)
                        put("title", song.displayName ?: "")
                        put("artist", song.artist ?: "")
                        put("cover", coverName)
                        put("lyric", lyricText)
                        put("duration", song.duration)
                    }.toString()
            } catch (e: Exception) {
                """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
            }
        }

        // ★ 粒子模式专用：获取竖屏当前播放状态（位置/播放状态）
        //   注意：@JavascriptInterface 在 WebView 后台线程调用，
        //   player.currentPosition 必须在主线程访问，否则返回 0。
        @JavascriptInterface
        fun getPortraitPlaybackState(): String =
            try {
                val player = GlobalContext.get().get<com.mineradio.app.player.api.IMusicPlayer>()
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val latch = java.util.concurrent.CountDownLatch(1)
                val result = arrayOfNulls<Any>(4) // position, isPlaying, duration, mediaStoreId
                mainHandler.post {
                    try {
                        result[0] = player.currentPosition
                        result[1] = player.isPlaying.value
                        val mediaItem = player.currentMediaItem.value
                        result[2] = mediaItem?.mediaMetadata?.durationMs ?: 0L
                        result[3] = mediaItem?.mediaId?.toLongOrNull() ?: -1L
                    } catch (e: Exception) {
                        Log.w(TAG, "[Particle] getPortraitPlaybackState main error: ${e.message}")
                    } finally {
                        latch.countDown()
                    }
                }
                if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    Log.w(TAG, "[Particle] getPortraitPlaybackState main thread timeout")
                }
                val position = (result[0] as? Long) ?: 0L
                val isPlaying = (result[1] as? Boolean) ?: false
                val duration = (result[2] as? Long) ?: 0L
                val mediaStoreId = (result[3] as? Long) ?: -1L
                JSONObject()
                    .apply {
                        put("position", position)
                        put("duration", duration)
                        put("isPlaying", isPlaying)
                        put("mediaStoreId", mediaStoreId)
                    }.toString()
            } catch (e: Exception) {
                """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
            }

        @JavascriptInterface
        fun analyzeAudioFilePath(filePath: String): String {
            try {
                val file = File(filePath)
                if (!file.exists()) return """{"duration":0,"artist":"","title":""}"""
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(filePath)
                    val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                    val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                    return """{"duration":$dur,"artist":${JSONObject.quote(artist)},"title":${JSONObject.quote(title)}}"""
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                return """{"duration":0,"artist":"","title":""}"""
            }
        }

        // ═══════════════════════════════════════════
        //  ★ 竖屏 UI Room 数据库桥接（横屏 HTML 共享竖屏本地歌曲/歌单/歌词）
        // ═══════════════════════════════════════════

        /** 通过 Koin 全局上下文获取 SongDao（可能返回 null） */
        private fun songDao(): SongDao? =
            try {
                GlobalContext.get().get<SongDao>()
            } catch (e: Exception) {
                Log.w(TAG, "SongDao 获取失败: ${e.message}")
                null
            }

        private fun playlistDao(): PlaylistDao? =
            try {
                GlobalContext.get().get<PlaylistDao>()
            } catch (e: Exception) {
                Log.w(TAG, "PlaylistDao 获取失败: ${e.message}")
                null
            }

        private fun extraInfoDao(): ExtraInfoDao? =
            try {
                GlobalContext.get().get<ExtraInfoDao>()
            } catch (e: Exception) {
                Log.w(TAG, "ExtraInfoDao 获取失败: ${e.message}")
                null
            }

        /**
         * 读取竖屏 UI Room 数据库中所有歌曲（含 mediaStoreId, displayName, artist, duration, albumId, path, like）
         * 返回 JSON 数组字符串
         */
        @JavascriptInterface
        fun getRoomSongs(): String {
            return try {
                val dao = songDao() ?: return "[]"
                val songs = dao.getAllSync()
                val arr = JSONArray()
                for (s in songs) {
                    val obj = JSONObject()
                    obj.put("mediaStoreId", s.mediaStoreId)
                    obj.put("displayName", s.displayName)
                    obj.put("artist", s.artist ?: "")
                    obj.put("album", s.album ?: "")
                    obj.put("albumId", s.albumId)
                    obj.put("duration", s.duration)
                    obj.put("path", s.path ?: "")
                    obj.put("mimeType", s.mimeType ?: "")
                    obj.put("like", s.like)
                    obj.put("bitRate", s.bitRate)
                    obj.put("sampleRate", s.sampleRate)
                    arr.put(obj)
                }
                arr.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getRoomSongs error", e)
                "[]"
            }
        }

        /**
         * 读取竖屏 UI Room 数据库中所有歌单
         * 返回 JSON 数组字符串：[{playlistId, playlistName, createTimestamp, playTimes}]
         */
        @JavascriptInterface
        fun getRoomPlaylists(): String {
            return try {
                val dao = playlistDao() ?: return "[]"
                val playlists = dao.getPlaylistsWithSongs()
                val arr = JSONArray()
                for (p in playlists) {
                    val entity: PlaylistEntity = p.playlist
                    val obj = JSONObject()
                    obj.put("playlistId", entity.playlistId ?: 0L)
                    obj.put("playlistName", entity.playlistName ?: "")
                    obj.put("createTimestamp", entity.createTimestamp)
                    obj.put("playTimes", entity.playTimes)
                    obj.put("songCount", p.songs.size)
                    // 前4个专辑ID用于封面马赛克
                    val albumIds = JSONArray()
                    p.songs.take(4).forEach { song ->
                        albumIds.put(song.albumId)
                    }
                    obj.put("coverAlbumIds", albumIds)
                    arr.put(obj)
                }
                arr.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getRoomPlaylists error", e)
                "[]"
            }
        }

        /**
         * 读取指定歌单内的所有歌曲
         */
        @JavascriptInterface
        fun getRoomPlaylistSongs(playlistId: Long): String {
            return try {
                val dao = playlistDao() ?: return "[]"
                val songs = dao.getSongsByPlaylistId(playlistId)
                val arr = JSONArray()
                for (s in songs) {
                    val obj = JSONObject()
                    obj.put("mediaStoreId", s.mediaStoreId)
                    obj.put("displayName", s.displayName)
                    obj.put("artist", s.artist ?: "")
                    obj.put("album", s.album ?: "")
                    obj.put("albumId", s.albumId)
                    obj.put("duration", s.duration)
                    obj.put("path", s.path ?: "")
                    obj.put("like", s.like)
                    arr.put(obj)
                }
                arr.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getRoomPlaylistSongs error", e)
                "[]"
            }
        }

        /**
         * 获取竖屏 UI 歌曲封面 URL（通过 albumId 构造 content:// URI，由 MineradioServer 代理）
         * @param albumId 专辑 ID
         * @return /room-cover/{albumId} 相对 URL，无专辑返回空串
         */
        @JavascriptInterface
        fun getRoomCoverUrl(albumId: Long): String {
            if (albumId <= 0L) return ""
            return "/room-cover/$albumId"
        }

        /**
         * 获取竖屏 UI 歌曲文件播放 URL（通过 mediaStoreId 找到 path，由 MineradioServer 服务）
         * @param mediaStoreId MediaStore ID
         * @return /room-song/{mediaStoreId} 相对 URL，找不到返回空串
         */
        @JavascriptInterface
        fun getRoomSongUrl(mediaStoreId: Long): String {
            if (mediaStoreId <= 0L) return ""
            val dao = songDao() ?: return ""
            val song = dao.getSongWithMediaStoreId(mediaStoreId) ?: return ""
            if (song.path.isBlank()) return ""
            return "/room-song/$mediaStoreId"
        }

        /**
         * 读取竖屏 UI 中指定歌曲的歌词（ExtraInfoEntity.lyrics）
         * @param mediaStoreId 即 mediaId（与 SongEntity.mediaStoreId 一致）
         * @return 歌词文本（LRC/YRC），无歌词返回空串
         */
        @JavascriptInterface
        fun getRoomLyric(mediaStoreId: Long): String {
            return try {
                val dao = extraInfoDao() ?: return ""
                val entity: ExtraInfoEntity? = dao.getLyricWithMediaId(mediaStoreId)
                entity?.lyrics ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "getRoomLyric error", e)
                ""
            }
        }

        /**
         * 读取竖屏 UI 中指定歌曲的歌词封面（ExtraInfoEntity.cover，通常是歌词背景图 URL）
         * @param mediaStoreId
         * @return 封面 URL 字符串，无返回空串
         */
        @JavascriptInterface
        fun getRoomLyricCover(mediaStoreId: Long): String {
            return try {
                val dao = extraInfoDao() ?: return ""
                val entity: ExtraInfoEntity? = dao.getLyricWithMediaId(mediaStoreId)
                entity?.cover ?: ""
            } catch (e: Exception) {
                ""
            }
        }

        /**
         * ★ 读取竖屏 UI 中指定歌曲的歌词延迟
         */
        @JavascriptInterface
        fun getRoomLyricDelay(mediaStoreId: Long): Long {
            return try {
                val dao = extraInfoDao() ?: return 0L
                val entity: ExtraInfoEntity? = dao.getLyricWithMediaId(mediaStoreId)
                entity?.delay ?: 0L
            } catch (e: Exception) {
                0L
            }
        }

        /**
         * ★ 在线搜索歌词（使用与竖屏 UI 相同的 API）
         * 同步返回第一首匹配的歌词文本，无结果返回空串
         */
        @JavascriptInterface
        fun searchLyricOnline(
            songName: String,
            artist: String,
        ): String {
            return try {
                val title = songName.trim()
                if (title.isBlank()) return ""
                val useCases =
                    try {
                        GlobalContext.get().get<com.mineradio.app.feature.lyrics.domain.LyricsUseCases>()
                    } catch (e: Exception) {
                        Log.w(TAG, "LyricsUseCases 获取失败: ${e.message}")
                        return ""
                    }
                // ★ 用 runBlocking 同步调用 suspend 函数
                val results =
                    kotlinx.coroutines.runBlocking {
                        useCases.searchAllLyrics(title)
                    }
                if (results.isNullOrEmpty()) return ""
                // 优先匹配 artist，否则取第一个
                val match =
                    if (!artist.isBlank()) {
                        results.find { it.artist.contains(artist, ignoreCase = true) || artist.contains(it.artist, ignoreCase = true) }
                            ?: results.first()
                    } else {
                        results.first()
                    }
                match.lyrics
            } catch (e: Exception) {
                Log.e(TAG, "searchLyricOnline error", e)
                ""
            }
        }

        // ════════════════════════════════════════════════════════════
        // 桌面歌词（Desktop Lyric）桥接方法
        // ════════════════════════════════════════════════════════════

        @JavascriptInterface
        fun toggleDesktopLyric(on: Boolean) {
            runOnUiThread {
                if (on) {
                    // 检查悬浮窗权限
                    if (!Settings.canDrawOverlays(this@LandscapeWebActivity)) {
                        // 无权限 → 记录待开启状态 + 跳转授权页 + 通知 JS
                        pendingDesktopLyricEnable = true
                        try {
                            val intent =
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + packageName),
                                )
                            startActivityForResult(intent, 0x1001)
                        } catch (_: Exception) {
                        }
                        webView.evaluateJavascript(
                            "if(window._onDesktopLyricPermissionGranted)window._onDesktopLyricPermissionGranted(false);",
                            null,
                        )
                        return@runOnUiThread
                    }
                    // 有权限 → 显示浮窗
                    showDesktopLyricView()
                    webView.evaluateJavascript(
                        "if(window._onDesktopLyricPermissionGranted)window._onDesktopLyricPermissionGranted(true);",
                        null,
                    )
                } else {
                    pendingDesktopLyricEnable = false
                    hideDesktopLyricView()
                }
            }
        }

        /**
         * 接收完整桌面歌词状态 JSON（与桌面版 desktopLyricsPayload 一致），
         * 在 Kotlin 端解析后转调 DesktopLyricsManager 的 updateLyric / updateStyle / setPosition。
         *
         * Java TextView 版只关心：text / colors.primary / colors.highlight / size / opacity / y
         * 其他字段（粒子、cinema、beatMap、motion 等）由 HTML 版使用，这里忽略。
         */
        @JavascriptInterface
        fun updateDesktopLyricsState(json: String) {
            runOnUiThread {
                val mgr = desktopLyricsManager ?: return@runOnUiThread
                try {
                    val obj = JSONObject(json)
                    val enabled = obj.optBoolean("enabled", false)
                    if (!enabled) {
                        // 关闭 → 隐藏浮窗
                        if (mgr.isShowing()) mgr.hide()
                        return@runOnUiThread
                    }
                    // 确保浮窗已显示
                    if (!mgr.isShowing()) mgr.show()

                    // 1. 文本（默认空，避免未播放时显示软件名占位）
                    val text = obj.optString("text", "")
                    val highlightFollow = obj.optBoolean("highlightFollow", false)
                    // ★ 进度（0-1，用于"高亮跟随"逐字高亮已唱过部分）
                    val progress = obj.optDouble("progress", 0.0).toFloat()
                    // ★ 暂停/停止时清空文字（不显示歌词），但保留浮窗
                    val playing = obj.optBoolean("playing", true)
                    val displayText = if (playing) text else ""
                    mgr.updateLyric(displayText, progress, highlightFollow, false)

                    // 2. 样式：颜色 + 字体大小 + 透明度
                    val colors = obj.optJSONObject("colors")
                    val primary = colors?.optString("primary", "#f6fdff") ?: "#f6fdff"
                    val highlight = colors?.optString("highlight", "#fff0b8") ?: "#fff0b8"
                    val size = obj.optDouble("size", 1.0)
                    val opacity = obj.optDouble("opacity", 0.92).toFloat()
                    // ★ 字体大小整体比例缩小至 40%：原 HTML 公式 baseFontSize = 58 * size（CSS px）
                    //   这里乘 0.4 后再乘 density 转为物理 PX
                    val density = resources.displayMetrics.density
                    val fontSizePx = (58.0 * size * 0.4 * density).toInt()
                    mgr.updateStyle(primary, highlight, fontSizePx, opacity)

                    // 3. 位置：y 是 0-1 屏幕高度比例，转为相对屏幕中心的偏移
                    val yFrac = obj.optDouble("y", 0.5)
                    val screenH = resources.displayMetrics.heightPixels
                    val yOffset = ((yFrac - 0.5) * screenH).toInt()
                    mgr.setPosition(0, yOffset)

                    // 4. ★ 锁定状态：clickThrough=true 时锁定（点击穿透，不可拖动）
                    val clickThrough = obj.optBoolean("clickThrough", false)
                    mgr.setLocked(clickThrough)
                } catch (e: Exception) {
                    Log.w(TAG, "updateDesktopLyricsState parse failed: ${e.message}")
                }
            }
        }

        /**
         * 高频进度推送（0-1 比例 + 时长秒数）
         * ★ 用于"高亮跟随"平滑逐字高亮：转调 DesktopLyricsManager.updateProgress
         */
        @JavascriptInterface
        fun updateDesktopLyricProgressFrac(
            frac: Double,
            spanSec: Double,
        ) {
            runOnUiThread {
                desktopLyricsManager?.updateProgress(frac.toFloat())
            }
        }

        // ═══════════════════════════════════════════
        //  壁纸管理桥接（壁纸模式开关下方按钮调用）
        // ═══════════════════════════════════════════

        /** 返回当前壁纸信息（JSON 字符串） */
        @JavascriptInterface
        fun getWallpaperInfo(): String =
            try {
                val wm =
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                val current = wm.getCurrentWallpaper()
                val appBg = wm.getAppBackgroundWallpaper()
                val json = JSONObject()
                json.put("current", current?.name ?: "")
                json.put("currentId", current?.id ?: "")
                json.put("appBg", appBg?.name ?: "")
                json.put("systemBg", current?.name ?: "")
                json.put("libraryCount", wm.getWallpaperList().size)
                val libraryArray = JSONArray()
                wm.getWallpaperList().forEach { w ->
                    val item = JSONObject()
                    item.put("id", w.id)
                    item.put("name", w.name)
                    item.put("type", w.wallpaperType)
                    // 检测壁纸实际尺寸（用于前端"手机壁纸"分类判定：高>宽）
                    val (width, height) = detectWallpaperSize(w)
                    item.put("width", width)
                    item.put("height", height)
                    libraryArray.put(item)
                }
                json.put("library", libraryArray)
                json.toString()
            } catch (e: Exception) {
                Log.e("Wallpaper", "getWallpaperInfo failed", e)
                "{}"
            }

        /**
         * 检测壁纸文件的实际尺寸
         * - 图片：用 BitmapFactory 解码边界
         * - 视频：用 MediaMetadataRetriever 读取 METADATA_KEY_VIDEO_WIDTH/HEIGHT
         * - 网页/失败：返回 (0, 0)
         */
        private fun detectWallpaperSize(w: com.mineradio.app.wallpaper.WallpaperEntity): Pair<Int, Int> {
            return try {
                val type = w.wallpaperType
                val path = w.getRealPath(this@LandscapeWebActivity)
                val file = java.io.File(path)
                if (!file.exists()) return Pair(0, 0)
                when (type) {
                    com.mineradio.app.wallpaper.WallpaperEntity.TYPE_IMAGE -> {
                        val opts = BitmapFactory.Options()
                        opts.inJustDecodeBounds = true
                        BitmapFactory.decodeFile(path, opts)
                        Pair(opts.outWidth, opts.outHeight)
                    }
                    com.mineradio.app.wallpaper.WallpaperEntity.TYPE_VIDEO -> {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(path)
                            val vw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            val vh = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            Pair(vw, vh)
                        } finally {
                            try {
                                retriever.release()
                            } catch (_: Exception) {
                            }
                        }
                    }
                    else -> Pair(0, 0)
                }
            } catch (e: Exception) {
                Log.w("Wallpaper", "detectWallpaperSize failed for ${w.id}: ${e.message}")
                Pair(0, 0)
            }
        }

        /** 选择壁纸为当前壁纸 */
        @JavascriptInterface
        fun wallpaperSelect(id: String): String =
            try {
                val wm =
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                val list = wm.getWallpaperList()
                val found = list.find { it.id == id }
                if (found != null) {
                    wm.setCurrentWallpaper(found)
                    found.name ?: "已选择"
                } else {
                    ""
                }
            } catch (e: Exception) {
                Log.e("Wallpaper", "wallpaperSelect failed", e)
                ""
            }

        /**
         * ★ 获取壁纸的本地文件路径（用于 JS 端读取文件内容设为应用背景）
         * 返回: file:///sdcard/Documents/Mineradio/Wallpaper/<id>/<path>
         *       或网络 URL（http://...）
         *       失败返回空字符串
         */
        @JavascriptInterface
        fun wallpaperGetFilePath(id: String): String =
            try {
                val wm =
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                val list = wm.getWallpaperList()
                val found = list.find { it.id == id }
                if (found != null) {
                    val realPath = found.getRealPath(this@LandscapeWebActivity)
                    if (realPath.startsWith("http")) {
                        realPath
                    } else {
                        "file://$realPath"
                    }
                } else {
                    ""
                }
            } catch (e: Exception) {
                Log.e("Wallpaper", "wallpaperGetFilePath failed", e)
                ""
            }

        /** 删除壁纸 */
        @JavascriptInterface
        fun wallpaperDelete(id: String) {
            try {
                com.mineradio.app.wallpaper.WallpaperManager
                    .get(this@LandscapeWebActivity)
                    .removeWallpaper(id)
            } catch (e: Exception) {
                Log.e("Wallpaper", "wallpaperDelete failed", e)
            }
        }

        /** 导入图片壁纸（打开文件选择器） */
        @JavascriptInterface
        fun wallpaperImportImage() {
            runOnUiThread {
                try {
                    val intent =
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "image/*"
                        }
                    startActivityForResult(intent, WALLPAPER_IMAGE_REQUEST)
                } catch (e: Exception) {
                    Log.e("Wallpaper", "wallpaperImportImage failed", e)
                }
            }
        }

        /** 导入视频壁纸（打开文件选择器） */
        @JavascriptInterface
        fun wallpaperImportVideo() {
            runOnUiThread {
                try {
                    val intent =
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "video/*"
                        }
                    startActivityForResult(intent, WALLPAPER_VIDEO_REQUEST)
                } catch (e: Exception) {
                    Log.e("Wallpaper", "wallpaperImportVideo failed", e)
                }
            }
        }

        /** 统一导入壁纸（打开文件选择器，自动识别图片/视频/HTML/MPKG）
         *  ★★ 支持 EXTRA_ALLOW_MULTIPLE 多选导入
         */
        @JavascriptInterface
        fun wallpaperImport() {
            runOnUiThread {
                try {
                    val intent =
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    startActivityForResult(intent, WALLPAPER_UNIFIED_REQUEST)
                } catch (e: Exception) {
                    Log.e("Wallpaper", "wallpaperImport failed", e)
                }
            }
        }

        /** 为壁纸文件生成 content:// URI（解决 WebView 禁止 file:// 加载本地资源） */
        private fun getWallpaperContentUri(filePath: String): String =
            try {
                val file = java.io.File(filePath)
                if (!file.exists()) return ""
                val authority = "$packageName.fileprovider"
                val uri =
                    androidx.core.content.FileProvider.getUriForFile(
                        this@LandscapeWebActivity,
                        authority,
                        file,
                    )
                Log.d("Wallpaper", "getWallpaperContentUri: $filePath -> $uri")
                uri.toString()
            } catch (e: Exception) {
                Log.e("Wallpaper", "getWallpaperContentUri failed: $filePath", e)
                ""
            }

        /** 设为应用背景壁纸，返回 JSON 字符串：{name, path, fileUri, type, mimeType} */
        @JavascriptInterface
        fun wallpaperSetAppBg(): String =
            try {
                val wm =
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                // ★ 修复 Bug #1：优先使用用户上次保存的应用背景，避免重启后变成壁纸库第一个
                //   回退链：getAppBackgroundWallpaper（用户上次保存）→ getCurrentWallpaper（系统桌面壁纸）
                //   ★ 禁止回退到壁纸库第一个：恢复/清除背景后重启不应自动选中任何壁纸
                val current =
                    wm.getAppBackgroundWallpaper()
                        ?: wm.getCurrentWallpaper()
                if (current != null) {
                    wm.setAppBackgroundWallpaper(current)
                    val realPath = current.getRealPath(this@LandscapeWebActivity)
                    val file = java.io.File(realPath)
                    val json = JSONObject()
                    json.put("name", current.name ?: "")
                    json.put("path", realPath)
                    // ★ 生成 content:// URI（WebView 禁止 file:// 协议）
                    val contentUriBg = if (file.exists() && !realPath.startsWith("http")) getWallpaperContentUri(realPath) else ""
                    json.put("uri", contentUriBg)
                    json.put("fileUri", contentUriBg)
                    json.put("exists", file.exists())
                    json.put("type", current.wallpaperType)
                    json.put("mimeType", getWallpaperMimeType(current.wallpaperType))
                    Log.d("Wallpaper", "wallpaperSetAppBg: name=${current.name}, path=$realPath, exists=${file.exists()}")
                    // ★ MPKG 类型：启动原生背景渲染层
                    if (current.wallpaperType == com.mineradio.app.wallpaper.WallpaperEntity.TYPE_MPKG_WEBGL) {
                        this@LandscapeWebActivity.startMpkgBackground(realPath)
                    } else {
                        this@LandscapeWebActivity.stopMpkgBackground()
                    }
                    json.toString()
                } else {
                    Log.w("Wallpaper", "wallpaperSetAppBg: no current wallpaper and library is empty")
                    ""
                }
            } catch (e: Exception) {
                Log.e("Wallpaper", "wallpaperSetAppBg failed", e)
                ""
            }

        /** 读取本地文件为 base64（用于前端加载本地壁纸文件） */
        @JavascriptInterface
        fun wallpaperReadFile(path: String): String =
            try {
                val file: java.io.File =
                    when {
                        path.startsWith("file://") -> java.io.File(path.removePrefix("file://"))
                        path.startsWith("/") -> java.io.File(path)
                        else -> java.io.File(this@LandscapeWebActivity.filesDir, path)
                    }
                if (!file.exists() || !file.isFile) return ""
                val bytes = file.readBytes()
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e("Wallpaper", "wallpaperReadFile failed", e)
                ""
            }

        /** 根据壁纸类型获取 MIME 类型 */
        private fun getWallpaperMimeType(type: Int): String =
            when (type) {
                com.mineradio.app.wallpaper.WallpaperEntity.TYPE_VIDEO -> "video/mp4"
                com.mineradio.app.wallpaper.WallpaperEntity.TYPE_IMAGE -> "image/jpeg"
                com.mineradio.app.wallpaper.WallpaperEntity.TYPE_HTML -> "text/html"
                else -> "application/octet-stream"
            }

        /** 设为系统桌面壁纸 */
        @JavascriptInterface
        fun wallpaperSetSystem(): String =
            try {
                val wm =
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                val current = wm.getCurrentWallpaper()
                if (current != null) {
                    val result =
                        com.mineradio.app.wallpaper.WallpaperHelper.applyAsSystemWallpaper(
                            this@LandscapeWebActivity,
                            current,
                        )
                    when (result) {
                        com.mineradio.app.wallpaper.WallpaperHelper.ApplyResult.SuccessDirect ->
                            current.name ?: "已设置"
                        com.mineradio.app.wallpaper.WallpaperHelper.ApplyResult.NeedSystemActivate ->
                            "NEED_ACTIVATE"
                        else -> ""
                    }
                } else {
                    val list = wm.getWallpaperList()
                    if (list.isNotEmpty()) {
                        val result =
                            com.mineradio.app.wallpaper.WallpaperHelper.applyAsSystemWallpaper(
                                this@LandscapeWebActivity,
                                list[0],
                            )
                        when (result) {
                            com.mineradio.app.wallpaper.WallpaperHelper.ApplyResult.SuccessDirect ->
                                list[0].name ?: "已设置"
                            com.mineradio.app.wallpaper.WallpaperHelper.ApplyResult.NeedSystemActivate ->
                                "NEED_ACTIVATE"
                            else -> ""
                        }
                    } else {
                        ""
                    }
                }
            } catch (e: Exception) {
                Log.e("Wallpaper", "wallpaperSetSystem failed", e)
                ""
            }

        /** 清除应用背景壁纸 */
        @JavascriptInterface
        fun wallpaperClearAppBg() {
            try {
                com.mineradio.app.wallpaper.WallpaperManager
                    .get(this@LandscapeWebActivity)
                    .setAppBackgroundWallpaper(null)
                // ★ 停止 MPKG 背景渲染
                this@LandscapeWebActivity.stopMpkgBackground()
            } catch (e: Exception) {
                Log.e("Wallpaper", "wallpaperClearAppBg failed", e)
            }
        }

        /**
         * ★ 按壁纸 ID 设置应用背景（支持 MPKG 类型）
         *   JS 端调用：KeepApp.wallpaperSetAppBgById("wp_xxx")
         *   返回 JSON：{name, path, type, ok}
         *   - 如果是 MPKG 类型，自动启动原生背景渲染层
         *   - 其他类型则停止 MPKG 背景层，由 WebView 自行渲染
         */
        @JavascriptInterface
        fun wallpaperSetAppBgById(wallpaperId: String): String =
            try {
                val wm =
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                val wallpaper = wm.getWallpaperList().firstOrNull { it.id == wallpaperId }
                if (wallpaper != null) {
                    wm.setAppBackgroundWallpaper(wallpaper)
                    val realPath = wallpaper.getRealPath(this@LandscapeWebActivity)
                    val json = JSONObject()
                    json.put("name", wallpaper.name ?: "")
                    json.put("path", realPath)
                    json.put("type", wallpaper.wallpaperType)
                    json.put("ok", true)
                    // 鈽?鐢熸垚 content:// URI 渚?JS 绔缃棰?src
                    val contentUriForJs = getWallpaperContentUri(realPath)
                    json.put("uri", contentUriForJs)
                    Log.d(
                        "Wallpaper",
                        "wallpaperSetAppBgById: id=$wallpaperId, name=${wallpaper.name}, type=${wallpaper.wallpaperType}, uri=$contentUriForJs",
                    )
                    // ★ MPKG 类型：启动原生背景渲染层
                    if (wallpaper.wallpaperType == com.mineradio.app.wallpaper.WallpaperEntity.TYPE_MPKG_WEBGL) {
                        this@LandscapeWebActivity.startMpkgBackground(realPath)
                    } else {
                        this@LandscapeWebActivity.stopMpkgBackground()
                    }
                    json.toString()
                } else {
                    Log.w("Wallpaper", "wallpaperSetAppBgById: wallpaper not found, id=$wallpaperId")
                    "{\"ok\":false,\"error\":\"wallpaper not found\"}"
                }
            } catch (e: Exception) {
                Log.e("Wallpaper", "wallpaperSetAppBgById failed", e)
                "{\"ok\":false,\"error\":\"${e.message}\"}"
            }

        /** 清除系统壁纸 */
        @JavascriptInterface
        fun wallpaperClearSystem() {
            try {
                com.mineradio.app.wallpaper.WallpaperHelper
                    .clearSystemWallpaper(this@LandscapeWebActivity)
            } catch (e: Exception) {
                Log.e("Wallpaper", "wallpaperClearSystem failed", e)
            }
        }

        // ═══════════════════════════════════════════
        //  在线壁纸库桥接（背景媒体 → 在线壁纸库按钮调用）
        //  对接 com.mineradio.app.wallpaper.OnlineWallpaperApi
        //  支持源：moewalls（视频壁纸）/ haowallpaper（需登录）/ bizhihui（图片壁纸）
        // ═══════════════════════════════════════════

        /** 获取在线壁纸列表
         *  @return JSON: {list:[{id,title,thumbnail,detailUrl,type,source,resolution,...}], page, hasMore, totalPages, total} */
        @JavascriptInterface
        fun onlineWpGetList(
            page: Int,
            keyword: String,
            source: String,
            category: String,
            resolution: String,
            callbackId: String,
        ) {
            Thread {
                try {
                    val api =
                        com.mineradio.app.wallpaper
                            .OnlineWallpaperApi(this@LandscapeWebActivity)
                    val result =
                        api
                            .getList(
                                page,
                                if (keyword.isBlank()) null else keyword,
                                source,
                                if (category.isBlank()) null else category,
                                if (resolution.isBlank()) null else resolution,
                            ).toString()
                    runOnUiThread {
                        try {
                            val js =
                                "window.onlineWpOnListResult(" +
                                    JSONObject.quote(callbackId) + "," +
                                    JSONObject.quote(result) + ",null);"
                            webView.evaluateJavascript(js, null)
                        } catch (e: Exception) {
                            Log.e("OnlineWp", "callback failed", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("OnlineWp", "getList failed", e)
                    val errMsg = e.message ?: "unknown"
                    runOnUiThread {
                        try {
                            val js =
                                "window.onlineWpOnListResult(" +
                                    JSONObject.quote(callbackId) + ",null," +
                                    JSONObject.quote(errMsg) + ");"
                            webView.evaluateJavascript(js, null)
                        } catch (_: Exception) {
                        }
                    }
                }
            }.start()
        }

        /** 获取指定源的可用分类列表 */
        @JavascriptInterface
        fun onlineWpGetCategories(source: String): String =
            try {
                com.mineradio.app.wallpaper
                    .OnlineWallpaperApi(this@LandscapeWebActivity)
                    .getCategories(source)
                    .toString()
            } catch (e: Exception) {
                Log.e("OnlineWp", "getCategories failed", e)
                "[]"
            }

        /** 获取指定源的可用分辨率列表 */
        @JavascriptInterface
        fun onlineWpGetResolutions(source: String): String =
            try {
                com.mineradio.app.wallpaper
                    .OnlineWallpaperApi(this@LandscapeWebActivity)
                    .getResolutions(source)
                    .toString()
            } catch (e: Exception) {
                Log.e("OnlineWp", "getResolutions failed", e)
                "[]"
            }

        /** 获取壁纸详情页（提取下载用的真实地址） */
        @JavascriptInterface
        fun onlineWpGetDetail(
            detailUrl: String,
            source: String,
        ): String =
            try {
                com.mineradio.app.wallpaper
                    .OnlineWallpaperApi(this@LandscapeWebActivity)
                    .getDetail(detailUrl, source)
                    .toString()
            } catch (e: Exception) {
                Log.e("OnlineWp", "getDetail failed", e)
                "{\"error\":\"${e.message}\"}"
            }

        /** 异步下载并导入壁纸到本地壁纸库
         *  立即返回，前端通过 onlineWpGetProgress 轮询下载进度
         *  @param detailUrl 详情页 URL（作为壁纸 ID 与详情解析入口）
         *  @param name 壁纸名称
         *  @param type 类型：1=图片, 2=视频
         *  @param source 壁纸源（moewalls / haowallpaper / bizhihui / usermpkg）
         *  @param fileId 文件 ID（haowallpaper / bizhihui 用） */
        @JavascriptInterface
        fun onlineWpDownload(
            detailUrl: String,
            name: String,
            type: Int,
            source: String,
            fileId: String,
        ): String {
            Thread {
                try {
                    com.mineradio.app.wallpaper
                        .OnlineWallpaperApi(this@LandscapeWebActivity)
                        .downloadAndImport(detailUrl, name, type, source, fileId, null)
                } catch (e: Exception) {
                    Log.e("OnlineWp", "download failed", e)
                    com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus = "下载失败：${e.message}"
                }
            }.start()
            return "{\"started\":true}"
        }

        /** 查询当前下载进度
         *  @return JSON: {progress:0-100, status:"下载中 / 失败 / 完成"} */
        @JavascriptInterface
        fun onlineWpGetProgress(): String =
            try {
                JSONObject()
                    .apply {
                        put("progress", com.mineradio.app.wallpaper.OnlineWallpaperApi.currentProgress)
                        put("status", com.mineradio.app.wallpaper.OnlineWallpaperApi.currentStatus)
                    }.toString()
            } catch (e: Exception) {
                "{\"progress\":0,\"status\":\"\"}"
            }

        /** 获取本地壁纸库列表（按分类筛选）
         *  @param category: all / video / image / phone / local
         *  - video: TYPE_VIDEO
         *  - image: TYPE_IMAGE
         *  - phone: 高>宽（手机壁纸）
         *  - local: 本地导入（非在线下载，id 不以 wp_online_/wp_hao_/wp_bizhihui_/wp_usermpkg_ 开头）
         *  - all: 全部 */
        @JavascriptInterface
        fun wpLibraryList(category: String): String =
            try {
                val wm =
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                val list = wm.getWallpaperList()
                val arr = JSONArray()
                list.forEach { w ->
                    val (width, height) = detectWallpaperSize(w)
                    val isPhone = height > width && width > 0
                    val isOnline =
                        w.id.startsWith("wp_online_") ||
                            w.id.startsWith("wp_hao_") ||
                            w.id.startsWith("wp_bizhihui_") ||
                            w.id.startsWith("wp_usermpkg_")
                    val match =
                        when (category) {
                            "video" -> w.wallpaperType == com.mineradio.app.wallpaper.WallpaperEntity.TYPE_VIDEO
                            "image" -> w.wallpaperType == com.mineradio.app.wallpaper.WallpaperEntity.TYPE_IMAGE
                            "phone" -> isPhone
                            "local" -> !isOnline
                            else -> true
                        }
                    if (match) {
                        val realPath = w.getRealPath(this@LandscapeWebActivity)
                        // ★ 跳过文件不存在的壁纸（避免显示无封面/黑色卡片）
                        if (!realPath.startsWith("http")) {
                            val wpF = java.io.File(realPath)
                            if (!wpF.exists()) {
                                Log.d("WpLibrary", "skip missing wallpaper: ${w.id} path=$realPath")
                                return@forEach
                            }
                        }
                        val item = JSONObject()
                        item.put("id", w.id)
                        item.put("name", w.name)
                        item.put("type", w.wallpaperType)
                        item.put("width", width)
                        item.put("height", height)
                        item.put("author", w.author ?: "")
                        item.put("isPhone", isPhone)
                        item.put("isOnline", isOnline)
                        item.put("path", realPath)
                        if (!realPath.startsWith("http")) {
                            val wpF = java.io.File(realPath)
                            if (wpF.exists()) item.put("url", getWallpaperContentUri(realPath))
                        } else {
                            item.put("url", realPath)
                        }
                        if (w.wallpaperType == com.mineradio.app.wallpaper.WallpaperEntity.TYPE_VIDEO) {
                            try {
                                val thumbDir = java.io.File(cacheDir, "wp_thumbs")
                                if (!thumbDir.exists()) thumbDir.mkdirs()
                                val thumbFile = java.io.File(thumbDir, w.id + ".jpg")
                                if (!thumbFile.exists()) {
                                    val retriever = android.media.MediaMetadataRetriever()
                                    retriever.setDataSource(realPath)
                                    val bitmap = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    retriever.release()
                                    if (bitmap != null) {
                                        val fos = java.io.FileOutputStream(thumbFile)
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos)
                                        fos.close()
                                        bitmap.recycle()
                                    }
                                }
                                if (thumbFile.exists()) {
                                    val thumbBytes = thumbFile.readBytes()
                                    val thumbB64 = android.util.Base64.encodeToString(thumbBytes, android.util.Base64.NO_WRAP)
                                    item.put("thumbnail", "data:image/jpeg;base64," + thumbB64)
                                }
                            } catch (_: Exception) {
                            }
                        }
                        arr.put(item)
                    }
                }
                JSONObject()
                    .apply {
                        put("list", arr)
                        put("total", arr.length())
                    }.toString()
            } catch (e: Exception) {
                Log.e("WpLibrary", "list failed", e)
                "{\"list\":[],\"total\":0,\"error\":\"${e.message}\"}"
            }

        /** 删除壁纸库中的壁纸 */
        @JavascriptInterface
        fun wpLibraryDelete(id: String) {
            try {
                com.mineradio.app.wallpaper.WallpaperManager
                    .get(this@LandscapeWebActivity)
                    .removeWallpaper(id)
            } catch (e: Exception) {
                Log.e("WpLibrary", "delete failed", e)
            }
        }

        /** 预览壁纸（返回壁纸文件 URL 供前端 <img>/<video> 预览）
         *  @return JSON: {ok, url, type, name} */
        @JavascriptInterface
        fun wpLibraryPreview(id: String): String =
            try {
                val wm =
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                val w = wm.getWallpaperList().firstOrNull { it.id == id }
                if (w != null) {
                    val realPath = w.getRealPath(this@LandscapeWebActivity)
                    val wpFile = java.io.File(realPath)
                    val url =
                        if (realPath.startsWith("http")) {
                            realPath
                        } else if (wpFile.exists()) {
                            getWallpaperContentUri(realPath)
                        } else {
                            "file://$realPath"
                        }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("url", url)
                    json.put("path", realPath)
                    json.put("type", w.wallpaperType)
                    json.put("name", w.name ?: "")
                    json.put("id", w.id)
                    if (w.wallpaperType == com.mineradio.app.wallpaper.WallpaperEntity.TYPE_VIDEO) {
                        try {
                            val thumbDir = java.io.File(cacheDir, "wp_thumbs")
                            if (!thumbDir.exists()) thumbDir.mkdirs()
                            val thumbFile = java.io.File(thumbDir, w.id + ".jpg")
                            if (!thumbFile.exists()) {
                                val retriever = android.media.MediaMetadataRetriever()
                                retriever.setDataSource(realPath)
                                val bitmap = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                retriever.release()
                                if (bitmap != null) {
                                    val fos = java.io.FileOutputStream(thumbFile)
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos)
                                    fos.close()
                                    bitmap.recycle()
                                }
                            }
                            if (thumbFile.exists()) {
                                val thumbBytes = thumbFile.readBytes()
                                val thumbB64 = android.util.Base64.encodeToString(thumbBytes, android.util.Base64.NO_WRAP)
                                json.put("thumbnail", "data:image/jpeg;base64," + thumbB64)
                            }
                        } catch (_: Exception) {
                        }
                    }
                    json.toString()
                } else {
                    "{\"ok\":false,\"error\":\"not found\"}"
                }
            } catch (e: Exception) {
                Log.e("WpLibrary", "preview failed", e)
                "{\"ok\":false,\"error\":\"${e.message}\"}"
            }

        /** 设为应用背景（按壁纸 ID） */
        @JavascriptInterface
        fun wpLibrarySetAppBg(id: String): String = wallpaperSetAppBgById(id)

        /** 设为系统壁纸（按壁纸 ID） */
        @JavascriptInterface
        fun wpLibrarySetSystem(id: String): String =
            try {
                val wm =
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                val wallpaper = wm.getWallpaperList().firstOrNull { it.id == id }
                if (wallpaper != null) {
                    wm.setCurrentWallpaper(wallpaper)
                    val result =
                        com.mineradio.app.wallpaper.WallpaperHelper.applyAsSystemWallpaper(
                            this@LandscapeWebActivity,
                            wallpaper,
                        )
                    when (result) {
                        com.mineradio.app.wallpaper.WallpaperHelper.ApplyResult.SuccessDirect ->
                            "{\"ok\":true,\"result\":\"direct\"}"
                        com.mineradio.app.wallpaper.WallpaperHelper.ApplyResult.NeedSystemActivate ->
                            "{\"ok\":true,\"result\":\"NEED_ACTIVATE\"}"
                        else -> "{\"ok\":false,\"error\":\"apply failed\"}"
                    }
                } else {
                    "{\"ok\":false,\"error\":\"not found\"}"
                }
            } catch (e: Exception) {
                Log.e("WpLibrary", "setSystem failed", e)
                "{\"ok\":false,\"error\":\"${e.message}\"}"
            }

        /** ★ MPKG 壁纸设为系统桌面：从 base64 保存 MPKG 文件，注册到 WallpaperManager，再设为系统壁纸 */
        @JavascriptInterface
        fun wpLibrarySetSystemMpkg(
            mpkgId: String,
            name: String,
            fileName: String,
            base64Data: String,
        ): String =
            try {
                val wm =
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                // 生成唯一壁纸 ID
                val wallpaperId = "wp_usermpkg_$mpkgId"
                // 检查是否已注册
                var wallpaper = wm.getWallpaperList().firstOrNull { it.id == wallpaperId }
                if (wallpaper == null) {
                    // 解析 base64 数据
                    val base64Content =
                        if (base64Data.contains(",")) {
                            base64Data.substring(base64Data.indexOf(",") + 1)
                        } else {
                            base64Data
                        }
                    val bytes = android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT)
                    // 保存 MPKG 文件到外部存储目录
                    val targetDir =
                        java.io
                            .File(
                                android.os.Environment.getExternalStorageDirectory(),
                                "Documents/Mineradio/Wallpaper/$wallpaperId",
                            ).apply { mkdirs() }
                    // ★ 严格清洗 fileName：剥离 URL 查询参数（?v=...&token=...）和非法字符
                    //   双重保险：即使 JS 端漏过滤，Kotlin 端也确保文件名合法
                    var cleanedName = fileName
                    // 1. 去掉 URL 查询参数和锚点
                    val qIdx = cleanedName.indexOfAny(charArrayOf('?', '#'))
                    if (qIdx >= 0) cleanedName = cleanedName.substring(0, qIdx)
                    // 2. 去掉路径分隔符
                    cleanedName = cleanedName.replace("/", "").replace("\\", "")
                    // 3. 只保留字母、数字、点、下划线、连字符
                    cleanedName = cleanedName.replace(Regex("[^a-zA-Z0-9._-]"), "")
                    // 4. 清洗后为空则用 mpkgId 兜底
                    if (cleanedName.isBlank()) cleanedName = mpkgId
                    // 5. 确保以 .mpkg 结尾
                    val safeFileName = if (cleanedName.endsWith(".mpkg", ignoreCase = true)) cleanedName else "$cleanedName.mpkg"
                    Log.d("WpLibrary", "setSystemMpkg: mpkgId=$mpkgId, rawFileName=$fileName, safeFileName=$safeFileName")
                    val targetFile = java.io.File(targetDir, safeFileName)
                    java.io.FileOutputStream(targetFile).use { it.write(bytes) }
                    Log.d("WpLibrary", "saved MPKG file: ${targetFile.absolutePath} (${bytes.size} bytes)")
                    // 注册 MPKG 壁纸到 WallpaperManager
                    wallpaper =
                        com.mineradio.app.wallpaper.WallpaperEntity(
                            author = "Wallpaper Engine",
                            description = name,
                            id = wallpaperId,
                            name = name,
                            path = safeFileName,
                            versionCode = 1,
                            versionName = "1.0",
                            wallpaperType = com.mineradio.app.wallpaper.WallpaperEntity.TYPE_MPKG_WEBGL,
                        )
                    wm.addWallpaper(wallpaper)
                }
                wm.setCurrentWallpaper(wallpaper)
                val result =
                    com.mineradio.app.wallpaper.WallpaperHelper.applyAsSystemWallpaper(
                        this@LandscapeWebActivity,
                        wallpaper,
                    )
                when (result) {
                    com.mineradio.app.wallpaper.WallpaperHelper.ApplyResult.SuccessDirect ->
                        "{\"ok\":true,\"result\":\"direct\"}"
                    com.mineradio.app.wallpaper.WallpaperHelper.ApplyResult.NeedSystemActivate ->
                        "{\"ok\":true,\"result\":\"NEED_ACTIVATE\"}"
                    else -> "{\"ok\":false,\"error\":\"apply failed\"}"
                }
            } catch (e: Exception) {
                Log.e("WpLibrary", "setSystemMpkg failed", e)
                "{\"ok\":false,\"error\":\"${e.message}\"}"
            }

        /** ★ MPKG 壁纸设为系统桌面（文件路径版）：直接从临时文件复制，避免 Base64 解码 OOM */
        @JavascriptInterface
        fun wpLibrarySetSystemMpkgFromFile(
            mpkgId: String,
            name: String,
            fileName: String,
            tempFilePath: String,
        ): String =
            try {
                val wm =
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                val wallpaperId = "wp_usermpkg_$mpkgId"
                var wallpaper = wm.getWallpaperList().firstOrNull { it.id == wallpaperId }
                if (wallpaper == null) {
                    val srcFile = java.io.File(tempFilePath)
                    if (!srcFile.exists()) return "{\"ok\":false,\"error\":\"临时文件不存在: $tempFilePath\"}"
                    val targetDir =
                        java.io
                            .File(
                                android.os.Environment.getExternalStorageDirectory(),
                                "Documents/Mineradio/Wallpaper/$wallpaperId",
                            ).apply {
                                mkdirs()
                            }
                    var cleanedName = fileName
                    val qIdx = cleanedName.indexOfAny(charArrayOf('?', '#'))
                    if (qIdx >= 0) cleanedName = cleanedName.substring(0, qIdx)
                    cleanedName = cleanedName.replace("/", "").replace("\\", "")
                    cleanedName = cleanedName.replace(Regex("[^a-zA-Z0-9._-]"), "")
                    if (cleanedName.isBlank()) cleanedName = mpkgId
                    val safeFileName = if (cleanedName.endsWith(".mpkg", ignoreCase = true)) cleanedName else "$cleanedName.mpkg"
                    Log.d("WpLibrary", "setSystemMpkgFromFile: mpkgId=$mpkgId, safeFileName=$safeFileName, srcSize=${srcFile.length()}")
                    val targetFile = java.io.File(targetDir, safeFileName)
                    srcFile.copyTo(targetFile, overwrite = true)
                    Log.d("WpLibrary", "copied MPKG file: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                    wallpaper =
                        com.mineradio.app.wallpaper.WallpaperEntity(
                            author = "Wallpaper Engine",
                            description = name,
                            id = wallpaperId,
                            name = name,
                            path = safeFileName,
                            versionCode = 1,
                            versionName = "1.0",
                            wallpaperType = com.mineradio.app.wallpaper.WallpaperEntity.TYPE_MPKG_WEBGL,
                        )
                    wm.addWallpaper(wallpaper)
                }
                wm.setCurrentWallpaper(wallpaper)
                val result =
                    com.mineradio.app.wallpaper.WallpaperHelper
                        .applyAsSystemWallpaper(this@LandscapeWebActivity, wallpaper)
                when (result) {
                    com.mineradio.app.wallpaper.WallpaperHelper.ApplyResult.SuccessDirect -> "{\"ok\":true,\"result\":\"direct\"}"
                    com.mineradio.app.wallpaper.WallpaperHelper.ApplyResult.NeedSystemActivate -> "{\"ok\":true,\"result\":\"NEED_ACTIVATE\"}"
                    else -> "{\"ok\":false,\"error\":\"apply failed\"}"
                }
            } catch (e: Exception) {
                Log.e("WpLibrary", "setSystemMpkgFromFile failed", e)
                "{\"ok\":false,\"error\":\"${e.message}\"}"
            }

        /** ★ 分块写入临时文件：JS 端分块 base64 传输，避免一次性 Base64.decode 大文件 OOM */
        @JavascriptInterface
        fun wpWriteTempFileStart(fileName: String): String =
            try {
                val cacheDir = this@LandscapeWebActivity.cacheDir
                val tempFile = java.io.File(cacheDir, "mpkg_temp_${System.currentTimeMillis()}_$fileName")
                tempFile.delete()
                tempFile.createNewFile()
                Log.d("WpLibrary", "wpWriteTempFileStart: ${tempFile.absolutePath}")
                "{\"ok\":true,\"path\":\"${tempFile.absolutePath}\"}"
            } catch (e: Exception) {
                Log.e("WpLibrary", "wpWriteTempFileStart failed", e)
                "{\"ok\":false,\"error\":\"${e.message}\"}"
            }

        /** ★ 分块追加写入临时文件 */
        @JavascriptInterface
        fun wpWriteTempFileChunk(
            tempFilePath: String,
            base64Chunk: String,
        ): String =
            try {
                val file = java.io.File(tempFilePath)
                val base64Content = if (base64Chunk.contains(",")) base64Chunk.substring(base64Chunk.indexOf(",") + 1) else base64Chunk
                val bytes = android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT)
                java.io.FileOutputStream(file, true).use { it.write(bytes) }
                "{\"ok\":true,\"written\":${bytes.size}}"
            } catch (e: Exception) {
                Log.e("WpLibrary", "wpWriteTempFileChunk failed", e)
                "{\"ok\":false,\"error\":\"${e.message}\"}"
            }

        /** ★ 完成临时文件写入，返回路径 */
        @JavascriptInterface
        fun wpWriteTempFileFinish(tempFilePath: String): String =
            try {
                val file = java.io.File(tempFilePath)
                if (!file.exists()) {
                    "{\"ok\":false,\"error\":\"临时文件不存在\"}"
                } else {
                    Log.d("WpLibrary", "wpWriteTempFileFinish: ${file.absolutePath} (${file.length()} bytes)")
                    "{\"ok\":true,\"path\":\"${file.absolutePath}\",\"size\":${file.length()}}"
                }
            } catch (e: Exception) {
                Log.e("WpLibrary", "wpWriteTempFileFinish failed", e)
                "{\"ok\":false,\"error\":\"${e.message}\"}"
            }

        /** 打开原生壁纸管理界面
         *  ★ 竖屏 Compose 界面（含原生壁纸管理页）已整体移除，此入口不再拉起 MainActivity，
         *     仅记录日志并返回；避免 MainActivity 再次转入横屏导致界面被重建。 */
        @JavascriptInterface
        fun wallpaperOpenManager() {
            Log.w("Wallpaper", "wallpaperOpenManager: 原生壁纸管理界面已随竖屏界面移除，调用被忽略")
        }

        /** 读取「强制系统壁纸」开关状态（"1"=开启 "0"=关闭）
         *  ★ 多层防护：通过 WallpaperManager 双读（外部存储 + SharedPreferences），防止"清除数据"丢失开关 */
        @JavascriptInterface
        fun getForceSystemWallpaper(): String =
            try {
                if (com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                        .getForceSystemWallpaper()
                ) {
                    "1"
                } else {
                    "0"
                }
            } catch (e: Exception) {
                "0"
            }

        /** 设置「强制系统壁纸」开关状态
         *  ★ 多层防护：通过 WallpaperManager 双写（外部存储 + SharedPreferences），防止"清除数据"丢失开关 */
        @JavascriptInterface
        fun setForceSystemWallpaper(value: String) {
            try {
                val on = value == "1" || value.equals("true", ignoreCase = true)
                com.mineradio.app.wallpaper.WallpaperManager
                    .get(this@LandscapeWebActivity)
                    .setForceSystemWallpaper(on)
            } catch (e: Exception) {
                Log.e("Wallpaper", "setForceSystemWallpaper failed", e)
            }
        }

        /** ★★★ 桌面萌宠：启用/关闭
         *  使用悬浮窗方式显示，不依赖系统壁纸设置
         *  @param value "1"=启用, "0"=关闭 */
        @JavascriptInterface
        fun setDesktopPetEnabled(value: String) {
            try {
                val on = value == "1" || value.equals("true", ignoreCase = true)
                com.mineradio.app.wallpaper.WallpaperManager
                    .get(this@LandscapeWebActivity)
                    .setDesktopPetEnabled(on)
                if (on) {
                    // 检查悬浮窗权限
                    if (android.provider.Settings.canDrawOverlays(this@LandscapeWebActivity)) {
                        com.mineradio.app.wallpaper.PetFloatingService
                            .start(this@LandscapeWebActivity)
                    } else {
                        // 引导用户授权
                        runOnUiThread {
                            Toast
                                .makeText(
                                    this@LandscapeWebActivity,
                                    "请先授予悬浮窗权限",
                                    Toast.LENGTH_LONG,
                                ).show()
                            val intent =
                                Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${this@LandscapeWebActivity.packageName}"),
                                )
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            this@LandscapeWebActivity.startActivity(intent)
                        }
                    }
                } else {
                    com.mineradio.app.wallpaper.PetFloatingService
                        .stop(this@LandscapeWebActivity)
                }
            } catch (e: Exception) {
                Log.e("Wallpaper", "setDesktopPetEnabled failed", e)
            }
        }

        /** ★★★ 桌面萌宠：读取当前开关状态
         *  @return "1"=已启用且服务在运行, "0"=未启用或服务未运行
         *
         *  ★ v2.2.7 修复：开关状态 + 服务实际运行状态
         *    之前只读取开关状态，导致删除后台后开关显示"已启用"但服务实际未运行
         *    现在同时检测 PetFloatingService.isRunning，确保返回真实状态
         */
        @JavascriptInterface
        fun getDesktopPetEnabled(): String =
            try {
                val switchOn =
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                        .getDesktopPetEnabled()
                val serviceRunning = com.mineradio.app.wallpaper.PetFloatingService.isRunning
                // ★ 开关开启且服务在运行才算真正启用
                if (switchOn && serviceRunning) "1" else "0"
            } catch (e: Exception) {
                Log.e("Wallpaper", "getDesktopPetEnabled failed", e)
                "0"
            }

        /** ★ v2.2.3 桌面萌宠：获取当前加载的模型名
         *  - 用于检测是否已有自定义模型加载
         *  - 返回空字符串表示无自定义模型（使用默认）
         */
        @JavascriptInterface
        fun getCurrentModelName(): String =
            try {
                val name =
                    com.mineradio.app.wallpaper.PetFloatingService
                        .getCurrentModelName(this@LandscapeWebActivity)
                // "默认" 表示未加载自定义模型，返回空字符串
                if (name == "默认") "" else name
            } catch (e: Exception) {
                Log.e("Wallpaper", "getCurrentModelName failed", e)
                ""
            }

        /** ★ v2.2.5 桌面萌宠：更新对话气泡文字（显示当前歌词）
         *  - text 为空时隐藏对话气泡
         *  - text 非空时显示对话气泡并更新文字
         */
        @JavascriptInterface
        fun updatePetSpeechBubble(text: String?) {
            try {
                val t = text ?: ""
                com.mineradio.app.wallpaper.PetFloatingService
                    .updateSpeechBubbleText(this@LandscapeWebActivity, t)
            } catch (e: Exception) {
                Log.w("Wallpaper", "updatePetSpeechBubble failed", e)
            }
        }

        /** ★★★ 桌面萌宠：跳转系统壁纸设置，让用户激活动态壁纸 */
        @JavascriptInterface
        fun gotoSystemWallpaperSettings() {
            try {
                com.mineradio.app.wallpaper.WallpaperHelper
                    .gotoSystemWallpaperSettings(this@LandscapeWebActivity)
            } catch (e: Exception) {
                Log.e("Wallpaper", "gotoSystemWallpaperSettings failed", e)
            }
        }

        /** ★ v2.2 桌面萌宠大小（屏幕高度百分比 0.10-0.50，默认 0.20）
         *  - 兼容旧版整数档位 1-10（自动转换为百分比：idx/10.0）
         */
        @JavascriptInterface
        fun setPetSize(value: String) {
            try {
                val v = value.trim()
                // ★ 优先按浮点数解析（新版本百分比格式，如 "0.20"）
                val pct = v.toFloatOrNull()
                if (pct != null) {
                    com.mineradio.app.wallpaper.PetFloatingService
                        .setSizePercent(this@LandscapeWebActivity, pct.coerceIn(0.10f, 0.50f))
                    return
                }
                // ★ 兼容旧版整数档位 1-10
                val idx = v.toIntOrNull() ?: 2
                com.mineradio.app.wallpaper.PetFloatingService
                    .setSize(this@LandscapeWebActivity, idx.coerceIn(1, 10))
            } catch (e: Exception) {
                Log.e("Wallpaper", "setPetSize failed", e)
            }
        }

        /** ★ v2.2 读取桌面萌宠大小（返回屏幕高度百分比字符串，如 "0.20"） */
        @JavascriptInterface
        fun getPetSize(): String =
            try {
                com.mineradio.app.wallpaper.PetFloatingService
                    .getSizePercent(this@LandscapeWebActivity)
                    .toString()
            } catch (e: Exception) {
                Log.e("Wallpaper", "getPetSize failed", e)
                "0.20" // ★ v2.2 默认 20%（屏幕高度百分比）
            }

        /** ★ 桌面萌宠触摸穿透: "1"=只显示不接收触摸, "0"=正常交互 */
        @JavascriptInterface
        fun setPetTouchPassthrough(value: String) {
            try {
                val on = value == "1" || value.equals("true", ignoreCase = true)
                com.mineradio.app.wallpaper.PetFloatingService
                    .setTouchPassthrough(this@LandscapeWebActivity, on)
            } catch (e: Exception) {
                Log.e("Wallpaper", "setPetTouchPassthrough failed", e)
            }
        }

        @JavascriptInterface
        fun getPetTouchPassthrough(): String =
            if (com.mineradio.app.wallpaper.PetFloatingService
                    .getTouchPassthrough(this@LandscapeWebActivity)
            ) {
                "1"
            } else {
                "0"
            }

        /** ★ 桌面萌宠位置固定: "1"=固定不可拖拽, "0"=正常可拖拽 */
        @JavascriptInterface
        fun setPetPinned(value: String) {
            try {
                val on = value == "1" || value.equals("true", ignoreCase = true)
                com.mineradio.app.wallpaper.PetFloatingService
                    .setPinned(this@LandscapeWebActivity, on)
            } catch (e: Exception) {
                Log.e("Wallpaper", "setPetPinned failed", e)
            }
        }

        @JavascriptInterface
        fun getPetPinned(): String =
            if (com.mineradio.app.wallpaper.PetFloatingService
                    .getPinned(this@LandscapeWebActivity)
            ) {
                "1"
            } else {
                "0"
            }

        /** ★★★ 桌面萌宠：获取当前模型所有表情名称（JSON 数组字符串） */
        @JavascriptInterface
        fun getPetExpressionList(): String =
            try {
                com.mineradio.app.wallpaper.PetFloatingService
                    .getExpressionList(this@LandscapeWebActivity)
            } catch (e: Exception) {
                Log.e("Wallpaper", "getPetExpressionList failed", e)
                "[]"
            }

        /** ★★★ 桌面萌宠：触发指定表情 */
        @JavascriptInterface
        fun triggerPetExpression(name: String) {
            try {
                com.mineradio.app.wallpaper.PetFloatingService
                    .triggerExpression(this@LandscapeWebActivity, name)
            } catch (e: Exception) {
                Log.e("Wallpaper", "triggerPetExpression failed", e)
            }
        }

        /** ★★★ 桌面萌宠：导入自定义模型（zip 压缩包）
         *  @param fileName 原始文件名（.zip 结尾）
         *  @param base64Data zip 文件的 base64 编码
         *  @return "1"=成功, 其他=错误信息 */
        @JavascriptInterface
        fun importPetModel(
            fileName: String,
            base64Data: String,
        ): String =
            try {
                com.mineradio.app.wallpaper.PetFloatingService
                    .importModel(this@LandscapeWebActivity, fileName, base64Data)
            } catch (e: Exception) {
                Log.e("Wallpaper", "importPetModel failed", e)
                e.message ?: "导入失败"
            }

        /** ★★★ 桌面萌宠：重置为默认模型
         *  @return "1"=成功, 其他=错误信息 */
        @JavascriptInterface
        fun resetPetModel(): String =
            try {
                com.mineradio.app.wallpaper.PetFloatingService
                    .resetModel(this@LandscapeWebActivity)
            } catch (e: Exception) {
                Log.e("Wallpaper", "resetPetModel failed", e)
                e.message ?: "重置失败"
            }

        /** ★★★ 桌面萌宠：获取当前模型名称
         *  @return 模型名称（自定义模型返回模型名，默认模型返回"默认"） */
        @JavascriptInterface
        fun getPetModelName(): String =
            try {
                com.mineradio.app.wallpaper.PetFloatingService
                    .getCurrentModelName(this@LandscapeWebActivity)
            } catch (e: Exception) {
                Log.e("Wallpaper", "getPetModelName failed", e)
                "默认"
            }

        // ═══════════════════════════════════════════════════════════
        //  ★★★ v2.1 扩展：插件原生能力桥接（加载 .so / 渲染悬浮窗 / 后台自启动）
        // ═══════════════════════════════════════════════════════════

        /**
         * ★ 加载插件内置 Native 库（.so）
         * 通过 PluginManager.getResourcePath 获取插件 .so 绝对路径，然后用 System.load 加载
         *
         * @param pluginId 插件 ID
         * @param relPath .so 在插件目录中的相对路径，如 "lib/arm64-v8a/libLive2DCubismCore.so"
         * @return "1"=成功, "loaded"=已加载, 其他=错误信息
         */
        @JavascriptInterface
        fun loadPluginLibrary(
            pluginId: String,
            relPath: String,
        ): String =
            try {
                val pm =
                    com.mineradio.app.manager
                        .PluginManager(this@LandscapeWebActivity)
                val absPath = pm.getResourcePath(pluginId, relPath)
                if (absPath == null) {
                    "插件 $pluginId 中找不到 $relPath"
                } else {
                    // ★ System.load() 加载绝对路径 .so
                    //  重复加载会抛 UnsatisfiedLinkError，需要捕获并视为成功
                    try {
                        System.load(absPath)
                        Log.i("PluginNative", "★ 已加载插件库: $absPath")
                        "1"
                    } catch (e: UnsatisfiedLinkError) {
                        val msg = e.message ?: ""
                        if (msg.contains("already loaded") || msg.contains("library already loaded")) {
                            Log.i("PluginNative", "插件库已加载: $absPath")
                            "loaded"
                        } else {
                            Log.e("PluginNative", "loadPluginLibrary 失败: $msg", e)
                            "加载失败: $msg"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Wallpaper", "loadPluginLibrary failed", e)
                e.message ?: "加载异常"
            }

        /**
         * ★ 渲染原生悬浮窗（由配置驱动）
         * 当前实现：将配置转为 PetFloatingService 启动指令
         *
         * @param configJson 配置 JSON 字符串
         *   {
         *     type: "live2d" | "webview",
         *     pluginId: "desktop-pet",
         *     modelPath: "assets/live2d/xiaoaimisi/小爱弥斯.model3.json",
         *     sdkLib: "Live2DCubismCore",
         *     width: 200, height: 280,
         *     x: 0, y: 0,
         *     touchPassthrough: false, draggable: true, pinned: false,
         *     autoStart: false
         *   }
         * @return "1"=成功, 其他=错误信息
         */
        @JavascriptInterface
        fun renderFloatWindow(configJson: String): String =
            try {
                val cfg = org.json.JSONObject(configJson)
                val pluginId = cfg.optString("pluginId", "")
                val type = cfg.optString("type", "live2d")
                val modelPath = cfg.optString("modelPath", "")
                val sdkLib = cfg.optString("sdkLib", "")
                val touchPassthrough = cfg.optBoolean("touchPassthrough", false)
                val pinned = cfg.optBoolean("pinned", false)
                val size = cfg.optDouble("size", 3.0)

                Log.i("Wallpaper", "renderFloatWindow: type=$type pluginId=$pluginId modelPath=$modelPath")

                // 1. 若指定了 SDK 库，先加载（如果插件自带 .so）
                if (sdkLib.isNotEmpty() && pluginId.isNotEmpty()) {
                    var libName = sdkLib
                    if (!libName.startsWith("lib")) libName = "lib" + libName
                    if (!libName.endsWith(".so")) libName = libName + ".so"
                    val relLibPath = "lib/arm64-v8a/$libName"
                    val loadResult = loadPluginLibrary(pluginId, relLibPath)
                    Log.i("Wallpaper", "renderFloatWindow: SDK 加载结果=$loadResult")
                    // 失败也继续，因为 SDK 可能在 APK 中已加载
                }

                // 2. 若指定了模型路径，将模型从插件目录复制到 PetFloatingService 的自定义模型目录
                //    （当前 PetFloatingService 默认从 assets/live2d 读取，自定义模型从 filesDir/custom_pet_model 读取）
                //    为了让插件模型生效，需要把插件模型导入到 custom_pet_model
                if (modelPath.isNotEmpty() && pluginId.isNotEmpty()) {
                    try {
                        val pm =
                            com.mineradio.app.manager
                                .PluginManager(this@LandscapeWebActivity)
                        val srcPath = pm.getResourcePath(pluginId, modelPath)
                        if (srcPath != null) {
                            val srcFile = java.io.File(srcPath)
                            val srcDir = srcFile.parentFile ?: srcFile
                            // 复制模型目录到 filesDir/custom_pet_model/<pluginId>/
                            val targetDir =
                                java.io.File(
                                    this@LandscapeWebActivity.filesDir,
                                    "custom_pet_model/$pluginId",
                                )
                            if (targetDir.exists()) targetDir.deleteRecursively()
                            targetDir.mkdirs()
                            srcDir.copyRecursively(targetDir, overwrite = true)
                            Log.i("Wallpaper", "renderFloatWindow: 已复制插件模型到 $targetDir")
                            // ★ v2.2.4 直接设置 currentModelName 而不调用 setCurrentModelDirName
                            //   避免触发 reloadModel（stop+start），后面会统一调用 start
                            com.mineradio.app.wallpaper.PetFloatingService
                                .setCurrentModelDirNameNoReload(this@LandscapeWebActivity, pluginId)
                        }
                    } catch (e: Exception) {
                        Log.w("Wallpaper", "renderFloatWindow: 模型复制失败（继续启动）: ${e.message}")
                    }
                }

                // 3. 应用配置
                com.mineradio.app.wallpaper.PetFloatingService
                    .setTouchPassthrough(this@LandscapeWebActivity, touchPassthrough)
                com.mineradio.app.wallpaper.PetFloatingService
                    .setPinned(this@LandscapeWebActivity, pinned)
                // ★ v2.2.4 修复：JS 层传的是百分比浮点数（0.10-0.50），而非档位整数（1-10）
                //   - size < 1.0 → 百分比模式，使用 setSizePercent
                //   - size >= 1.0 → 旧版档位模式，使用 setSize（兼容旧版）
                if (size < 1.0) {
                    val sizePercent = size.coerceIn(0.10, 0.50).toFloat()
                    com.mineradio.app.wallpaper.PetFloatingService
                        .setSizePercent(this@LandscapeWebActivity, sizePercent)
                } else {
                    com.mineradio.app.wallpaper.PetFloatingService
                        .setSize(this@LandscapeWebActivity, size.toInt().coerceIn(1, 10))
                }

                // 4. 启动悬浮窗
                //    检查悬浮窗权限
                if (!android.provider.Settings.canDrawOverlays(this@LandscapeWebActivity)) {
                    runOnUiThread {
                        Toast
                            .makeText(
                                this@LandscapeWebActivity,
                                "请先授予悬浮窗权限",
                                Toast.LENGTH_LONG,
                            ).show()
                        val intent =
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${this@LandscapeWebActivity.packageName}"),
                            )
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        this@LandscapeWebActivity.startActivity(intent)
                    }
                    "需要悬浮窗权限"
                } else {
                    // 启用并启动服务
                    com.mineradio.app.wallpaper.WallpaperManager
                        .get(this@LandscapeWebActivity)
                        .setDesktopPetEnabled(true)
                    // ★ v2.2.4 使用 reloadModel（stop + 1秒后 start）替代直接 start
                    //   确保服务重新创建，避免服务已存在但萌宠不显示的问题
                    //   与 importModel 后的启动流程一致
                    com.mineradio.app.wallpaper.PetFloatingService
                        .reloadModelPublic(this@LandscapeWebActivity)
                    "1"
                }
            } catch (e: Exception) {
                Log.e("Wallpaper", "renderFloatWindow failed", e)
                e.message ?: "渲染失败"
            }

        /**
         * ★ 关闭原生悬浮窗
         * @param windowId 悬浮窗 ID（当前实现忽略，只支持一个萌宠窗口）
         * @return "1"=成功
         */
        @JavascriptInterface
        fun closeFloatWindow(windowId: String): String =
            try {
                com.mineradio.app.wallpaper.WallpaperManager
                    .get(this@LandscapeWebActivity)
                    .setDesktopPetEnabled(false)
                com.mineradio.app.wallpaper.PetFloatingService
                    .stop(this@LandscapeWebActivity)
                "1"
            } catch (e: Exception) {
                Log.e("Wallpaper", "closeFloatWindow failed", e)
                e.message ?: "关闭失败"
            }

        /**
         * ★ 注册后台自启动
         * @param configJson 配置 JSON 字符串
         *   {
         *     action: "boot_completed" | "app_killed" | "background",
         *     enabled: true,
         *     pluginId: "desktop-pet"
         *   }
         * @return "1"=成功
         */
        @JavascriptInterface
        fun registerAutoStart(configJson: String): String =
            try {
                val cfg = org.json.JSONObject(configJson)
                val enabled = cfg.optBoolean("enabled", true)
                val action = cfg.optString("action", "boot_completed")
                Log.i("Wallpaper", "registerAutoStart: action=$action enabled=$enabled")
                // 当前实现：复用 WallpaperManager 的桌面萌宠开关
                //   "boot_completed" → 由 BootReceiver 触发自启动
                //   其他类型暂时只持久化开关
                com.mineradio.app.wallpaper.WallpaperManager
                    .get(this@LandscapeWebActivity)
                    .setDesktopPetEnabled(enabled)
                "1"
            } catch (e: Exception) {
                Log.e("Wallpaper", "registerAutoStart failed", e)
                e.message ?: "注册失败"
            }

        /**
         * ★ 获取插件资源（base64 编码）
         * 用于插件 JS 直接读取二进制资源（不通过 HTTP 路由）
         *
         * @param pluginId 插件 ID
         * @param relPath 资源相对路径
         * @return base64 编码字符串，文件不存在返回 ""
         */
        @JavascriptInterface
        fun getPluginResource(
            pluginId: String,
            relPath: String,
        ): String =
            try {
                val pm =
                    com.mineradio.app.manager
                        .PluginManager(this@LandscapeWebActivity)
                val bytes = pm.readResource(pluginId, relPath)
                if (bytes != null) {
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                } else {
                    ""
                }
            } catch (e: Exception) {
                Log.e("Wallpaper", "getPluginResource failed", e)
                ""
            }

        /**
         * ★ 列出插件目录中所有文件（JSON 数组字符串）
         * @param pluginId 插件 ID
         * @param subDir 子目录路径（""=插件根目录）
         * @return JSON 数组字符串，如 ["lib/arm64-v8a/libxxx.so", ...]
         */
        @JavascriptInterface
        fun listPluginFiles(
            pluginId: String,
            subDir: String,
        ): String =
            try {
                val pm =
                    com.mineradio.app.manager
                        .PluginManager(this@LandscapeWebActivity)
                val files = pm.listResources(pluginId, subDir)
                val arr = org.json.JSONArray()
                files.forEach { arr.put(it) }
                arr.toString()
            } catch (e: Exception) {
                Log.e("Wallpaper", "listPluginFiles failed", e)
                "[]"
            }

        // ═══════════════════════════════════════════════════════════
        //  ★★★ v2.2 扩展：精细化原生能力桥接 ★★★
        //   - 悬浮窗完整控制（位置/大小/透明度/状态/列表）
        //   - Live2D 动态加载（模型/动作/表情/参数）
        //   - 跨插件广播通信（postBroadcast/onBroadcast）
        //   - 系统级能力（系统广播/系统状态/启动应用）
        // ═══════════════════════════════════════════════════════════

        // ──── 悬浮窗完整控制 ────

        /** ★ 查询悬浮窗是否激活 */
        @JavascriptInterface
        fun isFloatWindowActive(windowId: String): String =
            try {
                val active = com.mineradio.app.wallpaper.PetFloatingService.isRunning
                if (active) "1" else "0"
            } catch (e: Exception) {
                Log.e("Wallpaper", "isFloatWindowActive failed", e)
                "0"
            }

        /** ★ 设置悬浮窗位置（屏幕坐标） */
        @JavascriptInterface
        fun setFloatWindowPosition(
            windowId: String,
            x: Int,
            y: Int,
        ): String =
            try {
                com.mineradio.app.wallpaper.PetFloatingService
                    .setPosition(this@LandscapeWebActivity, x, y)
                "1"
            } catch (e: Exception) {
                Log.e("Wallpaper", "setFloatWindowPosition failed", e)
                e.message ?: "设置失败"
            }

        /** ★ 设置悬浮窗大小（像素） */
        @JavascriptInterface
        fun setFloatWindowSize(
            windowId: String,
            width: Int,
            height: Int,
        ): String =
            try {
                com.mineradio.app.wallpaper.PetFloatingService
                    .setWindowSize(this@LandscapeWebActivity, width, height)
                "1"
            } catch (e: Exception) {
                Log.e("Wallpaper", "setFloatWindowSize failed", e)
                e.message ?: "设置失败"
            }

        /** ★ 设置悬浮窗透明度 (0.0-1.0) */
        @JavascriptInterface
        fun setFloatWindowOpacity(
            windowId: String,
            alpha: Float,
        ): String =
            try {
                com.mineradio.app.wallpaper.PetFloatingService
                    .setOpacity(this@LandscapeWebActivity, alpha.coerceIn(0f, 1f))
                "1"
            } catch (e: Exception) {
                Log.e("Wallpaper", "setFloatWindowOpacity failed", e)
                e.message ?: "设置失败"
            }

        /** ★ 获取悬浮窗当前位置和大小 (JSON) */
        @JavascriptInterface
        fun getFloatWindowInfo(windowId: String): String =
            try {
                val info =
                    com.mineradio.app.wallpaper.PetFloatingService
                        .getWindowInfo(this@LandscapeWebActivity)
                org.json
                    .JSONObject()
                    .apply {
                        put("x", info.x)
                        put("y", info.y)
                        put("width", info.width)
                        put("height", info.height)
                        put("active", com.mineradio.app.wallpaper.PetFloatingService.isRunning)
                    }.toString()
            } catch (e: Exception) {
                Log.e("Wallpaper", "getFloatWindowInfo failed", e)
                "{}"
            }

        // ──── Live2D 动态加载 ────

        /** ★ 加载指定路径的 Live2D 模型（pluginId/relPath 形式）
         *  从插件目录复制模型到 custom_pet_model，然后切换并重载
         */
        @JavascriptInterface
        fun loadLive2DModel(
            pluginId: String,
            modelPath: String,
        ): String =
            try {
                if (pluginId.isEmpty() || modelPath.isEmpty()) return "参数不能为空"
                val pm =
                    com.mineradio.app.manager
                        .PluginManager(this@LandscapeWebActivity)
                val srcPath = pm.getResourcePath(pluginId, modelPath)
                if (srcPath == null) {
                    return "找不到模型: $modelPath"
                }
                val srcFile = java.io.File(srcPath)
                val srcDir = srcFile.parentFile ?: srcFile
                // 复制到 custom_pet_model/<pluginId>/
                val targetDir =
                    java.io.File(
                        this@LandscapeWebActivity.filesDir,
                        "custom_pet_model/$pluginId",
                    )
                if (targetDir.exists()) targetDir.deleteRecursively()
                targetDir.mkdirs()
                srcDir.copyRecursively(targetDir, overwrite = true)
                Log.i("Wallpaper", "loadLive2DModel: 已复制模型到 $targetDir")
                com.mineradio.app.wallpaper.PetFloatingService
                    .setCurrentModelDirName(this@LandscapeWebActivity, pluginId)
            } catch (e: Exception) {
                Log.e("Wallpaper", "loadLive2DModel failed", e)
                e.message ?: "加载失败"
            }

        /** ★ 触发 Live2D 动作（motion3.json 文件名，不含扩展名） */
        @JavascriptInterface
        fun triggerMotion(motionName: String): String =
            try {
                com.mineradio.app.wallpaper.JniBridgePet
                    .nativeTriggerExpression(motionName)
                "1"
            } catch (e: Exception) {
                Log.e("Wallpaper", "triggerMotion failed", e)
                e.message ?: "触发失败"
            }

        /** ★ 触发 Live2D 表情（exp3.json 文件名，不含扩展名） */
        @JavascriptInterface
        fun triggerLive2DExpression(expressionName: String): String =
            try {
                com.mineradio.app.wallpaper.PetFloatingService
                    .triggerExpression(this@LandscapeWebActivity, expressionName)
                "1"
            } catch (e: Exception) {
                Log.e("Wallpaper", "triggerLive2DExpression failed", e)
                e.message ?: "触发失败"
            }

        /** ★ 获取当前模型信息 (JSON) */
        @JavascriptInterface
        fun getLive2DModelInfo(): String =
            try {
                val info = org.json.JSONObject()
                info.put(
                    "modelName",
                    com.mineradio.app.wallpaper.PetFloatingService
                        .getCurrentModelName(this@LandscapeWebActivity),
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
                // 表情列表
                val exprList =
                    com.mineradio.app.wallpaper.PetFloatingService
                        .getExpressionList(this@LandscapeWebActivity)
                info.put("expressions", org.json.JSONArray(exprList))
                info.toString()
            } catch (e: Exception) {
                Log.e("Wallpaper", "getLive2DModelInfo failed", e)
                "{}"
            }

        // ──── 跨插件广播通信 ────

        /** ★ 发送广播消息给其他插件（通过 JS 事件分发）
         *  action: 广播动作名（如 "music.beat"）
         *  data: 任意 JSON 字符串
         *  返回值: 1=成功分发给 JS, -1=失败
         */
        @JavascriptInterface
        fun postPluginBroadcast(
            action: String,
            data: String,
        ): Int =
            try {
                runOnUiThread {
                    try {
                        val payload =
                            org.json.JSONObject().apply {
                                put("action", action)
                                put("data", data)
                            }
                        val js =
                            "if(window.MineradioPlugins && MineradioPlugins._dispatchBroadcast){" +
                                "MineradioPlugins._dispatchBroadcast($payload);" +
                                "}else{console.warn('[Broadcast] MineradioPlugins._dispatchBroadcast 未注册');};"
                        webView?.evaluateJavascript(js, null)
                    } catch (e: Exception) {
                        Log.w("Wallpaper", "postPluginBroadcast JS dispatch failed: ${e.message}")
                    }
                }
                1
            } catch (e: Exception) {
                Log.e("Wallpaper", "postPluginBroadcast failed", e)
                -1
            }

        // ──── 系统级能力 ────

        /** ★ 发送系统广播（受 manifest 权限限制） */
        @JavascriptInterface
        fun sendSystemBroadcast(
            action: String,
            data: String,
        ): String =
            try {
                val intent = android.content.Intent(action)
                if (data.isNotEmpty()) {
                    try {
                        val json = org.json.JSONObject(data)
                        for (key in json.keys()) {
                            intent.putExtra(key, json.getString(key))
                        }
                    } catch (e: Exception) {
                        intent.putExtra("data", data)
                    }
                }
                this@LandscapeWebActivity.sendBroadcast(intent)
                Log.i("Wallpaper", "sendSystemBroadcast: action=$action")
                "1"
            } catch (e: Exception) {
                Log.e("Wallpaper", "sendSystemBroadcast failed", e)
                e.message ?: "发送失败"
            }

        /** ★ 获取系统状态信息
         *  key 取值: screen_width / screen_height / battery / network / package_name / version_name
         */
        @JavascriptInterface
        fun getSystemState(key: String): String =
            try {
                when (key) {
                    "screen_width", "screenWidth" -> {
                        val dm = android.util.DisplayMetrics()
                        this@LandscapeWebActivity.windowManager.defaultDisplay.getMetrics(dm)
                        dm.widthPixels.toString()
                    }
                    "screen_height", "screenHeight" -> {
                        val dm = android.util.DisplayMetrics()
                        this@LandscapeWebActivity.windowManager.defaultDisplay.getMetrics(dm)
                        dm.heightPixels.toString()
                    }
                    "battery" -> {
                        val intent =
                            this@LandscapeWebActivity.registerReceiver(
                                null,
                                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
                            )
                        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                        val pct = if (scale > 0) (level * 100 / scale) else -1
                        pct.toString()
                    }
                    "network" -> {
                        val cm =
                            this@LandscapeWebActivity.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                                as android.net.ConnectivityManager
                        val ni = cm.activeNetworkInfo
                        if (ni != null && ni.isConnected) ni.typeName else "none"
                    }
                    "package_name" -> this@LandscapeWebActivity.packageName
                    "version_name" -> {
                        val pm = this@LandscapeWebActivity.packageManager
                        val info = pm.getPackageInfo(this@LandscapeWebActivity.packageName, 0)
                        info.versionName ?: ""
                    }
                    "android_version" -> android.os.Build.VERSION.RELEASE
                    "device_model" -> android.os.Build.MODEL
                    else -> "未知 key: $key"
                }
            } catch (e: Exception) {
                Log.e("Wallpaper", "getSystemState failed", e)
                e.message ?: "获取失败"
            }

        /** ★ 启动其他应用（通过包名） */
        @JavascriptInterface
        fun launchApp(packageName: String): String =
            try {
                val intent =
                    this@LandscapeWebActivity
                        .packageManager
                        .getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    this@LandscapeWebActivity.startActivity(intent)
                    "1"
                } else {
                    // 应用未安装，跳转到应用商店
                    "应用 $packageName 未安装"
                }
            } catch (e: Exception) {
                Log.e("Wallpaper", "launchApp failed", e)
                e.message ?: "启动失败"
            }

        /** ★ v2.2.3 下载并自动安装登录插件
         *  - 接受可选 url 参数（来自云端 [6] 字段），未提供时使用默认 URL
         *  - 原生 Toast 提示"本插件由第三方人员提供"
         */
        @JavascriptInterface
        fun downloadAndInstallLoginPlugin(url: String?) {
            runOnUiThread {
                Toast
                    .makeText(
                        this@LandscapeWebActivity,
                        "本插件由第三方人员提供",
                        Toast.LENGTH_LONG,
                    ).show()
            }
            // ★ v2.2.3 优先使用云端 [6] 字段传入的 URL，否则使用默认值
            val finalUrl =
                if (!url.isNullOrBlank()) {
                    url.trim()
                } else {
                    "http://wpan.cdndns.site/down/bef8ebe70fd6e9cdcbfe86025b577ce8"
                }
            // 在后台线程执行下载+安装
            Thread {
                try {
                    val url = URL(finalUrl)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 20000
                    conn.readTimeout = 60000
                    conn.setRequestProperty("User-Agent", "Mineradio-Android")
                    conn.instanceFollowRedirects = true
                    conn.connect()
                    if (conn.responseCode != 200) {
                        runOnUiThread {
                            Toast
                                .makeText(
                                    this@LandscapeWebActivity,
                                    "下载失败：HTTP ${conn.responseCode}",
                                    Toast.LENGTH_LONG,
                                ).show()
                        }
                        conn.disconnect()
                        return@Thread
                    }
                    // 保存到手机下载目录（用户可查看的存储位置）
                    val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val target = java.io.File(downloadDir, "Mineradio_LoginPlugin.mr")
                    target.parentFile?.mkdirs()
                    val input = conn.inputStream
                    val output = java.io.FileOutputStream(target)
                    val buffer = ByteArray(8192)
                    try {
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
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
                    runOnUiThread {
                        Toast
                            .makeText(
                                this@LandscapeWebActivity,
                                "下载完成，正在安装...",
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                    // 调用 PluginManager.install(uri) 安装
                    val pm = server?.pluginManager
                    if (pm == null) {
                        runOnUiThread {
                            Toast
                                .makeText(
                                    this@LandscapeWebActivity,
                                    "插件管理器未就绪",
                                    Toast.LENGTH_LONG,
                                ).show()
                        }
                        return@Thread
                    }
                    val uri = android.net.Uri.fromFile(target)
                    val result = pm.install(uri)
                    runOnUiThread {
                        if (result.success) {
                            Toast
                                .makeText(
                                    this@LandscapeWebActivity,
                                    "登录插件安装成功：${result.name ?: result.pluginId ?: ""}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            webView.evaluateJavascript(
                                "if(window._onLoginPluginInstalled)window._onLoginPluginInstalled(true,'${result.name ?: result.pluginId ?: ""}')",
                                null,
                            )
                        } else {
                            Toast
                                .makeText(
                                    this@LandscapeWebActivity,
                                    "登录插件安装失败：${result.message ?: "未知错误"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            webView.evaluateJavascript(
                                "if(window._onLoginPluginInstalled)window._onLoginPluginInstalled(false,'${result.message ?: "未知错误"}')",
                                null,
                            )
                        }
                        // 清理临时文件
                        try {
                            target.delete()
                        } catch (_: Exception) {
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "downloadAndInstallLoginPlugin failed", e)
                    runOnUiThread {
                        Toast
                            .makeText(
                                this@LandscapeWebActivity,
                                "下载安装失败：${e.message ?: "网络错误"}",
                                Toast.LENGTH_LONG,
                            ).show()
                        webView.evaluateJavascript(
                            "if(window._onLoginPluginInstalled)window._onLoginPluginInstalled(false,'${e.message ?: "网络错误"}')",
                            null,
                        )
                    }
                }
            }.start()
        }

        // ★★★ 插件市场：打开独立 Activity 加载 H5 商店页 ★★★
        @JavascriptInterface
        fun openPluginMarket() {
            runOnUiThread {
                try {
                    val intent =
                        android.content.Intent(
                            this@LandscapeWebActivity,
                            com.mineradio.app.manager.PluginMarketActivity::class.java,
                        )
                    startActivity(intent)
                    Log.i(TAG, "KeepApp.openPluginMarket() called → PluginMarketActivity")
                } catch (e: Throwable) {
                    Log.e(TAG, "openPluginMarket failed", e)
                    android.widget.Toast
                        .makeText(
                            this@LandscapeWebActivity,
                            "打开插件市场失败: ${e.message}",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                }
            }
        }

        // ★★★ 插件管理：列举已安装插件 ★★★
        @JavascriptInterface
        fun listInstalledPlugins(): String =
            try {
                val pm =
                    com.mineradio.app.manager
                        .PluginManager(this@LandscapeWebActivity)
                pm.listPlugins().toString()
            } catch (e: Exception) {
                Log.e(TAG, "listInstalledPlugins failed", e)
                "[]"
            }

        // ★★★ 插件管理：启用/禁用插件 ★★★
        @JavascriptInterface
        fun setPluginEnabled(
            pluginId: String,
            enabled: Boolean,
        ): Boolean =
            try {
                val pm =
                    com.mineradio.app.manager
                        .PluginManager(this@LandscapeWebActivity)
                pm.setEnabled(pluginId, enabled)
            } catch (e: Exception) {
                Log.e(TAG, "setPluginEnabled failed", e)
                false
            }

        // ★★★ 插件管理：卸载插件 ★★★
        @JavascriptInterface
        fun uninstallPlugin(pluginId: String): Boolean =
            try {
                val pm =
                    com.mineradio.app.manager
                        .PluginManager(this@LandscapeWebActivity)
                pm.uninstall(pluginId)
            } catch (e: Exception) {
                Log.e(TAG, "uninstallPlugin failed", e)
                false
            }

        // ★★★ 插件市场 iframe 安装桥接：从市场页接收下载 URL，下载并安装 ★★★
        @JavascriptInterface
        fun installPluginFromUrl(
            downloadUrl: String,
            filename: String,
            callbackId: String,
        ) {
            Log.i(TAG, "installPluginFromUrl: url=$downloadUrl, filename=$filename")
            Thread {
                val result = installPluginFromUrlInternal(downloadUrl, filename)
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.post {
                    webView.evaluateJavascript(
                        "try{if(window.__onMarketInstall)window.__onMarketInstall('$callbackId', ${result.first}, ${org.json.JSONObject.quote(
                            result.second,
                        )})}catch(e){console.error(e)}",
                        null,
                    )
                }
            }.start()
        }

        // ★ 下载并安装插件（在后台线程调用）
        private fun installPluginFromUrlInternal(
            downloadUrl: String,
            filename: String,
        ): Pair<Boolean, String> {
            return try {
                val conn = java.net.URL(downloadUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.setRequestProperty("User-Agent", "Mineradio-Android/PluginMarket")
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode != 200) {
                    Log.e(TAG, "download HTTP failed: ${conn.responseCode} ${conn.responseMessage}")
                    return false to "HTTP ${conn.responseCode}"
                }
                val expectedLen = conn.contentLength
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                Log.i(TAG, "downloaded ${bytes.size} bytes (expected=$expectedLen), filename=$filename")
                // ★ 完整性校验：如果服务器给了 Content-Length，必须完全匹配
                if (expectedLen > 0 && bytes.size != expectedLen) {
                    Log.e(TAG, "下载不完整: expected=$expectedLen, actual=${bytes.size}")
                    return false to "下载不完整(预期 $expectedLen 字节,实际 ${bytes.size})"
                }
                // ★ ZIP 头校验：必须以 PK\x03\x04 开头
                if (bytes.size < 4 ||
                    bytes[0] != 0x50.toByte() ||
                    bytes[1] != 0x4b.toByte() ||
                    bytes[2] != 0x03.toByte() ||
                    bytes[3] != 0x04.toByte()
                ) {
                    Log.e(TAG, "文件头不是 ZIP 格式: ${bytes.take(4).map { "%02x".format(it) }}")
                    return false to "文件格式错误(不是有效ZIP)"
                }
                if (bytes.size < 22) return false to "数据太小"
                // 写入临时文件
                val tmpFile = java.io.File(this@LandscapeWebActivity.cacheDir, "market_install_${System.currentTimeMillis()}.zip")
                try {
                    java.io.FileOutputStream(tmpFile).use { it.write(bytes) }
                    val pm =
                        server?.pluginManager
                            ?: com.mineradio.app.manager
                                .PluginManager(this@LandscapeWebActivity)
                    val result = pm.install(android.net.Uri.fromFile(tmpFile))
                    if (result.success) {
                        Log.i(TAG, "install success: ${result.pluginId} (${result.name})")
                        true to "已安装: ${result.name ?: result.pluginId ?: filename}"
                    } else {
                        Log.w(TAG, "install failed: ${result.message}")
                        false to result.message
                    }
                } finally {
                    try {
                        tmpFile.delete()
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "installPluginFromUrlInternal failed", e)
                false to "下载/安装失败: ${e.message}"
            }
        }

        // ★★ Android 内存信息：读取手机运行内存占用，供设置面板「内存压缩」区域显示
        //   （桌面版走 desktopWindow.getMemorySnapshot；手机版走此桥接，仅展示，不做系统级释放）
        @JavascriptInterface
        fun getDeviceMemoryInfo(): String =
            try {
                val am =
                    this@LandscapeWebActivity.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                        as android.app.ActivityManager
                val mi = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val totalMB = mi.totalMem / (1024L * 1024L)
                val availMB = mi.availMem / (1024L * 1024L)
                val usedMB = totalMB - availMB
                val usedPercent =
                    if (mi.totalMem > 0) Math.round((mi.totalMem - mi.availMem) * 100.0 / mi.totalMem.toDouble()) else 0
                val json = JSONObject()
                json.put("ok", true)
                json.put("totalMB", totalMB)
                json.put("freeMB", availMB)
                json.put("usedMB", usedMB)
                json.put("usedPercent", usedPercent)
                json.toString()
            } catch (e: Exception) {
                Log.e("Memory", "getDeviceMemoryInfo failed", e)
                "{\"ok\":false,\"error\":\"${e.message}\"}"
            }

        // 递归统计目录占用（字节）
        private fun androidCacheDirSize(dir: java.io.File?): Long {
            if (dir == null || !dir.exists()) return 0L
            var size = 0L
            try {
                dir.listFiles()?.forEach { f ->
                    size += if (f.isDirectory) androidCacheDirSize(f) else f.length()
                }
            } catch (_: Exception) {
            }
            return size
        }

        // 递归删除目录内容（返回释放字节数）
        private fun androidCacheDeleteRecursive(dir: java.io.File?): Long {
            if (dir == null || !dir.exists()) return 0L
            var freed = 0L
            try {
                dir.listFiles()?.forEach { f ->
                    if (f.isDirectory) {
                        freed += androidCacheDeleteRecursive(f)
                    } else {
                        freed += f.length()
                    }
                    try {
                        f.delete()
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
            return freed
        }

        /** ★★ Android 缓存占用：统计 WebView 网络缓存、MPKG 场景解析缓存、封面缓存等 */
        @JavascriptInterface
        fun getAppCacheInfo(): String =
            try {
                val cacheDir = this@LandscapeWebActivity.cacheDir
                val filesDir = this@LandscapeWebActivity.filesDir
                val dataDir = this@LandscapeWebActivity.getDataDir()
                // ★ WebView 内部存储：歌词/译文、节奏分析、歌曲音频实际都存于此处
                //   - localStorage（节奏分析 beatmap、歌词译文）→ app_webview/Default/Local Storage
                //   - IndexedDB（歌词/译文/封面大对象）→ app_webview/Default/IndexedDB
                //   - 网络 HTTP 缓存（播放时的歌曲/音频分片）→ cacheDir/WebView 等
                val webviewDir = java.io.File(dataDir, "app_webview")
                val localStorageBytes =
                    androidCacheDirSize(java.io.File(webviewDir, "Default/Local Storage")) +
                        androidCacheDirSize(java.io.File(webviewDir, "Local Storage"))
                val indexedDBBytes =
                    androidCacheDirSize(java.io.File(webviewDir, "Default/IndexedDB")) +
                        androidCacheDirSize(java.io.File(webviewDir, "IndexedDB"))
                val httpCacheBytes =
                    androidCacheDirSize(java.io.File(cacheDir, "WebView")) +
                        androidCacheDirSize(java.io.File(cacheDir, "webview")) +
                        androidCacheDirSize(java.io.File(cacheDir, "http")) +
                        androidCacheDirSize(java.io.File(webviewDir, "Default/Cache"))
                val localLyrics = androidCacheDirSize(java.io.File(filesDir, "local_lyrics"))
                val localSongs = androidCacheDirSize(java.io.File(filesDir, "local_songs"))
                val beatMapDisk = androidCacheDirSize(java.io.File(filesDir, "mineradio-data/beatmaps"))
                val mpkgSize = androidCacheDirSize(java.io.File(filesDir, "mpkg"))
                val coversSize = androidCacheDirSize(java.io.File(filesDir, "wallpaper_covers"))
                // ★ 归类展示：歌词/译文、节奏分析、歌曲缓存均取真实存储位置
                val lyricsBytes = indexedDBBytes + localLyrics
                val beatmapsBytes = localStorageBytes + beatMapDisk
                val songsBytes = httpCacheBytes + localSongs
                val total = lyricsBytes + beatmapsBytes + songsBytes + mpkgSize + coversSize
                val json = JSONObject()
                json.put("ok", true)
                json.put("webviewBytes", httpCacheBytes)
                json.put("mpkgBytes", mpkgSize)
                json.put("coversBytes", coversSize)
                json.put("lyricsBytes", lyricsBytes)
                json.put("beatmapsBytes", beatmapsBytes)
                json.put("songsBytes", songsBytes)
                json.put("totalBytes", total)
                json.toString()
            } catch (e: Exception) {
                Log.e("Memory", "getAppCacheInfo failed", e)
                "{\"ok\":false,\"error\":\"${e.message}\"}"
            }

        /** ★★ Android 清理缓存：清 WebView 网络缓存与封面缓存（可再生成），保留登录资料(IndexedDB)与已导入壁纸 */
        @JavascriptInterface
        fun clearAppCache(): String =
            try {
                val cacheDir = this@LandscapeWebActivity.cacheDir
                val filesDir = this@LandscapeWebActivity.filesDir
                var freed = 0L
                listOf(
                    java.io.File(cacheDir, "WebView"),
                    java.io.File(cacheDir, "webview"),
                    java.io.File(cacheDir, "http"),
                    java.io.File(filesDir, "wallpaper_covers"),
                ).forEach { dir ->
                    freed += androidCacheDeleteRecursive(dir)
                }
                val json = JSONObject()
                json.put("ok", true)
                json.put("freedBytes", freed)
                json.toString()
            } catch (e: Exception) {
                Log.e("Memory", "clearAppCache failed", e)
                "{\"ok\":false,\"error\":\"${e.message}\"}"
            }
    }

    // ════════════════════════════════════════════════════════════
    // 桌面歌词浮窗管理（Java TextView 版，从 mineradio-android 完整移植）
    // ════════════════════════════════════════════════════════════
    private fun showDesktopLyricView() {
        try {
            if (desktopLyricsManager == null) {
                desktopLyricsManager = com.mineradio.desktop.DesktopLyricsManager(this)
            }
            // 检查悬浮窗权限
            if (desktopLyricsManager?.hasOverlayPermission() != true) {
                Log.w(TAG, "showDesktopLyricView: no overlay permission")
                return
            }
            desktopLyricsManager?.show()
        } catch (e: Exception) {
            Log.e(TAG, "showDesktopLyricView error", e)
        }
    }

    private fun hideDesktopLyricView() {
        try {
            desktopLyricsManager?.hide()
        } catch (e: Exception) {
            Log.e(TAG, "hideDesktopLyricView error", e)
        }
    }

    private fun sendUpdateProgress(
        done: Boolean,
        progress: Int,
        received: Long,
        total: Long,
        speed: Long,
        etaSeconds: Int,
        error: String,
    ) {
        val json =
            JSONObject().apply {
                put("done", done)
                put("progress", progress)
                put("received", received)
                put("total", total)
                put("speed", speed)
                put("etaSeconds", etaSeconds)
                put("error", error)
            }
        runOnUiThread {
            webView.evaluateJavascript(
                "if(window._onUpdateDownloadProgress)window._onUpdateDownloadProgress($json)",
                null,
            )
        }
    }

    private fun installApkSafely(apkFile: File) {
        try {
            if (!apkFile.exists()) {
                Toast.makeText(this, "APK 文件不存在", Toast.LENGTH_SHORT).show()
                return
            }

            val uri: Uri =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(
                        this,
                        "$packageName.fileprovider",
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

            if (intent.resolveActivity(packageManager) != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!packageManager.canRequestPackageInstalls()) {
                        val permIntent =
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:$packageName")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        startActivity(permIntent)
                        Toast.makeText(this, "请先允许安装未知来源应用，然后再次尝试", Toast.LENGTH_LONG).show()
                        return
                    }
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "未找到可用的安装程序", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "installApk error", e)
            Toast.makeText(this, "安装失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "LandscapeWebActivity"

        // ★ haowallpaper token 持久化（避免每次下载都要扫码登录）
        private const val HAO_TOKEN_PREFS = "hao_wallpaper_prefs"
        private const val HAO_TOKEN_KEY = "hao_token"

        // ★ 用户上传壁纸：服务端 API 基础 URL（CloudBase 云函数）
        private const val WALLPAPER_SERVER_API_BASE = "https://plugin-market-d2gpn5vfb44d821b8.service.tcloudbase.com/plugin-api"

        // ★ 用户上传壁纸：JWT token 持久化（与 JS localStorage mineradio_token 同步）
        private const val MINERADIO_TOKEN_PREFS = "mineradio_prefs"
        private const val MINERADIO_TOKEN_KEY = "mineradio_token"

        // ★ 标记横屏 Activity 是否存活（供 MainActivity 判断是否需要恢复横屏）
        @Volatile
        var isAlive = false

        // ★ PC 版 Chrome User-Agent（用于独立登录 Dialog 强制 PC 版网页）
        private const val PC_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // ★ haowallpaper 下载 JS 注入代码（手动模式）
        //   1. 重写 URL.createObjectURL 拦截大 blob（壁纸文件）
        //   2. 通过 FileReader 读取为 ArrayBuffer，分块 base64 后传回 Android
        //   3. 同时拦截 fetch，确保捕获真实文件
        //   ★ 用户手动操作：不自动点击下载按钮、不自动点击人机验证、不超时检测
        private val HAO_DOWNLOAD_JS_INJECTION = """
(function(){
    if (window.__haoDownloadInjected) return;
    window.__haoDownloadInjected = true;

    // ★ 全局错误捕获，便于调试
    window.addEventListener('error', function(e) {
        console.error('[HaoDownload] window.error: ' + (e.message || '') + ' @ ' + (e.filename || '') + ':' + (e.lineno || 0));
    });
    window.addEventListener('unhandledrejection', function(e) {
        console.error('[HaoDownload] unhandledrejection: ' + (e.reason && e.reason.message ? e.reason.message : e.reason));
    });

    console.log('[HaoDownload] injection starting (manual mode), location=' + window.location.href);

    var CHUNK_SIZE = 512 * 1024; // 512KB per chunk
    var downloading = false;

    function arrayBufferToBase64(buffer) {
        var binary = '';
        var bytes = new Uint8Array(buffer);
        var len = bytes.length;
        var chunk = 8192;
        for (var i = 0; i < len; i += chunk) {
            var end = Math.min(i + chunk, len);
            binary += String.fromCharCode.apply(null, bytes.subarray(i, end));
        }
        return btoa(binary);
    }

    function handleBlob(blob, fileName) {
        if (downloading) return;
        if (!blob || blob.size < 200000) return;

        // ★ 严格 MIME 类型检查：只接受 video/* 和 image/*
        //   拒绝 application/json、text/html、text/plain 等非媒体类型
        //   防止 haowallpaper API 错误响应（如 {"status":305,...}）被当作壁纸文件保存
        var mime = (blob.type || '').toLowerCase();
        var isVideo = mime.indexOf('video/') === 0 || mime.indexOf('mp4') >= 0;
        var isImage = mime.indexOf('image/') === 0;
        if (!isVideo && !isImage) {
            console.warn('[HaoDownload] reject blob (non-media mime): mime=' + mime + ', size=' + blob.size);
            // 尝试读取前 256 字节判断是否是 JSON 文本
            try {
                blob.slice(0, 256).text().then(function(txt) {
                    console.warn('[HaoDownload] rejected blob head: ' + txt.substring(0, 200));
                    try { AndroidHao.apiError('非媒体类型: ' + mime + ' / ' + txt.substring(0, 100)); } catch(e) {}
                }).catch(function(){});
            } catch(e) {}
            return;
        }

        downloading = true;
        try {
            var name = fileName || ('wallpaper.' + (isVideo ? 'mp4' : 'jpg'));
            console.log('[HaoDownload] capture blob: name=' + name + ', mime=' + mime + ', size=' + blob.size);
            AndroidHao.startDownload(name, mime, blob.size);

            var reader = new FileReader();
            reader.onload = function(e) {
                try {
                    var buf = e.target.result;
                    // ★ Magic bytes 二次验证：防止错误的 MIME 类型标签
                    //   真实视频/图片的前几个字节有固定签名，JSON 响应以 "{" (0x7B) 开头
                    var bytes = new Uint8Array(buf);
                    var head = bytes.length >= 4 ? String.fromCharCode(bytes[0], bytes[1], bytes[2], bytes[3]) : '';
                    var isJson = bytes.length >= 1 && bytes[0] === 0x7B; // '{'
                    var isHtml = bytes.length >= 1 && (bytes[0] === 0x3C); // '<'
                    // MP4: "ftyp" 在 offset 4-7
                    var isMp4 = bytes.length >= 8 &&
                        String.fromCharCode(bytes[4], bytes[5], bytes[6], bytes[7]) === 'ftyp';
                    // JPEG: FF D8 FF
                    var isJpeg = bytes.length >= 3 && bytes[0] === 0xFF && bytes[1] === 0xD8 && bytes[2] === 0xFF;
                    // PNG: 89 50 4E 47
                    var isPng = bytes.length >= 4 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4E && bytes[3] === 0x47;
                    // WebM: 1A 45 DF A3
                    var isWebm = bytes.length >= 4 && bytes[0] === 0x1A && bytes[1] === 0x45 && bytes[2] === 0xDF && bytes[3] === 0xA3;
                    // GIF: 47 49 46 38
                    var isGif = bytes.length >= 4 && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x38;

                    if (isJson || isHtml) {
                        // 内容实际是 JSON/HTML 文本，不是真正的视频/图片
                        var headStr = '';
                        try {
                            headStr = String.fromCharCode.apply(null, bytes.subarray(0, Math.min(200, bytes.length)));
                        } catch(_){}
                        console.error('[HaoDownload] magic bytes check FAILED: mime=' + mime + ' but content is text/json: ' + headStr.substring(0, 200));
                        try { AndroidHao.cancelDownload('下载失败：服务器返回的是错误响应而非真实文件'); } catch(_){}
                        try { AndroidHao.apiError('非真实文件: ' + headStr.substring(0, 100)); } catch(e) {}
                        downloading = false;
                        return;
                    }
                    // MIME 标签 vs Magic bytes 不一致时，按 magic bytes 校正
                    if (isVideo && !isMp4 && !isWebm) {
                        // 声称是视频但 magic bytes 不对，再次用图片签名验证
                        if (isJpeg || isPng || isGif) {
                            // 实际是图片，校正
                            mime = isPng ? 'image/png' : (isJpeg ? 'image/jpeg' : 'image/gif');
                            if (name.indexOf('.mp4') >= 0) {
                                name = name.replace(/\.mp4$/i, isPng ? '.png' : (isJpeg ? '.jpg' : '.gif'));
                            }
                            console.warn('[HaoDownload] mime corrected: video -> ' + mime + ', name=' + name);
                        } else if (bytes.length >= 4 && bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 && bytes[3] === 0x46) {
                            // PDF - 不是壁纸
                            console.error('[HaoDownload] reject PDF');
                            try { AndroidHao.cancelDownload('下载失败：PDF 文件不是壁纸'); } catch(_){}
                            downloading = false;
                            return;
                        }
                        // 其他情况接受（可能是不常见视频编码）
                    }

                    var b64 = arrayBufferToBase64(buf);
                    var len = b64.length;
                    var step = 700 * 1024;
                    for (var off = 0; off < len; off += step) {
                        var chunkStr = b64.substring(off, Math.min(off + step, len));
                        AndroidHao.appendChunk(chunkStr);
                    }
                    AndroidHao.finishDownload();
                    console.log('[HaoDownload] done, sent ' + len + ' base64 chars, head=' + head);
                } catch (err) {
                    console.error('[HaoDownload] chunk failed: ' + err);
                    AndroidHao.cancelDownload('分块传输失败: ' + err);
                }
            };
            reader.onerror = function() {
                AndroidHao.cancelDownload('读取文件失败');
            };
            reader.readAsArrayBuffer(blob);
        } catch (e) {
            console.error('[HaoDownload] handleBlob error: ' + e);
            AndroidHao.cancelDownload('处理文件失败: ' + e);
            downloading = false;
        }
    }

    // ── 拦截 URL.createObjectURL ──
    var origCreateObjectURL = URL.createObjectURL;
    URL.createObjectURL = function(obj) {
        var url = origCreateObjectURL.call(this, obj);
        try {
            if (obj instanceof Blob && obj.size > 200000 && !downloading) {
                handleBlob(obj, '');
            }
        } catch (e) {
            console.error('[HaoDownload] createObjectURL hook error: ' + e);
        }
        return url;
    };

    // ── 拦截 fetch（监测响应是 blob 的大文件 + 检测 API 错误响应） ──
    var origFetch = window.fetch;
    if (origFetch) {
        window.fetch = function(input, init) {
            return origFetch.apply(this, arguments).then(function(response) {
                try {
                    var urlStr = (typeof input === 'string') ? input : (input && input.url) || '';
                    // 检测 getCompleteUrl API 错误响应（手动模式下仅记录日志 + 通知 apiError）
                    if (urlStr.indexOf('getCompleteUrl') >= 0 || urlStr.indexOf('common/file') >= 0) {
                        response.clone().text().then(function(txt) {
                            try {
                                if (!txt) return;
                                if (txt.indexOf('"status":') >= 0 && txt.indexOf('"status":200') < 0 && txt.indexOf('"status": 200') < 0) {
                                    console.log('[HaoDownload] API error: ' + txt.substring(0, 200));
                                    try { AndroidHao.apiError(txt.substring(0, 500)); } catch(e) {}
                                }
                            } catch(_) {}
                        }).catch(function(){});
                    }
                    // 拦截大 blob
                    var cl = response.headers && response.headers.get('content-length');
                    var size = cl ? parseInt(cl) : 0;
                    if (size > 200000 && !downloading) {
                        response.clone().blob().then(function(blob) {
                            if (blob && blob.size > 200000) {
                                var name = '';
                                try {
                                    var m = urlStr.match(/\/([^\/\?]+)\.(mp4|jpg|jpeg|png|webm|webp)(\?|$)/i);
                                    if (m) name = m[1] + '.' + m[2];
                                } catch (_) {}
                                handleBlob(blob, name);
                            }
                        }).catch(function(){});
                    }
                } catch (e) {
                    console.error('[HaoDownload] fetch hook error: ' + e);
                }
                return response;
            });
        };
    }

    // ★ 手动模式：超时检测、自动点击人机验证、自动点击下载按钮、主动调用下载 API 均已移除
    //   用户在嵌入式小窗中手动完成验证、手动点击下载按钮、手动扫码登录
    var __manualModePlaceholder = true;

    // ★ 定期轮询保存 token：用户扫码登录后 SPA 路由变化不一定触发 onPageFinished
    //   每 2 秒检查一次 localStorage.token 和 document.cookie，发现 token 就调用 saveToken
    //   连续 150 次未发现新 token 后停止轮询（5 分钟，给用户足够时间扫码登录）
    var __lastSavedToken = '';
    var __tokenPollCount = 0;
    var __tokenPollMax = 150;
    var __tokenPollTimer = setInterval(function() {
        try {
            __tokenPollCount++;
            if (__tokenPollCount > __tokenPollMax) {
                clearInterval(__tokenPollTimer);
                return;
            }
            var lsToken = '';
            try { lsToken = localStorage.getItem('token') || localStorage.getItem('hao_token') || ''; } catch(_) {}
            var cookieToken = '';
            try {
                var cookies = document.cookie ? document.cookie.split(';') : [];
                for (var i = 0; i < cookies.length; i++) {
                    var c = cookies[i].trim();
                    if (c.indexOf('token=') === 0) {
                        cookieToken = c.substring('token='.length);
                        break;
                    }
                }
            } catch(_) {}
            var finalToken = lsToken || cookieToken;
            if (finalToken && finalToken.length > 10 && finalToken !== __lastSavedToken) {
                __lastSavedToken = finalToken;
                try { AndroidHao.saveToken(finalToken); } catch(e) {}
                console.log('[HaoDownload] token saved via polling (len=' + finalToken.length + ')');
                // 找到 token 后可以提前结束轮询
                clearInterval(__tokenPollTimer);
            }
        } catch(e) {
            console.error('[HaoDownload] token poll error: ' + e);
        }
    }, 2000);

    // ★ 手动模式：以下自动点击下载按钮、dumpPageInfo 诊断、clickTimer 轮询、triggerDownloadApi 兜底
    //   全部移除 — 用户在小窗中手动点击下载按钮、手动完成验证、手动扫码登录
    console.log('[HaoDownload] injection ready (manual mode), body children=' + (document.body ? document.body.children.length : 0));
})();
"""

        private val BRIDGE_JS =
            """
(function(){
    // ═══ 安卓横屏适配 ═══

    // ── A0. 去除蓝色点击高亮 + 禁止长按选择 ──
    (function(){
        var style0 = document.createElement('style');
        style0.textContent =
            '* { -webkit-tap-highlight-color:transparent!important; -webkit-touch-callout:none!important; -webkit-user-select:none!important; user-select:none!important; } ' +
            'input,textarea,[contenteditable] { -webkit-user-select:text!important; user-select:text!important; } ' +
            '*:focus { outline:none!important; }';
        document.head.appendChild(style0);
    })();

    // ── A. 整体 UI 缩放 80% ──
    (function(){
        var style = document.createElement('style');
        style.textContent = 'html, body { zoom: 0.80; -moz-transform: scale(0.80); }';
        document.head.appendChild(style);
        // 启动动画补偿缩放，保持全屏效果（0.80 × 1.25 = 1.0）
        var splashStyle = document.createElement('style');
        splashStyle.textContent = '#splash { zoom: 1.25; }';
        document.head.appendChild(splashStyle);
    })();

    // ── B. 删除 DIY 视觉引导 ──
    window.markVisualGuideSeen = function(){};
    window.visualGuideWasSeen = function(){ return true; };
    window.maybeRunStartupVisualGuide = function(){ return false; };
    window.startVisualGuide = function(){};
    try { localStorage.setItem('mineradio-visual-guide-seen-v2', '1'); } catch(e) {}

    // ── D. 音质默认 standard ──
    if (!localStorage.getItem('mineradio-playback-quality-v1')) {
        try { localStorage.setItem('mineradio-playback-quality-v1', 'standard'); } catch(e) {}
    }

    // ── E. 全屏按钮 → 沉浸 ──
    (function(){
        var btn = document.querySelector('.fullscreen-toggle-btn');
        if (btn) btn.style.display = 'none';
        window.toggleFullscreen = function(){
            if (window.toggleImmersiveMode) window.toggleImmersiveMode();
        };
    })();

    // ── F. 底部白条: 常驻显示，点白条弹控制栏 ──
    (function(){
        var style2 = document.createElement('style');
        style2.textContent =
          'body.controls-visible #bottom-handle { opacity:0!important; pointer-events:none!important; }';
        document.head.appendChild(style2);

        function isBarVisible() {
            var bar = document.getElementById('bottom-bar');
            return !!(bar && bar.classList.contains('visible'));
        }

        var handle = document.getElementById('bottom-handle');
        if (handle) {
            handle.addEventListener('touchstart', function(e){
                e.stopPropagation(); e.preventDefault();
                if (typeof window.toggleBottomControlsFromHandle === 'function') window.toggleBottomControlsFromHandle();
            }, {passive:false});
        } else {
            var _retryHandle = setInterval(function(){
                var h = document.getElementById('bottom-handle');
                if (h) { clearInterval(_retryHandle);
                    h.addEventListener('touchstart', function(ev){
                        ev.stopPropagation(); ev.preventDefault();
                        if (typeof window.toggleBottomControlsFromHandle === 'function') window.toggleBottomControlsFromHandle();
                    }, {passive:false});
                }
            }, 300);
        }
    })();

    // ── G. 显示 fx-fab（右下角设置按钮）+ diy 模式 ──
    document.documentElement.classList.remove('simple-mode-preload');
    document.body.classList.remove('simple-mode');
    window.diyPlayerMode = true;
    try { localStorage.setItem('mineradio-diy-player-mode-v1', '1'); } catch(e) {}

    // ── K. 音频解锁 ──
    var _androidAudioUnlocked = false;
    function unlockAndroidAudio() {
        if (_androidAudioUnlocked) return;
        _androidAudioUnlocked = true;
        try {
            var ctx = new (window.AudioContext || window.webkitAudioContext)();
            var buf = ctx.createBuffer(1, 1, 22050);
            var src = ctx.createBufferSource();
            src.buffer = buf; src.connect(ctx.destination); src.start(0);
            ctx.resume().catch(function(){});
            setTimeout(function(){ try { ctx.close(); } catch(e) {} }, 300);
        } catch(e) {}
    }
    document.addEventListener('touchstart', unlockAndroidAudio, {capture:true,passive:false,once:true});
    document.addEventListener('pointerdown', unlockAndroidAudio, {capture:true,passive:false,once:true});

    // ── L. 触摸旋转 3D ──
    (function(){
        function initTouchControls() {
            var canvas = window.renderer && window.renderer.domElement;
            if (!canvas) { setTimeout(initTouchControls, 300); return; }

            var touchState = { active: false, lastTouchX: 0, lastTouchY: 0, lastPinchDist: 0, startTouchX: 0, startTouchY: 0 };
            var lastTapTime = 0;
            var DOUBLE_TAP_MS = 320;
            var CLICK_THRESHOLD = 6;

            function getTouchDistance(touches) {
                var dx = touches[0].clientX - touches[1].clientX;
                var dy = touches[0].clientY - touches[1].clientY;
                return Math.sqrt(dx*dx + dy*dy);
            }
            function dispatchFakeMouse(type, x, y) {
                var evt = new MouseEvent(type, { clientX: x, clientY: y, button: 0, bubbles: true, cancelable: true });
                canvas.dispatchEvent(evt);
            }
            function dispatchFakeClick(x, y) {
                var evt = new MouseEvent('click', { clientX: x, clientY: y, button: 0, bubbles: true, cancelable: true });
                canvas.dispatchEvent(evt);
            }
            function dispatchFakeWheel(deltaY, x, y) {
                var evt = new WheelEvent('wheel', { deltaY: deltaY, clientX: x != null ? x : touchState.lastTouchX, clientY: y != null ? y : touchState.lastTouchY, bubbles: true, cancelable: true });
                canvas.dispatchEvent(evt);
            }

            canvas.addEventListener('touchstart', function(e) {
                e.preventDefault();
                if (e.touches.length === 1) {
                    touchState.active = true;
                    touchState.lastTouchX = e.touches[0].clientX;
                    touchState.lastTouchY = e.touches[0].clientY;
                    touchState.startTouchX = e.touches[0].clientX;
                    touchState.startTouchY = e.touches[0].clientY;
                    dispatchFakeMouse('mousedown', e.touches[0].clientX, e.touches[0].clientY);
                } else if (e.touches.length === 2) {
                    touchState.lastPinchDist = getTouchDistance(e.touches);
                    if (touchState.active) {
                        dispatchFakeMouse('mouseup', touchState.lastTouchX, touchState.lastTouchY);
                        touchState.active = false;
                    }
                }
            }, { passive: false });

            canvas.addEventListener('touchmove', function(e) {
                e.preventDefault();
                if (e.touches.length === 1 && touchState.active) {
                    var curX = e.touches[0].clientX;
                    var curY = e.touches[0].clientY;
                    dispatchFakeMouse('mousemove', curX, curY);
                    if (typeof window.shelfManager !== 'undefined' && window.shelfManager) {
                        var shelfMode = null;
                        try { shelfMode = window.shelfManager.getMode && window.shelfManager.getMode(); } catch(x) {}
                        var shelfOpen = false;
                        try { shelfOpen = !!(window.shelfManager.hasOpenContent && window.shelfManager.hasOpenContent()); } catch(x) {}
                        if (shelfMode === 'side' || shelfMode === 'stage' || shelfOpen) {
                            var dy = touchState.lastTouchY - curY;
                            if (Math.abs(dy) > 1) { dispatchFakeWheel(dy * 0.6, curX, curY); }
                        }
                    }
                    touchState.lastTouchX = curX;
                    touchState.lastTouchY = curY;
                } else if (e.touches.length === 2) {
                    var currentDist = getTouchDistance(e.touches);
                    var delta = touchState.lastPinchDist - currentDist;
                    if (Math.abs(delta) > 0.5) {
                        dispatchFakeWheel(delta * 0.8);
                        touchState.lastPinchDist = currentDist;
                    }
                }
            }, { passive: false });

            canvas.addEventListener('touchend', function(e) {
                e.preventDefault();
                if (e.touches.length === 0 && touchState.active) {
                    var finalX = touchState.lastTouchX;
                    var finalY = touchState.lastTouchY;
                    dispatchFakeMouse('mouseup', finalX, finalY);
                    var dx = finalX - (touchState.startTouchX || finalX);
                    var dy = finalY - (touchState.startTouchY || finalY);
                    if (Math.sqrt(dx*dx + dy*dy) < CLICK_THRESHOLD) {
                        dispatchFakeClick(finalX, finalY);
                        var now = Date.now();
                        if (now - lastTapTime < DOUBLE_TAP_MS) {
                            lastTapTime = 0;
                            if (typeof window.recenterCamera === 'function') window.recenterCamera();
                        } else {
                            lastTapTime = now;
                        }
                    }
                    touchState.active = false;
                } else if (e.touches.length === 1) {
                    touchState.active = true;
                    touchState.lastTouchX = e.touches[0].clientX;
                    touchState.lastTouchY = e.touches[0].clientY;
                    touchState.startTouchX = e.touches[0].clientX;
                    touchState.startTouchY = e.touches[0].clientY;
                    dispatchFakeMouse('mousedown', e.touches[0].clientX, e.touches[0].clientY);
                }
            }, { passive: false });

            canvas.addEventListener('touchcancel', function(e) {
                if (touchState.active) {
                    dispatchFakeMouse('mouseup', touchState.lastTouchX, touchState.lastTouchY);
                    touchState.active = false;
                }
            });
        }
        if (window.renderer && window.renderer.domElement) initTouchControls();
        else window.addEventListener('DOMContentLoaded', function(){ initTouchControls(); }, {once:true});
    })();

    // ── N. 静音 splash ──
    window.splashSoundPlayed = true;
    window.splashAudioCtx = null;
    window.playMineradioIntroSound = function(){ window.splashSoundPlayed = true; };

    // ── O. 修复粒子 ──
    window.revealIdleParticles = function(target, durationMs) {
        if (window.uniforms && window.uniforms.uAlpha) window.uniforms.uAlpha.value = 0.96;
        if (window.uniforms && window.uniforms.uFloatAlpha) window.uniforms.uFloatAlpha.value = 1.0;
        document.body.classList.remove('render-deep-sleep');
    };

    // ── P. Splash 后显示主页 ──
    window.homeForcedOpen = true;
    window.playing = false;
    window.currentIdx = -1;
    if (!window.immersiveMode) window.immersiveMode = false;
    if (!window.shelfPinnedOpen) window.shelfPinnedOpen = false;
    window.hasActivePlaybackControls = function(){ return false; };
    window.closeMiniQueue = function(){};
    window.closeFxPanel = function(){};
    window.setHomeControlsLocked = function(){};
    window.shouldShowEmptyHomeAfterSplash = function(){ return true; };
    window.shouldForceEmptyHomeAfterSplash = function(){ return true; };
    window.shouldUseIdleWallpaperPreview = function(){ return true; };

    // ── Q. 音频代理 ──
    (function(){
        var origDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
        if (origDesc && origDesc.set) {
            var origSet = origDesc.set;
            Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                set: function(url) {
                    if (typeof url === 'string') {
                        if (url.startsWith('content://')) {
                            url = '/api/local-file?uri=' + encodeURIComponent(url);
                        } else if (url.charAt(0) === '/' && !url.startsWith('/api/') && !url.startsWith('/local-song/') && !url.startsWith('/assets/') && !url.startsWith('/qs-cache/') && !url.startsWith('/music-cache/') && !url.startsWith('/room-song/') && !url.startsWith('/room-cover/') && !url.startsWith('/local-cover/') && !url.startsWith('/local-lyric/')) {
                            url = '/api/local-file?path=' + encodeURIComponent(url);
                        } else if (url.startsWith('http') && !url.includes('127.0.0.1') && !url.includes('localhost') && !url.startsWith('blob:')) {
                            url = '/api/audio?url=' + encodeURIComponent(url);
                        }
                    }
                    origSet.call(this, url);
                },
                get: origDesc.get,
                configurable: true, enumerable: true
            });
        }
    })();

    // ── R. Android 返回键: 逐层关闭面板 ──
    window.handleAndroidBack = function() {
        function isVisible(el) {
            if (!el) return false;
            var r = el.getBoundingClientRect();
            return r.width > 0 && r.height > 0 && r.right > -50 && r.bottom > -50 && r.left < window.innerWidth + 50 && r.top < window.innerHeight + 50;
        }
        if (typeof shelfFocusLocked !== 'undefined' && shelfFocusLocked) {
            shelfFocusLocked = false;
            if (typeof setFocusZone === 'function') setFocusZone(null, true);
            if (typeof recenterCamera === 'function') recenterCamera();
            return JSON.stringify({closed:true});
        }
        var dm = document.getElementById('track-detail-modal');
        if (isVisible(dm)) {
            if (typeof window.closeTrackDetailModal === 'function') window.closeTrackDetailModal();
            else { dm.style.display = 'none'; dm.classList.remove('show'); }
            return JSON.stringify({closed:true});
        }
        var modals = document.querySelectorAll('.modal:not(.track-detail-modal)');
        for (var mi = 0; mi < modals.length; mi++) {
            var m = modals[mi];
            if (isVisible(m)) {
                if (typeof window.closeLoginModal === 'function' && m.id === 'login-modal') { window.closeLoginModal(); return JSON.stringify({closed:true}); }
                m.style.display = 'none'; m.classList.remove('show'); return JSON.stringify({closed:true});
            }
        }
        var sa = document.getElementById('search-area');
        if (sa && sa.classList.contains('peek')) {
            if (typeof window.setPeek === 'function') window.setPeek(sa, false, 'search');
            else sa.classList.remove('peek');
            return JSON.stringify({closed:true});
        }
        var pp = document.getElementById('playlist-panel');
        if (pp && pp.classList.contains('peek')) {
            if (typeof window.setPeek === 'function') window.setPeek(pp, false, 'pl');
            else pp.classList.remove('peek');
            return JSON.stringify({closed:true});
        }
        var fx = document.getElementById('fx-panel');
        if (fx && (fx.classList.contains('peek') || fx.classList.contains('show'))) {
            if (typeof window.closeFxPanel === 'function') window.closeFxPanel();
            else fx.classList.remove('peek', 'show', 'closing');
            return JSON.stringify({closed:true});
        }
        if (window.miniQueueOpen) {
            if (typeof window.closeMiniQueue === 'function') window.closeMiniQueue();
            return JSON.stringify({closed:true});
        }
        if (window.immersiveMode) {
            if (typeof window.setImmersiveMode === 'function') window.setImmersiveMode(false);
            return JSON.stringify({closed:true});
        }
        return JSON.stringify({closed:false});
    };

    // ── S0. void模式强制显示自定义背景 ──
    (function(){
        var styleBg = document.createElement('style');
        styleBg.textContent =
            'body.custom-background-override #custom-bg { display:block!important; } ' +
            'body.custom-background-override #custom-bg::before { opacity:var(--custom-bg-image-opacity,1)!important; }';
        document.head.appendChild(styleBg);
    })();

    // ── S. XenoEngine 原生手势推理桥接 (CameraX + MediaPipe HandLandmarker) ──
    // 拦截 startGestureControl：优先使用原生 CameraX + MediaPipe，绕过 JS 版 hands.js 加载
    (function(){
        if (window._nativeGestureBridgeReady) return;
        window._nativeGestureBridgeReady = true;
        window._nativeGestureActive = false;
        window._nativeGestureModelReady = false;

        // 原生手势识别结果回调 → 转发给 processHandFrame
        window._nativeHandCallback = function(data) {
            try {
                if (!window._nativeGestureActive && !window._nativeGestureStarting) return; // 手势已关闭，忽略残留回调
                if (!data || !data.lm || data.lm.length < 21) return;
                if (!window.gestureActive) {
                    window.gestureActive = true;
                    window._nativeGestureActive = true;
                    window._nativeGestureStarting = false;
                    // ★ 初始化 handCanvas（原生路径未走 JS 版 startGestureControl，需手动初始化）
                    if (!window.handCanvas) {
                        window.handCanvas = document.getElementById('hand-canvas');
                        if (window.handCanvas) {
                            window.handCanvasCtx = window.handCanvas.getContext('2d');
                            if (typeof window.resizeHandCanvas === 'function') window.resizeHandCanvas();
                            window.handCanvas.classList.add('show');
                        }
                    }
                    // ★ 显示手势 HUD
                    if (typeof window.showGestureHUD === 'function') window.showGestureHUD('待命', 0, '把手放进视野');
                    if (typeof showToast === 'function') showToast('手势已开启: 手掌推开 · 捏合旋转 · 握拳收束');
                }
                // 将 {x,y,z} 对象数组传给 processHandFrame
                if (typeof window.processHandFrame === 'function') {
                    window.processHandFrame(data.lm);
                }
            } catch(e) { console.warn('[NativeGesture] cb error:', e); }
        };

        // 无手帧回调 → 转发给 onHandLost 清除屏幕骨骼
        window._nativeHandNoop = function() {
            if (!window._nativeGestureActive) return; // 手势已关闭，忽略残留回调
            if (typeof window.onHandLost === 'function') window.onHandLost();
        };

        // 拦截 startGestureControl：直接走原生路径
        if (typeof window.startGestureControl === 'function') {
            var _origStartGesture = window.startGestureControl;
            window._nativeGestureStarting = false;
            // 原生手势加载失败时的回退回调
            window._onNativeGestureFailed = function(reason) {
                console.warn('[Gesture] 原生手势加载失败, reason=' + reason);
                window._nativeGestureStarting = false;
                window._nativeGestureActive = false;
                // ★ 根据失败原因显示准确提示
                var msg = '手势识别启动失败';
                if (reason === 'model') msg = '手势模型加载失败，请重启应用重试';
                else if (reason === 'camera') msg = '摄像头启动失败，请检查权限';
                else if (reason === 'timeout') msg = '手势识别启动超时，请重试';
                if (typeof showToast === 'function') showToast(msg);
                // ★ 不回退到 JS MediaPipe（当前项目未引入 MediaPipe JS CDN，会导致"HANDS未定义"）
            };
            // ★ 原生手势模型就绪回调（模型加载成功，等待第一帧）
            window._onNativeGestureReady = function() {
                if (typeof showToast === 'function') showToast('手势模型已加载，等待摄像头...');
                // 消除超时定时器
                if (window._nativeGestureTimeoutId) {
                    clearTimeout(window._nativeGestureTimeoutId);
                    window._nativeGestureTimeoutId = null;
                }
            };
            window.startGestureControl = async function() {
                if (window._nativeGestureStarting) return; // 防止重复调用
                // 优先尝试原生手势推理
                if (window.KeepApp && typeof window.KeepApp.startNativeHandGesture === 'function') {
                    try {
                        console.log('[Gesture] 启动原生手势推理 (CameraX + MediaPipe)');
                        window._nativeGestureStarting = true;
                        var result = window.KeepApp.startNativeHandGesture();
                        console.log('[Gesture] 原生启动结果:', result);
                        try { var r = JSON.parse(result); } catch(e) { var r = {ok:false}; }
                        if (r.ok) {
                            // 原生启动请求已接受，等待 _nativeHandCallback 或 _onNativeGestureFailed
                            if (typeof showToast === 'function') showToast('正在加载手势模型…');
                            // 设置30秒超时（模型加载较慢时给足时间）
                            window._nativeGestureTimeoutId = setTimeout(function() {
                                if (window._nativeGestureStarting && !window._nativeGestureActive) {
                                    console.warn('[Gesture] 原生手势30秒超时');
                                    window._onNativeGestureFailed('timeout');
                                }
                            }, 30000);
                            return;
                        } else {
                            console.warn('[Gesture] 原生启动失败:', r.error);
                            window._nativeGestureStarting = false;
                        }
                    } catch(e) {
                        console.warn('[Gesture] 原生手势启动异常:', e);
                        window._nativeGestureStarting = false;
                    }
                }
                // ★ 不回退到 JS MediaPipe（未引入 CDN）
                if (typeof showToast === 'function') showToast('手势识别不可用');
            };
        }

        // 拦截 stopGestureControl：同时停止原生推理
        if (typeof window.stopGestureControl === 'function') {
            var _origStopGesture = window.stopGestureControl;
            window.stopGestureControl = function() {
                if (window._nativeGestureActive && window.KeepApp && typeof window.KeepApp.stopNativeHandGesture === 'function') {
                    try { window.KeepApp.stopNativeHandGesture(); } catch(e) {}
                }
                window._nativeGestureActive = false;
                window._nativeGestureStarting = false;
                var ret = _origStopGesture.apply(this, arguments);
                // ★ 确保手势 HUD 和 handCanvas 被隐藏（防止残留回调重新显示）
                var hud = document.getElementById('gesture-hud');
                if (hud) hud.classList.remove('show');
                if (window.handCanvas) window.handCanvas.classList.remove('show');
                if (window.handCanvasCtx) window.handCanvasCtx.clearRect(0, 0, window.handCanvas.width, window.handCanvas.height);
                return ret;
            };
        }

        console.log('[LandscapeWebActivity] Native gesture bridge ready');
    })();

    // ── S. 第三方平台登录覆盖（QQ/酷狗/网易云/汽水）──
    // 覆盖 openQQWebLogin / openProviderWebLogin → 走 KeepApp 独立 Dialog
    (function(){
        // QQ 音乐登录回调
        window._onQQLoginCookie = async function(rawCookie) {
            try {
                if (!rawCookie || rawCookie.trim() === '') { if(window.showToast)window.showToast('QQ登录未完成'); return; }
                if(window.showToast)window.showToast('正在同步QQ音乐…');
                var info = await window.apiJson('/api/qq/login/cookie', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ cookie: rawCookie })
                });
                if (!info || !info.loggedIn) throw new Error((info&&(info.message||info.error))||'QQ会话不可用');
                window.qqLoginStatus = info; window.activeAccountProvider = 'qq';
                window.qqManualCookieOpen = false;
                if(typeof window.renderUserBtn==='function')window.renderUserBtn();
                if(typeof window.refreshUserPlaylists==='function')window.refreshUserPlaylists(true);
                if(typeof window.closeLoginModal==='function')window.closeLoginModal();
                var ready = !!info.playbackKeyReady;
                if(window.showToast)window.showToast((ready?'QQ音乐已登录: ':'账号已同步: ')+(info.nickname||info.userId||''));
            } catch(e) { if(window.showToast)window.showToast('QQ同步失败: '+(e&&e.message?e.message:'网络错误')); }
        };
        // 酷狗音乐登录回调
        window._onKugouLoginCookie = async function(rawCookie) {
            try {
                if (!rawCookie || rawCookie.trim() === '') { if(window.showToast)window.showToast('酷狗登录未完成'); return; }
                if(window.showToast)window.showToast('正在同步酷狗…');
                var info = await window.apiJson('/api/kg/login/cookie', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ cookie: rawCookie })
                });
                if (!info || !info.loggedIn) throw new Error((info&&(info.message||info.error))||'酷狗会话不可用');
                window.kgLoginStatus = info; window.activeAccountProvider = 'kg';
                if(typeof window.renderUserBtn==='function')window.renderUserBtn();
                if(typeof window.refreshUserPlaylists==='function')window.refreshUserPlaylists(true);
                if(typeof window.closeLoginModal==='function')window.closeLoginModal();
                if(window.showToast)window.showToast('酷狗音乐已登录: '+(info.nickname||info.userId||''));
            } catch(e) { if(window.showToast)window.showToast('酷狗同步失败: '+(e&&e.message?e.message:'网络错误')); }
        };
        // 网易云登录回调
        window._onNeteaseLoginCookie = async function(rawCookie) {
            try {
                if (!rawCookie || rawCookie.trim() === '') { if(window.showToast)window.showToast('网易云登录未完成'); return; }
                if(window.showToast)window.showToast('正在同步网易云…');
                var info = await window.apiJson('/api/login/cookie', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ cookie: rawCookie })
                });
                if (!info || !info.loggedIn) throw new Error((info&&(info.message||info.error))||'网易云会话不可用');
                window.loginStatus = info; window.activeAccountProvider = 'netease';
                if(typeof window.renderUserBtn==='function')window.renderUserBtn();
                if(typeof window.refreshUserPlaylists==='function')window.refreshUserPlaylists(true);
                if(typeof window.closeLoginModal==='function')window.closeLoginModal();
                if(window.showToast)window.showToast('网易云已登录: '+(info.nickname||info.userId||''));
            } catch(e) { if(window.showToast)window.showToast('网易云同步失败: '+(e&&e.message?e.message:'网络错误')); }
        };
        // 汽水登录回调
        window._onQishuiLoginCookie = async function(rawCookie) {
            try {
                if (!rawCookie || rawCookie.trim() === '') { if(window.showToast)window.showToast('汽水登录未完成'); return; }
                if(window.showToast)window.showToast('正在同步汽水…');
                var info = await window.apiJson('/api/qishui/login/cookie', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ cookie: rawCookie })
                });
                if (!info || !info.loggedIn) throw new Error((info&&(info.message||info.error))||'汽水会话不可用');
                // ★ 修复：使用 qishuiLoginStatus（与前端一致），而非 qsLoginStatus
                window.qishuiLoginStatus = (typeof window.normalizeQishuiLoginStatus==='function')
                    ? window.normalizeQishuiLoginStatus(info)
                    : Object.assign({}, window.qishuiLoginStatus||{}, info);
                window.qishuiLoginStatus.loggedIn = true;
                window.activeAccountProvider = 'qishui';
                if(typeof window.markLoginWorkflowConnected==='function')window.markLoginWorkflowConnected('qishui');
                if(typeof window.renderUserBtn==='function')window.renderUserBtn();
                if(typeof window.refreshUserPlaylists==='function')window.refreshUserPlaylists(true);
                if(typeof window.closeLoginModal==='function')window.closeLoginModal();
                if(window.showToast)window.showToast('汽水已登录: '+(info.nickname||info.userId||''));
            } catch(e) { if(window.showToast)window.showToast('汽水同步失败: '+(e&&e.message?e.message:'网络错误')); }
        };

        // 覆盖 openQQWebLogin → 走 KeepApp.openQQMusicLogin
        var _origQQ = window.openQQWebLogin;
        if (_origQQ) {
            window.openQQWebLogin = function() {
                if (typeof KeepApp !== 'undefined' && KeepApp.openQQMusicLogin) {
                    if(window.showToast)window.showToast('正在打开QQ音乐登录页…');
                    KeepApp.openQQMusicLogin('');
                } else { _origQQ(); }
            };
        }
        // 覆盖 openProviderWebLogin → 各平台走内置
        var _origProvider = window.openProviderWebLogin;
        if (_origProvider) {
            window.openProviderWebLogin = function() {
                if (window.loginProvider === 'qq') {
                    if (typeof KeepApp !== 'undefined' && KeepApp.openQQMusicLogin) {
                        if(window.showToast)window.showToast('正在打开QQ音乐登录页…');
                        KeepApp.openQQMusicLogin(''); return;
                    }
                }
                if (window.loginProvider === 'kg') {
                    if (typeof KeepApp !== 'undefined' && KeepApp.openKugouMusicLogin) {
                        if(window.showToast)window.showToast('正在打开酷狗登录页…');
                        KeepApp.openKugouMusicLogin(''); return;
                    }
                }
                if (window.loginProvider === 'qishui') {
                    // ★ 汽水音乐：不走扫码，走原逻辑（手机端登录/PC端登录选择对话框）
                    _origProvider(); return;
                }
                // ★ 网易云：不使用官方窗口，走原逻辑（内置二维码扫码登录）
                _origProvider();
            };
        }
        // 覆盖 openKugouWebLogin → 走 KeepApp.openKugouMusicLogin
        var _origKG = window.openKugouWebLogin;
        if (_origKG) {
            window.openKugouWebLogin = function() {
                if (typeof KeepApp !== 'undefined' && KeepApp.openKugouMusicLogin) {
                    if(window.showToast)window.showToast('正在打开酷狗登录页…');
                    KeepApp.openKugouMusicLogin('');
                } else { _origKG(); }
            };
        }
        // ★ 网易云：不覆盖 openNeteaseWebLogin，保留原逻辑（内置二维码扫码登录）
        // ★ 汽水音乐登录：不覆盖 openQishuiWebLogin，保留原逻辑（手机端登录/PC端登录选择对话框）
        // 原逻辑会调用 showQSLoginChoiceDialog() 弹出选择框，支持自动读取 sessionid 或手动粘贴
        console.log('[LandscapeWebActivity] Login bridge ready');
    })();

    // ── P. 粒子效果模式：隐藏登录/歌单/主页/搜索/控制栏，只保留背景/粒子山/歌词/设置 ──
    if (location.search.indexOf('mode=particle') !== -1) {
        window.__PARTICLE_MODE__ = true;
        // ★ 注意：不能隐藏 desktop-window-shell（最外层容器），canvas-container/fx-fab/fx-panel 都在它内部
        var hideIds = [
            'playlist-toggle', 'playlist-hide-btn', 'top-right', 'empty-home',
            'search-area', 'bottom-handle', 'bottom-bar', 'login-modal',
            'user-modal', 'track-detail-modal', 'cover-crop-modal',
            'desktop-titlebar', 'playlist-panel', 'controls-hide-btn',
            'immersive-btn', 'visual-guide-btn', 'update-entry',
            'mobile-update-entry', 'diy-mode-btn', 'login-guide-canvas',
            'playlist-toggle-auto-show', 'user-capsule-auto-show'
        ];
        var pStyle = document.createElement('style');
        pStyle.textContent = hideIds.map(function(id){
            return '#' + id + '{ display:none !important; pointer-events:none !important; }';
        }).join('\n');
        (document.head || document.documentElement).appendChild(pStyle);
        // 标记 body 用于额外 CSS
        if (document.body) document.body.classList.add('particle-mode');
        else document.addEventListener('DOMContentLoaded', function(){
            document.body.classList.add('particle-mode');
        });

        // ★ 跳过启动动画，直接显示粒子背景
        function skipSplashForParticle() {
            var splash = document.getElementById('splash');
            if (splash) {
                splash.style.display = 'none';
                splash.classList.add('hide', 'exiting');
            }
            if (document.body) {
                document.body.classList.remove('splash-active', 'splash-revealing');
                document.body.classList.add('loaded');
            }
            // 显示 canvas-container
            var cc = document.getElementById('canvas-container');
            if (cc) cc.style.opacity = '1';
        }
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', skipSplashForParticle);
        } else {
            skipSplashForParticle();
        }
        // 延迟再次执行确保覆盖
        setTimeout(skipSplashForParticle, 100);
        setTimeout(skipSplashForParticle, 500);

        // ★ 粒子模式：音频频谱注入 — 让动态效果（抖动/涟漪/粒子）跟随竖屏播放
        //   audio 被静音不播放，analyser.getByteFrequencyData 读不到数据，
        //   通过 AudioCapture(Visualizer) 抓系统混音 → window.__feedAudio →
        //   存到 __particleSpectrum → patch analyser 填充 frequencyData
        window.__particleSpectrum = null; // 64 段 FloatArray (0~1)
        var __origFeedAudio = window.__feedAudio;
        window.__feedAudio = function(bins) {
            window.__particleSpectrum = bins;
            // 兼容原逻辑：转发给 sonic-topography iframe
            if (typeof __origFeedAudio === 'function') {
                try { __origFeedAudio(bins); } catch(e) {}
            }
        };
        function patchAnalyserGetFreqData() {
            var targets = [];
            if (typeof analyser !== 'undefined' && analyser && !analyser.__particlePatched) targets.push(analyser);
            if (typeof beatAnalyser !== 'undefined' && beatAnalyser && !beatAnalyser.__particlePatched) targets.push(beatAnalyser);
            if (!targets.length) {
                // 诊断：为什么 analyser 还没就绪
                if (!window.__particlePatchDiag) {
                    window.__particlePatchDiag = 0;
                }
                window.__particlePatchDiag++;
                if (window.__particlePatchDiag <= 3 || window.__particlePatchDiag % 10 === 0) {
                    console.log('[Particle] analyser not ready yet (tick=' + window.__particlePatchDiag +
                        ') audioReady=' + (typeof audioReady !== 'undefined' ? audioReady : '?') +
                        ' analyser=' + (typeof analyser !== 'undefined' ? (analyser ? 'set' : 'null') : 'undef') +
                        ' audio=' + (typeof audio !== 'undefined' ? (audio ? 'set' : 'null') : 'undef'));
                }
                return false;
            }
            targets.forEach(function(an) {
                an.__particlePatched = true;
                an.getByteFrequencyData = function(arr) {
                    var sp = window.__particleSpectrum;
                    if (!sp || !sp.length) { arr.fill(0); return; }
                    var len = arr.length;
                    var srcLen = sp.length;
                    // 把 64 段映射到 len 个 bin：每段重复填充，值 *=255
                    var ratio = srcLen / len;
                    for (var i = 0; i < len; i++) {
                        var srcIdx = Math.min(srcLen - 1, Math.floor(i * ratio));
                        arr[i] = Math.max(0, Math.min(255, Math.round(sp[srcIdx] * 255)));
                    }
                };
            });
            console.log('[Particle] analyser.getByteFrequencyData patched (' + targets.length + ' targets)');
            return true;
        }
        // analyser/beatAnalyser 在 initAudio() 后才存在，轮询 patch
        function ensureAnalyserPatched() {
            if (patchAnalyserGetFreqData()) return;
            setTimeout(ensureAnalyserPatched, 300);
        }
        ensureAnalyserPatched();

        // ★ 粒子模式：patch setParticleLyricsSilently，阻止沉浸模式强制启用 3D 粒子文字
        //   播放控制与歌词.js:2441 进入沉浸模式时会 setParticleLyricsSilently(true) 覆盖 PopWord 设置
        if (typeof window.setParticleLyricsSilently === 'function') {
            window.__origSetParticleLyricsSilently = window.setParticleLyricsSilently;
            window.setParticleLyricsSilently = function(on) {
                // 粒子模式：PopWord DOM 模式下不允许启用 3D 粒子文字
                if (typeof fx !== 'undefined' && fx.lyricStyle === 1) {
                    console.log('[Particle] setParticleLyricsSilently(' + on + ') blocked — PopWord mode');
                    return;
                }
                return window.__origSetParticleLyricsSilently(on);
            };
            console.log('[Particle] setParticleLyricsSilently patched');
        }

        // ★ 粒子模式：强制使用逐字弹出（PopWord DOM，每字随机倾斜，对齐竖屏 UI）
        //   关闭 3D 粒子文字（避免 GPU 耗尽白屏 + 避免两个歌词同时显示）
        function forcePopWordStyle() {
            if (typeof fx === 'undefined') {
                setTimeout(forcePopWordStyle, 300);
                return;
            }
            if (typeof setLyricStyle === 'function') {
                setLyricStyle(1);
                console.log('[Particle] Lyric style forced to 1 (PopWord DOM)');
            } else {
                fx.lyricStyle = 1;
                fx.particleLyrics = false;
                if (typeof lyricsVisible !== 'undefined') lyricsVisible = false;
                _popWordMode = true;
                _lyricFloatMode = false;
                if (typeof clearStageLyrics === 'function') clearStageLyrics();
                if (typeof popWordOpen === 'function') popWordOpen();
                console.log('[Particle] Lyric style set to 1 (PopWord DOM, manual)');
            }
        }
        setTimeout(forcePopWordStyle, 1500);
        setTimeout(forcePopWordStyle, 4000);

        // ★ 粒子模式：WebGL context lost 恢复 + GPU 内存优化（防白屏）
        //   GPU SharedImage 创建失败会导致 WebView 渲染崩溃 → 白屏
        //   监听 webglcontextlost 事件，阻止默认行为并强制恢复
        function setupGpuRecovery() {
            var canvas = document.querySelector('canvas');
            if (!canvas) {
                setTimeout(setupGpuRecovery, 500);
                return;
            }
            // 监听 context lost，阻止默认行为（允许自动恢复）
            canvas.addEventListener('webglcontextlost', function(e) {
                e.preventDefault();
                console.warn('[Particle] WebGL context lost — preventing default, will auto-restore');
            }, false);
            // 监听 context restored，重新初始化
            canvas.addEventListener('webglcontextrestored', function(e) {
                console.log('[Particle] WebGL context restored — reinitializing');
                if (typeof createLyricsParticles === 'function') createLyricsParticles();
            }, false);
            console.log('[Particle] GPU recovery listeners installed on canvas');
        }
        setTimeout(setupGpuRecovery, 1000);
        setTimeout(setupGpuRecovery, 3000); // 确保 canvas 存在后再次安装

        // ★ 粒子模式：定时清理 stageLyrics.outgoing 防止 GPU 内存泄漏
        //   3D 粒子文字不断创建/销毁 mesh，outgoing 数组如果堆积会耗尽 GPU 内存
        setInterval(function() {
            if (typeof stageLyrics !== 'undefined' && stageLyrics.outgoing && stageLyrics.outgoing.length > 8) {
                // 清理过期的 outgoing mesh
                while (stageLyrics.outgoing.length > 4) {
                    var old = stageLyrics.outgoing.shift();
                    try {
                        if (old && old.parent) old.parent.remove(old);
                        if (old && old.geometry) old.geometry.dispose();
                        if (old && old.material) {
                            if (Array.isArray(old.material)) old.material.forEach(function(m){ m.dispose(); });
                            else old.material.dispose();
                        }
                    } catch(e) {}
                }
                console.log('[Particle] Cleaned outgoing lyrics, remaining=' + stageLyrics.outgoing.length);
            }
        }, 5000);

        // ★ 粒子模式：节奏分析正常运行（fetch 本地音频 + OfflineAudioContext.decodeAudioData）
        //   audio.paused 已 patch 为 false，分析流程不会被 early-return 阻止
        //   仅在分析卡住超过 30 秒时自动隐藏"正在分析节奏"角标
        function hideBeatChipIfStale() {
            var chip = document.getElementById('beat-chip');
            var txt = document.getElementById('beat-text');
            if (chip && txt && txt.textContent.indexOf('分析') >= 0) {
                // 仍在分析中，保持显示
            }
        }
        // 不强制隐藏，让 analyzeAudioBeats 自己在完成后 hideBeatChip

        // ★ 粒子模式：横屏独立播放声音（audio 元素真正播放）
        //   - 不再 mute / 不再阻止 audio.play()
        //   - 不再 patch currentTime/paused（让 audio 使用真实播放位置）
        //   - 不启动 MusicPlaybackService（通知栏不显示，避免与竖屏 ExoPlayer 冲突）
        //   - 歌词跟随横屏 audio.currentTime 自然推进

        // ★ 不再 patch audio.currentTime — 让 audio 使用真实播放位置
        window.__particleCurrentTime = 0;
        function patchAudioCurrentTime() {
            // 粒子模式现在让 audio 真正播放，不需要 patch currentTime
            return;
        }
        function installParticleAudioBlocker() {
            // 粒子模式现在让 audio 真正播放，不安装 blocker
            if (typeof audio === 'undefined' || !audio) return;
            // 确保不静音
            try { audio.muted = false; audio.volume = 1; } catch(e) {}
            console.log('[Particle] Audio play enabled (landscape independent playback)');
        }
        installParticleAudioBlocker();

        // ★ 粒子模式：Monkey-patch KeepApp.setMusicPlaying，避免触发竖屏暂停逻辑
        if (typeof KeepApp !== 'undefined' && KeepApp && KeepApp.setMusicPlaying) {
            KeepApp.__origSetMusicPlaying = KeepApp.setMusicPlaying;
            KeepApp.setMusicPlaying = function(playing) {
                console.log('[Particle] KeepApp.setMusicPlaying(' + playing + ') blocked — no notification bar');
                return;
            };
        }
        // ★ 注意：不 patch audio.load()，需要让 audio 正常加载 metadata，
        //   否则 audio.duration 为 NaN，audio.currentTime 设置无效，歌词无法推进

        function autoPlayCurrentSong() {
            var msId = window.__PARTICLE_MEDIA_STORE_ID__ || -1;
            if (msId <= 0) {
                console.log('[Particle] No currentMediaStoreId, skipping');
                return;
            }
            if (typeof KeepApp === 'undefined' || !KeepApp.importRoomSong) {
                setTimeout(autoPlayCurrentSong, 500);
                return;
            }
            try {
                var resultStr = KeepApp.importRoomSong(msId);
                var result = JSON.parse(resultStr);
                if (result.error) {
                    console.warn('[Particle] importRoomSong error:', result.error);
                    return;
                }
                console.log('[Particle] Song imported:', result.cachedFile, result.title);
                var newSong = {
                    name: result.title || result.displayName || '未知歌曲',
                    artist: result.artist || '本地文件',
                    source: 'local',
                    mediaStoreId: msId,
                    localKey: result.cachedFile || '',
                    localUrl: result.cachedFile ? '/local-song/' + result.cachedFile : '',
                    cachedFile: result.cachedFile || '',
                    duration: result.duration || 0,
                    cover: result.cover || '',
                    lyric: result.lyric || ''
                };
                // ★ 存储当前歌曲信息，供节奏分析兜底使用
                window.__PARTICLE_CURRENT_SONG__ = newSong;
                window.__PARTICLE_LOCAL_URL__ = newSong.localUrl || '';
                window.__PARTICLE_BM_KEY__ = newSong.localKey || ('local:' + msId);
                if (typeof window.persistLocalSong === 'function') {
                    window.persistLocalSong(newSong);
                }
                if (typeof window.__syncLocalSongsFromDisk === 'function') window.__syncLocalSongsFromDisk();
                setTimeout(function(){
                    try {
                        var freshSongs = (typeof window.readLocalSongs === 'function') ? window.readLocalSongs() : [];
                        var idx = -1;
                        for (var i = 0; i < freshSongs.length; i++) {
                            if (freshSongs[i].mediaStoreId === msId) { idx = i; break; }
                        }
                        if (idx >= 0 && typeof window.playLocalSavedSong === 'function') {
                            window.playLocalSavedSong(idx);
                            console.log('[Particle] Song loaded at index', idx);
                            // ★ 确保不静音（playLocalSavedSong 可能重建 audio 对象）
                            setTimeout(function() {
                                if (typeof audio !== 'undefined' && audio) {
                                    try { audio.muted = false; audio.volume = 1; } catch(e) {}
                                }
                            }, 100);
                            // ★ 粒子模式兜底：主动触发节奏分析（scheduleBeatAnalysis 可能因 audio 检查失败）
                            //   歌曲是竖屏播放的，粒子模式 audio 未真正播放，scheduleBeatAnalysis 的
                            //   !audio || audio.paused 检查或 token 检查可能阻止分析，这里直接触发
                            setTimeout(function() {
                                try {
                                    var localUrl = window.__PARTICLE_LOCAL_URL__;
                                    var bmKey = window.__PARTICLE_BM_KEY__;
                                    var curSong = window.__PARTICLE_CURRENT_SONG__;
                                    if (!localUrl) { console.warn('[Particle] No localUrl for beat analysis'); return; }
                                    if (window.__PARTICLE_BEAT_DONE__ === msId) {
                                        console.log('[Particle] Beat already analyzed for', msId);
                                        return;
                                    }
                                    window.__PARTICLE_BEAT_DONE__ = msId;
                                    // 取消可能挂起的 scheduleBeatAnalysis
                                    if (typeof cancelBeatAnalysisTimer === 'function') cancelBeatAnalysisTimer();
                                    if (typeof beatMapToken !== 'undefined') beatMapToken++;
                                    var myTok = (typeof beatMapToken !== 'undefined' ? beatMapToken : 1);
                                    if (typeof beatMapCache === 'undefined') window.beatMapCache = {};
                                    if (beatMapCache[bmKey]) {
                                        if (typeof currentBeatMap !== 'undefined') currentBeatMap = beatMapCache[bmKey];
                                        if (typeof applyCinemaProfileFromBeatMap === 'function') applyCinemaProfileFromBeatMap(currentBeatMap);
                                        if (typeof notifyDesktopLyricsBeatMapReady === 'function') notifyDesktopLyricsBeatMapReady();
                                        console.log('[Particle] Beat cache hit:', currentBeatMap.kicks.length, 'kicks');
                                        return;
                                    }
                                    if (typeof showBeatChip === 'function') showBeatChip('正在分析节奏…');
                                    console.log('[Particle] Triggering analyzeAudioBeats:', localUrl);
                                    analyzeAudioBeats(localUrl, null, myTok, {
                                        background: true,
                                        song: curSong || null
                                    }).then(function(map){
                                        if (myTok !== (typeof beatMapToken !== 'undefined' ? beatMapToken : 0)) {
                                            console.log('[Particle] Beat token stale, discard');
                                            return;
                                        }
                                        if (map) {
                                            if (bmKey) beatMapCache[bmKey] = map;
                                            if (typeof currentBeatMap !== 'undefined') currentBeatMap = map;
                                            if (typeof applyCinemaProfileFromBeatMap === 'function') applyCinemaProfileFromBeatMap(map);
                                            if (typeof notifyDesktopLyricsBeatMapReady === 'function') notifyDesktopLyricsBeatMapReady();
                                            console.log('[Particle] Beat map ready:', map.kicks ? map.kicks.length : 0, 'kicks');
                                        } else {
                                            if (typeof hideBeatChip === 'function') hideBeatChip();
                                        }
                                    }).catch(function(e){
                                        console.warn('[Particle] Beat analysis failed:', e);
                                        if (typeof hideBeatChip === 'function') hideBeatChip();
                                    });
                                } catch(err) { console.warn('[Particle] Beat trigger error:', err); }
                            }, 4000);
                        } else {
                            console.warn('[Particle] Song not found, len=', freshSongs.length);
                        }
                    } catch(e) { console.warn('[Particle] Load failed:', e); }
                }, 800);
            } catch(e) {
                console.warn('[Particle] importRoomSong exception:', e);
            }
        }
        setTimeout(autoPlayCurrentSong, 2000);

        // ★ 粒子模式：横屏独立播放，不再同步竖屏位置
        //   仅检测竖屏切歌（用于自动重新加载歌词/封面）
        function syncPortraitPosition() {
            // 确保不静音
            if (typeof audio !== 'undefined' && audio) {
                try { audio.muted = false; if (audio.volume === 0) audio.volume = 1; } catch(e) {}
            }
            if (typeof KeepApp === 'undefined' || !KeepApp.getPortraitPlaybackState) return;
            try {
                var stateStr = KeepApp.getPortraitPlaybackState();
                var state = JSON.parse(stateStr);
                if (state.error) return;

                // ★ 粒子模式：强制 playing = true（让 tickLyricsParticles 更新歌词）
                if (typeof playing !== 'undefined' && !playing) {
                    playing = true;
                    console.log('[Particle] playing forced to true for lyrics');
                }

                // 歌曲切换检测：竖屏切歌时自动重新加载
                if (state.mediaStoreId > 0 && state.mediaStoreId !== window.__PARTICLE_LAST_MEDIA_ID__) {
                    window.__PARTICLE_LAST_MEDIA_ID__ = state.mediaStoreId;
                    if (state.mediaStoreId !== window.__PARTICLE_MEDIA_STORE_ID__) {
                        console.log('[Particle] Portrait song changed to', state.mediaStoreId);
                        window.__PARTICLE_MEDIA_STORE_ID__ = state.mediaStoreId;
                        autoPlayCurrentSong();
                    }
                }
            } catch(e) {
                console.warn('[Particle] syncPortraitPosition error:', e);
            }
        }
        setTimeout(function() {
            setInterval(syncPortraitPosition, 500);
            console.log('[LandscapeWebActivity] Song change detection started (500ms)');
        }, 3000);

        console.log('[LandscapeWebActivity] Particle mode active — landscape independent audio playback');
    }

    console.log('[LandscapeWebActivity] BRIDGE_JS injected');
})();
            """.trimIndent()
    }
}

/**
 * ★ 场景画面拉伸渲染器
 *
 * native 层（libscenejni.so）在 updateScene 中会将 glViewport 设为 resizeScene 的尺寸
 * （renderW x renderH），导致画面只在底部 renderH 高度内显示，上方空白。
 *
 * SceneStretchRenderer 在 updateScene 后：
 * 1. glCopyTexSubImage2D 将 Framebuffer 内容复制到纹理
 * 2. glViewport 设为全屏 (dstW x dstH)
 * 3. 绑定纹理，绘制全屏四边形，将纹理垂直拉伸到屏幕高度
 *
 * 效果：左右保持壁纸原生比例（不变形），上下填满屏幕
 */
private class SceneStretchRenderer {
    private var program = 0
    private var texId = 0
    private var texW = 0
    private var texH = 0
    private var aPosLoc = 0
    private var aTcLoc = 0
    private var uTexLoc = 0

    // 全屏四边形顶点：位置(x,y) + 纹理坐标(u,v)
    // GL 纹理坐标原点在左下角，Framebuffer 像素原点也在左下角
    private val quadVerts =
        floatArrayOf(
            // x      y    u    v
            -1f,
            -1f,
            0f,
            0f, // 左下
            1f,
            -1f,
            1f,
            0f, // 右下
            -1f,
            1f,
            0f,
            1f, // 左上
            1f,
            1f,
            1f,
            1f, // 右上
        )

    private val vbo = IntArray(1)

    init {
        try {
            // 编译着色器
            val vs =
                compileShader(
                    android.opengl.GLES20.GL_VERTEX_SHADER,
                    """
                    attribute vec4 aPosition;
                    attribute vec2 aTexCoord;
                    varying vec2 vTexCoord;
                    void main() {
                        gl_Position = aPosition;
                        vTexCoord = aTexCoord;
                    }
                    """.trimIndent(),
                )
            val fs =
                compileShader(
                    android.opengl.GLES20.GL_FRAGMENT_SHADER,
                    """
                    precision mediump float;
                    varying vec2 vTexCoord;
                    uniform sampler2D uTexture;
                    void main() {
                        gl_FragColor = texture2D(uTexture, vTexCoord);
                    }
                    """.trimIndent(),
                )
            program = android.opengl.GLES20.glCreateProgram()
            android.opengl.GLES20.glAttachShader(program, vs)
            android.opengl.GLES20.glAttachShader(program, fs)
            android.opengl.GLES20.glLinkProgram(program)
            android.opengl.GLES20.glDeleteShader(vs)
            android.opengl.GLES20.glDeleteShader(fs)

            aPosLoc = android.opengl.GLES20.glGetAttribLocation(program, "aPosition")
            aTcLoc = android.opengl.GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexLoc = android.opengl.GLES20.glGetUniformLocation(program, "uTexture")

            // 创建纹理
            val texIds = IntArray(1)
            android.opengl.GLES20.glGenTextures(1, texIds, 0)
            texId = texIds[0]
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, texId)
            android.opengl.GLES20.glTexParameteri(
                android.opengl.GLES20.GL_TEXTURE_2D,
                android.opengl.GLES20.GL_TEXTURE_MIN_FILTER,
                android.opengl.GLES20.GL_LINEAR,
            )
            android.opengl.GLES20.glTexParameteri(
                android.opengl.GLES20.GL_TEXTURE_2D,
                android.opengl.GLES20.GL_TEXTURE_MAG_FILTER,
                android.opengl.GLES20.GL_LINEAR,
            )
            android.opengl.GLES20.glTexParameteri(
                android.opengl.GLES20.GL_TEXTURE_2D,
                android.opengl.GLES20.GL_TEXTURE_WRAP_S,
                android.opengl.GLES20.GL_CLAMP_TO_EDGE,
            )
            android.opengl.GLES20.glTexParameteri(
                android.opengl.GLES20.GL_TEXTURE_2D,
                android.opengl.GLES20.GL_TEXTURE_WRAP_T,
                android.opengl.GLES20.GL_CLAMP_TO_EDGE,
            )

            // 创建 VBO
            android.opengl.GLES20.glGenBuffers(1, vbo, 0)
            val buf =
                java.nio.ByteBuffer
                    .allocateDirect(quadVerts.size * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
            buf.asFloatBuffer().put(quadVerts)
            buf.rewind()
            android.opengl.GLES20.glBindBuffer(android.opengl.GLES20.GL_ARRAY_BUFFER, vbo[0])
            android.opengl.GLES20.glBufferData(
                android.opengl.GLES20.GL_ARRAY_BUFFER,
                quadVerts.size * 4,
                buf,
                android.opengl.GLES20.GL_STATIC_DRAW,
            )

            android.util.Log.d("MpkgBg", "SceneStretchRenderer init ok: program=$program, texId=$texId")
        } catch (e: Throwable) {
            android.util.Log.e("MpkgBg", "SceneStretchRenderer init failed", e)
        }
    }

    private fun compileShader(
        type: Int,
        src: String,
    ): Int {
        val sh = android.opengl.GLES20.glCreateShader(type)
        android.opengl.GLES20.glShaderSource(sh, src)
        android.opengl.GLES20.glCompileShader(sh)
        val status = IntArray(1)
        android.opengl.GLES20.glGetShaderiv(sh, android.opengl.GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            android.util.Log.e("MpkgBg", "Shader compile failed: ${android.opengl.GLES20.glGetShaderInfoLog(sh)}")
            android.opengl.GLES20.glDeleteShader(sh)
            return 0
        }
        return sh
    }

    /**
     * 将 Framebuffer 内容复制到纹理，然后以全屏 viewport 绘制全屏四边形。
     *
     * @param srcW 源宽度（renderW）
     * @param srcH 源高度（renderH，native 层 viewport 的高度）
     * @param dstW 目标宽度（screenW）
     * @param dstH 目标高度（screenH）
     */
    fun blitStretch(
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
    ) {
        if (program == 0 || texId == 0) return
        try {
            // 1. 如果纹理尺寸不够，重新分配
            if (texW < srcW || texH < srcH) {
                texW = srcW
                texH = srcH
                android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, texId)
                android.opengl.GLES20.glTexImage2D(
                    android.opengl.GLES20.GL_TEXTURE_2D,
                    0,
                    android.opengl.GLES20.GL_RGBA,
                    srcW,
                    srcH,
                    0,
                    android.opengl.GLES20.GL_RGBA,
                    android.opengl.GLES20.GL_UNSIGNED_BYTE,
                    null,
                )
            }

            // 2. 将 Framebuffer 的 (0,0,srcW,srcH) 区域复制到纹理
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, texId)
            android.opengl.GLES20.glCopyTexSubImage2D(
                android.opengl.GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                0,
                0,
                srcW,
                srcH,
            )

            // 3. 设置 viewport 为全屏
            android.opengl.GLES20.glViewport(0, 0, dstW, dstH)

            // 4. 清除 Framebuffer
            android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f)
            android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)

            // 5. 绑定着色器程序
            android.opengl.GLES20.glUseProgram(program)

            // 6. 绑定纹理
            android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0)
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, texId)
            android.opengl.GLES20.glUniform1i(uTexLoc, 0)

            // 7. 绘制全屏四边形
            android.opengl.GLES20.glBindBuffer(android.opengl.GLES20.GL_ARRAY_BUFFER, vbo[0])
            android.opengl.GLES20.glEnableVertexAttribArray(aPosLoc)
            android.opengl.GLES20.glVertexAttribPointer(aPosLoc, 2, android.opengl.GLES20.GL_FLOAT, false, 16, 0)
            android.opengl.GLES20.glEnableVertexAttribArray(aTcLoc)
            android.opengl.GLES20.glVertexAttribPointer(aTcLoc, 2, android.opengl.GLES20.GL_FLOAT, false, 16, 8)

            android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4)

            android.opengl.GLES20.glDisableVertexAttribArray(aPosLoc)
            android.opengl.GLES20.glDisableVertexAttribArray(aTcLoc)
            android.opengl.GLES20.glBindBuffer(android.opengl.GLES20.GL_ARRAY_BUFFER, 0)
        } catch (e: Throwable) {
            android.util.Log.e("MpkgBg", "SceneStretchRenderer.blitStretch failed", e)
        }
    }
}
