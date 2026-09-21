package com.mineradio.app.manager

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * ★ 插件市场 Activity
 *
 * 加载 Cloudflare Workers 上托管的 H5 商店页，提供：
 *  - 在线浏览/搜索/下载插件
 *  - 用户注册/登录
 *  - 用户上传插件（zip）
 *  - 下载后自动通过 PluginManager 安装
 *
 * 与 LandscapeWebActivity 的关系：
 *  - 这是独立的全屏 Activity（不影响横屏 UI 状态）
 *  - JS 桥接 `MR.installPluginFromBlob(base64, filename)` 触发本地自动安装
 *
 * 配置：
 *  - 默认市场 URL:  https://mineradio-market.YOUR-SUBDOMAIN.workers.dev
 *  - 可在 BuildConfig.PLUGIN_MARKET_URL 覆盖
 *  - 调试时可临时改为局域网 IP 便于真机测试
 */
class PluginMarketActivity : Activity() {
    companion object {
        private const val TAG = "PluginMarket"
        const val EXTRA_URL = "market_url"

        // ★ 默认市场地址（CloudBase 静态托管）
        const val DEFAULT_MARKET_URL = "https://plugin-market-d2gpn5vfb44d821b8-1458757189.tcloudbaseapp.com/market/"
    }

    private lateinit var webView: WebView
    private var marketUrl: String = DEFAULT_MARKET_URL
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        marketUrl = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_MARKET_URL
        Log.i(TAG, "onCreate: marketUrl=$marketUrl")

        // 沉浸式
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        window.statusBarColor = Color.parseColor("#0e1116")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        val root = FrameLayout(this)
        webView =
            WebView(this).apply {
                setBackgroundColor(Color.parseColor("#0e1116"))
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = true
                }
                // ★ JS 桥接：自动安装插件 + 关闭 Activity
                addJavascriptInterface(MarketBridge(), "MR")
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            url: String,
                        ): Boolean {
                            // 外部链接用系统浏览器打开
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                if (url.startsWith(marketUrl.substringBefore("/")) || url.contains("mineradio")) {
                                    return false // 内部链接继续在 webview 加载
                                }
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    return true
                                } catch (e: Exception) {
                                    Log.w(TAG, "open external url failed: ${e.message}")
                                }
                            }
                            return false
                        }
                    }
                webChromeClient = WebChromeClient()
            }
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)

        webView.loadUrl(marketUrl)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try {
            webView.destroy()
        } catch (_: Exception) {
        }
        ioExecutor.shutdown()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════
    //  JS 桥接
    // ═══════════════════════════════════════════════════════════
    inner class MarketBridge {
        /**
         * 从 base64 字符串接收 zip 字节，自动通过 PluginManager 安装
         * @param base64Content zip 文件的 base64 编码（无 data: 前缀）
         * @param filename 文件名（用于日志/调试）
         * @param callbackId JS 端回调 ID，结束时调用 window.__marketCallback(id, ok, msg)
         */
        @JavascriptInterface
        fun installPluginFromBlob(
            base64Content: String,
            filename: String,
            callbackId: String,
        ) {
            Log.i(TAG, "installPluginFromBlob: filename=$filename, size=${base64Content.length} chars")
            ioExecutor.execute {
                val result = installFromBase64(base64Content, filename)
                mainHandler.post {
                    webView.evaluateJavascript(
                        "try{if(window.__marketCallback)window.__marketCallback('$callbackId', ${result.first}, ${JSONObject.quote(
                            result.second,
                        )})}catch(e){console.error(e)}",
                        null,
                    )
                }
            }
        }

        /**
         * 兼容方案：通过 URL 下载 zip 后安装
         * 当前 Worker 直接返回 zip 流，所以这个方法也可以用
         */
        @JavascriptInterface
        fun installPluginFromUrl(
            downloadUrl: String,
            filename: String,
            callbackId: String,
        ) {
            Log.i(TAG, "installPluginFromUrl: $downloadUrl")
            ioExecutor.execute {
                val result = installFromUrl(downloadUrl, filename)
                mainHandler.post {
                    webView.evaluateJavascript(
                        "try{if(window.__marketCallback)window.__marketCallback('$callbackId', ${result.first}, ${JSONObject.quote(
                            result.second,
                        )})}catch(e){console.error(e)}",
                        null,
                    )
                }
            }
        }

        /** 关闭 Activity */
        @JavascriptInterface
        fun closeMarket() {
            runOnUiThread { finish() }
        }

        /** 获取当前包名/版本（让 JS 端显示） */
        @JavascriptInterface
        fun getAppInfo(): String {
            val pkg = packageName
            val ver =
                try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
                } catch (e: Exception) {
                    "?"
                }
            return JSONObject().put("package", pkg).put("version", ver).toString()
        }

        /** Toast 提示（让 JS 端显示原生 toast） */
        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread { Toast.makeText(this@PluginMarketActivity, message, Toast.LENGTH_SHORT).show() }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  安装逻辑
    // ═══════════════════════════════════════════════════════════
    private fun installFromBase64(
        base64: String,
        filename: String,
    ): Pair<Boolean, String> {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.size < 22) return false to "数据太小"
            installZipBytes(bytes, filename)
        } catch (e: Exception) {
            Log.e(TAG, "installFromBase64 failed", e)
            false to "Base64 解码失败: ${e.message}"
        }
    }

    private fun installFromUrl(
        url: String,
        filename: String,
    ): Pair<Boolean, String> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "Mineradio-Android/PluginMarket")
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode != 200) {
                return false to "HTTP ${conn.responseCode}"
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            installZipBytes(bytes, filename)
        } catch (e: Exception) {
            Log.e(TAG, "installFromUrl failed", e)
            false to "下载失败: ${e.message}"
        }
    }

    /**
     * 核心：把 zip 字节写入临时文件，用 PluginManager 安装
     * 复用现有 installFromZip 流程
     */
    private fun installZipBytes(
        bytes: ByteArray,
        filename: String,
    ): Pair<Boolean, String> {
        // 写到 cacheDir，因为 PluginManager 用 ContentResolver 读 Uri
        val tmpFile = File(cacheDir, "market_install_${System.currentTimeMillis()}.zip")
        return try {
            FileOutputStream(tmpFile).use { it.write(bytes) }
            val pm = PluginManager(this)
            val result = pm.install(android.net.Uri.fromFile(tmpFile))
            if (result.success) {
                Log.i(TAG, "install success: ${result.pluginId} (${result.name})")
                true to "已安装: ${result.name}"
            } else {
                Log.w(TAG, "install failed: ${result.message}")
                false to result.message
            }
        } catch (e: Exception) {
            Log.e(TAG, "installZipBytes failed", e)
            false to "安装异常: ${e.message}"
        } finally {
            try {
                tmpFile.delete()
            } catch (_: Exception) {
            }
        }
    }
}
