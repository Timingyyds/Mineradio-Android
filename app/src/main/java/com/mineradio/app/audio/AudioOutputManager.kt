package com.mineradio.app.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * 音频输出设备管理器（单例）
 *
 * 从 LandscapeWebActivity 的 @JavascriptInterface 接收设备选择，
 * 转发给 PlaybackService 中持有的 DefaultAudioSink 实例（通过 setPreferredDevice）。
 *
 * 此功能独立于音域回响开关，随时可用。
 */
object AudioOutputManager {
    private const val TAG = "AudioOutputManager"

    /** 当前选择的首选输出设备（null = 系统默认） */
    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    /** 当前镜像监听设备（实验功能，null = 未启用） */
    @Volatile
    private var mirrorDevice: AudioDeviceInfo? = null

    /** 当前注册的 AudioSink 实例（由 PlaybackService 注册） */
    @Volatile
    private var audioSink: DefaultAudioSink? = null

    /** 镜像 PCM 写入器（第二路 AudioTrack） */
    @Volatile
    private var mirrorWriter: MirrorAudioWriter? = null

    /**
     * 由 PlaybackService 注册 AudioSink 实例，以便设置 preferredDevice
     * 注册时会立即应用当前保存的设备选择。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun registerSink(sink: DefaultAudioSink?) {
        audioSink = sink
        applyToDevice()
    }

    /**
     * 设置首选输出设备
     * @param device 目标设备，null 恢复系统默认
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferredDevice = device
        applyToDevice()
    }

    /**
     * ★ 镜像监听：设置镜像输出设备
     *   通过创建第二路 AudioTrack 并写入 PCM 实现真正的音频镜像。
     *   PCM 数据由 FFTAudioProcessorWrapper.queueInput() 拦截后传入 writeMirrorPcm()。
     *   传入 null 关闭镜像监听。
     */
    fun setMirrorDevice(device: AudioDeviceInfo?) {
        mirrorDevice = device
        if (device != null) {
            Log.i(TAG, "Mirror device set: ${device.productName}")
            // MirrorAudioWriter 会在 configureMirror() 被调用时根据音频格式创建
        } else {
            Log.i(TAG, "Mirror device cleared")
            // 释放镜像写入器
            mirrorWriter?.release()
            mirrorWriter = null
        }
    }

    /** 获取当前镜像设备 */
    fun getMirrorDevice(): AudioDeviceInfo? = mirrorDevice

    /** 获取当前选择的首选设备 */
    fun getPreferredDevice(): AudioDeviceInfo? = preferredDevice

    /**
     * ★ 由 FFTAudioProcessorWrapper.configure() 调用，
     *   通知当前音频格式，以便创建匹配的镜像 AudioTrack
     */
    fun configureMirror(
        sampleRate: Int,
        channelCount: Int,
    ) {
        val dev = mirrorDevice ?: return
        try {
            mirrorWriter?.release()
            mirrorWriter = MirrorAudioWriter(dev, sampleRate, channelCount)
            Log.i(TAG, "Mirror AudioTrack created: ${dev.productName}, ${sampleRate}Hz, ${channelCount}ch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create mirror AudioTrack", e)
        }
    }

    /**
     * ★ 由 FFTAudioProcessorWrapper.queueInput() 调用，
     *   将 PCM 数据复制到镜像 AudioTrack
     */
    fun writeMirrorPcm(
        data: ByteArray,
        size: Int,
    ) {
        val writer = mirrorWriter ?: return
        try {
            writer.write(data, size)
        } catch (e: Exception) {
            // 静默失败，避免影响主播放
        }
    }

    /** 释放镜像资源（由 PlaybackService.onDestroy 调用） */
    fun releaseMirror() {
        mirrorWriter?.release()
        mirrorWriter = null
    }

    /**
     * ★ 设置输出静音（无设备模式）
     *   歌曲继续播放（进度/歌词推进），但 ExoPlayer volume=0 不输出声音
     *   @param muted true=静音（无设备），false=恢复声音
     */
    fun setOutputMuted(muted: Boolean) {
        com.mineradio.app.service.PlaybackService.outputMuted = muted
        // 直接通过 mediaSession 设置 ExoPlayer volume（无需服务运行）
        // PlaybackService 在播放时会检查 outputMuted 标志
        Log.i(TAG, "Output muted: $muted (playback continues, no sound)")
    }

    /** 将当前设备选择应用到 AudioSink */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun applyToDevice() {
        val sink =
            audioSink ?: run {
                Log.w(TAG, "AudioSink not registered yet, device choice saved for later")
                return
            }
        try {
            sink.setPreferredDevice(preferredDevice)
            if (preferredDevice != null) {
                Log.i(TAG, "Preferred device applied: ${preferredDevice?.productName}")
            } else {
                Log.i(TAG, "Preferred device cleared (system default)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set preferred device", e)
        }
    }

    /**
     * ★ 镜像 PCM 写入器：创建第二路 AudioTrack 输出到指定设备
     */
    private class MirrorAudioWriter(
        private val device: AudioDeviceInfo,
        sampleRate: Int,
        channelCount: Int,
    ) {
        private var audioTrack: AudioTrack? = null

        init {
            val channelMask =
                if (channelCount >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBufSize =
                AudioTrack.getMinBufferSize(
                    sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            val bufSize = (minBufSize * 2).coerceAtLeast(minBufSize)
            audioTrack =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(channelMask)
                            .build(),
                    ).setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            // 设置输出设备
            audioTrack?.setPreferredDevice(device)
            audioTrack?.play()
        }

        fun write(
            data: ByteArray,
            size: Int,
        ) {
            val track = audioTrack ?: return
            // 非阻塞写入，避免影响主播放线程
            track.write(data, 0, size, AudioTrack.WRITE_NON_BLOCKING)
        }

        fun release() {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Mirror AudioTrack release error", e)
            }
            audioTrack = null
        }
    }
}
