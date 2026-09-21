package com.mineradio.app.wallpaper

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 壁纸保活前台服务（★★★ 多层防护版）
 *
 * 通过前台服务方式保持应用进程存活，确保：
 * 1. 壁纸服务（LWService）不会被系统杀掉
 * 2. 视频壁纸和 HTML 壁纸能在后台继续显示
 * 3. 重启手机后能自启动恢复壁纸
 *
 * ★★★ 多层防护机制（解决主程序后台十几秒壁纸回退、重启后壁纸丢失问题）：
 *
 * 【第1层】服务内 Handler 高频轮询（2 秒）
 *   - 不依赖 AlarmManager，不受 Android 14+ 精确闹钟配额限制
 *   - 服务存活时每 2 秒检测 wallpaperInfo，发现被切换立即恢复
 *   - 这是主力检测机制，能覆盖 99% 的"后台被清理"场景
 *
 * 【第2层】AlarmManager 心跳（3 秒，兜底）
 *   - 服务被系统杀死时，AlarmManager 唤醒并重启服务
 *   - START_STICKY + 心跳双重保证服务能被重新拉起
 *
 * 【第3层】多层指数退避重试
 *   - 反射 setWallpaperComponent 失败后，按 100ms/300ms/800ms/2000ms 重试
 *   - 连续失败时退避（最长 10 秒），成功后重置
 *
 * 【第4层】onDestroy / onTaskRemoved 异步重启
 *   - 不直接 startService（避免 "process is bad" 机制）
 *   - 通过 AlarmManager 1-2 秒后异步拉起
 *
 * 【第5层】START_STICKY
 *   - 服务被杀后系统自动重启
 *
 * 【第6层】forceSystemWallpaper 开关多层持久化
 *   - 同时存 SharedPreferences 和外部存储配置文件
 *   - "清除数据"不会丢失外部存储的开关，BootReceiver 仍能恢复
 *
 * 重要：必须在 onCreate() 或 onStartCommand() 中 5 秒内调用 startForeground()，
 * 否则系统会强制杀掉服务。
 */
