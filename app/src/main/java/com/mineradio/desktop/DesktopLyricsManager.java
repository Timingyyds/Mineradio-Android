package com.mineradio.desktop;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 桌面歌词悬浮窗管理器 (Android 端实现)
 * - 通过 WindowManager 添加悬浮窗
 * - 使用原生 TextView 渲染歌词 (背景完全透明, 无 WebView 方框问题)
 * - 前端通过 AndroidExternal JS 接口控制
 */
public class DesktopLyricsManager {
    private static final String TAG = "DesktopLyrics";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private FrameLayout overlayView;
    private TextView lyricsTextView;
    private boolean isShowing = false;
    private boolean isDragging = false;
    private float touchStartX, touchStartY;
    private float windowStartX, windowStartY;

    // 默认位置: 屏幕中央
    private int posX = 0;
    private int posY = 0;

    // ★ 用户是否已手动拖动过悬浮窗
    //   true → 后续 setPosition 调用被忽略，避免前端 payload.y 把窗口拉回原位（"回弹"问题）
    //   show() 时重置为 false，允许初始定位生效
    private boolean userMoved = false;

    // ★ 是否锁定（点击穿透，不可拖动）
    private boolean locked = false;

    // ★ 悬浮窗 LayoutParams（用于锁定时更新 flags）
    private WindowManager.LayoutParams overlayParams;

    // ★ 位置持久化（SharedPreferences）：记住用户拖动后的位置，重启后恢复
    private SharedPreferences prefs;
    private static final String PREFS_NAME = "desktop_lyrics_prefs";
    private static final String KEY_POS_X = "pos_x";
    private static final String KEY_POS_Y = "pos_y";
    private static final String KEY_USER_MOVED = "user_moved";

    // ★ 高亮跟随缓存：避免 50ms 高频 setText 导致闪烁
    private int lastHighlightCount = -1;
    private String lastRenderedText = null;

    // 屏幕参数
    private float screenDensity = 1.0f;
    private int defaultFontSize = 22;

    // 当前样式状态
    private String currentColor = "#f6fdff";
    private String currentHighlightColor = "#fff0b8";
    private int currentFontSize = 0;
    private float currentOpacity = 0.92f;
    // ★ 默认空字符串：未收到歌词前不显示任何文字（去除"Mineradio"软件名占位）
    private String currentText = "";
    private boolean currentHighlight = false;
    // ★ 当前进度（0-1），用于"高亮跟随"逐字高亮已唱过部分
    private float currentProgress = 0f;
    // ★ 是否启用高亮跟随（true=按 progress 逐字高亮，false=整体单色）
    private boolean highlightFollow = false;

