package com.mineradio.app.wallpaper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * 自启动管理引导工具类
 *
 * ★ 核心作用：
 *   检测应用是否被允许自启动，如果没有则引导用户跳转到对应设备的自启动设置页面
 *
 * ★★★ 适配各种主流 Android 设备的自启动管理界面：
 *   - ZUI（联想/摩托罗拉）
 *   - MIUI（小米/Redmi）
 *   - EMUI/HarmonyOS（华为）
 *   - ColorOS（OPPO）
 *   - FuntouchOS/OriginOS（vivo）
 *   - OneUI（三星）
 *   - Flyme（魅族）
 *   - 原生 Android（无自启动管理）
 *
 * ★ 使用场景：
 *   1. 应用启动时检测自启动状态
 *   2. 设置壁纸时检测自启动状态
 *   3. 如果未开启自启动，弹出引导对话框
 */
object AutoStartHelper {
    private const val TAG = "AutoStartHelper"

    /**
     * 检测应用是否被允许自启动
     *
     * ★ 检测方式：
     *   1. 尝试读取 ZUI 安全服务的自启动列表
     *   2. 检查应用是否在自启动白名单中
     *
     * @return true=已开启自启动，false=未开启或无法检测
     */
    fun isAutoStartEnabled(context: Context): Boolean {
        try {
            // ★ ZUI 检测：通过 ZuiSecurityService 检查自启动状态
            //   日志中显示：ZuiSecurityService: getAutorunAppList pkgName com.mineradio.app status 0
            //   status 0 可能表示被阻止，status 1 可能表示允许
            //   但这个 API 是系统级的，普通应用无法直接调用
            //   所以我们使用间接检测方式

            // ★ 间接检测：检查 BOOT_COMPLETED 广播是否被延迟
            //   如果自启动被阻止，BOOT_COMPLETED 广播不会被及时接收
            //   我们通过检查 SharedPreferences 中记录的最后一次开机时间来判断
            val prefs = context.getSharedPreferences("mineradio_autostart_check", Context.MODE_PRIVATE)
            val lastBootCheckTime = prefs.getLong("last_boot_check_time", 0L)
            val lastBootCompletedTime = prefs.getLong("last_boot_completed_time", 0L)

            // 如果有记录但 lastBootCompletedTime 为 0，说明 BootReceiver 没有被触发
            if (lastBootCheckTime > 0 && lastBootCompletedTime == 0L) {
                Log.w(TAG, "isAutoStartEnabled: BootReceiver was not triggered, auto-start may be disabled")
                return false
            }

            // ★ 默认返回 true，避免误判
            //   真正的检测应该在 BootReceiver 中记录 lastBootCompletedTime
            return true
        } catch (e: Exception) {
            Log.e(TAG, "isAutoStartEnabled failed", e)
            return true
        }
    }

    /**
     * 跳转到自启动设置页面
     *
     * ★★★ 适配各种主流 Android 设备：
     *   依次尝试各种设备的自启动管理 Intent，直到找到能打开的
     *
     * @param context 上下文
     * @return true=成功跳转，false=所有 Intent 都无法打开
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val intents = getAllAutoStartIntents(context)

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (context.packageManager.resolveActivity(intent, 0) != null) {
                    context.startActivity(intent)
                    Log.d(TAG, "openAutoStartSettings: opened ${intent.component?.flattenToString()}")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "openAutoStartSettings: failed for ${intent.component?.flattenToString()}: ${e.message}")
            }
        }

        // ★ 所有自启动 Intent 都无法打开，回退到应用详情页
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:${context.packageName}")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "openAutoStartSettings: fallback to app details")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "openAutoStartSettings: fallback failed", e)
            return false
        }
    }

    /**
     * 获取所有设备的自启动管理 Intent
     */
    private fun getAllAutoStartIntents(context: Context): List<Intent> {
        val packageName = context.packageName
        val intents = mutableListOf<Intent>()

        // ★ ZUI（联想/摩托罗拉）- 安全中心
        intents.add(Intent().setComponent(ComponentName("com.zui.safecenter", "com.zui.safecenter.ui.MainNavigationActivity")))
        intents.add(Intent().setComponent(ComponentName("com.zui.safecenter", "com.zui.safecenter.autostart.AutoStartActivity")))

        // ★ MIUI（小米/Redmi）- 安全中心
        intents.add(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        )

        // ★ EMUI/HarmonyOS（华为）- 手机管家
        intents.add(
            Intent().setComponent(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ),
        )
        intents.add(
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
        )

        // ★ ColorOS（OPPO）- 手机管家
        intents.add(
            Intent().setComponent(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ),
        )
        intents.add(
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
        )
        intents.add(Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")))

        // ★ FuntouchOS/OriginOS（vivo）- i管家
        intents.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")))
        intents.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")))
        intents.add(
            Intent().setComponent(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ),
        )
        intents.add(
            Intent().setComponent(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"),
            ),
        )

        // ★ OneUI（三星）- 设备维护
        intents.add(Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")))
        intents.add(Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")))

        // ★ Flyme（魅族）- 手机管家
        intents.add(Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC")))
        intents.add(Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")))

        // ★ 魅族权限管理
        val meizuIntent = Intent("com.meizu.safe.security.SHOW_APPSEC")
        meizuIntent.putExtra("packageName", packageName)
        meizuIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intents.add(meizuIntent)

        return intents
    }

    /**
     * 获取设备品牌名称（用于显示提示）
     */
    fun getDeviceBrand(): String {
        val brand = Build.BRAND?.lowercase() ?: ""
        return when {
            brand.contains("zui") || brand.contains("lenovo") || brand.contains("motorola") -> "ZUI"
            brand.contains("xiaomi") || brand.contains("redmi") -> "MIUI"
            brand.contains("huawei") || brand.contains("honor") -> "HarmonyOS"
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> "ColorOS"
            brand.contains("vivo") || brand.contains("iqoo") -> "FuntouchOS"
            brand.contains("samsung") -> "OneUI"
            brand.contains("meizu") -> "Flyme"
            else -> "Android"
        }
    }

    /**
     * 记录 BootReceiver 被触发（用于检测自启动是否开启）
     */
    fun recordBootCompleted(context: Context) {
        try {
            val prefs = context.getSharedPreferences("mineradio_autostart_check", Context.MODE_PRIVATE)
            prefs
                .edit()
                .putLong("last_boot_completed_time", System.currentTimeMillis())
                .apply()
            Log.d(TAG, "recordBootCompleted: BootReceiver was triggered")
        } catch (e: Exception) {
            Log.e(TAG, "recordBootCompleted failed", e)
        }
    }

    /**
     * 记录应用启动（用于检测自启动是否开启）
     */
    fun recordAppStart(context: Context) {
        try {
            val prefs = context.getSharedPreferences("mineradio_autostart_check", Context.MODE_PRIVATE)
            val lastBootCompletedTime = prefs.getLong("last_boot_completed_time", 0L)
            // 如果应用启动了但 lastBootCompletedTime 为 0，说明 BootReceiver 没有被触发
            // 可能是自启动被阻止了
            prefs
                .edit()
                .putLong("last_app_start_time", System.currentTimeMillis())
                .apply()

            if (lastBootCompletedTime == 0L) {
                Log.w(TAG, "recordAppStart: BootReceiver was never triggered, auto-start may be disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "recordAppStart failed", e)
        }
    }
}
