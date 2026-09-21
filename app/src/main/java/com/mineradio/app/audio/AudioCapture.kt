package com.mineradio.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.*

/**
 * 音频采集 + FFT 频谱分析。
 *
 * 完全对齐原版 sonic-topography AudioEngine.ts 的算法:
 *  - analyser.getByteFrequencyData → /255 → lerp 平滑 (dt=0.15)
 *  - 无 maxMag 归一化,无 normalizeByPeak (这两个会压平起伏)
 *  - analyser.smoothingTimeConstant = 0.8 (Web Audio 内置平滑)
 *  - 线性尺度 0~Nyquist 重采样到 64 段
 *
 * 两种采集源:
 *  - "system":android.media.audiofx.Visualizer 抓系统混音输出(戴耳机也能跟随)
 *  - "mic":AudioRecord + 自实现 FFT 抓麦克风(外放拾音)
 *
 * 系统混音失败自动 fallback 到麦克风。
 */
class AudioCapture(
    private val context: Context,
) {
    fun interface SpectrumListener {
        fun onSpectrum(bins: FloatArray)
    }

    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = 44100 // 麦克风采样率
        private const val FFT_SIZE = 1024 // FFT 输入样本数 → 512 bin
        private const val OUTPUT_BINS = 64 // 输出 64 段频谱(0~1)

        // ★ 对齐原版 AudioEngine.ts (smoothingTimeConstant=0.3 + lerp dt=0.3)
        //   不用 maxMag 归一化和 normalizeByPeak,这两步会压平起伏
        //   平滑系数小 → 衰减快,节点上升后快速落下,起伏干脆
        private const val ANALYSER_SMOOTHING = 0.3f // Web Audio 风格平滑(模拟 smoothingTimeConstant=0.3)
        private const val LERP_DT = 0.3f // lerp 平滑系数(加大,上升下降都更快)
        private const val MIC_GAIN = 2.5f // ★ 麦克风增益(提高,放大设备播放声音的响应)
        private const val MIC_ENERGY_THRESHOLD = 0.008f // ★ 麦克风能量门控(降低,让小音量也能响应)
        private const val BIN_GAIN = 1.8f // Visualizer 幅度增益(补偿平方曲线压缩)

        // ★ Visualizer 全0帧阈值：连续 N 帧全0触发自动 fallback（小米/红米 MIUI 兼容）
        private const val SILENT_THRESHOLD = 30 // ~0.3秒（Visualizer ~100Hz 回调）

        // ★ 麦克风环境噪声抑制参数
        //   动态噪声基线：持续追踪每帧各频段的最低能量，视为环境底噪
        //   频谱相减：当前频谱 - 噪声基线 = 音乐频谱（设备播放的声音）
        private const val NOISE_FLOOR_LEARN_RATE = 0.002f // 噪声基线学习率(缓慢上升)
        private const val NOISE_FLOOR_DECAY_RATE = 0.05f // 噪声基线衰减率(快速下降,让音乐通过)
        private const val NOISE_SUBTRACTION_MARGIN = 1.5f // 频谱相减余量(多减一点,更干净)
    }

    var listener: SpectrumListener? = null
    var audioSource: String = "system" // "system" / "mic"
        private set

    /** ★ Visualizer 持续返回全0时的回调（小米/红米 MIUI 兼容，触发自动切麦克风） */
    var onVisualizerSilent: (() -> Unit)? = null

    private var micThread: Thread? = null
    private var micRecord: AudioRecord? = null

    @Volatile private var micRunning = false
    private var visualizer: Visualizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ★ 平滑后的频谱 (对齐 AudioEngine.ts smoothedData, 无 peak hold)
    //   512 bin 每个都做 lerp 平滑,模拟 analyser.smoothingTimeConstant=0.8
    private var smoothedBins = FloatArray(FFT_SIZE / 2)

    // ★ 麦克风噪声基线（每个频段的底噪估计，用于频谱相减）
    //   持续追踪各频段最低能量，音乐信号远高于基线，环境噪音接近基线
    private var noiseFloor = FloatArray(FFT_SIZE / 2)
    private var debugCounter = 0

    // ★ Visualizer 全0帧计数器：连续 SILENT_THRESHOLD 帧全0触发 onVisualizerSilent
    //   小米/红米 MIUI 对 STREAM_MUSIC 做 Visualizer 隐私限制，数据全0但启动不报错
    private var silentFrameCount = 0

    @Volatile private var silentFallbackTriggered = false

    /** 检查 RECORD_AUDIO 权限是否已授予 */
    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 启动采集。
     * @param mode "system"=系统混音(Visualizer), "mic"=麦克风(AudioRecord)
     * system 失败时自动 fallback 到 mic。
     */
    fun start(mode: String = "system") {
        stop()
        this.audioSource = mode
        // 重置平滑数组
        smoothedBins = FloatArray(FFT_SIZE / 2)
        // 重置全0计数器
        silentFrameCount = 0
        silentFallbackTriggered = false
        if (mode == "system") {
            if (!startVisualizer()) {
                Log.w(TAG, "Visualizer 启动失败,自动回退到麦克风 (fallbackToMic)")
                this.audioSource = "mic"
                startMic()
            }
        } else {
            startMic()
        }
        Log.i(TAG, "AudioCapture started, source=$audioSource (requested=$mode)")
    }

    /** 停止采集,释放资源 */
    fun stop() {
        stopMic()
        stopVisualizer()
        smoothedBins = FloatArray(FFT_SIZE / 2)
    }

    // ══════════════════════════════════════════════
    //  Visualizer (系统混音)
    // ══════════════════════════════════════════════
    private fun startVisualizer(): Boolean =
        try {
            val range = Visualizer.getCaptureSizeRange()
            val captureSize =
                if (range != null && range.size >= 2) {
                    minOf(range[1], 1024).coerceAtLeast(range[0])
                } else {
                    256
                }
            visualizer = Visualizer(0) // 0 = 全局音频输出
            visualizer?.captureSize = captureSize
            visualizer?.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?,
                        data: ByteArray?,
                        samplingRate: Int,
                    ) {}

                    override fun onFftDataCapture(
                        v: Visualizer?,
                        data: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (data != null) processVisualizerFft(data, samplingRate)
                    }
                },
                Visualizer.getMaxCaptureRate(), // 最大回调速率 (~100Hz, 最低延迟)
                false, // 不抓 waveform
                true, // 抓 FFT
            )
            visualizer?.enabled = true
            Log.i(TAG, "Visualizer started, captureSize=$captureSize")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Visualizer start failed", e)
            false
        }

    private fun stopVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {
        }
        visualizer = null
    }

    private fun processVisualizerFft(
        data: ByteArray,
        samplingRate: Int,
    ) {
        // Visualizer.getFft() 返回 interleaved (real, imaginary) 对
        val captureSize = data.size
        val numBins = captureSize / 2
        val sampleRateHz = samplingRate / 1000f
        val binHz = sampleRateHz / captureSize

        // 1. 计算 magnitude → 对数缩放 → 平方曲线 → 线性 0~1
        //   ★ 关键:用平方曲线压低低值,让静音背景接近 0,鼓点保持高值
        //   解决"八点到十点之间波动"问题:底部 0.10,顶部 0.95,起伏对比明显
        //   鼓点(m=80-157)→ 0.72-0.95,中等(m=30)→ 0.44,背景(m=5-10)→ 0.10-0.20,静音→0.02
        val mag = FloatArray(numBins)
        var rawMax = 0f
        var rawMin = 999f
        for (i in 0 until numBins) {
            val re = data[2 * i].toFloat()
            val im = data[2 * i + 1].toFloat()
            val m = sqrt(re * re + im * im)
            if (m > rawMax) rawMax = m
            if (m < rawMin) rawMin = m
            // 对数缩放 + 平方曲线:压低底部,突出起伏
            val dbVal = 20f * log10(m + 1f) // 0~45 dB
            val linear = (dbVal / 45f).coerceIn(0f, 1f) // 0~1
            mag[i] = (linear * linear * BIN_GAIN).coerceIn(0f, 1f) // 平方+增益
        }

        // ★ 全0检测：小米/红米 MIUI 对 STREAM_MUSIC 做 Visualizer 隐私限制，
        //   Visualizer 启动成功但数据全0。连续 SILENT_THRESHOLD 帧全0触发自动 fallback
        if (rawMax <= 0f) {
            silentFrameCount++
            if (silentFrameCount >= SILENT_THRESHOLD && !silentFallbackTriggered) {
                silentFallbackTriggered = true
                Log.w(TAG, "Visualizer data all-zero for $silentFrameCount frames, triggering fallback (MIUI compat)")
                mainHandler.post { onVisualizerSilent?.invoke() }
            }
        } else {
            if (silentFrameCount > 0) silentFrameCount = 0
        }

        // 2. ★ 非对称 lerp 平滑:上升 0.8(快),下降 0.95(几乎瞬间)
        //   解决"上升下降都不快"问题:节点几乎瞬间响应,起伏干脆
        if (numBins <= smoothedBins.size) {
            for (i in 0 until numBins) {
                val dt = if (mag[i] > smoothedBins[i]) 0.8f else 0.95f
                smoothedBins[i] += (mag[i] - smoothedBins[i]) * dt
            }
        }

        // 3. 重采样到 64 段 (线性尺度 0~Nyquist, 含人声频段压缩)
        val out = resampleToBins(smoothedBins, numBins, binHz)

        // 4. 直接在采集线程回调,降低延迟
        listener?.onSpectrum(out)

        // ★ 调试日志:每秒打印一次关键数据
        debugCounter++
        if (debugCounter % 40 == 0) {
            val max0 = if (out.isNotEmpty()) out[0] else 0f
            val max1 = if (out.size > 1) out[1] else 0f
            val maxAll = out.maxOrNull() ?: 0f
            val sumAll = out.sum()
            android.util.Log.w(
                "AudioCapture",
                "VIS raw[min=${"%.2f".format(
                    rawMin,
                )},max=${"%.2f".format(
                    rawMax,
                )}] out[0]=${"%.3f".format(
                    max0,
                )} out[1]=${"%.3f".format(
                    max1,
                )} max=${"%.3f".format(maxAll)} sum=${"%.3f".format(sumAll)} sr=$sampleRateHz binHz=$binHz numBins=$numBins",
            )
        }
    }

    // ══════════════════════════════════════════════
    //  AudioRecord (麦克风)
    // ══════════════════════════════════════════════
    private fun startMic() {
        if (!hasMicPermission()) {
            Log.w(TAG, "无 RECORD_AUDIO 权限,无法启动麦克风采集")
            return
        }
        micRunning = true
        micThread =
            Thread {
                val minBuf =
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                if (minBuf <= 0) {
                    Log.e(TAG, "AudioRecord.getMinBufferSize 失败: $minBuf")
                    micRunning = false
                    return@Thread
                }
                val bufSize = max(minBuf, FFT_SIZE * 2)
                try {
                    micRecord =
                        AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufSize,
                        )
                    if (micRecord?.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioRecord 初始化失败")
                        micRunning = false
                        return@Thread
                    }
                    Log.i(TAG, "AudioRecord started (native capture)")
                    micRecord?.startRecording()
                    val samples = ShortArray(FFT_SIZE)
                    while (micRunning) {
                        // 阻塞 read:读到 FFT_SIZE 个样本立即处理,无强制 sleep,最低延迟
                        //   44100Hz × 1024 样本 ≈ 23ms 自然间隔(由采样率决定)
                        val n = micRecord?.read(samples, 0, FFT_SIZE) ?: -1
                        if (n > 0) processMicFft(samples, n)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Mic capture failed", e)
                } finally {
                    try {
                        micRecord?.stop()
                    } catch (e: Exception) {
                    }
                    try {
                        micRecord?.release()
                    } catch (e: Exception) {
                    }
                    micRecord = null
                    Log.i(TAG, "AudioRecord stopped")
                }
            }.apply {
                isDaemon = true
                name = "AudioCapture-Mic"
                start()
            }
    }

    private fun stopMic() {
        micRunning = false
        try {
            micThread?.join(500)
        } catch (e: Exception) {
        }
        micThread = null
    }

    private fun processMicFft(
        samples: ShortArray,
        n: Int,
    ) {
        // 0. 能量门控:RMS 低于阈值视为静音
        var sumSquares = 0f
        val frameSize = min(n, FFT_SIZE)
        for (i in 0 until frameSize) {
            val s = samples[i].toFloat() / Short.MAX_VALUE
            sumSquares += s * s
        }
        val rms = sqrt(sumSquares / frameSize)
        if (rms < MIC_ENERGY_THRESHOLD) {
            val silentBins = FloatArray(OUTPUT_BINS)
            listener?.onSpectrum(silentBins)
            return
        }

        // 1. 转 float[-1,1] 并加 Hann 窗(减少频谱泄漏)
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        for (i in 0 until min(n, FFT_SIZE)) {
            val s = samples[i].toFloat() / Short.MAX_VALUE
            val w = 0.5f * (1f - cos(2f * PI.toFloat() * i / (FFT_SIZE - 1)))
            re[i] = s * w
        }
        // 2. 原地 radix-2 Cooley-Tukey FFT
        fft(re, im)
        // 3. 取前 N/2 个 bin 的 magnitude,归一化到 0~1
        //   ★ 对齐 Web Audio getByteFrequencyData:用对数 dB 转换 + minDecibels=-75, maxDecibels=-25
        //   简化版:直接 magnitude 归一化(用经验值缩放)
        val numBins = FFT_SIZE / 2
        val binHz = SAMPLE_RATE.toFloat() / FFT_SIZE
        val mag = FloatArray(numBins)
        // 计算 magnitude,然后做 dB 风格归一化(模拟 Web Audio minDecibels=-75, maxDecibels=-25)
        val minDb = -75f
        val maxDb = -25f
        val dbRange = maxDb - minDb
        for (i in 0 until numBins) {
            val m = sqrt(re[i] * re[i] + im[i] * im[i])
            // magnitude 转 dB (相对于 FFT_SIZE 归一化)
            val normalizedMag = m / FFT_SIZE
            val db = if (normalizedMag > 0) 20f * log10(normalizedMag) else minDb
            // dB 转线性 0~1 (对齐 Web Audio getByteFrequencyData)
            mag[i] = ((db - minDb) / dbRange).coerceIn(0f, 1f)
        }

        // ★ 3.5 麦克风环境噪声抑制：动态噪声基线 + 频谱相减
        //   原理：环境噪音是持续的低能量信号，音乐是有节奏的高低能量变化
        //   持续追踪各频段最低能量作为噪声基线，从当前频谱中减去基线
        //   效果：环境噪音被抑制，设备播放的音乐信号被突出
        for (i in 0 until numBins) {
            // 噪声基线学习：当前能量低于基线时，基线缓慢下降（学习新噪声水平）
            //               当前能量高于基线时，基线缓慢上升（但很慢，避免把音乐学成噪声）
            if (mag[i] < noiseFloor[i]) {
                // 能量低于基线 → 快速下降学习新底噪
                noiseFloor[i] += (mag[i] - noiseFloor[i]) * NOISE_FLOOR_DECAY_RATE
            } else {
                // 能量高于基线 → 缓慢上升（避免把音乐学成噪声）
                noiseFloor[i] += (mag[i] - noiseFloor[i]) * NOISE_FLOOR_LEARN_RATE
            }
            // 频谱相减：当前频谱 - 噪声基线 * 余量 = 纯音乐频谱
            val subtracted = mag[i] - noiseFloor[i] * NOISE_SUBTRACTION_MARGIN
            mag[i] = if (subtracted > 0) subtracted else 0f
        }

        // 4. ★ lerp 平滑 (对齐 AudioEngine.ts smoothedData, dt=0.15)
        //   无 maxMag 归一化,无 normalizeByPeak
        for (i in 0 until numBins) {
            smoothedBins[i] += (mag[i] - smoothedBins[i]) * LERP_DT
        }

        // 5. 重采样到 64 段 (线性尺度 0~Nyquist)
        val out = resampleToBins(smoothedBins, numBins, binHz)

        // 6. 麦克风增益
        for (i in 0 until OUTPUT_BINS) out[i] = (out[i] * MIC_GAIN).coerceIn(0f, 1f)

        // 7. 直接在采集线程回调,降低延迟
        listener?.onSpectrum(out)
    }

    // ══════════════════════════════════════════════
    //  通用工具:重采样 (线性尺度 0~Nyquist)
    // ══════════════════════════════════════════════
    private fun resampleToBins(
        mag: FloatArray,
        numBins: Int,
        binHz: Float,
    ): FloatArray {
        val out = FloatArray(OUTPUT_BINS)
        // ★ 线性尺度 0~Nyquist:匹配 WE audio listener 标准 API
        //   bundle 内部 setWallpaperAudioData 把 128 值线性上采样到 512,按 bin index 划分频段
        //   线性尺度下 64 段每段 = Nyquist/64 Hz:
        //   - bin 0 = 0~345Hz (subBass+bass,驱动中心大幅起伏)
        //   - bin 1 = 345~690Hz (lowMid)
        //   - bin 2~5 = 690~2070Hz (mid)
        //   - bin 6~11 = 2070~4140Hz (highMid)
        //   - bin 12~17 = 4140~6210Hz (presence)
        //   - bin 18~29 = 6210~10350Hz (brilliance)
        //   - bin 30~63 = 10350~22050Hz (air)
        val nyquist = numBins * binHz
        val binWidth = nyquist / OUTPUT_BINS
        for (i in 0 until OUTPUT_BINS) {
            val fLow = i * binWidth
            val fHigh = (i + 1) * binWidth
            val bLow = (fLow / binHz).toInt().coerceIn(0, numBins - 1)
            val bHigh = (fHigh / binHz).toInt().coerceIn(0, numBins - 1)
            // 取该频段内的最大值(保留瞬态能量,鼓点 punch 不丢失)
            var max = 0f
            for (b in bLow..bHigh) if (mag[b] > max) max = mag[b]
            out[i] = max
        }

        // ★ 人声频段压缩:bin 0 = 0~345Hz (含鼓点+人声基频)
        //   鼓点能量主要在 40-80Hz,人声在 85-255Hz
        //   bin 0 只保留 85Hz 以下的鼓点能量,丢弃人声频段
        //   避免人声误触发 Pulse 节奏点
        val voiceStartBin = (85f / binHz).toInt().coerceIn(0, numBins - 1)
        var kickMax = 0f
        for (b in 0 until voiceStartBin) {
            if (mag[b] > kickMax) kickMax = mag[b]
        }
        out[0] = kickMax

        return out
    }

    // ══════════════════════════════════════════════
    //  原地 radix-2 Cooley-Tukey FFT
    // ══════════════════════════════════════════════
    private fun fft(
        re: FloatArray,
        im: FloatArray,
    ) {
        val n = re.size
        if (n and (n - 1) != 0) return // 仅支持 2 的幂

        // Bit reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
        }

        // Butterfly
        var len = 2
        while (len <= n) {
            val ang = -2f * PI.toFloat() / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val idx = i + k
                    val idx2 = idx + len / 2
                    val tRe = curRe * re[idx2] - curIm * im[idx2]
                    val tIm = curRe * im[idx2] + curIm * re[idx2]
                    re[idx2] = re[idx] - tRe
                    im[idx2] = im[idx] - tIm
                    re[idx] = re[idx] + tRe
                    im[idx] = im[idx] + tIm
                    val newRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = newRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
