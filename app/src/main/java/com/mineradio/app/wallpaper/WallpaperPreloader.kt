package com.mineradio.app.wallpaper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 壁纸功能预加载器（★★★ 启动动画期间并行加载所有壁纸功能）
 *
 * ★ 核心作用：
 *   在程序启动动画显示期间，并行加载所有壁纸功能相关的内容，
 *   让壁纸功能优先于其他业务（WebView、JS、媒体播放等）就绪。
 *
 * ★ 设计目标：
 *   1. 启动动画显示的同时，所有壁纸功能初始化也并行进行
 *   2. 壁纸功能的初始化不阻塞 UI 线程（启动动画流畅度不受影响）
 *   3. 多次调用安全（idempotent），App/MainActivity/LandscapeWebActivity 都可调用
 *
 * ★★★ 并行加载项（启动动画期间同时进行）：
 *
 *   【第1项】预读取 WallpaperManager 配置
 *     - 触发外部存储 JSON 文件读取和内存缓存
 *     - 后续 LWService/KeepAlive 启动时可直接使用缓存配置
 *
 *   【第2项】预启动 WallpaperKeepAliveService（前台保活服务）
 *     - 让 :wallpaper 进程独立运行
 *     - 即使主程序被清理，壁纸进程仍存活
 *
 *   【第3项】预调度 WorkManager 周期任务
 *     - 设备重启后仍能恢复壁纸
 *     - 不依赖 KeepAlive 服务启动成功
 *
 *   【第4项】预调度 JobScheduler 持久任务
 *     - 持久化 Job，设备重启后自动恢复
 *     - 不依赖 KeepAlive 服务启动成功
 *
 *   【第5项】预调度 AlarmManager 唤醒
 *     - 兜底机制，覆盖 "process is bad" 场景
 *     - 不依赖 KeepAlive 服务启动成功
 *
 *   【第6项】预发送 ACTION_REFRESH 广播
 *     - 让已运行的 LWService 立即重载壁纸
 *     - 应对壁纸文件更新场景
 *
 *   【第7项】预反射 setWallpaperComponent 激活 LWService
 *     - 如果系统壁纸不是 LWService，立即尝试激活
 *     - 启动动画期间就开始激活，不等 WebView 加载完成
 *
 *   【第8项】预加载壁纸文件元数据
 *     - 图片壁纸：预读取文件尺寸
 *     - 视频壁纸：预检查文件存在性
 *     - HTML 壁纸：预检查文件存在性
 */
object WallpaperPreloader {
    private const val TAG = "WallpaperPreloader"

    /** ★ 后台 IO 协程作用域，避免阻塞 UI 线程 */
    private val scope = CoroutineScope(Dispatchers.IO)

    /** ★ 防止重复预加载（一次启动周期内只完整执行一次） */
    @Volatile
    private var preloadStarted = false

    /** ★ 当前预加载任务（用于在重复调用时跳过） */
    @Volatile
    private var preloadJob: Job? = null

