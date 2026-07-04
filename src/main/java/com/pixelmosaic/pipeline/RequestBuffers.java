package com.pixelmosaic.pipeline;

import java.util.BitSet;

/**
 * Pre-allocated working set for a single concurrent request. One instance is reused
 * across many requests (see {@link BufferPool}); {@link #reset()} cheaply prepares it
 * for the next request without zeroing the big primitive arrays — those are fully
 * overwritten before they are read.
 *
 * <p>Fields are package-private so the pipeline classes in this package can touch them
 * directly on the hot path, avoiding accessor overhead.
 */
final class RequestBuffers {

    /** Resolution cap shared with {@link ImageDecoder#MAX_PIXELS}. */
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

    /**
     * Prepare for the next request. Clears the masks and zeros the dimension counters
     * but deliberately leaves {@link #sourceData}/{@link #targetData} and the rasters
     * untouched — they are rewritten end-to-end before use, so zeroing ~48&nbsp;MB here
     * would be pure waste.
     */
    void reset() {
        sourceMask.clear();
        targetMask.clear();
        srcWidth = srcHeight = srcPixelCount = 0;
        tgtWidth = tgtHeight = tgtPixelCount = 0;
    }
}
