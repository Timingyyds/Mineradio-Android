package com.mineradio.app

import android.app.Application
import android.os.Build
import android.webkit.WebView
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.util.UnstableApi
import com.mineradio.app.crash.CrashHandler
import com.mineradio.app.di.AppModule
import com.mineradio.app.feature.library.domain.MusicScanUseCases
import com.mineradio.app.feature.library.domain.libraryDomainModule
import com.mineradio.app.feature.lyrics.domain.lyricsDomainModule
import com.mineradio.app.feature.player.domain.playerDomainModule
import com.mineradio.app.feature.settings.domain.settingsDomainModule
import com.mineradio.app.lyric_core.di.extraInfoModule
import com.mineradio.app.player.impl.SpicaPlayer
import com.mineradio.app.service.PlaybackService
import com.mineradio.app.storage.impl.di.storageModule
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext.startKoin
import timber.log.Timber

/**
 * 应用程序类
 * 负责初始化 Koin 依赖注入、ImageLoader 和其他全局配置
 *
 * ★ 多进程支持：
 *   - 主进程（com.mineradio.app）：完整初始化 Koin、MediaStore 监听、CrashHandler 等
 *   - 壁纸进程（com.mineradio.app:wallpaper）：仅做最小化初始化，避免不必要的资源占用
 */
class App : Application() {
    private val musicScanService: MusicScanUseCases by inject()

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this

        // ★ 性能优化：在第一时间初始化性能监控（不影响启动流程）
        try {
            com.mineradio.app.utils.PerformanceMonitor
                .init(this)
        } catch (_: Exception) {
        }

        val processName = getCurrentProcessName()
        val isWallpaperProcess = processName.endsWith(":wallpaper")

