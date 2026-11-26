package io.wallpaperengine.weutil;

import java.io.InputStream;

/**
 * 范围输入流 - 从 InputStream 的指定偏移开始，只读取指定大小的数据。
 * SupportFileLoader.loadBitmapFromVFS 依赖此类从 mpkg 虚拟文件系统中按偏移读取图片。
 */
public final class RangedInputStream extends InputStream {
    private int bytesLeft;
    private final int maxSize;
    private final InputStream original;

    public RangedInputStream(InputStream original, int offset, int maxSize) {
        if (original == null) throw new IllegalArgumentException("original == null");
        this.bytesLeft = offset;
        this.original = original;
        this.maxSize = maxSize;
        try {
            original.skip(offset);
        } catch (Exception ignored) {
        }
    }

    @Override
    public int read() {
        if (this.bytesLeft <= 0) {
            return -1;
        }
        this.bytesLeft--;
        try {
            return this.original.read();
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) {
        if (b == null) throw new IllegalArgumentException("b == null");
        int i = this.bytesLeft;
        if (i <= 0) {
            return -1;
        }
        try {
            int read = this.original.read(b, off, Math.min(len, i));
            if (read > 0) {
                int i2 = this.bytesLeft - read;
                this.bytesLeft = Math.max(0, i2);
            }
            return read;
        } catch (Exception e) {
            return -1;
        }
    }
}
