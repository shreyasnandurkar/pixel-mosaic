package com.pixelmosaic.pipeline;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;

public final class ImageDecoder {

    public static final int MAX_BYTES = 10_485_760;
    public static final int MAX_PIXELS = 2_000_000;
    public static final int MAX_DIMENSION = 65_535;

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
            int dw = Math.clamp((int) Math.floor(width * scale), 1, MAX_DIMENSION);
            int dh = Math.clamp((int) Math.floor(height * scale), 1, MAX_DIMENSION);
            argb = bilinearResize(argb, width, height, dw, dh);
            width = dw;
            height = dh;
        }

        dimensionsOut[0] = width;
        dimensionsOut[1] = height;
        return argb;
    }

    private static int[] extractArgb(BufferedImage image, int width, int height) {
        WritableRaster raster = image.getRaster();
        DataBuffer db = raster.getDataBuffer();
        int n = width * height;
        int[] out = new int[n];

        if (db instanceof DataBufferInt dbi) {
            int[] data = dbi.getData();
            return switch (image.getType()) {
                case BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE -> {
                    System.arraycopy(data, 0, out, 0, n);
                    yield out;
                }
                case BufferedImage.TYPE_INT_RGB -> {
                    for (int i = 0; i < n; i++) {
                        out[i] = 0xFF000000 | (data[i] & 0x00FFFFFF);
                    }
                    yield out;
                }
                case BufferedImage.TYPE_INT_BGR -> {
                    for (int i = 0; i < n; i++) {
                        int v = data[i];
                        int b = (v >> 16) & 0xFF;
                        int g = (v >> 8) & 0xFF;
                        int r = v & 0xFF;
                        out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                    yield out;
                }
                default -> normalizeViaDraw(image, width, height);
            };
        }

        if (db instanceof DataBufferByte dbb) {
            byte[] data = dbb.getData();
            return switch (image.getType()) {
                case BufferedImage.TYPE_3BYTE_BGR -> {
                    for (int i = 0, p = 0; i < n; i++) {
                        int b = data[p++] & 0xFF;
                        int g = data[p++] & 0xFF;
                        int r = data[p++] & 0xFF;
                        out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                    yield out;
                }
                case BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_4BYTE_ABGR_PRE -> {
                    for (int i = 0, p = 0; i < n; i++) {
                        int a = data[p++] & 0xFF;
                        int b = data[p++] & 0xFF;
                        int g = data[p++] & 0xFF;
                        int r = data[p++] & 0xFF;
                        out[i] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                    yield out;
                }
                case BufferedImage.TYPE_BYTE_GRAY -> {
                    for (int i = 0; i < n; i++) {
                        int v = data[i] & 0xFF;
                        out[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
                    }
                    yield out;
                }
                default -> normalizeViaDraw(image, width, height);
            };
        }

        return normalizeViaDraw(image, width, height);
    }

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