package com.mineradio.app.wallpaper

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Live2D 宠物渲染 View（Native SDK + JNI 版）
 *
 * 使用 Cubism SDK for Native 的 C++ Framework，
 * 通过 JNI 桥接调用 native 代码进行模型加载和渲染。
 */
class Live2DPetView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : GLSurfaceView(context, attrs),
        GLSurfaceView.Renderer {
        companion object {
            private const val TAG = "Live2DPetView"
        }

        init {
            // 设置 JNI 上下文
            JniBridgePet.SetContext(context)

            // OpenGL ES 2.0
            setEGLContextClientVersion(2)

            // 配置透明背景（8位 RGBA + 16位深度 + 0位模板）
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderOnTop(true)

            // 设置渲染器
            setRenderer(this)
            renderMode = RENDERMODE_CONTINUOUSLY
        }

        override fun onSurfaceCreated(
            gl: GL10?,
            config: EGLConfig?,
        ) {
            Log.i(TAG, "onSurfaceCreated")
            JniBridgePet.nativeOnSurfaceCreated()
        }

        override fun onSurfaceChanged(
            gl: GL10?,
            width: Int,
            height: Int,
        ) {
            Log.i(TAG, "onSurfaceChanged: ${width}x$height")
            JniBridgePet.nativeOnSurfaceChanged(width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            JniBridgePet.nativeOnDrawFrame()
        }

        /**
         * 触摸事件转发到 native
         */
        fun onTouchBegan(
            x: Float,
            y: Float,
        ) {
            JniBridgePet.nativeOnTouchesBegan(x, y)
        }

        fun onTouchMoved(
            x: Float,
            y: Float,
        ) {
            JniBridgePet.nativeOnTouchesMoved(x, y)
        }

        fun onTouchEnded(
            x: Float,
            y: Float,
        ) {
            JniBridgePet.nativeOnTouchesEnded(x, y)
        }

        /**
         * 启动 native 引擎
         */
        fun startEngine() {
            try {
                JniBridgePet.nativeOnStart()
            } catch (e: Exception) {
                Log.e(TAG, "nativeOnStart failed", e)
            }
        }

        /**
         * 停止 native 引擎
         */
        fun stopEngine() {
            try {
                JniBridgePet.nativeOnStop()
            } catch (e: Exception) {
                Log.e(TAG, "nativeOnStop failed", e)
            }
        }

        /**
         * 销毁 native 引擎
         */
        fun destroyEngine() {
            try {
                JniBridgePet.nativeOnDestroy()
            } catch (e: Exception) {
                Log.e(TAG, "nativeOnDestroy failed", e)
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            destroyEngine()
        }
    }
