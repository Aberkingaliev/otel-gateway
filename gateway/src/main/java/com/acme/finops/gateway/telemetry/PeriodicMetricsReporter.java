package com.acme.finops.gateway.telemetry;

import com.acme.finops.gateway.util.JsonCodec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Lightweight metrics logger for canary/ops visibility.
 */
public final class PeriodicMetricsReporter implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(PeriodicMetricsReporter.class.getName());

    private final AtomicHotPathMetrics metrics;
    private final Supplier<Map<String, Long>> additionalCountersSupplier;
    private final ScheduledExecutorService executor;
    private final long intervalSeconds;

    public PeriodicMetricsReporter(AtomicHotPathMetrics metrics, long intervalSeconds) {
        this(metrics, intervalSeconds, () -> Map.of());
    }

    public PeriodicMetricsReporter(AtomicHotPathMetrics metrics,
                                   long intervalSeconds,
                                   Supplier<Map<String, Long>> additionalCountersSupplier) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.intervalSeconds = Math.max(1L, intervalSeconds);
        this.additionalCountersSupplier = additionalCountersSupplier == null ? (() -> Map.of()) : additionalCountersSupplier;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gateway-metrics-reporter");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        executor.scheduleAtFixedRate(this::emit, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void emit() {
        try {
            AtomicHotPathMetrics.Snapshot s = metrics.snapshot();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("component", "gateway");
            payload.put("type", "hot_path_metrics");
            payload.put("packetsIn", s.packetsIn());
            payload.put("packetsOut", s.packetsOut());
            payload.put("queueDepth", s.queueDepth());
            payload.put("parseNanosTotal", s.parseNanosTotal());
            payload.put("parseSamples", s.parseSamples());
            payload.put("endToEndNanosTotal", s.endToEndNanosTotal());
            payload.put("endToEndSamples", s.endToEndSamples());
            payload.put("endToEndP99Nanos", s.endToEndP99Nanos());
            payload.put("droppedByReason", s.droppedByReason());
            payload.put("parseErrorsByCode", s.parseErrorsByCode());
            Map<String, Long> extra = additionalCountersSupplier.get();
            if (extra != null && !extra.isEmpty()) {
                payload.put("extraCounters", extra);
            }
            String rendered;
            try {
                rendered = JsonCodec.writeString(payload);
            } catch (Exception e) {
                rendered = payload.toString();
            }
            LOG.info(rendered);
        } catch (Throwable t) {
            LOG.warning("Metrics reporter failure: " + t.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
