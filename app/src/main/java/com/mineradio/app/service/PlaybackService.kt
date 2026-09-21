package com.mineradio.app.service

import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.mineradio.app.player.api.IMusicPlayer
import com.mineradio.app.player.impl.utils.MediaLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * 媒体播放后台服务
 * 使用 Media3 MediaLibraryService 实现后台播放
 * 集成 FFT 音频处理器进行频谱分析
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {
    private val player: IMusicPlayer by inject()

    // ★ 注入 PreferencesManager 用于读取保存的音效预设
    private val preferencesManager: com.mineradio.app.core.preferences.PreferencesManager by inject()

    // ★ 注入 PlayerKVUtils 用于保存播放进度
    private val playerKVUtils: com.mineradio.app.player.impl.utils.PlayerKVUtils by inject()

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var exoPlayer: ExoPlayer

    // ★ 保存 AudioSink 引用，供 AudioOutputManager 设置 preferredDevice
    private var audioSink: AudioSink? = null

    companion object {
        // ★ 静音标志：由 AudioOutputManager 设置，PlaybackService 应用到 ExoPlayer
        @Volatile
        var outputMuted: Boolean = false
    }

    // 服务级别的协程作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            SpicaNotificationProvider(this),
        )
        // ★ 注册镜像 PCM 回调：FFTAudioProcessorWrapper → AudioOutputManager
        com.mineradio.app.player.impl.dsp.FFTAudioProcessorWrapper.mirrorPcmCallback =
            { data, size, _, _ ->
                com.mineradio.app.audio.AudioOutputManager
                    .writeMirrorPcm(data, size)
            }
        com.mineradio.app.player.impl.dsp.FFTAudioProcessorWrapper.mirrorConfigCallback =
            { sampleRate, channelCount ->
                com.mineradio.app.audio.AudioOutputManager
                    .configureMirror(sampleRate, channelCount)
            }
        // 创建自定义渲染器工厂，添加音频处理器（FFT、EQ、混响）
        val renderersFactory =
            object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink =
                    DefaultAudioSink
                        .Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(
                            // 音频处理链: FFT -> EQ -> Reverb
                            (player as? com.mineradio.app.player.impl.SpicaPlayer)
                                ?.getAudioProcessors()
                                ?: arrayOf(player.fftAudioProcessor),
                        ).build()
                        .apply {
                            setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
                            // ★ 保存 sink 引用并注册到 AudioOutputManager
                            audioSink = this
                            com.mineradio.app.audio.AudioOutputManager
                                .registerSink(this as? DefaultAudioSink)
                        }
            }
        exoPlayer =
            ExoPlayer
                .Builder(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        createAttributionContext("audioPlayback")
                    } else {
                        this
                    },
                    renderersFactory,
                ).setWakeMode(C.WAKE_MODE_LOCAL)
                .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
                        .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                ).setUsePlatformDiagnostics(false)
                .build()

        // ★ 应用无设备静音标志：播放时根据 outputMuted 设置 volume
        exoPlayer.addListener(
            object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    exoPlayer.volume = if (outputMuted) 0f else 1f
                }
            },
        )

        mediaSession =
            MediaLibrarySession
                .Builder(
                    this,
                    exoPlayer,
                    object : MediaLibrarySession.Callback {
                        override fun onGetLibraryRoot(
                            session: MediaLibrarySession,
                            browser: MediaSession.ControllerInfo,
                            params: LibraryParams?,
                        ): ListenableFuture<LibraryResult<MediaItem>> =
                            Futures.immediateFuture(
                                LibraryResult.ofItem(
                                    MediaItem
                                        .Builder()
                                        .setMediaId(MediaLibrary.ROOT)
                                        .build(),
                                    params,
                                ),
                            )

                        override fun onGetItem(
                            session: MediaLibrarySession,
                            browser: MediaSession.ControllerInfo,
                            mediaId: String,
                        ): ListenableFuture<LibraryResult<MediaItem>> {
                            Timber.tag("PlaybackService").d("onGetItem: mediaId=$mediaId")
                            return serviceScope.future {
                                val item = MediaLibrary.getItem(mediaId)
                                if (item != null) {
                                    Timber
                                        .tag("PlaybackService")
                                        .d("onGetItem: Found item ${item.mediaMetadata.title}")
                                    LibraryResult.ofItem(item, null)
                                } else {
                                    Timber
                                        .tag("PlaybackService")
                                        .e("onGetItem: Item not found for mediaId=$mediaId")
                                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                                }
                            }
                        }

                        override fun onGetChildren(
                            session: MediaLibrarySession,
                            browser: MediaSession.ControllerInfo,
                            parentId: String,
                            page: Int,
                            pageSize: Int,
                            params: LibraryParams?,
                        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
                            serviceScope.future {
                                val children = MediaLibrary.getChildren(parentId)
                                LibraryResult.ofItemList(children, params)
                            }

//                        override fun onPlaybackResumption(
//                            session: MediaSession,
//                            controller: MediaSession.ControllerInfo,
//                        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
//                            Timber.tag("PlaybackService").d("onPlaybackResumption called")
//                            // 从播放历史恢复最后播放的歌曲列表
//                            val items = MediaLibrary.getChildren(MediaLibrary.ALL_SONGS)
//                            return Futures.immediateFuture(
//                                MediaSession.MediaItemsWithStartPosition(
//                                    items,
//                                    0,
//                                    0,
//                                ),
//                            )
//                        }
                    },
                ).setSessionActivity(
                    android.app.PendingIntent.getActivity(
                        this,
                        0,
                        packageManager.getLaunchIntentForPackage(packageName),
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build()

        // ★ 恢复保存的音效预设（3D环绕/360度环绕），修复重启后不生效的问题
        serviceScope.launch {
            preferencesManager
                .getInt(com.mineradio.app.core.preferences.PreferencesManager.Keys.SOUND_PRESET, 0)
                .collect { preset ->
                    player.setSurroundMode(preset)
                }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onDestroy() {
        // ★ 退出前保存当前播放进度，下次启动可恢复
        try {
            val currentMediaItem = exoPlayer.currentMediaItem
            val currentPosition = exoPlayer.currentPosition
            val duration = exoPlayer.duration
            if (currentMediaItem != null && currentPosition > 0L && (duration <= 0L || currentPosition < duration)) {
                playerKVUtils.savePlaybackPosition(currentMediaItem.mediaId, currentPosition)
                Timber
                    .tag(
                        "PlaybackService",
                    ).d("Saved playback position on destroy: mediaId=${currentMediaItem.mediaId}, pos=$currentPosition")
            }
        } catch (e: Exception) {
            Timber.tag("PlaybackService").w(e, "Failed to save playback position on destroy")
        }
        serviceScope.cancel()
        mediaSession?.run {
            // ★ 注销 AudioSink，避免 AudioOutputManager 持有已释放的实例
            com.mineradio.app.audio.AudioOutputManager
                .registerSink(null)
            // ★ 释放镜像 AudioTrack
            com.mineradio.app.audio.AudioOutputManager
                .releaseMirror()
            // ★ 清除镜像回调
            com.mineradio.app.player.impl.dsp.FFTAudioProcessorWrapper.mirrorPcmCallback = null
            com.mineradio.app.player.impl.dsp.FFTAudioProcessorWrapper.mirrorConfigCallback = null
            exoPlayer.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
