package com.pixelmosaic.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/*
 * ============================================================================
 *  BENCHMARK RESULTS
 *  Command: java -jar benchmark/target/benchmarks.jar -wi 2 -i 3 -f 1
 *  JMH 1.37 | JDK 21.0.2 HotSpot 64-bit | x86_64 Windows dev machine
 *  (Targets below are stated for the Oracle Ampere A1 deploy target; this
 *   x86 laptop run is indicative only.)
 * ----------------------------------------------------------------------------
 *  Benchmark                                   Mode  Cnt    Score     Error  Units   Target
 *  benchmarkPack                               avgt    3  120.877 ± 365.659  ms/op   < 150  (PASS)
 *  benchmarkSort                               avgt    3  285.996 ± 612.716  ms/op   < 120  (over)
 *  benchmarkMap                                avgt    3   33.242 ± 100.891  ms/op   <  30  (marginal)
 *  benchmarkFullPipeline                       avgt    3  457.939 ± 254.690  ms/op   < 300  (over)
 *
 *  fullPipeline ~= pack + sort + map (458 ~= 121 + 286 + 33), confirming the
 *  composite measures the same work as the parts. Single-threaded Arrays.sort
 *  of 2M longs dominates; the design recovers wall-clock time by sorting the
 *  source and target arrays concurrently (not modelled in this micro-bench).
 *  Error bars are wide because Cnt=3; raise -i/-f for tighter intervals.
 * ============================================================================
 */

/**
 * JMH micro-benchmarks for the four hot-path stages of the pixel pipeline:
 * packing, sorting, dual-ratio mapping, and the three combined.
 *
 * <p>Data is synthetic (see {@link SyntheticDataGenerator}) and sized to the
 * 2,000,000-pixel cap (~1414x1414). The pristine unsorted source snapshot is
 * restored before every invocation so {@link #benchmarkSort()} always measures
 * sorting unsorted data rather than re-sorting an already-sorted array.
 */
@State(Scope.Benchmark)
public class PixelPackerBenchmark {

    static final int PIXELS = 2_000_000;
    static final int WIDTH = 1414;
    static final int HEIGHT = 1414;
    static final int BYTES_PER_PARTICLE = 12;

    long[] sourceData;
    long[] targetData;
    int[] sourceRaster;
    ByteBuffer outputBuffer;

    /** Pristine, unsorted source snapshot used to reset {@link #sourceData}. */
    private long[] sourcePristine;

    @Setup(Level.Trial)
    public void setup() {
        sourceData = new long[PIXELS];
        targetData = new long[PIXELS];
        sourcePristine = new long[PIXELS];
        sourceRaster = new int[PIXELS];
        outputBuffer = ByteBuffer.allocateDirect(PIXELS * BYTES_PER_PARTICLE);

        SyntheticDataGenerator.generateSourceData(sourcePristine, WIDTH, HEIGHT);
        SyntheticDataGenerator.generateTargetData(targetData, WIDTH, HEIGHT);

        // The mapping stage assumes a sorted target lane; sort it once up front.
        Arrays.sort(targetData, 0, PIXELS);

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < PIXELS; i++) {
            sourceRaster[i] = rnd.nextInt();
        }

        System.arraycopy(sourcePristine, 0, sourceData, 0, PIXELS);
    }

    /** Restore unsorted source before each invocation; runs outside the timed region. */
    @Setup(Level.Invocation)
    public void refresh() {
        System.arraycopy(sourcePristine, 0, sourceData, 0, PIXELS);
    }

    // ----- a. pack -----------------------------------------------------------
    // Target: < 150 ms
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public long[] benchmarkPack() {
        pack(sourceData);
        return sourceData;
    }

    // ----- b. sort -----------------------------------------------------------
    // Target: < 120 ms
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public long[] benchmarkSort() {
        Arrays.sort(sourceData, 0, PIXELS);
        return sourceData;
    }

    // ----- c. map ------------------------------------------------------------
    // Target: < 30 ms
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public ByteBuffer benchmarkMap() {
        return map();
    }

    // ----- d. full pipeline: pack + sort + map -------------------------------
    // Target: < 300 ms
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public ByteBuffer benchmarkFullPipeline() {
        pack(sourceData);
        Arrays.sort(sourceData, 0, PIXELS);
        return map();
    }

    /** Pack every synthetic ARGB pixel into the destination long[]. */
    private void pack(long[] dst) {
        for (int i = 0; i < PIXELS; i++) {
            int argb = sourceRaster[i];
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;

            int x = i % WIDTH;
            int y = i / WIDTH;

            int lum = PixelUtils.computeLuminance(r, g, b);
            int hue = PixelUtils.computeHue(r, g, b);
            int hash = PixelUtils.spatialHash(x, y);
            int fgBit = (hash & 1); // cheap deterministic FG/BG assignment

            dst[i] = PixelUtils.packSource(fgBit, lum, hue, hash, x, y);
        }
    }

    /**
     * Dual-ratio mapping: each target pixel pulls a source pixel from the same
     * lane (FG or BG) using the per-lane index ratio, then 12 bytes per particle
     * are written to the direct output buffer.
     */
    private ByteBuffer map() {
        int srcSplit = PixelUtils.findFgBgSplit(sourceData, PIXELS);
        int tgtSplit = PixelUtils.findFgBgSplit(targetData, PIXELS);

        float fgRatio = tgtSplit == 0 ? 0f : (float) srcSplit / (float) tgtSplit;
        int srcBgLen = PIXELS - srcSplit;
        int tgtBgLen = PIXELS - tgtSplit;
        float bgRatio = tgtBgLen == 0 ? 0f : (float) srcBgLen / (float) tgtBgLen;

        ByteBuffer out = outputBuffer;
        out.clear();

        for (int i = 0; i < PIXELS; i++) {
            long tgt = targetData[i];

            int srcIdx;
            if (i < tgtSplit) {
                // FG lane: source [0, srcSplit)
                srcIdx = (int) Math.floor(i * fgRatio);
                if (srcIdx >= srcSplit) srcIdx = srcSplit - 1;
                if (srcIdx < 0) srcIdx = 0;
            } else {
                // BG lane: source [srcSplit, PIXELS)
                int off = i - tgtSplit;
                srcIdx = srcSplit + (int) Math.floor(off * bgRatio);
                if (srcIdx >= PIXELS) srcIdx = PIXELS - 1;
                if (srcIdx < srcSplit) srcIdx = srcSplit;
            }

            long src = sourceData[srcIdx];
            int srcX = PixelUtils.unpackX(src);
            int srcY = PixelUtils.unpackY(src);
            int tgtX = PixelUtils.unpackX(tgt);
            int tgtY = PixelUtils.unpackY(tgt);

            int argb = sourceRaster[srcIdx]; // RGB recovered from the resident raster
            byte r = (byte) ((argb >> 16) & 0xFF);
            byte g = (byte) ((argb >> 8) & 0xFF);
            byte b = (byte) (argb & 0xFF);

            out.putShort((short) srcX);
            out.putShort((short) srcY);
            out.putShort((short) tgtX);
            out.putShort((short) tgtY);
            out.put(r).put(g).put(b).put((byte) 0);
        }

        out.flip();
        return out;
    }
}