        if (isWallpaperProcess) {
            // ★ 壁纸进程：最小化初始化
            // 1. WebView 数据目录后缀（多进程 WebView 必需，否则崩溃）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    WebView.setDataDirectorySuffix("wallpaper")
                } catch (e: Exception) {
                    // 可能已经被调用过
                    android.util.Log.w("App", "setDataDirectorySuffix failed: ${e.message}")
                }
            }
            // 2. 仅初始化 CrashHandler（便于排查崩溃）
            try {
                CrashHandler.init(this)
            } catch (e: Exception) {
                android.util.Log.w("App", "CrashHandler init failed in :wallpaper process: ${e.message}")
            }
            // ★★★ 3. 在 :wallpaper 进程中启动 KeepAlive 服务（同进程启动，不会被 BAL 拒绝）
            //   :wallpaper 进程启动的场景：系统绑定 LWService / BootReceiver 拉起 / AlarmManager 唤醒
            //   在这些场景下，:wallpaper 进程启动时主动拉起 KeepAlive 服务，确保保活机制运行
            try {
                com.mineradio.app.wallpaper.WallpaperKeepAliveService
                    .start(this)
                android.util.Log.i("App", "WallpaperKeepAliveService started from :wallpaper process")
            } catch (e: Exception) {
                android.util.Log.w("App", "Failed to start WallpaperKeepAliveService in :wallpaper process: ${e.message}")
            }
            android.util.Log.i("App", "App.onCreate in :wallpaper process (minimal init)")
            return
        }

        // ★ 主进程：完整初始化

        // 初始化日志
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 初始化 Koin 依赖注入
        startKoin {
            androidLogger()
            androidContext(this@App)
            modules(
                storageModule, // 数据模块 (feature-library-data)
                SpicaPlayer.createModule(PlaybackService::class.java), // 数据模块 (feature-player-data)
                libraryDomainModule,
                playerDomainModule,
                settingsDomainModule,
                lyricsDomainModule,
                AppModule.appModule, // 应用模块
                extraInfoModule,
            )
        }

        // 启动 MediaStore 变更监听
        setupMediaStoreObserver()
        CrashHandler.init(this)

        // ★★★ 主进程启动时优先并行预加载所有壁纸功能（与启动动画并行进行）
        //   WallpaperPreloader 会在后台 IO 线程中并行执行 8 项预加载：
        //     1. 预读取 WallpaperManager 配置
        //     2. 预启动 WallpaperKeepAliveService
        //     3. 预调度 WorkManager 周期任务
        //     4. 预调度 JobScheduler 持久任务
        //     5. 预调度 AlarmManager 唛醒
        //     6. 预发送 ACTION_REFRESH 广播
        //     7. 预反射 setWallpaperComponent 激活 LWService
        //     8. 预加载壁纸文件元数据
        //   ★ 多次调用安全：MainActivity/LandscapeWebActivity 中再次调用会自动跳过
        com.mineradio.app.wallpaper.WallpaperPreloader
            .preloadDuringSplash(this, "App")

        // ★★★ 用户使用量统计上报（匿名，不阻塞启动）
        //   上报设备 ID（Android ID 脱敏哈希）、APP 版本、系统信息
        //   管理员可在管理面板"统计"标签查看
        // ★ 性能优化：延迟 5 秒后再上报，避免与启动 I/O 竞争网络资源
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            reportUsageStats()
        }, 5000)
    }

    /**
     * 用户使用量统计上报
     * - 使用 Android ID 的 SHA-256 哈希作为设备唯一标识（脱敏）
     * - 在 IO 线程异步上报，失败不影响启动
     * - ★ 每次启动都上报（后端会自动累加 launch_count）
     */
    private fun reportUsageStats() {
        try {
            // 获取设备唯一标识（Android ID 的 SHA-256 哈希，脱敏）
            val androidId =
                try {
                    val aid =
                        android.provider.Settings.Secure.getString(
                            contentResolver,
                            android.provider.Settings.Secure.ANDROID_ID,
                        )
                    if (aid.isNullOrEmpty()) "unknown_device" else aid
                } catch (_: Exception) {
                    "unknown_device"
                }
            // SHA-256 哈希（脱敏）
            val deviceId =
                try {
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    md.update(androidId.toByteArray())
                    val hex = md.digest().joinToString("") { "%02x".format(it) }
                    hex.substring(0, 32) // 前 32 位足够唯一
                } catch (_: Exception) {
                    androidId
                }

            val appVersion =
                try {
                    com.mineradio.app.BuildConfig.VERSION_NAME
                } catch (_: Exception) {
                    ""
                }
            val osInfo = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

            // 异步上报
            Thread {
                try {
                    val url = java.net.URL("https://plugin-market-d2gpn5vfb44d821b8.service.tcloudbase.com/plugin-api/api/usage/report")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.doOutput = true
                    val payload =
                        org.json
                            .JSONObject()
                            .put("device_id", deviceId)
                            .put("app_version", appVersion)
                            .put("os_info", osInfo)
                            .toString()
                    conn.outputStream.use { it.write(payload.toByteArray()) }
                    val code = conn.responseCode
                    android.util.Log.i("App", "usage report: HTTP $code")
                    conn.disconnect()
                } catch (e: Exception) {
                    android.util.Log.w("App", "usage report failed: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            android.util.Log.w("App", "reportUsageStats error: ${e.message}")
        }
    }

    /**
     * 获取当前进程名
     */
    private fun getCurrentProcessName(): String =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                val pid = android.os.Process.myPid()
                val am = getSystemService(android.app.ActivityManager::class.java)
                am?.runningAppProcesses?.find { it.pid == pid }?.processName ?: "unknown"
            }
        } catch (_: Exception) {
            "unknown"
        }

    /**
     * 设置 MediaStore 变更监听
     * 绑定到应用生命周期，前台时监听，后台时停止（节省资源）
     */
    private fun setupMediaStoreObserver() {
        // 立即启动监听器
        musicScanService.startMediaStoreObserver()
        Timber.i("MediaStore 监听器已启动")

        // 监听应用前后台切换，优化资源使用
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // 应用进入前台，启动监听
                    musicScanService.startMediaStoreObserver()
                    Timber.d("应用进入前台，MediaStore 监听器已启动")
                }

                override fun onStop(owner: LifecycleOwner) {
                    // 应用进入后台，停止监听
                    musicScanService.stopMediaStoreObserver()
                    Timber.d("应用进入后台，MediaStore 监听器已停止")
                }
            },
        )
    }

    companion object {
        private lateinit var instance: App

        fun getInstance(): App = instance
    }
}
