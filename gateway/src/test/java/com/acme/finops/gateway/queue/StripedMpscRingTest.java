package com.acme.finops.gateway.queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripedMpscRingTest {

    @Test
    void shouldOfferPollAndRespectShardCapacity() {
        StripedMpscRing<String> ring = new StripedMpscRing<>(4, 2);
        try {
            assertInstanceOf(OfferResult.Ok.class, ring.offer(0, "a0"));
            assertInstanceOf(OfferResult.Ok.class, ring.offer(0, "a1"));
            assertInstanceOf(OfferResult.Full.class, ring.offer(0, "a2"));

            assertInstanceOf(OfferResult.Ok.class, ring.offer(1, "b0"));
            assertInstanceOf(OfferResult.Ok.class, ring.offer(1, "b1"));
            assertEquals(4, ring.sizeApprox());

            assertEquals("a0", ring.pollShard(0));
            assertEquals("a1", ring.pollShard(0));
            assertNull(ring.pollShard(0));
            assertEquals(2, ring.sizeApprox());

            QueueSnapshot snapshot = ring.snapshot();
            assertEquals(2, snapshot.depth());
            assertTrue(snapshot.tailSeq() >= snapshot.headSeq());
        } finally {
            ring.close();
        }
    }

    @Test
    void shouldRejectOfferWhenClosed() {
        StripedMpscRing<String> ring = new StripedMpscRing<>(8, 2);
        ring.close();
        assertInstanceOf(OfferResult.Closed.class, ring.offer(0, "x"));
    }

    @Test
    void shouldHandleWrapAround() {
        StripedMpscRing<Integer> ring = new StripedMpscRing<>(4, 1);
        try {
            int cap = ring.capacity();
            // Fill completely
            for (int i = 0; i < cap; i++) {
                assertInstanceOf(OfferResult.Ok.class, ring.offer(0, i));
            }
            assertInstanceOf(OfferResult.Full.class, ring.offer(0, 999));

            // Drain completely — FIFO order
            for (int i = 0; i < cap; i++) {
                assertEquals(i, ring.pollShard(0));
            }
            assertNull(ring.pollShard(0));
            assertEquals(0, ring.sizeApprox());

            // Fill again after wrap-around
            for (int i = 100; i < 100 + cap; i++) {
                assertInstanceOf(OfferResult.Ok.class, ring.offer(0, i));
            }
            assertInstanceOf(OfferResult.Full.class, ring.offer(0, 999));

            // Drain again — FIFO order preserved
            for (int i = 100; i < 100 + cap; i++) {
                assertEquals(i, ring.pollShard(0));
            }
            assertNull(ring.pollShard(0));
            assertTrue(ring.isDrained());
        } finally {
            ring.close();
        }
    }

    @Test
    void shouldRoundUpCapacityToPowerOfTwo() {
        // totalCapacity=3, shards=1 → perShard rounds up to 4
        StripedMpscRing<String> ring3 = new StripedMpscRing<>(3, 1);
        assertTrue(ring3.capacity() >= 3);
        assertTrue(Integer.bitCount(ring3.capacity()) == 1, "capacity must be power of 2");
        assertEquals(4, ring3.capacity());

        // totalCapacity=5, shards=1 → perShard rounds up to 8
        StripedMpscRing<String> ring5 = new StripedMpscRing<>(5, 1);
        assertTrue(ring5.capacity() >= 5);
        assertTrue(Integer.bitCount(ring5.capacity()) == 1);
        assertEquals(8, ring5.capacity());

        // totalCapacity=8, shards=2 → perShard=4 (already pow2), total=8
        StripedMpscRing<String> ring8 = new StripedMpscRing<>(8, 2);
        assertEquals(8, ring8.capacity());

        // totalCapacity=10, shards=2 → perShard=5 rounds to 8, total=16
        StripedMpscRing<String> ring10 = new StripedMpscRing<>(10, 2);
        assertTrue(ring10.capacity() >= 10);
        assertEquals(16, ring10.capacity());
    }

    @Test
    void shouldReturnCorrectShardDepth() {
        StripedMpscRing<String> ring = new StripedMpscRing<>(8, 2);
        try {
            assertEquals(0, ring.shardDepth(0));
            assertEquals(0, ring.shardDepth(1));

            ring.offer(0, "a");
            ring.offer(0, "b");
            ring.offer(1, "c");

            assertEquals(2, ring.shardDepth(0));
            assertEquals(1, ring.shardDepth(1));

            ring.pollShard(0);
            assertEquals(1, ring.shardDepth(0));
            assertEquals(1, ring.shardDepth(1));

            ring.pollShard(0);
            ring.pollShard(1);
            assertEquals(0, ring.shardDepth(0));
            assertEquals(0, ring.shardDepth(1));
        } finally {
            ring.close();
        }
    }

    @Test
    void shouldRejectNullElement() {
        StripedMpscRing<String> ring = new StripedMpscRing<>(4, 1);
        try {
            assertThrows(NullPointerException.class, () -> ring.offer(0, null));
            assertThrows(NullPointerException.class, () -> ring.offer(null));
        } finally {
            ring.close();
        }
    }

    @Test
    void shouldHandleSingleShardRing() {
        StripedMpscRing<String> ring = new StripedMpscRing<>(2, 1);
        try {
            assertEquals(1, ring.shardCount());
            assertEquals(2, ring.capacity());

            assertInstanceOf(OfferResult.Ok.class, ring.offer(0, "x"));
            assertInstanceOf(OfferResult.Ok.class, ring.offer(0, "y"));
            assertInstanceOf(OfferResult.Full.class, ring.offer(0, "z"));

            assertEquals("x", ring.poll());
            assertEquals("y", ring.poll());
            assertNull(ring.poll());

            assertTrue(ring.validateInvariants());
        } finally {
            ring.close();
        }
    }

    @Test
    void shouldOfferViaRandomShardSelection() {
        // Exercises the single-arg offer(E) which uses selectShard()
        // Use large capacity to avoid Full results from uneven random distribution
        StripedMpscRing<String> ring = new StripedMpscRing<>(256, 4);
        try {
            int offered = 0;
            for (int i = 0; i < 16; i++) {
                OfferResult result = ring.offer("item-" + i);
                if (result instanceof OfferResult.Ok) {
                    offered++;
                }
            }
            assertTrue(offered > 0);
            assertTrue(ring.sizeApprox() > 0);
        } finally {
            ring.close();
        }
    }

    @Test
    void shouldNormalizeNegativeShardId() {
        StripedMpscRing<String> ring = new StripedMpscRing<>(8, 4);
        try {
            assertInstanceOf(OfferResult.Ok.class, ring.offer(-1, "neg1"));
            assertInstanceOf(OfferResult.Ok.class, ring.offer(-3, "neg3"));
            assertEquals(2, ring.sizeApprox());

            // Negative shard -1 normalizes to shard 3 (for shardCount=4)
            assertNotNull(ring.pollShard(-1));
            // Negative shard -3 normalizes to shard 1
            assertNotNull(ring.pollShard(-3));
        } finally {
            ring.close();
        }
    }

    @Test
    void shouldReportIsClosedAndIsDrained() {
        StripedMpscRing<String> ring = new StripedMpscRing<>(4, 1);
        assertFalse(ring.isClosed());
        assertTrue(ring.isDrained());

        ring.offer(0, "x");
        assertFalse(ring.isDrained());

        ring.close();
        assertTrue(ring.isClosed());

        ring.pollShard(0);
        assertTrue(ring.isDrained());
    }

    @Test
    void shouldRoundUpPowerOfTwoEdgeCases() {
        // value=1 → 1 (the <= 1 branch)
        assertEquals(1, StripedMpscRing.roundUpPow2(1));
        assertEquals(1, StripedMpscRing.roundUpPow2(0));
        assertEquals(1, StripedMpscRing.roundUpPow2(-5));

        // already power of two
        assertEquals(2, StripedMpscRing.roundUpPow2(2));
        assertEquals(4, StripedMpscRing.roundUpPow2(4));
        assertEquals(16, StripedMpscRing.roundUpPow2(16));

        // not power of two → rounds up
        assertEquals(4, StripedMpscRing.roundUpPow2(3));
        assertEquals(8, StripedMpscRing.roundUpPow2(5));
        assertEquals(16, StripedMpscRing.roundUpPow2(9));

        // max safe value
        assertEquals(StripedMpscRing.MAX_POW2, StripedMpscRing.roundUpPow2(StripedMpscRing.MAX_POW2));

        // overflow guard
        assertThrows(IllegalArgumentException.class,
            () -> StripedMpscRing.roundUpPow2(StripedMpscRing.MAX_POW2 + 1));
        assertThrows(IllegalArgumentException.class,
            () -> StripedMpscRing.roundUpPow2(Integer.MAX_VALUE));
    }
}
