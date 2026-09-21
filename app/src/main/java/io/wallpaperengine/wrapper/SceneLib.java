package io.wallpaperengine.wrapper;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Point;
import android.graphics.PointF;

import io.wallpaperengine.weutil.VirtualFileRange;
import io.wallpaperengine.weutil.WallpaperInfoSparse;

public class SceneLib {
    public static final String TAG = "SceneLib";

    private static boolean isInitialized = false;
    public static AssetManager sharedAssetManager;

    static {
        try {
            // ★ 设置时区为 Asia/Shanghai，确保 native 层 localtime() 使用正确时区
            //   不设置的话 native 层可能使用 UTC，导致场景壁纸时间显示不对（日期对但时间差8小时）
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            System.setProperty("user.timezone", "Asia/Shanghai");
            // ★ 同时设置 TZ 环境变量，影响 native C++ 层的 localtime()（CST-8 = 中国标准时间 UTC+8）
            try {
                android.system.Os.setenv("TZ", "CST-8", true);
            } catch (Throwable ignore) {
            }
            System.loadLibrary("scenejni");
        } catch (Throwable t) {
            android.util.Log.e(TAG, "Failed to load scenejni", t);
        }
    }

    public SceneLib() {
    }

    public static boolean isInitialized() {
        return isInitialized;
    }

    public static void setInitialized(boolean z) {
        isInitialized = z;
    }

    public static AssetManager getSharedAssetManager() {
        return sharedAssetManager;
    }

    public static void setSharedAssetManager(AssetManager assetManager) {
        sharedAssetManager = assetManager;
    }

    private native void init(String str, String str2, AssetManager assetManager);

    public synchronized void initLibrary(Context context) {
        if (isInitialized) {
            return;
        }
        try {
            sharedAssetManager = context.getAssets();
            String filesDir = context.getFilesDir().getAbsolutePath();
            // ★ 使用当前系统时间戳（秒级）替代硬编码 buildTime
            //   原版使用硬编码的 APK 构建时间，会导致 V8 引擎 Date 对象时间不随系统时间更新
            //   改用当前时间戳后，V8 的 new Date() 能正确获取当前系统时间
            long currentTimeSec = System.currentTimeMillis() / 1000L;
            String timeStr = String.valueOf(currentTimeSec);
            init(filesDir, timeStr, sharedAssetManager);
            isInitialized = true;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "initLibrary failed", t);
        }
    }

    public native int initContext(Context context);

    public native void destroyContext(int i);

    public native void initScene(int i, String str);

    public native void shutdownScene(int i);

    public native void cancelLoadingScene(int i);

    public native void updateScene(int i);

    public native void resizeScene(int i, int i2, int i3);

    public native void sendTouchInput(int i, boolean z, float f, float f2);

    public native void sendGravityInput(int i, float f, float f2, float f3);

    public native void sendAudioData(int i, float[] fArr);

    public native void sendForceInput(int i, float f, float f2);

    public native void sendFreeAlignmentXForm(int i, float[] fArr);

    public native void sendNormalizedParallaxOffset(int i, boolean z, float f, float f2, float f3, float f4);

    public native String getSceneProperties(int i);

    public native void applySceneProperties(int i, String str);

    public native int getSceneFeatureFlags(int i);

    public native boolean getSceneCanvasSize(int i, PointF pointF);

    public native String getWallpaperType(String str);

    public native WallpaperInfoSparse getWallpaperInfoSparse(String str);

    public native byte[] getWallpaperInfoPreviewBitmap(String str);

    public native Point getWallpaperResolution(String str);

    public native String getWallpaperProjectString(String str, String str2);

    public native VirtualFileRange getWallpaperFileVirtualOffset(String str);

    public native boolean isWallpaperVersionValid(String str);

    public native WallpaperInfoSparse enumerateWallpapers();

    public native String getLocalization(String str);

    public native String getUserLocalizations(String str);

    public native void setLanguage(String str);

    public native void setLogToFileEnabled(boolean z);
}
