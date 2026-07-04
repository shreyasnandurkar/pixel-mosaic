package com.pixelmosaic.pipeline;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.pixelmosaic.config.ModelLoader;
import com.pixelmosaic.config.OnnxSessionFactory;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Standalone end-to-end integration check for the full pipeline (not a JUnit test). Builds
 * every component by hand (no Spring), runs two real images through {@link MosaicPipeline},
 * prints timing and mapping stats, and writes {@code output.bin} for inspection.
 *
 * <p>Run (note {@code classpathScope=test}, since this class lives under {@code src/test}):
 * <pre>
 *   mvn exec:java -pl backend -Dexec.classpathScope=test \
 *       -Dexec.mainClass=com.pixelmosaic.pipeline.PipelineSmokeTest \
 *       -Dexec.args="source.jpg target.jpg"
 * </pre>
 */
public final class PipelineSmokeTest {

    private static final int BYTES_PER_PARTICLE = MosaicMapper.BYTES_PER_PARTICLE;

    private PipelineSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: PipelineSmokeTest <source-image> <target-image>");
            System.exit(1);
        }
        if (!ModelLoader.isAvailable()) {
            System.out.println(ModelLoader.missingMessage());
            System.exit(0);
        }

        byte[] sourceBytes = Files.readAllBytes(Path.of(args[0]));
        byte[] targetBytes = Files.readAllBytes(Path.of(args[1]));

        OrtEnvironment env = OnnxSessionFactory.createEnvironment();
        String modelPath = ModelLoader.resolveFilePath();
        try (OrtSession session = OnnxSessionFactory.createSession(env, modelPath)) {
            ImageDecoder decoder = new ImageDecoder();
            OnnxMaskGenerator maskGenerator = new OnnxMaskGenerator(env, session);
            BufferPool bufferPool = new BufferPool(2, ImageDecoder.MAX_PIXELS);
            MosaicMapper mapper = new MosaicMapper();
            ImageProcessor imageProcessor = new ImageProcessor(decoder, maskGenerator);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            MosaicPipeline pipeline = new MosaicPipeline(imageProcessor, mapper, bufferPool, pool);

            try {
                // Canonical timed run through the facade.
                long t0 = System.nanoTime();
                MosaicResult result = pipeline.process(sourceBytes, targetBytes);
                long ms = (System.nanoTime() - t0) / 1_000_000;

                ByteBuffer output = result.payload();
                int payloadBytes = output.remaining();
                int particles = result.particleCount();
                System.out.printf("Pipeline completed in %dms%n", ms);
                System.out.printf("Output payload: %.2f MB (%d particles)%n",
                        payloadBytes / (1024.0 * 1024.0), particles);

                // Instrumented second pass to surface internal split/ratio stats.
                printStats(imageProcessor, mapper, bufferPool, sourceBytes, targetBytes);

                printParticles(output, particles);
                Files.write(Path.of("output.bin"), toByteArray(output));
                System.out.println("Wrote output.bin (" + payloadBytes + " bytes)");
            } finally {
                pool.shutdown();
            }
        }
    }

    /** Re-run decode + mask + pack + sort on a fresh buffer to read the split/ratio internals. */
    private static void printStats(ImageProcessor imageProcessor, MosaicMapper mapper,
                                   BufferPool bufferPool, byte[] sourceBytes, byte[] targetBytes)
            throws Exception {
        RequestBuffers buf = bufferPool.acquire();
        try {
            imageProcessor.process(sourceBytes, buf, true);
            imageProcessor.process(targetBytes, buf, false);
            mapper.map(buf); // sorts sourceData/targetData in place

            int srcLen = buf.srcPixelCount;
            int tgtLen = buf.tgtPixelCount;
            int srcSplit = PixelUtils.findFgBgSplit(buf.sourceData, srcLen);
            int tgtSplit = PixelUtils.findFgBgSplit(buf.targetData, tgtLen);
            int srcBg = srcLen - srcSplit;
            int tgtBg = tgtLen - tgtSplit;
            float fgRatio = tgtSplit > 0 ? (float) srcSplit / tgtSplit : Float.NaN;
            float bgRatio = tgtBg > 0 ? (float) srcBg / tgtBg : Float.NaN;

            System.out.printf("Source: %dx%d (%d pixels), FG: %d, BG: %d%n",
                    buf.srcWidth, buf.srcHeight, srcLen, srcSplit, srcBg);
            System.out.printf("Target: %dx%d (%d pixels), FG: %d, BG: %d%n",
                    buf.tgtWidth, buf.tgtHeight, tgtLen, tgtSplit, tgtBg);
            System.out.printf("fgRatio: %.4f, bgRatio: %.4f%n", fgRatio, bgRatio);
        } finally {
            bufferPool.release(buf);
        }
    }

    /** Print up to the first 100 particles in human-readable form. */
    private static void printParticles(ByteBuffer output, int particles) {
        ByteBuffer rp = output.duplicate();
        int show = Math.min(100, particles);
        for (int p = 0; p < show; p++) {
            int srcX = rp.getShort() & 0xFFFF;
            int srcY = rp.getShort() & 0xFFFF;
            int tgtX = rp.getShort() & 0xFFFF;
            int tgtY = rp.getShort() & 0xFFFF;
            int r = rp.get() & 0xFF;
            int g = rp.get() & 0xFF;
            int b = rp.get() & 0xFF;
            rp.get(); // reserved
            System.out.printf("Particle %d: src(%d,%d)->tgt(%d,%d) rgb(%d,%d,%d)%n",
                    p, srcX, srcY, tgtX, tgtY, r, g, b);
        }
    }

    private static byte[] toByteArray(ByteBuffer buf) {
        byte[] bytes = new byte[buf.remaining()];
        buf.duplicate().get(bytes);
        return bytes;
    }
}
