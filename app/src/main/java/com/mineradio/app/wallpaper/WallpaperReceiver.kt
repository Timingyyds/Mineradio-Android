package com.mineradio.app.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 壁纸广播接收器（★★★ 多层防护版）
 * 移植自 project_lw 的 WallpaperReceiver，并增强为多层防护组件
 *
 * ★★★ 增强：
 *   1. ACTION_REFRESH 收到后启动 KeepAlive 服务（触发 tryActivateLWService 恢复壁纸）
 *   2. ACTION_TEST 用于测试广播
 *
 * 这个接收器在以下位置注册：
 *   - WallpaperKeepAliveService.onCreate（动态注册，RECEIVER_NOT_EXPORTED）
 *   - AndroidManifest.xml（静态注册，仅 ACTION_REFRESH，exported=false）
 */
class WallpaperReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "WallpaperReceiver"
        const val ACTION_TEST = "com.mineradio.wallpaper.ACTION_TEST"
        const val ACTION_REFRESH = "com.mineradio.wallpaper.ACTION_REFRESH"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Log.d(TAG, "onReceive: ${intent.action}")
        when (intent.action) {
            ACTION_TEST -> {
                // 测试广播：启动 KeepAlive 服务验证保活机制
                startKeepAliveService(context)
            }
            ACTION_REFRESH -> {
                // ★ 刷新壁纸：启动 KeepAlive 服务，触发 tryActivateLWService 恢复壁纸
                //   KeepAlive 服务的 onStartCommand 会主动检查并激活 LWService
                startKeepAliveService(context)
            }
        }
    }

    /**
     * 启动 KeepAlive 服务（触发壁纸恢复检测）
     */
    private fun startKeepAliveService(context: Context) {
        try {
            val serviceIntent = Intent(context, WallpaperKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "startKeepAliveService: started")
        } catch (e: Exception) {
            Log.e(TAG, "startKeepAliveService failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
