package com.mineradio.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.mineradio.app.LandscapeWebActivity
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 音乐播放前台服务 — MediaStyle 通知栏 + 后台保活核心
 *
 * 通知栏功能：
 *  - 歌曲名 / 歌手展示
 *  - 封面图片（从URL异步加载）
 *  - 播放时长 / 进度条
 *  - 播放/暂停、上一首、下一首按钮
 */
class MusicPlaybackService : Service() {
    companion object {
        const val TAG = "MusicPlaybackSvc"
        const val CHANNEL_ID = "mineradio_playback_svc"
        const val CHANNEL_NAME = "Mineradio 音乐控制"
        private const val CHANNEL_DESC = "音乐播放控制与后台保活。关闭将中断后台播放。"
        const val NOTIFICATION_ID = 9701
        const val MEDIA_SESSION_TAG = "MineradioMediaSession"

        // ── 进度条 10 段点击区域的 View ID ──
        val SEEK_ZONE_IDS =
            intArrayOf(
                com.mineradio.app.R.id.ncm_seek_0,
                com.mineradio.app.R.id.ncm_seek_1,
                com.mineradio.app.R.id.ncm_seek_2,
                com.mineradio.app.R.id.ncm_seek_3,
                com.mineradio.app.R.id.ncm_seek_4,
                com.mineradio.app.R.id.ncm_seek_5,
                com.mineradio.app.R.id.ncm_seek_6,
                com.mineradio.app.R.id.ncm_seek_7,
                com.mineradio.app.R.id.ncm_seek_8,
                com.mineradio.app.R.id.ncm_seek_9,
            )
    }

    private var serviceWakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null

    // ── 定时刷新通知栏 ──
    private val uiHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private val refreshIntervalMs = 1000L

    // ── 封面异步加载 ──
    private val coverLoader = Executors.newSingleThreadExecutor()

    @Volatile private var loadedCoverUrl: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireServiceWakeLock()
        setupMediaSession()
        Log.d(TAG, "MusicPlaybackService 创建")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val action = intent?.action

        when (action) {
            "STOP" -> {
                Log.d(TAG, "收到停止指令，释放资源并关闭")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopRefreshLoop()
                releaseServiceWakeLock()
                mediaSession?.release()
                mediaSession = null
                stopSelf()
                return START_NOT_STICKY
            }
            "PLAY_PAUSE" -> {
                Log.d(TAG, "通知栏 播放/暂停 按钮")
                MediaNotificationState.onPlayPause?.invoke()
            }
            "PREVIOUS" -> {
                Log.d(TAG, "通知栏 上一首 按钮")
                MediaNotificationState.onSkipPrevious?.invoke()
            }
            "NEXT" -> {
                Log.d(TAG, "通知栏 下一首 按钮")
                MediaNotificationState.onSkipNext?.invoke()
            }
            "SEEK" -> {
                val seekSec = intent?.getFloatExtra("seek_sec", -1f) ?: -1f
                Log.d(TAG, "通知栏 拖动进度: ${seekSec}s")
                if (seekSec >= 0f) {
                    MediaNotificationState.onSeek?.invoke(seekSec)
                }
            }
        }

