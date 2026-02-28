package com.acme.finops.gateway.memory;

import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.util.GatewayStatusCodes;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Lock-free circular multi-region slab allocator.
 *
 * <p>The slab is divided into N independent regions, each acting as an
 * independent bump-pointer allocator with its own cursor and active-allocation
 * counter. Only one region is ACTIVE at a time; allocations CAS-bump its
 * cursor. When the active region is full, it transitions to DRAINING and the
 * allocator rotates circularly to the next FREE region.
 *
 * <p>When all packets allocated from a DRAINING region are released, that
 * region resets its cursor to 0 and transitions back to FREE — independently
 * of other regions. This eliminates the "epoch stall" problem where the entire
 * slab could not reclaim until ALL packets were released.
 *
 * <h3>Region States</h3>
 * <ul>
 *   <li><b>FREE</b> — cursor=0, activeAllocations=0, ready for allocation</li>
 *   <li><b>ACTIVE</b> — bump-pointer advancing (only ONE region ACTIVE at any time)</li>
 *   <li><b>DRAINING</b> — filled or rotated, packets still live, awaiting all releases</li>
 * </ul>
 *
 * <h3>Concurrency Protocol</h3>
 * <ul>
 *   <li>The {@code ACTIVE→DRAINING} CAS in {@link #rotateFromFull} is the rotation
 *       mutex: only the thread that wins it performs the circular scan and publishes
 *       the new active region index via a plain {@code set()}.</li>
 *   <li>{@link Region#tryBump} increments {@code activeAllocations} AFTER the cursor
 *       CAS succeeds (not before). The ACTIVE epoch-reset in {@link Region#releaseOne}
 *       re-checks {@code activeAllocations == 0} to guard against a concurrent post-CAS
 *       increment that raced with the reset.</li>
 *   <li>For DRAINING regions, {@code cursor.set(0)} happens BEFORE the
 *       {@code DRAINING→FREE} CAS so no thread can activate the region before the
 *       cursor is zeroed.</li>
 * </ul>
 */
public final class SlabPacketAllocator implements PacketAllocator {
    private static final Logger LOG = Logger.getLogger(SlabPacketAllocator.class.getName());

    private static final int STATE_FREE = 0;
    private static final int STATE_ACTIVE = 1;
    private static final int STATE_DRAINING = 2;

    /** Minimum region size in bytes for auto-clamping in the default constructor. */
    private static final long MIN_REGION_BYTES = 4096;

    private final Arena arena;
    private final MemorySegment slab;
    private final long capacity;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final Region[] regions;
    private final int regionCount;
    private final AtomicInteger activeRegionIndex = new AtomicInteger(0);

    private final AtomicLong seq = new AtomicLong(1);

    private final AtomicLong allocCount = new AtomicLong();
    private final AtomicLong releaseCount = new AtomicLong();
    private final AtomicLong failedAllocations = new AtomicLong();

    /**
     * Creates a slab allocator with the default region count (8), auto-clamped
     * so that each region is at least {@code MIN_REGION_BYTES}.
     */
    public SlabPacketAllocator(long capacityBytes) {
        this(capacityBytes, clampRegions(8, capacityBytes));
    }

    /**
     * Creates a slab allocator divided into {@code regionCount} independent regions.
     *
     * @param capacityBytes total slab size in bytes
     * @param regionCount   number of regions (must be a power of two, >= 1)
     */
    public SlabPacketAllocator(long capacityBytes, int regionCount) {
        if (regionCount <= 0 || (regionCount & (regionCount - 1)) != 0) {
            throw new IllegalArgumentException("regionCount must be a power of two, got " + regionCount);
        }
        if (capacityBytes < regionCount) {
            throw new IllegalArgumentException(
                "capacityBytes (" + capacityBytes + ") must be >= regionCount (" + regionCount + ")");
        }

        this.arena = Arena.ofShared();
        this.slab = arena.allocate(capacityBytes, 8);
        this.capacity = capacityBytes;
        this.regionCount = regionCount;

        // Align region capacity down to 8 bytes so every baseOffset is 8-aligned.
        // Critical for Panama MemorySegment slicing and SIMD mask operations.
        long regionCapacity = (capacityBytes / regionCount) & ~7L;
        this.regions = new Region[regionCount];
        for (int i = 0; i < regionCount; i++) {
            long baseOffset = i * regionCapacity;
            regions[i] = new Region(i, baseOffset, regionCapacity);
        }
        // First region starts as ACTIVE
        regions[0].state.set(STATE_ACTIVE);
    }

    /**
     * Reduces region count (power-of-two) so each region is >= MIN_REGION_BYTES.
     */
    private static int clampRegions(int requested, long capacityBytes) {
        int r = requested;
        while (r > 1 && capacityBytes / r < MIN_REGION_BYTES) {
            r >>>= 1;
        }
        return r;
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

        // Fast path: try the current ACTIVE region; slow path: rotate
        for (int attempt = 0; attempt < regionCount * 2 + 1; attempt++) {
            int idx = activeRegionIndex.get();
            Region region = regions[idx];
            int s = region.state.get();

            if (s == STATE_ACTIVE) {
                long start = region.tryBump(size);
                if (start >= 0) {
                    return createPacketRef(region, start, size, minBytes, tag);
                }
                // Region full — transition ACTIVE → DRAINING and rotate
                rotateFromFull(idx);
            } else if (s == STATE_FREE) {
                // Region drained back to FREE while it was the active index.
                // Re-activate it and publish as current active so allocations proceed.
                if (region.state.compareAndSet(STATE_FREE, STATE_ACTIVE)) {
                    activeRegionIndex.set(idx);
                    continue; // retry allocation on the now-ACTIVE region
                }
            }
            // DRAINING or CAS lost — retry with (potentially) updated index
        }

        // No FREE region found after full scan
        failedAllocations.incrementAndGet();
        return new LeaseResult.Denied(GatewayStatusCodes.INSUFFICIENT_STORAGE);
    }

    /**
     * Attempts to transition the current active region to DRAINING and find the
     * next FREE region.
     *
     * <p>The {@code ACTIVE→DRAINING} CAS is the rotation mutex: only the thread
     * that wins it performs the scan and publishes the new active index. Losers
     * return immediately and will see the updated index on their next
     * {@code activeRegionIndex.get()} in the allocate loop.
     */
    private void rotateFromFull(int currentIdx) {
        Region current = regions[currentIdx];
        // The DRAINING CAS is the rotation lock.
        // Only the winner scans and publishes the new active region.
        if (!current.state.compareAndSet(STATE_ACTIVE, STATE_DRAINING)) {
            return;
        }

        // Circular scan for next FREE region
        for (int i = 1; i <= regionCount; i++) {
            int candidateIdx = (currentIdx + i) % regionCount;
            Region candidate = regions[candidateIdx];
            if (candidate.state.compareAndSet(STATE_FREE, STATE_ACTIVE)) {
                // Sole rotator — plain set is safe
                activeRegionIndex.set(candidateIdx);
                return;
            }
        }
        // No FREE region found — all are DRAINING.
        // allocate() will loop until a region drains back to FREE.
    }

    private LeaseResult.Granted createPacketRef(Region region, long regionLocalStart,
                                                 long size, int minBytes, AllocationTag tag) {
        long globalOffset = region.baseOffset + regionLocalStart;
        MemorySegment slice = slab.asSlice(globalOffset, size);
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
        PacketRef tracked = new TrackedPacketRef(ref, region);

        allocCount.incrementAndGet();
        return new LeaseResult.Granted(tracked);
    }

    @Override
    public AllocatorStats stats() {
        long totalInUse = 0;
        for (Region r : regions) {
            // Only count cursor bytes if the region has live allocations.
            // A region with activeAllocations==0 is fully reclaimable even
            // if its cursor hasn't been reset yet (ACTIVE region that drained
            // but hasn't overflowed into the DRAINING→FREE cycle).
            if (r.activeAllocations.get() > 0) {
                totalInUse += r.cursor.get();
            }
        }
        return new AllocatorStats(
            allocCount.get(),
            releaseCount.get(),
            totalInUse,
            failedAllocations.get()
        );
    }

    private static long align8(int n) {
        return (n + 7L) & ~7L;
    }

    // ---- Region ----

    /**
     * Independent bump-pointer region within the slab.
     */
    static final class Region {
        final int index;
        final long baseOffset;
        final long regionCapacity;
        final AtomicLong cursor = new AtomicLong(0);
        final AtomicLong activeAllocations = new AtomicLong(0);
        final AtomicInteger state = new AtomicInteger(STATE_FREE);

        Region(int index, long baseOffset, long regionCapacity) {
            this.index = index;
            this.baseOffset = baseOffset;
            this.regionCapacity = regionCapacity;
        }

        /**
         * CAS-bumps the cursor by {@code size} bytes. Returns the start offset
         * within the region on success, or -1 if the region is full.
         *
         * <p>{@code activeAllocations} is incremented AFTER the cursor CAS succeeds.
         * The ACTIVE epoch-reset in {@link #releaseOne()} re-checks
         * {@code activeAllocations == 0} to guard against the window between
         * the cursor CAS and this increment. For DRAINING regions, the state CAS
         * gate in releaseOne is sufficient (no re-check needed).
         */
        long tryBump(long size) {
            while (true) {
                long start = cursor.get();
                long end = start + size;
                if (end < start || end > regionCapacity) {
                    return -1; // region full
                }
                if (cursor.compareAndSet(start, end)) {
                    activeAllocations.incrementAndGet();
                    return start;
                }
                // CAS failed — another thread bumped; retry (no counter side-effect)
            }
        }

        /**
         * Decrements active allocations. If this was the last allocation:
         * <ul>
         *   <li>DRAINING → cursor reset, then CAS to FREE (reclaim).
         *       Safe because while state is DRAINING, no thread can CAS it to
         *       ACTIVE, so the zeroed cursor is visible before anyone can tryBump.</li>
         *   <li>ACTIVE → cursor reset to 0 (epoch reset), guarded by a re-check
         *       of {@code activeAllocations == 0} to handle the window where a
         *       concurrent tryBump has CAS-bumped the cursor but not yet
         *       incremented the counter.</li>
         * </ul>
         */
        void releaseOne() {
            long remaining = activeAllocations.decrementAndGet();
            if (remaining == 0) {
                int s = state.get();
                if (s == STATE_DRAINING) {
                    cursor.set(0);
                    state.compareAndSet(STATE_DRAINING, STATE_FREE);
                } else if (s == STATE_ACTIVE) {
                    // Re-confirm still zero: a concurrent tryBump may have
                    // CAS-bumped the cursor and is about to increment the counter.
                    if (activeAllocations.get() == 0) {
                        cursor.set(0);
                    }
                }
            }
        }
    }

    // ---- TrackedPacketRef ----

    private final class TrackedPacketRef implements PacketRef {
        private final PacketRefImpl delegate;
        private final Region region;

        private TrackedPacketRef(PacketRefImpl delegate, Region region) {
            this.delegate = delegate;
            this.region = region;
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
                region.releaseOne();
            }
            return done;
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
        long totalActive = 0;
        for (Region r : regions) {
            totalActive += r.activeAllocations.get();
        }
        if (totalActive > 0) {
            LOG.warning("Closing SlabPacketAllocator with " + totalActive
                + " active allocations still in flight — potential use-after-free risk");
        }
        for (Region r : regions) {
            r.cursor.set(0);
            r.activeAllocations.set(0);
            r.state.set(STATE_FREE);
        }
        arena.close();
    }
}
