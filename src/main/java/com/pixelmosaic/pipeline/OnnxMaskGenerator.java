package com.pixelmosaic.pipeline;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.BitSet;
import java.util.Map;

public class OnnxMaskGenerator {

    private static final int SIZE = 320;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private final OrtEnvironment env;
    private final OrtSession session;

    public OnnxMaskGenerator(OrtEnvironment env, OrtSession session) {
        this.env = env;
        this.session = session;
    }

    public BitSet generateMask(int[] raster, int width, int height) throws OrtException {
        float[] input = buildNormalizedInput(raster, width, height);
        FloatBuffer inputBuf = ByteBuffer
                .allocateDirect(input.length * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        inputBuf.put(input).flip();

        String inputName = session.getInputNames().iterator().next();

        float[] mask = new float[SIZE * SIZE];
        long[] shape = {1, 3, SIZE, SIZE};
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputBuf, shape);
             OrtSession.Result result = session.run(Map.of(inputName, inputTensor))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            FloatBuffer maskBuf = output.getFloatBuffer();
            maskBuf.get(mask);
        }

        boolean[] fg = upsampleAndThreshold(mask, width, height);
        boolean[] cleaned = close(open(fg, width, height), width, height);

        BitSet result = new BitSet(width * height);
        for (int i = 0; i < cleaned.length; i++) {
            if (cleaned[i]) {
                result.set(i);
            }
        }
        return result;
    }

    private static float[] buildNormalizedInput(int[] raster, int width, int height) {
        int[] small = nearestResize(raster, width, height, SIZE, SIZE);
        int plane = SIZE * SIZE;
        float[] input = new float[3 * plane];
        for (int i = 0; i < plane; i++) {
            int argb = small[i];
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            input[i] = ((r / 255f) - MEAN[0]) / STD[0];
            input[plane + i] = ((g / 255f) - MEAN[1]) / STD[1];
            input[2 * plane + i] = ((b / 255f) - MEAN[2]) / STD[2];
        }
        return input;
    }

    private static boolean[] upsampleAndThreshold(float[] mask, int width, int height) {
        boolean[] fg = new boolean[width * height];
        for (int py = 0; py < height; py++) {
            int my = Math.min((int) (py * (float) SIZE / height), SIZE - 1);
            int rowBase = py * width;
            int maskRow = my * SIZE;
            for (int px = 0; px < width; px++) {
                int mx = Math.min((int) (px * (float) SIZE / width), SIZE - 1);
                fg[rowBase + px] = mask[maskRow + mx] > 0.5f;
            }
        }
        return fg;
    }

    private static int[] nearestResize(int[] src, int sw, int sh, int dw, int dh) {
        int[] dst = new int[dw * dh];
        for (int dy = 0; dy < dh; dy++) {
            int sy = Math.min((int) (dy * (float) sh / dh), sh - 1);
            for (int dx = 0; dx < dw; dx++) {
                int sx = Math.min((int) (dx * (float) sw / dw), sw - 1);
                dst[dy * dw + dx] = src[sy * sw + sx];
            }
        }
        return dst;
    }

    private static boolean[] open(boolean[] in, int w, int h) {
        return dilate(erode(in, w, h), w, h);
    }

    private static boolean[] close(boolean[] in, int w, int h) {
        return erode(dilate(in, w, h), w, h);
    }

    private static boolean[] erode(boolean[] in, int w, int h) {
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y * w + x] = neighborhood(in, w, h, x, y, true);
            }
        }
        return out;
    }

    private static boolean[] dilate(boolean[] in, int w, int h) {
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y * w + x] = neighborhood(in, w, h, x, y, false);
            }
        }
        return out;
    }

    private static boolean neighborhood(boolean[] in, int w, int h, int x, int y, boolean requireAll) {
        for (int dy = -1; dy <= 1; dy++) {
            int ny = clamp(y + dy, h);
            for (int dx = -1; dx <= 1; dx++) {
                int nx = clamp(x + dx, w);
                boolean v = in[ny * w + nx];
                if (requireAll) {
                    if (!v) {
                        return false;
                    }
                } else if (v) {
                    return true;
                }
            }
        }
        return requireAll;
    }

    private static int clamp(int v, int size) {
        if (v < 0) {
            return 0;
        }
        return Math.min(v, size - 1);
    }
}