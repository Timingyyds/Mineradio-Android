package io.wallpaperengine.weutil;

public class VirtualFileRange {
    public long vOffset;
    public long vSize;
    public String fileName;

    public VirtualFileRange() {
    }

    public VirtualFileRange(long offset, long size) {
        this.vOffset = offset;
        this.vSize = size;
    }
}
