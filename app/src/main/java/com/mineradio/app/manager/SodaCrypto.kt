package com.mineradio.app.manager

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 汽水音乐 (Soda/Qishui) 加密音频解密 — 移植自 guohuiyuan/music-lib soda/crypto.go
 *
 * VIP 歌曲返回 CENC 加密的 MP4 音频，需要用 PlayAuth 提取 AES 密钥后解密:
 * 1. extractKey(playAuth) → 从 playAuth 提取 hex 格式的 AES 密钥
 * 2. decryptAudio(fileData, playAuth) → 解密 MP4/CENC 加密音频
 */
object SodaCrypto {
    private const val TAG = "SodaCrypto"

    /** MP4 box 信息 */
    private data class Mp4Box(
        val offset: Int,
        val size: Int,
        val data: ByteArray,
    )

    /** senc 子样本 */
    private data class SencSubsample(
        val clear: Int,
        val encrypted: Long,
    )

    /** senc 样本 */
    private data class SencSample(
        val iv: ByteArray,
        val subsamples: List<SencSubsample>,
    )

    /**
     * 解密汽水音乐加密音频
     * @param fileData 加密的 MP4 音频数据
     * @param playAuth 播放授权字符串 (从 track_v2 响应的 play_auth 字段获取)
     * @return 解密后的 MP4 音频数据，失败返回 null
     */
    fun decryptAudio(
        fileData: ByteArray,
        playAuth: String,
    ): ByteArray? {
        return try {
            val hexKey =
                extractKey(playAuth) ?: run {
                    Log.e(TAG, "extractKey failed")
                    return null
                }
            val keyBytes = hexDecode(hexKey)

            val moov =
                findBox(fileData, "moov", 0, fileData.size) ?: run {
                    Log.e(TAG, "moov box not found")
                    return null
                }

            // 在 moov 内查找 stbl（可能嵌套在 trak → mdia → minf → stbl）
            var stbl = findBox(fileData, "stbl", moov.offset, moov.offset + moov.size)
            if (stbl == null) {
                val trak = findBox(fileData, "trak", moov.offset + 8, moov.offset + moov.size)
                if (trak != null) {
                    val mdia = findBox(fileData, "mdia", trak.offset + 8, trak.offset + trak.size)
                    if (mdia != null) {
                        val minf = findBox(fileData, "minf", mdia.offset + 8, mdia.offset + mdia.size)
                        if (minf != null) {
                            stbl = findBox(fileData, "stbl", minf.offset + 8, minf.offset + minf.size)
                        }
                    }
                }
            }
            if (stbl == null) {
                Log.e(TAG, "stbl box not found")
                return null
            }

            val stsz =
                findBox(fileData, "stsz", stbl.offset + 8, stbl.offset + stbl.size) ?: run {
                    Log.e(TAG, "stsz box not found")
                    return null
                }
            val sampleSizes = parseStsz(stsz.data)

            // senc 可能在 moov 下或 stbl 下
            var senc = findBox(fileData, "senc", moov.offset + 8, moov.offset + moov.size)
            if (senc == null) {
                senc = findBox(fileData, "senc", stbl.offset + 8, stbl.offset + stbl.size)
            }
            if (senc == null) {
                Log.e(TAG, "senc box not found")
                return null
            }
            val ivSize = defaultPerSampleIVSize(fileData, stbl.offset, stbl.offset + stbl.size)
            val sencSamples = parseSenc(senc.data, ivSize)

            val mdat =
                findBox(fileData, "mdat", 0, fileData.size) ?: run {
                    Log.e(TAG, "mdat box not found")
                    return null
                }

            // ★ 解密诊断日志
            Log.d(TAG, "decryptAudio: sampleSizes=${sampleSizes.size} sencSamples=${sencSamples.size} ivSize=$ivSize mdatSize=${mdat.size}")
            if (sencSamples.isNotEmpty()) {
                val withSubs = sencSamples.count { it.subsamples.isNotEmpty() }
                Log.d(TAG, "decryptAudio: senc with subsamples=$withSubs/${sencSamples.size}")
            }

            val keySpec = SecretKeySpec(keyBytes, "AES")
            val decryptedData = fileData.copyOf()
            var readPtr = mdat.offset + 8
            val decryptedMdat = ByteArrayOutputStream(mdat.size - 8)

            for (i in sampleSizes.indices) {
                val size = sampleSizes[i].toInt()
                // ★ 严格校验: 文件必须完整,任何不匹配都说明下载不完整
                //   参考 go-music-lib/crypto.go: len(decryptedMdat) == int(mdat.size)-8
                if (size < 0 || readPtr + size > decryptedData.size) {
                    Log.e(TAG, "decryptAudio: file truncated at sample $i (need $size bytes, have ${decryptedData.size - readPtr})")
                    return null
                }
                val chunk = decryptedData.copyOfRange(readPtr, readPtr + size)
                if (i < sencSamples.size) {
                    val dst = decryptSencSample(keySpec, chunk, sencSamples[i])
                    decryptedMdat.write(dst)
                } else {
                    decryptedMdat.write(chunk)
                }
                readPtr += size
            }

            val decryptedMdatBytes = decryptedMdat.toByteArray()
            // ★ 严格校验: 解密后大小必须完全匹配 mdat.size-8
            //   不匹配说明文件下载不完整,必须返回 null 让上层重试下载
            if (decryptedMdatBytes.size != mdat.size - 8) {
                Log.e(TAG, "decrypted size mismatch: ${decryptedMdatBytes.size} != ${mdat.size - 8} (file download incomplete)")
                return null
            }
            System.arraycopy(decryptedMdatBytes, 0, decryptedData, mdat.offset + 8, decryptedMdatBytes.size)

            // 修复 stsd: enca → 原始格式 (mp4a 等)
            val stsd = findBox(fileData, "stsd", stbl.offset + 8, stbl.offset + stbl.size)
            if (stsd != null) {
                val stsdOffset = stsd.offset
                val stsdData = decryptedData.copyOfRange(stsdOffset, stsdOffset + stsd.size)
                val encaIdx = indexOf(stsdData, "enca".toByteArray())
                if (encaIdx != -1) {
                    val originalFormat = encryptedSampleOriginalFormat(stsdData)
                    System.arraycopy(originalFormat, 0, stsdData, encaIdx, 4)
                    System.arraycopy(stsdData, 0, decryptedData, stsdOffset, stsdData.size)
                }
            }

            Log.d(TAG, "decryptAudio success: ${fileData.size} → ${decryptedData.size} bytes")
            decryptedData
        } catch (e: Exception) {
            Log.e(TAG, "decryptAudio error: ${e.message}", e)
            null
        }
    }

