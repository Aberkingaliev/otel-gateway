package com.acme.finops.gateway.backpressure;

import com.acme.finops.gateway.memory.PacketDescriptor;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.memory.PacketRefImpl;
import com.acme.finops.gateway.queue.QueueSnapshot;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class QueueAwareDropPolicyTest {

    @Test
    void shouldDropStalePacket() {
        long now = System.nanoTime();
        QueueAwareDropPolicy policy = new QueueAwareDropPolicy(
            (depth, wm, ts) -> new ThrottleDecision(ThrottleMode.PASS, 0.0d, 0L, "pass"),
            new Watermarks(10, 20, 30),
            1_000_000L
        );
        PacketRef packet = packetRef(1L, now - 5_000_000L);
        try {
            DropDecision decision = policy.decide(packet, new QueueSnapshot(1, 100, 1, 1, now), now);
            DropDecision.Drop drop = assertInstanceOf(DropDecision.Drop.class, decision);
            assertEquals(DropReasonCode.STALE_PACKET, drop.reason());
        } finally {
            packet.release();
        }
    }

    @Test
    void shouldHonorShedRatioExtremes() {
        PacketRef packet = packetRef(42L, System.nanoTime());
        try {
            QueueAwareDropPolicy keepPolicy = new QueueAwareDropPolicy(
                (depth, wm, ts) -> new ThrottleDecision(ThrottleMode.SHED_LIGHT, 0.0d, 0L, "shed0"),
                new Watermarks(10, 20, 30),
                1_000_000_000L
            );
            DropDecision keepDecision = keepPolicy.decide(packet, new QueueSnapshot(50, 100, 1, 2, System.nanoTime()), System.nanoTime());
            assertInstanceOf(DropDecision.Keep.class, keepDecision);

            QueueAwareDropPolicy dropPolicy = new QueueAwareDropPolicy(
                (depth, wm, ts) -> new ThrottleDecision(ThrottleMode.SHED_AGGRESSIVE, 1.0d, 0L, "shed100"),
                new Watermarks(10, 20, 30),
                1_000_000_000L
            );
            DropDecision dropped = dropPolicy.decide(packet, new QueueSnapshot(50, 100, 1, 2, System.nanoTime()), System.nanoTime());
            DropDecision.Drop drop = assertInstanceOf(DropDecision.Drop.class, dropped);
            assertEquals(DropReasonCode.LOW_PRIORITY_SHEDDING, drop.reason());
        } finally {
            packet.release();
        }
    }

    @Test
    void shouldDropWhenPauseIngressMode() {
        QueueAwareDropPolicy policy = new QueueAwareDropPolicy(
            (depth, wm, ts) -> new ThrottleDecision(ThrottleMode.PAUSE_INGRESS, 1.0d, 1_000L, "pause"),
            new Watermarks(10, 20, 30),
            1_000_000_000L
        );
        PacketRef packet = packetRef(77L, System.nanoTime());
        try {
            DropDecision decision = policy.decide(packet, new QueueSnapshot(100, 100, 1, 2, System.nanoTime()), System.nanoTime());
            DropDecision.Drop drop = assertInstanceOf(DropDecision.Drop.class, decision);
            assertEquals(DropReasonCode.QUEUE_FULL, drop.reason());
        } finally {
            packet.release();
        }
    }

    private static PacketRef packetRef(long requestId, long ingestNanos) {
        byte[] payload = new byte[] {0x01, 0x02, 0x03};
        PacketDescriptor descriptor = new PacketDescriptor(
            requestId,
            requestId,
            SignalKind.TRACES,
            ProtocolKind.OTLP_HTTP_PROTO,
            0,
            payload.length,
            ingestNanos
        );
        return new PacketRefImpl(requestId, descriptor, MemorySegment.ofArray(payload), 0, payload.length);
    }
}

