package com.mineradio.app.wallpaper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * 壁纸操作辅助类
 * 提供设置系统桌面壁纸和应用背景壁纸的统一入口
 */
object WallpaperHelper {
    private const val TAG = "WallpaperHelper"

    /**
     * 跳转到系统壁纸选择器，将 LWService 设为动态壁纸
     */
    fun gotoSystemWallpaperSettings(context: Context) {
        val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        intent.putExtra(
            android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(context, LWService::class.java),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "gotoSystemWallpaperSettings failed", e)
            // 回退到系统壁纸设置页面
            try {
                val fallback = Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
            } catch (e2: Exception) {
                Log.e(TAG, "fallback to live wallpaper chooser also failed", e2)
            }
        }
    }

    /**
     * 设置图片为系统静态壁纸
     */
    fun setImageAsSystemWallpaper(
        context: Context,
        wallpaper: WallpaperEntity,
    ): Boolean {
        return try {
            val path = wallpaper.getRealPath(context)
            val file = File(path)
            if (!file.exists()) {
                Log.e(TAG, "setImageAsSystemWallpaper: file not found $path")
                return false
            }
            val bitmap = BitmapFactory.decodeFile(path) ?: return false
            val wm = android.app.WallpaperManager.getInstance(context)
            wm.setBitmap(bitmap)
            bitmap.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "setImageAsSystemWallpaper failed", e)
            false
        }
    }

    /**
     * 清除系统壁纸
     */
    fun clearSystemWallpaper(context: Context) {
        try {
            // 先清除当前壁纸记录
            WallpaperManager.get(context).setCurrentWallpaper(null)
            // 通过 WallpaperManager 清除系统壁纸
            val wm = android.app.WallpaperManager.getInstance(context)
            wm.clear()
        } catch (e: Exception) {
            Log.e(TAG, "clearSystemWallpaper failed", e)
        }
    }

    /**
     * 设置当前壁纸为系统动态壁纸
     * - HTML / VIDEO 类型：保存到 WallpaperManager 并直接弹出系统动态壁纸确认界面
     * - IMAGE 类型：直接通过 WallpaperManager.setBitmap 设置
     */
    fun applyAsSystemWallpaper(
        context: Context,
        wallpaper: WallpaperEntity,
    ): ApplyResult {
        // 先保存到当前壁纸记录
        WallpaperManager.get(context).setCurrentWallpaper(wallpaper)
        // 启动保活服务，确保壁纸在后台持续运行
        WallpaperKeepAliveService.start(context)
        return when (wallpaper.wallpaperType) {
            WallpaperEntity.TYPE_IMAGE -> {
                val ok = setImageAsSystemWallpaper(context, wallpaper)
                if (ok) ApplyResult.SuccessDirect else ApplyResult.Failed
            }
            WallpaperEntity.TYPE_HTML, WallpaperEntity.TYPE_VIDEO, WallpaperEntity.TYPE_MPKG_WEBGL -> {
                // 直接弹出系统动态壁纸确认界面（带预览，用户只需点"设置壁纸"）
                gotoSystemWallpaperSettings(context)
                ApplyResult.NeedSystemActivate
            }
            else -> ApplyResult.Failed
        }
    }

    /**
     * 设置应用背景壁纸
     */
    fun setAppBackgroundWallpaper(
        context: Context,
        wallpaper: WallpaperEntity?,
    ) {
        WallpaperManager.get(context).setAppBackgroundWallpaper(wallpaper)
    }

    /**
     * 清除应用背景壁纸
     */
    fun clearAppBackgroundWallpaper(context: Context) {
        WallpaperManager.get(context).setAppBackgroundWallpaper(null)
    }

    /**
     * 应用操作结果
     */
    sealed class ApplyResult {
        /** 直接成功（图片壁纸） */
        object SuccessDirect : ApplyResult()

        /** 需要用户去系统设置激活动态壁纸 */
        object NeedSystemActivate : ApplyResult()

        /** 设置失败 */
        object Failed : ApplyResult()
    }
}
