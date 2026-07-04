package com.pixelmosaic.config;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.pixelmosaic.pipeline.BufferPool;
import com.pixelmosaic.pipeline.ImageDecoder;
import com.pixelmosaic.pipeline.ImageProcessor;
import com.pixelmosaic.pipeline.MosaicMapper;
import com.pixelmosaic.pipeline.MosaicPipeline;
import com.pixelmosaic.pipeline.OnnxMaskGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central wiring for the pipeline: the (expensive, shared) ONNX runtime, the bounded buffer
 * pool and worker pool, admission control, and the pipeline components themselves. The pool
 * size, semaphore permits, and buffer count are all driven from {@code application.yml} and
 * kept equal, so a thread that wins an admission permit always finds a free buffer.
 */
@Configuration
public class AppConfig {

    /** Process-wide ONNX Runtime environment. */
    @Bean
    public OrtEnvironment ortEnvironment() {
        return OnnxSessionFactory.createEnvironment();
    }

    /**
     * Build the shared session. ONNX Runtime loads from a file path, so the model resource
     * (which may live inside the packaged jar) is copied to a temp file first.
     */
    @Bean
    public OrtSession ortSession(OrtEnvironment env,
                                 @Value("${pixelmosaic.model-path}") Resource modelPath)
            throws IOException, OrtException {
        Path tempFile = Files.createTempFile("u2netp", ".onnx");
        tempFile.toFile().deleteOnExit();
        try (InputStream in = modelPath.getInputStream()) {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return OnnxSessionFactory.createSession(env, tempFile.toString());
    }

    @Bean
    public BufferPool bufferPool(@Value("${pixelmosaic.max-concurrent}") int maxConcurrent,
                                 @Value("${pixelmosaic.max-pixels}") int maxPixels) {
        return new BufferPool(maxConcurrent, maxPixels);
    }

    /** CPU pool for the pipeline branches and sorts. Kept separate from {@link #requestExecutor}
     *  so a blocked orchestrator can never starve the branches it is waiting on. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService processingPool() {
        return new ThreadPoolExecutor(
                4, 4,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(10),
                daemonThreadFactory("pipeline-worker-"));
    }

    /** Orchestrator pool: one blocking task per admitted request. Sized to the permit count,
     *  so a request only lands here after winning a semaphore slot. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService requestExecutor(@Value("${pixelmosaic.max-concurrent}") int maxConcurrent) {
        return new ThreadPoolExecutor(
                maxConcurrent, maxConcurrent,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                daemonThreadFactory("request-orchestrator-"));
    }

    @Bean
    public Semaphore admissionSemaphore(@Value("${pixelmosaic.max-concurrent}") int max) {
        return new Semaphore(max, true);
    }

    @Bean
    public ImageDecoder imageDecoder() {
        return new ImageDecoder();
    }

    @Bean
    public OnnxMaskGenerator maskGenerator(OrtEnvironment env, OrtSession session) {
        return new OnnxMaskGenerator(env, session);
    }

    @Bean
    public ImageProcessor imageProcessor(ImageDecoder decoder, OnnxMaskGenerator maskGenerator) {
        return new ImageProcessor(decoder, maskGenerator);
    }

    @Bean
    public MosaicMapper mosaicMapper() {
        return new MosaicMapper();
    }

    @Bean
    public MosaicPipeline mosaicPipeline(ImageProcessor imageProcessor, MosaicMapper mosaicMapper,
                                         BufferPool bufferPool,
                                         @Qualifier("processingPool") ExecutorService processingPool) {
        return new MosaicPipeline(imageProcessor, mosaicMapper, bufferPool, processingPool);
    }

    /** CORS for the REST endpoints; the WebSocket handler sets its own allowed origins. */
    @Bean
    public WebMvcConfigurer corsConfigurer(
            @Value("${pixelmosaic.allowed-origins}") String allowedOrigins) {
        String[] origins = allowedOrigins.split(",");
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOriginPatterns(origins)
                        .allowedMethods("GET", "POST", "OPTIONS");
            }
        };
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
