package com.acme.finops.gateway.memory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;

public final class PacketRefImpl implements PacketRef {
    private final long packetId;
    private final PacketDescriptor descriptor;
    private final MemorySegment segment;
    private final int offset;
    private final int length;
    private final AtomicInteger refCount = new AtomicInteger(1);

    public PacketRefImpl(long packetId,
                         PacketDescriptor descriptor,
                         MemorySegment segment,
                         int offset,
                         int length) {
        this.packetId = packetId;
        this.descriptor = descriptor;
        this.segment = segment;
        this.offset = offset;
        this.length = length;
    }

    @Override
    public long packetId() {
        return packetId;
    }

    @Override
    public PacketDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public MemorySegment segment() {
        return segment;
    }

    @Override
    public int offset() {
        return offset;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public int refCount() {
        return refCount.get();
    }

    @Override
    public boolean isExclusiveOwner() {
        return refCount.get() == 1;
    }

    @Override
    public PacketRef retain() {
        while (true) {
            int current = refCount.get();
            if (current <= 0) {
                throw new IllegalStateException("Retain after release for packetId=" + packetId);
            }
            if (refCount.compareAndSet(current, current + 1)) {
                return this;
            }
        }
    }

    @Override
    public boolean release() {
        while (true) {
            int current = refCount.get();
            if (current <= 0) {
                throw new IllegalStateException("Double release for packetId=" + packetId);
            }
            int next = current - 1;
            if (refCount.compareAndSet(current, next)) {
                return next == 0;
            }
        }
    }

    @Override
    public void close() {
        release();
    }
}
