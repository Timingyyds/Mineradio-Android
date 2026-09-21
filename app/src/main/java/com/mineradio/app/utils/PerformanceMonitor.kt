package com.mineradio.app.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicLong

/**
 * 轻量级性能监控工具
 *
 * 参考:
 * - RicardoJiang/android-performance: ANR 监控、卡顿监控、启动优化
 * - imhet/android-performance-optimization: 性能数据采集
 * - jecyu/Web-Performance-Optimization: 性能指标定义
 *
 * 监控内容:
 * 1. 应用启动耗时（冷启动 / 热启动）
 * 2. 内存使用（Java 堆 / Native / PSS）
 * 3. 帧率 / 掉帧（Choreographer）
 * 4. ANR 检测（主线程消息分发耗时）
 *
 * 设计原则:
 * - 极低开销：所有采集在后台线程，采样率可控
 * - 无依赖：不引入第三方库，纯 Android API
 * - 可关闭：release 默认开启但仅 Log，不上报
 */
object PerformanceMonitor {
    private const val TAG = "PerfMonitor"
    private const val ANR_THRESHOLD_MS = 3000L
    private const val FRAME_DROP_THRESHOLD_NS = 33_000_000L // ~30ms = 丢一帧

    @Volatile private var initialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context

    // 启动耗时
    private val appStartElapsed = AtomicLong(0)
    private var sceneStartElapsed = AtomicLong(0)

    // 帧率统计（全局监控）
    private val framesCounter = AtomicLong(0)
    private val jankCounter = AtomicLong(0)

    // ANR 检测
    private val anrChecker = AnrChecker()

