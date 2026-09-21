package com.mineradio.app.wallpaper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * AlarmManager 唤醒接收器（★★★ 解决 "process is bad" 问题）
 *
 * ★ 核心问题：
 *   ZUI 清理器杀死 :wallpaper 进程后，Android 将进程标记为 "bad"，
 *   10+ 秒内无法启动任何服务。系统尝试绑定 LWService 失败后放弃，
 *   设置壁纸为 null（回退到系统壁纸）。
 *
 * ★★★ 解决方案：
 *   使用 AlarmManager + 广播接收器，在 "process is bad" 状态结束后
 *   通过多次重试启动 :wallpaper 进程，恢复壁纸。
 *
 *   1. KeepAlive 服务启动时调度多个 AlarmManager 唤醒（3/6/9/12/15秒）
 *   2. 每次唤醒都尝试启动 KeepAlive 服务
 *   3. "process is bad" 期间启动会失败，但 AlarmManager 会继续触发
 *   4. "process is bad" 结束后（约 10-12 秒），某个唤醒会成功启动服务
 *   5. 服务启动后检测壁纸被切换并恢复
 *
 * ★ 防护层级：第 9 层（解决 "process is bad" 问题）
 */
class WallpaperAlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "WallpaperAlarmRecv"
        const val ACTION_WAKEUP = "com.mineradio.wallpaper.ACTION_WAKEUP"

        /** ★ 唤醒间隔（5 秒）— ★★★ 性能优化：从 1.5/3 秒改为 5 秒，减少闹钟数量 */
        private const val WAKEUP_INTERVAL_MS = 5 * 1000L

        /** ★★★ 关键：覆盖 "process is bad" 期结束后的唤醒（20 秒后）
         *   ZUI 清理后进程被标记为 bad，11 秒后系统设置壁纸为 null
         *   但 "process is bad" 期可能持续更久（15-20 秒）
         *   一次长延迟唤醒确保在 "process is bad" 期结束后能立即恢复壁纸 */
        private val WAKEUP_LONG_DELAYS = longArrayOf(20_000)

        /** ★ 最大重试次数（3 次）— ★★★ 性能优化：从 20 次减少到 3 次 */
        private const val MAX_RETRY = 3

        /**
         * 调度多次 AlarmManager 唤醒
         * 在 "process is bad" 状态结束后能成功启动服务
         *
         * ★★★ 性能优化：大幅减少闹钟数量
         *   - 3 次用 5 秒间隔（覆盖 "process is bad" 期内，虽然会失败但占位）
         *   - 1 次长延迟唤醒（20 秒后），确保在 "process is bad" 期结束后能恢复
         *   总共 4 次唤醒，之前是 25 次，减少 84%
         */
        fun scheduleWakeups(context: Context) {
            try {
                val alarmManager =
                    context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                        ?: return

                val pendingFlags =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }

                // ★ 调度 3 次唤醒，用 5 秒间隔
                var elapsed = 0L
                for (i in 0 until MAX_RETRY) {
                    elapsed += WAKEUP_INTERVAL_MS

                    val intent =
                        Intent(context, WallpaperAlarmReceiver::class.java).apply {
                            action = ACTION_WAKEUP
                            putExtra("attempt", i)
                        }
                    val pendingIntent =
                        PendingIntent.getBroadcast(
                            context,
                            0x77A3 + i, // ★ 每次使用不同的 requestCode，避免覆盖
                            intent,
                            pendingFlags,
                        )
                    val triggerAt = SystemClock.elapsedRealtime() + elapsed
                    scheduleAlarm(alarmManager, triggerAt, pendingIntent)
                }

                // ★★★ 额外调度 1 次长延迟唤醒（20 秒后）
                //   确保在 "process is bad" 期结束后（通常 15-20 秒）能立即恢复壁纸
                for ((index, delay) in WAKEUP_LONG_DELAYS.withIndex()) {
                    val intent =
                        Intent(context, WallpaperAlarmReceiver::class.java).apply {
                            action = ACTION_WAKEUP
                            putExtra("attempt", 100 + index) // 使用 100+ 标记长延迟唤醒
                        }
                    val pendingIntent =
                        PendingIntent.getBroadcast(
                            context,
                            0x77B0 + index, // ★ 使用不同的 requestCode
                            intent,
                            pendingFlags,
                        )
                    val triggerAt = SystemClock.elapsedRealtime() + delay
                    scheduleAlarm(alarmManager, triggerAt, pendingIntent)
                }

                Log.d(
                    TAG,
                    "scheduleWakeups: scheduled $MAX_RETRY wakeups + ${WAKEUP_LONG_DELAYS.size} long-delay wakeups",
                )
            } catch (e: Exception) {
                Log.e(TAG, "scheduleWakeups failed", e)
            }
        }

        /**
         * 调度单个 AlarmManager 唤醒
         */
        private fun scheduleAlarm(
            alarmManager: AlarmManager,
            triggerAt: Long,
            pendingIntent: PendingIntent,
        ) {
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
                }
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            }
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val attempt = intent.getIntExtra("attempt", -1)
        Log.d(TAG, "onReceive: action=${intent.action}, attempt=$attempt, process=${getProcessName(context)}")

        if (intent.action != ACTION_WAKEUP) {
            return
        }

        // ★ 使用 goAsync() 在后台线程执行
        val pendingResult = goAsync()
        Thread {
            try {
                // 1. 检查 forceSystemWallpaper 开关
                val forceSystemWallpaper =
                    try {
                        WallpaperManager.get(context).getForceSystemWallpaper()
                    } catch (e: Exception) {
                        context
                            .getSharedPreferences("mineradio_wallpaper_prefs", Context.MODE_PRIVATE)
                            .getBoolean("force_system_wallpaper", false)
                    }

                if (!forceSystemWallpaper) {
                    Log.d(TAG, "forceSystemWallpaper=off, skip")
                    return@Thread
                }

                // 2. 检查当前壁纸是否是 LWService
                val sysWm = android.app.WallpaperManager.getInstance(context)
                val wallpaperInfo = sysWm.wallpaperInfo
                val isLWServiceActive =
                    wallpaperInfo?.packageName == context.packageName &&
                        wallpaperInfo?.serviceName == "com.mineradio.app.wallpaper.LWService"

                Log.d(TAG, "isLWServiceActive=$isLWServiceActive, LWService.isRunning=${LWService.isRunning}")

                // 3. 如果壁纸被切换走，尝试恢复
                if (!isLWServiceActive) {
                    Log.d(TAG, "★ wallpaper was switched away, trying to recover...")

                    // ★ 尝试反射激活
                    val reflectOk = tryReflectSetWallpaperComponent(context)
                    if (reflectOk) {
                        Log.d(TAG, "★ recovery succeeded via reflection")
                        Thread.sleep(1000) // 等待系统绑定
                        // 验证是否成功
                        val wallpaperInfo2 =
                            android.app.WallpaperManager
                                .getInstance(context)
                                .wallpaperInfo
                        val isActive2 =
                            wallpaperInfo2?.packageName == context.packageName &&
                                wallpaperInfo2?.serviceName == "com.mineradio.app.wallpaper.LWService"
                        if (isActive2) {
                            Log.d(TAG, "★ LWService confirmed active after recovery")
                            // 发送刷新广播
                            val refreshIntent = Intent(WallpaperReceiver.ACTION_REFRESH)
                            refreshIntent.setPackage(context.packageName)
                            context.sendBroadcast(refreshIntent)
                            return@Thread
                        }
                    } else {
                        Log.w(TAG, "recovery failed via reflection")
                    }
                }

                // 4. 启动 KeepAlive 服务
                try {
                    val serviceIntent = Intent(context, WallpaperKeepAliveService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d(TAG, "KeepAlive service started")
                } catch (e: Exception) {
                    Log.w(TAG, "start KeepAlive service failed: ${e.javaClass.simpleName}: ${e.message}")
                    // ★ "process is bad" 期间会失败，后续的 AlarmManager 唤醒会重试
                }
            } catch (e: Exception) {
                Log.e(TAG, "WallpaperAlarmReceiver thread failed", e)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    /**
     * 反射调用 setWallpaperComponent 绑定 LWService
     */
    private fun tryReflectSetWallpaperComponent(context: Context): Boolean =
        try {
            val sysWm = android.app.WallpaperManager.getInstance(context)
            val component = ComponentName(context, LWService::class.java)
            val method =
                android.app.WallpaperManager::class.java
                    .getMethod("setWallpaperComponent", ComponentName::class.java)
            method.invoke(sysWm, component)
            Log.d(TAG, "tryReflectSetWallpaperComponent: succeeded")
            true
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            Log.w(
                TAG,
                "tryReflectSetWallpaperComponent: failed (cause): ${cause?.javaClass?.simpleName}: ${cause?.message}",
            )
            false
        } catch (e: Exception) {
            Log.w(TAG, "tryReflectSetWallpaperComponent: failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }

    private fun getProcessName(context: Context): String =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.app.Application.getProcessName()
            } else {
                val pid = android.os.Process.myPid()
                val am = context.getSystemService(android.app.ActivityManager::class.java)
                am?.runningAppProcesses?.find { it.pid == pid }?.processName ?: "unknown"
            }
        } catch (_: Exception) {
            "unknown"
        }
}
