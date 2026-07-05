package com.pixelmosaic.config;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ModelLoader {

    public static final String MODEL_RESOURCE = "/models/u2netp.onnx";
    public static final String MODEL_RELATIVE_PATH = "backend/src/main/resources/models/u2netp.onnx";

    private ModelLoader() {
    }

    public static boolean isAvailable() {
        return ModelLoader.class.getResource(MODEL_RESOURCE) != null;
    }

    public static String resolveFilePath() throws IOException {
        URL url = ModelLoader.class.getResource(MODEL_RESOURCE);
        if (url == null) {
            return null;
        }
        try {
            Path path = Paths.get(url.toURI());
            return path.toString();
        } catch (Exception e) {
            throw new IOException("model is on the classpath but not a filesystem file: " + url, e);
        }
    }

    public static byte[] loadBytes() throws IOException {
        try (InputStream in = ModelLoader.class.getResourceAsStream(MODEL_RESOURCE)) {
            if (in == null) {
                throw new FileNotFoundException(missingMessage());
            }
            return in.readAllBytes();
        }
    }

    public static String missingMessage() {
        return "Model not found on classpath at " + MODEL_RESOURCE
                + ". Place u2netp.onnx in " + MODEL_RELATIVE_PATH
                + " (export from https://github.com/xuebinqin/U-2-Net).";
    }
}