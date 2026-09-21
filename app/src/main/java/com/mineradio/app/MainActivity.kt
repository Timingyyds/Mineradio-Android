package com.mineradio.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * 主 Activity（启动壳 / Launcher）
 *
 * ★ 竖屏 Compose 界面（com.mineradio.app.ui.*）已整体移除，App 现在只保留横屏 H5 界面。
 *   本 Activity 仅承担启动分发职责：
 *   1. 开机自启动 / 自动激活动态壁纸：intent extra `auto_activate_wallpaper=true`
 *   2. 其余情况一律进入横屏界面 [LandscapeWebActivity]，不再进入竖屏
 *
 * ★ 保留本 Activity 作为 LAUNCHER 入口，避免影响既有链路：
 *   - 动态壁纸反射失败 fallback（WallpaperKeepAliveService → MainActivity）
 *   - 崩溃页「重启应用」（CrashActivity → MainActivity）
 *   - adb 脚本入口 `am start -n com.mineradio.app/.MainActivity`
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★★★ 启动动画期间优先并行加载所有壁纸功能（与启动动画并行进行）
        //   WallpaperPreloader 在后台 IO 线程中并行执行 8 项预加载，不阻塞 UI 线程
        //   ★ 多次调用安全：如果 App.onCreate 已调用过，这里会自动跳过
        com.mineradio.app.wallpaper.WallpaperPreloader
            .preloadDuringSplash(this, "MainActivity")

        // ★ 开机自启动：如果检测到 auto_activate_wallpaper=true，尝试自动激活动态壁纸
        val autoActivateWallpaper = intent?.getBooleanExtra("auto_activate_wallpaper", false) == true
        if (autoActivateWallpaper) {
            try {
                val sysWm = android.app.WallpaperManager.getInstance(this)
                val wallpaperInfo = sysWm.wallpaperInfo
                val isLWServiceActive =
                    wallpaperInfo?.packageName == packageName &&
                        wallpaperInfo?.serviceName == "com.mineradio.app.wallpaper.LWService"
                if (!isLWServiceActive) {
                    // ★ 优先尝试通过反射调用 setWallpaperComponent（如果主进程有 SET_WALLPAPER_COMPONENT 权限）
                    var reflectOk = false
                    try {
                        val component = android.content.ComponentName(this, com.mineradio.app.wallpaper.LWService::class.java)
                        val method =
                            android.app.WallpaperManager::class.java
                                .getMethod("setWallpaperComponent", android.content.ComponentName::class.java)
                        method.invoke(sysWm, component)
                        reflectOk = true
                        android.util.Log.d("MainActivity", "auto_activate_wallpaper: setWallpaperComponent succeeded")
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        val cause = e.cause
                        android.util.Log.w(
                            "MainActivity",
                            "auto_activate_wallpaper: setWallpaperComponent failed (cause): ${cause?.javaClass?.simpleName}: ${cause?.message}",
                        )
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "MainActivity",
                            "auto_activate_wallpaper: setWallpaperComponent failed: ${e.javaClass.simpleName}: ${e.message}",
                        )
                    }

                    // ★ 反射失败，回退到系统动态壁纸激活界面（需要用户点击"设置壁纸"）
                    if (!reflectOk) {
                        android.util.Log.d("MainActivity", "auto_activate_wallpaper: fallback to ACTION_CHANGE_LIVE_WALLPAPER")
                        val activateIntent =
                            Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                                putExtra(
                                    android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                    android.content.ComponentName(this@MainActivity, com.mineradio.app.wallpaper.LWService::class.java),
                                )
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        startActivity(activateIntent)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "auto_activate_wallpaper failed", e)
            }
            finish()
            return
        }

        // ★ 唯一去向：横屏 H5 界面（竖屏界面已移除）
        //   force_landscape=true 的脚本入口同样走这里，行为保持一致
        startActivity(Intent(this, LandscapeWebActivity::class.java))
        finish()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
