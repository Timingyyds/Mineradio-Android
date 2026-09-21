package com.mineradio.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.mineradio.app.inference.GestureRecognizerHelper

/**
 * 全屏手势骨骼叠加层
 * - 21 个关键点: 白色圆点, 5 个指尖红色高亮
 * - MediaPipe 骨骼连线
 * - 顶部状态文字
 */
class GestureOverlayView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        init {
            // ★ 不拦截触摸事件，让下层 WebView 正常响应
            isClickable = false
            isFocusable = false
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false

        private val dotPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 255, 255, 255)
                style = Paint.Style.FILL
            }
        private val fingertipPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 255, 60, 60)
                style = Paint.Style.FILL
            }
        private val linePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(120, 0, 210, 255)
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 48f
                textAlign = Paint.Align.LEFT
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
            }
        private val smallTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 255, 255, 255)
                textSize = 32f
                textAlign = Paint.Align.CENTER
                setShadowLayer(3f, 0f, 0f, Color.BLACK)
            }

        private val skeletonPath = Path()
        private var landmarks: List<GestureRecognizerHelper.LandmarkData>? = null
        private var gestureText: String = ""
        private var score: Float = 0f
        private var viewWidth = 0
        private var viewHeight = 0

        /** 5 个指尖 index */
        private val fingertipIndices = setOf(4, 8, 12, 16, 20)

        /** MediaPipe 手部骨骼连线 */
        private val connections =
            listOf(
                0 to 1,
                1 to 2,
                2 to 3,
                3 to 4, // 拇指
                0 to 5,
                5 to 6,
                6 to 7,
                7 to 8, // 食指
                0 to 9,
                9 to 10,
                10 to 11,
                11 to 12, // 中指
                0 to 13,
                13 to 14,
                14 to 15,
                15 to 16, // 无名指
                0 to 17,
                17 to 18,
                18 to 19,
                19 to 20, // 小指
                5 to 9,
                9 to 13,
                13 to 17, // 指根
            )

        fun update(
            landmarks: List<GestureRecognizerHelper.LandmarkData>?,
            gesture: String?,
            score: Float,
        ) {
            this.landmarks = landmarks
            this.gestureText = gesture ?: ""
            this.score = score
            postInvalidate()
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            viewWidth = w
            viewHeight = h
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (viewWidth == 0 || viewHeight == 0) return

            val lm = landmarks
            if (lm.isNullOrEmpty() || lm.size < 21) return // 无手时不画任何文字

            // 计算画布区域 (居中正方形)
            val canvasSize = minOf(viewWidth, viewHeight).toFloat() * 0.8f
            val offsetX = (viewWidth - canvasSize) / 2f
            val offsetY = (viewHeight - canvasSize) / 2f * 0.6f // 稍偏上

            // 1. 骨骼连线
            for ((i, j) in connections) {
                if (i >= lm.size || j >= lm.size) continue
                val a = lm[i]
                val b = lm[j]
                skeletonPath.reset()
                skeletonPath.moveTo(offsetX + a.x * canvasSize, offsetY + a.y * canvasSize)
                skeletonPath.lineTo(offsetX + b.x * canvasSize, offsetY + b.y * canvasSize)
                canvas.drawPath(skeletonPath, linePaint)
            }

            // 2. 关键点
            var dotRadius = canvasSize * 0.012f
            if (dotRadius < 5f) dotRadius = 5f
            if (dotRadius > 12f) dotRadius = 12f

            for ((idx, p) in lm.withIndex()) {
                val cx = offsetX + p.x * canvasSize
                val cy = offsetY + p.y * canvasSize
                if (idx in fingertipIndices) {
                    canvas.drawCircle(cx, cy, dotRadius * 1.6f, fingertipPaint)
                } else {
                    canvas.drawCircle(cx, cy, dotRadius, dotPaint)
                }
            }

            // 3. 顶部状态文字
            val statusText =
                if (gestureText.isNotEmpty()) {
                    "$gestureText  ${(score * 100).toInt()}%"
                } else {
                    "手已检测"
                }
            canvas.drawText(statusText, 32f, 80f, textPaint)

            // 4. FPS 计数器 (由 JS 维护)
            // (不在这里画, 避免覆盖)
        }
    }
