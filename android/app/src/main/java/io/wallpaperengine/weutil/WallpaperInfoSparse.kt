package io.wallpaperengine.weutil

import android.graphics.Point

/** 壁纸元信息稀疏版（原 WallpaperInfoSparse.java 转写，见 SceneLib.kt 顶部说明）。 */
class WallpaperInfoSparse {
    var type: String? = null
    var title: String? = null
    var filePath: String? = null
    var resolution: Point? = null
    var description: String? = null
    var lastModifiedDate: Long = 0L
    var isError: Boolean = false
    var isLocal: Boolean = false
    var isOfficial: Boolean = false
    var progress: Int = 0
}
