package com.acme.finops.gateway.telemetry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

public final class AtomicHotPathMetrics implements HotPathMetrics {
    private final LongAdder packetsIn = new LongAdder();
    private final LongAdder packetsOut = new LongAdder();
    private final LongAdder parseNanos = new LongAdder();
    private final LongAdder parseSamples = new LongAdder();
    private final LongAdder e2eNanos = new LongAdder();
    private final LongAdder e2eSamples = new LongAdder();
    private final AtomicInteger queueDepth = new AtomicInteger();
    private final ConcurrentHashMap<Integer, LongAdder> droppedByReason = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, LongAdder> parseErrorsByCode = new ConcurrentHashMap<>();

    private static final int LATENCY_RING_SIZE = 4096;
    private static final int LATENCY_RING_MASK = LATENCY_RING_SIZE - 1;
    private final AtomicLongArray latencyRing = new AtomicLongArray(LATENCY_RING_SIZE);
    private final AtomicLong latencyRingPos = new AtomicLong();

    @Override
    public void incPacketsIn(long n) {
        packetsIn.add(Math.max(0L, n));
    }

    @Override
    public void incPacketsOut(long n) {
        packetsOut.add(Math.max(0L, n));
    }

    @Override
    public void incDropped(long n, int reasonCode) {
        if (n <= 0) return;
        droppedByReason.computeIfAbsent(reasonCode, ignored -> new LongAdder()).add(n);
    }

    @Override
    public void incParseErrors(long n, int errorCode) {
        if (n <= 0) return;
        parseErrorsByCode.computeIfAbsent(errorCode, ignored -> new LongAdder()).add(n);
    }

    @Override
    public void observeParseNanos(long nanos) {
        if (nanos < 0) return;
        parseNanos.add(nanos);
        parseSamples.increment();
    }

    @Override
    public void observeEndToEndNanos(long nanos) {
        if (nanos < 0) return;
        e2eNanos.add(nanos);
        e2eSamples.increment();
        latencyRing.set((int) (latencyRingPos.getAndIncrement() & LATENCY_RING_MASK), nanos);
    }

    public long p99LatencyNanos() {
        long pos = latencyRingPos.get();
        int count = (int) Math.min(pos, LATENCY_RING_SIZE);
        if (count == 0) return 0;
        long[] samples = new long[count];
        int start = (int) ((pos - count) & LATENCY_RING_MASK);
        for (int i = 0; i < count; i++) {
            samples[i] = latencyRing.get((start + i) & LATENCY_RING_MASK);
        }
        java.util.Arrays.sort(samples);
        int idx = Math.min((int) (count * 0.99), count - 1);
        return samples[idx];
    }

    @Override
    public void setQueueDepth(int depth) {
        queueDepth.set(Math.max(0, depth));
    }

    public Snapshot snapshot() {
        return new Snapshot(
            packetsIn.sum(),
            packetsOut.sum(),
            queueDepth.get(),
            parseNanos.sum(),
            parseSamples.sum(),
            e2eNanos.sum(),
            e2eSamples.sum(),
            p99LatencyNanos(),
            mapToLongs(droppedByReason),
            mapToLongs(parseErrorsByCode)
        );
    }

    private static java.util.Map<Integer, Long> mapToLongs(ConcurrentHashMap<Integer, LongAdder> src) {
        java.util.Map<Integer, Long> out = new java.util.HashMap<>();
        src.forEach((k, v) -> out.put(k, v.sum()));
        return java.util.Collections.unmodifiableMap(out);
    }

    public record Snapshot(long packetsIn,
                           long packetsOut,
                           int queueDepth,
                           long parseNanosTotal,
                           long parseSamples,
                           long endToEndNanosTotal,
                           long endToEndSamples,
                           long endToEndP99Nanos,
                           java.util.Map<Integer, Long> droppedByReason,
                           java.util.Map<Integer, Long> parseErrorsByCode) {}
}