    /**
     * 初始化（App.onCreate 中调用，主进程仅一次）
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        appStartElapsed.set(SystemClock.elapsedRealtime())

        // 启动 ANR 检测
        anrChecker.start()

        // ★ 全局帧率/掉帧监控（Choreographer 后台时不会触发回调，零开销）
        startGlobalFrameMonitor()

        // 前后台切换时记录内存
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        logMemory("foreground")
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        logMemory("background")
                    }
                },
            )
        } catch (_: Exception) {
        }

        Log.i(TAG, "PerformanceMonitor initialized, coldStart begin")
    }

    /**
     * 全局帧率监控：通过 Choreographer 统计真实帧率与掉帧数
     * 参考 RicardoJiang/android-performance jank-optimize 卡顿监控思路
     * Choreographer 回调仅在有帧渲染时触发（后台时自动停止），开销极低
     */
    private fun startGlobalFrameMonitor() {
        try {
            // 每秒汇总一次统计数据
            mainHandler.post(
                object : Runnable {
                    private var windowStartMs = SystemClock.elapsedRealtime()

                    override fun run() {
                        if (!initialized) return
                        val framesInWindow = framesCounter.getAndSet(0)
                        val jankFramesInWindow = jankCounter.getAndSet(0)
                        val elapsed = SystemClock.elapsedRealtime() - windowStartMs
                        val fps = if (elapsed > 0) framesInWindow * 1000 / elapsed else 0
                        // 仅当掉帧率 > 10% 或 帧率 < 45 时记录（避免日志噪音）
                        val jankRate = if (framesInWindow > 0) jankFramesInWindow * 100L / framesInWindow else 0L
                        if ((elapsed >= 2000) && (jankRate > 10 || fps < 45)) {
                            Log.w(TAG, "jank window: fps=$fps, frames=$framesInWindow, jank=$jankFramesInWindow ($jankRate%)")
                        }
                        windowStartMs = SystemClock.elapsedRealtime()
                        mainHandler.postDelayed(this, 1000)
                    }
                },
            )

            // Choreographer 逐帧统计（仅在渲染活跃时触发，后台自动停止，开销极低）
            val frameCallback =
                object : Choreographer.FrameCallback {
                    private var lastFrameTimeNanos = 0L

                    override fun doFrame(frameTimeNanos: Long) {
                        if (lastFrameTimeNanos != 0L) {
                            val frameInterval = frameTimeNanos - lastFrameTimeNanos
                            framesCounter.incrementAndGet()
                            if (frameInterval > FRAME_DROP_THRESHOLD_NS) {
                                jankCounter.incrementAndGet()
                            }
                        }
                        lastFrameTimeNanos = frameTimeNanos
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } catch (_: Exception) {
        }
    }

    /**
     * 记录某个场景开始（如 Activity.onCreate / WebView.loadUrl）
     */
    fun sceneBegin(scene: String) {
        sceneStartElapsed.set(SystemClock.elapsedRealtime())
        Log.i(TAG, "sceneBegin: $scene")
    }

    /**
     * 记录某个场景结束，输出耗时
     */
    fun sceneEnd(scene: String): Long {
        val cost = SystemClock.elapsedRealtime() - sceneStartElapsed.get()
        Log.i(TAG, "sceneEnd: $scene cost=${cost}ms")
        return cost
    }

    /**
     * 记录应用首帧渲染完成（可在 WebView onPageFinished 或 Compose 首帧时调用）
     */
    fun onFirstFrameRendered() {
        val total = SystemClock.elapsedRealtime() - appStartElapsed.get()
        Log.i(TAG, "★★★ App cold start total = ${total}ms")
    }

    /**
     * 输出当前内存信息
     */
    fun logMemory(tag: String = "") {
        try {
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMb = runtime.maxMemory() / 1024 / 1024
            val nativeUsedMb = Debug.getNativeHeapAllocatedSize() / 1024 / 1024

            val pssMb =
                try {
                    val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val pids = intArrayOf(android.os.Process.myPid())
                    val memInfos = am?.getProcessMemoryInfo(pids)
                    memInfos?.firstOrNull()?.totalPss?.div(1024) ?: 0
                } catch (_: Exception) {
                    0
                }

            Log.i(
                TAG,
                "memory[$tag]: javaHeap=${usedMb}MB/${maxMb}MB, native=${nativeUsedMb}MB, pss=${pssMb}MB",
            )

            // 内存压力警告
            if (pssMb > 0) {
                val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.let {
                    val memInfo = ActivityManager.MemoryInfo()
                    it.getMemoryInfo(memInfo)
                    if (memInfo.lowMemory) {
                        Log.w(TAG, "★★★ System low memory! avail=${memInfo.availMem / 1024 / 1024}MB")
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 触发 GC 建议（在内存紧张时调用，而非手动 System.gc）
     * 仅记录建议，由调用方决定是否执行
     */
    fun checkMemoryPressure(): Boolean =
        try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val availMb = memInfo.availMem / 1024 / 1024
            val thresholdMb = memInfo.threshold / 1024 / 1024
            val pressured = availMb < thresholdMb * 2
            if (pressured) {
                Log.w(TAG, "memory pressure detected: avail=${availMb}MB, threshold=${thresholdMb}MB")
            }
            pressured
        } catch (_: Exception) {
            false
        }

    /**
     * ANR 检测器
     * 原理：向主线程 Handler 发送延迟消息，如果消息处理时间与投递时间差超过阈值，
     * 说明主线程被阻塞（卡顿 / ANR 前兆）
     */
    private class AnrChecker {
        private val handler = Handler(Looper.getMainLooper())

        fun start() {
            handler.postDelayed({
                val start = SystemClock.uptimeMillis()
                handler.post {
                    val delay = SystemClock.uptimeMillis() - start - 50
                    if (delay > ANR_THRESHOLD_MS) {
                        Log.w(TAG, "★★★ Possible ANR: main thread blocked ${delay}ms")
                        // 打印主线程堆栈
                        val stackTrace = Looper.getMainLooper().thread.stackTrace
                        val topFrames =
                            stackTrace
                                .take(minOf(15, stackTrace.size))
                                .joinToString("\n") { "    at $it" }
                        Log.w(TAG, "main thread stack:\n$topFrames")
                    }
                    // 继续下一轮检测
                    if (initialized) start()
                }
            }, 5000)
        }
    }

    /**
     * 获取启动耗时（用于报告）
     */
    fun getColdStartMs(): Long = SystemClock.elapsedRealtime() - appStartElapsed.get()
}
