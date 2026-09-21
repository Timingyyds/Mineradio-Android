package com.mineradio.app.wallpaper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * WorkManager 壁纸保活 Worker（★★★ 第 10 层防护）
 *
 * ★ 核心作用：
 *   WorkManager 是 Android Jetpack 提供的持久化任务调度库，基于 JobScheduler 实现。
 *   即使应用被杀、设备重启，已调度的 Work 仍会被系统保留并在合适时机重新执行。
 *
 * ★★★ 相比 JobScheduler 的优势：
 *   - WorkManager 会自动选择最佳的调度方式（JobScheduler / AlarmManager / BroadcastReceiver）
 *   - 支持约束条件（网络、充电、空闲等）
 *   - 支持重试和退避策略
 *   - 支持链式任务
 *   - 设备重启后自动恢复
 *
 * ★ 防护层级：第 10 层（WorkManager 持久化兜底）
 *
 * ★ 工作流程：
 *   1. KeepAlive 服务启动时调度周期性 Work（15 分钟，WorkManager 最小周期）
 *   2. Work 触发时检查壁纸状态，如果被切换则尝试恢复
 *   3. 同时启动 KeepAlive 服务，让保活机制继续运行
 *   4. 同时调度一次性 Work（1 秒后执行），实现快速重试
 */
class WallpaperWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        private const val TAG = "WallpaperWorker"
        private const val PERIODIC_WORK_NAME = "wallpaper_periodic_work"
        private const val ONE_TIME_WORK_NAME = "wallpaper_one_time_work"
        private const val KEY_REASON = "reason"

        /**
         * 调度周期性 Work（15 分钟，WorkManager 最小周期限制）
         * 设备重启后仍能自动恢复
         */
        fun schedulePeriodicWork(context: Context) {
            try {
                val constraints =
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.NOT_REQUIRED)
                        .setRequiresCharging(false)
                        .setRequiresDeviceIdle(false)
                        .build()

                val periodicWork =
                    PeriodicWorkRequestBuilder<WallpaperWorker>(
                        15,
                        TimeUnit.MINUTES,
                    ).setConstraints(constraints)
                        .setInputData(workDataOf(KEY_REASON to "periodic"))
                        .build()

                WorkManager
                    .getInstance(context)
                    .enqueueUniquePeriodicWork(
                        PERIODIC_WORK_NAME,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        periodicWork,
                    )

                Log.d(TAG, "schedulePeriodicWork: scheduled (15 min period)")
            } catch (e: Exception) {
                Log.e(TAG, "schedulePeriodicWork failed", e)
            }
        }

        /**
         * 调度一次性 Work（快速重试）
         * @param delayMs 延迟时间（毫秒）
         */
        fun scheduleOneTimeWork(
            context: Context,
            delayMs: Long,
            reason: String,
        ) {
            try {
                val oneTimeWork =
                    OneTimeWorkRequestBuilder<WallpaperWorker>()
                        .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf(KEY_REASON to reason))
                        .build()

                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(
                        ONE_TIME_WORK_NAME,
                        ExistingWorkPolicy.REPLACE,
                        oneTimeWork,
                    )

                Log.d(TAG, "scheduleOneTimeWork: scheduled (delay=${delayMs}ms, reason=$reason)")
            } catch (e: Exception) {
                Log.e(TAG, "scheduleOneTimeWork failed", e)
            }
        }

        /**
         * 取消所有 Work
         */
        fun cancelAllWork(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
                WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK_NAME)
                Log.d(TAG, "cancelAllWork: cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "cancelAllWork failed", e)
            }
        }
    }

    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_REASON) ?: "unknown"
        Log.d(TAG, "doWork: reason=$reason, process=${getProcessName()}")

        return try {
            // 1. 检查 forceSystemWallpaper 开关
            val forceSystemWallpaper =
                try {
                    WallpaperManager.get(applicationContext).getForceSystemWallpaper()
                } catch (e: Exception) {
                    applicationContext
                        .getSharedPreferences("mineradio_wallpaper_prefs", Context.MODE_PRIVATE)
                        .getBoolean("force_system_wallpaper", false)
                }

            if (!forceSystemWallpaper) {
                Log.d(TAG, "doWork: forceSystemWallpaper=off, skip")
                return Result.success()
            }

            // 2. 检查当前壁纸是否是 LWService
            val sysWm = android.app.WallpaperManager.getInstance(applicationContext)
            val wallpaperInfo = sysWm.wallpaperInfo
            val isLWServiceActive =
                wallpaperInfo?.packageName == applicationContext.packageName &&
                    wallpaperInfo?.serviceName == "com.mineradio.app.wallpaper.LWService"

            Log.d(
                TAG,
                "doWork: isLWServiceActive=$isLWServiceActive, LWService.isRunning=${LWService.isRunning}",
            )

            // 3. 如果壁纸被切换走，尝试恢复
            if (!isLWServiceActive) {
                Log.d(TAG, "doWork: ★ wallpaper was switched away, trying to recover...")

                // 尝试反射激活
                val reflectOk = tryReflectSetWallpaperComponent()
                if (reflectOk) {
                    Log.d(TAG, "doWork: ★ recovery succeeded via reflection")
                    // 等待 2 秒让系统绑定
                    Thread.sleep(2000)
                } else {
                    Log.w(TAG, "doWork: recovery failed via reflection")
                }
            }

            // 4. 启动 KeepAlive 服务
            try {
                val serviceIntent = Intent(applicationContext, WallpaperKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(serviceIntent)
                } else {
                    applicationContext.startService(serviceIntent)
                }
                Log.d(TAG, "doWork: KeepAlive service started")
            } catch (e: Exception) {
                Log.e(TAG, "doWork: start KeepAlive service failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            // 5. 发送刷新广播
            val refreshIntent = Intent(WallpaperReceiver.ACTION_REFRESH)
            refreshIntent.setPackage(applicationContext.packageName)
            applicationContext.sendBroadcast(refreshIntent)

            // 6. 调度一次性 Work（1 秒后），实现快速重试
            if (!isLWServiceActive) {
                scheduleOneTimeWork(applicationContext, 5000, "retry_after_recovery")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "doWork: failed", e)
            Result.retry()
        }
    }

    /**
     * 反射调用 setWallpaperComponent 绑定 LWService
     */
    private fun tryReflectSetWallpaperComponent(): Boolean =
        try {
            val sysWm = android.app.WallpaperManager.getInstance(applicationContext)
            val component = ComponentName(applicationContext, LWService::class.java)
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
                val am = applicationContext.getSystemService(android.app.ActivityManager::class.java)
                am?.runningAppProcesses?.find { it.pid == pid }?.processName ?: "unknown"
            }
        } catch (_: Exception) {
            "unknown"
        }
}
