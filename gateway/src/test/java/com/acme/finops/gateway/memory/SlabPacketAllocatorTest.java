package com.acme.finops.gateway.memory;

import com.acme.finops.gateway.util.GatewayStatusCodes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlabPacketAllocatorTest {

    @Test
    void shouldEpochResetAfterAllReleasedAndReallocate() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(96)) {

            PacketRef a = granted(allocator.allocate(32, tag()));
            PacketRef b = granted(allocator.allocate(32, tag()));
            PacketRef c = granted(allocator.allocate(32, tag()));

            // Slab is full
            assertInstanceOf(LeaseResult.Denied.class, allocator.allocate(8, tag()));

            // Release all — epoch reset, cursor back to 0
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
    void shouldNotReclaimUntilAllReleased() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(128)) {

            PacketRef a = granted(allocator.allocate(32, tag()));
            PacketRef b = granted(allocator.allocate(32, tag()));
            PacketRef c = granted(allocator.allocate(32, tag()));
            PacketRef d = granted(allocator.allocate(32, tag()));

            // Release b and c — but a and d still active, no epoch reset
            b.release();
            c.release();

            // Cursor stays at 128, no reclaim yet
            assertEquals(128L, allocator.stats().inUseBytes());

            // Cannot allocate — slab full, no free-list in bump-pointer
            assertInstanceOf(LeaseResult.Denied.class, allocator.allocate(8, tag()));

            a.release();
            d.release();

            // Now all released — epoch reset
            assertEquals(0L, allocator.stats().inUseBytes());
        }
    }

    @Test
    void shouldResetCursorWhenAllocatorBecomesIdle() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(64)) {

            PacketRef ref = granted(allocator.allocate(40, tag()));
            ref.release();

            AllocatorStats stats = allocator.stats();
            assertEquals(0L, stats.inUseBytes());
            assertTrue(stats.releaseCount() >= 1);
        }
    }

    @Test
    void shouldDenyNonPositiveAllocations() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(64)) {
            LeaseResult.Denied denied = assertInstanceOf(LeaseResult.Denied.class, allocator.allocate(0, tag()));
            assertEquals(400, denied.reasonCode());
        }
    }

    @Test
    void shouldDenyWhenCapacityExceeded() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(32)) {
            LeaseResult.Denied denied = assertInstanceOf(LeaseResult.Denied.class, allocator.allocate(1024, tag()));
            assertEquals(507, denied.reasonCode());
        }
    }

    @Test
    void shouldDenyAfterClose() {
        SlabPacketAllocator allocator = new SlabPacketAllocator(64);
        allocator.close();
        LeaseResult.Denied denied = assertInstanceOf(LeaseResult.Denied.class, allocator.allocate(8, tag()));
        assertEquals(503, denied.reasonCode());
    }

    @Test
    void shouldEpochResetAfterOutOfOrderRelease() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(256)) {

            PacketRef a = granted(allocator.allocate(32, tag()));
            PacketRef b = granted(allocator.allocate(32, tag()));
            PacketRef c = granted(allocator.allocate(32, tag()));

            a.release();
            c.release();
            b.release(); // last one triggers epoch reset

            assertEquals(0L, allocator.stats().inUseBytes());

            // Full slab available again
            PacketRef big = granted(allocator.allocate(256, tag()));
            assertEquals(256, big.length());
            big.release();
        }
    }

    @Test
    void shouldCursorNotShrinkOnPartialRelease() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(256)) {

            PacketRef a = granted(allocator.allocate(32, tag()));
            PacketRef b = granted(allocator.allocate(32, tag()));
            PacketRef c = granted(allocator.allocate(32, tag()));

            // cursor is at 96 (3 x 32)
            assertEquals(96L, allocator.stats().inUseBytes());

            c.release(); // partial release — cursor stays at 96 (bump-pointer, no trim)
            assertEquals(96L, allocator.stats().inUseBytes());

            a.release(); // still partial
            assertEquals(96L, allocator.stats().inUseBytes());

            b.release(); // last — epoch reset
            assertEquals(0L, allocator.stats().inUseBytes());
        }
    }

    @Test
    void shouldAlignNonPowerOfTwoAllocation() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(256)) {

            // 13 bytes should be aligned up to 16 bytes internally
            PacketRef first = granted(allocator.allocate(13, tag()));
            assertEquals(13, first.length()); // reported length is the requested size

            PacketRef second = granted(allocator.allocate(13, tag()));
            assertEquals(13, second.length());

            AllocatorStats stats = allocator.stats();
            // Two allocations of 13 bytes each aligned to 16 bytes = 32 bytes used from the cursor
            assertEquals(32L, stats.inUseBytes());
            assertEquals(2L, stats.allocCount());

            first.release();
            second.release();
        }
    }

    @Test
    void shouldDenyFullSlabWithCorrectCode() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(64)) {

            // Fill the entire slab
            PacketRef full = granted(allocator.allocate(64, tag()));
            assertEquals(64, full.length());

            // Attempt to allocate 8 more bytes — slab is full
            LeaseResult result = allocator.allocate(8, tag());
            LeaseResult.Denied denied = assertInstanceOf(LeaseResult.Denied.class, result);
            assertEquals(GatewayStatusCodes.INSUFFICIENT_STORAGE, denied.reasonCode());

            full.release();
        }
    }

    @Test
    void shouldWarnOnCloseWithActiveAllocations() {
        SlabPacketAllocator allocator = new SlabPacketAllocator(256);

        // Allocate a block and intentionally do NOT release it
        PacketRef leaked = granted(allocator.allocate(64, tag()));
        assertTrue(leaked.length() > 0);

        // Closing with active allocations should not throw
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
                            // Denied results are acceptable under contention — just skip
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

            startGate.countDown(); // release all threads simultaneously
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "threads did not complete in time");
            executor.shutdown();

            assertTrue(errors.isEmpty(), "unexpected errors: " + errors);

            AllocatorStats stats = allocator.stats();
            assertEquals(stats.allocCount(), stats.releaseCount(),
                "alloc and release counts must match");
            assertEquals(0L, stats.inUseBytes(), "all allocations should have been released");
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
