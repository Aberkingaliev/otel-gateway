package com.acme.finops.gateway.memory;

/**
 * Contract for off-heap packet buffer allocation.
 *
 * <p>Implementations must be thread-safe. Each returned {@link LeaseResult}
 * wraps a {@code PacketRef} that the caller owns and must release when done.
 * Closing the allocator releases any remaining pooled memory.
 */
public interface PacketAllocator extends AutoCloseable {
    /**
     * Allocates a buffer of at least {@code minBytes} tagged with {@code tag}.
     *
     * @return a {@link LeaseResult} whose {@code PacketRef} must be released by the caller
     */
    LeaseResult allocate(int minBytes, AllocationTag tag);

    /** Returns a snapshot of current allocation statistics. */
    AllocatorStats stats();

    @Override
    default void close() {
    }
}