    /**
     * 从 playAuth 提取 AES hex 密钥
     */
    private fun extractKey(playAuth: String): String? {
        return try {
            val bytesData = Base64.decode(playAuth, Base64.DEFAULT)
            if (bytesData.size < 3) return null

            val paddingLen =
                (
                    (bytesData[0].toInt() and 0xFF) xor
                        (bytesData[1].toInt() and 0xFF) xor
                        (bytesData[2].toInt() and 0xFF)
                ) - 48
            if (paddingLen < 0 || bytesData.size < paddingLen + 2) return null

            val innerInput = bytesData.copyOfRange(1, bytesData.size - paddingLen)
            val tmpBuff = decryptSpadeInner(innerInput)
            if (tmpBuff.isEmpty()) return null

            val skipBytes = decodeBase36(tmpBuff[0])
            val endIndex = 1 + (bytesData.size - paddingLen - 2) - skipBytes
            if (endIndex > tmpBuff.size || endIndex < 1) return null

            String(tmpBuff, 1, endIndex - 1, Charsets.US_ASCII)
        } catch (e: Exception) {
            Log.e(TAG, "extractKey error: ${e.message}")
            null
        }
    }

    /**
     * Spade 内部解密
     */
    private fun decryptSpadeInner(keyBytes: ByteArray): ByteArray {
        val result = ByteArray(keyBytes.size)
        // buff = [0xFA, 0x55] + keyBytes
        val buff = ByteArray(2 + keyBytes.size)
        buff[0] = 0xFA.toByte()
        buff[1] = 0x55
        System.arraycopy(keyBytes, 0, buff, 2, keyBytes.size)

        for (i in result.indices) {
            var v = ((keyBytes[i].toInt() and 0xFF) xor (buff[i].toInt() and 0xFF)) - bitcount(i) - 21
            while (v < 0) v += 255
            result[i] = (v and 0xFF).toByte()
        }
        return result
    }

