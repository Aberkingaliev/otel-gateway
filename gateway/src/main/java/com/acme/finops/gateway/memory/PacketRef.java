package com.acme.finops.gateway.memory;

import java.lang.foreign.MemorySegment;

/**
 * PacketRef: zero-copy envelope around off-heap payload.
 * Ownership: caller MUST release exactly once per ownership unit.
 */
public interface PacketRef extends AutoCloseable {
    long packetId();
    PacketDescriptor descriptor();

    MemorySegment segment();
    int offset();
    int length();

    int refCount();
    boolean isExclusiveOwner();

    PacketRef retain();
    boolean release();

    @Override
    default void close() { release(); }
}
