package com.acme.finops.gateway.memory;

import com.acme.finops.gateway.util.GatewayStatusCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlabPacketAllocatorTest {

    @Test
    void shouldEpochResetAfterAllReleasedAndReallocate() {
        // 1 region: classic epoch-reset behaviour
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(96, 1)) {

            PacketRef a = granted(allocator.allocate(32, tag()));
            PacketRef b = granted(allocator.allocate(32, tag()));
            PacketRef c = granted(allocator.allocate(32, tag()));

            // Region is full
            assertInstanceOf(LeaseResult.Denied.class, allocator.allocate(8, tag()));

            // Release all — region resets cursor to 0
            a.release();
            b.release();
            c.release();

            assertEquals(0L, allocator.stats().inUseBytes());

            // Can allocate again from the beginning
            PacketRef reused = granted(allocator.allocate(32, tag()));
            assertEquals(32, reused.length());
            reused.release();
        }
    }

    @Test
    void shouldNotReclaimUntilAllReleasedInSingleRegion() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(128, 1)) {

            PacketRef a = granted(allocator.allocate(32, tag()));
            PacketRef b = granted(allocator.allocate(32, tag()));
            PacketRef c = granted(allocator.allocate(32, tag()));
            PacketRef d = granted(allocator.allocate(32, tag()));

            // Release b and c — but a and d still active, no epoch reset
            b.release();
            c.release();

            // Cursor stays at 128
            assertEquals(128L, allocator.stats().inUseBytes());

            // Cannot allocate — single region full, no free-list
            assertInstanceOf(LeaseResult.Denied.class, allocator.allocate(8, tag()));

            a.release();
            d.release();

            // Now all released — region resets
            assertEquals(0L, allocator.stats().inUseBytes());
        }
    }

    @Test
    void shouldResetCursorWhenAllocatorBecomesIdle() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(64, 1)) {

            PacketRef ref = granted(allocator.allocate(40, tag()));
            ref.release();

            AllocatorStats stats = allocator.stats();
            assertEquals(0L, stats.inUseBytes());
            assertTrue(stats.releaseCount() >= 1);
        }
    }

    @Test
    void shouldDenyNonPositiveAllocations() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(64, 1)) {
            LeaseResult.Denied denied = assertInstanceOf(LeaseResult.Denied.class, allocator.allocate(0, tag()));
            assertEquals(400, denied.reasonCode());
        }
    }

    @Test
    void shouldDenyWhenCapacityExceeded() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(32, 1)) {
            LeaseResult.Denied denied = assertInstanceOf(LeaseResult.Denied.class, allocator.allocate(1024, tag()));
            assertEquals(507, denied.reasonCode());
        }
    }

    @Test
    void shouldDenyAfterClose() {
        SlabPacketAllocator allocator = new SlabPacketAllocator(64, 1);
        allocator.close();
        LeaseResult.Denied denied = assertInstanceOf(LeaseResult.Denied.class, allocator.allocate(8, tag()));
        assertEquals(503, denied.reasonCode());
    }

    @Test
    void shouldEpochResetAfterOutOfOrderRelease() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(256, 1)) {

            PacketRef a = granted(allocator.allocate(32, tag()));
            PacketRef b = granted(allocator.allocate(32, tag()));
            PacketRef c = granted(allocator.allocate(32, tag()));

            a.release();
            c.release();
            b.release(); // last one triggers epoch reset

            assertEquals(0L, allocator.stats().inUseBytes());

            // Full region available again
            PacketRef big = granted(allocator.allocate(256, tag()));
            assertEquals(256, big.length());
            big.release();
        }
    }

    @Test
    void shouldCursorNotShrinkOnPartialRelease() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(256, 1)) {

            PacketRef a = granted(allocator.allocate(32, tag()));
            PacketRef b = granted(allocator.allocate(32, tag()));
            PacketRef c = granted(allocator.allocate(32, tag()));

            // cursor is at 96 (3 x 32)
            assertEquals(96L, allocator.stats().inUseBytes());

            c.release(); // partial release — cursor stays at 96
            assertEquals(96L, allocator.stats().inUseBytes());

            a.release(); // still partial
            assertEquals(96L, allocator.stats().inUseBytes());

            b.release(); // last — epoch reset
            assertEquals(0L, allocator.stats().inUseBytes());
        }
    }

    @Test
    void shouldAlignNonPowerOfTwoAllocation() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(256, 1)) {

            // 13 bytes should be aligned up to 16 bytes internally
            PacketRef first = granted(allocator.allocate(13, tag()));
            assertEquals(13, first.length());

            PacketRef second = granted(allocator.allocate(13, tag()));
            assertEquals(13, second.length());

            AllocatorStats stats = allocator.stats();
            // Two allocations of 13 bytes each aligned to 16 bytes = 32 bytes
            assertEquals(32L, stats.inUseBytes());
            assertEquals(2L, stats.allocCount());

            first.release();
            second.release();
        }
    }

    @Test
    void shouldDenyFullSlabWithCorrectCode() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(64, 1)) {

            PacketRef full = granted(allocator.allocate(64, tag()));
            assertEquals(64, full.length());

            LeaseResult result = allocator.allocate(8, tag());
            LeaseResult.Denied denied = assertInstanceOf(LeaseResult.Denied.class, result);
            assertEquals(GatewayStatusCodes.INSUFFICIENT_STORAGE, denied.reasonCode());

            full.release();
        }
    }

    @Test
    void shouldWarnOnCloseWithActiveAllocations() {
        SlabPacketAllocator allocator = new SlabPacketAllocator(256, 1);

        PacketRef leaked = granted(allocator.allocate(64, tag()));
        assertTrue(leaked.length() > 0);

        assertDoesNotThrow(allocator::close);
    }

    @Test
    void shouldHandleConcurrentAllocateAndRelease() throws Exception {
        final int threadCount = 8;
        final int cyclesPerThread = 1000;
        final long slabSize = 1024 * 1024; // 1 MB

        try (SlabPacketAllocator allocator = new SlabPacketAllocator(slabSize)) {

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            List<Throwable> errors = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        for (int i = 0; i < cyclesPerThread; i++) {
                            LeaseResult result = allocator.allocate(64, tag());
                            if (result instanceof LeaseResult.Granted granted) {
                                granted.packetRef().release();
                            }
                        }
                    } catch (Throwable ex) {
                        synchronized (errors) {
                            errors.add(ex);
                        }
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "threads did not complete in time");
            executor.shutdown();

            assertTrue(errors.isEmpty(), "unexpected errors: " + errors);

            AllocatorStats stats = allocator.stats();
            assertEquals(stats.allocCount(), stats.releaseCount(),
                "alloc and release counts must match");
            assertEquals(0L, stats.inUseBytes(), "all allocations should have been released");
        }
    }

    // ---- Multi-region tests ----

    @Test
    void shouldRotateToNextRegionWhenCurrentFull() {
        // 2 regions of 32 bytes each = 64 total
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(64, 2)) {

            // Fill region 0 (32 bytes)
            PacketRef a = granted(allocator.allocate(32, tag()));

            // Region 0 full → rotates to region 1
            PacketRef b = granted(allocator.allocate(32, tag()));

            // Both regions filled
            assertEquals(64L, allocator.stats().inUseBytes());

            // Still holding both — all regions draining → Denied
            assertInstanceOf(LeaseResult.Denied.class, allocator.allocate(8, tag()));

            a.release();
            b.release();
        }
    }

    @Test
    void shouldReclaimSingleRegionIndependently() {
        // 2 regions of 64 bytes each = 128 total
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(128, 2)) {

            // Fill region 0 completely
            PacketRef a1 = granted(allocator.allocate(32, tag()));
            PacketRef a2 = granted(allocator.allocate(32, tag()));

            // Region 0 full → rotates to region 1
            PacketRef b1 = granted(allocator.allocate(32, tag()));

            // Release all of region 0's packets — region 0 resets independently
            a1.release();
            a2.release();

            // Region 0 is now FREE. Region 1 still has b1 active.
            // When region 1 fills, it should rotate back to region 0.
            PacketRef b2 = granted(allocator.allocate(32, tag()));

            // Region 1 is now full and draining. Allocate again → should use region 0
            PacketRef c1 = granted(allocator.allocate(32, tag()));
            assertEquals(32, c1.length());

            b1.release();
            b2.release();
            c1.release();
        }
    }

    @Test
    void shouldDenyWhenAllRegionsDraining() {
        // 2 regions of 16 bytes each = 32 total
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(32, 2)) {

            // Fill region 0
            PacketRef a = granted(allocator.allocate(16, tag()));
            // Fill region 1
            PacketRef b = granted(allocator.allocate(16, tag()));

            // Both regions draining — no FREE region available
            LeaseResult result = allocator.allocate(8, tag());
            LeaseResult.Denied denied = assertInstanceOf(LeaseResult.Denied.class, result);
            assertEquals(GatewayStatusCodes.INSUFFICIENT_STORAGE, denied.reasonCode());

            a.release();
            b.release();
        }
    }

    @Test
    void shouldHandleConcurrentRegionRotation() throws Exception {
        final int threadCount = 8;
        final int cyclesPerThread = 2000;
        // 4 regions — forces rotation under concurrent load
        final long slabSize = 1024 * 1024; // 1 MB

        try (SlabPacketAllocator allocator = new SlabPacketAllocator(slabSize, 4)) {

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
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
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "threads did not complete in time");
            executor.shutdown();

            assertTrue(errors.isEmpty(), "unexpected errors: " + errors);

            AllocatorStats stats = allocator.stats();
            assertEquals(stats.allocCount(), stats.releaseCount(),
                "alloc and release counts must match");
            assertEquals(0L, stats.inUseBytes(), "all allocations should have been released");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16})
    void shouldAcceptPowerOfTwoRegionCounts(int regionCount) {
        assertDoesNotThrow(() -> {
            try (SlabPacketAllocator a = new SlabPacketAllocator(1024, regionCount)) {
                // verify construction succeeds
            }
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 3, 5, 6, 7})
    void shouldRejectNonPowerOfTwoRegionCount(int regionCount) {
        assertThrows(IllegalArgumentException.class,
            () -> new SlabPacketAllocator(1024, regionCount));
    }

    @Test
    void shouldDefaultToEightRegions() {
        // The 1-arg constructor should work with default 8 regions
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(1024)) {
            PacketRef ref = granted(allocator.allocate(64, tag()));
            assertEquals(64, ref.length());
            ref.release();

            AllocatorStats stats = allocator.stats();
            assertEquals(1L, stats.allocCount());
            assertEquals(1L, stats.releaseCount());
            assertEquals(0L, stats.inUseBytes());
        }
    }

    private static PacketRef granted(LeaseResult result) {
        LeaseResult.Granted granted = assertInstanceOf(LeaseResult.Granted.class, result);
        return granted.packetRef();
    }

    private static AllocationTag tag() {
        return new AllocationTag("test", "slab", 1);
    }
}
