package com.pixelmosaic.pipeline;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public final class MosaicMapper {

    private static final Logger LOG = Logger.getLogger(MosaicMapper.class.getName());

    static final int BYTES_PER_PARTICLE = 12;

    public ByteBuffer map(RequestBuffers buf) throws Exception {
        int srcLen = buf.srcPixelCount;
        int tgtLen = buf.tgtPixelCount;

        CompletableFuture<Void> sortSrc =
                CompletableFuture.runAsync(() -> java.util.Arrays.sort(buf.sourceData, 0, srcLen));
        CompletableFuture<Void> sortTgt =
                CompletableFuture.runAsync(() -> java.util.Arrays.sort(buf.targetData, 0, tgtLen));
        CompletableFuture.allOf(sortSrc, sortTgt).get();

        int srcSplit = PixelUtils.findFgBgSplit(buf.sourceData, srcLen);
        int tgtSplit = PixelUtils.findFgBgSplit(buf.targetData, tgtLen);

        boolean srcHasFG = srcSplit > 0;
        boolean srcHasBG = srcSplit < srcLen;
        boolean tgtHasFG = tgtSplit > 0;
        boolean tgtHasBG = tgtSplit < tgtLen;

        ByteBuffer output = ByteBuffer.allocateDirect(tgtLen * BYTES_PER_PARTICLE);

        if (!(srcHasFG && srcHasBG && tgtHasFG && tgtHasBG)) {
            mapSingleLane(buf, output, srcLen, tgtLen,
                    srcHasFG, srcHasBG, tgtHasFG, tgtHasBG);
            output.flip();
            return output;
        }

        float fgRatio = (float) srcSplit / (float) tgtSplit;
        float bgRatio = (float) (srcLen - srcSplit) / (float) (tgtLen - tgtSplit);

        for (int i = 0; i < tgtLen; i++) {
            int srcIdx = normalSrcIdx(i, srcLen, srcSplit, tgtSplit, fgRatio, bgRatio);
            writeParticle(buf, output, srcIdx, i);
        }
        output.flip();

        logStats(buf, srcLen, tgtLen, srcSplit, tgtSplit, fgRatio, bgRatio);
        return output;
    }

    private void mapSingleLane(RequestBuffers buf, ByteBuffer output, int srcLen, int tgtLen,
                               boolean srcHasFG, boolean srcHasBG,
                               boolean tgtHasFG, boolean tgtHasBG) {
        float ratio = (float) srcLen / (float) tgtLen;
        for (int i = 0; i < tgtLen; i++) {
            int srcIdx = (int) (i * ratio);
            if (srcIdx < 0) {
                srcIdx = 0;
            } else if (srcIdx >= srcLen) {
                srcIdx = srcLen - 1;
            }
            writeParticle(buf, output, srcIdx, i);
        }
        LOG.warning(String.format(
                "degenerate lane(s) -> single-lane fallback "
                        + "(srcFG=%b srcBG=%b tgtFG=%b tgtBG=%b); ratio=%.4f, src=%d, tgt=%d",
                srcHasFG, srcHasBG, tgtHasFG, tgtHasBG, ratio, srcLen, tgtLen));
    }

    static int normalSrcIdx(int i, int srcLen, int srcSplit, int tgtSplit,
                            float fgRatio, float bgRatio) {
        int srcIdx;
        if (i < tgtSplit) {
            srcIdx = (int) (i * fgRatio);
            if (srcIdx < 0) {
                srcIdx = 0;
            } else if (srcIdx >= srcSplit) {
                srcIdx = srcSplit - 1;
            }
        } else {
            srcIdx = srcSplit + (int) ((i - tgtSplit) * bgRatio);
            if (srcIdx < srcSplit) {
                srcIdx = srcSplit;
            } else if (srcIdx >= srcLen) {
                srcIdx = srcLen - 1;
            }
        }
        return srcIdx;
    }

    private static void writeParticle(RequestBuffers buf, ByteBuffer output, int srcIdx, int tgtIdx) {
        long srcWord = buf.sourceData[srcIdx];
        int srcX = PixelUtils.unpackX(srcWord);
        int srcY = PixelUtils.unpackY(srcWord);

        int argb = buf.sourceRaster[srcY * buf.srcWidth + srcX];
        byte r = (byte) ((argb >> 16) & 0xFF);
        byte g = (byte) ((argb >> 8) & 0xFF);
        byte b = (byte) (argb & 0xFF);

        long tgtWord = buf.targetData[tgtIdx];
        int tgtX = PixelUtils.unpackX(tgtWord);
        int tgtY = PixelUtils.unpackY(tgtWord);

        output.putShort((short) (srcX & 0xFFFF));
        output.putShort((short) (srcY & 0xFFFF));
        output.putShort((short) (tgtX & 0xFFFF));
        output.putShort((short) (tgtY & 0xFFFF));
        output.put(r).put(g).put(b);
        output.put((byte) 0);
    }

    private void logStats(RequestBuffers buf, int srcLen, int tgtLen,
                          int srcSplit, int tgtSplit, float fgRatio, float bgRatio) {
        int sampleCount = Math.min(1000, tgtLen);
        Set<Integer> distinct = new HashSet<>(sampleCount * 2);
        Random rnd = new Random(0x9E3779B9L);
        for (int s = 0; s < sampleCount; s++) {
            int i = rnd.nextInt(tgtLen);
            distinct.add(normalSrcIdx(i, srcLen, srcSplit, tgtSplit, fgRatio, bgRatio));
        }
        LOG.info(String.format(
                "mosaic mapped: srcSplit=%d (FG) / %d (BG), tgtSplit=%d (FG) / %d (BG), "
                        + "fgRatio=%.4f, bgRatio=%.4f, ~%d/%d distinct source pixels sampled (of %d total)",
                srcSplit, srcLen - srcSplit, tgtSplit, tgtLen - tgtSplit,
                fgRatio, bgRatio, distinct.size(), sampleCount, srcLen));
    }
}