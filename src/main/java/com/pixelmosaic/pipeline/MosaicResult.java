package com.pixelmosaic.pipeline;

import java.nio.ByteBuffer;

public record MosaicResult(ByteBuffer payload,
                           int srcWidth, int srcHeight,
                           int tgtWidth, int tgtHeight,
                           int particleCount) {
}