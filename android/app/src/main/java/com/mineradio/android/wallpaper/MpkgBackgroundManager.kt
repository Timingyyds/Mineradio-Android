package com.mineradio.android.wallpaper

import android.app.Activity
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import io.wallpaperengine.wrapper.SceneLib
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * MPKG 场景壁纸的「程序内背景」渲染器（精简重写版）。
 *
 * 参考原生工程 LandscapeWebActivity 的做法，但只保留让画面显示出来的必备环节：
 *
 *   CoordinatorLayout
 *     ├── GLSurfaceView   ← 本类添加，索引 0（最底）
 *     └── WebView         ← Capacitor 的，背景设为透明
 *
 * 必备条件（缺一个就黑屏/看不见）：
 *   1. GLSurfaceView 加到 WebView 所在容器的索引 0
 *   2. WebView 自身背景设为透明
 *   3. 页面所有背景层通过 CSS 注入强制透明（body.mpkg-scene-active）
 *   4. surfaceCreated 之后才 initScene
 *
 * 刻意不包含（原生工程里有、但和"能不能看见"无关）：
 *   陀螺仪视差、音频可视化、场景参数面板、SceneStretchRenderer 拉伸校正。
 *   这些是画面细节增强，可以后续再补。
 */
object MpkgBackgroundManager {

    private const val TAG = "MpkgBg"

    /**
     * 文件日志。
     *
     * 为什么不用 logcat：OPPO/ColorOS 对每个进程有 300 行日志配额
     * （logcat 里会出现 "LOG_FLOWCTRL: LOGS OVER PROC QUOTA ... DROPPED"），
     * 超出的全部丢弃 —— 排查黑屏时最关键的几行恰好被丢掉。
     * 这里直接写 App 私有目录，root 可以读出来。
     */
    private var logFile: java.io.File? = null

    private fun logToFile(msg: String) {
        try {
            val f = logFile ?: return
            val line = "${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg\n"
            f.appendText(line)
        } catch (e: Throwable) {
            // 日志失败不能影响渲染
        }
    }

    private fun initLogFile(act: Activity) {
        try {
            logFile = java.io.File(act.filesDir, "mpkgbg.log").apply {
                if (exists()) delete()
                writeText("")
            }
            logToFile("=== MpkgBackgroundManager log start ===")
        } catch (e: Throwable) {
        }
    }

    private fun logD(msg: String) {
        Log.d(TAG, msg)
        logToFile("D $msg")
    }

