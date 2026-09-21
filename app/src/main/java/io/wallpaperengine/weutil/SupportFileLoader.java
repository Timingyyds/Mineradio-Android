package io.wallpaperengine.weutil;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * 文件加载器 - native 库 libscenejni.so 通过 JNI 回调此类。
 * 缺失会导致 initScene 时 FindClass 失败 → SIGABRT 闪退。
 *
 * native 层通过以下 JNI 签名调用：
 *   - loadBitmapFromUri(String, Context) -> BitmapFileResponse
 *   - loadBitmapFromVFS(String, int, int) -> BitmapFileResponse
 *   - loadDataFromUri(String, Context) -> byte[]
 *   - isUriValid(String, Context) -> boolean
 *   - getUriFileExtension(String, Context) -> String
 */
public final class SupportFileLoader {

    public static final Companion INSTANCE = new Companion();

    public static String getUriFileExtension(String uri, Context context) {
        return INSTANCE.getUriFileExtension(uri, context);
    }

    public static boolean isUriValid(String uri, Context context) {
        return INSTANCE.isUriValid(uri, context);
    }

    public static BitmapFileResponse loadBitmapFromUri(String uri, Context context) {
        return INSTANCE.loadBitmapFromUri(uri, context);
    }

    public static BitmapFileResponse loadBitmapFromVFS(String vfsSource, int offset, int size) {
        return INSTANCE.loadBitmapFromVFS(vfsSource, offset, size);
    }

    public static byte[] loadDataFromUri(String uri, Context context) {
        return INSTANCE.loadDataFromUri(uri, context);
    }

    /**
     * 位图文件响应 - native 层通过 JNI 直接读取 w/h/data 字段。
     * 字段名和类型必须与原版完全一致，否则 native 层 GetFieldID 失败。
     */
    public static final class BitmapFileResponse {
        public int w;
        public int h;
        public byte[] data = new byte[0];

        public int getW() { return w; }
        public void setW(int w) { this.w = w; }
        public int getH() { return h; }
        public void setH(int h) { this.h = h; }
        public byte[] getData() { return data; }
        public void setData(byte[] data) {
            if (data == null) data = new byte[0];
            this.data = data;
        }
    }

    public static final class Companion {
        public boolean isUriValid(String uri, Context context) {
            if (uri == null || context == null) return false;
            try {
                InputStream is = context.getContentResolver().openInputStream(Uri.parse(uri));
                if (is != null) {
                    is.close();
                }
                return true;
            } catch (Throwable unused) {
                return false;
            }
        }

        public String getUriFileExtension(String uri, Context context) {
            if (uri == null || context == null) return "";
            String result = "";
            try {
                android.database.Cursor cursor = context.getContentResolver().query(
                        Uri.parse(uri), null, null, null, null);
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            int idx = cursor.getColumnIndex("_display_name");
                            if (idx >= 0) {
                                result = cursor.getString(idx);
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }
            } catch (Throwable ignored) {
            }
            return result;
        }

        public BitmapFileResponse loadBitmapFromUri(String uri, Context context) {
            if (uri == null || context == null) return null;
            try {
                InputStream is = context.getContentResolver().openInputStream(Uri.parse(uri));
                if (is == null) return null;
                Bitmap bmp = BitmapFactory.decodeStream(is);
                try { is.close(); } catch (Exception ignored) {}
                return bitmapToResponse(bmp);
            } catch (Throwable unused) {
                return null;
            }
        }

        public BitmapFileResponse loadBitmapFromVFS(String vfsSource, int offset, int size) {
            if (vfsSource == null) return null;
            try {
                RangedInputStream ris = new RangedInputStream(new FileInputStream(vfsSource), offset, size);
                Bitmap bmp = BitmapFactory.decodeStream(ris);
                try { ris.close(); } catch (Exception ignored) {}
                return bitmapToResponse(bmp);
            } catch (Throwable unused) {
                return null;
            }
        }

        public byte[] loadDataFromUri(String uri, Context context) {
            if (uri == null || context == null) return null;
            try {
                InputStream is = context.getContentResolver().openInputStream(Uri.parse(uri));
                if (is == null) return null;
                try {
                    return readBytes(is);
                } finally {
                    try { is.close(); } catch (Exception ignored) {}
                }
            } catch (Exception unused) {
                return null;
            }
        }

        private static BitmapFileResponse bitmapToResponse(Bitmap bmp) {
            if (bmp == null) return null;
            try {
                BitmapFileResponse resp = new BitmapFileResponse();
                resp.w = bmp.getWidth();
                resp.h = bmp.getHeight();
                ByteBuffer buf = ByteBuffer.allocate(bmp.getByteCount());
                bmp.copyPixelsToBuffer(buf);
                buf.rewind();
                resp.data = buf.array();
                bmp.recycle();
                return resp;
            } catch (Throwable t) {
                return null;
            }
        }

        private static byte[] readBytes(InputStream is) throws Exception {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
