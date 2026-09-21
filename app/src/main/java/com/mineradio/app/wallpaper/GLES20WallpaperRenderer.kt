package com.mineradio.app.wallpaper

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import com.mineradio.app.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLES 2.0 视频壁纸渲染器
 * 移植自 project_lw 的 GLES20WallpaperRenderer
 */
internal class GLES20WallpaperRenderer(
    context: Context,
) : GLWallpaperRenderer(context) {
    private val vertices: FloatBuffer
    private val texCoords: FloatBuffer
    private val indices: IntBuffer
    private val textures: IntArray
    private val buffers: IntArray
    private val mvp: FloatArray
    private var program = 0
    private var mvpLocation = 0
    private var positionLocation = 0
    private var texCoordLocation = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoRotation = 0
    private var xOffset = 0f
    private var yOffset = 0f
    private var maxXOffset = 0f
    private var maxYOffset = 0f

    private var updatedFrame: Long = 0
    private var renderedFrame: Long = 0

    override fun onSurfaceCreated(
        gl10: GL10?,
        eglConfig: EGLConfig?,
    ) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glGenTextures(textures.size, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program =
            ShaderUtils.linkProgramGLES20(
                ShaderUtils.compileShaderGLES20(context, GLES20.GL_VERTEX_SHADER, R.raw.vertex_20),
                ShaderUtils.compileShaderGLES20(context, GLES20.GL_FRAGMENT_SHADER, R.raw.fragment_20),
            )
        mvpLocation = GLES20.glGetUniformLocation(program, "mvp")
        positionLocation = GLES20.glGetAttribLocation(program, "in_position")
        texCoordLocation = GLES20.glGetAttribLocation(program, "in_tex_coord")
        GLES20.glGenBuffers(buffers.size, buffers, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            vertices.capacity() * BYTES_PER_FLOAT,
            vertices,
            GLES20.GL_STATIC_DRAW,
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[1])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            texCoords.capacity() * BYTES_PER_FLOAT,
            texCoords,
            GLES20.GL_STATIC_DRAW,
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[2])
        GLES20.glBufferData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER,
            indices.capacity() * BYTES_PER_INT,
            indices,
            GLES20.GL_STATIC_DRAW,
        )
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
    }

    override fun onSurfaceChanged(
        gl10: GL10?,
        width: Int,
        height: Int,
    ) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl10: GL10?) {
        if (surfaceTexture == null) {
            return
        }
        if (renderedFrame < updatedFrame) {
            surfaceTexture!!.updateTexImage()
            ++renderedFrame
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 2 * BYTES_PER_FLOAT, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[1])
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 2 * BYTES_PER_FLOAT, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[2])
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_INT, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glUseProgram(0)
    }

    override fun setSourcePlayer(exoPlayer: ExoPlayer) {
        createSurfaceTexture()
        exoPlayer.setVideoSurface(Surface(surfaceTexture))
    }

    override fun setScreenSize(
        width: Int,
        height: Int,
    ) {
        if (screenWidth != width || screenHeight != height) {
            screenWidth = width
            screenHeight = height
            if (videoWidth != 0 && videoHeight != 0) {
                maxXOffset = (1.0f - screenWidth.toFloat() / screenHeight / (videoWidth.toFloat() / videoHeight)) / 2
                maxYOffset = (1.0f - screenHeight.toFloat() / screenWidth / (videoHeight.toFloat() / videoWidth)) / 2
            }
            updateMatrix()
        }
    }

    override fun setVideoSizeAndRotation(
        width: Int,
        height: Int,
        rotation: Int,
    ) {
        var w = width
        var h = height
        if (rotation % 180 != 0) {
            val swap = w
            w = h
            h = swap
        }
        if (videoWidth != w || videoHeight != h || videoRotation != rotation) {
            videoWidth = w
            videoHeight = h
            videoRotation = rotation
            if (screenWidth != 0 && screenHeight != 0) {
                maxXOffset = (1.0f - screenWidth.toFloat() / screenHeight / (videoWidth.toFloat() / videoHeight)) / 2
                maxYOffset = (1.0f - screenHeight.toFloat() / screenWidth / (videoHeight.toFloat() / videoWidth)) / 2
            }
            updateMatrix()
        }
    }

    override fun setOffset(
        xOffset: Float,
        yOffset: Float,
    ) {
        var x = xOffset
        var y = yOffset
        if (x > maxXOffset) x = maxXOffset
        if (x < -maxXOffset) x = -maxXOffset
        if (y > maxYOffset) y = maxYOffset
        if (y < -maxXOffset) y = -maxYOffset
        if (this.xOffset != x || this.yOffset != y) {
            this.xOffset = x
            this.yOffset = y
            updateMatrix()
        }
    }

    private fun createSurfaceTexture() {
        if (surfaceTexture != null) {
            surfaceTexture!!.release()
            surfaceTexture = null
        }
        updatedFrame = 0
        renderedFrame = 0
        surfaceTexture = SurfaceTexture(textures[0])
        surfaceTexture!!.setDefaultBufferSize(
            if (videoWidth > 0) videoWidth else 1920,
            if (videoHeight > 0) videoHeight else 1080,
        )
        surfaceTexture!!.setOnFrameAvailableListener { ++updatedFrame }
    }

    private fun updateMatrix() {
        for (i in 0..15) {
            mvp[i] = 0.0f
        }
        mvp[15] = 1.0f
        mvp[10] = mvp[15]
        mvp[5] = mvp[10]
        mvp[0] = mvp[5]
        if (videoWidth == 0 || videoHeight == 0) return
        val videoRatio = videoWidth.toFloat() / videoHeight
        val screenRatio = screenWidth.toFloat() / screenHeight
        if (videoRatio >= screenRatio) {
            Matrix.scaleM(
                mvp,
                0,
                videoWidth.toFloat() / videoHeight / (screenWidth.toFloat() / screenHeight),
                1f,
                1f,
            )
            if (videoRotation % 360 != 0) {
                Matrix.rotateM(mvp, 0, -videoRotation.toFloat(), 0f, 0f, 1f)
            }
            Matrix.translateM(mvp, 0, xOffset, 0f, 0f)
        } else {
            Matrix.scaleM(
                mvp,
                0,
                1f,
                videoHeight.toFloat() / videoWidth / (screenHeight.toFloat() / screenWidth),
                1f,
            )
            if (videoRotation % 360 != 0) {
                Matrix.rotateM(mvp, 0, -videoRotation.toFloat(), 0f, 0f, 1f)
            }
            Matrix.translateM(mvp, 0, 0f, yOffset, 0f)
        }
    }

    companion object {
        private const val TAG = "GLES20WallpaperRenderer"
        private const val BYTES_PER_FLOAT = 4
        private const val BYTES_PER_INT = 4
    }

    init {
        val vertexArray =
            floatArrayOf(
                -1.0f,
                -1.0f,
                -1.0f,
                1.0f,
                1.0f,
                -1.0f,
                1.0f,
                1.0f,
            )
        vertices =
            ByteBuffer
                .allocateDirect(vertexArray.size * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        vertices.put(vertexArray).position(0)

        val texCoordArray =
            floatArrayOf(
                0.0f,
                1.0f,
                0.0f,
                0.0f,
                1.0f,
                1.0f,
                1.0f,
                0.0f,
            )
        texCoords =
            ByteBuffer
                .allocateDirect(texCoordArray.size * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        texCoords.put(texCoordArray).position(0)

        val indexArray = intArrayOf(0, 1, 2, 3, 2, 1)
        indices =
            ByteBuffer
                .allocateDirect(indexArray.size * BYTES_PER_INT)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer()
        indices.put(indexArray).position(0)

        buffers = IntArray(3)
        textures = IntArray(1)
        mvp =
            floatArrayOf(
                1.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                1.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                1.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                1.0f,
            )
    }
}
