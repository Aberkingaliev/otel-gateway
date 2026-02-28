package com.acme.finops.gateway.wire.mutate;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class SimdMaskWriterTest {

    @Test
    void shouldProduceSameBytesAsScalarWriter() {
        byte[] left = new byte[128];
        byte[] right = new byte[128];
        Arrays.fill(left, (byte) 'A');
        Arrays.fill(right, (byte) 'A');

        byte[] token = new byte[64];
        for (int i = 0; i < token.length; i++) {
            token[i] = (byte) ('a' + (i % 26));
        }

        MutationPlan.InplaceMaskOp op = new MutationPlan.InplaceMaskOp(16, token.length, (byte) '*', token, "test");
        ScalarMaskWriter.INSTANCE.mask(MemorySegment.ofArray(left), op);
        new SimdMaskWriter().mask(MemorySegment.ofArray(right), op);

        assertArrayEquals(left, right);
    }

    @Test
    void shouldProduceSameBytesAsScalarWriterForSingleByteMask() {
        byte[] left = new byte[96];
        byte[] right = new byte[96];
        Arrays.fill(left, (byte) 'Z');
        Arrays.fill(right, (byte) 'Z');

        MutationPlan.InplaceMaskOp op = new MutationPlan.InplaceMaskOp(8, 80, (byte) '*', new byte[]{'#'}, "test");
        ScalarMaskWriter.INSTANCE.mask(MemorySegment.ofArray(left), op);
        new SimdMaskWriter().mask(MemorySegment.ofArray(right), op);

        assertArrayEquals(left, right);
    }
}
