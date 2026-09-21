package com.mineradio.desktop.nodejs;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Mineradio Android Node.js 运行时(基于 nodejs-mobile-react-native 18.20.4 剥离 RN 依赖)
 * 启动内置 server.js 提供 /api/* HTTP 服务,前端 WebView 后端通过 http://127.0.0.1:3000 访问。
 *
 * 由 ui设计_MR 从 mineradio-android 移植。nodejs-project 资产为明文(不加密)，
 * 因此本实现直接复制资产，无需解密。
 */
public class NodeJSMobile {

    private static final String TAG = "MineradioNode";
    private static final String NODEJS_PROJECT_DIR = "nodejs-project";
    private static final String BUILTIN_MODULES_DIR = "nodejs-builtin_modules";
    private static final String TRASH_DIR = "nodejs-project-trash";
    private static final String SHARED_PREFS = "MINERADIO_NODE_MOBILE_PREFS";
    private static final String LAST_UPDATED_TIME = "MINERADIO_NODE_APK_LastUpdateTime";
    private static final String SYSTEM_CHANNEL = "_SYSTEM_";

    private static String filesDirPath;
    private static String nodeJsProjectPath;
    private static String builtinModulesPath;
    private static String trashDirPath;

    private static long lastUpdateTime = 1;
    private static long previousLastUpdateTime = 0;
    private static final Semaphore initSemaphore = new Semaphore(1);
    private static boolean initCompleted = false;

    private static AssetManager assetManager;
    private static Context appContext;

    private static volatile boolean nodeStarted = false;
    private static volatile boolean nodeReady = false;
    private static NodeReadyListener readyListener;

    static {
        Log.i(TAG, "NodeJSMobile static block loading libs...");
        try {
            System.loadLibrary("node");
            Log.i(TAG, "libnode.so loaded");
        } catch (Throwable t) {
            Log.e(TAG, "loadLibrary(node) failed", t);
            throw t;
        }
        try {
            System.loadLibrary("mineradio-node-native");
            Log.i(TAG, "libmineradio-node-native.so loaded");
        } catch (Throwable t) {
            Log.e(TAG, "loadLibrary(mineradio-node-native) failed", t);
            throw t;
        }
    }

    public interface NodeReadyListener {
        void onNodeReady();
        void onNodeFailed(String reason);
    }

