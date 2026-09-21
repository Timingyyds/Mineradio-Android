package com.mineradio.app.wallpaper

import android.content.Context
import android.opengl.GLSurfaceView
import androidx.media3.exoplayer.ExoPlayer

/**
 * 壁纸 GL 渲染器抽象基类
 * 移植自 project_lw 的 GLWallpaperRenderer
 */
internal abstract class GLWallpaperRenderer(
    protected val context: Context,
) : GLSurfaceView.Renderer {
    abstract fun setSourcePlayer(exoPlayer: ExoPlayer)

    abstract fun setScreenSize(
        width: Int,
        height: Int,
    )

    abstract fun setVideoSizeAndRotation(
        width: Int,
        height: Int,
        rotation: Int,
    )

    abstract fun setOffset(
        xOffset: Float,
        yOffset: Float,
    )

    companion object {
        private const val TAG = "GLWallpaperRenderer"
    }
}
