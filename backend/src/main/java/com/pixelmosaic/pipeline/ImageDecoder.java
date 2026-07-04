package com.pixelmosaic.pipeline;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Decodes JPEG / PNG / WebP bytes into a packed ARGB {@code int[]} raster without
 * ever calling {@link BufferedImage#getRGB} (banned on the hot path). Pixel data
 * is read straight from the backing {@link DataBuffer}; unusual buffer/image types
 * fall back to a one-shot draw into a known TYPE_INT_ARGB image.
 *
 * <p>Images larger than {@link #MAX_PIXELS} are bilinearly downscaled to fit the
 * 2-megapixel envelope while preserving aspect ratio.
 */
public final class ImageDecoder {

    /** Hard input cap: 10 MB. */
    public static final int MAX_BYTES = 10_485_760;
    /** Resolution cap: 2 megapixels. */
    public static final int MAX_PIXELS = 2_000_000;
    /** Per-side cap: X/Y are packed into 16 bits downstream, so neither side may exceed this. */
    public static final int MAX_DIMENSION = 65_535;

    /**
     * Decode image bytes to an ARGB raster.
     *
     * @param imageBytes    encoded image (JPEG/PNG/WebP)
     * @param dimensionsOut int[2]; on return holds [width, height] of the raster
     * @return ARGB pixels, row-major, length == width * height
     */
    public int[] decodeToRaster(byte[] imageBytes, int[] dimensionsOut) throws IOException {
        if (imageBytes == null) {
            throw new IllegalArgumentException("imageBytes must not be null");
        }
        if (imageBytes.length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "image exceeds 10 MB limit: " + imageBytes.length + " bytes");
        }
        if (dimensionsOut == null || dimensionsOut.length < 2) {
            throw new IllegalArgumentException("dimensionsOut must be an int[2]");
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            throw new IllegalArgumentException(
                    "unrecognized or unsupported image format (expected JPEG, PNG, or WebP)");
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = extractArgb(image, width, height);

        // Downscale to fit both the pixel-count cap and the per-side cap, whichever binds harder.
        double scale = 1.0;
        if ((long) width * height > MAX_PIXELS) {
            scale = Math.min(scale, Math.sqrt((double) MAX_PIXELS / ((double) width * height)));
        }
        if (width > MAX_DIMENSION) {
            scale = Math.min(scale, (double) MAX_DIMENSION / width);
        }
        if (height > MAX_DIMENSION) {
            scale = Math.min(scale, (double) MAX_DIMENSION / height);
        }
        if (scale < 1.0) {
            int dw = Math.min(MAX_DIMENSION, Math.max(1, (int) Math.floor(width * scale)));
            int dh = Math.min(MAX_DIMENSION, Math.max(1, (int) Math.floor(height * scale)));
            argb = bilinearResize(argb, width, height, dw, dh);
            width = dw;
            height = dh;
        }

        dimensionsOut[0] = width;
        dimensionsOut[1] = height;
        return argb;
    }

    /** Pull ARGB ints from the raster's backing buffer; no getRGB(). */
    private static int[] extractArgb(BufferedImage image, int width, int height) {
        WritableRaster raster = image.getRaster();
        DataBuffer db = raster.getDataBuffer();
        int n = width * height;
        int[] out = new int[n];

        if (db instanceof DataBufferInt dbi) {
            int[] data = dbi.getData();
            switch (image.getType()) {
                case BufferedImage.TYPE_INT_ARGB:
                case BufferedImage.TYPE_INT_ARGB_PRE:
                    System.arraycopy(data, 0, out, 0, n);
                    return out;
                case BufferedImage.TYPE_INT_RGB:
                    for (int i = 0; i < n; i++) {
                        out[i] = 0xFF000000 | (data[i] & 0x00FFFFFF);
                    }
                    return out;
                case BufferedImage.TYPE_INT_BGR:
                    for (int i = 0; i < n; i++) {
                        int v = data[i];
                        int b = (v >> 16) & 0xFF;
                        int g = (v >> 8) & 0xFF;
                        int r = v & 0xFF;
                        out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                    return out;
                default:
                    return normalizeViaDraw(image, width, height);
            }
        }

        if (db instanceof DataBufferByte dbb) {
            byte[] data = dbb.getData();
            switch (image.getType()) {
                case BufferedImage.TYPE_3BYTE_BGR:
                    for (int i = 0, p = 0; i < n; i++) {
                        int b = data[p++] & 0xFF;
                        int g = data[p++] & 0xFF;
                        int r = data[p++] & 0xFF;
                        out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                    return out;
                case BufferedImage.TYPE_4BYTE_ABGR:
                case BufferedImage.TYPE_4BYTE_ABGR_PRE:
                    for (int i = 0, p = 0; i < n; i++) {
                        int a = data[p++] & 0xFF;
                        int b = data[p++] & 0xFF;
                        int g = data[p++] & 0xFF;
                        int r = data[p++] & 0xFF;
                        out[i] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                    return out;
                case BufferedImage.TYPE_BYTE_GRAY:
                    for (int i = 0; i < n; i++) {
                        int v = data[i] & 0xFF;
                        out[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
                    }
                    return out;
                default:
                    return normalizeViaDraw(image, width, height);
            }
        }

        // Unknown DataBuffer type (e.g. ushort): normalize without getRGB().
        return normalizeViaDraw(image, width, height);
    }

    /** Last-resort conversion: render into a TYPE_INT_ARGB image and read its int buffer. */
    private static int[] normalizeViaDraw(BufferedImage image, int width, int height) {
        BufferedImage argbImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argbImage.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        int[] data = ((DataBufferInt) argbImage.getRaster().getDataBuffer()).getData();
        int[] out = new int[width * height];
        System.arraycopy(data, 0, out, 0, out.length);
        return out;
    }

    /**
     * Bilinear resize of an ARGB raster. Uses corner-aligned sampling: output corners
     * map exactly onto source corners and interior pixels are interpolated from the
     * four nearest source samples, weighted by fractional position.
     */
    public static int[] bilinearResize(int[] src, int sw, int sh, int dw, int dh) {
        int[] dst = new int[dw * dh];
        if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) {
            return dst;
        }
        float xScale = dw > 1 ? (float) (sw - 1) / (dw - 1) : 0f;
        float yScale = dh > 1 ? (float) (sh - 1) / (dh - 1) : 0f;

        for (int dy = 0; dy < dh; dy++) {
            float gy = dy * yScale;
            int y0 = (int) gy;
            int y1 = Math.min(y0 + 1, sh - 1);
            float fy = gy - y0;

            for (int dx = 0; dx < dw; dx++) {
                float gx = dx * xScale;
                int x0 = (int) gx;
                int x1 = Math.min(x0 + 1, sw - 1);
                float fx = gx - x0;

                int c00 = src[y0 * sw + x0];
                int c10 = src[y0 * sw + x1];
                int c01 = src[y1 * sw + x0];
                int c11 = src[y1 * sw + x1];

                dst[dy * dw + dx] = lerp2d(c00, c10, c01, c11, fx, fy);
            }
        }
        return dst;
    }

    /** Bilinearly blend four ARGB samples; rounds each channel to nearest. */
    private static int lerp2d(int c00, int c10, int c01, int c11, float fx, float fy) {
        int a = channelLerp(c00, c10, c01, c11, fx, fy, 24);
        int r = channelLerp(c00, c10, c01, c11, fx, fy, 16);
        int g = channelLerp(c00, c10, c01, c11, fx, fy, 8);
        int b = channelLerp(c00, c10, c01, c11, fx, fy, 0);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channelLerp(int c00, int c10, int c01, int c11,
                                   float fx, float fy, int shift) {
        int v00 = (c00 >> shift) & 0xFF;
        int v10 = (c10 >> shift) & 0xFF;
        int v01 = (c01 >> shift) & 0xFF;
        int v11 = (c11 >> shift) & 0xFF;
        float top = v00 + (v10 - v00) * fx;
        float bottom = v01 + (v11 - v01) * fx;
        float value = top + (bottom - top) * fy;
        int rounded = (int) (value + 0.5f);
        if (rounded < 0) {
            return 0;
        }
        return Math.min(rounded, 255);
    }
}