    public static synchronized void start(Context context, NodeReadyListener listener) {
        Log.i(TAG, "NodeJSMobile.start() called, nodeStarted=" + nodeStarted);
        if (nodeStarted) {
            if (nodeReady && listener != null) listener.onNodeReady();
            return;
        }
        nodeStarted = true;
        appContext = context.getApplicationContext();
        readyListener = listener;

        filesDirPath = appContext.getFilesDir().getAbsolutePath();
        nodeJsProjectPath = filesDirPath + "/" + NODEJS_PROJECT_DIR;
        builtinModulesPath = filesDirPath + "/" + BUILTIN_MODULES_DIR;
        trashDirPath = filesDirPath + "/" + TRASH_DIR;

        try {
            Os.setenv("TMPDIR", appContext.getCacheDir().getAbsolutePath(), true);
        } catch (ErrnoException e) {
            Log.w(TAG, "setenv TMPDIR failed", e);
        }
        // ★ 设置 HOME，使 start.js 中基于 HOME 的路径解析到当前应用文件目录
        try {
            Os.setenv("HOME", filesDirPath, true);
        } catch (ErrnoException e) {
            Log.w(TAG, "setenv HOME failed", e);
        }

        registerNodeDataDirPath(filesDirPath);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (wasAPKUpdated()) {
                        initSemaphore.acquire();
                        try {
                            emptyTrash();
                            copyNodeJsAssets();
                            initCompleted = true;
                        } finally {
                            initSemaphore.release();
                            emptyTrash();
                        }
                    } else {
                        initCompleted = true;
                    }

                    // 设置 server.js 需要的环境变量
                    String dataDir = filesDirPath + "/mineradio-data";
                    new File(dataDir).mkdirs();
                    Os.setenv("HOST", "127.0.0.1", true);
                    Os.setenv("PORT", "3000", true);
                    Os.setenv("COOKIE_FILE", dataDir + "/.cookie", true);
                    Os.setenv("QQ_COOKIE_FILE", dataDir + "/.qq-cookie", true);
                    Os.setenv("KUGOU_COOKIE_FILE", dataDir + "/.kugou-cookie", true);
                    Os.setenv("QISHUI_COOKIE_FILE", dataDir + "/.qishui-cookie", true);
                    Os.setenv("QISHUI_TOKEN_FILE", dataDir + "/.qishui-token", true);
                    Os.setenv("QISHUI_QR_CONFIG_FILE", dataDir + "/.qishui-qr-login.json", true);
                    Os.setenv("SPOTIFY_TOKEN_FILE", dataDir + "/.spotify-token.json", true);
                    Os.setenv("SPOTIFY_CONFIG_FILE", dataDir + "/.spotify-credentials.json", true);
                    Os.setenv("MINERADIO_BEAT_CACHE_DIR", dataDir + "/beatmaps", true);
                    Os.setenv("CUEFIELD_FEEDBACK_FILE", dataDir + "/cuefield-feedback.jsonl", true);
                    Os.setenv("MINERADIO_LISTEN_SYNC_FILE", dataDir + "/listen-sync-journal.json", true);
                    Os.setenv("MINERADIO_IS_ANDROID", "1", true);
                    new File(dataDir + "/beatmaps").mkdirs();

                    Log.i(TAG, "Starting node with start.js...");
                    int exitCode = startNodeWithArguments(
                        new String[]{"node", nodeJsProjectPath + "/start.js"},
                        nodeJsProjectPath,
                        true
                    );
                    Log.e(TAG, "Node exited with code " + exitCode);
                    if (readyListener != null) readyListener.onNodeFailed("Node exited: " + exitCode);
                } catch (Throwable t) {
                    Log.e(TAG, "Node start failed", t);
                    if (readyListener != null) readyListener.onNodeFailed(Log.getStackTraceString(t));
                }
            }
        }, "NodeJSMobile-Start").start();
    }

    // 由 JNI 回调(Node 进程内 server.js listen 成功后通过 channel 通知)
    public static void sendMessageToApplication(String channelName, String msg) {
        if (SYSTEM_CHANNEL.equals(channelName)) {
            if ("ready-for-app-events".equals(msg)) {
                nodeReady = true;
                Log.i(TAG, "Node is ready for app events");
            } else if ("server-listening".equals(msg)) {
                nodeReady = true;
                Log.i(TAG, "Server is listening on 127.0.0.1:3000");
                if (readyListener != null) {
                    final NodeReadyListener l = readyListener;
                    new Thread(new Runnable() {
                        @Override
                        public void run() { l.onNodeReady(); }
                    }).start();
                }
            }
        }
    }

    public static boolean isNodeReady() { return nodeReady; }

    // ===== Native 方法(JNI 包名对应 com.mineradio.desktop.nodejs.NodeJSMobile) =====
    public static native void registerNodeDataDirPath(String dataDir);
    public static native String getCurrentABIName();
    public static native int startNodeWithArguments(String[] arguments, String modulesPath, boolean redirectOutputToLogcat);
    public static native void sendMessageToNodeChannel(String channelName, String msg);

    // ===== 资产复制逻辑(明文资产,直接复制) =====
    private static boolean wasAPKUpdated() {
        SharedPreferences prefs = appContext.getSharedPreferences(SHARED_PREFS, Context.MODE_PRIVATE);
        previousLastUpdateTime = prefs.getLong(LAST_UPDATED_TIME, 0);
        try {
            PackageInfo pi = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
            lastUpdateTime = pi.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "getPackageInfo failed", e);
        }
        return lastUpdateTime != previousLastUpdateTime;
    }

    private static void saveLastUpdateTime() {
        SharedPreferences prefs = appContext.getSharedPreferences(SHARED_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putLong(LAST_UPDATED_TIME, lastUpdateTime).commit();
    }

    private static void emptyTrash() {
        File trash = new File(trashDirPath);
        if (trash.exists()) deleteFolderRecursively(trash);
    }

    private static boolean deleteFolderRecursively(File file) {
        boolean res = true;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) res &= deleteFolderRecursively(child);
                else res &= child.delete();
            }
        }
        return res && file.delete();
    }

    private static void copyNodeJsAssets() throws IOException {
        assetManager = appContext.getAssets();
        File nodeDir = new File(nodeJsProjectPath);
        if (nodeDir.exists()) {
            File trash = new File(trashDirPath);
            nodeDir.renameTo(trash);
        }
        copyAssetFolder(NODEJS_PROJECT_DIR, nodeJsProjectPath);
        // 内置模块(可选,libnode.so 已编译内置模块; assets 无则跳过)
        File bmDir = new File(builtinModulesPath);
        if (bmDir.exists()) deleteFolderRecursively(bmDir);
        try {
            copyAssetFolder(BUILTIN_MODULES_DIR, builtinModulesPath);
            Log.i(TAG, "builtin_modules copied");
        } catch (IOException e) {
            Log.i(TAG, "builtin_modules not in assets, skipping (libnode has builtins compiled in)");
        }

        saveLastUpdateTime();
        Log.i(TAG, "Node assets copied to " + nodeJsProjectPath);
    }

    private static void copyAssetFolder(String fromPath, String toPath) throws IOException {
        String[] children = assetManager.list(fromPath);
        if (children == null || children.length == 0) {
            copyAsset(fromPath, toPath);
        } else {
            new File(toPath).mkdirs();
            for (String c : children) copyAssetFolder(fromPath + "/" + c, toPath + "/" + c);
        }
    }

    private static void copyAsset(String fromPath, String toPath) throws IOException {
        new File(toPath).getParentFile().mkdirs();
        InputStream in = null;
        OutputStream out = null;
        try {
            in = assetManager.open(fromPath);
            new File(toPath).createNewFile();
            out = new FileOutputStream(toPath);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            if (in != null) try { in.close(); } catch (IOException e) {}
            if (out != null) try { out.flush(); out.close(); } catch (IOException e) {}
        }
    }
}