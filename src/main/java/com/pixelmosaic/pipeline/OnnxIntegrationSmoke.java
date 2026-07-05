package com.pixelmosaic.pipeline;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.pixelmosaic.config.ModelLoader;
import com.pixelmosaic.config.OnnxSessionFactory;

import java.util.BitSet;

public final class OnnxIntegrationSmoke {

    private static final int W = 100;
    private static final int H = 100;

    private OnnxIntegrationSmoke() {
    }

    public static void main(String[] args) {
        if (!ModelLoader.isAvailable()) {
            System.out.println("Model not found — place u2netp.onnx in "
                    + "backend/src/main/resources/models/ to test ONNX integration");
            System.exit(0);
        }

        try {
            OrtEnvironment env = OnnxSessionFactory.createEnvironment();
            String modelPath = ModelLoader.resolveFilePath();
            try (OrtSession session = OnnxSessionFactory.createSession(env, modelPath)) {
                OnnxMaskGenerator generator = new OnnxMaskGenerator(env, session);
                int[] raster = syntheticRaster(W, H);

                long t0 = System.nanoTime();
                BitSet mask = generator.generateMask(raster, W, H);
                long ms = (System.nanoTime() - t0) / 1_000_000;

                System.out.println("Model: " + modelPath);
                System.out.println("Inference + mask time: " + ms + " ms");
                System.out.println("Mask cardinality: " + mask.cardinality() + " / " + (W * H));
                System.out.println("ONNX integration OK");
            }
        } catch (Exception e) {
            System.err.println("ONNX integration FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static int[] syntheticRaster(int width, int height) {
        int[] raster = new int[width * height];
        float cx = width / 2f;
        float cy = height / 2f;
        float r = Math.min(width, height) * 0.3f;
        float r2 = r * r;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float dx = x - cx;
                float dy = y - cy;
                boolean inside = (dx * dx + dy * dy) <= r2;
                int v = inside ? 230 : 20;
                raster[y * width + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        return raster;
    }
}