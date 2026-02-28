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
    void shouldReuseFreedGapWhenCursorReachedCapacity() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(96)) {

            PacketRef a = granted(allocator.allocate(32, tag()));
            PacketRef b = granted(allocator.allocate(32, tag()));
            PacketRef c = granted(allocator.allocate(32, tag()));

            a.release(); // creates a gap at the beginning while other allocations are still active

            PacketRef reused = granted(allocator.allocate(32, tag()));
            assertEquals(32, reused.length());

            reused.release();
            b.release();
            c.release();
        }
    }

    @Test
    void shouldCoalesceAdjacentFreedBlocks() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(128)) {

            PacketRef a = granted(allocator.allocate(32, tag()));
            PacketRef b = granted(allocator.allocate(32, tag()));
            PacketRef c = granted(allocator.allocate(32, tag()));
            PacketRef d = granted(allocator.allocate(32, tag()));

            b.release();
            c.release(); // adjacent blocks must coalesce into 64 bytes

            PacketRef merged = granted(allocator.allocate(64, tag()));
            assertEquals(64, merged.length());

            merged.release();
            a.release();
            d.release();
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
    void shouldTripleCoalesceWhenMiddleBlockFreedLast() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(256)) {

            PacketRef a = granted(allocator.allocate(32, tag()));
            PacketRef b = granted(allocator.allocate(32, tag()));
            PacketRef c = granted(allocator.allocate(32, tag()));

            a.release(); // free left
            c.release(); // free right
            b.release(); // free middle — should coalesce all 3 into one 96-byte block

            // All 3 blocks (3 x 32 = 96 bytes) should have merged into one contiguous free block
            PacketRef merged = granted(allocator.allocate(96, tag()));
            assertEquals(96, merged.length());

            merged.release();
        }
    }

    @Test
    void shouldPartiallyTrimCursorFromTail() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(256)) {

            PacketRef a = granted(allocator.allocate(32, tag()));
            PacketRef b = granted(allocator.allocate(32, tag()));
            PacketRef c = granted(allocator.allocate(32, tag()));

            // cursor is at 96 (3 x 32)
            AllocatorStats beforeFree = allocator.stats();
            assertEquals(96L, beforeFree.inUseBytes());

            c.release(); // tail block freed — cursor should shrink from 96 to 64

            AllocatorStats afterCFree = allocator.stats();
            assertEquals(64L, afterCFree.inUseBytes());

            a.release(); // gap at start — cursor should NOT shrink (B still blocks it)

            AllocatorStats afterAFree = allocator.stats();
            // Cursor stays at 64 because B (offset 32..64) is still active
            assertEquals(64L, afterAFree.inUseBytes());

            b.release(); // last block freed — full rewind
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
    void shouldExactFitFromFreeList() {
        try (SlabPacketAllocator allocator = new SlabPacketAllocator(256)) {

            PacketRef a = granted(allocator.allocate(32, tag()));
            PacketRef b = granted(allocator.allocate(32, tag()));

            a.release(); // creates a 32-byte free block at offset 0

            // Allocate exactly 32 bytes — should reuse A's slot (best-fit exact match)
            PacketRef reused = granted(allocator.allocate(32, tag()));
            assertEquals(32, reused.length());

            AllocatorStats stats = allocator.stats();
            // cursor should still be at 64 (A's slot was reused from the free list, not the cursor)
            assertEquals(64L, stats.inUseBytes());
            assertEquals(3L, stats.allocCount());
            assertEquals(1L, stats.releaseCount());

            reused.release();
            b.release();
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
            // activeAllocations is not directly exposed via stats, but inUseBytes == 0 confirms
            // all allocations were released (cursor rewind happens when activeAllocations reaches 0)
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
