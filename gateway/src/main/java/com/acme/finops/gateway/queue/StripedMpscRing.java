package com.acme.finops.gateway.queue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free bounded multi-producer single-consumer sharded ring buffer using
 * Lamport-style sequence stamps for coordination.
 *
 * <p>Each shard is a pre-allocated array with sequence stamps.
 * Producers CAS on a shared {@code producerIndex}; the single consumer
 * advances a {@code volatile long} consumer index. Acquire/release fences
 * on the sequence array ensure correct visibility without full barriers.</p>
 *
 * <p>Per-shard capacity is rounded up to the next power-of-two for fast
 * index masking.</p>
 *
 * <p><b>Contract:</b> {@link #pollShard(int)} must be called by at most one
 * thread per shard at any time. Concurrent calls for the same shard corrupt
 * the queue. {@link #poll()} polls all shards and must only be called by a
 * single consumer thread.</p>
 */
public final class StripedMpscRing<E> implements BoundedRing<E> {

    static final int MAX_POW2 = 1 << 30;

    private static final VarHandle SEQ_HANDLE =
            MethodHandles.arrayElementVarHandle(long[].class);

    private final Shard<E>[] shards;
    private final int shardCount;
    private final int perShardCapacity;
    private final AtomicLong globalSequence = new AtomicLong(1);
    private final AtomicLong globalHeadSeq = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @SuppressWarnings("unchecked")
    public StripedMpscRing(int totalCapacity, int shardCount) {
        int normalizedShards = Math.max(1, shardCount);
        int normalizedCapacity = Math.max(normalizedShards, totalCapacity);
        int rawPerShard = Math.max(1, normalizedCapacity / normalizedShards);
        int pow2PerShard = roundUpPow2(rawPerShard);

        this.shardCount = normalizedShards;
        this.perShardCapacity = pow2PerShard;
        this.shards = new Shard[normalizedShards];
        for (int i = 0; i < normalizedShards; i++) {
            this.shards[i] = new Shard<>(pow2PerShard);
        }
    }

    @Override
    public int capacity() {
        return perShardCapacity * shardCount;
    }

    @Override
    public int sizeApprox() {
        long sum = 0;
        for (int i = 0; i < shardCount; i++) {
            Shard<E> s = shards[i];
            long produced = s.producerIndex.get();
            long consumed = s.consumerIndex;
            sum += Math.max(0, produced - consumed);
        }
        return (int) Math.min(sum, Integer.MAX_VALUE);
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
        int idx = normalizeShard(shardId);
        Shard<E> shard = shards[idx];
        int mask = shard.mask;
        long[] sequences = shard.sequences;

        for (;;) {
            long pos = shard.producerIndex.get();
            int slot = (int) (pos & mask);
            long seq = (long) SEQ_HANDLE.getAcquire(sequences, slot);

            if (seq == pos) {
                // Slot is free — try to claim it
                if (shard.producerIndex.compareAndSet(pos, pos + 1)) {
                    shard.buffer[slot] = e;
                    SEQ_HANDLE.setRelease(sequences, slot, pos + 1);
                    long globalSeq = globalSequence.getAndIncrement();
                    return new OfferResult.Ok(globalSeq);
                }
                // CAS failed, another producer won — retry immediately
            } else if (seq < pos) {
                // Slot appears full. Recheck after a brief spin to avoid
                // false-full when a producer has claimed the slot via CAS
                // but hasn't committed its sequence stamp yet.
                Thread.onSpinWait();
                long seqRecheck = (long) SEQ_HANDLE.getAcquire(sequences, slot);
                if (seqRecheck < pos) {
                    return new OfferResult.Full(sizeApprox(), capacity());
                }
                // Producer committed during spin — retry
            }
            // seq > pos means another producer took this slot, retry with new pos
        }
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

    /**
     * Polls one element from the specified shard.
     * <b>Contract: at most one thread may call this method for a given shardId
     * at any time. Concurrent calls for the same shard corrupt the queue.</b>
     */
    @SuppressWarnings("unchecked")
    public E pollShard(int shardId) {
        int idx = normalizeShard(shardId);
        Shard<E> shard = shards[idx];
        long pos = shard.consumerIndex;
        int slot = (int) (pos & shard.mask);
        long seq = (long) SEQ_HANDLE.getAcquire(shard.sequences, slot);

        if (seq != pos + 1) {
            // Slot not yet filled by producer
            return null;
        }

        E value = (E) shard.buffer[slot];
        shard.buffer[slot] = null;
        SEQ_HANDLE.setRelease(shard.sequences, slot, pos + shard.capacity);
        // volatile write — must remain after setRelease to preserve ordering;
        // producers read consumerIndex in sizeApprox()/shardDepth()
        shard.consumerIndex = pos + 1;
        globalHeadSeq.incrementAndGet();
        return value;
    }

    public QueueSnapshot snapshot() {
        return new QueueSnapshot(
            sizeApprox(),
            capacity(),
            globalHeadSeq.get(),
            Math.max(0, globalSequence.get() - 1),
            System.nanoTime()
        );
    }

    public int shardCount() {
        return shardCount;
    }

    public int shardDepth(int shardId) {
        int idx = normalizeShard(shardId);
        Shard<E> shard = shards[idx];
        long produced = shard.producerIndex.get();
        long consumed = shard.consumerIndex;
        return (int) Math.max(0, produced - consumed);
    }

    @Override
    public void close() {
        closed.set(true);
    }

    public boolean isClosed() {
        return closed.get();
    }

    public boolean isDrained() {
        return sizeApprox() == 0;
    }

    /**
     * Validates that per-shard depth (producerIndex - consumerIndex) is non-negative
     * and within bounds. Intended for shutdown diagnostics, not hot path.
     */
    public boolean validateInvariants() {
        for (int i = 0; i < shardCount; i++) {
            Shard<E> shard = shards[i];
            long depth = shard.producerIndex.get() - shard.consumerIndex;
            if (depth < 0 || depth > shard.capacity) {
                return false;
            }
        }
        return true;
    }

    private int selectShard() {
        return ThreadLocalRandom.current().nextInt(shardCount);
    }

    private int normalizeShard(int shardId) {
        int shard = shardId % shardCount;
        return shard < 0 ? shard + shardCount : shard;
    }

    static int roundUpPow2(int value) {
        if (value <= 1) {
            return 1;
        }
        if (value > MAX_POW2) {
            throw new IllegalArgumentException(
                "Per-shard capacity " + value + " exceeds maximum " + MAX_POW2);
        }
        return Integer.highestOneBit(value - 1) << 1;
    }

    /**
     * A single shard of the ring buffer with cache-line padding between
     * producer and consumer indices to prevent false sharing.
     */
    static final class Shard<E> {
        final int capacity;
        final int mask;
        final Object[] buffer;
        final long[] sequences;
        final AtomicLong producerIndex;

        // ---- cache-line padding between producerIndex and consumerIndex ----
        @SuppressWarnings("unused")
        long p1, p2, p3, p4, p5, p6, p7, p8;

        // volatile is required: producers read this field in sizeApprox()/shardDepth()
        // to estimate queue depth. The volatile write in pollShard() must remain after
        // the setRelease on sequences[] to preserve ordering.
        volatile long consumerIndex;

        Shard(int capacity) {
            this.capacity = capacity;
            this.mask = capacity - 1;
            this.buffer = new Object[capacity];
            this.sequences = new long[capacity];
            for (int i = 0; i < capacity; i++) {
                this.sequences[i] = i;
            }
            this.producerIndex = new AtomicLong(0);
            this.consumerIndex = 0;
        }
    }
}
