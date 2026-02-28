package com.acme.finops.gateway.backpressure;

import com.acme.finops.gateway.memory.PacketDescriptor;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.queue.QueueSnapshot;

import java.util.Objects;

/**
 * Drop policy for queued packets under overload.
 */
public final class QueueAwareDropPolicy implements DropPolicy {
    private final ThrottleStrategy throttleStrategy;
    private final Watermarks watermarks;
    private final long maxQueueWaitNanos;

    public QueueAwareDropPolicy(ThrottleStrategy throttleStrategy,
                                Watermarks watermarks,
                                long maxQueueWaitNanos) {
        this.throttleStrategy = Objects.requireNonNull(throttleStrategy, "throttleStrategy");
        this.watermarks = Objects.requireNonNull(watermarks, "watermarks");
        this.maxQueueWaitNanos = Math.max(0L, maxQueueWaitNanos);
    }

    @Override
    public DropDecision decide(PacketRef packet, QueueSnapshot snapshot, long nowNanos) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(snapshot, "snapshot");

        PacketDescriptor descriptor = packet.descriptor();
        if (descriptor != null && descriptor.ingestNanos() > 0
            && nowNanos - descriptor.ingestNanos() > maxQueueWaitNanos) {
            return new DropDecision.Drop(DropReasonCode.STALE_PACKET);
        }

        ThrottleDecision throttle = throttleStrategy.onDepth(snapshot.depth(), watermarks, nowNanos);
        return switch (throttle.mode()) {
            case PASS -> new DropDecision.Keep();
            case SHED_LIGHT, SHED_AGGRESSIVE -> shouldShed(packet, throttle.shedRatio())
                ? new DropDecision.Drop(DropReasonCode.LOW_PRIORITY_SHEDDING)
                : new DropDecision.Keep();
            case PAUSE_INGRESS -> new DropDecision.Drop(DropReasonCode.QUEUE_FULL);
        };
    }

    private static boolean shouldShed(PacketRef packet, double ratio) {
        if (ratio <= 0.0d) {
            return false;
        }
        if (ratio >= 1.0d) {
            return true;
        }
        long seed = packet.descriptor() != null && packet.descriptor().requestId() > 0
            ? packet.descriptor().requestId()
            : packet.packetId();
        long mixed = mix64(seed);
        long bucket = mixed & 0xFFFF;
        long threshold = (long) (ratio * 65535.0d);
        return bucket <= threshold;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
