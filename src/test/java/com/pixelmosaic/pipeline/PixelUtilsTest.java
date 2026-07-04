package com.pixelmosaic.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PixelUtilsTest {

    @Test
    void testAchromaticSentinel() {
        assertEquals(65535, PixelUtils.computeHue(255, 255, 255),
                "white is achromatic -> sentinel hue");
    }

    @Test
    void testAchromaticBlack() {
        assertEquals(65535, PixelUtils.computeHue(0, 0, 0),
                "black is achromatic -> sentinel hue");
    }

    @Test
    void testChromatic() {
        int hue = PixelUtils.computeHue(255, 0, 0);
        assertTrue(hue >= 0 && hue <= 10, "pure red sits near 0 degrees, got " + hue);
    }

    @Test
    void testLuminanceWhite() {
        assertEquals(127, PixelUtils.computeLuminance(255, 255, 255));
    }

    @Test
    void testLuminanceBlack() {
        assertEquals(0, PixelUtils.computeLuminance(0, 0, 0));
    }

    @Test
    void testFgSortsFirst() {
        // Identical sort key except the FG flag; FG must be negative, BG non-negative.
        long fg = PixelUtils.packSource(1, 50, 1234, 7, 10, 20);
        long bg = PixelUtils.packSource(0, 50, 1234, 7, 10, 20);
        assertTrue(fg < 0, "FG word must be negative (bit 63 set)");
        assertTrue(bg >= 0, "BG word must be non-negative");
        assertTrue(fg < bg, "FG must sort before BG");
    }

    @Test
    void testFindSplit() {
        long[] arr = new long[200];
        for (int i = 0; i < 100; i++) {
            arr[i] = PixelUtils.packSource(1, i % 128, i, i & 0xFF, i, i); // FG, negative
        }
        for (int i = 100; i < 200; i++) {
            arr[i] = PixelUtils.packSource(0, i % 128, i, i & 0xFF, i, i); // BG, non-negative
        }
        assertEquals(100, PixelUtils.findFgBgSplit(arr, 200));
    }

    @Test
    void testSpatialHashDeterministic() {
        int a = PixelUtils.spatialHash(123, 456);
        int b = PixelUtils.spatialHash(123, 456);
        assertEquals(a, b, "same coordinates must hash identically");
        assertTrue(a >= 0 && a <= 255, "hash must fit in 8 bits");
    }

    @Test
    void testUnpackRoundtrip() {
        long word = PixelUtils.packSource(1, 63, 1000, 42, 123, 456);
        assertEquals(123, PixelUtils.unpackX(word));
        assertEquals(456, PixelUtils.unpackY(word));
    }
}