    /**
     * 位计数 (popcount)
     */
    private fun bitcount(n: Int): Int {
        var u = n.toLong() and 0xFFFFFFFFL
        u = u - ((u shr 1) and 0x55555555L)
        u = (u and 0x33333333L) + ((u shr 2) and 0x33333333L)
        return (((u + (u shr 4)) and 0x0F0F0F0FL) * 0x01010101L shr 24).toInt()
    }

    /**
     * Base36 解码单个字符
     */
    private fun decodeBase36(c: Byte): Int =
        when (c.toInt() and 0xFF) {
            in '0'.code..'9'.code -> (c.toInt() and 0xFF) - '0'.code
            in 'a'.code..'z'.code -> (c.toInt() and 0xFF) - 'a'.code + 10
            in 'A'.code..'Z'.code -> (c.toInt() and 0xFF) - 'A'.code + 10
            else -> 0xFF
        }

    /**
     * 查找 MP4 box (非递归)
     */
    private fun findBox(
        data: ByteArray,
        boxType: String,
        start: Int,
        end: Int,
    ): Mp4Box? {
        val endAdj = if (end > data.size) data.size else end
        var pos = start
        val target = boxType.toByteArray()
        while (pos + 8 <= endAdj) {
            val size = readUInt32BE(data, pos).toInt()
            if (size < 8) break
            if (pos + 4 + 4 <= endAdj &&
                data[pos + 4] == target[0] &&
                data[pos + 5] == target[1] &&
                data[pos + 6] == target[2] &&
                data[pos + 7] == target[3]
            ) {
                val dataEnd = minOf(pos + size, data.size)
                return Mp4Box(pos, size, data.copyOfRange(pos + 8, dataEnd))
            }
            pos += size
        }
        return null
    }

    /**
     * 查找 MP4 box (递归深度搜索，支持 64 位 size)
     */
    private fun findBoxDeep(
        data: ByteArray,
        boxType: String,
        start: Int,
        end: Int,
    ): Mp4Box? {
        val endAdj = if (end > data.size) data.size else end
        var pos = start
        val target = boxType.toByteArray()
        while (pos + 8 <= endAdj) {
            var size = readUInt32BE(data, pos).toInt()
            var headerSize = 8
            if (size == 1) {
                if (pos + 16 > endAdj) break
                val size64 = readUInt64BE(data, pos + 8)
                if (size64 > (endAdj - pos).toLong()) break
                size = size64.toInt()
                headerSize = 16
            }
            if (size < headerSize || pos + size > endAdj) break
            val currentType = String(data, pos + 4, 4, Charsets.US_ASCII)
            if (data[pos + 4] == target[0] &&
                data[pos + 5] == target[1] &&
                data[pos + 6] == target[2] &&
                data[pos + 7] == target[3]
            ) {
                val dataEnd = minOf(pos + size, data.size)
                return Mp4Box(pos, size, data.copyOfRange(pos + headerSize, dataEnd))
            }
            val childStart = boxChildStart(currentType, pos, headerSize)
            if (childStart > 0 && childStart < pos + size) {
                findBoxDeep(data, boxType, childStart, pos + size)?.let { return it }
            }
            pos += size
        }
        return null
    }

    /**
     * 获取 box 子元素的起始偏移
     */
    private fun boxChildStart(
        boxType: String,
        offset: Int,
        headerSize: Int,
    ): Int =
        when (boxType) {
            "moov", "trak", "mdia", "minf", "stbl", "sinf", "schi" -> offset + headerSize
            "stsd" -> offset + headerSize + 8
            "enca", "mp4a", "alac", "fLaC" -> offset + headerSize + 28
            else -> 0
        }

