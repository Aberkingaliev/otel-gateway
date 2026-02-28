package com.acme.finops.gateway.backpressure;

import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.queue.QueueSnapshot;

/**
 * Decides whether an inbound packet should be dropped based on current
 * queue state and system load.
 *
 * <p>Implementations must be thread-safe and allocation-free on the hot path.
 */
public interface DropPolicy {
    /**
     * Evaluates whether the given packet should be accepted or dropped.
     *
     * @param packet   the candidate packet
     * @param snapshot current queue depth and capacity snapshot
     * @param nowNanos monotonic timestamp in nanoseconds
     * @return the drop-or-accept decision
     */
    DropDecision decide(PacketRef packet, QueueSnapshot snapshot, long nowNanos);
}
