package com.pixelmosaic.pipeline;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * Fixed pool of {@link RequestBuffers}. All slots are allocated eagerly at startup so the
 * ~48&nbsp;MB-per-slot allocation never happens on the request path. The pool size matches
 * the admission semaphore's permit count, so a thread that holds a permit is guaranteed a
 * free buffer and {@link #acquire()} never actually blocks.
 */
public final class BufferPool {

    private final ArrayBlockingQueue<RequestBuffers> pool;

    public BufferPool(int poolSize, int maxPixels) {
        pool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            pool.offer(new RequestBuffers(maxPixels));
        }
    }

    /** Take a buffer, blocking until one is free. */
    public RequestBuffers acquire() throws InterruptedException {
        return pool.take();
    }

    /** Reset and return a buffer to the pool. */
    public void release(RequestBuffers buf) {
        buf.reset();
        pool.offer(buf);
    }
}
