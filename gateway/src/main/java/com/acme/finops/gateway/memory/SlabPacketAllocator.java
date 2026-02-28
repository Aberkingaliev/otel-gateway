package com.acme.finops.gateway.memory;

import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.util.GatewayStatusCodes;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * MVP slab allocator: one shared slab + bump pointer.
 * Release strategy is intentionally simplified for bootstrap.
 */
public final class SlabPacketAllocator implements PacketAllocator {
    private static final Logger LOG = Logger.getLogger(SlabPacketAllocator.class.getName());

    private final Arena arena;
    private final MemorySegment slab;
    private final long capacity;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ReentrantLock lock = new ReentrantLock();
    private final TreeMap<Long, FreeBlock> freeByStart = new TreeMap<>();
    private final TreeSet<FreeBlock> freeBySize = new TreeSet<>(
        Comparator.comparingLong(FreeBlock::size).thenComparingLong(FreeBlock::start)
    );
    private long cursor = 0;
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
        activeAllocations.incrementAndGet();
        lock.lock();
        try {
            if (closed.get()) {
                activeAllocations.decrementAndGet();
                failedAllocations.incrementAndGet();
                return new LeaseResult.Denied(GatewayStatusCodes.SERVICE_UNAVAILABLE);
            }
            Reservation reservation = reserve(size);
            if (reservation == null) {
                activeAllocations.decrementAndGet();
                failedAllocations.incrementAndGet();
                return new LeaseResult.Denied(GatewayStatusCodes.INSUFFICIENT_STORAGE);
            }

            try {
                MemorySegment slice = slab.asSlice(reservation.start(), size);
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
                PacketRef tracked = new TrackedPacketRef(ref, reservation.end(), reservation.start());

                allocCount.incrementAndGet();
                return new LeaseResult.Granted(tracked);
            } catch (RuntimeException e) {
                rollbackReservation(reservation);
                activeAllocations.decrementAndGet();
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AllocatorStats stats() {
        long usedBytes;
        lock.lock();
        try {
            usedBytes = cursor;
        } finally {
            lock.unlock();
        }
        return new AllocatorStats(
            allocCount.get(),
            releaseCount.get(),
            usedBytes,
            failedAllocations.get()
        );
    }

    private static long align8(int n) {
        return (n + 7L) & ~7L;
    }

    private final class TrackedPacketRef implements PacketRef {
        private final PacketRefImpl delegate;
        private final long end;
        private final long start;

        private TrackedPacketRef(PacketRefImpl delegate, long end, long start) {
            this.delegate = delegate;
            this.end = end;
            this.start = start;
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
                lock.lock();
                try {
                    if (active == 0) {
                        // No live packets remain: full rewind eliminates fragmentation from out-of-order releases.
                        freeByStart.clear();
                        freeBySize.clear();
                        cursor = 0;
                    } else {
                        addFreeBlock(new FreeBlock(start, end - start));
                        trimCursorFromTail();
                    }
                } finally {
                    lock.unlock();
                }
            }
            return done;
        }

        @Override
        public void close() {
            release();
        }
    }

    private Reservation reserve(long size) {
        FreeBlock fromFree = findBestFit(size);
        if (fromFree != null) {
            removeFreeBlock(fromFree);
            long remainder = fromFree.size() - size;
            long start = fromFree.start();
            long end = start + size;
            if (remainder > 0) {
                addFreeBlock(new FreeBlock(end, remainder));
            }
            return new Reservation(start, end, false);
        }

        long start = cursor;
        long end = start + size;
        if (end < start || end > capacity) {
            return null;
        }
        cursor = end;
        return new Reservation(start, end, true);
    }

    private void rollbackReservation(Reservation reservation) {
        if (reservation.fromCursor()) {
            cursor = reservation.start();
            return;
        }
        addFreeBlock(new FreeBlock(reservation.start(), reservation.end() - reservation.start()));
    }

    private FreeBlock findBestFit(long size) {
        return freeBySize.ceiling(new FreeBlock(Long.MIN_VALUE, size));
    }

    private void addFreeBlock(FreeBlock block) {
        if (block.size() <= 0) {
            return;
        }

        long mergedStart = block.start();
        long mergedEnd = block.end();

        Map.Entry<Long, FreeBlock> left = freeByStart.floorEntry(mergedStart);
        if (left != null && left.getValue().end() == mergedStart) {
            FreeBlock prev = left.getValue();
            removeFreeBlock(prev);
            mergedStart = prev.start();
        }

        while (true) {
            FreeBlock right = freeByStart.get(mergedEnd);
            if (right == null) {
                break;
            }
            removeFreeBlock(right);
            mergedEnd = right.end();
        }

        FreeBlock merged = new FreeBlock(mergedStart, mergedEnd - mergedStart);
        freeByStart.put(merged.start(), merged);
        freeBySize.add(merged);
    }

    private void removeFreeBlock(FreeBlock block) {
        freeByStart.remove(block.start());
        freeBySize.remove(block);
    }

    private void trimCursorFromTail() {
        while (cursor > 0) {
            Map.Entry<Long, FreeBlock> candidate = freeByStart.floorEntry(cursor - 1);
            if (candidate == null) {
                return;
            }
            FreeBlock tail = candidate.getValue();
            if (tail.end() != cursor) {
                return;
            }
            removeFreeBlock(tail);
            cursor = tail.start();
        }
    }

    private record Reservation(long start, long end, boolean fromCursor) {}

    private record FreeBlock(long start, long size) {
        private long end() {
            return start + size;
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
        lock.lock();
        try {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            long active = activeAllocations.get();
            if (active > 0) {
                LOG.warning("Closing SlabPacketAllocator with " + active
                    + " active allocations still in flight — potential use-after-free risk");
            }
            freeByStart.clear();
            freeBySize.clear();
            cursor = 0;
            arena.close();
        } finally {
            lock.unlock();
        }
    }
}
