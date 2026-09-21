package com.mineradio.app.player.impl.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.mineradio.app.player.api.IFFTProcessor
import java.nio.ByteBuffer

/**
 * Media3 AudioProcessor 包装器
 * 将音频数据传递给 FFT 处理器进行频谱分析
 */
@UnstableApi
class FFTAudioProcessorWrapper(
    private val fftProcessor: IFFTProcessor,
) : AudioProcessor {

    private var inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var isActive = false

    private var outputBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // 预分配音频数据缓冲区，避免每帧分配新 ByteArray（GC 压力来源）
    private var audioDataBuffer = ByteArray(0)

    // ★ 镜像 PCM 回调（由 app 模块注册，避免循环依赖）
    companion object {
        /** (data, size, sampleRate, channelCount) → 复制 PCM 到镜像 AudioTrack */
        @Volatile
        var mirrorPcmCallback: ((ByteArray, Int, Int, Int) -> Unit)? = null

        /** (sampleRate, channelCount) → 通知音频格式变更，创建镜像 AudioTrack */
        @Volatile
        var mirrorConfigCallback: ((Int, Int) -> Unit)? = null
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // 只处理 16-bit PCM 数据
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            this.inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
            return AudioProcessor.AudioFormat.NOT_SET
        }

        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        this.isActive = true

        // ★ 通知镜像模块音频格式（创建匹配的 AudioTrack）
        try {
            mirrorConfigCallback?.invoke(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        } catch (e: Exception) {
            // 静默失败，不影响主播放
        }

        return outputAudioFormat
    }

    override fun isActive(): Boolean = inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val size = inputBuffer.remaining()

        // ★ 复用缓冲区读取 PCM 数据（FFT 分析 + 镜像输出共用）
        if (size > 0) {
            if (audioDataBuffer.size < size) {
                audioDataBuffer = ByteArray(size)
            }
            inputBuffer.get(audioDataBuffer, 0, size)
            // 重置 position 以便后续播放使用
            inputBuffer.position(position)

            // FFT 频谱分析（异步，不阻塞音频流）
            if (fftProcessor.isEnabled.value) {
                fftProcessor.process(
                    audioData = audioDataBuffer,
                    sampleRate = inputAudioFormat.sampleRate,
                    channelCount = inputAudioFormat.channelCount,
                    audioDataSize = size,
                )
            }

            // ★ 镜像输出：将 PCM 数据复制到镜像 AudioTrack
            try {
                mirrorPcmCallback?.invoke(
                    audioDataBuffer,
                    size,
                    inputAudioFormat.sampleRate,
                    inputAudioFormat.channelCount,
                )
            } catch (e: Exception) {
                // 静默失败，不影响主播放
            }
        }

        // 将缓冲区传递给输出（透传模式，不修改音频数据）
        outputBuffer = inputBuffer
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val buffer = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return buffer
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        fftProcessor.reset()
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        isActive = false
        audioDataBuffer = ByteArray(0)
    }
}
