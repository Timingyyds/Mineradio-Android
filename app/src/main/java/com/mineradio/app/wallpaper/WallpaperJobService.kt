package com.mineradio.app.wallpaper

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log

/**
 * JobScheduler 兜底服务（★★★ 第 7 层防护）
 *
 * ★ 核心作用：
 *   JobScheduler 是 Android 提供的持久化任务调度器，即使应用被杀、设备重启，
 *   已调度的 Job 仍会被系统保留并在合适时机重新执行。
 *
 * ★★★ 这是解决"服务被杀后无法重启"问题的关键组件：
 *   - AlarmManager 在应用被"强制停止"后会失效（Android 3.1+ 机制）
 *   - JobScheduler 在应用被"强制停止"后也会失效，但在普通清理后台后仍能工作
 *   - 与 AlarmManager 形成双重兜底，提高保活成功率
 *
 * ★ 防护层级：第 7 层（持久化兜底，AlarmManager 失效后的最后一道防线）
 *
 * ★ 工作流程：
 *   1. KeepAlive 服务启动时调度 Job（周期 15 分钟，JobScheduler 最小周期）
 *   2. Job 触发时检查壁纸状态，如果被切换则尝试恢复
 *   3. 同时启动 KeepAlive 服务，让保活机制继续运行
 *   4. Job 完成后重新调度下一个 Job（持久化循环）
 */
class WallpaperJobService : JobService() {
    companion object {
        private const val TAG = "WallpaperJobService"
        private const val JOB_ID = 0x77A2
        private const val KEY_TRIGGER_REASON = "trigger_reason"

        /** ★ Job 调度周期（15 分钟，JobScheduler 最小周期限制）
         *   虽然 15 分钟较长，但作为兜底机制已经足够
         *   主力检测仍是 KeepAlive 服务的 2 秒 Handler 轮询 */
        private const val JOB_PERIOD_MS = 15 * 60 * 1000L

        /**
         * 调度壁纸保活 Job
         * @param context 上下文
         * @param reason 触发原因（用于日志）
         */
        fun scheduleJob(
            context: Context,
            reason: String,
        ) {
            try {
                val jobScheduler =
                    context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                        ?: return

                val componentName = ComponentName(context, WallpaperJobService::class.java)
                val extras = PersistableBundle().apply { putString(KEY_TRIGGER_REASON, reason) }

                val jobInfo =
                    JobInfo
                        .Builder(JOB_ID, componentName)
                        .setPeriodic(JOB_PERIOD_MS)
                        .setPersisted(true) // ★ 设备重启后仍保留 Job
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                        .setRequiresCharging(false)
                        .setRequiresDeviceIdle(false)
                        .setExtras(extras)
                        .build()

                val result = jobScheduler.schedule(jobInfo)
                if (result == JobScheduler.RESULT_SUCCESS) {
                    Log.d(TAG, "scheduleJob: succeeded (reason=$reason, period=${JOB_PERIOD_MS / 1000}s)")
                } else {
                    Log.w(TAG, "scheduleJob: failed (result=$result, reason=$reason)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "scheduleJob failed", e)
            }
        }

        /**
         * 取消已调度的 Job
         */
        fun cancelJob(context: Context) {
            try {
                val jobScheduler =
                    context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                        ?: return
                jobScheduler.cancel(JOB_ID)
                Log.d(TAG, "cancelJob: cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "cancelJob failed", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val reason = params.extras.getString(KEY_TRIGGER_REASON, "unknown")
        Log.d(TAG, "onStartJob: reason=$reason, process=${getProcessName()}")

        // ★ 在后台线程执行，避免阻塞主线程
        Thread {
            try {
                // 1. 检查 forceSystemWallpaper 开关
                val forceSystemWallpaper =
                    try {
                        WallpaperManager.get(this).getForceSystemWallpaper()
                    } catch (e: Exception) {
                        getSharedPreferences("mineradio_wallpaper_prefs", MODE_PRIVATE)
                            .getBoolean("force_system_wallpaper", false)
                    }

                if (!forceSystemWallpaper) {
                    Log.d(TAG, "onStartJob: forceSystemWallpaper=off, skip")
                    jobFinished(params, false)
                    return@Thread
                }

                // 2. 检查当前壁纸是否是 LWService
                val sysWm = android.app.WallpaperManager.getInstance(this)
                val wallpaperInfo = sysWm.wallpaperInfo
                val isLWServiceActive =
                    wallpaperInfo?.packageName == packageName &&
                        wallpaperInfo?.serviceName == "com.mineradio.app.wallpaper.LWService"

                Log.d(
                    TAG,
                    "onStartJob: isLWServiceActive=$isLWServiceActive, LWService.isRunning=${LWService.isRunning}",
                )

                // 3. 如果壁纸被切换走，尝试恢复
                if (!isLWServiceActive) {
                    Log.d(TAG, "onStartJob: ★ wallpaper was switched away, trying to recover...")

                    // 尝试反射激活
                    val reflectOk = tryReflectSetWallpaperComponent()
                    if (reflectOk) {
                        Log.d(TAG, "onStartJob: ★ recovery succeeded via reflection")
                        // 等待 2 秒让系统绑定
                        Thread.sleep(2000)
                    } else {
                        Log.w(TAG, "onStartJob: recovery failed via reflection")
                    }
                }

                // 4. 启动 KeepAlive 服务，让保活机制继续运行
                try {
                    val serviceIntent = Intent(this, WallpaperKeepAliveService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    Log.d(TAG, "onStartJob: KeepAlive service started")
                } catch (e: Exception) {
                    Log.e(TAG, "onStartJob: start KeepAlive service failed: ${e.javaClass.simpleName}: ${e.message}")
                }

                // 5. 发送刷新广播
                val refreshIntent = Intent(WallpaperReceiver.ACTION_REFRESH)
                refreshIntent.setPackage(packageName)
                sendBroadcast(refreshIntent)
            } catch (e: Exception) {
                Log.e(TAG, "onStartJob: failed", e)
            } finally {
                // ★★★ 不在这里重新调度 Job，setPeriodic 会自动周期性执行
                //   之前在这里调用 scheduleJob 导致无限循环
                jobFinished(params, false)
            }
        }.start()

        return true // 表示在后台线程处理
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Log.d(TAG, "onStopJob")
        // ★ 返回 false，不重新调度（setPeriodic 会自动周期性执行）
        return false
    }

    /**
     * 反射调用 setWallpaperComponent 绑定 LWService
     */
    private fun tryReflectSetWallpaperComponent(): Boolean =
        try {
            val sysWm = android.app.WallpaperManager.getInstance(this)
            val component = ComponentName(this, LWService::class.java)
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

    private fun getProcessName(): String =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.app.Application.getProcessName()
            } else {
                val pid = android.os.Process.myPid()
                val am = getSystemService(android.app.ActivityManager::class.java)
                am?.runningAppProcesses?.find { it.pid == pid }?.processName ?: "unknown"
            }
        } catch (_: Exception) {
            "unknown"
        }
}