    public DesktopLyricsManager(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        // ★ 初始化位置持久化：读取上次拖动后的位置
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_USER_MOVED, false)) {
            this.posX = prefs.getInt(KEY_POS_X, 0);
            this.posY = prefs.getInt(KEY_POS_Y, 0);
            this.userMoved = true;
            Log.i(TAG, "Restored position: x=" + posX + ", y=" + posY);
        }
    }

    /** 检查是否有悬浮窗权限 */
    public boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    /** 显示桌面歌词悬浮窗 */
    public boolean show() {
        if (!hasOverlayPermission()) {
            Log.w(TAG, "No overlay permission");
            return false;
        }

        // ★ 不重置 userMoved：保留持久化位置/会话内拖动位置，避免"回弹"到初始位置
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                // ★ 防止重复浮窗：如果已存在旧 view，先同步移除
                if (overlayView != null) {
                    try {
                        if (windowManager != null) windowManager.removeView(overlayView);
                    } catch (Exception e) {
                        Log.w(TAG, "show: remove stale view failed", e);
                    }
                    overlayView = null;
                    lyricsTextView = null;
                }
                createOverlayView();
                addOverlayToWindow();
                isShowing = true;
                Log.i(TAG, "Desktop lyrics overlay shown, userMoved=" + userMoved + ", pos=" + posX + "," + posY);
            }
        });
        return true;
    }

    /** 隐藏桌面歌词悬浮窗 */
    public void hide() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isShowing) return;
                try {
                    if (overlayView != null && windowManager != null) {
                        windowManager.removeView(overlayView);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "hide failed", e);
                }
                lyricsTextView = null;
                overlayView = null;
                overlayParams = null;
                isShowing = false;
                // ★ 重置渲染缓存，避免下次 show() 时缓存脏数据
                lastRenderedText = null;
                lastHighlightCount = -1;
                Log.i(TAG, "Desktop lyrics overlay hidden");
            }
        });
    }

    /**
     * 更新歌词内容（含进度，支持"高亮跟随"逐字高亮）
     *
     * @param text           歌词文本
     * @param progress       当前歌词进度 0~1（highlightFollow=true 时有效）
     * @param highlightFollow 是否启用高亮跟随：
     *                        true → 前 progress 比例字符用 highlightColor，其余用 primaryColor
     *                        false → 整体用 primaryColor（若 highlight=true 则整体用 highlightColor）
     * @param highlight      仅在 highlightFollow=false 时生效，整体高亮色
     */
    public void updateLyric(final String text, final float progress, final boolean highlightFollow, final boolean highlight) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                String newText = text != null ? text : "";
                float p = progress < 0 ? 0 : (progress > 1 ? 1 : progress);
                // 文本或样式变化才更新 UI
                boolean textChanged = !newText.equals(currentText);
                currentText = newText;
                currentProgress = p;
                DesktopLyricsManager.this.highlightFollow = highlightFollow;
                currentHighlight = highlight;

                if (lyricsTextView == null || !isShowing) {
                    Log.d(TAG, "updateLyric deferred (showing=" + isShowing + "): " + newText);
                    return;
                }
                Log.i(TAG, "updateLyric exec: text=" + newText + ", progress=" + p +
                    ", follow=" + highlightFollow + ", highlight=" + highlight);

                // 构建 Spannable 实现逐字高亮
                applyLyricText(textChanged);
            }
        });
    }

    /** 应用歌词文字到 TextView（含逐字高亮跟随逻辑） */
    private void applyLyricText(boolean textChanged) {
        if (lyricsTextView == null) return;
        if (currentText.isEmpty()) {
            if (lastRenderedText == null || !lastRenderedText.isEmpty()) {
                lyricsTextView.setText("");
                lastRenderedText = "";
                lastHighlightCount = -1;
            }
            return;
        }

        int primary = parseColor(currentColor);
        int highlight = parseColor(currentHighlightColor);

        // 计算本次高亮字符数
        int highlightCount = 0;
        if (highlightFollow && currentProgress > 0) {
            int total = currentText.length();
            highlightCount = Math.round(total * currentProgress);
            if (highlightCount > total) highlightCount = total;
        } else if (currentHighlight) {
            highlightCount = currentText.length(); // 整体高亮
        }

        // ★ 防闪烁：文本未变且高亮字符数未变 → 跳过 setText（避免 50ms 高频重绘）
        boolean textSame = !textChanged
            && lastRenderedText != null
            && lastRenderedText.equals(currentText);
        if (textSame && lastHighlightCount == highlightCount) {
            return; // 完全无变化，跳过
        }

        SpannableStringBuilder ssb = new SpannableStringBuilder(currentText);
        if (highlightCount > 0) {
            ssb.setSpan(new ForegroundColorSpan(highlight), 0, highlightCount, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (highlightCount < currentText.length()) {
            ssb.setSpan(new ForegroundColorSpan(primary), highlightCount, currentText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        lyricsTextView.setText(ssb);

        lastRenderedText = currentText;
        lastHighlightCount = highlightCount;
        lyricsTextView.setTag(currentText);
        // 只在文本变化时才重播进入动画
        if (textChanged) {
            playEnterAnimation();
        }
    }

    /** 更新歌词样式 */
    public void updateStyle(final String color, final String highlightColor,
                            final int fontSize, final float opacity) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (color != null) currentColor = color;
                if (highlightColor != null) currentHighlightColor = highlightColor;
                if (fontSize > 0) currentFontSize = fontSize;
                if (opacity >= 0) currentOpacity = opacity;
                if (lyricsTextView == null || !isShowing) {
                    Log.d(TAG, "updateStyle deferred: color=" + currentColor +
                        ", fontSize=" + currentFontSize + ", opacity=" + currentOpacity);
                    return;
                }
                Log.i(TAG, "updateStyle exec: color=" + currentColor +
                    ", fontSize=" + currentFontSize + ", opacity=" + currentOpacity +
                    ", density=" + screenDensity);
                applyStyle();
            }
        });
    }

    /** 设置悬浮窗位置 */
    public void setPosition(final int x, final int y) {
        // ★ 用户已手动拖动过 → 跳过前端 payload 推送的位置更新，避免"回弹"
        if (userMoved) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                posX = x;
                posY = y;
                if (isShowing && overlayView != null && overlayView.getLayoutParams() != null) {
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
                    lp.x = x;
                    lp.y = y;
                    try { windowManager.updateViewLayout(overlayView, lp); } catch (Exception e) {}
                }
            }
        });
    }

    /**
     * 高频进度更新（用于"高亮跟随"平滑逐字高亮）
     * 仅更新 currentProgress 并刷新 TextView 颜色 spans，不重播进入动画
     */
    public void updateProgress(final float progress) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                float p = progress < 0 ? 0 : (progress > 1 ? 1 : progress);
                currentProgress = p;
                if (lyricsTextView == null || !isShowing || !highlightFollow) return;
                applyLyricText(false);
            }
        });
    }

    /**
     * 设置悬浮窗锁定状态
     * @param locked true=锁定（点击穿透，不可拖动）；false=解锁（可拖动）
     */
    public void setLocked(final boolean locked) {
        this.locked = locked;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (overlayView == null || overlayParams == null || windowManager == null) return;
                try {
                    int baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    if (locked) {
                        // 锁定：添加 FLAG_NOT_TOUCHABLE，触摸事件穿透到下层
                        baseFlags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    }
                    overlayParams.flags = baseFlags;
                    windowManager.updateViewLayout(overlayView, overlayParams);
                    Log.i(TAG, "setLocked: " + locked + ", flags=" + overlayParams.flags);
                } catch (Exception e) {
                    Log.e(TAG, "setLocked failed", e);
                }
            }
        });
    }

    public boolean isLocked() {
        return locked;
    }

    /** 应用文字颜色 (根据高亮状态) */
    private void applyTextColor() {
        if (lyricsTextView == null) return;
        int color = parseColor(currentHighlight ? currentHighlightColor : currentColor);
        lyricsTextView.setTextColor(color);
    }

    /** 应用样式 (字体大小、阴影、透明度) */
    private void applyStyle() {
        if (lyricsTextView == null) return;
        // 字体大小: 前端传来的 fontSize 已经是 CSS px (density 后的值), 直接使用 PX 单位
        float fontSizePx = currentFontSize > 0 ? currentFontSize : defaultFontSize;
        lyricsTextView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSizePx);
        // 字体粗细
        lyricsTextView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        // 发光阴影 (模拟 PC 版 text-shadow: 0 0 6px soft, 0 0 14px glow)
        int shadowColor = parseColor(currentColor);
        lyricsTextView.setShadowLayer(fontSizePx * 0.5f, 0, 0, setColorAlpha(shadowColor, 0.6f));
        // 透明度
        lyricsTextView.setAlpha(currentOpacity);
        // 更新文字颜色
        applyTextColor();
        // 重新设置 padding (基于字体大小, 避免阴影被裁剪)
        int pad = (int)(fontSizePx * 0.6f);
        lyricsTextView.setPadding(pad, pad, pad, pad);
    }

    /** 播放进入动画 (模拟 PC 版 lyr-in 动画) */
    private void playEnterAnimation() {
        if (lyricsTextView == null) return;
        AnimationSet set = new AnimationSet(true);
        // 透明度: 0 -> 1
        AlphaAnimation alpha = new AlphaAnimation(0f, 1f);
        alpha.setDuration(820);
        // 位移: 从下方 20px 移动到 0 (模拟 translateY(20px) -> 0)
        TranslateAnimation translate = new TranslateAnimation(
            Animation.RELATIVE_TO_SELF, 0f,
            Animation.RELATIVE_TO_SELF, 0f,
            Animation.ABSOLUTE, 20f,
            Animation.ABSOLUTE, 0f);
        translate.setDuration(820);
        // 缓动: 类似 cubic-bezier(.16,.84,.32,1.02)
        set.setInterpolator(new DecelerateInterpolator(1.5f));
        set.addAnimation(alpha);
        set.addAnimation(translate);
        set.setFillAfter(true);
        lyricsTextView.startAnimation(set);
    }

    /** 解析颜色字符串 (支持 #rgb, #rrggbb, rgb(r,g,b), rgba(r,g,b,a)) */
    private int parseColor(String colorStr) {
        if (colorStr == null || colorStr.isEmpty()) return Color.WHITE;
        try {
            colorStr = colorStr.trim();
            if (colorStr.startsWith("#")) {
                return Color.parseColor(colorStr);
            } else if (colorStr.startsWith("rgb(")) {
                String nums = colorStr.substring(4, colorStr.indexOf(')'));
                String[] parts = nums.split(",");
                int r = Integer.parseInt(parts[0].trim());
                int g = Integer.parseInt(parts[1].trim());
                int b = Integer.parseInt(parts[2].trim());
                return Color.rgb(r, g, b);
            } else if (colorStr.startsWith("rgba(")) {
                String nums = colorStr.substring(5, colorStr.indexOf(')'));
                String[] parts = nums.split(",");
                int r = Integer.parseInt(parts[0].trim());
                int g = Integer.parseInt(parts[1].trim());
                int b = Integer.parseInt(parts[2].trim());
                float a = Float.parseFloat(parts[3].trim());
                return Color.argb((int)(a * 255), r, g, b);
            }
        } catch (Exception e) {
            Log.w(TAG, "parseColor failed: " + colorStr, e);
        }
        return Color.WHITE;
    }

    /** 设置颜色透明度 */
    private int setColorAlpha(int color, float alpha) {
        return Color.argb((int)(alpha * 255), Color.red(color), Color.green(color), Color.blue(color));
    }

    private void createOverlayView() {
        // 创建 FrameLayout 容器
        overlayView = new FrameLayout(context);
        overlayView.setBackgroundColor(0);
        overlayView.setBackground(null);

        // 创建 TextView 显示歌词 (原生 TextView 背景默认完全透明)
        lyricsTextView = new TextView(context);
        lyricsTextView.setBackgroundColor(0);
        lyricsTextView.setBackground(null);
        // ★ 用 applyLyricText 应用文字（含逐字高亮 spans），而非裸 setText
        applyLyricText(false);
        lyricsTextView.setGravity(Gravity.CENTER);
        lyricsTextView.setSingleLine(true);
        lyricsTextView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        // 添加到 FrameLayout
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER);
        overlayView.addView(lyricsTextView, textParams);

        // 应用样式
        applyStyle();

        // 拖拽触摸事件
        lyricsTextView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartX = event.getRawX();
                        touchStartY = event.getRawY();
                        if (overlayView.getLayoutParams() != null) {
                            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
                            windowStartX = lp.x;
                            windowStartY = lp.y;
                        }
                        isDragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchStartX;
                        float dy = event.getRawY() - touchStartY;
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) isDragging = true;
                        if (isDragging && overlayView.getLayoutParams() != null) {
                            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
                            lp.x = (int) (windowStartX + dx);
                            lp.y = (int) (windowStartY + dy);
                            posX = lp.x;
                            posY = lp.y;
                            // ★ 标记用户已拖动，后续 setPosition 调用被忽略，避免回弹
                            userMoved = true;
                            try { windowManager.updateViewLayout(overlayView, lp); } catch (Exception e) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        boolean wasDragging = isDragging;
                        isDragging = false;
                        // ★ 拖动结束 → 持久化保存位置，重启后恢复
                        if (wasDragging) {
                            try {
                                prefs.edit()
                                    .putInt(KEY_POS_X, posX)
                                    .putInt(KEY_POS_Y, posY)
                                    .putBoolean(KEY_USER_MOVED, true)
                                    .apply();
                                Log.i(TAG, "Position saved: x=" + posX + ", y=" + posY);
                            } catch (Exception e) {
                                Log.w(TAG, "save position failed", e);
                            }
                        }
                        return wasDragging;
                }
                return false;
            }
        });
    }

    private void addOverlayToWindow() {
        if (overlayView == null || windowManager == null) return;

        // 获取屏幕参数
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        try {
            windowManager.getDefaultDisplay().getMetrics(metrics);
        } catch (Exception e) {
            metrics.widthPixels = 1080;
            metrics.heightPixels = 1920;
            metrics.density = 2.0f;
        }

        screenDensity = metrics.density;
        // 默认字体大小: 19.2sp * density (文字大小加倍后再乘1.2)
        defaultFontSize = Math.round(19.2f * screenDensity);

        Log.i(TAG, "Screen metrics: w=" + metrics.widthPixels + ", h=" + metrics.heightPixels +
            ", density=" + screenDensity + ", defaultFontSize=" + defaultFontSize);

        // 使用 WRAP_CONTENT 让悬浮窗只包裹文字, 避免大方框
        WindowManager.LayoutParams params;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        } else {
            params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        }

        // 使用 CENTER 让悬浮窗在屏幕中央显示
        params.gravity = Gravity.CENTER;
        params.x = posX;
        params.y = posY;

        try {
            windowManager.addView(overlayView, params);
            overlayParams = params;
            Log.i(TAG, "Overlay added successfully, WRAP_CONTENT, gravity=CENTER");
            // ★ 不在此处播放进入动画，由 applyLyricText(textChanged=true) 统一处理，避免重复闪烁
        } catch (Exception e) {
            Log.e(TAG, "addView failed", e);
        }
    }

    public boolean isShowing() {
        return isShowing;
    }
}
