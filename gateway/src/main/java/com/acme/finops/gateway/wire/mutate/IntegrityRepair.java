package com.acme.finops.gateway.wire.mutate;

import java.lang.foreign.MemorySegment;

/**
 * Post-reframe integrity repair pass (e.g. recomputing a CRC32 trailer)
 * applied after structural mutations to restore payload integrity.
 *
 * <p>Implementations must be thread-safe. Use {@link #NOOP} when no
 * integrity repair is required.
 */
@FunctionalInterface
public interface IntegrityRepair {
    /** A no-op implementation that performs no repair. */
    IntegrityRepair NOOP = (payload, payloadLength) -> { };

    /**
     * Repairs integrity metadata (e.g. checksums) over the given payload.
     *
     * @param payload       the memory segment containing the mutated payload
     * @param payloadLength the number of valid bytes in the payload
     */
    void repair(MemorySegment payload, int payloadLength);
}
