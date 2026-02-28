package com.acme.finops.gateway.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicHotPathMetricsTest {

    @Test
    void p99ShouldReturnZeroWhenNoSamples() {
        AtomicHotPathMetrics metrics = new AtomicHotPathMetrics();
        assertEquals(0L, metrics.p99LatencyNanos());
    }

    @Test
    void p99ShouldReturnSingleSample() {
        AtomicHotPathMetrics metrics = new AtomicHotPathMetrics();
        metrics.observeEndToEndNanos(42_000L);
        assertEquals(42_000L, metrics.p99LatencyNanos());
    }

    @Test
    void p99ShouldApproximateCorrectPercentile() {
        AtomicHotPathMetrics metrics = new AtomicHotPathMetrics();
        for (int i = 1; i <= 100; i++) {
            metrics.observeEndToEndNanos(i * 1000L);
        }
        long p99 = metrics.p99LatencyNanos();
        // p99 of 1..100 should be >= 99_000 (99th value)
        assertTrue(p99 >= 99_000L, "Expected p99 >= 99000 but got " + p99);
    }

    @Test
    void p99ShouldHandleRingBufferWraparound() {
        AtomicHotPathMetrics metrics = new AtomicHotPathMetrics();
        // Fill beyond ring buffer size (4096)
        for (int i = 0; i < 5000; i++) {
            metrics.observeEndToEndNanos(1000L);
        }
        // Add one high outlier
        metrics.observeEndToEndNanos(999_000L);
        long p99 = metrics.p99LatencyNanos();
        // Most values are 1000, so p99 should be reasonable
        assertTrue(p99 >= 1000L, "Expected p99 >= 1000 but got " + p99);
    }

    @Test
    void snapshotShouldIncludeP99() {
        AtomicHotPathMetrics metrics = new AtomicHotPathMetrics();
        for (int i = 1; i <= 200; i++) {
            metrics.observeEndToEndNanos(i * 100L);
        }
        AtomicHotPathMetrics.Snapshot snapshot = metrics.snapshot();
        assertTrue(snapshot.endToEndP99Nanos() > 0, "Snapshot p99 should be positive");
        // p99 of 100..20000 should be around 19800+
        assertTrue(snapshot.endToEndP99Nanos() >= 19_800L,
            "Expected snapshot p99 >= 19800 but got " + snapshot.endToEndP99Nanos());
    }

    @Test
    void negativeSamplesShouldBeIgnored() {
        AtomicHotPathMetrics metrics = new AtomicHotPathMetrics();
        metrics.observeEndToEndNanos(-1L);
        assertEquals(0L, metrics.p99LatencyNanos());
    }
}
