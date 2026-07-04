package com.pixelmosaic.pipeline;

import com.pixelmosaic.benchmark.PixelUtils;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the core mapping algorithm. Buffers are built synthetically with small
 * rasters and a known FG/BG split, so the tests exercise the lane logic without decoding
 * images or running ONNX.
 */
class MosaicMapperTest {

    private final MosaicMapper mapper = new MosaicMapper();

    @Test
    void testDualLaneMapping() throws Exception {
        RequestBuffers buf = buildBuffer(10, 10, 50, 10, 10, 50);
        ByteBuffer out = mapper.map(buf);

        int tgtLen = buf.tgtPixelCount;
        assertEquals(tgtLen * MosaicMapper.BYTES_PER_PARTICLE, out.capacity(),
                "one 12-byte particle per target pixel");
        assertEquals(0, out.position(), "flip() should rewind position to 0");
        assertEquals(out.capacity(), out.limit(), "flip() should set limit to bytes written");
    }

    @Test
    void testDegenerateFallback() {
        // Every source pixel is foreground -> source BG lane empty -> single-lane fallback.
        RequestBuffers buf = buildBuffer(10, 10, 100, 10, 10, 50);
        ByteBuffer out = assertDoesNotThrow(() -> mapper.map(buf));

        assertEquals(buf.tgtPixelCount * MosaicMapper.BYTES_PER_PARTICLE, out.capacity());
        assertEquals(0, out.position());
    }

    @Test
    void testSrcIdxNeverOutOfBounds() {
        // srcSplit=1, tgtSplit=1000 -> fgRatio = 0.001; both lanes non-empty -> normal path.
        RequestBuffers buf = buildBuffer(2, 1, 1, 50, 40, 1000);
        ByteBuffer out = assertDoesNotThrow(() -> mapper.map(buf));

        assertEquals(2000 * MosaicMapper.BYTES_PER_PARTICLE, out.capacity());
    }

    @Test
    void testBgLaneMapping() {
        int srcLen = 100, srcSplit = 40, tgtSplit = 60, tgtLen = 120;
        float fgRatio = (float) srcSplit / tgtSplit;
        float bgRatio = (float) (srcLen - srcSplit) / (tgtLen - tgtSplit);

        for (int i = 0; i < tgtSplit; i++) {
            int idx = MosaicMapper.normalSrcIdx(i, srcLen, srcSplit, tgtSplit, fgRatio, bgRatio);
            assertTrue(idx >= 0 && idx < srcSplit,
                    "FG target " + i + " must stay in FG lane [0," + srcSplit + "), got " + idx);
        }
        for (int i = tgtSplit; i < tgtLen; i++) {
            int idx = MosaicMapper.normalSrcIdx(i, srcLen, srcSplit, tgtSplit, fgRatio, bgRatio);
            assertTrue(idx >= srcSplit && idx < srcLen,
                    "BG target " + i + " must stay in BG lane [" + srcSplit + "," + srcLen + "), got " + idx);
        }
    }

    // --- helpers -----------------------------------------------------------

    private static RequestBuffers buildBuffer(int srcW, int srcH, int srcFg,
                                              int tgtW, int tgtH, int tgtFg) {
        RequestBuffers buf = new RequestBuffers(4096);
        buf.srcWidth = srcW;
        buf.srcHeight = srcH;
        buf.srcPixelCount = srcW * srcH;
        fillLane(buf.sourceData, buf.sourceRaster, srcW, srcH, srcFg, true);

        buf.tgtWidth = tgtW;
        buf.tgtHeight = tgtH;
        buf.tgtPixelCount = tgtW * tgtH;
        fillLane(buf.targetData, buf.targetRaster, tgtW, tgtH, tgtFg, false);
        return buf;
    }

    /**
     * Fill one lane: the first {@code fgCount} pixels get the FG bit (which makes the packed
     * long negative, so they sort first), the rest are background. Each pixel gets a unique
     * (x,y), so every packed long is distinct and the post-sort split equals {@code fgCount}.
     */
    private static void fillLane(long[] data, int[] raster, int width, int height,
                                 int fgCount, boolean source) {
        int n = width * height;
        for (int i = 0; i < n; i++) {
            int x = i % width;
            int y = i / width;
            int fgBit = (i < fgCount) ? 1 : 0;
            int lum = i & 0x7F;
            int hue = i & 0xFFFF;
            int hash = i & 0xFF;
            data[i] = source
                    ? PixelUtils.packSource(fgBit, lum, hue, hash, x, y)
                    : PixelUtils.packTarget(fgBit, lum, hue, hash, x, y);
            raster[y * width + x] = 0xFF000000 | ((i * 7) & 0xFFFFFF);
        }
    }
}
