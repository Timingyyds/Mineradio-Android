package com.mineradio.app.wallpaper

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * MPKG (PKGM0012) 打包格式解析器
 *
 * 文件结构：
 *   headerSize(4B, little-endian) + magic("PKGM0012", 8B) + entryCount(4B)
 *   + 条目表（每条：4B nameLen + name + 4B dataOffset + 4B dataSize）
 *   + 数据区（按 dataOffset 定位）
 *
 * 设计：
 * - 不一次性读入整个 mpkg，按需通过 RandomAccessFile 读取内部文件
 * - 解析一次条目表后，缓存 VirtualFileRange 列表供后续随机访问
 */
class MpkgParser(
    private val mpkgFile: File,
) {
    companion object {
        private const val TAG = "MpkgParser"
        private const val MAGIC = "PKGM0012"

        /** ★ PKGM0014 格式 magic（视频壁纸包，结构同 PKGM0012） */
        private const val MAGIC_14 = "PKGM0014"

        /**
         * ★★★ 支持的所有 PKGM magic 版本
         *   实测线上壁纸包存在 PKGM0012 / PKGM0014 / PKGM0019 / PKGM0020 等多种版本，
         *   条目表结构完全一致（4B nameLen + name + 4B dataOffset + 4B dataSize），
         *   只是 magic 版本号不同。parse() 之前只接受 PKGM0012 会导致
         *   高版本包解析失败 → entries 为空 → 视频壁纸被误识别为场景壁纸 → 黑屏。
         */
        private val SUPPORTED_MAGICS =
            setOf("PKGM0012", "PKGM0013", "PKGM0014", "PKGM0015", "PKGM0016", "PKGM0017", "PKGM0018", "PKGM0019", "PKGM0020")

        /**
         * ★★★ 从 mpkg 文件中提取视频文件（用于视频壁纸播放）
         *
         * 支持 PKGM0012 / PKGM0014 格式：
         *   headerSize(4B, LE) + magic(8B) + entryCount(4B, LE)
         *   + 条目表（每条：4B nameLen + name + 4B dataOffset + 4B dataSize）
         *   + 数据区
         *
         * 找到第一个 .mp4 扩展名的条目并提取到 outputDir
         *
         * @param mpkgPath mpkg 文件绝对路径
         * @param outputDir 输出目录（若不存在会自动创建）
         * @return 提取后的视频文件；失败返回 null
         */
        fun extractVideoFile(
            mpkgPath: String,
            outputDir: File,
        ): File? {
            return try {
                val mpkgFile = File(mpkgPath)
                if (!mpkgFile.exists()) {
                    Log.e(TAG, "extractVideoFile: mpkg not exists: $mpkgPath")
                    return null
                }
                val raf = RandomAccessFile(mpkgFile, "r")
                try {
                    // 1. 读取 headerSize（4B, LE）
                    val headerSizeBytes = ByteArray(4)
                    raf.readFully(headerSizeBytes)
                    val headerSize =
                        ByteBuffer
                            .wrap(headerSizeBytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .int
                            .toLong() and 0xFFFFFFFFL
                    if (headerSize <= 0 || headerSize > mpkgFile.length()) {
                        Log.e(TAG, "extractVideoFile: invalid headerSize=$headerSize, fileLen=${mpkgFile.length()}")
                        return null
                    }
                    // 2. 读取 magic（8B）
                    val magicBytes = ByteArray(8)
                    raf.readFully(magicBytes)
                    val magic = String(magicBytes, StandardCharsets.US_ASCII)
                    // ★ 与 parse() 保持一致：支持所有 PKGM 版本
                    if (magic !in SUPPORTED_MAGICS) {
                        Log.e(TAG, "extractVideoFile: unsupported magic, got=$magic")
                        return null
                    }
                    // 3. 读取 entryCount（4B, LE）
                    val countBytes = ByteArray(4)
                    raf.readFully(countBytes)
                    val entryCount = ByteBuffer.wrap(countBytes).order(ByteOrder.LITTLE_ENDIAN).int
                    if (entryCount <= 0 || entryCount > 10000) {
                        Log.e(TAG, "extractVideoFile: invalid entryCount=$entryCount")
                        return null
                    }
                    // 4. 遍历所有条目，记录视频文件条目（不能 break，需要读完所有条目才能计算数据区起始位置）
                    var videoEntry: Triple<String, Long, Long>? = null // name, relOffset, size
                    for (i in 0 until entryCount) {
                        val nameLenBytes = ByteArray(4)
                        raf.readFully(nameLenBytes)
                        val nameLen = ByteBuffer.wrap(nameLenBytes).order(ByteOrder.LITTLE_ENDIAN).int
                        if (nameLen <= 0 || nameLen > 4096) {
                            Log.e(TAG, "extractVideoFile: invalid nameLen=$nameLen at entry $i")
                            return null
                        }
                        val nameBytes = ByteArray(nameLen)
                        raf.readFully(nameBytes)
                        val name = String(nameBytes, StandardCharsets.UTF_8)
                        val offBytes = ByteArray(4)
                        raf.readFully(offBytes)
                        val dataOffset =
                            ByteBuffer
                                .wrap(offBytes)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .int
                                .toLong() and 0xFFFFFFFFL
                        val sizeBytes = ByteArray(4)
                        raf.readFully(sizeBytes)
                        val dataSize =
                            ByteBuffer
                                .wrap(sizeBytes)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .int
                                .toLong() and 0xFFFFFFFFL
                        Log.d(TAG, "extractVideoFile: entry[$i] name=$name relOffset=$dataOffset size=$dataSize")
                        // ★ 找视频文件（.mp4 优先，其次 .webm/.mkv 等常见视频格式）
                        val lower = name.lowercase()
                        if (lower.endsWith(".mp4") ||
                            lower.endsWith(".webm") ||
                            lower.endsWith(".mkv") ||
                            lower.endsWith(".mov") ||
                            lower.endsWith(".avi") ||
                            lower.endsWith(".m4v")
                        ) {
                            // 优先选 .mp4
                            if (videoEntry == null || lower.endsWith(".mp4")) {
                                videoEntry = Triple(name, dataOffset, dataSize)
                            }
                        }
                    }
                    if (videoEntry == null) {
                        Log.e(TAG, "extractVideoFile: no video entry found in $mpkgPath, entryCount=$entryCount")
                        return null
                    }
                    val (vName, vRelOffset, vSize) = videoEntry
                    // ★ 关键修复：dataOffset 是相对于数据区起始的偏移，不是绝对文件偏移
                    // 数据区起始 = 当前文件指针位置（读完所有条目后的位置）
                    val dataAreaStart = raf.filePointer
                    val vAbsOffset = dataAreaStart + vRelOffset
                    Log.d(
                        TAG,
                        "extractVideoFile: found video entry name=$vName relOffset=$vRelOffset absOffset=$vAbsOffset size=$vSize dataAreaStart=$dataAreaStart",
                    )
                    // 5. 提取视频数据到 outputDir
                    // ★ 使用纯 ASCII 文件名，避免中文/空格导致 MediaPlayer setDataSource 路径解析失败
                    if (!outputDir.exists()) outputDir.mkdirs()
                    val ext = vName.substringAfterLast('.', "mp4").lowercase()
                    val outFile = File(outputDir, "mpkg_extracted_video.$ext")
                    // 如果已存在且大小一致，直接复用
                    if (outFile.exists() && outFile.length() == vSize) {
                        Log.d(TAG, "extractVideoFile: reuse existing ${outFile.absolutePath}, size=${outFile.length()}")
                        return outFile
                    }
                    // ★★★ 关键修复：用流式分块拷贝代替 ByteArray(vSize.toInt())
                    //   原方案对 >200MB 视频会 OOM（ByteArray 申请连续堆内存失败）
                    //   新方案：8KB 缓冲区按块读写，内存占用恒定，可处理任意大小视频
                    raf.seek(vAbsOffset)
                    java.io.FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(8 * 1024)
                        var remaining = vSize
                        while (remaining > 0) {
                            val toRead = if (remaining < buf.size) remaining.toInt() else buf.size
                            raf.readFully(buf, 0, toRead)
                            fos.write(buf, 0, toRead)
                            remaining -= toRead
                        }
                    }
                    Log.d(TAG, "extractVideoFile: extracted to ${outFile.absolutePath}, size=${outFile.length()}")
                    return outFile
                } finally {
                    raf.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "extractVideoFile failed: $mpkgPath", e)
                null
            }
        }
    }

    /** 内部文件虚拟区间（vOffset 相对 mpkg 文件起始的绝对偏移） */
    data class VirtualFileRange(
        val name: String,
        val vOffset: Long,
        val vSize: Long,
    )

    private val entries: LinkedHashMap<String, VirtualFileRange> = LinkedHashMap()

    /** 是否已解析 */
    @Volatile
    private var parsed = false

    /**
     * 解析 mpkg 文件头和条目表
     * @return true 解析成功；false 解析失败（文件损坏或格式不符）
     */
    fun parse(): Boolean {
        if (parsed) return entries.isNotEmpty()
        synchronized(this) {
            if (parsed) return entries.isNotEmpty()
            return try {
                RandomAccessFile(mpkgFile, "r").use { raf ->
                    // 1. 读取 headerSize（4B, little-endian）
                    val headerSizeBytes = ByteArray(4)
                    raf.readFully(headerSizeBytes)
                    val headerSize =
                        ByteBuffer
                            .wrap(headerSizeBytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .int
                            .toLong() and 0xFFFFFFFFL
                    if (headerSize <= 0 || headerSize > mpkgFile.length()) {
                        Log.e(TAG, "parse: invalid headerSize=$headerSize, fileLen=${mpkgFile.length()}")
                        return false
                    }
                    // 2. 读取 magic（8B）
                    val magicBytes = ByteArray(8)
                    raf.readFully(magicBytes)
                    val magic = String(magicBytes, StandardCharsets.US_ASCII)
                    // ★ 支持 PKGM0012~PKGM0020 所有版本（条目表结构一致，仅 magic 版本号不同）
                    //   之前只接受 PKGM0012 会导致高版本包解析失败 → 视频壁纸被误识别为场景壁纸
                    if (magic !in SUPPORTED_MAGICS) {
                        Log.e(TAG, "parse: unsupported magic, got=$magic, supported=$SUPPORTED_MAGICS")
                        return false
                    }
                    // 3. 读取 entryCount（4B, little-endian）
                    val countBytes = ByteArray(4)
                    raf.readFully(countBytes)
                    val entryCount = ByteBuffer.wrap(countBytes).order(ByteOrder.LITTLE_ENDIAN).int
                    if (entryCount <= 0 || entryCount > 10000) {
                        Log.e(TAG, "parse: invalid entryCount=$entryCount")
                        return false
                    }
                    // 4. 读取条目表
                    // 跳过 headerSize 之后可能的填充，直接按流式读取每个条目
                    // 条目结构：4B nameLen + name(nameLen B) + 4B dataOffset + 4B dataSize
                    for (i in 0 until entryCount) {
                        val nameLenBytes = ByteArray(4)
                        raf.readFully(nameLenBytes)
                        val nameLen = ByteBuffer.wrap(nameLenBytes).order(ByteOrder.LITTLE_ENDIAN).int
                        if (nameLen <= 0 || nameLen > 4096) {
                            Log.e(TAG, "parse: invalid nameLen=$nameLen at entry $i")
                            return false
                        }
                        val nameBytes = ByteArray(nameLen)
                        raf.readFully(nameBytes)
                        val name = String(nameBytes, StandardCharsets.UTF_8)
                        val offBytes = ByteArray(4)
                        raf.readFully(offBytes)
                        val dataOffset =
                            ByteBuffer
                                .wrap(offBytes)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .int
                                .toLong() and 0xFFFFFFFFL
                        val sizeBytes = ByteArray(4)
                        raf.readFully(sizeBytes)
                        val dataSize =
                            ByteBuffer
                                .wrap(sizeBytes)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .int
                                .toLong() and 0xFFFFFFFFL
                        entries[name] = VirtualFileRange(name, dataOffset, dataSize)
                    }
                    // ★ 读完所有条目后，当前文件指针就是数据区起始位置
                    //   条目表中的 dataOffset 是相对偏移，需转换为绝对文件偏移，
                    //   否则 readBytes/extractAll 的 raf.seek(vOffset) 会定位错误
                    val dataAreaStart = raf.filePointer
                    val updated = LinkedHashMap<String, VirtualFileRange>()
                    entries.forEach { (k, v) ->
                        updated[k] = VirtualFileRange(v.name, v.vOffset + dataAreaStart, v.vSize)
                    }
                    entries.clear()
                    entries.putAll(updated)
                    parsed = true
                    Log.d(
                        TAG,
                        "parse: success, entries=${entries.size}, headerSize=$headerSize, magic=$magic, dataAreaStart=$dataAreaStart",
                    )
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "parse failed", e)
                false
            }
        }
    }

    /**
     * 获取所有内部文件条目
     */
    fun listEntries(): List<VirtualFileRange> {
        if (!parsed) parse()
        return entries.values.toList()
    }

    /**
     * 是否包含指定内部文件
     */
    fun contains(name: String): Boolean {
        if (!parsed) parse()
        return entries.containsKey(name)
    }

    /**
     * 读取指定内部文件的字节内容
     * @param name 内部文件名（相对路径，如 "techno.json"）
     * @return 文件内容字节；若不存在返回 null
     */
    fun readBytes(name: String): ByteArray? {
        if (!parsed && !parse()) return null
        val entry =
            entries[name] ?: run {
                Log.w(TAG, "readBytes: entry not found: $name")
                return null
            }
        return try {
            RandomAccessFile(mpkgFile, "r").use { raf ->
                raf.seek(entry.vOffset)
                val data = ByteArray(entry.vSize.toInt())
                raf.readFully(data)
                data
            }
        } catch (e: Exception) {
            Log.e(TAG, "readBytes failed: $name", e)
            null
        }
    }

    /**
     * 读取指定内部文件为 UTF-8 文本
     */
    fun readText(name: String): String? = readBytes(name)?.let { String(it, StandardCharsets.UTF_8) }

    /**
     * 将所有内部文件释放到目标目录（用于 WebView 资源访问）
     * @param targetDir 目标目录
     * @return 释放的文件数量
     */
    fun extractAll(targetDir: File): Int {
        if (!parsed && !parse()) return 0
        var count = 0
        entries.forEach { (name, range) ->
            try {
                val outFile = File(targetDir, name)
                outFile.parentFile?.mkdirs()
                RandomAccessFile(mpkgFile, "r").use { raf ->
                    raf.seek(range.vOffset)
                    val data = ByteArray(range.vSize.toInt())
                    raf.readFully(data)
                    outFile.writeBytes(data)
                }
                count++
            } catch (e: Exception) {
                Log.e(TAG, "extractAll: failed to extract $name", e)
            }
        }
        Log.d(TAG, "extractAll: extracted $count/${entries.size} files to ${targetDir.absolutePath}")
        return count
    }

    /**
     * 释放单个内部文件到目标路径
     */
    fun extractTo(
        name: String,
        targetFile: File,
    ): Boolean {
        val data = readBytes(name) ?: return false
        return try {
            targetFile.parentFile?.mkdirs()
            targetFile.writeBytes(data)
            true
        } catch (e: Exception) {
            Log.e(TAG, "extractTo failed: $name -> ${targetFile.absolutePath}", e)
            false
        }
    }
}
