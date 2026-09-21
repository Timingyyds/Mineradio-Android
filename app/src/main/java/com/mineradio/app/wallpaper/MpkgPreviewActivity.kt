package com.mineradio.app.wallpaper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import io.wallpaperengine.wrapper.SceneLib
import java.io.File

class MpkgPreviewActivity : Activity() {
    companion object {
        private const val TAG = "MpkgPreview"
        const val EXTRA_WALLPAPER_PATH = "wallpaper_path"

        fun launch(
            context: Context,
            wallpaperPath: String,
        ) {
            val intent =
                Intent(context, MpkgPreviewActivity::class.java).apply {
                    putExtra(EXTRA_WALLPAPER_PATH, wallpaperPath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
        }
    }

    private var glSurfaceView: GLSurfaceView? = null
    private var sceneLib: SceneLib? = null
    private var contextId = -1
    private var wallpaperPath: String? = null
    private var isVideoWallpaper = false
    private var videoTextureView: TextureView? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoFileStream: java.io.FileInputStream? = null

    // ★ 场景属性编辑面板相关字段
    private var propertiesPanel: LinearLayout? = null
    private var propertiesToggleBtn: Button? = null
    private var scenePropertiesJson: org.json.JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wallpaperPath = intent.getStringExtra(EXTRA_WALLPAPER_PATH)
        Log.d(TAG, "onCreate: wallpaperPath=$wallpaperPath")

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        val container =
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
            }

        // ★ 视频壁纸使用的 TextureView（初始隐藏）
        videoTextureView =
            TextureView(this).apply {
                visibility = View.GONE
                surfaceTextureListener =
                    object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            Log.d(TAG, "Video TextureView available: ${width}x$height")
                            startVideoPlayback(surface)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            stopVideoPlayback()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        }
                    }
            }

        glSurfaceView =
            GLSurfaceView(this).apply {
                setEGLContextClientVersion(3)
                preserveEGLContextOnPause = true
                setRenderer(
                    object : GLSurfaceView.Renderer {
                        private var frameCount = 0

                        override fun onSurfaceCreated(
                            gl: javax.microedition.khronos.opengles.GL10?,
                            config: javax.microedition.khronos.egl.EGLConfig?,
                        ) {
                            Log.d(TAG, "onSurfaceCreated")
                            try {
                                sceneLib = SceneLib()
                                sceneLib!!.initLibrary(this@MpkgPreviewActivity)
                                // 启用 native 日志（写到 filesDir/Logs/），便于排查黑屏
                                sceneLib!!.setLogToFileEnabled(true)
                                contextId = sceneLib!!.initContext(this@MpkgPreviewActivity)
                                Log.d(TAG, "contextId=$contextId")
                                if (contextId >= 0 && wallpaperPath != null) {
                                    // ★ 获取私有目录路径（native 库需要读取 app 私有目录下的文件）
                                    val privatePath = ensureMpkgInPrivateDir(wallpaperPath!!)
                                    Log.d(TAG, "using path: $privatePath")

                                    // 检查文件可读性
                                    try {
                                        val f = File(privatePath)
                                        Log.d(TAG, "file exists=${f.exists()} readable=${f.canRead()} len=${f.length()}")
                                    } catch (_: Throwable) {
                                    }

                                    // ★ 检查 wallpaper 类型
                                    var wType: String? = null
                                    try {
                                        wType = sceneLib!!.getWallpaperType(privatePath)
                                        Log.d(TAG, "getWallpaperType=$wType")
                                    } catch (e: Throwable) {
                                        Log.e(TAG, "getWallpaperType failed", e)
                                    }

                                    if (wType == "Video") {
                                        // ★ 视频壁纸：切换到 TextureView+MediaPlayer 渲染
                                        Log.d(TAG, "Video wallpaper detected, switching to TextureView")
                                        isVideoWallpaper = true
                                        runOnUiThread {
                                            glSurfaceView?.visibility = View.GONE
                                            videoTextureView?.visibility = View.VISIBLE
                                        }
                                        return
                                    }

                                    // ★ 场景壁纸：继续用 native 库渲染
                                    try {
                                        val valid = sceneLib!!.isWallpaperVersionValid(privatePath)
                                        Log.d(TAG, "isWallpaperVersionValid=$valid")
                                    } catch (e: Throwable) {
                                        Log.e(TAG, "isWallpaperVersionValid failed", e)
                                    }
                                    sceneLib!!.initScene(contextId, privatePath)
                                    Log.d(TAG, "initScene done")
                                    // 确保媒体音量不为0（场景壁纸音频用 STREAM_MUSIC）
                                    val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                                    if (audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) {
                                        audioManager.setStreamVolume(
                                            android.media.AudioManager.STREAM_MUSIC,
                                            audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) / 2,
                                            0,
                                        )
                                    }
                                    try {
                                        val props = sceneLib!!.getSceneProperties(contextId)
                                        Log.d(TAG, "getSceneProperties=$props")
                                        // ★ 解析场景属性并生成编辑控件
                                        if (props != null && props.isNotEmpty()) {
                                            val propsJson = org.json.JSONObject(props)
                                            scenePropertiesJson = propsJson
                                            runOnUiThread { buildPropertiesPanel(propsJson) }
                                        }
                                    } catch (e: Throwable) {
                                        Log.e(TAG, "getSceneProperties failed", e)
                                    }
                                    try {
                                        val flags = sceneLib!!.getSceneFeatureFlags(contextId)
                                        Log.d(TAG, "getSceneFeatureFlags=$flags")
                                    } catch (e: Throwable) {
                                        Log.e(TAG, "getSceneFeatureFlags failed", e)
                                    }
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "onSurfaceCreated failed", e)
                            }
                        }

                        override fun onSurfaceChanged(
                            gl: javax.microedition.khronos.opengles.GL10?,
                            width: Int,
                            height: Int,
                        ) {
                            Log.d(TAG, "onSurfaceChanged ${width}x$height")
                            try {
                                if (contextId >= 0 && !isVideoWallpaper) {
                                    sceneLib?.resizeScene(contextId, width, height)
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "onSurfaceChanged failed", e)
                            }
                        }

                        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
                            try {
                                if (contextId >= 0 && !isVideoWallpaper) {
                                    sceneLib?.updateScene(contextId)
                                    frameCount++
                                    if (frameCount % 60 == 1) {
                                        Log.d(TAG, "onDrawFrame frame=$frameCount ctx=$contextId")
                                    }
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "onDrawFrame failed", e)
                            }
                        }
                    },
                )
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }

        container.addView(
            glSurfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        container.addView(
            videoTextureView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )

        // ★ 场景属性编辑面板切换按钮（右上角齿轮）
        propertiesToggleBtn =
            Button(this).apply {
                text = "⚙"
                textSize = 20f
                setTextColor(Color.WHITE)
                setPadding(20, 10, 20, 10)
                background =
                    GradientDrawable().apply {
                        cornerRadius = 40f
                        setColor(0x99000000.toInt())
                    }
                setOnClickListener {
                    propertiesPanel?.visibility = if (propertiesPanel?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
        container.addView(
            propertiesToggleBtn,
            FrameLayout
                .LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ).apply {
                    marginEnd = 20
                    topMargin = 20
                },
        )

        // ★ 场景属性编辑面板（底部浮动，半透明深蓝色背景，圆角，玻璃模糊材质）
        propertiesPanel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 40, 40, 40)
                visibility = View.GONE
                // 圆角半透明深蓝色背景（玻璃材质效果）
                background =
                    GradientDrawable().apply {
                        cornerRadius = 30f
                        setColor(0xCC1A1A2E.toInt())
                    }
            }
        container.addView(
            propertiesPanel,
            FrameLayout
                .LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ).apply {
                    marginStart = 20
                    marginEnd = 20
                    bottomMargin = 40
                },
        )

        setContentView(container)
    }

    /**
     * 确保 MPKG 文件存在于 app 私有目录
     * native 库 libscenejni.so 需要读取 app 私有目录下的文件
     * 如果文件不在私有目录，自动复制过去
     * @return 私有目录中的文件路径
     */
    private fun ensureMpkgInPrivateDir(originalPath: String): String {
        try {
            // 如果已经在私有目录，直接返回
            if (originalPath.startsWith("/data/data/") || originalPath.startsWith("/data/user/")) {
                return originalPath
            }
            val srcFile = File(originalPath)
            if (!srcFile.exists()) {
                Log.e(TAG, "source mpkg not exists: $originalPath")
                return originalPath
            }
            // 复制到 app 私有目录的 mpkg 子目录
            val mpkgDir = File(filesDir, "mpkg")
            if (!mpkgDir.exists()) mpkgDir.mkdirs()
            val targetFile = File(mpkgDir, "preview_${srcFile.name}")
            if (!targetFile.exists() || targetFile.length() != srcFile.length()) {
                srcFile.copyTo(targetFile, overwrite = true)
                Log.d(TAG, "copied mpkg to private: ${targetFile.absolutePath}")
            }
            return targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "ensureMpkgInPrivateDir failed", e)
            return originalPath
        }
    }

    /**
     * 启动视频播放（视频壁纸）
     * 从 mpkg 中提取视频文件后用 MediaPlayer 播放
     * ★ 使用 FileDescriptor 方式 setDataSource，避免路径含中文/空格导致播放失败
     * ★ 保持 FileInputStream 打开直到 MediaPlayer 释放
     */
    private fun startVideoPlayback(surface: SurfaceTexture) {
        try {
            val privatePath = ensureMpkgInPrivateDir(wallpaperPath ?: return)
            // ★ 先尝试用 MpkgParser 提取 mpkg 内的视频文件
            val videoFile = MpkgParser.extractVideoFile(privatePath, filesDir)
            Log.d(TAG, "startVideoPlayback: videoFile=${videoFile?.absolutePath}")

            // 关闭旧流
            videoFileStream?.close()
            videoFileStream = null

            mediaPlayer =
                MediaPlayer().apply {
                    setSurface(Surface(surface))
                    setLooping(true)
                    setVolume(1f, 1f)
                    if (videoFile != null && videoFile.exists()) {
                        // ★ 使用 FileDescriptor 避免 Chinese 字符路径问题
                        val fis = java.io.FileInputStream(videoFile)
                        videoFileStream = fis
                        setDataSource(fis.fd)
                    } else {
                        // 直接尝试播放 mpkg 文件本身
                        val fis = java.io.FileInputStream(File(privatePath))
                        videoFileStream = fis
                        setDataSource(fis.fd)
                    }
                    setOnPreparedListener { mp ->
                        Log.d(TAG, "MediaPlayer prepared, starting playback")
                        mp.start()
                    }
                    setOnErrorListener { mp, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                        true
                    }
                    prepareAsync()
                }
        } catch (e: Throwable) {
            Log.e(TAG, "startVideoPlayback failed", e)
        }
    }

    private fun stopVideoPlayback() {
        try {
            mediaPlayer?.let {
                it.stop()
                it.release()
            }
            mediaPlayer = null
        } catch (e: Throwable) {
            Log.e(TAG, "stopVideoPlayback failed", e)
        }
        try {
            videoFileStream?.close()
        } catch (e: Throwable) {
            Log.e(TAG, "close videoFileStream failed", e)
        }
        videoFileStream = null
    }

    /**
     * ★ 根据场景属性 JSON 构建编辑面板
     *   支持 color、slider、bool、combo 类型
     */
    private fun buildPropertiesPanel(props: org.json.JSONObject) {
        val panel = propertiesPanel ?: return
        panel.removeAllViews()

        // 标题
        val title =
            TextView(this).apply {
                text = "场景属性"
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, 0, 0, 20)
            }
        panel.addView(title)

        // 遍历所有属性
        val keys = props.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val prop = props.optJSONObject(key) ?: continue
            val type = prop.optString("type", "")
            val propText = prop.optString("text", key)
            val value = prop.opt("value")

            // 属性标题
            val label =
                TextView(this).apply {
                    setTextColor(Color.parseColor("#AABBCC"))
                    textSize = 13f
                    setPadding(0, 10, 0, 5)
                    text = propText
                }
            panel.addView(label)

            when (type) {
                "color" -> {
                    // 颜色值格式 "R G B"（0-1 范围）
                    val parts = (value as? String ?: "1 1 1").split(" ")
                    val r = parts.getOrNull(0)?.toFloatOrNull() ?: 1f
                    val g = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
                    val b = parts.getOrNull(2)?.toFloatOrNull() ?: 1f

                    // 颜色预览
                    val colorPreview =
                        View(this).apply {
                            setBackgroundColor(Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt()))
                            layoutParams =
                                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 60).apply {
                                    bottomMargin = 10
                                }
                        }
                    panel.addView(colorPreview)

                    // RGB 滑块
                    val colorValues = floatArrayOf(r, g, b)
                    val colorLabels = listOf("R", "G", "B")
                    for (i in 0..2) {
                        val row =
                            LinearLayout(this@MpkgPreviewActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                val lbl =
                                    TextView(this@MpkgPreviewActivity).apply {
                                        text = colorLabels[i]
                                        setTextColor(Color.WHITE)
                                        textSize = 12f
                                        setPadding(0, 0, 20, 0)
                                        layoutParams = LinearLayout.LayoutParams(80, LinearLayout.LayoutParams.WRAP_CONTENT)
                                    }
                                val sb =
                                    SeekBar(this@MpkgPreviewActivity).apply {
                                        max = 255
                                        progress = (colorValues[i] * 255).toInt()
                                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                        setOnSeekBarChangeListener(
                                            object : SeekBar.OnSeekBarChangeListener {
                                                override fun onProgressChanged(
                                                    seekBar: SeekBar?,
                                                    progress: Int,
                                                    fromUser: Boolean,
                                                ) {
                                                    if (fromUser) {
                                                        colorValues[i] = progress / 255f
                                                        colorPreview.setBackgroundColor(
                                                            Color.rgb(
                                                                (colorValues[0] * 255).toInt(),
                                                                (colorValues[1] * 255).toInt(),
                                                                (colorValues[2] * 255).toInt(),
                                                            ),
                                                        )
                                                        // 更新属性值
                                                        val newValue = "${colorValues[0]} ${colorValues[1]} ${colorValues[2]}"
                                                        updateSceneProperty(key, newValue)
                                                    }
                                                }

                                                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                                                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                                            },
                                        )
                                    }
                                addView(lbl)
                                addView(sb)
                            }
                        panel.addView(row)
                    }
                }
                "slider" -> {
                    val sliderMin = prop.optDouble("min", 0.0).toFloat()
                    val sliderMax = prop.optDouble("max", 1.0).toFloat()
                    val curVal = (value as? Number)?.toFloat() ?: sliderMin
                    val valueLabel =
                        TextView(this).apply {
                            setTextColor(Color.WHITE)
                            textSize = 12f
                            text = String.format("%.2f", curVal)
                        }
                    val sb =
                        SeekBar(this).apply {
                            max = 1000
                            progress = ((curVal - sliderMin) / (sliderMax - sliderMin) * 1000).toInt()
                            setOnSeekBarChangeListener(
                                object : SeekBar.OnSeekBarChangeListener {
                                    override fun onProgressChanged(
                                        seekBar: SeekBar?,
                                        progress: Int,
                                        fromUser: Boolean,
                                    ) {
                                        if (fromUser) {
                                            val v = sliderMin + (sliderMax - sliderMin) * progress / 1000f
                                            valueLabel.text = String.format("%.2f", v)
                                            updateSceneProperty(key, v.toDouble())
                                        }
                                    }

                                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                                },
                            )
                        }
                    panel.addView(sb)
                    panel.addView(valueLabel)
                }
                "bool" -> {
                    val sw =
                        Switch(this).apply {
                            text = propText
                            setTextColor(Color.WHITE)
                            isChecked = value as? Boolean ?: false
                            setOnCheckedChangeListener { _, isChecked ->
                                updateSceneProperty(key, isChecked)
                            }
                        }
                    panel.addView(sw)
                }
                else -> {
                    // 其他类型用文本显示
                    val tv =
                        TextView(this).apply {
                            setTextColor(Color.WHITE)
                            textSize = 12f
                            text = "$type: $value"
                        }
                    panel.addView(tv)
                }
            }
        }

        // 如果没有属性
        if (panel.childCount <= 1) {
            val noProps =
                TextView(this).apply {
                    text = "此壁纸无可编辑属性"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                }
            panel.addView(noProps)
        }
    }

    /**
     * ★ 更新场景属性并实时应用
     */
    private fun updateSceneProperty(
        key: String,
        newValue: Any,
    ) {
        try {
            val props = scenePropertiesJson ?: return
            val prop = props.optJSONObject(key) ?: return
            prop.put("value", newValue)

            // 在 GL 线程中应用属性
            glSurfaceView?.queueEvent {
                try {
                    val jsonStr = props.toString()
                    sceneLib?.applySceneProperties(contextId, jsonStr)
                    Log.d(TAG, "applySceneProperties: $key=$newValue")
                } catch (e: Throwable) {
                    Log.e(TAG, "applySceneProperties failed", e)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "updateSceneProperty failed", e)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event != null && contextId >= 0 && !isVideoWallpaper) {
            try {
                val down = event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE
                val x = event.x
                val y = event.y
                // 发送触摸输入
                sceneLib?.sendTouchInput(contextId, down, x, y)
                // 发送视差偏移（让场景视角跟随触摸位置移动）
                val normX = (x / glSurfaceView!!.width) * 2f - 1f
                val normY = (y / glSurfaceView!!.height) * 2f - 1f
                sceneLib?.sendNormalizedParallaxOffset(contextId, down, normX, normY, normX, normY)
            } catch (e: Throwable) {
                Log.e(TAG, "onTouchEvent failed", e)
            }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView?.onResume()
    }

    override fun onPause() {
        glSurfaceView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopVideoPlayback()
        try {
            if (contextId >= 0) {
                sceneLib?.shutdownScene(contextId)
                sceneLib?.destroyContext(contextId)
                contextId = -1
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onDestroy cleanup failed", e)
        }
        super.onDestroy()
    }
}