        try {
            val notification = buildMediaNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "前台服务已启动 (MediaStyle)")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败: ${e.message}", e)
        }

        startRefreshLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "MusicPlaybackService 销毁")
        stopRefreshLoop()
        releaseServiceWakeLock()
        mediaSession?.release()
        mediaSession = null
        coverLoader.shutdown()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════
    //  MediaSession
    // ═══════════════════════════════════════════════

    private fun setupMediaSession() {
        mediaSession =
            MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
                )
                setPlaybackState(
                    PlaybackStateCompat
                        .Builder()
                        .setActions(
                            PlaybackStateCompat.ACTION_PLAY or
                                PlaybackStateCompat.ACTION_PAUSE or
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                                PlaybackStateCompat.ACTION_SEEK_TO,
                        ).setState(PlaybackStateCompat.STATE_NONE, 0, 1f)
                        .build(),
                )
                setCallback(
                    object : MediaSessionCompat.Callback() {
                        override fun onPlay() {
                            MediaNotificationState.onPlayPause?.invoke()
                        }

                        override fun onPause() {
                            MediaNotificationState.onPlayPause?.invoke()
                        }

                        override fun onSkipToPrevious() {
                            MediaNotificationState.onSkipPrevious?.invoke()
                        }

                        override fun onSkipToNext() {
                            MediaNotificationState.onSkipNext?.invoke()
                        }

                        override fun onSeekTo(pos: Long) {
                            val sec = pos / 1000f
                            Log.d(TAG, "MediaSession 拖动进度: ${sec}s")
                            MediaNotificationState.onSeek?.invoke(sec)
                        }
                    },
                )
                isActive = true
            }
    }

    // ═══════════════════════════════════════════════
    //  通知栏刷新循环
    // ═══════════════════════════════════════════════

    private fun startRefreshLoop() {
        stopRefreshLoop()
        refreshRunnable =
            object : Runnable {
                override fun run() {
                    try {
                        // 检查封面加载
                        val covUrl = MediaNotificationState.coverUrl
                        if (covUrl.isNotBlank() && covUrl != loadedCoverUrl) {
                            loadCoverAsync(covUrl)
                        }
                        // 更新 MediaSession 状态和元数据（Android 12+ 需要 Metadata 才能显示进度条）
                        val state = MediaNotificationState
                        val ps =
                            if (state.isPlaying) {
                                PlaybackStateCompat.STATE_PLAYING
                            } else {
                                PlaybackStateCompat.STATE_PAUSED
                            }

                        // ★ 构建 PlaybackState（含 seek 能力 + 位置/时长）
                        val playbackState =
                            PlaybackStateCompat
                                .Builder()
                                .setActions(
                                    PlaybackStateCompat.ACTION_PLAY or
                                        PlaybackStateCompat.ACTION_PAUSE or
                                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                                        PlaybackStateCompat.ACTION_SEEK_TO or
                                        PlaybackStateCompat.ACTION_PLAY_PAUSE,
                                ).setState(ps, state.position, 1f)
                                .build()
                        mediaSession?.setPlaybackState(playbackState)

                        // ★ 设置 MediaMetadata（Android 12+ 需要 DURATION 才能显示进度条）
                        val meta =
                            android.support.v4.media.MediaMetadataCompat
                                .Builder()
                                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
                                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
                                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, state.artist)
                                .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, state.duration)
                        state.coverBitmap?.let {
                            meta.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                        }
                        mediaSession?.setMetadata(meta.build())
                        // 刷新通知
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, buildMediaNotification())
                        MediaNotificationState.needsUpdate = false
                    } catch (e: Exception) {
                        Log.w(TAG, "通知刷新失败: ${e.message}")
                    }
                    uiHandler.postDelayed(this, refreshIntervalMs)
                }
            }
        uiHandler.post(refreshRunnable!!)
    }

    private fun stopRefreshLoop() {
        refreshRunnable?.let { uiHandler.removeCallbacks(it) }
        refreshRunnable = null
    }

    // ═══════════════════════════════════════════════
    //  封面异步加载
    // ═══════════════════════════════════════════════

    private fun loadCoverAsync(url: String) {
        coverLoader.execute {
            try {
                val bitmap = downloadCover(url)
                if (bitmap != null) {
                    MediaNotificationState.coverBitmap = bitmap
                    loadedCoverUrl = url
                    MediaNotificationState.needsUpdate = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "封面加载失败: ${e.message}")
            }
        }
    }

    private fun downloadCover(urlStr: String): Bitmap? {
        // ★ 本地封面（/local-cover/xxx.jpg）直接从文件系统读取，不走 HTTP
        //   WebView 里 <img src="/local-cover/..."> 是相对路径，能被 MineradioServer 解析；
        //   但通知栏在 Service 进程里用 URL() 打开连接，相对路径无法解析 → 必须直接读文件
        if (urlStr.startsWith("/local-cover/")) {
            val rawFileName = urlStr.substringAfter("/local-cover/")
            // ★ 文件名可能被 URL 编码（如 %E4%B8%96%E7%95%8C...），需解码后再读文件
            val fileName =
                try {
                    java.net.URLDecoder.decode(rawFileName, "UTF-8")
                } catch (_: Exception) {
                    rawFileName
                }
            return try {
                val coverFile = java.io.File(filesDir, "local_covers/$fileName")
                if (!coverFile.exists()) return null
                val bytes = coverFile.readBytes()
                decodeCoverBytes(bytes)
            } catch (e: Exception) {
                null
            }
        }
        // ★ 其它以 / 开头的相对路径（如 /room-cover/）：拼上本地服务器端口
        val effectiveUrl =
            if (urlStr.startsWith("/")) {
                "http://127.0.0.1:${MediaNotificationState.serverPort}$urlStr"
            } else {
                urlStr
            }
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            val urlObj = URL(effectiveUrl)
            conn = urlObj.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            )
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return null
            input = conn.inputStream
            val bytes = input.readBytes()
            decodeCoverBytes(bytes)
        } catch (e: Exception) {
            null
        } finally {
            try {
                input?.close()
            } catch (_: Exception) {
            }
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** 解码封面字节数组并缩放到 256px 上限 */
    private fun decodeCoverBytes(bytes: ByteArray): Bitmap? {
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val maxSize = 256
        val scale = maxOf(raw.width, raw.height).toFloat() / maxSize
        return if (scale > 1f) {
            val w = (raw.width / scale).toInt()
            val h = (raw.height / scale).toInt()
            Bitmap.createScaledBitmap(raw, w, h, true)
        } else {
            raw
        }
    }

    // ═══════════════════════════════════════════════
    //  构建通知（自定义 RemoteViews + 时间显示 + 可点击进度条）
    // ═══════════════════════════════════════════════

    private fun buildMediaNotification(): Notification {
        val state = MediaNotificationState

        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, LandscapeWebActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val playPauseIcon =
            if (state.isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
        val playPauseLabel = if (state.isPlaying) "暂停" else "播放"

        val playPauseAction =
            NotificationCompat.Action
                .Builder(
                    playPauseIcon,
                    playPauseLabel,
                    buildMediaButtonIntent("PLAY_PAUSE"),
                ).build()

        val prevAction =
            NotificationCompat.Action
                .Builder(
                    android.R.drawable.ic_media_previous,
                    "上一首",
                    buildMediaButtonIntent("PREVIOUS"),
                ).build()

        val nextAction =
            NotificationCompat.Action
                .Builder(
                    android.R.drawable.ic_media_next,
                    "下一首",
                    buildMediaButtonIntent("NEXT"),
                ).build()

        val subtitle =
            buildString {
                if (state.artist.isNotBlank()) append(state.artist)
                if (state.duration > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(formatTime(state.position))
                    append(" / ")
                    append(formatTime(state.duration))
                }
            }

        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setContentTitle(state.title)
                .setContentText(subtitle.ifBlank { state.artist.ifBlank { "Mineradio" } })
                .setSmallIcon(com.mineradio.app.R.mipmap.ic_launcher)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(prevAction)
                .addAction(playPauseAction)
                .addAction(nextAction)

        val cover =
            state.coverBitmap
                ?: try {
                    BitmapFactory.decodeResource(resources, com.mineradio.app.R.mipmap.ic_launcher)
                } catch (_: Exception) {
                    null
                }
        if (cover != null) {
            builder.setLargeIcon(cover)
        }

        // ★★★ 时间显示 + 可拖动进度条 ★★★
        // Android 12+ (API 31+): MediaStyle 从 MediaSession PlaybackState 自动渲染进度条
        // Android 12 以下: 使用自定义 RemoteViews 显示时间和进度条
        val customView = buildSeekBarRemoteViews(state)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            builder.setCustomContentView(customView)
            builder.setCustomBigContentView(customView)
        }

        // 通知样式：MediaStyle + MediaSession = 系统级媒体控制（支持锁屏/蓝牙/车载）
        val mediaStyle =
            androidx.media.app.NotificationCompat
                .MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        builder.setStyle(mediaStyle)

        // ★ 始终显示进度条（MediaSession PlaybackState + setProgress 双重保障）
        if (state.duration > 0) {
            builder.setProgress(state.duration.toInt(), state.position.toInt(), false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    /**
     * 构建带时间显示和可点击进度条的 RemoteViews
     * 布局: [标题/艺术家] [当前时间 / 总时长] [=====10段点击进度条=====]
     */
    private fun buildSeekBarRemoteViews(state: MediaNotificationState): RemoteViews {
        val rv = RemoteViews(packageName, com.mineradio.app.R.layout.notification_media_custom)

        // ── 标题 + 艺术家 ──
        rv.setTextViewText(com.mineradio.app.R.id.ncm_text_title, state.title)
        rv.setTextViewText(com.mineradio.app.R.id.ncm_text_artist, state.artist)

        // ── 时间显示 ──
        rv.setTextViewText(com.mineradio.app.R.id.ncm_text_position, formatTime(state.position))
        rv.setTextViewText(com.mineradio.app.R.id.ncm_text_duration, formatTime(state.duration))

        // ── 进度条 ──
        val dur = state.duration.coerceAtLeast(1L)
        val prog = ((state.position.toFloat() / dur.toFloat()) * 1000f).toInt().coerceIn(0, 1000)
        rv.setProgressBar(com.mineradio.app.R.id.ncm_seekbar_progress, 1000, prog, false)

        // ── 10 段点击区域：每段点击 → seek 到对应位置 ──
        for (i in 0 until 10) {
            val seekSec = (i.toFloat() / 10f) * (dur / 1000f)
            rv.setOnClickPendingIntent(
                SEEK_ZONE_IDS[i],
                buildSeekIntent(seekSec),
            )
        }

        return rv
    }

    /**
     * 构建 seek 操作的 PendingIntent
     * @param seekSec seek 目标位置（秒）
     */
    private fun buildSeekIntent(seekSec: Float): PendingIntent {
        val intent =
            Intent(this, MusicPlaybackService::class.java).apply {
                action = "SEEK"
                putExtra("seek_sec", seekSec)
            }
        return PendingIntent.getService(
            this,
            ("SEEK_${seekSec.toInt()}").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildMediaButtonIntent(action: String): PendingIntent {
        val intent =
            Intent(this, MusicPlaybackService::class.java).apply {
                this.action = action
            }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ═══════════════════════════════════════════════
    //  WakeLock
    // ═══════════════════════════════════════════════

    private fun acquireServiceWakeLock() {
        if (serviceWakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            serviceWakeLock =
                pm
                    .newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Mineradio:MusicPlaybackService",
                    ).apply { acquire(60 * 60 * 1000L) }
            Log.d(TAG, "Service WakeLock 已获取")
        } catch (e: Exception) {
            Log.w(TAG, "获取 WakeLock 失败: ${e.message}")
        }
    }

    private fun releaseServiceWakeLock() {
        try {
            if (serviceWakeLock?.isHeld == true) {
                serviceWakeLock?.release()
                Log.d(TAG, "Service WakeLock 已释放")
            }
        } catch (_: Exception) {
        }
        serviceWakeLock = null
    }

    // ═══════════════════════════════════════════════
    //  通知渠道
    // ═══════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = CHANNEL_DESC
                setShowBadge(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        nm.createNotificationChannel(channel)
        Log.d(TAG, "通知渠道已创建: $CHANNEL_ID")
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
}
