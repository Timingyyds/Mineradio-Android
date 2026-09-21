package com.mineradio.app.service

import android.graphics.Bitmap

/**
 * 媒体通知栏状态单例 — MainActivity 和 MusicPlaybackService 之间的共享状态
 *
 * KAppBridge 收到 JS 调用后更新此对象，
 * MusicPlaybackService 观察此对象来刷新通知栏。
 */
object MediaNotificationState {
    /** 歌曲标题 */
    @Volatile var title: String = "Mineradio"

    /** 歌手名 */
    @Volatile var artist: String = ""

    /** 封面图片 URL（用于异步加载） */
    @Volatile var coverUrl: String = ""

    /** 封面 Bitmap（加载后缓存） */
    @Volatile var coverBitmap: Bitmap? = null

    /** 当前播放位置（毫秒） */
    @Volatile var position: Long = 0L

    /** 总时长（毫秒） */
    @Volatile var duration: Long = 0L

    /** 是否正在播放 */
    @Volatile var isPlaying: Boolean = false

    /** 通知栏是否需要刷新 */
    @Volatile var needsUpdate: Boolean = false

    /** MineradioServer 端口（由 LandscapeWebActivity 启动时写入，供 MusicPlaybackService 拼接本地 URL） */
    @Volatile var serverPort: Int = 8800

    /** 播放/暂停回调（由 Service 设置） */
    var onPlayPause: (() -> Unit)? = null

    /** 播放上一首回调 */
    var onSkipPrevious: (() -> Unit)? = null

    /** 播放下一首回调 */
    var onSkipNext: (() -> Unit)? = null

    /** 拖动进度回调，参数为 seek 目标位置（秒） */
    var onSeek: ((Float) -> Unit)? = null

    /**
     * 更新歌曲元数据（JS 侧切歌时调用）
     * @param durationMs 总时长（毫秒），可选
     */
    fun updateMeta(
        title: String,
        artist: String,
        coverUrl: String,
        durationMs: Long = 0L,
    ) {
        val oldCoverUrl = this.coverUrl
        this.title = title.ifBlank { "Mineradio" }
        this.artist = artist
        this.coverUrl = coverUrl
        if (durationMs > 0) this.duration = durationMs
        // 封面变了，清除旧缓存
        if (coverUrl.isNotBlank() && coverUrl != oldCoverUrl) {
            coverBitmap = null
        }
        needsUpdate = true
    }

    /**
     * 更新播放进度（JS 侧定时调用）
     */
    fun updatePosition(
        positionMs: Long,
        durationMs: Long,
    ) {
        this.position = positionMs
        this.duration = durationMs
        needsUpdate = true
    }

    /**
     * 更新播放状态
     */
    fun updatePlayState(playing: Boolean) {
        this.isPlaying = playing
        needsUpdate = true
    }
}
