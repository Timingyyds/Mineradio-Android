package io.wallpaperengine.weutil;

import android.content.Context;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.RandomAccessFile;

/**
 * 视频壁纸播放器 - native 库 libscenejni.so 通过 JNI 回调此类。
 * 缺失会导致 initScene 时 FindClass 失败 → SIGABRT 闪退。
 *
 * 实现逻辑对齐原版壁纸引擎 2.8.8，使用 RandomAccessFile + MediaDataSource
 * 按需 seek 读取 mpkg 内的视频流，避免一次性读入大文件导致 OOM。
 */
public final class SupportVideoPlayer implements SurfaceTexture.OnFrameAvailableListener {
    private Surface surface;
    private SurfaceTexture surfaceTexture;
    private int textureId;
    private boolean updateSurface;
    private MediaPlayer video = new MediaPlayer();
    private final int GL_TEXTURE_EXTERNAL_OES = 36197;

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (this) {
            this.updateSurface = true;
        }
    }

    public final void init(int textureId) {
        this.textureId = textureId;
        SurfaceTexture surfaceTexture = new SurfaceTexture(textureId);
        this.surfaceTexture = surfaceTexture;
        surfaceTexture.setOnFrameAvailableListener(this);
    }

    public final void update() {
        synchronized (this) {
            if (this.updateSurface) {
                SurfaceTexture surfaceTexture = this.surfaceTexture;
                surfaceTexture.updateTexImage();
                this.updateSurface = false;
            }
        }
    }

    public final boolean isPlaying() {
        return this.video.isPlaying();
    }

    public final int getDuration() {
        return this.video.getDuration();
    }

    public final void setCurrentPosition(long time) {
        this.video.seekTo(time, 2);
    }

    public final int getCurrentPosition() {
        return this.video.getCurrentPosition();
    }

    public final void setPlaybackRate(float rate) {
        PlaybackParams playbackParams = new PlaybackParams();
        playbackParams.setSpeed(rate);
        this.video.setPlaybackParams(playbackParams);
    }

    public final void setLoop(boolean shouldLoop) {
        this.video.setLooping(shouldLoop);
    }

    public final void play() {
        try {
            this.video.start();
        } catch (Exception unused) {
        }
    }

    public final void pause() {
        try {
            this.video.pause();
        } catch (Exception unused) {
        }
    }

    public final void stop() {
        try {
            this.video.stop();
            this.video.reset();
        } catch (Exception unused) {
        }
    }

    public final void release() {
        stop();
        if (surfaceTexture != null) {
            surfaceTexture.setOnFrameAvailableListener(null);
            surfaceTexture.release();
        }
        surfaceTexture = null;
        if (surface != null) {
            surface.release();
        }
        surface = null;
    }

    /**
     * 启动视频播放。
     * 关键：用 RandomAccessFile + MediaDataSource.readAt 按需 seek 读取，
     * 只读取 MediaPlayer 当前需要的字节块，不一次性加载整个视频到内存。
     * 这使得 >200MB 的视频壁纸也能稳定播放，不会 OOM。
     *
     * @param androidContext 上下文
     * @param file           mpkg 文件绝对路径
     * @param baseOffset     视频流在 mpkg 内的起始字节偏移
     * @param size           视频流的字节长度
     */
    public final void startPlayback(Context androidContext, String file, final long baseOffset, final long size) {
        this.video.setLooping(true);
        this.video.setVolume(0.0f, 0.0f);
        try {
            final RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
            FileDescriptor fd = randomAccessFile.getFD();
            if (fd.valid()) {
                this.video.setDataSource(new MediaDataSource() {
                    @Override
                    public void close() {
                        try {
                            randomAccessFile.close();
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public int readAt(long position, byte[] buffer, int offset, int size2) {
                        try {
                            randomAccessFile.seek(position + baseOffset);
                            return randomAccessFile.read(buffer, offset, size2);
                        } catch (Exception e) {
                            return -1;
                        }
                    }

                    @Override
                    public long getSize() {
                        return size;
                    }
                });
                if (this.surface != null) {
                    this.surface.release();
                }
                Surface surface2 = new Surface(this.surfaceTexture);
                this.surface = surface2;
                this.video.setSurface(surface2);
                this.video.prepare();
                this.video.start();
            }
        } catch (Throwable unused) {
            try {
                this.video.setDataSource(androidContext, Uri.parse(file));
            } catch (Exception ignored) {
            }
        }
    }

    public final Point getVideoDimensions() {
        Point point = new Point(64, 64);
        try {
            point.x = this.video.getMetrics().getInt("android.media.mediaplayer.width");
            point.y = this.video.getMetrics().getInt("android.media.mediaplayer.height");
        } catch (Throwable unused) {
        }
        return point;
    }
}