    /**
     * 解析 stsz box (样本大小表)
     */
    private fun parseStsz(data: ByteArray): LongArray {
        if (data.size < 12) return LongArray(0)
        val sampleSizeFixed = readUInt32BE(data, 4)
        val sampleCount = readUInt32BE(data, 8).toInt()
        if (sampleCount <= 0) return LongArray(0)
        val sizes = LongArray(sampleCount)
        if (sampleSizeFixed != 0L) {
            for (i in 0 until sampleCount) sizes[i] = sampleSizeFixed
        } else {
            for (i in 0 until sampleCount) {
                val offset = 12 + i * 4
                if (offset + 4 <= data.size) {
                    sizes[i] = readUInt32BE(data, offset)
                }
            }
        }
        return sizes
    }

    /**
     * 解析 senc box (样本加密信息)
     */
    private fun parseSenc(
        data: ByteArray,
        ivSizeParam: Int,
    ): List<SencSample> {
        if (data.size < 8) return emptyList()
        var ivSize = ivSizeParam
        if (ivSize != 8 && ivSize != 16) ivSize = 8

        val flags = readUInt32BE(data, 0) and 0x00FFFFFFL
        val sampleCount = readUInt32BE(data, 4).toInt()
        if (sampleCount <= 0) return emptyList()

        val samples = mutableListOf<SencSample>()
        var ptr = 8
        val hasSubsamples = (flags and 0x02L) != 0L

        for (i in 0 until sampleCount) {
            if (ptr + ivSize > data.size) break
            val iv = data.copyOfRange(ptr, ptr + ivSize)
            ptr += ivSize

            val subsamples = mutableListOf<SencSubsample>()
            if (hasSubsamples) {
                if (ptr + 2 > data.size) break
                val subCount = readUInt16BE(data, ptr)
                ptr += 2
                if (ptr + subCount * 6 > data.size) break
                for (j in 0 until subCount) {
                    val clear = readUInt16BE(data, ptr)
                    val encrypted = readUInt32BE(data, ptr + 2)
                    subsamples.add(SencSubsample(clear, encrypted))
                    ptr += 6
                }
            }
            samples.add(SencSample(iv, subsamples))
        }
        return samples
    }

    /**
     * 默认 IV 大小 (从 tenc box 获取)
     */
    private fun defaultPerSampleIVSize(
        data: ByteArray,
        start: Int,
        end: Int,
    ): Int {
        val tenc = findBoxDeep(data, "tenc", start, end) ?: return 8
        if (tenc.data.size < 8) return 8
        val ivSize = tenc.data[7].toInt() and 0xFF
        return if (ivSize == 8 || ivSize == 16) ivSize else 8
    }

