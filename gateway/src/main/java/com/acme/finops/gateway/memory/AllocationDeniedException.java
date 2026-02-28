package com.acme.finops.gateway.memory;

/**
 * Thrown when a slab allocation is denied (all regions draining / capacity exhausted).
 * Carries the allocator's reason code so ingress adapters can map it to the appropriate
 * transport-level status (HTTP 503 / gRPC UNAVAILABLE).
 */
public final class AllocationDeniedException extends RuntimeException {
    private final int reasonCode;

    public AllocationDeniedException(int reasonCode) {
        super("Packet allocation denied, reasonCode=" + reasonCode);
        this.reasonCode = reasonCode;
    }

    public int reasonCode() {
        return reasonCode;
    }
}
