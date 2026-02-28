package com.acme.finops.gateway.queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}

