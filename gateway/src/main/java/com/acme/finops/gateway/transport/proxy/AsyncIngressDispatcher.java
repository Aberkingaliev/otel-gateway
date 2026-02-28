package com.acme.finops.gateway.transport.proxy;

import com.acme.finops.gateway.backpressure.DropDecision;
import com.acme.finops.gateway.backpressure.DropPolicy;
import com.acme.finops.gateway.backpressure.ThrottleDecision;
import com.acme.finops.gateway.backpressure.ThrottleMode;
import com.acme.finops.gateway.backpressure.ThrottleStrategy;
import com.acme.finops.gateway.backpressure.Watermarks;
import com.acme.finops.gateway.queue.OfferResult;
import com.acme.finops.gateway.queue.QueueEnvelope;
import com.acme.finops.gateway.queue.QueueSnapshot;
import com.acme.finops.gateway.queue.StripedMpscRing;
import com.acme.finops.gateway.telemetry.HotPathMetrics;
import com.acme.finops.gateway.telemetry.NoopHotPathMetrics;
import com.acme.finops.gateway.transport.api.InboundPacket;
import com.acme.finops.gateway.transport.api.IngestResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import com.acme.finops.gateway.util.GatewayDefaults;
import com.acme.finops.gateway.util.GatewayStatusCodes;

import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Async ingress queue + worker dispatcher.
 */