    /**
     * ★★★ 启动动画期间并行预加载所有壁纸功能
     *
     * 调用时机：
     *   - App.onCreate（主进程启动时）
     *   - MainActivity.onCreate（启动动画显示时）
     *   - LandscapeWebActivity.onCreate（横屏 Activity 创建时）
     *
     * ★ 多次调用安全：
     *   - 首次调用启动完整的并行预加载
     *   - 后续调用直接返回，避免重复加载
     *   - 启动动画期间所有预加载都在后台 IO 线程进行
     *
     * @param context 任意 Context（内部会转为 applicationContext）
     * @param tag 调用来源标记（用于日志追踪）
     */
    fun preloadDuringSplash(
        context: Context,
        tag: String = "unknown",
    ) {
        // ★ 防止重复预加载
        if (preloadStarted) {
            Log.d(TAG, "preloadDuringSplash: already started, skip (caller=$tag)")
            return
        }
        preloadStarted = true

        Log.i(TAG, "preloadDuringSplash: ★ start parallel wallpaper preloading (caller=$tag)")

        val appContext = context.applicationContext
        preloadJob =
            scope.launch {
                val t0 = SystemClock.elapsedRealtime()
                try {
                    // ════════════════════════════════════════════════
                    // ★ 性能优化：将 8 项预加载任务并行化
                    //   原实现：8 项任务在单个协程中顺序执行，总耗时 = Σ(每项耗时)
                    //   新实现：独立任务用 async 并行，总耗时 ≈ max(每项耗时)
                    //   依赖关系：第7项依赖第1项结果，第8项依赖第1项结果，
                    //            其余任务互相独立
                    // ════════════════════════════════════════════════

                    // ── 第一阶段：并行执行所有独立任务 ──

                    // 第1项：预读取 WallpaperManager 配置（后续第7/8项依赖它）
                    val configDeferred =
                        async(Dispatchers.IO) {
                            val forceSystemWallpaper =
                                try {
                                    WallpaperManager.get(appContext).getForceSystemWallpaper()
                                } catch (e: Exception) {
                                    Log.w(TAG, "preload[$tag]: getForceSystemWallpaper failed: ${e.message}")
                                    try {
                                        appContext
                                            .getSharedPreferences("mineradio_wallpaper_prefs", Context.MODE_PRIVATE)
                                            .getBoolean("force_system_wallpaper", false)
                                    } catch (_: Exception) {
                                        false
                                    }
                                }
                            val currentWallpaper =
                                try {
                                    WallpaperManager.get(appContext).getCurrentWallpaper()
                                } catch (e: Exception) {
                                    Log.w(TAG, "preload[$tag]: getCurrentWallpaper failed: ${e.message}")
                                    null
                                }
                            Log.i(
                                TAG,
                                "preload[$tag]: config ready, forceSystemWallpaper=$forceSystemWallpaper, " +
                                    "currentWallpaper=${currentWallpaper?.wallpaperType}, name=${currentWallpaper?.name}",
                            )
                            Pair(forceSystemWallpaper, currentWallpaper)
                        }

                    // 第2项：预启动 WallpaperKeepAliveService（独立）
                    val keepAliveDeferred =
                        async(Dispatchers.IO) {
                            try {
                                WallpaperKeepAliveService.start(appContext)
                                Log.i(TAG, "preload[$tag]: WallpaperKeepAliveService started")
                            } catch (e: Exception) {
                                Log.w(TAG, "preload[$tag]: start KeepAlive failed: ${e.message}")
                            }
                        }

                    // 第3项：预调度 WorkManager（独立，持久化任务）
                    val workDeferred =
                        async(Dispatchers.IO) {
                            try {
                                WallpaperWorker.schedulePeriodicWork(appContext)
                                Log.i(TAG, "preload[$tag]: WallpaperWorker scheduled")
                            } catch (e: Exception) {
                                Log.w(TAG, "preload[$tag]: schedulePeriodicWork failed: ${e.message}")
                            }
                        }

                    // 第4项：预调度 JobScheduler（独立，持久化任务）
                    val jobDeferred =
                        async(Dispatchers.IO) {
                            try {
                                WallpaperJobService.scheduleJob(appContext, "preloader_splash_$tag")
                                Log.i(TAG, "preload[$tag]: WallpaperJobService scheduled")
                            } catch (e: Exception) {
                                Log.w(TAG, "preload[$tag]: scheduleJob failed: ${e.message}")
                            }
                        }

                    // 第5项：预调度 AlarmManager 唤醒（独立，兜底机制）
                    val alarmDeferred =
                        async(Dispatchers.IO) {
                            try {
                                WallpaperAlarmReceiver.scheduleWakeups(appContext)
                                Log.i(TAG, "preload[$tag]: AlarmManager wakeups scheduled")
                            } catch (e: Exception) {
                                Log.w(TAG, "preload[$tag]: scheduleWakeups failed: ${e.message}")
                            }
                        }

                    // 第6项：预发送 ACTION_REFRESH 广播（独立）
                    val refreshDeferred =
                        async(Dispatchers.IO) {
                            try {
                                val refreshIntent = Intent(WallpaperReceiver.ACTION_REFRESH)
                                refreshIntent.setPackage(appContext.packageName)
                                appContext.sendBroadcast(refreshIntent)
                                Log.i(TAG, "preload[$tag]: ACTION_REFRESH broadcast sent")
                            } catch (e: Exception) {
                                Log.w(TAG, "preload[$tag]: sendBroadcast failed: ${e.message}")
                            }
                        }

                    // 等待所有独立任务 + 配置读取完成
                    val (forceSystemWallpaper, currentWallpaper) = configDeferred.await()
                    keepAliveDeferred.await()
                    workDeferred.await()
                    jobDeferred.await()
                    alarmDeferred.await()
                    refreshDeferred.await()

                    // ── 第二阶段：依赖配置结果的任务（并行） ──

                    // 第7项：预反射 setWallpaperComponent 激活 LWService（依赖第1项）
                    // 第8项：预加载壁纸文件元数据（依赖第1项）
                    val activateDeferred =
                        async(Dispatchers.IO) {
                            if (forceSystemWallpaper && currentWallpaper != null) {
                                val type = currentWallpaper.wallpaperType
                                if (type == WallpaperEntity.TYPE_VIDEO || type == WallpaperEntity.TYPE_HTML) {
                                    try {
                                        activateLWServiceIfNeed(appContext, tag)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "preload[$tag]: activateLWService failed: ${e.message}")
                                    }
                                } else if (type == WallpaperEntity.TYPE_IMAGE) {
                                    try {
                                        val sysWm = android.app.WallpaperManager.getInstance(appContext)
                                        val wallpaperInfo = sysWm.wallpaperInfo
                                        val isStaticWallpaperSet = wallpaperInfo == null
                                        if (!isStaticWallpaperSet) {
                                            Log.i(TAG, "preload[$tag]: image wallpaper, applying via WallpaperHelper")
                                            WallpaperHelper.applyAsSystemWallpaper(appContext, currentWallpaper)
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "preload[$tag]: applyAsSystemWallpaper failed: ${e.message}")
                                    }
                                }
                            } else {
                                Log.d(TAG, "preload[$tag]: forceSystemWallpaper=off or no current wallpaper, skip activate")
                            }
                        }

                    val metadataDeferred =
                        async(Dispatchers.IO) {
                            if (currentWallpaper != null) {
                                try {
                                    preloadWallpaperFileMetadata(appContext, currentWallpaper, tag)
                                } catch (e: Exception) {
                                    Log.w(TAG, "preload[$tag]: preloadFileMetadata failed: ${e.message}")
                                }
                            }
                        }

                    activateDeferred.await()
                    metadataDeferred.await()

                    val cost = SystemClock.elapsedRealtime() - t0
                    Log.i(TAG, "preloadDuringSplash: ★ complete (caller=$tag, cost=${cost}ms)")
                } catch (e: Exception) {
                    Log.e(TAG, "preloadDuringSplash failed (caller=$tag)", e)
                }
            }
    }

    /**
     * ★ 反射激活 LWService（如果系统壁纸不是 LWService）
     */
    private fun activateLWServiceIfNeed(
        appContext: Context,
        tag: String,
    ) {
        try {
            val sysWm = android.app.WallpaperManager.getInstance(appContext)
            val wallpaperInfo = sysWm.wallpaperInfo
            val isLWServiceActive =
                wallpaperInfo?.packageName == appContext.packageName &&
                    wallpaperInfo?.serviceName == "com.mineradio.app.wallpaper.LWService"

            if (!isLWServiceActive) {
                Log.i(TAG, "preload[$tag]: LWService not active, trying setWallpaperComponent via reflection...")
                val component = ComponentName(appContext, LWService::class.java)
                val method =
                    android.app.WallpaperManager::class.java
                        .getMethod("setWallpaperComponent", ComponentName::class.java)
                method.invoke(sysWm, component)
                Log.i(TAG, "preload[$tag]: setWallpaperComponent succeeded during splash")
            } else {
                Log.i(TAG, "preload[$tag]: LWService already active, no need to activate")
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            Log.w(
                TAG,
                "preload[$tag]: setWallpaperComponent failed (cause): ${cause?.javaClass?.simpleName}: ${cause?.message}",
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "preload[$tag]: setWallpaperComponent SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "preload[$tag]: setWallpaperComponent failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * ★ 预加载壁纸文件元数据
     *
     * - 图片壁纸：预读取文件尺寸（让后续 applyAsSystemWallpaper 更快）
     * - 视频壁纸：预检查文件存在性
     * - HTML 壁纸：预检查文件存在性
     */
    private fun preloadWallpaperFileMetadata(
        appContext: Context,
        wallpaper: WallpaperEntity,
        tag: String,
    ) {
        try {
            val realPath = wallpaper.getRealPath(appContext)
            if (realPath.isEmpty()) {
                Log.d(TAG, "preload[$tag]: wallpaper realPath is empty, skip file preload")
                return
            }
            val file = java.io.File(realPath)
            val exists = file.exists()
            val length = if (exists) file.length() else 0L
            Log.i(
                TAG,
                "preload[$tag]: file metadata ready, type=${wallpaper.wallpaperType}, " +
                    "exists=$exists, size=${length / 1024}KB, path=$realPath",
            )
        } catch (e: Exception) {
            Log.w(TAG, "preload[$tag]: preloadFileMetadata failed: ${e.message}")
        }
    }

    /**
     * ★ 重置预加载状态（用于测试或应用重启场景）
     */
    fun reset() {
        preloadStarted = false
        preloadJob?.cancel()
        preloadJob = null
    }
}
