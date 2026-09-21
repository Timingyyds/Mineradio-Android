package com.mineradio.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * HandLandmarker 封装 — 对标桌面版 MediaPipe Hands
 */
class GestureRecognizerHelper(
    private val context: Context,
    private val listener: Listener,
) {
    companion object {
        private const val TAG = "HandLM"
        private const val MODEL_NAME = "hand_landmarker.task"
    }

    interface Listener {
        fun onResults(
            landmarks: List<LandmarkData>,
            gesture: String?,
            score: Float,
        )

        fun onError(error: String)
    }

    data class LandmarkData(
        val x: Float,
        val y: Float,
        val z: Float,
    )

    private var handLandmarker: HandLandmarker? = null
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "hand-lm") }
    private var frameCount = 0
    private var detectCount = 0
    private var callbackCount = 0
    private var debugSaved = false

    fun load(onReady: (Boolean) -> Unit) {
        executor.execute {
            try {
                // 先检查模型文件是否存在
                val modelAsset = context.assets.open(MODEL_NAME).use { it.readBytes() }
                Log.i(TAG, "Model asset found: ${modelAsset.size} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "Model asset not found: $MODEL_NAME", e)
                onReady(false)
                return@execute
            }

            try {
                val baseOptions =
                    BaseOptions
                        .builder()
                        .setModelAssetPath(MODEL_NAME)
                        .setDelegate(Delegate.GPU)
                        .build()

                val options =
                    HandLandmarker.HandLandmarkerOptions
                        .builder()
                        .setBaseOptions(baseOptions)
                        .setMinHandDetectionConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .setMinHandPresenceConfidence(0.5f)
                        .setNumHands(1)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setResultListener { result, _ ->
                            callbackCount++
                            handleResult(result)
                        }.setErrorListener { err ->
                            Log.e(TAG, "HandLandmarker error", err)
                        }.build()

                handLandmarker = HandLandmarker.createFromOptions(context, options)
                Log.i(TAG, "HandLandmarker GPU ready")
                onReady(true)
            } catch (e: Exception) {
                Log.e(TAG, "GPU failed, trying CPU: ${e.message}", e)
                try {
                    val baseOptions =
                        BaseOptions
                            .builder()
                            .setModelAssetPath(MODEL_NAME)
                            .setDelegate(Delegate.CPU)
                            .build()
                    val options =
                        HandLandmarker.HandLandmarkerOptions
                            .builder()
                            .setBaseOptions(baseOptions)
                            .setMinHandDetectionConfidence(0.5f)
                            .setMinTrackingConfidence(0.5f)
                            .setMinHandPresenceConfidence(0.5f)
                            .setNumHands(1)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setResultListener { result, _ ->
                                callbackCount++
                                handleResult(result)
                            }.setErrorListener { err ->
                                Log.e(TAG, "HandLandmarker error", err)
                            }.build()
                    handLandmarker = HandLandmarker.createFromOptions(context, options)
                    Log.i(TAG, "HandLandmarker CPU ready")
                    onReady(true)
                } catch (e2: Exception) {
                    Log.e(TAG, "CPU also failed: ${e2.message}", e2)
                    onReady(false)
                }
            }
        }
    }

    fun processFrame(imageProxy: ImageProxy) {
        val frameTime = SystemClock.uptimeMillis()
        val w = imageProxy.width
        val h = imageProxy.height
        val rotation = imageProxy.imageInfo.rotationDegrees

        // ★ Google 官方示例写法: RGBA_8888 → Bitmap → 仅旋转，不做镜像
        //    镜像只用于摄像头预览，不能传给模型
        val bitmapBuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
        imageProxy.close()

        val result =
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmapBuffer, 0, 0, w, h, matrix, true)
            } else {
                bitmapBuffer
            }

        // 调试帧: 保存到 /sdcard 方便 adb pull
        if (!debugSaved && frameCount >= 30) {
            try {
                val f = FileOutputStream("/sdcard/hand_debug.jpg")
                result.compress(Bitmap.CompressFormat.JPEG, 85, f)
                f.close()
                Log.i(TAG, "Debug: /sdcard/hand_debug.jpg ${result.width}x${result.height} rot=$rotation orig=${w}x$h")
            } catch (e: Exception) {
                Log.w(TAG, "Debug save failed: ${e.message}")
            }
            debugSaved = true
        }

        val mpImage = BitmapImageBuilder(result).build()
        handLandmarker?.detectAsync(mpImage, frameTime)

        if (frameCount % 60 == 1) {
            Log.i(TAG, "Frame #$frameCount cb=$callbackCount detect=$detectCount → ${result.width}x${result.height} rot=$rotation")
        }
        frameCount++
    }

    private fun handleResult(result: HandLandmarkerResult) {
        try {
            val landmarksList = result.landmarks()
            Log.d(TAG, "Result #$callbackCount: hands=${landmarksList.size}")

            if (landmarksList.isEmpty()) {
                listener.onResults(emptyList(), null, 0f)
                return
            }

            // result.landmarks() → List<List<NormalizedLandmark>>
            // landmarksList[0] 直接就是 List<NormalizedLandmark>
            val rawLms = landmarksList[0]

            val landmarks = mutableListOf<LandmarkData>()
            for (lm in rawLms) {
                try {
                    val cls = lm::class.java
                    val x = cls.getMethod("x").invoke(lm) as? Float ?: 0f
                    val y = cls.getMethod("y").invoke(lm) as? Float ?: 0f
                    val z = cls.getMethod("z").invoke(lm) as? Float ?: 0f
                    landmarks.add(LandmarkData(x, y, z))
                } catch (e: Exception) {
                    try {
                        val cls2 = lm::class.java
                        val x = cls2.getMethod("getX").invoke(lm) as? Float ?: 0f
                        val y = cls2.getMethod("getY").invoke(lm) as? Float ?: 0f
                        val z = cls2.getMethod("getZ").invoke(lm) as? Float ?: 0f
                        landmarks.add(LandmarkData(x, y, z))
                    } catch (_: Exception) {
                    }
                }
            }

            if (landmarks.isNotEmpty()) {
                detectCount++
                if (detectCount % 30 == 1) {
                    Log.i(TAG, "DETECT #$detectCount: ${landmarks.size} lm lm[0]=(${landmarks[0].x},${landmarks[0].y})")
                }
            }

            listener.onResults(landmarks, null, 0f)
        } catch (e: Exception) {
            Log.e(TAG, "handleResult crashed", e)
        }
    }

    fun release() {
        handLandmarker?.close()
        handLandmarker = null
        executor.shutdown()
    }
}
