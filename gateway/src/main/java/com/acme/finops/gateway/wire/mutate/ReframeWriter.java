package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.memory.PacketAllocator;
import com.acme.finops.gateway.memory.PacketRef;

/**
 * Executes the reframe pass of a {@link MutationPlan}, producing a new packet
 * with structural changes (field additions, removals, or reordering).
 *
 * <p>A destination {@code PacketRef} is allocated from the supplied allocator.
 * On success the caller owns the destination ref and must release it.
 * On failure the writer releases the destination before returning.
 */
public interface ReframeWriter {
    /**
     * Writes the reframed packet.
     *
     * @param plan      the mutation plan to execute
     * @param source    the original packet (not modified; caller retains ownership)
     * @param allocator allocator used to obtain the destination buffer
     * @return result containing the new {@code PacketRef} on success
     */
    ReframeResult write(MutationPlan plan, PacketRef source, PacketAllocator allocator);
}
