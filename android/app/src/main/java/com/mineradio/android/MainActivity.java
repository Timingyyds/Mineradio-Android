package com.mineradio.android;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;
import com.mineradio.android.wallpaper.MpkgBackgroundManager;

/**
 * Folia Android 全屏沉浸容器：
 * - 真全屏（状态栏/导航栏全隐藏，IMMERSIVE_STICKY + WindowInsetsController 双通道）；
 * - 刘海屏 / 挖孔屏适配（API 30+ ALWAYS，低版本 SHORT_EDGES）；
 * - WebView：混合内容放行 + 65% 等比缩放（wideViewPort + overviewMode + initialScale）；
 * - 注册 FoliaBridge（网络层扩展）与 FoliaNative（本地音乐 MediaStore/SAF + LocalAudioServer）。
 */
public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 调试包开放 WebView 远程调试（Chrome DevTools / CDP），便于在真机上直接排查界面与网络问题。
        boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true);
        }
        registerPlugin(FoliaBridgePlugin.class);
        registerPlugin(FoliaCorePlugin.class);
        registerPlugin(MpkgWallpaperPlugin.class);
        registerPlugin(PluginsPlugin.class);
        registerPlugin(SystemToolsPlugin.class);
        super.onCreate(savedInstanceState);
        // ★ 窗口背景设为透明：主题里的 windowBackground(#09090b) 是不透明的，
        //   会盖住垫在 WebView 之下的 GLSurfaceView（场景壁纸），
        //   表现为场景只露出一部分、其余被窗口底色挡住。
        //   参考可正常显示的工程 LandscapeWebActivity 同样先做这一步。
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().getDecorView().setBackgroundColor(Color.TRANSPARENT);
        // 应用在前台时保持屏幕常亮：FLAG_KEEP_SCREEN_ON 只作用于本窗口，
        // 切到后台或锁屏后系统会自动忽略它，不需要额外监听生命周期去清。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Bridge bridge = getBridge();
        if (bridge != null && bridge.getWebView() != null) {
            WebSettings ws = bridge.getWebView().getSettings();
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            // 整体缩放的三个开关，缺一不可，作用各不相同：
            //   setUseWideViewPort(true)      → 让 viewport meta 里算出来的 width 真正生效
            //   setLoadWithOverviewMode(true) → 把「按那个 width 排出来的宽页面」缩放到屏幕宽度
            //   viewport width = 屏幕宽 / SCALE（数值见 index.html 顶部脚本）
            // 三者配合的结果才是 SCALE 倍缩放：布局按宽页面排，WebView 再等比缩到铺满。
            //
            // 之前把 overviewMode 关掉是错的（以为它会覆盖 width）：关掉之后
            // 页面按宽布局排版却完全不缩放，内容直接溢出屏幕右侧 —— 也就是「怎么还是这么大」。
            ws.setUseWideViewPort(true);
            ws.setLoadWithOverviewMode(true);
            ws.setSupportZoom(false);
            ws.setBuiltInZoomControls(false);
            // ★ 界面固定：布局比可视区宽时 WebView 默认允许整页左右拖动，
            //   这里关掉横向滚动条与边缘回弹，配合 CSS touch-action:pan-y 彻底钉住。
            bridge.getWebView().setHorizontalScrollBarEnabled(false);
            bridge.getWebView().setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
            // 关掉 WebView 的默认点击高亮：安卓上点任何元素都会闪一块半透明方块，
            // 桌面版的界面里没有这种东西，看着像网页而不是播放器。
            bridge.getWebView().setDefaultFocusHighlightEnabled(false);
            bridge.getWebView().setOnFocusChangeListener(null);
            // 【性能】渲染线程优先级拉满（大肥鱼 2026-11-03）:
            //  本 App 是 GLSurfaceView 垫底 + WebView 叠加的双层合成场景,
            //  部分 ROM 的调度器会把 WebView 渲染线程当普通线程压低,
            //  表现为点击/滑动掉帧。setRenderPriority(HIGH) 把 WebCore
            //  渲染线程提到高优先级档（deprecated 但仍生效,且是本 SDK
            //  android.jar 里唯一可用的渲染优先级开关）。
            ws.setRenderPriority(WebSettings.RenderPriority.HIGH);
            // 【性能】渲染进程优先级策略（API 26+）：
            //  WebView 的渲染进程默认是 RENDERER_PRIORITY_BOUND —— 页面不可见/切后台时
            //  被系统降级，回前台要重新提档、重建光栅树，用户看到的就是「切回来卡一下、
            //  衔接不流畅」。拉到 IMPORTANT 且不允许降级，让渲染进程始终满档调度
            //  （WebView 的渲染本来就是 GPU 合成 + 渲染进程多线程并行，这里做的是
            //  把调度优先级拉满、不让系统削它）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                bridge.getWebView().setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);

            }
            // 不要再调 setInitialScale：它和 viewport 的 initial-scale 会叠加，
            // 缩放倍率就变成两处相乘，控制不住。缩放只留上面那三个开关。
        }
        // ★ MPKG 场景壁纸的程序内背景层：GLSurfaceView 垫在 WebView 之下
        if (bridge != null && bridge.getWebView() != null) {
            MpkgBackgroundManager.INSTANCE.init(this, bridge.getWebView());
            // 检测上次是否崩在 initScene（native SIGSEGV 抓不到，只能启动时查哨兵）
            MpkgBackgroundManager.INSTANCE.checkLastCrash();
            // 恢复上次的 MPKG 应用内背景（原生工程 checkAndStartMpkgBackground 的等价实现）
            MpkgBackgroundManager.INSTANCE.restoreSavedBackground();
        }
        applyFullscreen();
        applyCutoutMode();
        getWindow().getDecorView().postDelayed(this::applyFullscreen, 500);
        getWindow().getDecorView().postDelayed(this::applyCutoutMode, 500);
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) applyFullscreen();
        });
        // 返回键 / 全面屏返回手势先交给 WebView：JS 那边的层（弹窗、搜索、播放页、集合栈）退一层，
        // 真的回到主界面时由 JS 调 exitApp。emitBackPressed() 返回 false 说明 JS 还没装监听，
        // 这时才走系统默认（结束 Activity）。
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (FoliaCorePlugin.emitBackPressed()) return;
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        MpkgBackgroundManager.INSTANCE.onResume();
        // 部分刘海 / 挖孔机型在切前台、旋转或厂商系统栏动画后会自己把状态栏弹回来，
        // 这里每次回前台都重下一遍全屏与刘海模式，保证各机型表现一致。
        applyFullscreen();
        applyCutoutMode();
        getWindow().getDecorView().postDelayed(this::applyFullscreen, 300);
        // ★ WebView 在部分机型上切回前台不会补发 visibilitychange，
        //   JS 侧的 render-deep-sleep（隐藏 3D 视觉层）会一直残留 → 整个界面看起来全黑。
        //   这里回前台主动让 JS 重新计算渲染状态并恢复画面。
        final WebView wv = getBridge() != null ? getBridge().getWebView() : null;
        if (wv != null) {
            wv.postDelayed(() -> wv.evaluateJavascript(
                    "try{if(document.body.classList.contains('render-deep-sleep')){" +
                            "if(typeof updateRenderPowerClasses==='function')updateRenderPowerClasses();" +
                            "if(typeof recoverVisualsAfterBackground==='function')recoverVisualsAfterBackground('app-resume');" +
                            "}}catch(e){}",
                    null), 400);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        MpkgBackgroundManager.INSTANCE.onPause();
    }

    @Override
    public void onDestroy() {
        MpkgBackgroundManager.INSTANCE.destroy();
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        // 触摸转发给 GL 背景层（它在 WebView 底下，收不到事件）
        MpkgBackgroundManager.INSTANCE.forwardTouch(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyFullscreen();
            applyCutoutMode();
        }
    }

    private void applyFullscreen() {
        View decor = getWindow().getDecorView();
        // 每次全屏刷新时顺带保持 DecorView 透明：Capacitor 的 SystemBars 会在
        // 切状态栏样式时按主题 windowBackground 回填背景色，不重设就会盖住场景壁纸的 GL 层。
        decor.setBackgroundColor(Color.TRANSPARENT);
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    private void applyCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                attributes.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } else {
                attributes.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            getWindow().setAttributes(attributes);
        }
    }
}
