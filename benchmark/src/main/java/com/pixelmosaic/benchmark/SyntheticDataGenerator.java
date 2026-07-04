package com.pixelmosaic.benchmark;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Fills packed {@code long[]} buffers with realistic synthetic pixel data so the
 * benchmark never depends on real images or ONNX. Each pixel gets a random RGB,
 * derived luminance/hue/hash, and an FG flag determined by membership in a
 * centered ellipse that stands in for a segmentation mask.
 */
public final class SyntheticDataGenerator {

    private SyntheticDataGenerator() {
    }

    /** Source pixels: subject modelled as a tall, narrow centered ellipse. */
    public static void generateSourceData(long[] dest, int width, int height) {
        fill(dest, width, height, 0.32f, 0.46f, true);
    }

    /** Target pixels: a different (wide, short) ellipse so the lane ratios differ. */
    public static void generateTargetData(long[] dest, int width, int height) {
        fill(dest, width, height, 0.46f, 0.30f, false);
    }

    private static void fill(long[] dest, int width, int height,
                             float rxFactor, float ryFactor, boolean source) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        float cx = width / 2f;
        float cy = height / 2f;
        float rx = width * rxFactor;
        float ry = height * ryFactor;

        for (int y = 0; y < height; y++) {
            float ny = (y - cy) / ry;
            float ny2 = ny * ny;
            int rowBase = y * width;
            for (int x = 0; x < width; x++) {
                int r = rnd.nextInt(256);
                int g = rnd.nextInt(256);
                int b = rnd.nextInt(256);

                int lum = PixelUtils.computeLuminance(r, g, b);
                int hue = PixelUtils.computeHue(r, g, b);
                int hash = PixelUtils.spatialHash(x, y);

                float nx = (x - cx) / rx;
                int fgBit = (nx * nx + ny2) <= 1f ? 1 : 0;

                long word = source
                        ? PixelUtils.packSource(fgBit, lum, hue, hash, x, y)
                        : PixelUtils.packTarget(fgBit, lum, hue, hash, x, y);
                dest[rowBase + x] = word;
            }
        }
    }
}