    private fun logE(msg: String, e: Throwable? = null) {
        Log.e(TAG, msg, e)
        logToFile("E $msg" + (e?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    private var activity: Activity? = null
    private var webView: WebView? = null

    private var sceneLib: SceneLib? = null
    private var contextId = -1

    // ─── 陀螺仪视差（横屏手机场景需要） ─────────────────────────────
    // 逻辑与 LWService 里的 ParallaxController 一致：
    //   TYPE_GYROSCOPE → 按屏幕旋转矩阵换算 → sendNormalizedParallaxOffset。
    // 只有场景 featureFlags 声明了 parallax（bit 2）时才真正发送，否则忽略。
    private var sensorManager: android.hardware.SensorManager? = null
    private var gyroListener: android.hardware.SensorEventListener? = null
    @Volatile private var parallaxX = 0f
    @Volatile private var parallaxY = 0f
    @Volatile private var sensorRegistered = false
    private var sensorMatrix = floatArrayOf(1f, 1f, -1f, 0f, 0f, 0f)

    private fun updateSensorMatrix() {
        try {
            val rotation = if (android.os.Build.VERSION.SDK_INT >= 30) {
                activity?.display?.rotation ?: 0
            } else {
                @Suppress("DEPRECATION")
                ((activity?.getSystemService(android.content.Context.WINDOW_SERVICE)) as? android.view.WindowManager)
                    ?.defaultDisplay?.rotation ?: 0
            }
            sensorMatrix = when (rotation) {
                1 -> floatArrayOf(0f, 0f, -1f, 1f, -1f, 0f)
                2 -> floatArrayOf(-1f, -1f, -1f, 0f, 0f, 0f)
                3 -> floatArrayOf(0f, 0f, -1f, -1f, 1f, 0f)
                else -> floatArrayOf(1f, 1f, -1f, 0f, 0f, 0f)
            }
        } catch (_: Throwable) { }
    }

    /** 注册陀螺仪（前台时调用）。效果只在支持 parallax 的场景上生效。 */
    fun startParallaxSensor() {
        if (sensorRegistered) return
        val act = activity ?: return
        try {
            val sm = act.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
            val gyro = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
            if (gyro == null) {
                logD("gyroscope sensor not available")
                return
            }
            updateSensorMatrix()
            // 与 LWService 一致：strength=1.0 → factor=0.225
            val factor = 0.225f
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent) {
                    if (!active || event.values.size < 2) return
                    val rawX = event.values[1] * factor
                    val rawY = -event.values[0] * factor * 0.5f
                    parallaxX = (rawX * sensorMatrix[0]) + (rawY * sensorMatrix[4])
                    parallaxY = (rawY * sensorMatrix[1]) + (rawX * sensorMatrix[3])
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) { }
            }
            sm.registerListener(listener, gyro, android.hardware.SensorManager.SENSOR_DELAY_GAME)
            sensorManager = sm
            gyroListener = listener
            sensorRegistered = true
            logD("parallax sensor registered: ${gyro.name}")
        } catch (e: Throwable) {
            logE("start parallax sensor failed", e)
        }
    }

    fun stopParallaxSensor() {
        try {
            val sm = sensorManager
            val listener = gyroListener
            if (sm != null && listener != null) sm.unregisterListener(listener)
        } catch (_: Throwable) { }
        sensorManager = null
        gyroListener = null
        sensorRegistered = false
        parallaxX = 0f
        parallaxY = 0f
    }
    private var sceneReady = false
    private var pendingPath: String? = null
    /** 当前场景的完整属性表（用户在「项目设置」里调整时基于它改值） */
    private var currentProps: org.json.JSONObject? = null
    // 旧的 GLSurfaceView 渲染路径已停用（本机 SurfaceView 不可见），保留字段仅为兼容旧代码
    private var glView: GLSurfaceView? = null
    private var surfaceCreated = false

    @Volatile
    var active = false
        private set

    // ───────────────────────────────────────────────────────────
    // 初始化
    // ───────────────────────────────────────────────────────────

    /**
     * 窗口 / DecorView 背景必须是透明的。
     *
     * GLSurfaceView 是 SurfaceView：它的画面在「窗口之下」，窗口上对应区域只要被画上
     * 不透明底色，场景壁纸就整片看不见（视频壁纸用 TextureView 是普通 View，所以一直正常）。
     * Capacitor 的 SystemBars 插件在启动/切换状态栏样式时会读主题 windowBackground
     * 回填 DecorView 背景，所以这里在 init / start / onResume 都兜底设一次。
     */
    private fun ensureWindowTransparent(act: Activity) {
        try {
            act.window?.setBackgroundDrawableResource(android.R.color.transparent)
            act.window?.decorView?.setBackgroundColor(Color.TRANSPARENT)
        } catch (e: Throwable) {
            logE("ensureWindowTransparent failed", e)
        }
    }

    // ───────────────────────────────────────────────────────────
    // 场景渲染：TextureView + 自建 EGL
    //
    // 为什么不是 GLSurfaceView：SurfaceView 的画面在「窗口之下」，
    // 这台机器（ColorOS / Android 16）上实机验证无论怎么设窗口透明都看不到场景；
    // 而视频壁纸用的 TextureView 是普通 View，一直能正常显示。
    // 所以场景也走 TextureView：自建 EGL 上下文，把 libscenejni 的输出渲染到
    // SurfaceTexture 上，与视频壁纸同一条合成路径。
    // ───────────────────────────────────────────────────────────

    private var sceneTextureView: android.view.TextureView? = null
    private var sceneSession: SceneGlSession? = null

    /** mpkg 类型判定缓存：key = path|length|lastModified，避免同一张壁纸每次点击都重新解析条目表。 */
    private val mpkgIsVideoCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    @Volatile private var scenePaused = false

    private var eglDisplay: android.opengl.EGLDisplay? = null
    private var eglContextObj: android.opengl.EGLContext? = null
    private var eglSurfaceObj: android.opengl.EGLSurface? = null

    private val sceneCommands = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    /** 创建场景 TextureView 并垫到 WebView 之下（index 0）。 */
    private fun startSceneTexture(act: Activity, web: WebView, path: String) {
        val parent = web.parent as? ViewGroup
        if (parent == null) {
            logE("webView has no parent ViewGroup")
            return
        }
        teardownSceneTexture()
        pendingPath = path
        val tv = android.view.TextureView(act).apply {
            layoutParams = androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    logD("scene TextureView available ${width}x$height")
                    startSceneGlThread(st, width, height)
                }

                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    logD("scene TextureView size changed ${width}x$height")
                    sceneCommands.add { resizeSceneIfReady(width, height) }
                }

                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                    logD("scene TextureView destroyed")
                    stopSceneGlThread()
                    return true
                }

                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
            }
        }
        sceneTextureView = tv
        parent.setBackgroundColor(Color.TRANSPARENT)
        parent.addView(tv, 0)
        tv.visibility = View.VISIBLE
        logD("scene TextureView added at index 0 of ${parent.javaClass.simpleName}")
        injectTransparentCss()
        web.postDelayed({ if (active) injectTransparentCss() }, 800)
    }

    /** 每个场景渲染线程一个会话：running 是线程私有的，避免新旧线程共用同一个标志互相干扰。 */
    private class SceneGlSession {
        @Volatile var running = true
        var thread: Thread? = null
    }

    private fun startSceneGlThread(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
        val act = activity ?: return
        val path = pendingPath ?: return
        // 先让旧会话停下，但不在 UI 线程 join —— 新线程启动后自己等旧的退出，
        // 这样切换壁纸时 UI 不会卡住，也不会出现两个 SceneLib context 同时活跃（native 会乱）。
        val old = sceneSession
        old?.running = false
        val session = SceneGlSession()
        sceneSession = session
        scenePaused = false
        frameCount = 0
        coverCaptured = false
        val t = Thread {
            old?.thread?.let { prev ->
                if (prev.isAlive) {
                    try { prev.join(2500) } catch (e: Throwable) { }
                }
            }
            var myContextId = -1
            var myReady = false
            val blitter = SceneBlitter()
            try {
                if (!createEgl(st)) {
                    logE("createEgl failed")
                    return@Thread
                }
                sceneLib = SceneLib
                SceneLib.initLibrary(act)
                myContextId = SceneLib.initContext(act)
                logD("sceneGlThread: contextId=$myContextId")
                if (myContextId >= 0) {
                    markInitStart(act, path)
                    SceneLib.initScene(myContextId, path)
                    try { SceneLib.setLogToFileEnabled(false) } catch (e: Throwable) { }
                    myReady = true
                    logD("initScene ok: $path")
                    applyParallaxOnly(myContextId)
                    // ★ 按壁纸原始比例「完整显示」：先算等比缩放后的尺寸，再交给 native 渲染。
                    //   native 会把 viewport 设成 resizeScene 的尺寸并画在左下角，
                    //   所以下面每帧再把这块内容居中画到屏幕（留黑边，不裁切、不拉伸）。
                    updateRenderSize(width, height)
                    SceneLib.resizeScene(myContextId, sceneRenderW, sceneRenderH)
                    logD("resizeScene done ${sceneRenderW}x${sceneRenderH} (screen ${width}x$height)")
                    // 恢复用户上次保存的场景参数（不覆盖 alignment / 视差）
                    try {
                        val saved = scenePropertiesForContext(myContextId)
                        if (saved != null) {
                            saved.remove("alignment")
                            saved.remove("mobileparallax")
                            saved.remove("mobileparallaxstrength")
                            currentProps = saved
                            SceneLib.applySceneProperties(myContextId, saved.toString())
                            logD("saved scene props applied")
                        }
                    } catch (e: Throwable) {
                        logE("apply saved scene props failed", e)
                    }
                    if (!blitter.init()) logE("blitter init failed")
                }
            } catch (e: Throwable) {
                logE("scene init failed", e)
            }
            if (sceneSession === session) {
                contextId = myContextId
                sceneReady = myReady
            }
            var lastFrame = 0L
            var swapFailCount = 0
            while (session.running) {
                try {
                    while (true) {
                        val cmd = sceneCommands.poll() ?: break
                        cmd()
                    }
                    if (scenePaused) {
                        Thread.sleep(120)
                        continue
                    }
                    if (myContextId >= 0 && myReady) {
                        SceneLib.updateScene(myContextId)
                        if (sceneSupportsParallax) {
                            // 陀螺仪视差：每帧把传感器换算后的偏移交给场景引擎
                            try {
                                SceneLib.sendNormalizedParallaxOffset(
                                    myContextId, true, parallaxX, parallaxY, 0.5f, 0.06f,
                                )
                            } catch (e: Throwable) { }
                        }
                        if (sceneSupportsAudio) {
                            try { SceneLib.sendAudioData(myContextId, silentFft) } catch (e: Throwable) { }
                        }
                        if (blitter.ready) {
                            val dst = blitter.copyAndDrawCentered(
                                sceneRenderW, sceneRenderH, sceneScreenW, sceneScreenH, sceneRotate,
                            )
                            if (dst != null && frameCount == 29 && !coverCaptured) {
                                coverCaptured = true
                                try { captureSceneCover(path, dst[0], dst[1], dst[2], dst[3]) } catch (e: Throwable) {
                                    logE("captureSceneCover failed", e)
                                }
                            }
                        }
                        if (!swapEgl()) {
                            swapFailCount += 1
                            // 切后台/分屏时 Surface 会失效，逐帧刷日志没用还会把 mpkgbg.log 写爆
                            if (swapFailCount == 1 || swapFailCount % 300 == 0) {
                                logE("eglSwapBuffers failed (count=$swapFailCount)")
                            }
                            if (swapFailCount >= 30) {
                                // 连续失败说明 Surface 暂时不可用：先暂停渲染，onResume 时重置
                                logE("surface unavailable, pause render until resume")
                                scenePaused = true
                            }
                        } else {
                            swapFailCount = 0
                        }
                        frameCount += 1
                        if (frameCount == 60) {
                            try { markInitSuccess(act) } catch (e: Throwable) { }
                        }
                    } else {
                        Thread.sleep(16)
                    }
                } catch (e: Throwable) {
                    if (!drawErrorLogged) {
                        drawErrorLogged = true
                        logE("scene frame failed", e)
                    }
                    Thread.sleep(16)
                }
                val now = android.os.SystemClock.uptimeMillis()
                val delta = now - lastFrame
                if (delta in 1L..15L) Thread.sleep(16 - delta)
                lastFrame = now
            }
            try {
                if (myContextId >= 0) {
                    if (myReady) {
                        try { SceneLib.shutdownScene(myContextId) } catch (e: Throwable) { }
                    }
                    try { SceneLib.destroyContext(myContextId) } catch (e: Throwable) { }
                }
            } catch (e: Throwable) { }
            try { blitter.release() } catch (e: Throwable) { }
            if (sceneSession === session) {
                contextId = -1
                sceneReady = false
            }
            releaseEgl()
            logD("sceneGlThread exit")
        }
        t.name = "MpkgSceneGl"
        session.thread = t
        t.start()
    }

    /** 渲染尺寸：按壁纸原始比例完整装进屏幕（高度贴边，宽度按比例），再由 blit 横向铺满。 */
    @Volatile private var sceneRenderW = 0
    @Volatile private var sceneRenderH = 0
    @Volatile private var sceneScreenW = 0
    @Volatile private var sceneScreenH = 0
    @Volatile private var sceneRotate = false

    private fun updateRenderSize(screenW: Int, screenH: Int) {
        if (screenW <= 0 || screenH <= 0) return
        // native 会按 resizeScene 的尺寸裁切画面，所以这里先给它一个「等比装进屏幕」的尺寸，
        // 保证整张壁纸都被渲染出来（上下不丢内容）；
        // 随后 blit 再把这张完整画面横向拉到屏幕宽度，上下高度保持不变。
        val ratio = if (sceneCanvasW > 0f && sceneCanvasH > 0f) sceneCanvasW / sceneCanvasH else 16f / 9f
        val screenRatio = screenW.toFloat() / screenH.toFloat()
        val rw: Int
        val rh: Int
        if (ratio > screenRatio) {
            rw = screenW
            rh = (screenW / ratio).toInt().coerceAtLeast(1)
        } else {
            rh = screenH
            rw = (screenH * ratio).toInt().coerceAtLeast(1)
        }

        sceneRenderW = rw
        sceneRenderH = rh
        sceneScreenW = screenW
        sceneScreenH = screenH
        // 竖构图的长条手机壁纸在横屏下整体旋转 90°，让画面正向铺满
        sceneRotate = ratio < 1f && screenRatio > 1f
        logD("render size: fit ${rw}x$rh -> stretch to ${screenW}x$screenH rotate=$sceneRotate")
    }

    private fun resizeSceneIfReady(width: Int, height: Int) {
        if (contextId >= 0 && sceneReady) {
            try {
                updateRenderSize(width, height)
                SceneLib.resizeScene(contextId, sceneRenderW, sceneRenderH)
                logD("resizeScene done ${sceneRenderW}x${sceneRenderH}")
            } catch (e: Throwable) {
                logE("resizeScene failed", e)
            }
        }
    }

    // ───────────────────────────────────────────────────────────
    // 场景参数（壁纸面板里的「项目设置」）
    // ───────────────────────────────────────────────────────────

    @JvmStatic
    fun isSceneActive(): Boolean = active && mediaPlayer == null && contextId >= 0 && sceneReady

    /** 在渲染线程同步执行：SceneLib 的属性读写必须发生在 GL 上下文的线程里。 */
    private fun runOnGlSync(timeoutMs: Long, block: () -> Unit): Boolean {
        if (sceneSession?.running != true) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        sceneCommands.add {
            try { block() } finally { latch.countDown() }
        }
        return try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            false
        }
    }

    private fun scenePropsFile(): java.io.File? {
        val act = activity ?: return null
        val path = pendingPath ?: return null
        val dir = java.io.File(act.filesDir, "scene_props").apply { mkdirs() }
        return java.io.File(dir, java.io.File(path).nameWithoutExtension + ".json")
    }

    /** 把用户保存的参数值合并进默认属性表。 */
    private fun loadScenePropsFromFile(json: org.json.JSONObject) {
        val f = scenePropsFile() ?: return
        if (!f.exists()) return
        try {
            val saved = org.json.JSONObject(f.readText())
            val keys = saved.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val target = json.optJSONObject(k) ?: continue
                target.put("value", saved.get(k))
            }
        } catch (e: Throwable) {
            logE("loadSceneProps failed", e)
        }
    }

    private fun saveScenePropsToFile(json: org.json.JSONObject) {
        val f = scenePropsFile() ?: return
        try {
            f.writeText(json.toString())
        } catch (e: Throwable) {
            logE("saveSceneProps failed", e)
        }
    }

    /** 场景属性表 → 前端面板消费的数组格式。 */
    private fun propsToArrayJson(json: org.json.JSONObject): String {
        val lib = sceneLib
        return try {
            val result = org.json.JSONArray()
            val keys = mutableListOf<String>()
            val it = json.keys()
            while (it.hasNext()) keys.add(it.next())
            keys.sortBy { k -> try { json.getJSONObject(k).optInt("order", 0) } catch (e: Throwable) { 0 } }
            for (key in keys) {
                try {
                    val prop = json.getJSONObject(key)
                    if (prop.optString("condition", "") == "false") continue
                    val type = prop.optString("type", "")
                    if (type.isEmpty()) continue
                    val item = org.json.JSONObject()
                    item.put("key", key)
                    item.put("type", type)
                    var text = prop.optString("text", "")
                    if (text.isNotEmpty() && lib != null) {
                        try {
                            val loc = lib.getLocalization(text)
                            if (!loc.isNullOrEmpty()) text = loc
                        } catch (e: Throwable) { }
                    }
                    if (text.isEmpty()) text = key
                    // 场景包里不少 text 字段是 i18n key / 带 URL / 带 HTML 标签（<BIG><BR/>）的标记文本，
                    // 直接显示就是「链接」「特殊字母符号」。统一剥掉再用参数名兜底。
                    if (text.contains("http", true) || text.contains("www.", true)) text = key
                    text = text.replace(Regex("<[^>]*>"), "")
                    text = text.replace(Regex("&[a-zA-Z#0-9]+;"), "")
                    text = text.replace(Regex("^[^\\p{L}\\p{N}]+"), "")
                    text = text.replace(Regex("[\\uFFFD\\u0000-\\u001f]"), " ").replace(Regex("\\s+"), " ").trim()
                    if (text.isEmpty()) text = key
                    item.put("text", text)
                    for (field in arrayOf("value", "min", "max", "step", "precision")) {
                        if (prop.has(field)) item.put(field, prop.get(field))
                    }
                    if (prop.has("options")) {
                        val opts = prop.getJSONArray("options")
                        val outOpts = org.json.JSONArray()
                        for (i in 0 until opts.length()) {
                            val o = opts.optJSONObject(i) ?: continue
                            val oo = org.json.JSONObject(o.toString())
                            val label = o.optString("label", "")
                            if (label.isNotEmpty() && lib != null) {
                                try {
                                    val loc = lib.getLocalization(label)
                                    if (!loc.isNullOrEmpty()) oo.put("label", loc)
                                } catch (e: Throwable) { }
                            }
                            val cleanLabel = oo.optString("label", "")
                                .replace(Regex("<[^>]*>"), "")
                                .replace(Regex("&[a-zA-Z#0-9]+;"), "")
                                .replace(Regex("[\\uFFFD\\u0000-\\u001f]"), " ").trim()
                            if (cleanLabel.isNotEmpty()) oo.put("label", cleanLabel)
                            outOpts.put(oo)
                        }
                        item.put("options", outOpts)
                    }
                    result.put(item)
                } catch (e: Throwable) { }
            }
            result.toString()
        } catch (e: Throwable) {
            logE("propsToArrayJson failed", e)
            "[]"
        }
    }

    /** 读取当前场景的可调参数（JSON 数组字符串）。 */
    @JvmStatic
    fun getScenePropertiesArrayJson(): String {
        if (!isSceneActive()) return "[]"
        var out = "[]"
        runOnGlSync(2500) {
            val cid = contextId
            val json = scenePropertiesForContext(cid)
            if (json != null) {
                currentProps = json
                out = propsToArrayJson(json)
            }
        }
        return out
    }

    /** 实时应用单个参数（滑块 / 开关 / 选项 / 颜色）。 */
    @JvmStatic
    fun applySceneProperty(key: String, value: String) {
        if (!isSceneActive()) return
        runOnGlSync(2000) {
            val cid = contextId
            val json = currentProps ?: scenePropertiesForContext(cid)?.also { currentProps = it } ?: return@runOnGlSync
            val prop = json.optJSONObject(key) ?: return@runOnGlSync
            val original = prop.opt("value")
            when {
                original is Boolean -> prop.put("value", value == "true" || value == "1")
                original is Int || original is Long -> prop.put("value", value.toIntOrNull() ?: 0)
                original is Number -> prop.put("value", value.toDoubleOrNull() ?: 0.0)
                else -> prop.put("value", value)
            }
            sceneLib?.applySceneProperties(cid, json.toString())
            saveScenePropsToFile(json)
            logD("applySceneProperty: $key=$value")
        }
    }

    /** 重置当前场景参数到默认值，返回新的参数数组。 */
    @JvmStatic
    fun resetSceneProperties(): String {
        if (!isSceneActive()) return "[]"
        var out = "[]"
        runOnGlSync(2500) {
            val cid = contextId
            try {
                scenePropsFile()?.let { if (it.exists()) it.delete() }
                val lib = sceneLib ?: return@runOnGlSync
                try { lib.setLanguage("zh-CN") } catch (e: Throwable) { }
                val json = scenePropertiesForContext(cid) ?: return@runOnGlSync
                currentProps = json
                lib.applySceneProperties(cid, json.toString())
                out = propsToArrayJson(json)
            } catch (e: Throwable) {
                logE("resetSceneProperties failed", e)
            }
        }
        return out
    }

    /** GL 线程内读取场景属性（含用户保存值合并）。 */
    private fun scenePropertiesForContext(cid: Int): org.json.JSONObject? {
        val lib = sceneLib ?: return null
        return try {
            try { lib.setLanguage("zh-CN") } catch (e: Throwable) { }
            val raw = lib.getSceneProperties(cid)
            if (raw.isNullOrEmpty()) return null
            val json = org.json.JSONObject(raw)
            loadScenePropsFromFile(json)
            json
        } catch (e: Throwable) {
            logE("scenePropertiesForContext failed", e)
            null
        }
    }

    /**
     * 抓当前 GL 帧里的「壁纸区域」存成封面 JPEG（壁纸面板的预览图）。
     * 只抓居中显示的那块（不含黑边），glReadPixels 必须在 GL 线程、当前上下文里调用。
     */
    private fun captureSceneCover(path: String, x: Int, y: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val act = activity ?: return
        val targetW = 480
        val targetH = (height.toFloat() / width.toFloat() * targetW).toInt().coerceAtLeast(1)
        val buf = java.nio.ByteBuffer.allocateDirect(width * height * 4)
        android.opengl.GLES20.glReadPixels(
            x, y, width, height,
            android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, buf,
        )
        buf.rewind()
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        val m = android.graphics.Matrix().apply {
            preScale(1f, -1f) // GL 原点在左下，垂直翻转
            postScale(targetW.toFloat() / width, targetH.toFloat() / height)
        }
        val flipped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, width, height, m, true)
        bmp.recycle()
        val coverDir = java.io.File(act.filesDir, "wallpaper_covers").apply { mkdirs() }
        val coverFile = java.io.File(coverDir, java.io.File(path).nameWithoutExtension + ".jpg")
        try {
            java.io.FileOutputStream(coverFile).use { fos ->
                flipped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, fos)
            }
            logD("cover saved: ${coverFile.absolutePath}")
        } finally {
            flipped.recycle()
        }
    }

    private fun stopSceneGlThread() {
        // 不阻塞 UI：只置停止标志，线程自己退出后释放 native 场景与 EGL
        sceneSession?.running = false
        sceneSession = null
        sceneCommands.clear()
    }

    private fun teardownSceneTexture() {
        stopSceneGlThread()
        sceneTextureView?.let { tv ->
            try { tv.surfaceTextureListener = null } catch (e: Throwable) { }
            tv.visibility = View.GONE
            (tv.parent as? ViewGroup)?.removeView(tv)
        }
        sceneTextureView = null
    }

    private fun createEgl(st: android.graphics.SurfaceTexture): Boolean {
        try {
            val d = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            if (d == android.opengl.EGL14.EGL_NO_DISPLAY) return false
            val ver = IntArray(2)
            if (!android.opengl.EGL14.eglInitialize(d, ver, 0, ver, 1)) return false
            val attribs = intArrayOf(
                android.opengl.EGL14.EGL_RENDERABLE_TYPE, 0x00000040, // EGL_OPENGL_ES3_BIT_KHR
                android.opengl.EGL14.EGL_RED_SIZE, 8,
                android.opengl.EGL14.EGL_GREEN_SIZE, 8,
                android.opengl.EGL14.EGL_BLUE_SIZE, 8,
                android.opengl.EGL14.EGL_ALPHA_SIZE, 8,
                android.opengl.EGL14.EGL_DEPTH_SIZE, 16,
                android.opengl.EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val num = IntArray(1)
            if (!android.opengl.EGL14.eglChooseConfig(d, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) return false
            val cfg = configs[0] ?: return false
            val ctx = android.opengl.EGL14.eglCreateContext(
                d, cfg, android.opengl.EGL14.EGL_NO_CONTEXT,
                intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, android.opengl.EGL14.EGL_NONE), 0,
            )
            if (ctx == null || ctx == android.opengl.EGL14.EGL_NO_CONTEXT) return false
            val surf = android.opengl.EGL14.eglCreateWindowSurface(d, cfg, st, intArrayOf(android.opengl.EGL14.EGL_NONE), 0)
            if (surf == null || surf == android.opengl.EGL14.EGL_NO_SURFACE) return false
            if (!android.opengl.EGL14.eglMakeCurrent(d, surf, surf, ctx)) return false
            eglDisplay = d
            eglContextObj = ctx
            eglSurfaceObj = surf
            logD("EGL ready (ES3)")
            return true
        } catch (e: Throwable) {
            logE("createEgl exception", e)
            return false
        }
    }

    private fun swapEgl(): Boolean {
        val d = eglDisplay ?: return false
        val s = eglSurfaceObj ?: return false
        return android.opengl.EGL14.eglSwapBuffers(d, s)
    }

    private fun releaseEgl() {
        try {
            val d = eglDisplay ?: return
            android.opengl.EGL14.eglMakeCurrent(
                d,
                android.opengl.EGL14.EGL_NO_SURFACE,
                android.opengl.EGL14.EGL_NO_SURFACE,
                android.opengl.EGL14.EGL_NO_CONTEXT,
            )
            eglSurfaceObj?.let { android.opengl.EGL14.eglDestroySurface(d, it) }
            eglContextObj?.let { android.opengl.EGL14.eglDestroyContext(d, it) }
            android.opengl.EGL14.eglTerminate(d)
        } catch (e: Throwable) {
        } finally {
            eglDisplay = null
            eglContextObj = null
            eglSurfaceObj = null
        }
    }

    @JvmStatic
    fun init(act: Activity, web: WebView) {
        activity = act
        webView = web
        if (logFile == null) initLogFile(act)
        ensureWindowTransparent(act)
        // WebView 必须透明，否则无论 GL 层画什么都看不见。
        // 注意：Capacitor 在 Bridge 初始化时会按 capacitor.config.json 的 backgroundColor
        // 再设一次（默认不透明），所以这里在 post 里再补一次，确保最终生效。
        val makeTransparent = {
            try {
                web.setBackgroundColor(Color.TRANSPARENT)
                web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                logD("webview set transparent")
            } catch (e: Throwable) {
                logE("set webview transparent failed", e)
            }
        }
        makeTransparent()
        web.post { makeTransparent() }
    }

    // ───────────────────────────────────────────────────────────
    // 场景崩溃防护
    //
    // native 的 initScene 遇到不兼容的场景包会 SIGSEGV（App 直接闪退，
    // try/catch 抓不到）。崩溃栈样例：
    //   Scene::CreateLayer → ImageLayer::SetConfig → Renderable::SetConfig
    // 这是 native 库自身解析不了的包（常见于带 _puppet.mdl 骨骼模型的复杂场景）。
    //
    // 对策：initScene 前写哨兵文件，成功渲染若干帧后删除。
    // 若下次启动发现哨兵还在 → 说明上次崩在 initScene，
    // 就记入黑名单，之后这些包自动降级为静态封面，不再尝试渲染。
    // ───────────────────────────────────────────────────────────

    private fun sentinelFile(act: Activity): java.io.File =
        java.io.File(act.filesDir, "mpkgbg_init_in_progress")

    private fun blacklistFile(act: Activity): java.io.File =
        java.io.File(act.filesDir, "mpkgbg_scene_blacklist.txt")

    /** 上次 initScene 是否崩溃过。启动时调用，若崩溃则把那个包记入黑名单。 */
    @JvmStatic
    fun checkLastCrash() {
        val act = activity ?: return
        try {
            val sentinel = sentinelFile(act)
            if (!sentinel.exists()) return
            val badPath = try { sentinel.readText().trim() } catch (e: Throwable) { "" }
            if (badPath.isNotEmpty()) {
                blacklistFile(act).appendText(badPath + "\n")
                logE("CRASH DETECTED: previous initScene crashed on $badPath → blacklisted")
            }
            sentinel.delete()
        } catch (e: Throwable) {
            logE("checkLastCrash failed", e)
        }
    }

    private fun isBlacklisted(act: Activity, path: String): Boolean {
        return try {
            val f = blacklistFile(act)
            if (!f.exists()) false
            else f.readLines().any { it.trim() == path }
        } catch (e: Throwable) {
            false
        }
    }

    /** initScene 前调用：写哨兵。 */
    private fun markInitStart(act: Activity, path: String) {
        try { sentinelFile(act).writeText(path) } catch (e: Throwable) { }
    }

    /** 渲染稳定后调用：删哨兵（说明没崩）。 */
    private fun markInitSuccess(act: Activity) {
        try { sentinelFile(act).delete() } catch (e: Throwable) { }
    }

    // ───────────────────────────────────────────────────────────
    // 启动恢复（对应原生工程的 checkAndStartMpkgBackground）
    // ───────────────────────────────────────────────────────────

    /**
     * 启动时恢复上次保存的应用内背景。
     * 必须在 WebView 与 Activity 都就绪后调用（MainActivity.onCreate 尾部）。
     */
    @JvmStatic
    fun restoreSavedBackground() {
        val act = activity ?: return
        try {
            val wm = WallpaperManager.get(act)
            val bg = wm.getAppBackgroundWallpaper()
            if (bg == null) {
                logD("restore: no saved app background")
                return
            }
            logD("restore: found ${bg.name} type=${bg.wallpaperType}")
            if (bg.wallpaperType != WallpaperEntity.TYPE_MPKG_WEBGL) {
                logD("restore: not mpkg, skip native GL layer")
                return
            }
            var path = bg.getMpkgPrivatePath(act)
            if (!java.io.File(path).exists()) {
                val src = java.io.File(bg.getRealPath(act))
                if (src.exists()) {
                    java.io.File(path).parentFile?.mkdirs()
                    src.copyTo(java.io.File(path), overwrite = true)
                    logD("restore: copied to private dir")
                } else {
                    Log.w(TAG, "restore: mpkg missing at both paths")
                    return
                }
            }
            // 等 WebView 首帧后再启动 GL（否则容器还没布局，尺寸为 0）
            webView?.postDelayed({ start(path) }, 800)
        } catch (e: Throwable) {
            logE("restoreSavedBackground failed", e)
        }
    }

    // ───────────────────────────────────────────────────────────
    // 启动 / 停止
    // ───────────────────────────────────────────────────────────

    /** 启动 MPKG 场景背景。path 是 mpkg 文件的绝对路径。 */
    @JvmStatic
    fun start(path: String?) {
        if (path.isNullOrBlank()) {
            Log.w(TAG, "start called with empty path")
            return
        }
        val act = activity ?: return
        val web = webView ?: return
        val file = java.io.File(path)
        if (!file.exists()) {
            Log.w(TAG, "mpkg not found: $path")
            return
        }

        // 同一张壁纸已经在渲染：直接返回。每次重建 TextureView + initScene 要 0.5~3 秒，
        // 用户连续点同一张时表现为「一直加载不出来、要点很多次」。
        if (active && pendingPath == path && sceneReady && sceneSession != null) {
            logD("start: already active, skip rebuild: $path")
            return
        }

        logD("start: $path (${file.length()} bytes)")
        active = true
        pendingPath = path

        // ★★★ 关键：先判断这个 mpkg 是「场景包」还是「视频包」
        //   原版注释（LandscapeWebActivity:1266）：
        //     "native 库 initScene 不支持纯视频壁纸（返回空场景属性 → 黑屏）"
        //   原版用 MpkgParser 只读条目表（几 KB，不加载整个文件）判断有没有视频文件，
        //   视频型走 TextureView + MediaPlayer，不走 initScene。
        //   之前我把所有 mpkg 都当场景包 initScene —— 视频包必然全黑
        //   （实测 pixelCheck 全是 (0,0,0,255) 就是空画面的证据）。
        // 类型判定：照原版 LandscapeWebActivity:1245 的逻辑
        //   包内有 mp4/webm/mkv/mov/avi/m4v 任一 → 视频型，走 TextureView + MediaPlayer
        //   否则 → 场景型，走 GLSurfaceView + libscenejni
        //
        // 为什么不用「有 scene.json 就当场景」：实测这个包
        //   [preview.gif, project.json, scene.json, wallpaper.mp4]
        // 虽然带 scene.json，但 initScene 渲染出来是全黑
        //（pixelCheck 全是 (0,0,0,255) 空画面）。
        // 原版对同一个包显示的是视频，所以判定必须跟原版一致。
        var isVideoPkg = false
        try {
            val typeKey = file.absolutePath + "|" + file.length() + "|" + file.lastModified()
            val cachedType = mpkgIsVideoCache[typeKey]
            if (cachedType != null) {
                // 同一文件已经判定过：不再解一遍条目表（几十 MB 的包有明显开销）
                isVideoPkg = cachedType
                logD("MpkgParser: cached type → ${if (isVideoPkg) "VIDEO" else "SCENE"}")
            } else {
                val entryNames = com.mineradio.android.wallpaper.MpkgParser(file)
                    .listEntries().map { it.name.lowercase() }
                val hasVideoEntry = entryNames.any {
                    it.endsWith(".mp4") || it.endsWith(".webm") || it.endsWith(".mkv") ||
                        it.endsWith(".mov") || it.endsWith(".avi") || it.endsWith(".m4v")
                }
                isVideoPkg = hasVideoEntry
                mpkgIsVideoCache[typeKey] = isVideoPkg
                logD("MpkgParser: entries=${entryNames.size} hasVideo=$hasVideoEntry → ${if (isVideoPkg) "VIDEO" else "SCENE"}")
                // 完整条目表只保留前 40 个：197 条全量写进 mpkgbg.log 会让日志暴涨
                logD("MpkgParser entries(${entryNames.size}): ${entryNames.take(40)}")
            }
        } catch (e: Throwable) {
            logE("MpkgParser type detection failed", e)
        }

        if (isVideoPkg) {
            logD("VIDEO mpkg → TextureView + MediaPlayer")
            startVideoBackground(file)
            return
        }
        // 上次 initScene 崩过的包 → 不再尝试渲染（会再次闪退），降级为静态显示
        if (isBlacklisted(act, file.absolutePath)) {
            logE("this scene crashed before (blacklisted) → skip initScene, keep page bg")
            return
        }
        logD("SCENE mpkg → TextureView + libscenejni")

        act.runOnUiThread {
            try {
                // 每次启动背景层都重新确认窗口背景透明（SystemBars 可能刚把它改回不透明）
                ensureWindowTransparent(act)
                // ★ 先彻底移除视频层，否则旧视频会盖在新场景之上
                teardownVideoLayer()
                startSceneTexture(act, web, path)
            } catch (e: Throwable) {
                logE("start failed", e)
            }
        }
    }

    /** 停止背景渲染，恢复 WebView 不透明。 */
    @JvmStatic
    fun stop() {
        logD("stop")
        active = false
        pendingPath = null
        sceneReady = false
        val act = activity ?: return
        act.runOnUiThread {
            try {
                removeTransparentCss()
                frontLayersHidden = false

                // 1) 先释放视频层（TextureView + MediaPlayer）——必须彻底移除，
                //    否则「恢复原背景」后视频还铺在 WebView 下面，看起来像两层背景。
                try { mediaPlayer?.setSurface(null) } catch (e: Throwable) { }
                try { mediaPlayer?.stop() } catch (e: Throwable) { }
                try { mediaPlayer?.release() } catch (e: Throwable) { }
                mediaPlayer = null
                videoView?.let { tv ->
                    tv.visibility = View.GONE
                    // ★ 必须清掉 listener，否则旧 TextureView 被回收时
                    //   onSurfaceTextureDestroyed 还会去释放已经换新的 MediaPlayer
                    try { tv.surfaceTextureListener = null } catch (e: Throwable) { }
                    (tv.parent as? ViewGroup)?.removeView(tv)
                    logD("video TextureView removed")
                }
                videoView = null
                pendingVideoFile = null

                // 2) 再销毁场景层（TextureView + 自建 EGL 线程）：
                //    先让 GL 线程释放 native 场景，再移除 View。
                teardownSceneTexture()
                contextId = -1
                sceneReady = false
                active = false
            } catch (e: Throwable) {
                logE("stop failed", e)
            }
        }
    }

    /**
     * GL 确认开始出帧后，隐藏前端自己的 3D 粒子/封面层（#canvas-container）。
     *
     * 为什么不直接在一开始就藏：那是用户原本看得见的效果层。
     * 只有在场景真的画出来之后再藏，才不会出现「两个都没有」。
     * 由 onDrawFrame 的帧计数触发（见 checkAndHideFrontLayers）。
     */
    @JvmStatic
    fun hideFrontLayers() {
        // ★ 必须切回 UI 线程：WebView 方法只能在主线程调用。
        //   之前是在 GL 线程直接调 evaluateJavascript，触发
        //   "A WebView method was called on thread 'GLThread'"，
        //   而那正是日志里 updateScene failed 的真实来源。
        val act = activity ?: return
        act.runOnUiThread {
            val web = webView ?: return@runOnUiThread
        val js = """
            (function(){
              try {
                if (!document.getElementById('mpkg-hide-front-style')) {
                  var st = document.createElement('style');
                  st.id = 'mpkg-hide-front-style';
                  st.textContent = '#canvas-container { opacity: 0 !important; visibility: hidden !important; }';
                  document.head.appendChild(st);
                }
                console.log('[MpkgBg] front layers hidden (canvas-container)');
              } catch(e) {}
            })();
            """.trimIndent()
            web.evaluateJavascript(js, null)
            logD("hideFrontLayers: canvas-container hidden")
        }
    }

    /** 恢复前端图层可见（停止背景时）。 */
    private fun showFrontLayers() {
        val act = activity ?: return
        act.runOnUiThread {
            val web = webView ?: return@runOnUiThread
        val js = """
            (function(){
              try {
                var st = document.getElementById('mpkg-hide-front-style');
                if (st) st.remove();
                console.log('[MpkgBg] front layers restored');
              } catch(e) {}
            })();
            """.trimIndent()
            web.evaluateJavascript(js, null)
        }
    }

    /** 帧计数达到阈值后，说明 GL 在正常出帧，隐藏前端图层避免遮挡。 */
    private fun checkAndHideFrontLayers() {
        if (frontLayersHidden) return
        if (frameCount < 30) return   // 30 帧 ≈ 0.5 秒，足够确认渲染稳定
        frontLayersHidden = true
        // 不隐藏前端图层：GL 场景至今未能画出非黑画面（glReadPixels 实测全黑），
        // 藏掉前端只会让用户"两个都看不到"。等场景真能出画再启用。
        logD("frameCount reached $frameCount (front layers left visible)")
    }

    private var frontLayersHidden = false


    // ───────────────────────────────────────────────────────────
    // 视频型 MPKG：TextureView + MediaPlayer
    //（照原版：native initScene 不支持纯视频壁纸 → 必须走这条路）
    // ───────────────────────────────────────────────────────────

    private var videoView: android.view.TextureView? = null
    private var mediaPlayer: android.media.MediaPlayer? = null

    private var pendingVideoFile: java.io.File? = null

    /**
     * ★★★ 切换为「视频背景」前，必须让 GL 层彻底让位。
     *
     * 两条背景渲染路径是互斥的，但它们的 View 都加在 WebView 父容器的 index 0。
     * 新的一层 addView(…, 0) 并不会把旧层顶掉 —— 旧 GLSurfaceView 仍留在层级里，
     * 而 GLSurfaceView 的自绘背景是**不透明黑**，于是：
     *   先应用场景壁纸 → 再应用视频壁纸 ＝ 视频在 GL 黑面**下面**，用户只看到一片黑。
     * 这是「壁纸导入了却一直显示不出来」的直接成因之一。
     *
     * 处理：隐藏 + 暂停 + 在 GL 线程释放 native context（View 保留以便下次复用）。
     */
    private fun teardownGlLayerForVideo() {
        // 场景层现在也是 TextureView，直接走统一销毁
        try {
            teardownSceneTexture()
            logD("scene layer removed (switching to video)")
        } catch (e: Throwable) {
            logE("teardownGlLayerForVideo failed", e)
        }
    }

    /**
     * ★★★ 切换为「场景背景」前，必须彻底移除视频层。
     *
     * 同理：旧 TextureView + MediaPlayer 若留着，新的 GLSurfaceView 会被 addView 到
     * index 0，把 TextureView 顶到 index 1 —— 视频反而盖在场景之上；而且 MediaPlayer
     * 还在后台占用解码器。这里直接 release 并从层级里摘掉。
     */
    private fun teardownVideoLayer() {
        try {
            try { mediaPlayer?.setSurface(null) } catch (e: Throwable) { }
            try { mediaPlayer?.stop() } catch (e: Throwable) { }
            try { mediaPlayer?.release() } catch (e: Throwable) { }
            mediaPlayer = null
            pendingVideoFile = null
            videoView?.let { tv ->
                tv.visibility = View.GONE
                // 先摘 listener：否则 removeView 触发的 onSurfaceTextureDestroyed
                // 会再去 release 一个已经换新的 MediaPlayer
                try { tv.surfaceTextureListener = null } catch (e: Throwable) { }
                (tv.parent as? ViewGroup)?.removeView(tv)
            }
            videoView = null
            logD("video layer removed (switching to scene)")
        } catch (e: Throwable) {
            logE("teardownVideoLayer failed", e)
        }
    }

    private fun startVideoBackground(file: java.io.File) {
        val act = activity ?: return
        val web = webView ?: return
        pendingVideoFile = file
        act.runOnUiThread {
            try {
                // ★ 先让 GL 层让位，否则不透明的 GLSurfaceView 会盖在 TextureView 之上
                teardownGlLayerForVideo()
                // 提取内嵌视频到私有目录（8KB 流式拷贝，不 OOM）
                val extracted = MpkgParser.extractVideoFile(file.absolutePath, act.filesDir)
                if (extracted == null || !extracted.exists()) {
                    logE("startVideoBackground: extract failed")
                    return@runOnUiThread
                }
                logD("video extracted: ${extracted.absolutePath} (${extracted.length()} bytes)")

                // 创建/复用 TextureView，垫在 WebView 之下
                var tv = videoView
                if (tv == null) {
                    tv = android.view.TextureView(act).apply {
                        layoutParams = androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        // ★ 关键：SurfaceTexture 只有在 View attach 并完成布局后才存在。
                        //   之前 addView 之后立刻读 tv.surfaceTexture 拿到 null，
                        //   MediaPlayer 没挂上 Surface → 播放黑屏
                        //   （日志："E TextureView not available yet"）。
                        //   正确做法是等这个回调再启动播放。
                        surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st: android.graphics.SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                logD("onSurfaceTextureAvailable ${width}x$height → start playback")
                                playVideoOnSurface(st, extracted)
                            }

                            override fun onSurfaceTextureSizeChanged(
                                st: android.graphics.SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                logD("onSurfaceTextureSizeChanged ${width}x$height")
                            }

                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                logD("onSurfaceTextureDestroyed")
                                try { mediaPlayer?.release() } catch (e: Throwable) { }
                                mediaPlayer = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {
                                // 每帧回调，不记日志
                            }
                        }
                    }
                    videoView = tv
                    val parent = web.parent as? ViewGroup
                    parent?.addView(tv, 0)
                    logD("TextureView added at index 0")
                }
                tv.visibility = View.VISIBLE

                // 如果 TextureView 已经可用（复用时常见），直接播
                val existing = tv.surfaceTexture
                if (existing != null) {
                    logD("TextureView already has SurfaceTexture → start playback immediately")
                    playVideoOnSurface(existing, extracted)
                } else {
                    logD("waiting for onSurfaceTextureAvailable...")
                }
            } catch (e: Throwable) {
                logE("startVideoBackground failed", e)
            }
        }
    }

    /** 在指定 SurfaceTexture 上启动 MediaPlayer 播放。 */
    private fun playVideoOnSurface(st: android.graphics.SurfaceTexture, extracted: java.io.File) {
        try {
            // 释放旧播放器
            try { mediaPlayer?.setSurface(null) } catch (e: Throwable) { }
            try { mediaPlayer?.release() } catch (e: Throwable) { }
            mediaPlayer = null

            val player = android.media.MediaPlayer()
            mediaPlayer = player
            player.setSurface(android.view.Surface(st))
            player.setDataSource(extracted.absolutePath)
            player.isLooping = true
            player.setVolume(0f, 0f)   // 壁纸静音
            player.setOnPreparedListener {
                logD("video prepared, starting playback")
                try { it.start() } catch (e: Throwable) { logE("start playback failed", e) }
                injectTransparentCss()   // 视频在 WebView 之下，页面需要透明
            }
            player.setOnErrorListener { _, what, extra ->
                logE("MediaPlayer error what=$what extra=$extra")
                true
            }
            player.prepareAsync()
            logD("prepareAsync called")
        } catch (e: Throwable) {
            logE("playVideoOnSurface failed", e)
        }
    }

    @JvmStatic
    fun onResume() {
        activity?.let { ensureWindowTransparent(it) }
        scenePaused = false
        startParallaxSensor()
        glView?.onResume()
    }

    @JvmStatic
    fun onPause() {
        scenePaused = true
        stopParallaxSensor()
        glView?.onPause()
    }

    @JvmStatic
    fun destroy() {
        try { stop() } catch (e: Throwable) { }
    }

    // ───────────────────────────────────────────────────────────
    // GL 渲染
    // ───────────────────────────────────────────────────────────

    private fun threadOnGl(run: () -> Unit) {
        val gl = glView
        if (gl == null) run() else gl.queueEvent(run)
    }

    private class SceneRenderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                logD("onSurfaceCreated begin")
                // 与原生 LandscapeWebActivity:14046 一致
                android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f)
                surfaceCreated = true
                sceneLib = SceneLib
                logD("SceneLib acquired, calling initLibrary")
                sceneLib?.initLibrary(activity!!)
                logD("initLibrary done, isInitialized=${SceneLib.isInitialized}")
                // ★ 照源码 LandscapeWebActivity:7851 的做法：init 后设语言。
                //   28MB 那个场景包里有中文材质名（主发/人物主体/书法字体.ttf），
                //   不设 zh-CN 时字体与本地化解析可能异常（崩溃栈在 Scene::CreateLayer）。
                try {
                    SceneLib.setLanguage("zh-CN")
                    logD("setLanguage(zh-CN) done")
                } catch (e: Throwable) {
                    logE("setLanguage failed", e)
                }
                contextId = sceneLib?.initContext(activity!!) ?: -1
                logD("initContext done, contextId=$contextId")

                if (contextId >= 0) {
                    val path = pendingPath
                    if (path != null) {
                        try {
                            // 崩溃哨兵：initScene 可能 SIGSEGV，下次启动据此拉黑该包
                            markInitStart(activity!!, path)
                            sceneLib?.initScene(contextId, path)
                            // 与原生一致：关掉 native 侧写文件日志（很拖性能）
                            try { sceneLib?.setLogToFileEnabled(false) } catch (e2: Throwable) { }
                            sceneReady = true
                            logD("initScene ok: $path")
                            // ★★★ 与参考工程一致：只设置 parallax / 音频开关，
                            //   不设置 alignment（alignment 会让场景按「填满屏幕」重新裁切放大，
                            //   画面被裁掉一部分 —— 这就是「场景壁纸显示不全」的直接原因）。
                            applyParallaxOnly()
                            // ★ 诊断：initScene 后查询场景真实状态
                            try {
                                val props = sceneLib?.getSceneProperties(contextId)
                                logD("sceneProperties: len=${props?.length ?: 0} preview=${props?.take(300)}")
                                val canvas = android.graphics.PointF()
                                val hasCanvas = sceneLib?.getSceneCanvasSize(contextId, canvas) ?: false
                                logD("sceneCanvasSize: ok=$hasCanvas ${canvas.x}x${canvas.y}")
                                val flags2 = sceneLib?.getSceneFeatureFlags(contextId) ?: 0
                                logD("sceneFeatureFlags=$flags2")
                            } catch (e: Throwable) {
                                logE("scene diagnostics failed", e)
                            }
                            try {
                                logD("isWallpaperVersionValid: ${sceneLib?.isWallpaperVersionValid(path)}")
                                val wt = sceneLib?.getWallpaperType(path)
                                logD("getWallpaperType: $wt")
                                val res = sceneLib?.getWallpaperResolution(path)
                                logD("getWallpaperResolution: ${res?.x}x${res?.y}")
                            } catch (e: Throwable) {
                                logE("wallpaper info diagnostics failed", e)
                            }
                        } catch (e: Throwable) {
                            logE("initScene failed", e)
                        }
                    }
                }
            } catch (e: Throwable) {
                logE("onSurfaceCreated failed", e)
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            try {
                logD("onSurfaceChanged ${width}x$height")
                // ★ 必须显式设 viewport（照 LWService 的做法）：
                //   native 的 updateScene 会把 viewport 改成它内部的渲染尺寸，
                //   不在这里重设为屏幕尺寸的话，画面只画在底部一小块，其余全黑。
                android.opengl.GLES20.glViewport(0, 0, width, height)
                // ★ 只有场景真正 initScene 成功后才 resize，与参考工程一致：
                //   initScene 之前 resize 会让 native 用错误尺寸建 viewport。
                if (contextId >= 0 && sceneReady) {
                    sceneLib?.resizeScene(contextId, width, height)
                    logD("resizeScene done ${width}x$height")
                }
            } catch (e: Throwable) {
                logE("onSurfaceChanged failed", e)
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            try {
                if (contextId >= 0 && sceneReady) {
                    // 正常渲染（红色清屏诊断已完成使命，移除）
                    sceneLib?.updateScene(contextId)
                    // 帧计数：确认 GL 真的在出帧
                    frameCount += 1
                    // 渲染到 60 帧说明 initScene 没崩，删掉哨兵
                    if (frameCount == 60) {
                        try { markInitSuccess(activity!!) } catch (e: Throwable) { }
                    }
                    // ★ 给支持音频的场景持续喂数据（静音 FFT），否则依赖音频的场景不渲染
                    if (sceneSupportsAudio && silentFft != null) {
                        try { sceneLib?.sendAudioData(contextId, silentFft) } catch (e: Throwable) { }
                    }
                    if (frameCount % 120 == 1) {
                        logD("onDrawFrame #$frameCount, contextId=$contextId, sceneReady=$sceneReady")
                    }
                    // ★ 每 60 帧在同一帧内抓一次像素：确认 updateScene 到底往 framebuffer 写了什么。
                    //   必须在 updateScene 之后、同一帧内读，否则读到的是交换后的空缓冲。
                    if (frameCount % 60 == 1) {
                        try {
                            val glv2 = glView
                            if (glv2 != null && glv2.width > 0) {
                                val sw = 64
                                val sh = 36
                                val fbo = IntArray(1)
                                android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_FRAMEBUFFER_BINDING, fbo, 0)
                                logD("[diag] current FBO binding=${fbo[0]}")
                                val b = java.nio.ByteBuffer.allocateDirect(sw * sh * 4)
                                android.opengl.GLES20.glReadPixels(0, 0, sw, sh,
                                    android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, b)
                                b.rewind()
                                var maxVal = 0
                                for (i in 0 until sw * sh * 4) {
                                    val v = b.get().toInt() and 0xFF
                                    if (v > maxVal) maxVal = v
                                }
                                b.rewind()
                                var redBytes = 0
                                var grayBytes = 0
                                var idx = 0
                                while (b.hasRemaining() && idx < sw * sh) {
                                    val r = b.get().toInt() and 0xFF
                                    val g = b.get().toInt() and 0xFF
                                    val bl = b.get().toInt() and 0xFF
                                    b.get() // alpha
                                    if (r > 200 && g < 60 && bl < 60) redBytes += 1
                                    else if (r == g && g == bl) grayBytes += 1
                                    idx += 1
                                }
                                logD("frame #$frameCount pixelCheck: red=$redBytes gray=$grayBytes other=${idx-redBytes-grayBytes} of $idx (max=$maxVal)")
                                // [diag] 再按默认 framebuffer(窗口 surface) 读一次：确认场景是否画到了屏幕缓冲
                                if (fbo[0] != 0) {
                                    android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, 0)
                                    val b2 = java.nio.ByteBuffer.allocateDirect(sw * sh * 4)
                                    android.opengl.GLES20.glReadPixels(0, 0, sw, sh,
                                        android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, b2)
                                    b2.rewind()
                                    var max2 = 0
                                    var rgb2 = 0
                                    for (i in 0 until sw * sh) {
                                        val r = b2.get().toInt() and 0xFF
                                        val g = b2.get().toInt() and 0xFF
                                        val bl = b2.get().toInt() and 0xFF
                                        b2.get()
                                        if (maxOf(r, g, bl) > max2) max2 = maxOf(r, g, bl)
                                        if (r != g || g != bl) rgb2 += 1
                                    }
                                    logD("[diag] default FBO(0) readback: max=$max2 colored=$rgb2 of ${sw * sh}")
                                }
                            }
                        } catch (e: Throwable) {
                            logE("pixelCheck failed", e)
                        }
                    }
                    // 第 8 帧截图存证：确认 GL 层到底画出了什么（黑屏时这是唯一客观证据）
                    // ★ 截图必须在 GL 线程内做：glReadPixels 只能在 GL context 当前线程调用，
                    //   之前放在独立 Thread 里，读到的是未定义内容（所以老是灰色）。
                    if (frameCount == 8 && !coverCaptured) {
                        coverCaptured = true
                        try {
                            val glv = glView
                            val w2 = glv?.width ?: 0
                            val h2 = glv?.height ?: 0
                            if (w2 > 0 && h2 > 0) {
                                val tw = 320
                                val th = (h2.toFloat() / w2.toFloat() * tw).toInt().coerceAtLeast(1)
                                val buf = java.nio.ByteBuffer.allocateDirect(tw * th * 4)
                                // 读之前先确认当前 framebuffer 内容：读 4x4 判断主色
                                android.opengl.GLES20.glReadPixels(0, 0, tw, th,
                                    android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, buf)
                                buf.rewind()
                                val bmp = android.graphics.Bitmap.createBitmap(tw, th, android.graphics.Bitmap.Config.ARGB_8888)
                                bmp.copyPixelsFromBuffer(buf)
                                // 统计颜色
                                var redCount = 0
                                var grayCount = 0
                                var otherCount = 0
                                val sample = 200
                                for (k in 0 until sample) {
                                    val px = bmp.getPixel((k * 7) % tw, (k * 13) % th)
                                    val r = (px shr 16) and 0xFF
                                    val g = (px shr 8) and 0xFF
                                    val b = px and 0xFF
                                    if (r > 200 && g < 60 && b < 60) redCount += 1
                                    else if (r == g && g == b) grayCount += 1
                                    else otherCount += 1
                                }
                                logD("frame#8 analysis: red=$redCount gray=$grayCount other=$otherCount (of $sample)")
                                // 垂直翻转后保存
                                val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
                                val flipped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, tw, th, m, true)
                                bmp.recycle()
                                java.io.FileOutputStream(java.io.File(activity!!.filesDir, "mpkgbg_frame.png")).use {
                                    flipped.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it)
                                }
                                logD("frame saved: ${tw}x$th")
                                flipped.recycle()
                            }
                        } catch (e: Throwable) {
                            logE("capture frame failed", e)
                        }
                    }
                    // 注意：不在这里自动隐藏前端层。
                    // 只有 GL 确认输出非黑画面后才有资格藏（见 frame capture 的判断），
                    // 否则就是"两个都没有"——这个教训已经吃过一次。
                }
            } catch (e: Throwable) {
                // 每帧都刷日志会爆，只记第一次
                if (!drawErrorLogged) {
                    drawErrorLogged = true
                    logE("updateScene failed", e)
                }
            }
        }
    }

    private var drawErrorLogged = false

    /** 场景特性：bit0=audio, bit2=parallax */
    private var sceneSupportsAudio = false
    private var sceneSupportsParallax = false
    private var sceneCanvasW = 0f
    private var sceneCanvasH = 0f

    /**
     * initScene 之后只设置 parallax / 音频开关（与参考工程 applyParallaxOnly 一致）。
     *
     * 为什么不整表下发 applySceneProperties：
     *   给场景补 alignment=0 会让 native 按「填满屏幕」重算变换，把画面放大并裁掉一部分，
     *   表现就是场景壁纸在应用背景里「显示不完全」（参考工程的注释也明确写了这一点）。
     *
     * 这里只做两件事：
     *   1. 场景支持音频 → audioprocessing=true（缺它 native 会忽略 sendAudioData）
     *   2. 场景原生支持 parallax → mobileparallax=1 / strength=100
     *      不支持视差的场景不设置，避免视角乱移
     */
    private fun applyParallaxOnly(cid: Int = contextId) {
        val lib = sceneLib ?: return
        if (cid < 0) return
        try {
            val flags = try { lib.getSceneFeatureFlags(cid) } catch (e: Throwable) { 0 }
            sceneSupportsAudio = (flags and 1) != 0
            sceneSupportsParallax = (flags and 4) != 0
            logD("applyParallaxOnly: featureFlags=$flags audio=$sceneSupportsAudio parallax=$sceneSupportsParallax")

            // 画布尺寸只做记录，用于日志排查（不参与任何缩放/裁切决策）
            try {
                val pt = android.graphics.PointF()
                if (lib.getSceneCanvasSize(cid, pt) && pt.x > 0 && pt.y > 0) {
                    sceneCanvasW = pt.x
                    sceneCanvasH = pt.y
                    logD("scene canvas: ${pt.x}x${pt.y}")
                }
            } catch (e: Throwable) { }

            val json = org.json.JSONObject()
            if (sceneSupportsAudio) {
                json.put("audioprocessing", org.json.JSONObject().apply {
                    put("type", "bool"); put("value", true); put("order", 2)
                })
            }
            if (sceneSupportsParallax) {
                json.put("mobileparallax", org.json.JSONObject().apply {
                    put("type", "combo"); put("value", 1); put("order", 4)
                })
                json.put("mobileparallaxstrength", org.json.JSONObject().apply {
                    put("type", "slider"); put("value", 100.0)
                    put("min", 1.0); put("max", 200.0); put("order", 5)
                    put("condition", "mobileparallax.value")
                })
            }
            if (json.length() == 0) {
                logD("applyParallaxOnly: nothing to set, keep scene defaults")
                return
            }
            lib.applySceneProperties(cid, json.toString())
            logD("applyParallaxOnly done, keys=${json.keys().asSequence().toList()}")
        } catch (e: Throwable) {
            logE("applyParallaxOnly failed", e)
        }
    }
    private var frameCount = 0
    private var coverCaptured = false
    /** 64 段静音 FFT：只为了让 audioprocessing 场景动起来，不接真实音频。 */
    private val silentFft: FloatArray = FloatArray(64) { 0.02f }

    // ───────────────────────────────────────────────────────────
    // 页面透明化
    // ───────────────────────────────────────────────────────────

    /**
     * 让页面所有背景层透明，GL 层才能透出来。
     *
     * 用持久 style 标签 + body class，并用 !important 覆盖页面自身的规则；
     * Mineradio 前端有 82 处 vw/vh 背景，逐元素改 inline style 不现实。
     */
    private fun injectTransparentCss() {
        val web = webView ?: return
        val js = """
            (function(){
              try {
                if (!document.getElementById('mpkg-bg-transparent-style')) {
                  var st = document.createElement('style');
                  st.id = 'mpkg-bg-transparent-style';
                  st.textContent =
                    // 1) 所有背景容器本身透明（让 GL 层透出来）
                    'html, body, #app, #root, #custom-bg, #custom-bg-video,' +
                    ' .bg, #bg, #background, #bg-layer, .bg-layer, canvas.bg {' +
                    ' background: transparent !important;' +
                    ' background-color: transparent !important;' +
                    ' background-image: none !important; }' +
                    // 2) 其余背景媒体层（自定义背景图/视频、专辑封面虚化、WE 页面层）隐藏。
                    //    ★ #album-bg 是首页的封面虚化背景，不透明时会整片盖住底层 GL 场景，
                    //      表现就是「场景壁纸只露出一部分」。参考工程同样把这些层压掉。
                    '#custom-bg, #custom-bg-video, .custom-bg, #particle-bg, #particle-canvas,' +
                    ' .particle-bg, #album-bg, #album-bg-next,' +
                    ' #wallpaper-engine-layer, #wallpaper-engine-glass-sampler,' +
                    ' #sonic-workshop-layer {' +
                    ' display: none !important; visibility: hidden !important; opacity: 0 !important; }' +
                    '#custom-bg::before, #custom-bg::after {' +
                    ' opacity: 0 !important; background: transparent !important;' +
                    ' background-image: none !important; }';
                  document.head.appendChild(st);
                }
                document.documentElement.classList.add('mpkg-scene-active');
                document.body.classList.add('mpkg-scene-active');
                document.documentElement.style.background = 'transparent';
                document.body.style.background = 'transparent';
                // 守护：JS 重建 DOM 后 class 可能被移除，每 2 秒检查一次
                if (!window.__mpkgBgGuard) {
                  window.__mpkgBgGuard = setInterval(function(){
                    if (!document.body.classList.contains('mpkg-scene-active')) {
                      document.body.classList.add('mpkg-scene-active');
                      document.documentElement.classList.add('mpkg-scene-active');
                    }
                  }, 2000);
                }
                console.log('[MpkgBg] transparent css injected');
              } catch(e) { console.warn('[MpkgBg] inject css failed', e); }
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun removeTransparentCss() {
        val web = webView ?: return
        val js = """
            (function(){
              try {
                document.body.classList.remove('mpkg-scene-active');
                document.documentElement.classList.remove('mpkg-scene-active');
                if (window.__mpkgBgGuard) { clearInterval(window.__mpkgBgGuard); window.__mpkgBgGuard = null; }
                var st = document.getElementById('mpkg-bg-transparent-style');
                if (st) st.remove();
                document.documentElement.style.background = '';
                document.body.style.background = '';
                console.log('[MpkgBg] transparent css removed');
              } catch(e) {}
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    // ───────────────────────────────────────────────────────────
    // 触摸转发（场景交互/视差）
    // ───────────────────────────────────────────────────────────

    /** 触摸转发：背景层在 WebView 底下收不到事件，需要从 Activity 转发。 */
    @JvmStatic
    fun forwardTouch(ev: android.view.MotionEvent) {
        if (!active) return
        if (contextId < 0 || !sceneReady) return
        val action = ev.actionMasked
        val x = ev.x
        val y = ev.y
        when (action) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_MOVE -> sceneCommands.add {
                try { sceneLib?.sendTouchInput(contextId, true, x, y) } catch (e: Throwable) { }
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> sceneCommands.add {
                try { sceneLib?.sendTouchInput(contextId, false, x, y) } catch (e: Throwable) { }
            }
        }
    }

    /**
     * 把 native 渲染在左下角的那块内容「等比居中」画到整屏。
     *
     * native 的 updateScene 固定把 viewport 设成 resizeScene 的尺寸并画在左下角，
     * 所以这里用 glCopyTexSubImage2D 把这块拷成纹理，再清屏、按目标矩形画一个全屏四边形。
     * 目标矩形是按壁纸原始比例算出来的（见 updateRenderSize），所以画面完整、不变形，
     * 屏幕比例不一致时两侧/上下留黑边。
     */
    private class SceneBlitter {
        var program = 0
        var tex = 0
        private var texW = 0
        private var texH = 0
        private var vbo = 0
        private var aPos = 0
        private var aTc = 0
        private var uTex = 0

        val ready: Boolean get() = program != 0 && tex != 0

        fun init(): Boolean {
            try {
                val vs = compileShader(
                    android.opengl.GLES20.GL_VERTEX_SHADER,
                    "attribute vec4 aPosition;\nattribute vec2 aTexCoord;\nvarying vec2 vTexCoord;\n" +
                        "void main(){ gl_Position=aPosition; vTexCoord=aTexCoord; }",
                )
                val fs = compileShader(
                    android.opengl.GLES20.GL_FRAGMENT_SHADER,
                    "precision mediump float;\nvarying vec2 vTexCoord;\nuniform sampler2D uTexture;\n" +
                        "void main(){ gl_FragColor=texture2D(uTexture, vTexCoord); }",
                )
                if (vs == 0 || fs == 0) return false
                program = android.opengl.GLES20.glCreateProgram()
                android.opengl.GLES20.glAttachShader(program, vs)
                android.opengl.GLES20.glAttachShader(program, fs)
                android.opengl.GLES20.glLinkProgram(program)
                android.opengl.GLES20.glDeleteShader(vs)
                android.opengl.GLES20.glDeleteShader(fs)
                aPos = android.opengl.GLES20.glGetAttribLocation(program, "aPosition")
                aTc = android.opengl.GLES20.glGetAttribLocation(program, "aTexCoord")
                uTex = android.opengl.GLES20.glGetUniformLocation(program, "uTexture")
                val ids = IntArray(1)
                android.opengl.GLES20.glGenTextures(1, ids, 0)
                tex = ids[0]
                android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, tex)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_S, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_T, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
                val bufs = IntArray(1)
                android.opengl.GLES20.glGenBuffers(1, bufs, 0)
                vbo = bufs[0]
                return ready
            } catch (e: Throwable) {
                android.util.Log.e("MpkgBg", "SceneBlitter init failed", e)
                return false
            }
        }

        /** @return 壁纸区域矩形 [x, y, w, h]（左下原点），失败返回 null */
        fun copyAndDrawCentered(srcW: Int, srcH: Int, screenW: Int, screenH: Int, rotate: Boolean): IntArray? {
            if (!ready || srcW <= 0 || srcH <= 0 || screenW <= 0 || screenH <= 0) return null
            try {
                android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, tex)
                if (texW < srcW || texH < srcH) {
                    texW = srcW
                    texH = srcH
                    android.opengl.GLES20.glTexImage2D(
                        android.opengl.GLES20.GL_TEXTURE_2D, 0, android.opengl.GLES20.GL_RGBA,
                        srcW, srcH, 0, android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, null,
                    )
                }
                android.opengl.GLES20.glCopyTexSubImage2D(
                    android.opengl.GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, srcW, srcH,
                )
                // ★ 竖构图长条壁纸：先整体旋转 90°，再等比铺满屏幕（铺满宽高，少量裁切），
                //   保证方向正确且人物比例不变形。
                //   非旋转路径保持「横向铺满、上下贴边」。
                val dstX: Int
                val dstY: Int
                val l: Float
                val r: Float
                val b: Float
                val t: Float
                val verts: FloatArray
                if (rotate) {
                    val imgW = srcH   // 旋转后的宽 = 原高
                    val imgH = srcW   // 旋转后的高 = 原宽
                    val scale = maxOf(screenW.toFloat() / imgW, screenH.toFloat() / imgH)
                    val dstW = (imgW * scale).toInt()
                    val dstH = (imgH * scale).toInt()
                    dstX = (screenW - dstW) / 2
                    dstY = (screenH - dstH) / 2
                    l = dstX.toFloat() / screenW * 2f - 1f
                    r = (dstX + dstW).toFloat() / screenW * 2f - 1f
                    b = dstY.toFloat() / screenH * 2f - 1f
                    t = (dstY + dstH).toFloat() / screenH * 2f - 1f
                    verts = floatArrayOf(
                        l, b, 0f, 1f,
                        r, b, 0f, 0f,
                        l, t, 1f, 1f,
                        r, t, 1f, 0f,
                    )
                } else {
                    dstX = 0
                    dstY = 0
                    l = -1f
                    r = 1f
                    b = -1f
                    t = 1f
                    verts = floatArrayOf(
                        l, b, 0f, 0f,
                        r, b, 1f, 0f,
                        l, t, 0f, 1f,
                        r, t, 1f, 1f,
                    )
                }
                val fb = java.nio.ByteBuffer.allocateDirect(verts.size * 4)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                fb.put(verts).rewind()

                android.opengl.GLES20.glViewport(0, 0, screenW, screenH)
                android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f)
                android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)
                android.opengl.GLES20.glUseProgram(program)
                android.opengl.GLES20.glBindBuffer(android.opengl.GLES20.GL_ARRAY_BUFFER, vbo)
                android.opengl.GLES20.glBufferData(android.opengl.GLES20.GL_ARRAY_BUFFER, verts.size * 4, fb, android.opengl.GLES20.GL_DYNAMIC_DRAW)
                android.opengl.GLES20.glEnableVertexAttribArray(aPos)
                android.opengl.GLES20.glEnableVertexAttribArray(aTc)
                android.opengl.GLES20.glVertexAttribPointer(aPos, 2, android.opengl.GLES20.GL_FLOAT, false, 16, 0)
                android.opengl.GLES20.glVertexAttribPointer(aTc, 2, android.opengl.GLES20.GL_FLOAT, false, 16, 8)
                android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0)
                android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, tex)
                android.opengl.GLES20.glUniform1i(uTex, 0)
                android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4)
                android.opengl.GLES20.glDisableVertexAttribArray(aPos)
                android.opengl.GLES20.glDisableVertexAttribArray(aTc)
                android.opengl.GLES20.glBindBuffer(android.opengl.GLES20.GL_ARRAY_BUFFER, 0)
                android.opengl.GLES20.glUseProgram(0)
                return intArrayOf(dstX, dstY, screenW, screenH)
            } catch (e: Throwable) {
                android.util.Log.e("MpkgBg", "SceneBlitter draw failed", e)
                return null
            }
        }

        fun release() {
            try {
                if (program != 0) android.opengl.GLES20.glDeleteProgram(program)
                if (tex != 0) android.opengl.GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
                if (vbo != 0) android.opengl.GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
            } catch (e: Throwable) { }
            program = 0
            tex = 0
            vbo = 0
        }

        private fun compileShader(type: Int, src: String): Int {
            val sh = android.opengl.GLES20.glCreateShader(type)
            android.opengl.GLES20.glShaderSource(sh, src)
            android.opengl.GLES20.glCompileShader(sh)
            val st = IntArray(1)
            android.opengl.GLES20.glGetShaderiv(sh, android.opengl.GLES20.GL_COMPILE_STATUS, st, 0)
            if (st[0] == 0) {
                android.util.Log.e("MpkgBg", "shader compile failed: " + android.opengl.GLES20.glGetShaderInfoLog(sh))
                android.opengl.GLES20.glDeleteShader(sh)
                return 0
            }
            return sh
        }
    }
}
