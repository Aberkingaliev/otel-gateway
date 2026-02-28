package com.acme.finops.gateway.memory;

import java.util.logging.Logger;

/**
 * Sharded slab allocator that distributes allocations across N independent
 * {@link SlabPacketAllocator} instances. Shard selection uses a bitmask on
 * the calling thread's ID, giving lock-free, deterministic thread affinity.
 *
 * <p>Each shard owns {@code totalCapacityBytes / shardCount} bytes of off-heap
 * memory. There is no cross-shard fallback: if the selected shard is full the
 * allocation is denied immediately, providing clear backpressure.
 */
public final class StripedPacketAllocator implements PacketAllocator {
    private static final Logger LOG = Logger.getLogger(StripedPacketAllocator.class.getName());

    private final SlabPacketAllocator[] shards;
    private final int mask;

    public StripedPacketAllocator(long totalCapacityBytes, int shardCount) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive, got " + shardCount);
        }
        if ((shardCount & (shardCount - 1)) != 0) {
            throw new IllegalArgumentException("shardCount must be a power of two, got " + shardCount);
        }
        if (totalCapacityBytes < shardCount) {
            throw new IllegalArgumentException(
                "totalCapacityBytes (" + totalCapacityBytes + ") must be >= shardCount (" + shardCount + ")");
        }

        this.mask = shardCount - 1;
        this.shards = new SlabPacketAllocator[shardCount];
        long perShard = totalCapacityBytes / shardCount;
        for (int i = 0; i < shardCount; i++) {
            shards[i] = new SlabPacketAllocator(perShard);
        }
    }

    @Override
    public LeaseResult allocate(int minBytes, AllocationTag tag) {
        int shard = (int) (Thread.currentThread().threadId() & mask);
        return shards[shard].allocate(minBytes, tag);
    }

    @Override
    public AllocatorStats stats() {
        long totalAlloc = 0;
        long totalRelease = 0;
        long totalInUse = 0;
        long totalFailed = 0;
        for (SlabPacketAllocator shard : shards) {
            AllocatorStats s = shard.stats();
            totalAlloc += s.allocCount();
            totalRelease += s.releaseCount();
            totalInUse += s.inUseBytes();
            totalFailed += s.failedAllocations();
        }
        return new AllocatorStats(totalAlloc, totalRelease, totalInUse, totalFailed);
    }

    @Override
    public void close() {
        for (int i = 0; i < shards.length; i++) {
            try {
                shards[i].close();
            } catch (RuntimeException e) {
                LOG.warning("Error closing shard " + i + ": " + e.getMessage());
            }
        }
    }
}
