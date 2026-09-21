package com.mineradio.app.wallpaper

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 壁纸数据实体
 * 移植自 project_lw 开源项目（top.geekcloud.project_lw.entity.Wallpaper）
 */
data class WallpaperEntity(
    val author: String = "",
    val description: String = "",
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val versionCode: Int = 0,
    val versionName: String = "",
    val wallpaperType: Int = TYPE_HTML,
) {
    companion object {
        const val TYPE_HTML = 0
        const val TYPE_VIDEO = 1
        const val TYPE_VIEW = 2
        const val TYPE_IMAGE = 3

        /**
         * ★★★ MPKG 场景壁纸（Wallpaper Engine 场景包）
         * - 使用 WebView + WebGL 渲染 PKGM0012 打包的场景资源
         * - 支持桌面动态壁纸和应用内预览
         * - 兼容 Wallpaper Engine 的 .mpkg 格式（含 .mdl 模型、.tex 纹理、.vert/.frag 着色器、techno.json 场景定义）
         */
        const val TYPE_MPKG_WEBGL = 4

        /** 外部存储根目录名（与 WallpaperManager 中保持一致） */
        private const val EXTERNAL_DIR_NAME = "Mineradio/Wallpaper"

        /** app 私有目录下存放 MPKG 文件的子目录名 */
        private const val MPKG_PRIVATE_DIR = "mpkg"
    }

    /**
     * 获取壁纸真实路径
     * - 若 path 以 http 开头，直接返回（网络壁纸）
     * - 否则返回外部公共存储目录下的绝对路径
     *   路径：/sdcard/Documents/Mineradio/Wallpaper/<id>/<path>
     *   清理应用数据不会删除此路径
     */
    fun getRealPath(context: Context): String {
        if (path.startsWith("http")) return path
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(docsDir, "$EXTERNAL_DIR_NAME/$id")
        return File(dir, path).absolutePath
    }

    /**
     * 获取 MPKG 文件在 app 私有目录中的路径
     * native 库 libscenejni.so 只能读取 app 私有目录下的 mpkg 文件
     * 路径：/data/data/com.mineradio.app/files/mpkg/<id>_<filename>
     */
    fun getMpkgPrivatePath(context: Context): String {
        val mpkgDir = File(context.filesDir, MPKG_PRIVATE_DIR)
        if (!mpkgDir.exists()) mpkgDir.mkdirs()
        return File(mpkgDir, "${id}_$path").absolutePath
    }
}
