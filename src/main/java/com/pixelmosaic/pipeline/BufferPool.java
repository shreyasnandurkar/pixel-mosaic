package com.pixelmosaic.pipeline;

import java.util.concurrent.ArrayBlockingQueue;

public final class BufferPool {

    private final ArrayBlockingQueue<RequestBuffers> pool;

    public BufferPool(int poolSize, int maxPixels) {
        pool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            pool.offer(new RequestBuffers(maxPixels));
        }
    }

    public RequestBuffers acquire() throws InterruptedException {
        return pool.take();
    }

    public void release(RequestBuffers buf) {
        buf.reset();
        pool.offer(buf);
    }
}