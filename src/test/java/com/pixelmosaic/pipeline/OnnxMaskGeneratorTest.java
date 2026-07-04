package com.pixelmosaic.pipeline;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the mask pipeline without the real model: the ORT session is mocked to
 * return a constant soft mask, so we test resize/threshold/morphology, not inference.
 * A real {@link OrtEnvironment} is used only to allocate the input tensor.
 */
class OnnxMaskGeneratorTest {

    private static final int MASK_FLOATS = 320 * 320;

    @Test
    void testMaskDimensions() throws OrtException {
        BitSet mask = runWithConstantMask(1.0f, 100, 100);
        assertEquals(10_000, mask.cardinality(), "all-foreground soft mask -> every pixel set");
    }

    @Test
    void testThresholdingAbove() throws OrtException {
        int w = 64, h = 48;
        BitSet mask = runWithConstantMask(1.0f, w, h);
        assertEquals(w * h, mask.cardinality(), "values above 0.5 -> full foreground");
    }

    @Test
    void testThresholdingBelow() throws OrtException {
        BitSet mask = runWithConstantMask(0.0f, 64, 48);
        assertEquals(0, mask.cardinality(), "values below 0.5 -> empty mask");
    }

    /** Build a generator whose session returns {@code fill} for every mask element. */
    private static BitSet runWithConstantMask(float fill, int width, int height) throws OrtException {
        OrtEnvironment env = OrtEnvironment.getEnvironment();

        float[] values = new float[MASK_FLOATS];
        Arrays.fill(values, fill);

        OnnxTensor output = mock(OnnxTensor.class);
        when(output.getFloatBuffer()).thenReturn(FloatBuffer.wrap(values));

        OrtSession.Result result = mock(OrtSession.Result.class);
        when(result.get(0)).thenReturn(output);

        OrtSession session = mock(OrtSession.class);
        when(session.getInputNames()).thenReturn(Set.of("input"));
        when(session.run(anyMap())).thenReturn(result);

        OnnxMaskGenerator generator = new OnnxMaskGenerator(env, session);
        int[] raster = new int[width * height]; // contents irrelevant; mask is mocked
        return generator.generateMask(raster, width, height);
    }
}
