package io.wallpaperengine.weutil

/** mpkg 虚拟文件条目的偏移与大小（原 VirtualFileRange.java 转写，见 SceneLib.kt 顶部说明）。 */
class VirtualFileRange(
    var vOffset: Long = 0L,
    var vSize: Long = 0L,
    var fileName: String? = null,
) {
    constructor(offset: Long, size: Long) : this(offset, size, null)
}