public final class AsyncIngressDispatcher implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(AsyncIngressDispatcher.class.getName());
    private static final long MIN_IDLE_PARK_NANOS = 1_000L;
    private static final long MAX_IDLE_PARK_NANOS = 1_000_000L;

    private final StripedMpscRing<QueueEnvelope> queue;
    private final int workers;
    private final ThrottleStrategy throttleStrategy;
    private final Watermarks watermarks;
    private final DropPolicy dropPolicy;
    private final Function<InboundPacket, IngestResult> coreProcessor;
    private final HotPathMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile List<Thread> workerThreads = List.of();

    public AsyncIngressDispatcher(StripedMpscRing<QueueEnvelope> queue,
                                  int workers,
                                  ThrottleStrategy throttleStrategy,
                                  Watermarks watermarks,
                                  DropPolicy dropPolicy,
                                  Function<InboundPacket, IngestResult> coreProcessor,
                                  HotPathMetrics metrics) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.workers = Math.max(1, workers);
        this.throttleStrategy = Objects.requireNonNull(throttleStrategy, "throttleStrategy");
        this.watermarks = Objects.requireNonNull(watermarks, "watermarks");
        this.dropPolicy = Objects.requireNonNull(dropPolicy, "dropPolicy");
        this.coreProcessor = Objects.requireNonNull(coreProcessor, "coreProcessor");
        this.metrics = metrics == null ? NoopHotPathMetrics.INSTANCE : metrics;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        List<Thread> started = new ArrayList<>(workers);
        for (int i = 0; i < workers; i++) {
            final int workerId = i;
            Thread t = new Thread(() -> workerLoop(workerId), "ingress-dispatcher-" + workerId);
            t.setDaemon(true);
            t.start();
            started.add(t);
        }
        workerThreads = List.copyOf(started);
    }

    public EnqueueResult enqueue(InboundPacket packet) {
        if (packet == null || packet.packetRef() == null) {
            metrics.incDropped(1L, GatewayStatusCodes.BAD_REQUEST);
            return new EnqueueResult.Rejected(GatewayStatusCodes.BAD_REQUEST, false);
        }
        if (!running.get() || queue.isClosed()) {
            metrics.incDropped(1L, GatewayStatusCodes.SERVICE_UNAVAILABLE);
            return new EnqueueResult.Busy(GatewayDefaults.RETRY_PAUSE_INGRESS_MS, GatewayStatusCodes.SERVICE_UNAVAILABLE);
        }

        QueueSnapshot snapshot = queue.snapshot();
        metrics.setQueueDepth(snapshot.depth());
        ThrottleDecision throttle = throttleStrategy.onDepth(snapshot.depth(), watermarks, System.nanoTime());
        if (throttle.mode() == ThrottleMode.PAUSE_INGRESS) {
            metrics.incDropped(1L, GatewayStatusCodes.TOO_MANY_REQUESTS);
            return new EnqueueResult.Busy(retryAfterMillis(throttle), GatewayStatusCodes.TOO_MANY_REQUESTS);
        }

        int shardId = shardFor(packet.requestId());
        packet.packetRef().retain();
        QueueEnvelope envelope = null;
        try {
            envelope = new QueueEnvelope(
                packet,
                packet.packetRef().packetId(),
                packet.requestId(),
                shardId,
                0L,
                System.nanoTime()
            );
            OfferResult offer = queue.offer(shardId, envelope);
            if (offer instanceof OfferResult.Ok ok) {
                metrics.setQueueDepth(queue.sizeApprox());
                unparkWorkerForShard(shardId);
                return new EnqueueResult.Accepted(ok.seq(), shardId, queue.sizeApprox());
            }
            packet.packetRef().release();
            if (offer instanceof OfferResult.Full full) {
                metrics.setQueueDepth(full.depth());
                metrics.incDropped(1L, GatewayStatusCodes.TOO_MANY_REQUESTS);
                return new EnqueueResult.Busy(retryAfterMillis(throttle), GatewayStatusCodes.TOO_MANY_REQUESTS);
            }
            metrics.incDropped(1L, GatewayStatusCodes.SERVICE_UNAVAILABLE);
            return new EnqueueResult.Busy(GatewayDefaults.RETRY_CLOSED_MS, GatewayStatusCodes.SERVICE_UNAVAILABLE);
        } catch (Throwable t) {
            try {
                packet.packetRef().release();
            } catch (Throwable ignored) {
            }
            LOG.log(Level.WARNING, "enqueue failed requestId=" + packet.requestId(), t);
            metrics.incDropped(1L, GatewayStatusCodes.INTERNAL_ERROR);
            return new EnqueueResult.Rejected(GatewayStatusCodes.INTERNAL_ERROR, true);
        }
    }

    private void workerLoop(int workerId) {
        int shards = queue.shardCount();
        long idleNanos = MIN_IDLE_PARK_NANOS;
        while (running.get() || queue.sizeApprox() > 0) {
            boolean progressed = false;
            for (int shard = workerId; shard < shards; shard += workers) {
                QueueEnvelope envelope = queue.pollShard(shard);
                if (envelope == null) {
                    continue;
                }
                progressed = true;
                metrics.setQueueDepth(queue.sizeApprox());
                try {
                    DropDecision drop = dropPolicy.decide(envelope.packet().packetRef(), queue.snapshot(), System.nanoTime());
                    if (drop instanceof DropDecision.Drop) {
                        metrics.incDropped(1L, GatewayStatusCodes.TOO_MANY_REQUESTS);
                        continue;
                    }
                    coreProcessor.apply(envelope.packet());
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "dispatcher worker failure requestId=" + envelope.requestId(), t);
                    metrics.incDropped(1L, GatewayStatusCodes.INTERNAL_ERROR);
                } finally {
                    try {
                        envelope.packet().packetRef().release();
                    } catch (Throwable releaseError) {
                        LOG.log(Level.WARNING, "Failed to release queue-owned packetRef requestId=" + envelope.requestId(), releaseError);
                    }
                }
            }
            if (!progressed) {
                LockSupport.parkNanos(idleNanos);
                idleNanos = Math.min(idleNanos << 1, MAX_IDLE_PARK_NANOS);
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
            } else {
                idleNanos = MIN_IDLE_PARK_NANOS;
            }
        }
    }

    public void stopAndDrain(Duration timeout) {
        running.set(false);
        queue.close();
        long deadlineNanos = System.nanoTime() + Math.max(1L, timeout.toNanos());
        List<Thread> threads = workerThreads;
        for (Thread t : threads) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            try {
                t.join(Math.max(1L, remaining / 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!queue.isDrained() || !queue.validateInvariants()) {
            LOG.warning("dispatcher drain completed with non-empty or inconsistent queue state"
                + " depth=" + queue.sizeApprox()
                + " invariants=" + queue.validateInvariants());
        }
    }

    private int shardFor(long requestId) {
        return Math.floorMod(Long.hashCode(requestId), queue.shardCount());
    }

    private void unparkWorkerForShard(int shardId) {
        List<Thread> threads = workerThreads;
        int size = threads.size();
        if (size == 0) {
            return;
        }
        Thread worker = threads.get(Math.floorMod(shardId, size));
        if (worker != null) {
            LockSupport.unpark(worker);
        }
    }

    @Override
    public void close() {
        stopAndDrain(Duration.ofSeconds(5));
    }

    private static long retryAfterMillis(ThrottleDecision throttleDecision) {
        if (throttleDecision.mode() == ThrottleMode.PAUSE_INGRESS) {
            return Math.max(1L, throttleDecision.pauseNanos() / 1_000_000L);
        }
        return switch (throttleDecision.mode()) {
            case SHED_AGGRESSIVE -> GatewayDefaults.RETRY_SHED_AGGRESSIVE_MS;
            case SHED_LIGHT -> GatewayDefaults.RETRY_SHED_LIGHT_MS;
            case PASS -> GatewayDefaults.RETRY_PASS_MS;
            case PAUSE_INGRESS -> GatewayDefaults.RETRY_PAUSE_INGRESS_MS;
        };
    }
}
