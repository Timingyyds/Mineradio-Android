package com.mineradio.app.common.utils

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * 设备环境兼容工具：识别车机（Android Automotive）、大屏、无摄像头等场景。
 * 用于 minSdk 26 降级与车机框架适配。
 */
object DeviceCompat {

    /**
     * 是否为车机环境（Android Automotive）。
     * 通过 FEATURE_AUTOMOTIVE 系统特性 + UI 模式双重判定。
     */
    fun isAutomotive(context: Context): Boolean {
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return true
        // 部分车机 ROM 未声明 FEATURE_AUTOMOTIVE，通过 uiMode 兜底
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return uiMode == Configuration.UI_MODE_TYPE_CAR
    }

    /**
     * 设备是否有可用摄像头（车机可能无前置摄像头）。
     * 用于决定是否启用手势识别。
     */
    fun hasCamera(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    /**
     * 设备是否有可用麦克风（车机可能无麦克风）。
     * 用于决定是否启用音域回响（Visualizer/AudioRecord）。
     */
    fun hasMicrophone(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }

    /**
     * 车机通常为横屏固定，不应强制竖屏。
     * 调用方在设置 requestedOrientation 前应检查此方法。
     */
    fun shouldForcePortrait(context: Context): Boolean = !isAutomotive(context)
}
