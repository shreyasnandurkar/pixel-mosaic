package com.pixelmosaic.pipeline;

/**
 * Pure static helpers for the bitwise pixel packer. No Spring, no external deps.
 *
 * <p>Both the source and target pipelines pack every pixel into a single 64-bit
 * {@code long} with this layout (MSB first):
 *
 * <pre>
 *   bit 63      : FG/BG flag (1 = foreground, 0 = background)
 *   bits 62-56  : luminance (7 bits, 0-127)
 *   bits 55-40  : hue       (16 bits, 0-65535; 65535 = achromatic sentinel)
 *   bits 39-32  : spatial hash (8 bits)
 *   bits 31-16  : X coordinate (16 bits)
 *   bits 15-0   : Y coordinate (16 bits)
 * </pre>
 *
 * Setting bit 63 makes the {@code long} negative in Java's signed arithmetic.
 * {@link java.util.Arrays#sort(long[])} orders negatives before non-negatives,
 * so foreground pixels (bit 63 = 1) sort BEFORE background pixels (bit 63 = 0).
 * The FG/BG split is therefore the first index holding a non-negative value.
 */
public final class PixelUtils {

    private PixelUtils() {
    }

    /** Relative luminance via ITU-R BT.709, scaled to a 7-bit range [0, 127]. */
    public static int computeLuminance(int r, int g, int b) {
        float y = 0.2126f * r + 0.7152f * g + 0.0722f * b; // [0, 255]
        return (int) (y / 255.0f * 127.0f);                // [0, 127]
    }

    /**
     * Hue from an RGB triple, packed into 16 bits.
     *
     * <p>Achromatic pixels (delta below the 0.04 saturation threshold) return the
     * sentinel 65535, which sorts after every chromatic hue within a luminance band.
     */
    public static int computeHue(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;

        if (delta < 0.04f) {
            return 65535; // achromatic sentinel
        }

        float h;
        if (max == rf) {
            h = 60f * (((gf - bf) / delta) % 6f);
        } else if (max == gf) {
            h = 60f * (((bf - rf) / delta) + 2f);
        } else {
            h = 60f * (((rf - gf) / delta) + 4f);
        }
        if (h < 0f) {
            h += 360f;
        }
        return (int) (h / 360f * 65534f); // [0, 65534]; 65535 reserved
    }

    /**
     * Multiplicative spatial hash for deterministic tiebreaking, 8 bits.
     * The constant 2654435761 overflows a Java int, so arithmetic is done in long.
     */
    public static int spatialHash(int x, int y) {
        return (int) (((long) x * 2654435761L ^ (long) y * 40503L) & 0xFF);
    }

    /** Pack a source pixel. Lower 32 bits hold the SOURCE coordinates. */
    public static long packSource(int fgBit, int lum, int hue16, int hash, int x, int y) {
        return ((long) (fgBit & 0x1) << 63)
                | ((long) (lum & 0x7F) << 56)
                | ((long) (hue16 & 0xFFFF) << 40)
                | ((long) (hash & 0xFF) << 32)
                | ((long) (x & 0xFFFF) << 16)
                | ((long) (y & 0xFFFF));
    }

    /**
     * Pack a target pixel. Identical bit layout to {@link #packSource}; the lower
     * 32 bits semantically hold the DESTINATION coordinates on the output canvas.
     */
    public static long packTarget(int fgBit, int lum, int hue16, int hash, int x, int y) {
        return ((long) (fgBit & 0x1) << 63)
                | ((long) (lum & 0x7F) << 56)
                | ((long) (hue16 & 0xFFFF) << 40)
                | ((long) (hash & 0xFF) << 32)
                | ((long) (x & 0xFFFF) << 16)
                | ((long) (y & 0xFFFF));
    }

    /** Extract the 16-bit X coordinate. */
    public static int unpackX(long word) {
        return (int) ((word >> 16) & 0xFFFF);
    }

    /** Extract the 16-bit Y coordinate. */
    public static int unpackY(long word) {
        return (int) (word & 0xFFFF);
    }

    /**
     * Binary search for the FG/BG boundary in a sorted {@code long[]}.
     * Returns the first index holding a non-negative value: FG (negative) occupies
     * {@code [0, split)} and BG (non-negative) occupies {@code [split, len)}.
     */
    public static int findFgBgSplit(long[] sorted, int len) {
        int lo = 0, hi = len;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}