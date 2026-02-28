package com.acme.finops.gateway.queue;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded multi-producer sharded queue with single-consumer semantics per shard.
 *
 * <p>This is a sharded bounded queue built on lock-free CLQ shards, not a strict ring-buffer.
 * Capacity is enforced per shard and tracked with atomic counters for low-overhead snapshots.</p>
 */
public final class StripedMpscRing<E> implements BoundedRing<E> {
    private final int shardCount;
    private final int perShardCapacity;
    private final ConcurrentLinkedQueue<E>[] shards;
    private final AtomicIntegerArray depths;
    private final LongAdder totalDepth = new LongAdder();
    private final AtomicLong sequence = new AtomicLong(1);
    private final AtomicLong headSeq = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @SuppressWarnings("unchecked")
    public StripedMpscRing(int totalCapacity, int shardCount) {
        int normalizedShards = Math.max(1, shardCount);
        int normalizedCapacity = Math.max(normalizedShards, totalCapacity);
        this.shardCount = normalizedShards;
        this.perShardCapacity = Math.max(1, normalizedCapacity / normalizedShards);
        this.shards = (ConcurrentLinkedQueue<E>[]) new ConcurrentLinkedQueue[normalizedShards];
        this.depths = new AtomicIntegerArray(normalizedShards);
        for (int i = 0; i < normalizedShards; i++) {
            this.shards[i] = new ConcurrentLinkedQueue<>();
        }
    }

    @Override
    public int capacity() {
        return perShardCapacity * shardCount;
    }

    @Override
    public int sizeApprox() {
        return (int) totalDepth.sum();
    }

    @Override
    public OfferResult offer(E e) {
        return offer(selectShard(), e);
    }

    public OfferResult offer(int shardId, E e) {
        Objects.requireNonNull(e, "e");
        if (closed.get()) {
            return new OfferResult.Closed();
        }
        int shard = normalizeShard(shardId);
        if (!tryIncrementDepth(shard)) {
            return new OfferResult.Full(sizeApprox(), capacity());
        }
        shards[shard].offer(e);
        long seq = sequence.getAndIncrement();
        return new OfferResult.Ok(seq);
    }

    @Override
    public E poll() {
        for (int i = 0; i < shardCount; i++) {
            E e = pollShard(i);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    public E pollShard(int shardId) {
        int shard = normalizeShard(shardId);
        E value = shards[shard].poll();
        if (value == null) {
            return null;
        }
        depths.decrementAndGet(shard);
        totalDepth.add(-1);
        headSeq.incrementAndGet();
        return value;
    }

    public QueueSnapshot snapshot() {
        return new QueueSnapshot(
            sizeApprox(),
            capacity(),
            headSeq.get(),
            Math.max(0, sequence.get() - 1),
            System.nanoTime()
        );
    }

    public int shardCount() {
        return shardCount;
    }

    public int shardDepth(int shardId) {
        return depths.get(normalizeShard(shardId));
    }

    @Override
    public void close() {
        closed.set(true);
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Returns true when queue has no outstanding items according to aggregate counters.
     */
    public boolean isDrained() {
        return sizeApprox() == 0;
    }

    /**
     * Validates internal depth counters against per-shard totals.
     * Intended for shutdown diagnostics, not hot path.
     */
    public boolean validateInvariants() {
        int sum = 0;
        for (int i = 0; i < shardCount; i++) {
            int depth = depths.get(i);
            if (depth < 0) {
                return false;
            }
            sum += depth;
        }
        return sum == (int) totalDepth.sum() && sum >= 0;
    }

    private int selectShard() {
        return ThreadLocalRandom.current().nextInt(shardCount);
    }

    private int normalizeShard(int shardId) {
        int shard = shardId % shardCount;
        return shard < 0 ? shard + shardCount : shard;
    }

    private boolean tryIncrementDepth(int shard) {
        while (true) {
            int current = depths.get(shard);
            if (current >= perShardCapacity) {
                return false;
            }
            if (depths.compareAndSet(shard, current, current + 1)) {
                totalDepth.add(1);
                return true;
            }
        }
    }
}
