package com.acme.finops.gateway.wire.mutate;

import java.lang.foreign.MemorySegment;

/**
 * Performs in-place byte-level masking on a wire payload.
 *
 * <p>Implementations must be thread-safe; the same instance may be invoked
 * concurrently from multiple pipeline threads.
 */
public interface MaskWriter {
    /** Applies the masking operation {@code op} to the given payload segment in-place. */
    void mask(MemorySegment payload, MutationPlan.InplaceMaskOp op);

    /** Returns the default scalar (single-threaded loop) mask writer. */
    static MaskWriter scalar() {
        return ScalarMaskWriter.INSTANCE;
    }
}
