package com.mineradio.app.wallpaper

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机自启动接收器（★★★ 多层防护版）
 *
 * 设备启动完成后，自动激活壁纸服务，确保重启手机后壁纸能正常显示。
 *
 * ★★★ 多层防护机制（解决重启手机后壁纸回到系统界面的问题）：
 *
 * 【第1层】多广播监听
 *   - BOOT_COMPLETED（标准开机广播）
 *   - LOCKED_BOOT_COMPLETED（Direct Boot 模式，解锁前就能收到）
 *   - MY_PACKAGE_REPLACED（应用更新后）
 *   - USER_PRESENT（用户解锁屏幕）
 *   - SCREEN_ON（屏幕点亮）
 *
 * 【第2层】多层重试激活
 *   - 首次激活失败后，按 2s/4s/8s 间隔重试 3 次
 *   - 应对系统刚启动时反射 setWallpaperComponent 失败的瞬时错误
 *
 * 【第3层】开关多层持久化
 *   - 通过 WallpaperManager 读取 forceSystemWallpaper（外部存储 + SharedPreferences 双读）
 *   - "清除数据"不会丢失外部存储的开关
 *
 * 【第4层】有壁纸配置就尝试激活
 *   - 即使开关读取失败（返回 false），只要外部存储有壁纸配置，也尝试静默反射激活
 *   - 反射激活是静默的，不会弹窗，所以即使开关"误判"也不会打扰用户
 *
 * 关键注意事项：
 * 1. 需要用户授予"自启动"权限（在系统设置中），否则收不到 BOOT_COMPLETED 广播
 * 2. 如果用户在系统设置中"强制停止"了应用，此机制会失效，直到下次手动打开应用
 * 3. 测试方法：不要用 AS 直接跑，要先手动启动一次应用，再重启手机验证
 *
 * ★ LWService 和 WallpaperKeepAliveService 运行在 :wallpaper 独立进程
 *   BootReceiver 运行在主进程，主要职责：
 *   1. 检查系统壁纸是否是 LWService，如果不是则尝试通过反射激活
 *   2. 启动保活服务维持 :wallpaper 进程
 *   3. 发送刷新广播让 LWService 重新加载壁纸
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MRBootReceiver"

        /** ★ 多层重试次数 */
        private const val MAX_ACTIVATE_RETRY = 3

        /** ★ 重试间隔（毫秒）：2s / 4s / 8s */
        private val RETRY_DELAYS = longArrayOf(2000, 4000, 8000)
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Log.d(TAG, "onReceive: action=${intent.action}, process=${getProcessName(context)}")

        // ★★★ 记录 BootReceiver 被触发（用于检测自启动是否开启）
        AutoStartHelper.recordBootCompleted(context)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SCREEN_ON,
            -> {
                // 使用 goAsync() 让 BroadcastReceiver 有更多时间执行后台操作
                val pendingResult = goAsync()
                Thread {
                    try {
                        // ★ USER_PRESENT / SCREEN_ON 不需要等 1 秒，立即拉起
                        val delay =
                            if (intent.action == Intent.ACTION_USER_PRESENT ||
                                intent.action == Intent.ACTION_SCREEN_ON
                            ) {
                                0L
                            } else {
                                1000L
                            }
                        Thread.sleep(delay)
                        activateWallpaperServiceWithRetry(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "activateWallpaperService thread failed", e)
                    } finally {
                        pendingResult.finish()
                    }
                }.start()
            }
        }
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

    /**
     * ★★★ 多层重试激活壁纸服务
     *
     * 首次激活失败后，按 2s/4s/8s 间隔重试 3 次
     * 应对系统刚启动时反射 setWallpaperComponent 失败的瞬时错误
     */
    private fun activateWallpaperServiceWithRetry(context: Context) {
        for (attempt in 0 until MAX_ACTIVATE_RETRY) {
            Log.d(TAG, "activateWallpaperServiceWithRetry: attempt ${attempt + 1}/$MAX_ACTIVATE_RETRY")
            val ok = activateWallpaperService(context)
            if (ok) {
                Log.d(TAG, "activateWallpaperServiceWithRetry: succeeded at attempt ${attempt + 1}")
                return
            }
            // 重试间隔
            if (attempt < RETRY_DELAYS.size) {
                try {
                    Thread.sleep(RETRY_DELAYS[attempt])
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        Log.w(TAG, "activateWallpaperServiceWithRetry: all $MAX_ACTIVATE_RETRY attempts finished")
    }

    /**
     * 激活壁纸服务
     *
     * ★ 改进：无论是否有当前壁纸配置，都启动 WallpaperKeepAliveService（让 :wallpaper 进程独立运行）
     *   这样：
     *   1. 即使用户清理了主程序数据导致配置丢失，:wallpaper 进程仍独立运行
     *   2. 系统已激活的 LWService 仍能继续显示壁纸（即使读取不到配置也只是显示空壁纸）
     *   3. 下次用户重新设置壁纸后，配置写入外部存储，BootReceiver 下次能正常恢复
     *
     * ★★★ 多层防护改进：
     *   1. 通过 WallpaperManager 读取 forceSystemWallpaper（外部存储 + SharedPreferences 双读）
     *   2. 即使开关读取为 false，但外部存储有壁纸配置，也尝试静默反射激活（不弹窗）
     *      反射 setWallpaperComponent 是静默的，不会打扰用户
     *
     * @return true=壁纸已激活或不需要激活，false=需要重试
     */
    private fun activateWallpaperService(context: Context): Boolean {
        try {
            val wm = WallpaperManager.get(context)
            val current = wm.getCurrentWallpaper()

            // ★ 读取桌面萌宠开关（与 force_system_wallpaper 同样的多层防护读取）
            val desktopPetEnabled =
                try {
                    wm.getDesktopPetEnabled()
                } catch (e: Exception) {
                    Log.w(TAG, "activateWallpaperService: getDesktopPetEnabled failed", e)
                    context
                        .getSharedPreferences("mineradio_wallpaper_prefs", Context.MODE_PRIVATE)
                        .getBoolean("desktop_pet_enabled", false)
                }

            // ★★★ 桌面萌宠悬浮窗：开关开启时启动悬浮窗服务（不依赖系统壁纸设置）
            if (desktopPetEnabled) {
                try {
                    if (android.provider.Settings.canDrawOverlays(context)) {
                        PetFloatingService.start(context)
                        Log.d(TAG, "activateWallpaperService: PetFloatingService started")
                    } else {
                        Log.w(TAG, "activateWallpaperService: overlay permission not granted, pet won't show")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "activateWallpaperService: PetFloatingService.start failed", e)
                }
            }

            // ★ 即使没有当前壁纸，也继续启动保活服务（让 :wallpaper 进程独立运行）
            if (current == null) {
                Log.d(
                    TAG,
                    "activateWallpaperService: no current wallpaper, but still start keep-alive service (petEnabled=$desktopPetEnabled)",
                )
                // ★★★ 桌面萌宠：开关开启时也需要激活 LWService
                if (desktopPetEnabled) {
                    val sysWm0 = android.app.WallpaperManager.getInstance(context)
                    val isActive0 =
                        sysWm0.wallpaperInfo?.packageName == context.packageName &&
                            sysWm0.wallpaperInfo?.serviceName == "com.mineradio.app.wallpaper.LWService"
                    if (!isActive0) {
                        Log.d(TAG, "activateWallpaperService: petEnabled, trying setWallpaperComponent")
                        val reflectOk = trySetWallpaperComponent(context)
                        if (reflectOk) {
                            Log.d(TAG, "activateWallpaperService: setWallpaperComponent succeeded (pet mode)")
                            Thread.sleep(2000)
                        } else {
                            Log.e(TAG, "activateWallpaperService: setWallpaperComponent failed (pet mode)")
                        }
                    }
                }
            } else {
                Log.d(TAG, "activateWallpaperService: current wallpaper = ${current.name}, type=${current.wallpaperType}")

                // 检查当前系统壁纸服务是否是 LWService
                val sysWm = android.app.WallpaperManager.getInstance(context)
                val wallpaperInfo = sysWm.wallpaperInfo
                val isLWServiceActive =
                    wallpaperInfo?.packageName == context.packageName &&
                        wallpaperInfo?.serviceName == "com.mineradio.app.wallpaper.LWService"
                Log.d(
                    TAG,
                    "activateWallpaperService: system wallpaper = ${wallpaperInfo?.packageName}/${wallpaperInfo?.serviceName}, isLWServiceActive=$isLWServiceActive",
                )

                // ★★★ 多层防护：通过 WallpaperManager 读取开关（外部存储 + SharedPreferences 双读）
                val forceSystemWallpaper =
                    try {
                        wm.getForceSystemWallpaper()
                    } catch (e: Exception) {
                        Log.w(TAG, "activateWallpaperService: getForceSystemWallpaper failed, fallback to SharedPreferences", e)
                        context
                            .getSharedPreferences("mineradio_wallpaper_prefs", Context.MODE_PRIVATE)
                            .getBoolean("force_system_wallpaper", false)
                    }

                // ★★★ 关键改进：有壁纸配置就尝试静默反射激活（即使开关为 false）
                //   原因：用户"清除数据"会导致 SharedPreferences 丢失，开关读为 false
                //         但外部存储的壁纸配置仍在，且用户之前明确设置了视频壁纸
                //         反射 setWallpaperComponent 是静默的，不会弹窗打扰用户
                //   只有 VIDEO/HTML 类型才需要反射激活（IMAGE 走 setBitmap）
                //   ★★★ 桌面萌宠：pet_enabled 开启时也必须激活 LWService（让 PetWallpaperEngine 显示）
                val shouldTryActivate =
                    (
                        forceSystemWallpaper ||
                            desktopPetEnabled ||
                            current.wallpaperType == WallpaperEntity.TYPE_VIDEO ||
                            current.wallpaperType == WallpaperEntity.TYPE_HTML
                    ) &&
                        !isLWServiceActive &&
                        (
                            desktopPetEnabled ||
                                current.wallpaperType == WallpaperEntity.TYPE_VIDEO ||
                                current.wallpaperType == WallpaperEntity.TYPE_HTML
                        )

                if (shouldTryActivate) {
                    Log.d(
                        TAG,
                        "activateWallpaperService: trying setWallpaperComponent via reflection (forceSystemWallpaper=$forceSystemWallpaper)",
                    )
                    val reflectOk = trySetWallpaperComponent(context)
                    if (reflectOk) {
                        Log.d(TAG, "activateWallpaperService: setWallpaperComponent succeeded")
                        // 反射成功后等待 2 秒让系统绑定 LWService
                        Thread.sleep(2000)
                        // 再次检查是否激活成功
                        val wallpaperInfo2 =
                            android.app.WallpaperManager
                                .getInstance(context)
                                .wallpaperInfo
                        val isActive2 =
                            wallpaperInfo2?.packageName == context.packageName &&
                                wallpaperInfo2?.serviceName == "com.mineradio.app.wallpaper.LWService"
                        if (isActive2) {
                            Log.d(TAG, "activateWallpaperService: LWService confirmed active after reflection")
                        }
                    } else {
                        Log.e(TAG, "activateWallpaperService: setWallpaperComponent failed")
                        // ★★★ 修复：原实现 forceSystemWallpaper=on 时会 context.startActivity(MainActivity)
                        //   拉起应用界面，导致用户在后台/被杀时被自动跳前台。
                        //   现改为静默重试，不拉起任何 Activity。KeepAlive 服务心跳会继续尝试反射激活，
                        //   用户可主动打开应用或调整「强制系统壁纸」开关。
                        return false // 反射失败，需要重试
                    }
                } else if (!isLWServiceActive && !forceSystemWallpaper) {
                    Log.d(TAG, "activateWallpaperService: forceSystemWallpaper=off and not video/html, skip auto-activate")
                }
            }

            // ★ 始终启动壁纸保活前台服务（在 :wallpaper 进程）
            //   即使没有当前壁纸配置，也要让 :wallpaper 进程独立运行
            try {
                val serviceIntent = Intent(context, WallpaperKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "activateWallpaperService: keep-alive service start requested")
            } catch (e: Exception) {
                Log.e(TAG, "activateWallpaperService: startForegroundService failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            // ★★★ 第7层防护：调度 JobScheduler 持久化 Job（设备重启后仍能恢复）
            try {
                WallpaperJobService.scheduleJob(context, "boot_receiver")
            } catch (e: Exception) {
                Log.w(TAG, "activateWallpaperService: scheduleJob failed: ${e.message}")
            }

            // ★★★ 第10层防护：调度 WorkManager 周期性 Work（设备重启后仍能恢复）
            try {
                WallpaperWorker.schedulePeriodicWork(context)
            } catch (e: Exception) {
                Log.w(TAG, "activateWallpaperService: schedulePeriodicWork failed: ${e.message}")
            }

            // 发送刷新广播让 LWService 重新加载壁纸
            val refreshIntent = Intent(WallpaperReceiver.ACTION_REFRESH)
            refreshIntent.setPackage(context.packageName)
            context.sendBroadcast(refreshIntent)
            Log.d(TAG, "activateWallpaperService: sent ACTION_REFRESH broadcast")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "activateWallpaperService failed: ${e.javaClass.simpleName}: ${e.message}", e)
            return false
        }
    }

    /**
     * 反射调用 setWallpaperComponent 绑定 LWService
     * @return true=成功，false=失败
     */
    private fun trySetWallpaperComponent(context: Context): Boolean =
        try {
            val sysWm = android.app.WallpaperManager.getInstance(context)
            val component = ComponentName(context, LWService::class.java)
            val method =
                android.app.WallpaperManager::class.java
                    .getMethod("setWallpaperComponent", ComponentName::class.java)
            method.invoke(sysWm, component)
            true
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            Log.e(
                TAG,
                "trySetWallpaperComponent: failed (cause): ${cause?.javaClass?.simpleName}: ${cause?.message}",
            )
            false
        } catch (e: Exception) {
            Log.e(TAG, "trySetWallpaperComponent: failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
}
