package com.acme.finops.gateway.memory;

import com.acme.finops.gateway.util.GatewayStatusCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripedPacketAllocatorTest {

    @Test
    void shouldAllocateAndReleaseSuccessfully() {
        try (StripedPacketAllocator allocator = new StripedPacketAllocator(1024, 4)) {
            LeaseResult result = allocator.allocate(64, tag());
            PacketRef ref = granted(result);
            assertEquals(64, ref.length());
            ref.release();

            AllocatorStats stats = allocator.stats();
            assertEquals(1L, stats.allocCount());
            assertEquals(1L, stats.releaseCount());
        }
    }

    @Test
    void shouldAggregateStatsAcrossShards() throws Exception {
        try (StripedPacketAllocator allocator = new StripedPacketAllocator(4096, 4)) {
            int threadCount = 4;
            int allocsPerThread = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        for (int i = 0; i < allocsPerThread; i++) {
                            LeaseResult result = allocator.allocate(8, tag());
                            if (result instanceof LeaseResult.Granted g) {
                                g.packetRef().release();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            startGate.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            AllocatorStats stats = allocator.stats();
            assertEquals(stats.allocCount(), stats.releaseCount());
            assertTrue(stats.allocCount() > 0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 3, 5, 6, 7})
    void shouldRejectNonPowerOfTwoShardCount(int shardCount) {
        assertThrows(IllegalArgumentException.class,
            () -> new StripedPacketAllocator(1024, shardCount));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16})
    void shouldAcceptPowerOfTwoShardCounts(int shardCount) {
        assertDoesNotThrow(() -> {
            try (StripedPacketAllocator a = new StripedPacketAllocator(1024, shardCount)) {
                // just verify construction succeeds
            }
        });
    }

    @Test
    void shouldCloseAllShards() {
        StripedPacketAllocator allocator = new StripedPacketAllocator(1024, 4);
        allocator.close();

        LeaseResult result = allocator.allocate(8, tag());
        LeaseResult.Denied denied = assertInstanceOf(LeaseResult.Denied.class, result);
        assertEquals(GatewayStatusCodes.SERVICE_UNAVAILABLE, denied.reasonCode());
    }

    @Test
    void shouldDenyWhenShardFull() {
        // 4 shards of 64 bytes each = 256 total; each shard has only 64 bytes
        try (StripedPacketAllocator allocator = new StripedPacketAllocator(256, 4)) {
            // Current thread maps to one shard (64 bytes). Fill it up.
            PacketRef a = granted(allocator.allocate(32, tag()));
            PacketRef b = granted(allocator.allocate(32, tag()));

            // Shard is now full — should get Denied
            LeaseResult result = allocator.allocate(8, tag());
            assertInstanceOf(LeaseResult.Denied.class, result);

            a.release();
            b.release();
        }
    }

    @Test
    void shouldMaintainThreadShardAffinity() {
        // With shardCount=1, all threads map to shard 0, so packet IDs are monotonic per-thread.
        // With multiple shards, same thread always hits same shard → monotonic IDs from that shard's seq.
        try (StripedPacketAllocator allocator = new StripedPacketAllocator(4096, 4)) {
            long prevId = -1;
            for (int i = 0; i < 20; i++) {
                PacketRef ref = granted(allocator.allocate(8, tag()));
                long currentId = ref.packetId();
                assertTrue(currentId > prevId,
                    "packet IDs should be monotonically increasing for same thread/shard");
                prevId = currentId;
                ref.release();
            }
        }
    }

    @Test
    void shouldHandleConcurrentStressWithoutLeaks() throws Exception {
        int threadCount = 8;
        int cyclesPerThread = 2000;
        long slabSize = 8L * 1024 * 1024; // 8 MB total

        try (StripedPacketAllocator allocator = new StripedPacketAllocator(slabSize, 8)) {
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            List<Throwable> errors = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        for (int i = 0; i < cyclesPerThread; i++) {
                            LeaseResult result = allocator.allocate(64, tag());
                            if (result instanceof LeaseResult.Granted g) {
                                g.packetRef().release();
                            }
                        }
                    } catch (Throwable ex) {
                        synchronized (errors) {
                            errors.add(ex);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }

            startGate.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not complete in time");
            executor.shutdown();

            assertTrue(errors.isEmpty(), "unexpected errors: " + errors);

            AllocatorStats stats = allocator.stats();
            assertEquals(stats.allocCount(), stats.releaseCount(),
                "alloc and release counts must match");
            assertEquals(0L, stats.inUseBytes(), "all allocations should have been released");
        }
    }

    @Test
    void shouldHandleDegenerateSingleShard() {
        try (StripedPacketAllocator allocator = new StripedPacketAllocator(512, 1)) {
            PacketRef ref = granted(allocator.allocate(128, tag()));
            assertEquals(128, ref.length());
            ref.release();

            AllocatorStats stats = allocator.stats();
            assertEquals(1L, stats.allocCount());
            assertEquals(1L, stats.releaseCount());
            assertEquals(0L, stats.inUseBytes());
        }
    }

    @Test
    void shouldRejectCapacityLessThanShardCount() {
        assertThrows(IllegalArgumentException.class,
            () -> new StripedPacketAllocator(3, 4));
    }

    private static PacketRef granted(LeaseResult result) {
        LeaseResult.Granted granted = assertInstanceOf(LeaseResult.Granted.class, result);
        return granted.packetRef();
    }

    private static AllocationTag tag() {
        return new AllocationTag("test", "striped", 1);
    }
}
