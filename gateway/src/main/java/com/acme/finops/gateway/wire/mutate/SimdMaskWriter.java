package com.acme.finops.gateway.wire.mutate;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

final class SimdMaskWriter implements MaskWriter {
    private static final int MIN_SIMD_LENGTH = 32;
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

    @Override
    public void mask(MemorySegment payload, MutationPlan.InplaceMaskOp op) {
        int len = op.length();
        if (len <= 0) {
            return;
        }
        if (len < MIN_SIMD_LENGTH || SPECIES.length() <= 1) {
            ScalarMaskWriter.INSTANCE.mask(payload, op);
            return;
        }

        byte[] token = op.tokenBytes();
        long base = op.absoluteOffset();

        if (!op.hasTokenBytes()) {
            fill(payload, base, len, op.maskByte());
            return;
        }

        if (token.length == 1) {
            fill(payload, base, len, token[0]);
            return;
        }

        if (token.length != len) {
            ScalarMaskWriter.INSTANCE.mask(payload, op);
            return;
        }

        int i = 0;
        int lane = SPECIES.length();
        while (i + lane <= len) {
            ByteVector v = ByteVector.fromArray(SPECIES, token, i);
            v.intoMemorySegment(payload, base + i, ByteOrder.nativeOrder());
            i += lane;
        }
        while (i < len) {
            payload.set(ValueLayout.JAVA_BYTE, base + i, token[i]);
            i++;
        }
    }

    private static void fill(MemorySegment payload, long absoluteOffset, int len, byte value) {
        int i = 0;
        int lane = SPECIES.length();
        ByteVector fill = ByteVector.broadcast(SPECIES, value);
        while (i + lane <= len) {
            fill.intoMemorySegment(payload, absoluteOffset + i, ByteOrder.nativeOrder());
            i += lane;
        }
        while (i < len) {
            payload.set(ValueLayout.JAVA_BYTE, absoluteOffset + i, value);
            i++;
        }
    }
}
