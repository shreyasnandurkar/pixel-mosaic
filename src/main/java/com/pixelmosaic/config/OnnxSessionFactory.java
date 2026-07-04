package com.pixelmosaic.config;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import ai.onnxruntime.OrtSession.SessionOptions.ExecutionMode;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;

/**
 * Static factory for the ONNX Runtime environment and session. Not a Spring bean
 * yet (DI wiring comes on Day 3). The session is configured to stay single-threaded
 * so ORT never grabs every core on the free-tier VM.
 */
public final class OnnxSessionFactory {

    private OnnxSessionFactory() {
    }

    /** The process-wide ORT environment singleton. */
    public static OrtEnvironment createEnvironment() {
        return OrtEnvironment.getEnvironment();
    }

    /** Build a session from a model file path with the project's standard options. */
    public static OrtSession createSession(OrtEnvironment env, String modelPath) throws OrtException {
        SessionOptions opts = new SessionOptions();
        opts.setIntraOpNumThreads(1);   // do not consume all cores
        opts.setInterOpNumThreads(1);
        opts.setOptimizationLevel(OptLevel.ALL_OPT);
        opts.setMemoryPatternOptimization(true);
        opts.setExecutionMode(ExecutionMode.SEQUENTIAL);
        return env.createSession(modelPath, opts);
    }
}
