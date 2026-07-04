package com.pixelmosaic.pipeline;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * Facade that turns two encoded images (source + target) into a packed particle buffer.
 * The source and target branches are decoded, masked, and packed in parallel on the shared
 * processing pool; the mapper then sorts and pairs them. A {@link BufferPool} slot is held
 * for the duration of one request and released before the result is returned.
 */
public final class MosaicPipeline {

    private final ImageProcessor imageProcessor;
    private final MosaicMapper mapper;
    private final BufferPool bufferPool;
    private final ExecutorService processingPool;

    public MosaicPipeline(ImageProcessor imageProcessor, MosaicMapper mapper,
                          BufferPool bufferPool, ExecutorService processingPool) {
        this.imageProcessor = imageProcessor;
        this.mapper = mapper;
        this.bufferPool = bufferPool;
        this.processingPool = processingPool;
    }

    /**
     * Run the full pipeline. The returned {@link MosaicResult} carries a flipped, ready-to-go
     * particle buffer plus the source/target dimensions; the working buffer is released back
     * to the pool before this returns, so the caller may stream the result at its leisure.
     */
    public MosaicResult process(byte[] sourceBytes, byte[] targetBytes) throws Exception {
        RequestBuffers buf = bufferPool.acquire();
        try {
            CompletableFuture<Integer> srcFuture = CompletableFuture.supplyAsync(
                    () -> processBranch(sourceBytes, buf, true), processingPool);
            CompletableFuture<Integer> tgtFuture = CompletableFuture.supplyAsync(
                    () -> processBranch(targetBytes, buf, false), processingPool);
            CompletableFuture.allOf(srcFuture, tgtFuture).get();

            ByteBuffer payload = mapper.map(buf);
            return new MosaicResult(payload,
                    buf.srcWidth, buf.srcHeight,
                    buf.tgtWidth, buf.tgtHeight,
                    buf.tgtPixelCount);
        } finally {
            bufferPool.release(buf);
        }
    }

    private int processBranch(byte[] imageBytes, RequestBuffers buf, boolean isSource) {
        try {
            return imageProcessor.process(imageBytes, buf, isSource);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }
}