    /**
     * 解密单个 senc 样本 (AES-CTR)
     * ★ CTR 模式 counter 必须连续递增 — 对齐 Go 的 stream.XORKeyStream 流式行为
     *   Java 的 Cipher.doFinal 每次调用都重置到初始 IV，导致子样本 counter 不连续
     *   修复: 为每个子样本创建新 Cipher 实例，手动递增 IV counter
     */
    private fun decryptSencSample(
        keySpec: SecretKeySpec,
        chunk: ByteArray,
        sample: SencSample,
    ): ByteArray {
        var iv = sample.iv
        if (iv.size < 16) {
            val padded = ByteArray(16)
            System.arraycopy(iv, 0, padded, 0, iv.size)
            iv = padded
        }

        if (sample.subsamples.isEmpty()) {
            // 整个 chunk 加密 — 一次性 doFinal 正确
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            return cipher.doFinal(chunk)
        }

        // 子样本: clear + encrypted 交替
        // ★ 手动维护 16 字节 counter，每个加密子样本后递增
        val dst = ByteArray(chunk.size)
        var pos = 0
        val counter = iv.copyOf() // 16 字节 counter，初始值 = IV

        for (sub in sample.subsamples) {
            var clearBytes = sub.clear
            if (clearBytes > chunk.size - pos) clearBytes = chunk.size - pos
            if (clearBytes > 0) {
                System.arraycopy(chunk, pos, dst, pos, clearBytes)
                pos += clearBytes
            }
            if (pos >= chunk.size) break

            var encryptedBytes = sub.encrypted.toInt()
            if (encryptedBytes > chunk.size - pos) encryptedBytes = chunk.size - pos
            if (encryptedBytes > 0) {
                // ★ 每个子样本用当前 counter 创建新 Cipher，doFinal 后手动递增 counter
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(counter))
                val dec = cipher.doFinal(chunk, pos, encryptedBytes)
                System.arraycopy(dec, 0, dst, pos, dec.size)
                pos += encryptedBytes
                // 递增 counter: 加密了 N 字节 = ceil(N/16) 个 block
                val blocks = (encryptedBytes + 15) / 16
                incrementCounter(counter, blocks)
            }
            if (pos >= chunk.size) break
        }
        if (pos < chunk.size) {
            System.arraycopy(chunk, pos, dst, pos, chunk.size - pos)
        }
        return dst
    }

    /**
     * 16 字节 counter 递增 (大端)
     * @param counter 16 字节 counter (会被修改)
     * @param blocks 递增的 block 数量
     */
    private fun incrementCounter(
        counter: ByteArray,
        blocks: Int,
    ) {
        var carry = blocks.toLong()
        // 从最低位开始递增 (大端，最后 8 字节)
        for (i in 15 downTo 8) {
            val sum = (counter[i].toLong() and 0xFF) + (carry and 0xFF)
            counter[i] = (sum and 0xFF).toByte()
            carry = (carry shr 8) + (sum shr 8)
            if (carry == 0L) break
        }
        // 处理高 8 字节
        if (carry != 0L) {
            for (i in 7 downTo 0) {
                val sum = (counter[i].toLong() and 0xFF) + (carry and 0xFF)
                counter[i] = (sum and 0xFF).toByte()
                carry = (carry shr 8) + (sum shr 8)
                if (carry == 0L) break
            }
        }
    }

    /**
     * 从 stsd 中获取加密样本的原始格式
     */
    private fun encryptedSampleOriginalFormat(stsdData: ByteArray): ByteArray {
        val frma = "frma".toByteArray()
        val idx = indexOf(stsdData, frma)
        if (idx < 4 || idx + 8 > stsdData.size) return "mp4a".toByteArray()
        val size = readUInt32BE(stsdData, idx - 4).toInt()
        if (size < 12 || idx - 4 + size > stsdData.size) return "mp4a".toByteArray()
        return stsdData.copyOfRange(idx + 4, idx + 8)
    }

    // ═══════════════════════════════════════════════
    //  二进制读写辅助
    // ═══════════════════════════════════════════════

    private fun readUInt32BE(
        data: ByteArray,
        offset: Int,
    ): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    private fun readUInt64BE(
        data: ByteArray,
        offset: Int,
    ): Long =
        ((data[offset].toLong() and 0xFF) shl 56) or
            ((data[offset + 1].toLong() and 0xFF) shl 48) or
            ((data[offset + 2].toLong() and 0xFF) shl 40) or
            ((data[offset + 3].toLong() and 0xFF) shl 32) or
            ((data[offset + 4].toLong() and 0xFF) shl 24) or
            ((data[offset + 5].toLong() and 0xFF) shl 16) or
            ((data[offset + 6].toLong() and 0xFF) shl 8) or
            (data[offset + 7].toLong() and 0xFF)

    private fun readUInt16BE(
        data: ByteArray,
        offset: Int,
    ): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)

    private fun indexOf(
        data: ByteArray,
        target: ByteArray,
    ): Int {
        if (target.isEmpty() || data.size < target.size) return -1
        for (i in 0..data.size - target.size) {
            var match = true
            for (j in target.indices) {
                if (data[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun hexDecode(hex: String): ByteArray {
        val len = hex.length
        val result = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            result[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return result
    }
}