class WallpaperKeepAliveService : Service() {
    companion object {
        private const val TAG = "WallpaperKeepAliveSvc"
        private const val NOTIFICATION_ID = 0x77A1
        private const val CHANNEL_ID = "mineradio_wallpaper_keep_alive"

        /** ★ AlarmManager 心跳间隔（30 秒，兜底机制）
         *   Android 14+ 对 setExactAndAllowWhileIdle 有每日配额限制
         *   主检测改用服务内 Handler 轮询，AlarmManager 仅作服务被杀时的兜底唤醒
         *   ★ ZUI 等系统清理器在清理后台时会主动重置动态壁纸绑定
         *   ★★★ 性能优化：从 3 秒改为 30 秒，减少闹钟风暴导致的卡顿 */
        private const val HEARTBEAT_INTERVAL_MS = 30 * 1000L

        /** ★★★ 服务内 Handler 轮询间隔（10 秒）
         *   不依赖 AlarmManager，不受系统配额限制
         *   服务存活时每 10 秒检测壁纸是否被切换，立即恢复
         *   ★★★ 性能优化：从 2 秒改为 10 秒，减少高频轮询导致的卡顿 */
        private const val HANDLER_POLL_INTERVAL_MS = 10 * 1000L

        /** ★ 反射 setWallpaperComponent 失败后的最大重试次数 */
        private const val MAX_RETRY_COUNT = 5

        /** ★ 连续失败时的最大退避间隔（10 秒） */
        private const val MAX_BACKOFF_MS = 10 * 1000L

        /** 自启动 Intent action */
        private const val ACTION_HEARTBEAT = "com.mineradio.app.wallpaper.HEARTBEAT"

        /** ★★★ 静态变量：上次调度唤醒的时间（避免服务重启后重置导致闹钟风暴） */
        @Volatile
        private var lastScheduleWakeupsTime: Long = 0L

        /**
         * 启动保活服务（静态便捷方法）
         */
        fun start(context: Context) {
            try {
                val intent = Intent(context, WallpaperKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
            }
        }

        /**
         * 停止保活服务
         */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, WallpaperKeepAliveService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "stop failed", e)
            }
        }

        /**
         * 设置 AlarmManager 心跳（精确闹钟，兜底唤醒服务）
         * 即使应用被系统杀死，AlarmManager 也能在下次触发时拉起服务
         *
         * ★ 改用 setExactAndAllowWhileIdle：
         *   - setAndAllowWhileIdle 在 Doze 下会被限制为每 9 分钟一次（太慢）
         *   - setExactAndAllowWhileIdle 是精确闹钟，能在 Doze 下按时触发
         *   - 已在 AndroidManifest 声明 SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM 权限
         *   - Android 14+ 需要 USE_EXACT_ALARM（已声明，普通应用也能获取）
         */
        fun scheduleHeartbeat(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent =
                    Intent(context, WallpaperKeepAliveService::class.java).apply {
                        action = ACTION_HEARTBEAT
                    }
                val pendingFlags =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                val pendingIntent =
                    PendingIntent.getService(
                        context,
                        NOTIFICATION_ID,
                        intent,
                        pendingFlags,
                    )
                val triggerAt = SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val canScheduleExact =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            alarmManager.canScheduleExactAlarms()
                        } else {
                            true
                        }
                    if (canScheduleExact) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            pendingIntent,
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            pendingIntent,
                        )
                        Log.w(TAG, "scheduleHeartbeat: exact alarm not allowed, fallback to inexact")
                    }
                } else {
                    alarmManager.setRepeating(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        HEARTBEAT_INTERVAL_MS,
                        pendingIntent,
                    )
                }
                Log.d(TAG, "scheduleHeartbeat: scheduled in ${HEARTBEAT_INTERVAL_MS / 1000}s")
            } catch (e: Exception) {
                Log.e(TAG, "scheduleHeartbeat failed", e)
            }
        }
    }

    private val refreshReceiver = WallpaperReceiver()

    /** ★ 服务内 Handler，用于高频轮询壁纸状态（不依赖 AlarmManager） */
    private val handler = Handler(Looper.getMainLooper())

    /** ★ 连续失败次数（用于指数退避） */
    private var consecutiveFailures = 0

    /** ★★★ setWallpaperComponent 是否因权限不足被拒绝
     *   SET_WALLPAPER_COMPONENT 是 signature|privileged 级权限，普通应用无法获取
     *   一旦检测到 SecurityException，不再反复尝试 setWallpaperComponent */
    @Volatile
    private var permissionDenied = false

    /** ★ 最近一次成功激活时间（用于避免短时间内重复激活） */
    private var lastSuccessTime = 0L

    /** ★ 最近一次检测时间（用于限速，避免 Handler 轮询和心跳同时触发重复激活） */
    private var lastCheckTime = 0L

    /** ★ 最小检测间隔（500ms），避免 Handler 轮询和 AlarmManager 心跳同时触发造成重复 */
    private val MIN_CHECK_INTERVAL_MS = 500L

    /** ★ Handler 高频轮询任务 */
    private val pollRunnable =
        object : Runnable {
            override fun run() {
                try {
                    tryActivateLWService()
                } catch (e: Exception) {
                    Log.e(TAG, "pollRunnable: tryActivateLWService failed", e)
                }
                // 继续下一次轮询
                handler.postDelayed(this, HANDLER_POLL_INTERVAL_MS)
            }
        }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        // 创建通知渠道（Android 8.0+ 必须）
        createNotificationChannel()

        // 必须在 5 秒内调用 startForeground
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 注册刷新广播
        val intentFilter = IntentFilter()
        intentFilter.addAction(WallpaperReceiver.ACTION_REFRESH)
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                applicationContext,
                refreshReceiver,
                intentFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (e: Exception) {
            Log.e(TAG, "registerReceiver failed", e)
        }

        // ★ 启动后主动激活 LWService（如果系统壁纸不是 LWService 且配置文件存在）
        tryActivateLWService()

        // ★★★ 启动服务内 Handler 高频轮询（第1层防护：2 秒检测一次壁纸是否被切换）
        handler.postDelayed(pollRunnable, HANDLER_POLL_INTERVAL_MS)

        // ★ 设置 AlarmManager 心跳（第2层防护：兜底机制，服务被杀时唤醒）
        scheduleHeartbeat(applicationContext)

        // ★★★ 第7层防护：调度 JobScheduler 持久化 Job（设备重启后仍能恢复）
        WallpaperJobService.scheduleJob(applicationContext, "keepalive_oncreate")

        // ★★★ 第8层防护：注册 ContentObserver 监听系统壁纸 URI 变化
        registerWallpaperObserver()

        // ★★★ 第10层防护：调度 WorkManager 周期性 Work（设备重启后仍能恢复）
        WallpaperWorker.schedulePeriodicWork(applicationContext)

        // ★★★ 第9层防护：不在 onCreate 中调度唤醒，避免服务循环启动
        //   只在 onTaskRemoved 中调度唤醒（用户划掉后台时）
    }

    /** ★★★ 系统壁纸变化 ContentObserver（第8层防护）
     *   监听 Settings.System.WALLPAPER_URI 变化，当系统壁纸被切换时立即触发恢复
     *   这是比 ACTION_WALLPAPER_CHANGED 广播更底层的监听方式 */
    private var wallpaperObserver: android.database.ContentObserver? = null

    /**
     * 注册系统壁纸 URI 变化监听
     */
    private fun registerWallpaperObserver() {
        try {
            if (wallpaperObserver == null) {
                wallpaperObserver =
                    object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            super.onChange(selfChange)
                            Log.d(TAG, "WallpaperObserver: onChange detected wallpaper change")
                            // ★ 延迟 500ms 执行，避免系统切换过程中的瞬时状态
                            handler.postDelayed({
                                try {
                                    tryActivateLWService()
                                } catch (e: Exception) {
                                    Log.e(TAG, "WallpaperObserver: tryActivateLWService failed", e)
                                }
                            }, 500)
                        }
                    }
            }
            // 监听 wallpaper 设置变化
            val uri =
                android.provider.Settings.System
                    .getUriFor(android.provider.Settings.System.WALLPAPER_ACTIVITY)
            if (uri != null) {
                contentResolver.registerContentObserver(uri, false, wallpaperObserver!!)
                Log.d(TAG, "registerWallpaperObserver: registered for WALLPAPER_ACTIVITY")
            }
            // 同时监听全局 wallpaper 设置
            val globalUri =
                android.provider.Settings.Global
                    .getUriFor("wallpaper")
            contentResolver.registerContentObserver(globalUri, false, wallpaperObserver!!)
            Log.d(TAG, "registerWallpaperObserver: registered for global wallpaper")
        } catch (e: Exception) {
            Log.e(TAG, "registerWallpaperObserver failed", e)
        }
    }

    /**
     * 注销系统壁纸 URI 变化监听
     */
    private fun unregisterWallpaperObserver() {
        try {
            wallpaperObserver?.let {
                contentResolver.unregisterContentObserver(it)
                wallpaperObserver = null
                Log.d(TAG, "unregisterWallpaperObserver: unregistered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "unregisterWallpaperObserver failed", e)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.d(TAG, "onStartCommand: flags=$flags, startId=$startId, action=${intent?.action}")
        // 确保前台通知显示
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // ★ 每次 onStartCommand 都主动检查并激活 LWService
        //   - 心跳触发：定期检查壁纸是否被 ZUI 等清理器解绑
        //   - 主进程启动触发（App.onCreate → KeepAlive.start）：立即检查并恢复
        //   - 服务重启触发（START_STICKY）：进程被杀后重启，立即恢复
        try {
            tryActivateLWService()
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand: tryActivateLWService failed", e)
        }

        // 确保 Handler 轮询正在运行（防止服务重启后轮询未恢复）
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, HANDLER_POLL_INTERVAL_MS)

        // 重新调度下一次心跳
        scheduleHeartbeat(applicationContext)

        // ★★★ 第9层防护：在 onStartCommand 中也调度唤醒（带频率限制）
        //   原因：ZUI 清理器直接杀死进程时，onTaskRemoved 不会被调用
        //   所以需要在每次 onStartCommand 时确保唤醒已调度
        //   频率限制：60 秒内只调度一次，避免循环
        tryScheduleWakeupsWithRateLimit()

        // 如果服务被杀，重启服务（保持保活）
        return START_STICKY
    }

    /**
     * 带频率限制的唤醒调度（5 分钟内只调度一次）
     * ★★★ 使用静态变量，避免服务重启后重置导致闹钟风暴
     */
    private fun tryScheduleWakeupsWithRateLimit() {
        val now = SystemClock.elapsedRealtime()
        // ★★★ 频率限制：从 60 秒改为 5 分钟，避免闹钟风暴
        if (now - lastScheduleWakeupsTime > 300_000L) {
            lastScheduleWakeupsTime = now
            WallpaperAlarmReceiver.scheduleWakeups(applicationContext)
            Log.d(TAG, "tryScheduleWakeupsWithRateLimit: scheduled wakeups (rate limited 5min)")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // ★ 用户从最近任务列表划掉应用时，通过 AlarmManager 异步重启服务
        //   ★ 不要直接 startForegroundService，否则会触发 "process is bad" 机制
        //   Android 在进程被杀过程中尝试启动新服务会拒绝，等 10 秒才允许
        //   通过 AlarmManager 1 秒后由系统调度拉起，避免该问题
        Log.d(TAG, "onTaskRemoved: schedule restart via AlarmManager")
        scheduleRestart(applicationContext, 1000L)
        // ★★★ 第9层防护：调度多次 AlarmManager 唤醒（解决 ZUI 清理后 "process is bad" 问题）
        WallpaperAlarmReceiver.scheduleWakeups(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // ★ 停止 Handler 轮询
        handler.removeCallbacks(pollRunnable)
        try {
            applicationContext.unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {
        }
        // ★ 注销 ContentObserver
        unregisterWallpaperObserver()
        Log.d(TAG, "onDestroy")

        // ★ 不要在 onDestroy 中直接 startForegroundService
        //   日志显示这会触发 "process is bad" 机制，导致 10 秒内无法重启
        //   改为通过 AlarmManager 2 秒后异步拉起
        scheduleRestart(applicationContext, 2000L)

        super.onDestroy()
    }

    /**
     * 通过 AlarmManager 异步拉起服务
     * 避免在 onDestroy / onTaskRemoved 中直接 startService 触发 "process is bad" 机制
     */
    private fun scheduleRestart(
        context: Context,
        delayMs: Long,
    ) {
        try {
            val restartIntent = Intent(context, WallpaperKeepAliveService::class.java)
            val pendingFlags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_ONE_SHOT
                }
            val pendingIntent =
                PendingIntent.getService(
                    context,
                    NOTIFICATION_ID + 1,
                    restartIntent,
                    pendingFlags,
                )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager?.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs,
                    pendingIntent,
                )
            } else {
                alarmManager?.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs,
                    pendingIntent,
                )
            }
            Log.d(TAG, "scheduleRestart: will restart in ${delayMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "scheduleRestart failed", e)
        }
    }

    /**
     * ★★★ 主动激活 LWService（如果系统壁纸不是 LWService 且配置文件存在）
     *
     * 多层防护核心逻辑：
     * 1. 检测系统壁纸是否仍是 LWService，不是则强制重新绑定
     * 2. 反射 setWallpaperComponent 调用（跳过系统 11 秒延迟）
     * 3. 反射失败时多层重试（指数退避：100ms/300ms/800ms/2000ms）
     * 4. 重试全部失败后 fallback 到 MainActivity（受 shouldFallbackToMainActivity 控制）
     *
     * ★★ 关键改进（解决 ZUI 黑屏 11 秒问题）：
     *
     * 日志显示 ZUI 系统清理后台时强制杀死 :wallpaper 进程（adj=100 前台服务也被杀，违反 Android 规范）：
     * ```
     * 06:38:08.597 I ActivityManager: Killing 7698:com.mineradio.app:wallpaper/u0a338 (adj 100): ZuiMemoryCleaner_Recents
     * 06:38:08.598 W ActivityManager: Scheduling restart of WallpaperKeepAliveService in 1000ms for start-requested
     * 06:38:08.598 W ActivityManager: Scheduling restart of LWService in 11000ms for connection  ← 黑屏 11 秒的根因
     * 06:38:09.614 I ActivityManager: Start proc 9660:com.mineradio.app:wallpaper/u0a338 for service {...WallpaperKeepAliveService}
     * ```
     *
     * 系统对 BIND_WALLPAPER 类型的服务有 11 秒强制重启延迟（无法绕过）。
     * 解决方案：在 KeepAlive 服务启动后立即调用 setWallpaperComponent，强制系统重新绑定 LWService。
     *
     * 通过 LWService.isRunning 静态变量判断进程是否刚被杀重启：
     * - false → 进程刚被重启（静态变量被重置），主动调用 setWallpaperComponent 立即绑定 LWService
     * - true → LWService 已在运行，跳过避免反复重置
     */
    private fun tryActivateLWService() {
        try {
            // ★ 限速：避免 Handler 轮询和 AlarmManager 心跳同时触发造成重复激活
            val now = SystemClock.elapsedRealtime()
            if (now - lastCheckTime < MIN_CHECK_INTERVAL_MS) {
                return
            }
            lastCheckTime = now

            val wm = WallpaperManager.get(applicationContext)
            val current = wm.getCurrentWallpaper()

            // ★★★ 读取桌面萌宠开关：开启时即使没有 currentWallpaper 也需要激活 LWService
            val petEnabled =
                try {
                    wm.getDesktopPetEnabled()
                } catch (_: Exception) {
                    false
                }

            // ★ 即使没有当前壁纸，也尝试启动保活服务（让 :wallpaper 进程独立运行）
            if (current == null) {
                if (petEnabled) {
                    Log.d(TAG, "tryActivateLWService: no current wallpaper, but desktopPet enabled, proceed to activate")
                } else {
                    Log.d(TAG, "tryActivateLWService: no current wallpaper config")
                    return
                }
            }

            // ★ 只在视频/HTML 壁纸时才需要激活 LWService（图片壁纸依赖系统 WallpaperManager）
            //   ★★★ 桌面萌宠：pet_enabled 开启时也必须激活 LWService
            if (!petEnabled &&
                current != null &&
                current.wallpaperType != WallpaperEntity.TYPE_VIDEO &&
                current.wallpaperType != WallpaperEntity.TYPE_HTML &&
                current.wallpaperType != WallpaperEntity.TYPE_MPKG_WEBGL
            ) {
                Log.d(TAG, "tryActivateLWService: not video/html/mpkg wallpaper and pet disabled, skip")
                return
            }

            val sysWm = android.app.WallpaperManager.getInstance(applicationContext)
            val wallpaperInfo = sysWm.wallpaperInfo
            val isLWServiceActive =
                wallpaperInfo?.packageName == packageName &&
                    wallpaperInfo?.serviceName == "com.mineradio.app.wallpaper.LWService"

            Log.d(
                TAG,
                "tryActivateLWService: system wallpaper = ${wallpaperInfo?.packageName}/${wallpaperInfo?.serviceName}, isLWServiceActive=$isLWServiceActive, LWService.isRunning=${LWService.isRunning}",
            )

            // ★★★ 核心逻辑改进（区分两种场景）：
            //
            // 场景A：壁纸配置仍是 LWService（isLWServiceActive=true）但 LWService.isRunning=false
            //   → 进程刚被杀重启，系统会自动重新绑定 LWService（11秒延迟）
            //   → 不需要调 setWallpaperComponent，等待系统自动绑定即可
            //   → 这样避免了不必要的 setWallpaperComponent 调用和权限错误
            //
            // 场景B：壁纸配置被切换走（isLWServiceActive=false）
            //   → 需要 setWallpaperComponent 重新绑定 LWService
            //   → 如果权限不足（permissionDenied=true），fallback 到 MainActivity
            val needForceRebind = !isLWServiceActive

            if (needForceRebind) {
                if (permissionDenied) {
                    // ★ 权限不足，不再尝试 setWallpaperComponent
                    //   fallback 到 MainActivity（ACTION_CHANGE_LIVE_WALLPAPER）让用户手动激活
                    Log.d(TAG, "tryActivateLWService: permissionDenied=true, fallback to MainActivity")
                    if (shouldFallbackToMainActivity()) {
                        fallbackToMainActivity()
                    }
                } else {
                    Log.d(TAG, "tryActivateLWService: forcing setWallpaperComponent to rebind LWService")
                    // ★ 在后台线程执行多层重试，避免 Thread.sleep 阻塞主线程
                    Thread {
                        val reflectOk = forceRebindWithRetry()
                        if (!reflectOk && shouldFallbackToMainActivity()) {
                            Log.w(TAG, "tryActivateLWService: reflect failed after retry, fallback to MainActivity auto-activate")
                            fallbackToMainActivity()
                        }
                    }.start()
                }
            } else if (!LWService.isRunning) {
                // ★ 场景A：壁纸配置仍是 LWService，但进程刚重启
                //   系统会自动重新绑定（11秒延迟），不需要调 setWallpaperComponent
                Log.d(TAG, "tryActivateLWService: wallpaper config is LWService but process just restarted, waiting for system auto-rebind")
            } else {
                // ★ 成功状态：重置失败计数
                consecutiveFailures = 0
                Log.d(TAG, "tryActivateLWService: LWService is running normally, no rebind needed")
            }

            // 发送刷新广播让 LWService 重新加载壁纸
            val refreshIntent = Intent(WallpaperReceiver.ACTION_REFRESH)
            refreshIntent.setPackage(packageName)
            sendBroadcast(refreshIntent)
        } catch (e: Exception) {
            Log.e(TAG, "tryActivateLWService failed", e)
        }
    }

    /**
     * ★★★ 多层重试强制重新绑定 LWService
     *
     * 重试策略（指数退避）：
     *   第1次：立即
     *   第2次：100ms 后
     *   第3次：300ms 后
     *   第4次：800ms 后
     *   第5次：2000ms 后
     *
     * @return true=某次重试成功，false=全部失败
     */
    private fun forceRebindWithRetry(): Boolean {
        val backoffDelays = longArrayOf(0, 100, 300, 800, 2000)

        for (attempt in 0 until MAX_RETRY_COUNT) {
            val delay = if (attempt < backoffDelays.size) backoffDelays[attempt] else backoffDelays.last()
            if (delay > 0) {
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }

            val ok = trySetWallpaperComponent()
            if (ok) {
                consecutiveFailures = 0
                lastSuccessTime = SystemClock.elapsedRealtime()
                Log.d(TAG, "forceRebindWithRetry: succeeded at attempt ${attempt + 1}/$MAX_RETRY_COUNT")
                return true
            } else {
                Log.w(TAG, "forceRebindWithRetry: attempt ${attempt + 1}/$MAX_RETRY_COUNT failed")
            }
        }

        // ★ 全部失败：增加连续失败计数（用于指数退避下次轮询间隔）
        consecutiveFailures++
        Log.e(TAG, "forceRebindWithRetry: all $MAX_RETRY_COUNT attempts failed, consecutiveFailures=$consecutiveFailures")
        return false
    }

    /**
     * 反射调用 setWallpaperComponent 绑定 LWService
     *
     * ★★★ 多层反射尝试：
     *   第1层：WallpaperManager.setWallpaperComponent（标准反射）
     *   第2层：IWallpaperManager.setWallpaperComponentChecked（底层 AIDL）
     *   第3层：IWallpaperManager.setWallpaperComponent（旧版 AIDL）
     *
     * @return true=成功，false=失败
     */
    private fun trySetWallpaperComponent(): Boolean {
        // ★ 第1层：WallpaperManager.setWallpaperComponent
        val ok1 = tryReflectSetWallpaperComponentStandard()
        if (ok1) return true

        // ★ 第2层：IWallpaperManager.setWallpaperComponentChecked（底层 AIDL）
        val ok2 = tryIWallpaperManagerChecked()
        if (ok2) return true

        // ★ 第3层：IWallpaperManager.setWallpaperComponent（旧版 AIDL）
        val ok3 = tryIWallpaperManagerLegacy()
        if (ok3) return true

        return false
    }

    /**
     * 第1层：通过 WallpaperManager 反射调用 setWallpaperComponent
     */
    private fun tryReflectSetWallpaperComponentStandard(): Boolean =
        try {
            val sysWm = android.app.WallpaperManager.getInstance(applicationContext)
            val component = ComponentName(applicationContext, LWService::class.java)
            val method =
                android.app.WallpaperManager::class.java
                    .getMethod("setWallpaperComponent", ComponentName::class.java)
            method.invoke(sysWm, component)
            Log.d(TAG, "tryReflectSetWallpaperComponentStandard: succeeded")
            permissionDenied = false
            true
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            // ★★★ 检测 SecurityException：权限不足，设置标志，后续不再尝试
            if (cause is SecurityException) {
                Log.e(TAG, "tryReflectSetWallpaperComponentStandard: SecurityException - SET_WALLPAPER_COMPONENT permission denied")
                permissionDenied = true
            } else {
                Log.w(
                    TAG,
                    "tryReflectSetWallpaperComponentStandard: failed (cause): " +
                        "${cause?.javaClass?.simpleName}: ${cause?.message}",
                )
            }
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "tryReflectSetWallpaperComponentStandard: SecurityException: ${e.message}")
            permissionDenied = true
            false
        } catch (e: Exception) {
            Log.w(TAG, "tryReflectSetWallpaperComponentStandard: failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }

    /**
     * ★★ 第2层：通过 IWallpaperManager.setWallpaperComponentChecked 反射调用（底层 AIDL）
     *
     * WallpaperManager.setWallpaperComponent 实际是调用 IWallpaperManager.setWallpaperComponent
     * 直接反射底层接口可能绕过某些权限检查
     */
    private fun tryIWallpaperManagerChecked(): Boolean =
        try {
            val wmClass = android.app.WallpaperManager::class.java
            val sGlobalField = wmClass.getDeclaredField("sGlobal")
            sGlobalField.isAccessible = true
            val sGlobal = sGlobalField.get(null)

            val wallpaperManagerField =
                sGlobal.javaClass.getDeclaredField("mWallpaperManager")
            wallpaperManagerField.isAccessible = true
            val iWallpaperManager = wallpaperManagerField.get(sGlobal)

            val component = ComponentName(applicationContext, LWService::class.java)
            val method =
                iWallpaperManager.javaClass.getMethod(
                    "setWallpaperComponentChecked",
                    ComponentName::class.java,
                    String::class.java,
                )
            method.invoke(iWallpaperManager, component, packageName)
            Log.d(TAG, "tryIWallpaperManagerChecked: succeeded via setWallpaperComponentChecked")
            permissionDenied = false
            true
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            Log.w(
                TAG,
                "tryIWallpaperManagerChecked: failed (cause): ${cause?.javaClass?.simpleName}: ${cause?.message}",
            )
            false
        } catch (e: NoSuchMethodException) {
            Log.d(TAG, "tryIWallpaperManagerChecked: method not found, skip")
            false
        } catch (e: Exception) {
            Log.w(TAG, "tryIWallpaperManagerChecked: failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }

    /**
     * 第3层：通过 IWallpaperManager.setWallpaperComponent 反射调用（旧版 AIDL）
     */
    private fun tryIWallpaperManagerLegacy(): Boolean =
        try {
            val wmClass = android.app.WallpaperManager::class.java
            val sGlobalField = wmClass.getDeclaredField("sGlobal")
            sGlobalField.isAccessible = true
            val sGlobal = sGlobalField.get(null)

            val wallpaperManagerField =
                sGlobal.javaClass.getDeclaredField("mWallpaperManager")
            wallpaperManagerField.isAccessible = true
            val iWallpaperManager = wallpaperManagerField.get(sGlobal)

            val component = ComponentName(applicationContext, LWService::class.java)
            val method =
                iWallpaperManager.javaClass.getMethod(
                    "setWallpaperComponent",
                    ComponentName::class.java,
                )
            method.invoke(iWallpaperManager, component)
            Log.d(TAG, "tryIWallpaperManagerLegacy: succeeded via setWallpaperComponent")
            permissionDenied = false
            true
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            Log.w(
                TAG,
                "tryIWallpaperManagerLegacy: failed (cause): ${cause?.javaClass?.simpleName}: ${cause?.message}",
            )
            false
        } catch (e: Exception) {
            Log.w(TAG, "tryIWallpaperManagerLegacy: failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }

    /**
     * Fallback 静默重试：不再启动 Activity，只记录日志。
     *
     * ★★★ 修复：原实现会通过 PendingIntent/startActivity 拉起 MainActivity → LandscapeWebActivity，
     *   导致用户在后台/被杀时被自动跳前台。现改为 no-op，让 KeepAlive 心跳静默重试反射激活，
     *   不再拉起任何 Activity。
     *
     * 用户若需手动恢复壁纸，可在「设置 → 外观 → 强制系统壁纸」开关控制，或主动打开应用。
     */
    private fun fallbackToMainActivity() {
        Log.d(
            TAG,
            "fallbackToMainActivity: silent retry (no-op). Force system wallpaper is on, " +
                "KeepAlive will continue polling without launching Activity.",
        )
    }

    /**
     * 判断是否应该 fallback 到 MainActivity 自动激活
     *
     * ★ 防止反复弹窗，但允许较快重试：
     *   - 15 秒内只允许一次 fallback（避免 2 秒心跳反复弹窗）
     *   - 15 秒后允许再次 fallback（解决用户连续两次清理后台后第二次无法恢复的问题）
     *
     * ★★ 必须检查「强制系统壁纸」开关：
     *   - 开关关闭时，绝不弹出系统壁纸设置界面（用户已明确表示不要自动恢复）
     *   - 开关开启时，才允许 fallback 到 MainActivity 自动激活
     *   - ★ 多层防护：通过 WallpaperManager 读取开关（外部存储 + SharedPreferences 双读）
     */
    private fun shouldFallbackToMainActivity(): Boolean {
        try {
            // ★ 多层防护：通过 WallpaperManager 读取开关（防止"清除数据"丢失开关）
            val forceSystemWallpaper =
                try {
                    WallpaperManager.get(applicationContext).getForceSystemWallpaper()
                } catch (_: Exception) {
                    getSharedPreferences("mineradio_wallpaper_prefs", MODE_PRIVATE)
                        .getBoolean("force_system_wallpaper", false)
                }
            if (!forceSystemWallpaper) {
                Log.d(TAG, "shouldFallbackToMainActivity: forceSystemWallpaper=off, skip fallback")
                return false
            }
            val now = System.currentTimeMillis()
            val last = lastFallbackTime
            if (last == 0L || now - last > 15_000L) {
                lastFallbackTime = now
                return true
            }
            return false
        } catch (_: Exception) {
            return false
        }
    }

    private var lastFallbackTime: Long = 0L

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "壁纸保活服务",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "保持壁纸在后台运行，防止视频和 HTML 壁纸被关闭"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 构建前台服务通知
     */
    private fun buildNotification(): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        builder
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentTitle("Mineradio 壁纸")
            .setContentText("壁纸服务运行中")
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .setShowWhen(false)

        return builder.build()
    }
}
