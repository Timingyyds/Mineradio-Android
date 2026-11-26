package com.mineradio.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.app.Service;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.content.Context;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.json.JSONObject;

import com.mineradio.android.coloros.ColorOsLyricBridge;

public class FoliaPlaybackService extends Service {
    private static final String CHANNEL_ID = "folia_playback";
    private static final int NOTIFICATION_ID = 31009;
    public static final String ACTION_REFRESH_METADATA = "com.mineradio.android.action.REFRESH_METADATA";
    private MediaSession mediaSession;
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private final OkHttpClient artworkClient = new OkHttpClient();
    private final Map<String, Bitmap> artworkCache = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String currentArtworkUrl = "";
    private volatile String appliedArtworkUrl = "";
    private volatile Bitmap appliedArtworkBitmap = null;
    private volatile String lastRequestedCoverUrl = "";
    private volatile String cachedTitle = "";
    private volatile String cachedArtist = "";
    private volatile String cachedAlbum = "";
    private volatile String cachedCoverUrl = "";
    private volatile String cachedSongKey = "";
    private volatile long cachedDurationMs = 0L;
    private volatile long cachedPositionMs = 0L;
    private volatile boolean cachedPlaying = false;
    private static volatile boolean running = false;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private static volatile String powerDiagnostic = "inactive";
    private static final long SERVICE_HEARTBEAT_INTERVAL_MS = 10_000L;
    /**
     * `service-start` 的重复记录间隔。前台服务每次收到播放状态推送都会走一遍 `onStartCommand`，
     * 崩溃历史只留 20 条，不节流的话这些重复记录会把真正的退出原因挤掉。
     */
    private static final long SERVICE_START_RECORD_INTERVAL_MS = 5_000L;
    private volatile boolean lastPlaying = false;
    private volatile long lastServiceStartRecordAt = 0L;
    private volatile boolean lastServiceStartPlaying = false;
    private volatile String lastServiceStartSongKey = "";
    private final Runnable serviceHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            recordServiceHeartbeat();
            mainHandler.postDelayed(this, SERVICE_HEARTBEAT_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createNotificationChannel();
        mediaSession = new MediaSession(this, "FoliaPlayback");
        // 「歌曲永不让位」策略：本服务持有音频焦点（AUDIOFOCUS_GAIN），焦点变化监听器 no-op。
        // 实际申请时机很关键，见 ensureAudioFocus 的注释：必须在 WebView 起播之前拿到，
        // 否则会把它挤掉（播放一秒就暂停）。
        ensureAudioFocus(this);

mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { emit("play"); }
            @Override public void onPause() { emit("pause"); }
            @Override public void onSkipToNext() { emit("next"); }
            @Override public void onSkipToPrevious() { emit("previous"); }
            @Override public void onStop() { emit("stop"); }
            @Override public void onSeekTo(long pos) { emit("seek:" + pos); }
        });
        mediaSession.setActive(true);
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Folia:Playback");
            wakeLock.setReferenceCounted(false);
        }
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager != null) {
            int lockMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wifiManager.createWifiLock(lockMode, "Folia:Playback");
            wifiLock.setReferenceCounted(false);
        }
        recordServiceLifecycle("service-create", "Foreground playback service created");
        mainHandler.post(serviceHeartbeatRunnable);
    }

    private static volatile boolean audioFocusHeld = false;
    private static AudioFocusRequest audioFocusRequest;

    /**
     * 申请（并长期保持）音频焦点。
     *
     * 【为什么时机很重要】WebView 里的 <audio> 起播时会自己向系统要焦点；
     * 如果前台服务在这之后才 requestAudioFocus(AUDIOFOCUS_GAIN)，系统会给
     * WebView 的 AudioFocusDelegate 派发 AUDIOFOCUS_LOSS(-1)，Chromium 收到就
     * 直接暂停播放 —— 表现就是「启动自动播放响一秒突然停，再点播放才正常」。
     *
     * 所以 JS 会在起播**之前**调用插件的 prepareAudioFocus()，先把焦点攥住；
     * 之后 WebView 要焦点时，被挤掉的是本服务（监听器 no-op，不暂停），播放不受影响。
     */
    static synchronized void ensureAudioFocus(Context context) {
        if (audioFocusHeld || context == null) return;
        try {
            AudioManager manager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) return;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                        .setOnAudioFocusChangeListener(focusChange -> { /* 刻意不响应：永不暂停 */ })
                        .setWillPauseWhenDucked(false)
                        .build();
                }
                manager.requestAudioFocus(audioFocusRequest);
            } else {
                //noinspection deprecation
                manager.requestAudioFocus(
                    focusChange -> { /* 刻意不响应 */ },
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
            audioFocusHeld = true;
        } catch (Exception ignored) {
            audioFocusHeld = false;
        }
    }

    /** ColorOS lyric bridge uses this to decide whether a metadata refresh intent is worth sending. */
    public static boolean isRunning() {
        return running;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "clear".equals(intent.getAction())) {
            recordServiceLifecycle("service-stop-command", "Stop command received");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH_METADATA.equals(intent.getAction())) {
            applyCachedMetadata();
            return START_STICKY;
        }

        cachedTitle = intent.getStringExtra("title");
        cachedArtist = intent.getStringExtra("artist");
        cachedAlbum = intent.getStringExtra("album");
        cachedCoverUrl = intent.getStringExtra("coverUrl");
        cachedSongKey = intent.getStringExtra("songKey");
        cachedPlaying = intent.getBooleanExtra("playing", false);
        cachedPositionMs = intent.getLongExtra("position", 0);
        cachedDurationMs = intent.getLongExtra("duration", 0);
        if (cachedTitle == null) cachedTitle = "";
        if (cachedArtist == null) cachedArtist = "";
        if (cachedAlbum == null) cachedAlbum = "";
        if (cachedCoverUrl == null) cachedCoverUrl = "";
        if (cachedSongKey == null) cachedSongKey = "";
        lastPlaying = cachedPlaying;
        recordServiceStartIfNoteworthy();
        currentArtworkUrl = cachedCoverUrl.trim();
        Bitmap retainedArtwork = appliedArtworkBitmap;
        if (currentArtworkUrl.isEmpty()) {
            appliedArtworkUrl = "";
            appliedArtworkBitmap = null;
            retainedArtwork = null;
        }
        if (!currentArtworkUrl.equals(lastRequestedCoverUrl)) {
            lastRequestedCoverUrl = currentArtworkUrl;
            FoliaCorePlugin.notePlaybackArtwork(
                currentArtworkUrl,
                currentArtworkUrl.isEmpty() ? "none" : "queued",
                ""
            );
        }

        // The notification goes on right below via startForeground; building it twice here was
        // one of the two writes per start command (the other is the lifecycle record).
        applyCachedMetadata(false);
        FoliaCorePlugin.notePlaybackArtworkApplied(retainedArtwork != null);

        Notification notification = buildNotification(cachedTitle, cachedArtist, cachedPlaying, retainedArtwork);
        startForeground(NOTIFICATION_ID, notification);
        updatePlaybackLocks(cachedPlaying);
        String requestedCover = currentArtworkUrl;
        loadArtwork(requestedCover, bitmap -> {
            if (!requestedCover.equals(currentArtworkUrl)) return;
            mediaSession.setMetadata(buildMetadata(cachedTitle, cachedArtist, cachedAlbum, cachedDurationMs, bitmap, cachedCoverUrl, cachedSongKey));
            FoliaCorePlugin.notePlaybackArtworkApplied(bitmap != null);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification(cachedTitle, cachedArtist, cachedPlaying, bitmap));
            }
        });
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        CrashDiagnostics.recordText(
            getApplicationContext(),
            "activity-lifecycle",
            "service",
            "task-removed",
            "Task removed from recents",
            "FoliaPlaybackService.onTaskRemoved"
        );
        super.onTaskRemoved(rootIntent);
    }

    private MediaMetadata buildMetadata(
        String title,
        String artist,
        String album,
        long duration,
        Bitmap artwork,
        String coverUrl,
        String songKey
    ) {
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title == null ? "Folia" : title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist == null ? "" : artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album == null ? "" : album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
        if (artwork == null && coverUrl != null && !coverUrl.isEmpty()) {
            metadata.putString(MediaMetadata.METADATA_KEY_ART_URI, coverUrl);
            metadata.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, coverUrl);
        }
        if (artwork != null) {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork);
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
        }
        ColorOsLyricBridge.attach(metadata, songKey);
        return metadata.build();
    }

    private void applyCachedMetadata() {
        applyCachedMetadata(true);
    }

    /** `notify=false`：紧接着就要 `startForeground` 的路径，不再重复构建一次通知。 */
    private void applyCachedMetadata(boolean notify) {
        if (mediaSession == null) return;
        Bitmap retained = appliedArtworkBitmap;
        mediaSession.setMetadata(buildMetadata(
            cachedTitle,
            cachedArtist,
            cachedAlbum,
            cachedDurationMs,
            retained,
            cachedCoverUrl,
            cachedSongKey
        ));
        mediaSession.setPlaybackState(buildPlaybackState(cachedPlaying, cachedPositionMs, cachedSongKey));
        if (notify) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && running) {
                manager.notify(NOTIFICATION_ID, buildNotification(cachedTitle, cachedArtist, cachedPlaying, retained));
            }
        }
    }

    private PlaybackState buildPlaybackState(boolean playing, long position, String songKey) {
        PlaybackState.Builder builder = new PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_SEEK_TO
            )
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, position, playing ? 1f : 0f);
        if (ColorOsLyricBridge.hasTranslationFor(songKey)) {
            builder.addCustomAction(new PlaybackState.CustomAction.Builder(
                ColorOsLyricBridge.ACTION_TOGGLE_TRANSLATION,
                "翻译",
                translationActionIcon()
            ).build());
        }
        return builder.build();
    }

    private int translationActionIcon() {
        int icon = getApplicationInfo().icon;
        return icon != 0 ? icon : android.R.drawable.ic_menu_info_details;
    }

    private Notification buildNotification(
        String title,
        String artist,
        boolean playing,
        Bitmap artwork
    ) {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        int playPauseIcon = playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        PendingIntent playPauseIntent = PendingIntent.getBroadcast(
            this,
            1,
            new Intent(this, PlaybackActionReceiver.class).setAction(playing ? "pause" : "play"),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent previousIntent = PendingIntent.getBroadcast(
            this,
            2,
            new Intent(this, PlaybackActionReceiver.class).setAction("previous"),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent nextIntent = PendingIntent.getBroadcast(
            this,
            3,
            new Intent(this, PlaybackActionReceiver.class).setAction("next"),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        // 副标题：歌手 · 当前时间 / 总时长（照参考工程 MusicPlaybackService 的拼法）
        String subtitle = buildSubtitle(artist, cachedPositionMs, cachedDurationMs);

        int smallIcon = getApplicationInfo().icon;
        if (smallIcon == 0) smallIcon = android.R.drawable.ic_media_play;

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title == null ? "Folia" : title)
            .setContentText(subtitle)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "上一首", previousIntent)
            .addAction(playPauseIcon, playing ? "暂停" : "播放", playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "下一首", nextIntent)
            .setStyle(new Notification.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        // 进度条（Android 12+ 由 MediaStyle 读 PlaybackState，这里再显式设一次兜底）
        if (cachedDurationMs > 0) {
            builder.setProgress((int) cachedDurationMs, (int) Math.max(0, cachedPositionMs), false);
        }
        if (artwork != null) builder.setLargeIcon(artwork);
        return builder.build();
    }

    private String buildSubtitle(String artist, long positionMs, long durationMs) {
        StringBuilder sb = new StringBuilder();
        if (artist != null && !artist.isEmpty()) sb.append(artist);
        if (durationMs > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(formatTime(positionMs)).append(" / ").append(formatTime(durationMs));
        }
        if (sb.length() == 0) return artist == null ? "" : artist;
        return sb.toString();
    }

    private String formatTime(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds);
    }

    private void loadArtwork(String value, ArtworkCallback callback) {
        if (value == null || value.isEmpty()) return;
        if (value.equals(appliedArtworkUrl)) return;
        Bitmap cached = artworkCache.get(value);
        if (cached != null) {
            mainHandler.post(() -> {
                if (value.equals(currentArtworkUrl)) {
                    appliedArtworkUrl = value;
                    appliedArtworkBitmap = cached;
                    FoliaCorePlugin.notePlaybackArtwork(
                        value,
                        "loaded",
                        "cached " + cached.getWidth() + "x" + cached.getHeight()
                    );
                }
                callback.onLoaded(cached);
            });
            return;
        }
        if (!(value.startsWith("http://") || value.startsWith("https://"))) {
            // 自定义封面是 data:image/... base64，直接解码即可
            if (value.startsWith("data:image")) {
                try {
                    String base64 = value.substring(value.indexOf(',') + 1);
                    byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                    Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (decoded != null) {
                        artworkCache.put(value, decoded);
                        mainHandler.post(() -> {
                            if (value.equals(currentArtworkUrl)) {
                                appliedArtworkUrl = value;
                                appliedArtworkBitmap = decoded;
                                FoliaCorePlugin.notePlaybackArtworkApplied(true);
                            }
                            callback.onLoaded(decoded);
                        });
                        return;
                    }
                } catch (Exception ignored) {
                    // 解码失败按「没有封面」处理
                }
            }
            FoliaCorePlugin.notePlaybackArtwork(value, "error", "unsupported-cover-url");
            return;
        }
        artworkExecutor.execute(() -> {
            Bitmap bitmap = null;
            String failure = "";
            try (Response response = artworkClient.newCall(new Request.Builder()
                .url(value)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile)")
                .build()).execute()) {
                ResponseBody body = response.body();
                if (response.isSuccessful() && body != null) {
                    try (InputStream input = body.byteStream()) {
                        bitmap = BitmapFactory.decodeStream(input);
                    }
                    if (bitmap == null) failure = "decode-failed";
                } else {
                    failure = "http-" + response.code();
                }
            } catch (Exception error) {
                failure = error.getClass().getSimpleName();
            }
            if (bitmap != null) artworkCache.put(value, bitmap);
            Bitmap resolved = bitmap;
            if (resolved != null) {
                mainHandler.post(() -> {
                    if (value.equals(currentArtworkUrl)) {
                        appliedArtworkUrl = value;
                        appliedArtworkBitmap = resolved;
                        FoliaCorePlugin.notePlaybackArtwork(
                            value,
                            "loaded",
                            resolved.getWidth() + "x" + resolved.getHeight()
                        );
                    }
                    callback.onLoaded(resolved);
                });
            } else {
                FoliaCorePlugin.notePlaybackArtwork(value, "error", failure.isEmpty() ? "unknown" : failure);
            }
        });
    }

    private interface ArtworkCallback {
        void onLoaded(Bitmap bitmap);
    }

    /**
     * Keeps the remote-audio proxy alive in the background.
     *
     * A foreground service keeps the process visible, but it does not stop Doze from throttling
     * the CPU or the Wi-Fi radio. Playback pulls the stream through LocalAudioServer, so losing
     * either one shows up as intermittent audio while the screen is off.
     */
    private void updatePlaybackLocks(boolean playing) {
        try {
            if (playing) {
                if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
                if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
            } else {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            }
            powerDiagnostic = "wake=" + (wakeLock != null && wakeLock.isHeld() ? "held" : "released")
                + " wifi=" + (wifiLock != null && wifiLock.isHeld() ? "held" : "released")
                + " error=none";
        } catch (Exception error) {
            powerDiagnostic = "wake=" + (wakeLock != null && wakeLock.isHeld() ? "held" : "released")
                + " wifi=" + (wifiLock != null && wifiLock.isHeld() ? "held" : "released")
                + " error=" + error.getClass().getSimpleName();
        }
    }

    static String getPowerDiagnostic() {
        return powerDiagnostic;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Folia playback",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void emit(String action) {
        FoliaCorePlugin.emitMediaAction(action);
    }

    private void recordServiceLifecycle(String type, String message) {
        CrashDiagnostics.recordText(
            getApplicationContext(),
            "service-lifecycle",
            "service",
            type,
            message,
            "FoliaPlaybackService"
        );
    }

    /**
     * 只在播放状态或曲目真的变了、或距上次记录已满 {@link #SERVICE_START_RECORD_INTERVAL_MS}
     * 时记一条 `service-start`。推送本身仍然每次都处理，只是不再每次都写诊断。
     */
    private void recordServiceStartIfNoteworthy() {
        long now = SystemClock.elapsedRealtime();
        boolean stateChanged = cachedPlaying != lastServiceStartPlaying
            || !cachedSongKey.equals(lastServiceStartSongKey);
        if (!stateChanged && now - lastServiceStartRecordAt < SERVICE_START_RECORD_INTERVAL_MS) {
            return;
        }
        lastServiceStartRecordAt = now;
        lastServiceStartPlaying = cachedPlaying;
        lastServiceStartSongKey = cachedSongKey;
        recordServiceLifecycle("service-start", "playing=" + cachedPlaying);
    }

    private void recordServiceHeartbeat() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("at", System.currentTimeMillis());
            payload.put("playing", lastPlaying);
            payload.put("wakeLock", wakeLock != null && wakeLock.isHeld() ? "held" : "released");
            payload.put("wifiLock", wifiLock != null && wifiLock.isHeld() ? "held" : "released");
            payload.put("foreground", true);
            CrashDiagnostics.recordServiceHeartbeat(getApplicationContext(), payload.toString());
        } catch (Exception ignored) {
            // A missed heartbeat is harmless; the next one is 10 seconds away.
        }
    }

    @Override
    public void onDestroy() {
        recordServiceLifecycle("service-destroy", "Foreground playback service destroyed");
        running = false;
        mainHandler.removeCallbacks(serviceHeartbeatRunnable);
        updatePlaybackLocks(false);
        artworkExecutor.shutdownNow();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
