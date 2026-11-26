package com.mineradio.android

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * 安卓版「内存管理 / 缓存与存储」后端。
 * 参考工程的这两个面板走 Electron 的 window.desktopWindow，手机上完全不可用；
 * 这里提供等价的原生数据与清理能力。
 */
@CapacitorPlugin(name = "MineradioSystem")
class SystemToolsPlugin : Plugin() {

    private fun memoryInfo(): JSObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMB = (mi.totalMem / (1024 * 1024)).toInt()
        val availMB = (mi.availMem / (1024 * 1024)).toInt()
        val usedMB = (totalMB - availMB).coerceAtLeast(0)
        val percent = if (totalMB > 0) Math.round(usedMB * 100f / totalMB) else 0
        val pssMB = try {
            val infos = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))
            if (infos != null && infos.isNotEmpty()) infos[0].totalPss / 1024 else 0
        } catch (e: Exception) {
            0
        }
        val process = JSObject()
        process.put("rssMB", pssMB)
        val snapshot = JSObject()
        snapshot.put("totalMB", totalMB)
        snapshot.put("usedMB", usedMB)
        snapshot.put("freeMB", availMB)
        snapshot.put("usedPercent", percent)
        snapshot.put("process", process)
        return snapshot
    }

    @PluginMethod
    fun getMemorySnapshot(call: PluginCall) {
        try {
            val out = JSObject()
            out.put("ok", true)
            out.put("snapshot", memoryInfo())
            // 安卓没有 Windows Mem Reduct 那种系统级释放
            out.put("systemPurgeAvailable", false)
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("getMemorySnapshot failed: " + e.message, e)
        }
    }

    @PluginMethod
    fun trimAppMemory(call: PluginCall) {
        try {
            Runtime.getRuntime().gc()
            try { Debug.getMemoryInfo(Debug.MemoryInfo()) } catch (e: Exception) { }
            val out = JSObject()
            out.put("ok", true)
            out.put("after", memoryInfo())
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("trimAppMemory failed: " + e.message, e)
        }
    }

    // ── 缓存与存储 ────────────────────────────────────

    private fun dirSize(dir: java.io.File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var total = 0L
        dir.listFiles()?.forEach { f ->
            total += if (f.isDirectory) dirSize(f) else f.length()
        }
        return total
    }

    private fun firstExisting(context: Context, vararg dirs: java.io.File): java.io.File {
        for (d in dirs) if (d.exists()) return d
        return dirs.first()
    }

    @PluginMethod
    fun getCacheSettings(call: PluginCall) {
        try {
            val cacheDir = context.cacheDir
            val filesDir = context.filesDir
            val externalDir = context.getExternalFilesDir(null)
                ?: java.io.File(context.filesDir.parentFile ?: context.filesDir, "external")

            val lyricsDir = firstExisting(context, java.io.File(filesDir, "lyrics"), java.io.File(cacheDir, "lyrics"))
            val beatmapsDir = firstExisting(context, java.io.File(filesDir, "beatmaps"), java.io.File(cacheDir, "beatmaps"))
            val wallpaperDir = firstExisting(
                context,
                java.io.File(filesDir, "mpkg"),
                externalDir,
            )

            val settings = JSObject()
            settings.put("rootPath", filesDir.parentFile?.absolutePath ?: filesDir.absolutePath)
            settings.put("lyricsPath", lyricsDir.absolutePath)
            settings.put("chromiumPath", cacheDir.absolutePath)
            settings.put("beatmapsPath", beatmapsDir.absolutePath)
            settings.put("wallpaperEnginePath", wallpaperDir.absolutePath)
            settings.put("userDataPath", filesDir.absolutePath)
            settings.put("restartRequired", false)

            val usage = JSObject()
            val cacheBytes = dirSize(cacheDir)
            val filesBytes = dirSize(filesDir)
            val externalBytes = dirSize(externalDir)
            usage.put("totalManagedBytes", cacheBytes + filesBytes + externalBytes)
            usage.put("lyricsBytes", dirSize(lyricsDir))
            usage.put("chromiumBytes", cacheBytes)
            usage.put("beatmapsBytes", dirSize(beatmapsDir))
            usage.put("wallpaperEngineBytes", dirSize(wallpaperDir))
            usage.put("userDataBytes", filesBytes)

            val out = JSObject()
            out.put("ok", true)
            out.put("settings", settings)
            out.put("usage", usage)
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("getCacheSettings failed: " + e.message, e)
        }
    }

    /** 只清缓存目录（WebView/网络/临时文件），不动用户数据与导入的壁纸插件。 */
    @PluginMethod
    fun clearCache(call: PluginCall) {
        try {
            var freed = 0L
            context.cacheDir.listFiles()?.forEach { f ->
                freed += dirSize(f)
                try { f.deleteRecursively() } catch (e: Exception) { }
            }
            val out = JSObject()
            out.put("ok", true)
            out.put("freedBytes", freed)
            call.resolve(out)
        } catch (e: Exception) {
            call.reject("clearCache failed: " + e.message, e)
        }
    }
}
