package com.pixelmosaic.pipeline;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageDecoderTest {

    private final ImageDecoder decoder = new ImageDecoder();

    @Test
    void testRejectOversizeBytes() {
        byte[] tooBig = new byte[ImageDecoder.MAX_BYTES + 1];
        assertThrows(IllegalArgumentException.class,
                () -> decoder.decodeToRaster(tooBig, new int[2]));
    }

    @Test
    void testDecodeSmallJpeg() throws IOException {
        byte[] jpeg = encode(syntheticGradient(100, 100), "jpg");
        int[] dims = new int[2];
        int[] raster = decoder.decodeToRaster(jpeg, dims);

        assertEquals(100, dims[0]);
        assertEquals(100, dims[1]);
        assertEquals(10_000, raster.length);
    }

    @Test
    void testDownscaleLargeImage() throws IOException {
        // 2000x2000 = 4,000,000 px > 2 MP cap. PNG keeps the encoded size small.
        byte[] png = encode(syntheticGradient(2000, 2000), "png");
        int[] dims = new int[2];
        int[] raster = decoder.decodeToRaster(png, dims);

        assertTrue((long) dims[0] * dims[1] <= ImageDecoder.MAX_PIXELS,
                "result must fit within 2 MP, was " + dims[0] + "x" + dims[1]);
        assertEquals(dims[0] * dims[1], raster.length, "raster length must match dimensions");
        // Square input must stay (near) square after proportional downscale.
        assertEquals(dims[0], dims[1], "aspect ratio of a square image must be preserved");
        assertTrue(dims[0] < 2000, "image should have been downscaled");
    }

    @Test
    void testBilinearResizeCorrectness() {
        // 2x2 grayscale corners: TL=0, TR=90, BL=90, BR=180.
        int[] src = {
                gray(0), gray(90),
                gray(90), gray(180)
        };
        int[] dst = ImageDecoder.bilinearResize(src, 2, 2, 4, 4);

        // Corner-aligned: output corners equal source corners.
        assertEquals(0, red(dst[idx(0, 0, 4)]), "top-left corner preserved");
        assertEquals(180, red(dst[idx(3, 3, 4)]), 1, "bottom-right corner preserved");

        // Interior pixel (col=1,row=0) sits at fx=1/3 along the top edge: 0->90 => ~30.
        int interpTop = red(dst[idx(1, 0, 4)]);
        assertEquals(30, interpTop, 3, "top-edge pixel must interpolate between 0 and 90");
        assertTrue(interpTop > 0 && interpTop < 90, "interpolated value lies strictly between corners");

        // Interior pixel (col=0,row=1) interpolates down the left edge: 0->90 => ~30.
        assertEquals(30, red(dst[idx(0, 1, 4)]), 3, "left-edge pixel must interpolate between 0 and 90");
    }

    // --- helpers -----------------------------------------------------------

    private static BufferedImage syntheticGradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] data = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (x * 255) / Math.max(1, w - 1);
                int g = (y * 255) / Math.max(1, h - 1);
                int b = ((x + y) * 255) / Math.max(1, w + h - 2);
                data[y * w + x] = (r << 16) | (g << 8) | b;
            }
        }
        return img;
    }

    private static byte[] encode(BufferedImage img, String format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, format, baos), "no ImageIO writer for " + format);
        return baos.toByteArray();
    }

    private static int gray(int v) {
        return 0xFF000000 | (v << 16) | (v << 8) | v;
    }

    private static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    private static int idx(int x, int y, int w) {
        return y * w + x;
    }
}
