package com.mineradio.app.wallpaper

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 系统壁纸变化监听接收器（★★★ 关键防护层）
 *
 * ★ 核心作用：
 *   当系统壁纸被切换走（ZUI 清理器、用户手动切换、其他壁纸应用抢占）时，
 *   系统会立即发送 ACTION_WALLPAPER_CHANGED 广播。
 *   本接收器收到广播后立即触发恢复流程，无需等待 2 秒 Handler 轮询。
 *
 * ★★★ 这是解决"主程序后台十几秒壁纸回退"问题的关键组件：
 *   - ZUI 清理后台时会主动解绑 LWService 并回退到系统壁纸
 *   - ACTION_WALLPAPER_CHANGED 会在回退瞬间立即触发
 *   - 本接收器收到后立即重新激活 LWService，恢复视频壁纸
 *
 * ★ 防护层级：第 0 层（最快响应，在壁纸被切换的瞬间触发）
 *
 * 注意：
 *   1. ACTION_WALLPAPER_CHANGED 是受保护广播，只能由系统发送
 *   2. 需要在 AndroidManifest 中静态注册
 *   3. 收到广播后通过 goAsync() 在后台线程执行恢复，避免 ANR
 */
class WallpaperChangeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "WallpaperChangeRecv"
        private const val MAX_ACTIVATE_RETRY = 5

        /** ★ 重试间隔（毫秒）：500ms / 1s / 2s / 3s / 5s */
        private val RETRY_DELAYS = longArrayOf(500, 1000, 2000, 3000, 5000)

        /** ★ 防止短时间内重复激活（去抖动） */
        @Volatile
        private var lastActivateTime: Long = 0L
        private const val MIN_ACTIVATE_INTERVAL_MS = 2000L
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Log.d(TAG, "onReceive: action=${intent.action}, process=${getProcessName(context)}")

        if (intent.action != Intent.ACTION_WALLPAPER_CHANGED) {
            return
        }

        // ★ 去抖动：2 秒内只处理一次，避免系统短时间内多次发送广播
        val now = SystemClock.elapsedRealtime()
        if (now - lastActivateTime < MIN_ACTIVATE_INTERVAL_MS) {
            Log.d(TAG, "onReceive: debounced, skip (last activate ${now - lastActivateTime}ms ago)")
            return
        }
        lastActivateTime = now

        // 使用 goAsync() 让 BroadcastReceiver 有更多时间执行后台操作
        val pendingResult = goAsync()
        Thread {
            try {
                // ★ 检查 forceSystemWallpaper 开关
                val forceSystemWallpaper =
                    try {
                        WallpaperManager.get(context).getForceSystemWallpaper()
                    } catch (e: Exception) {
                        Log.w(TAG, "getForceSystemWallpaper failed, fallback to SharedPreferences", e)
                        context
                            .getSharedPreferences("mineradio_wallpaper_prefs", Context.MODE_PRIVATE)
                            .getBoolean("force_system_wallpaper", false)
                    }

                if (!forceSystemWallpaper) {
                    Log.d(TAG, "forceSystemWallpaper=off, skip auto-activate")
                    return@Thread
                }

                // ★ 检查是否有当前壁纸配置
                val current =
                    try {
                        WallpaperManager.get(context).getCurrentWallpaper()
                    } catch (e: Exception) {
                        Log.w(TAG, "getCurrentWallpaper failed", e)
                        null
                    }

                if (current == null) {
                    Log.d(TAG, "no current wallpaper config, skip")
                    return@Thread
                }

                // ★ 只对 VIDEO/HTML 类型壁纸激活 LWService
                if (current.wallpaperType != WallpaperEntity.TYPE_VIDEO &&
                    current.wallpaperType != WallpaperEntity.TYPE_HTML
                ) {
                    Log.d(TAG, "not video/html wallpaper, skip")
                    return@Thread
                }

                // ★ 检查当前系统壁纸是否已被切换走
                val sysWm = android.app.WallpaperManager.getInstance(context)
                val wallpaperInfo = sysWm.wallpaperInfo
                val isLWServiceActive =
                    wallpaperInfo?.packageName == context.packageName &&
                        wallpaperInfo?.serviceName == "com.mineradio.app.wallpaper.LWService"

                if (isLWServiceActive) {
                    Log.d(TAG, "LWService still active, no need to reactivate")
                    return@Thread
                }

                Log.d(TAG, "★ Wallpaper was switched away! Starting recovery process...")

                // ★★★ 多层重试激活壁纸
                activateWallpaperWithRetry(context)

                // ★ 同时启动 KeepAlive 服务，让保活机制继续运行
                try {
                    val serviceIntent = Intent(context, WallpaperKeepAliveService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d(TAG, "KeepAlive service started")
                } catch (e: Exception) {
                    Log.e(TAG, "start KeepAlive service failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "WallpaperChangeReceiver thread failed", e)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    /**
     * ★★★ 多层重试激活壁纸
     *
     * 重试策略（指数退避）：500ms / 1s / 2s / 3s / 5s
     * 应对系统刚切换壁纸时的瞬时错误
     */
    private fun activateWallpaperWithRetry(context: Context) {
        for (attempt in 0 until MAX_ACTIVATE_RETRY) {
            Log.d(TAG, "activateWallpaperWithRetry: attempt ${attempt + 1}/$MAX_ACTIVATE_RETRY")

            val ok = trySetWallpaperComponent(context)
            if (ok) {
                Log.d(TAG, "★ activateWallpaperWithRetry: succeeded at attempt ${attempt + 1}")

                // ★ 等待 1 秒让系统绑定 LWService，然后验证
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }

                val wallpaperInfo =
                    android.app.WallpaperManager
                        .getInstance(context)
                        .wallpaperInfo
                val isActive =
                    wallpaperInfo?.packageName == context.packageName &&
                        wallpaperInfo?.serviceName == "com.mineradio.app.wallpaper.LWService"
                if (isActive) {
                    Log.d(TAG, "★ LWService confirmed active after recovery")
                    // 发送刷新广播
                    val refreshIntent = Intent(WallpaperReceiver.ACTION_REFRESH)
                    refreshIntent.setPackage(context.packageName)
                    context.sendBroadcast(refreshIntent)
                    return
                } else {
                    Log.w(TAG, "setWallpaperComponent returned ok but LWService not active, retrying...")
                }
            } else {
                Log.w(TAG, "activateWallpaperWithRetry: attempt ${attempt + 1} failed")
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

        Log.e(TAG, "★★★ activateWallpaperWithRetry: all $MAX_ACTIVATE_RETRY attempts failed, silent retry (no Activity launch)")

        // ★★★ 修复：原实现会 context.startActivity(MainActivity) 拉起应用界面，
        //   导致用户在后台/被杀时被自动跳前台。现改为静默重试，不拉起任何 Activity。
        //   KeepAlive 服务心跳会继续尝试反射激活，用户可主动打开应用或调整「强制系统壁纸」开关。
    }

    /**
     * 反射调用 setWallpaperComponent 绑定 LWService
     * @return true=成功，false=失败
     */
    private fun trySetWallpaperComponent(context: Context): Boolean {
        // ★ 第1层：尝试通过 WallpaperManager 反射调用 setWallpaperComponent
        val reflectOk = tryReflectSetWallpaperComponent(context)
        if (reflectOk) return true

        // ★ 第2层：尝试通过 IWallpaperManager 反射调用（底层接口）
        val aidlOk = tryIWallpaperManager(context)
        if (aidlOk) return true

        return false
    }

    /**
     * 第1层：通过 WallpaperManager 反射调用 setWallpaperComponent
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

    /**
     * ★★ 第2层：通过 IWallpaperManager 反射调用（底层 AIDL 接口）
     *
     * WallpaperManager.setWallpaperComponent 实际是调用 IWallpaperManager.setWallpaperComponent
     * 直接反射底层接口可能绕过某些权限检查
     */
    private fun tryIWallpaperManager(context: Context): Boolean =
        try {
            val sysWm = android.app.WallpaperManager.getInstance(context)
            // 反射获取 WallpaperManager 内部的 IWallpaperManager 字段
            val wmClass = android.app.WallpaperManager::class.java
            val sGlobalField = wmClass.getDeclaredField("sGlobal")
            sGlobalField.isAccessible = true
            val sGlobal = sGlobalField.get(null)

            // 获取 IWallpaperManager 字段
            val wallpaperManagerField =
                sGlobal.javaClass.getDeclaredField("mWallpaperManager")
            wallpaperManagerField.isAccessible = true
            val iWallpaperManager = wallpaperManagerField.get(sGlobal)

            // 调用 setWallpaperComponentChecked 或 setWallpaperComponent
            val component = ComponentName(context, LWService::class.java)
            val method =
                iWallpaperManager.javaClass.getMethod(
                    "setWallpaperComponentChecked",
                    ComponentName::class.java,
                    String::class.java,
                )
            method.invoke(iWallpaperManager, component, context.packageName)
            Log.d(TAG, "tryIWallpaperManager: succeeded via setWallpaperComponentChecked")
            true
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            Log.w(
                TAG,
                "tryIWallpaperManager: failed (cause): ${cause?.javaClass?.simpleName}: ${cause?.message}",
            )
            false
        } catch (e: NoSuchMethodException) {
            // ★ 尝试旧版方法 setWallpaperComponent
            tryIWallpaperManagerLegacy(context)
        } catch (e: Exception) {
            Log.w(TAG, "tryIWallpaperManager: failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }

    /**
     * 第2层兜底：旧版 IWallpaperManager.setWallpaperComponent
     */
    private fun tryIWallpaperManagerLegacy(context: Context): Boolean =
        try {
            val sysWm = android.app.WallpaperManager.getInstance(context)
            val wmClass = android.app.WallpaperManager::class.java
            val sGlobalField = wmClass.getDeclaredField("sGlobal")
            sGlobalField.isAccessible = true
            val sGlobal = sGlobalField.get(null)

            val wallpaperManagerField =
                sGlobal.javaClass.getDeclaredField("mWallpaperManager")
            wallpaperManagerField.isAccessible = true
            val iWallpaperManager = wallpaperManagerField.get(sGlobal)

            val component = ComponentName(context, LWService::class.java)
            val method =
                iWallpaperManager.javaClass.getMethod(
                    "setWallpaperComponent",
                    ComponentName::class.java,
                )
            method.invoke(iWallpaperManager, component)
            Log.d(TAG, "tryIWallpaperManagerLegacy: succeeded via setWallpaperComponent")
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
