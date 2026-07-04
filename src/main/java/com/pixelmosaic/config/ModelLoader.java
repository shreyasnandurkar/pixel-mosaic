package com.pixelmosaic.config;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the U&sup2;-NetP segmentation model on the classpath.
 *
 * <h2>Where to put the model</h2>
 * The 4.7&nbsp;MB model file must live at:
 * <pre>
 *   backend/src/main/resources/models/u2netp.onnx
 * </pre>
 * which is served on the classpath as {@code /models/u2netp.onnx}. Obtain it by
 * exporting U&sup2;-NetP (u2netp.pth) to ONNX, or grab a ready-made ONNX export.
 * Source: <a href="https://github.com/xuebinqin/U-2-Net">github.com/xuebinqin/U-2-Net</a>.
 *
 * <p>Nothing here fails at compile time if the model is absent. Callers should check
 * {@link #isAvailable()} and surface {@link #missingMessage()} at startup rather than
 * crashing with an opaque error.
 */
public final class ModelLoader {

    public static final String MODEL_RESOURCE = "/models/u2netp.onnx";
    public static final String MODEL_RELATIVE_PATH = "backend/src/main/resources/models/u2netp.onnx";

    private ModelLoader() {
    }

    /** True if the model resource is present on the classpath. */
    public static boolean isAvailable() {
        return ModelLoader.class.getResource(MODEL_RESOURCE) != null;
    }

    /**
     * Resolve the model to a filesystem path. Works while the resource is an
     * exploded file (dev / {@code target/classes}); returns {@code null} when the
     * model is absent.
     *
     * @throws IOException if the resource exists but cannot be resolved to a file
     *                     (e.g. packed inside a jar) — that path is handled later
     *                     via {@link #loadBytes()} once Spring packaging lands.
     */
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

    /** Read the whole model into memory; throws with a clear message if missing. */
    public static byte[] loadBytes() throws IOException {
        try (InputStream in = ModelLoader.class.getResourceAsStream(MODEL_RESOURCE)) {
            if (in == null) {
                throw new FileNotFoundException(missingMessage());
            }
            return in.readAllBytes();
        }
    }

    /** Human-readable instructions shown when the model is not found. */
    public static String missingMessage() {
        return "Model not found on classpath at " + MODEL_RESOURCE
                + ". Place u2netp.onnx in " + MODEL_RELATIVE_PATH
                + " (export from https://github.com/xuebinqin/U-2-Net).";
    }
}
