package com.pixelmosaic.pipeline;

import java.util.BitSet;

final class RequestBuffers {

    static final int DEFAULT_MAX_PIXELS = 2_000_000;

    final long[] sourceData;
    final long[] targetData;
    final int[] sourceRaster;
    final int[] targetRaster;
    BitSet sourceMask;
    BitSet targetMask;

    int srcWidth, srcHeight, srcPixelCount;
    int tgtWidth, tgtHeight, tgtPixelCount;

    RequestBuffers() {
        this(DEFAULT_MAX_PIXELS);
    }

    RequestBuffers(int maxPixels) {
        sourceData = new long[maxPixels];
        targetData = new long[maxPixels];
        sourceRaster = new int[maxPixels];
        targetRaster = new int[maxPixels];
        sourceMask = new BitSet(maxPixels);
        targetMask = new BitSet(maxPixels);
    }

    void reset() {
        sourceMask.clear();
        targetMask.clear();
        srcWidth = srcHeight = srcPixelCount = 0;
        tgtWidth = tgtHeight = tgtPixelCount = 0;
    }
}