package com.pixelmosaic.pipeline;

import java.nio.ByteBuffer;

/**
 * Result of one pipeline run: the packed particle buffer plus the dimension metadata the
 * client needs to interpret it. The payload is a flipped, ready-to-read {@link ByteBuffer}
 * holding {@code particleCount} 12-byte particles.
 */
public record MosaicResult(ByteBuffer payload,
                           int srcWidth, int srcHeight,
                           int tgtWidth, int tgtHeight,
                           int particleCount) {
}
