package com.pixelmosaic.config;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import ai.onnxruntime.OrtSession.SessionOptions.ExecutionMode;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;

public final class OnnxSessionFactory {

    private OnnxSessionFactory() {
    }

    public static OrtEnvironment createEnvironment() {
        return OrtEnvironment.getEnvironment();
    }

    public static OrtSession createSession(OrtEnvironment env, String modelPath) throws OrtException {
        SessionOptions opts = new SessionOptions();
        opts.setIntraOpNumThreads(1);
        opts.setInterOpNumThreads(1);
        opts.setOptimizationLevel(OptLevel.ALL_OPT);
        opts.setMemoryPatternOptimization(true);
        opts.setExecutionMode(ExecutionMode.SEQUENTIAL);
        return env.createSession(modelPath, opts);
    }
}