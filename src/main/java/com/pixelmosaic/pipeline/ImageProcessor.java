package com.pixelmosaic.pipeline;

import java.util.BitSet;

public final class ImageProcessor {

    private final ImageDecoder decoder;
    private final OnnxMaskGenerator maskGenerator;

    public ImageProcessor(ImageDecoder decoder, OnnxMaskGenerator maskGenerator) {
        this.decoder = decoder;
        this.maskGenerator = maskGenerator;
    }

    public int process(byte[] imageBytes, RequestBuffers buf, boolean isSource) throws Exception {
        int[] dimensions = new int[2];
        int[] decoded = decoder.decodeToRaster(imageBytes, dimensions);
        int width = dimensions[0];
        int height = dimensions[1];
        int pixelCount = width * height;

        int[] raster = isSource ? buf.sourceRaster : buf.targetRaster;
        System.arraycopy(decoded, 0, raster, 0, pixelCount);

        if (isSource) {
            buf.srcWidth = width;
            buf.srcHeight = height;
            buf.srcPixelCount = pixelCount;
        } else {
            buf.tgtWidth = width;
            buf.tgtHeight = height;
            buf.tgtPixelCount = pixelCount;
        }

        BitSet mask = maskGenerator.generateMask(decoded, width, height);
        if (isSource) {
            buf.sourceMask = mask;
        } else {
            buf.targetMask = mask;
        }

        long[] data = isSource ? buf.sourceData : buf.targetData;
        for (int y = 0; y < height; y++) {
            int rowBase = y * width;
            for (int x = 0; x < width; x++) {
                int p = rowBase + x;
                int argb = raster[p];
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;

                int fgBit = mask.get(p) ? 1 : 0;
                int lum = PixelUtils.computeLuminance(r, g, b);
                int hue = PixelUtils.computeHue(r, g, b);
                int hash = PixelUtils.spatialHash(x, y);

                data[p] = isSource
                        ? PixelUtils.packSource(fgBit, lum, hue, hash, x, y)
                        : PixelUtils.packTarget(fgBit, lum, hue, hash, x, y);
            }
        }
        return pixelCount;
    }
}