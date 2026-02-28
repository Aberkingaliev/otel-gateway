package com.acme.finops.gateway.wire.mutate;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

final class ScalarMaskWriter implements MaskWriter {
    static final ScalarMaskWriter INSTANCE = new ScalarMaskWriter();

    private ScalarMaskWriter() {
    }

    @Override
    public void mask(MemorySegment payload, MutationPlan.InplaceMaskOp op) {
        int len = op.length();
        if (len <= 0) {
            return;
        }
        if (!op.hasTokenBytes()) {
            payload.asSlice(op.absoluteOffset(), len).fill(op.maskByte());
            return;
        }

        byte[] token = op.tokenBytes();
        if (token.length == 1) {
            payload.asSlice(op.absoluteOffset(), len).fill(token[0]);
            return;
        }

        if (token.length == len) {
            MemorySegment.copy(token, 0, payload, ValueLayout.JAVA_BYTE, op.absoluteOffset(), len);
            return;
        }

        // Defensive fallback: preserve behavior even if planner passes non-matching lengths.
        payload.asSlice(op.absoluteOffset(), len).fill(op.maskByte());
    }
}
