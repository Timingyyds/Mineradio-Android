package com.mineradio.app.player.impl.dsp

import android.media.AudioFormat
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 环绕音效处理器
 *
 * 支持两种模式：
 * - MODE_3D_SURROUND (1): 3D 环绕 —— 左右声道交替播放（周期性开关）
 *   周期约 4 秒，左/右声道在 2 秒内各自"渐强→渐弱"，营造左右分离的立体感
 *
 * - MODE_360_SURROUND (3): 360 度环绕 —— 声像从右声道顺时针向左声道持续旋转
 *   使用正弦/余弦相位差实现顺时针声像旋转，旋转周期约 8 秒（从右→前→左→后→右）
 *
 * 采样级实现，无延迟缓冲，仅对立体声（2 声道）PCM 16-bit 生效。
 */
@UnstableApi
class SurroundAudioProcessor : AudioProcessor {

    companion object {
        private const val TAG = "SurroundAudioProcessor"

        /** 模式：关闭 */
        const val MODE_OFF = 0

        /** 模式：3D 环绕（左右声道交替分离） */
        const val MODE_3D_SURROUND = 1

        /** 模式：360 度环绕（顺时针旋转） */
        const val MODE_360_SURROUND = 3

        // 3D 环绕：声道切换周期 4 秒（每个声道独立 2 秒渐强→渐弱）
        private const val PERIOD_3D_SECONDS = 4.0

        // 360 度环绕：旋转周期 8 秒（顺时针一圈）
        private const val PERIOD_360_SECONDS = 8.0
    }

    @Volatile
    private var mode = MODE_OFF

    private var sampleRate = 44100
    private var channelCount = 2

    private var inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // 复用输出缓冲区
    private var cachedOutputBuffer: ByteBuffer = ByteBuffer.allocateDirect(0)

    // 累计采样计数（按帧计），用于相位计算。Long 范围足够长播放时长不溢出。
    private var frameCounter: Long = 0L

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != AudioFormat.ENCODING_PCM_16BIT) {
            this.inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
            return AudioProcessor.AudioFormat.NOT_SET
        }

        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        this.sampleRate = inputAudioFormat.sampleRate
        this.channelCount = inputAudioFormat.channelCount
        this.frameCounter = 0L
        return inputAudioFormat
    }

    // 已配置即纳入管线，模式判断在 queueInput 内部处理
    override fun isActive(): Boolean = inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (mode == MODE_OFF || channelCount != 2) {
            outputBuffer = inputBuffer
            return
        }

        val size = inputBuffer.remaining()
        if (size == 0) {
            return
        }

        // 复用输出缓冲区
        if (cachedOutputBuffer.capacity() < size) {
            cachedOutputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
        cachedOutputBuffer.clear().limit(size)
        val output = cachedOutputBuffer

        val totalSamples = size / 2 // 16-bit = 2 bytes/sample

        when (mode) {
            MODE_3D_SURROUND -> process3DSurround(inputBuffer, output, totalSamples)
            MODE_360_SURROUND -> process360Surround(inputBuffer, output, totalSamples)
            else -> {
                // 直接透传
                inputBuffer.position(inputBuffer.position() + size)
                output.put(inputBuffer)
                output.rewind()
            }
        }

        output.flip()
        outputBuffer = output
    }

    /**
     * 3D 环绕：左右声道交替播放
     * 周期 T = PERIOD_3D_SECONDS
     * 左声道增益 = 0.5 + 0.5 * cos(2π * t / T)  （t=0 时为 1，t=T/2 时为 0）
     * 右声道增益 = 0.5 + 0.5 * cos(2π * (t - T/2) / T) = 0.5 - 0.5 * cos(2π * t / T)
     * 即左右声道互为反相，实现"左右分离"
     */
    private fun process3DSurround(inputBuffer: ByteBuffer, output: ByteBuffer, totalSamples: Int) {
        val frames = totalSamples / channelCount
        val omega = 2.0 * PI / (PERIOD_3D_SECONDS * sampleRate)
        var frame = frameCounter

        for (i in 0 until frames) {
            val leftIn = inputBuffer.short
            val rightIn = inputBuffer.short

            val phase = cos(omega * frame)
            val leftGain = (0.5 + 0.5 * phase).toFloat()
            val rightGain = (0.5 - 0.5 * phase).toFloat()

            val leftOut = (leftIn * leftGain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            val rightOut = (rightIn * rightGain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            output.putShort(leftOut)
            output.putShort(rightOut)
            frame++
        }
        frameCounter = frame
    }

    /**
     * 360 度环绕：声像从右声道顺时针向左声道旋转
     * 使用正弦/余弦实现顺时针声像旋转（右→前→左→后→右）
     *
     * ★ 增益补偿：基础增益 0.7 + 振幅 0.3
     *   - 最小增益 0.7（声像在背面时不会消失，避免"声音离得很远"的感觉）
     *   - 最大增益 1.0（声像在正前方时满音量）
     *   - 平均增益约 0.89，接近原音量
     *   旋转感保留，但音量始终保持在可听清的范围内
     */
    private fun process360Surround(inputBuffer: ByteBuffer, output: ByteBuffer, totalSamples: Int) {
        val frames = totalSamples / channelCount
        val omega = 2.0 * PI / (PERIOD_360_SECONDS * sampleRate)
        var frame = frameCounter

        for (i in 0 until frames) {
            val leftIn = inputBuffer.short
            val rightIn = inputBuffer.short

            val angle = omega * frame
            // 顺时针：从右(cos=1) → 前(sin=1) → 左(cos=-1) → 后(sin=-1) → 右
            // ★ 基础增益 0.7 + 振幅 0.3：最小 0.7，最大 1.0，避免声音太小
            val rightGain = (0.7f + 0.3f * cos(angle)).toFloat()
            val leftGain = (0.7f + 0.3f * sin(angle)).toFloat()

            val leftOut = (leftIn * leftGain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            val rightOut = (rightIn * rightGain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            output.putShort(leftOut)
            output.putShort(rightOut)
            frame++
        }
        frameCounter = frame
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        frameCounter = 0L
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        // ★ 不重置 mode：3D环绕/360度环绕是用户设置的音效偏好，
        //   ExoPlayer 切歌时会调用 reset()，若重置 mode 会导致切歌后音效失效
    }

    /**
     * 设置环绕模式
     * @param mode 0=关闭, 1=3D环绕, 3=360度环绕
     *
     * ★ 即使新值与当前值相同也强制更新，修复重启后 flush() 把 mode 重置为 OFF 的问题
     */
    fun setMode(mode: Int) {
        val clamped = when (mode) {
            MODE_3D_SURROUND, MODE_360_SURROUND -> mode
            else -> MODE_OFF
        }
        // ★ 强制更新（不判断 this.mode != clamped），确保重启后恢复
        this.mode = clamped
        // 切换模式时重置相位，避免相位跳变导致咔嗒声
        frameCounter = 0L
        Timber.tag(TAG).d("Surround mode set: $clamped")
    }

    fun getMode(): Int = mode
}
