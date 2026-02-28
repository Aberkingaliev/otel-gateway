package com.acme.finops.gateway.memory;

import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.util.GatewayStatusCodes;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Lock-free bump-pointer slab allocator.
 *
 * <p>Allocation is a single CAS on an atomic cursor — O(1), zero contention.
 * Release decrements an active-allocation counter; when it reaches zero the
 * cursor rewinds to 0 (epoch reset), reclaiming the entire slab instantly.
 *
 * <p>There is no per-block free-list: individual releases do NOT return memory
 * to the slab until the epoch resets. This is optimal for short-lived packets
 * that flow through a pipeline (allocate → process → export → release) because
 * under steady throughput the epoch resets frequently.
 */
public final class SlabPacketAllocator implements PacketAllocator {
    private static final Logger LOG = Logger.getLogger(SlabPacketAllocator.class.getName());

    private final Arena arena;
    private final MemorySegment slab;
    private final long capacity;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final AtomicLong cursor = new AtomicLong(0);
    private final AtomicLong seq = new AtomicLong(1);

    private final AtomicLong allocCount = new AtomicLong();
    private final AtomicLong releaseCount = new AtomicLong();
    private final AtomicLong failedAllocations = new AtomicLong();
    private final AtomicLong activeAllocations = new AtomicLong();

    public SlabPacketAllocator(long capacityBytes) {
        this.arena = Arena.ofShared();
        this.slab = arena.allocate(capacityBytes, 8);
        this.capacity = capacityBytes;
    }

    @Override
    public LeaseResult allocate(int minBytes, AllocationTag tag) {
        if (closed.get()) {
            return new LeaseResult.Denied(GatewayStatusCodes.SERVICE_UNAVAILABLE);
        }
        if (minBytes <= 0) {
            return new LeaseResult.Denied(GatewayStatusCodes.BAD_REQUEST);
        }

        final long size = align8(minBytes);

        // CAS bump — lock-free
        long start;
        long end;
        while (true) {
            start = cursor.get();
            end = start + size;
            if (end < start || end > capacity) {
                failedAllocations.incrementAndGet();
                return new LeaseResult.Denied(GatewayStatusCodes.INSUFFICIENT_STORAGE);
            }
            if (cursor.compareAndSet(start, end)) {
                break;
            }
            // CAS failed — another thread bumped; retry
        }

        activeAllocations.incrementAndGet();

        MemorySegment slice = slab.asSlice(start, size);
        long packetId = seq.getAndIncrement();

        PacketDescriptor descriptor = new PacketDescriptor(
            packetId,
            0L,
            signalKindFromCode(tag == null ? 0 : tag.signalTypeCode()),
            null,
            0,
            minBytes,
            System.nanoTime()
        );

        PacketRefImpl ref = new PacketRefImpl(packetId, descriptor, slice, 0, minBytes);
        PacketRef tracked = new TrackedPacketRef(ref);

        allocCount.incrementAndGet();
        return new LeaseResult.Granted(tracked);
    }

    @Override
    public AllocatorStats stats() {
        return new AllocatorStats(
            allocCount.get(),
            releaseCount.get(),
            cursor.get(),
            failedAllocations.get()
        );
    }

    private static long align8(int n) {
        return (n + 7L) & ~7L;
    }

    private final class TrackedPacketRef implements PacketRef {
        private final PacketRefImpl delegate;

        private TrackedPacketRef(PacketRefImpl delegate) {
            this.delegate = delegate;
        }

        @Override
        public long packetId() {
            return delegate.packetId();
        }

        @Override
        public PacketDescriptor descriptor() {
            return delegate.descriptor();
        }

        @Override
        public MemorySegment segment() {
            return delegate.segment();
        }

        @Override
        public int offset() {
            return delegate.offset();
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public int refCount() {
            return delegate.refCount();
        }

        @Override
        public boolean isExclusiveOwner() {
            return delegate.isExclusiveOwner();
        }

        @Override
        public PacketRef retain() {
            delegate.retain();
            return this;
        }

        @Override
        public boolean release() {
            boolean done = delegate.release();
            if (done) {
                releaseCount.incrementAndGet();
                long active = activeAllocations.decrementAndGet();
                if (active == 0) {
                    // Epoch reset: all packets released, reclaim entire slab.
                    cursor.set(0);
                }
            }
            return done;
        }

        @Override
        public void close() {
            release();
        }
    }

    private static SignalKind signalKindFromCode(int signalTypeCode) {
        return switch (signalTypeCode) {
            case 1 -> SignalKind.TRACES;
            case 2 -> SignalKind.METRICS;
            case 3 -> SignalKind.LOGS;
            default -> null;
        };
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        long active = activeAllocations.get();
        if (active > 0) {
            LOG.warning("Closing SlabPacketAllocator with " + active
                + " active allocations still in flight — potential use-after-free risk");
        }
        cursor.set(0);
        arena.close();
    }
}
