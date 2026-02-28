package com.acme.finops.gateway.telemetry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.acme.finops.gateway.util.GatewayDefaults;
import com.acme.finops.gateway.util.GatewayStatusCodes;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * Lightweight machine-readable metrics endpoint.
 */
public final class MetricsHttpEndpoint implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(MetricsHttpEndpoint.class.getName());
    private static final Pattern METRIC_NAME_SANITIZER = Pattern.compile("[^a-zA-Z0-9_]");

    private final AtomicHotPathMetrics metrics;
    private final int port;
    private final String path;
    private final Supplier<Map<String, Long>> additionalCountersSupplier;
    private final Supplier<MaskingRuntimeInfo> maskingRuntimeInfoSupplier;
    private final HttpServer server;

    public MetricsHttpEndpoint(AtomicHotPathMetrics metrics,
                               int port,
                               String path,
                               Supplier<Map<String, Long>> additionalCountersSupplier) throws IOException {
        this(metrics, port, path, additionalCountersSupplier, () -> MaskingRuntimeInfo.UNKNOWN);
    }

    public MetricsHttpEndpoint(AtomicHotPathMetrics metrics,
                               int port,
                               String path,
                               Supplier<Map<String, Long>> additionalCountersSupplier,
                               Supplier<MaskingRuntimeInfo> maskingRuntimeInfoSupplier) throws IOException {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.port = port;
        this.path = normalizePath(path);
        this.additionalCountersSupplier = additionalCountersSupplier == null ? (() -> Map.of()) : additionalCountersSupplier;
        this.maskingRuntimeInfoSupplier = maskingRuntimeInfoSupplier == null
            ? (() -> MaskingRuntimeInfo.UNKNOWN)
            : maskingRuntimeInfoSupplier;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext(this.path, this::handle);
        this.server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "metrics-http-endpoint");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() {
        server.start();
        LOG.info("Metrics endpoint started on :" + port + path);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                write(exchange, GatewayStatusCodes.METHOD_NOT_ALLOWED, "method not allowed\n");
                return;
            }
            AtomicHotPathMetrics.Snapshot s = metrics.snapshot();
            Map<String, Long> extra = additionalCountersSupplier.get();
            MaskingRuntimeInfo maskingInfo = maskingRuntimeInfoSupplier.get();
            String body = renderPrometheus(s, extra == null ? Map.of() : extra, maskingInfo);
            write(exchange, GatewayStatusCodes.OK, body);
        } catch (Throwable t) {
            write(exchange, GatewayStatusCodes.INTERNAL_ERROR, "internal error\n");
        }
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "/metrics";
        }
        return rawPath.startsWith("/") ? rawPath : "/" + rawPath;
    }

    static String renderPrometheus(AtomicHotPathMetrics.Snapshot snapshot, Map<String, Long> additionalCounters) {
        return renderPrometheus(snapshot, additionalCounters, MaskingRuntimeInfo.UNKNOWN);
    }

    static String renderPrometheus(AtomicHotPathMetrics.Snapshot snapshot,
                                   Map<String, Long> additionalCounters,
                                   MaskingRuntimeInfo maskingRuntimeInfo) {
        StringBuilder sb = new StringBuilder(GatewayDefaults.DEFAULT_METRICS_RENDER_BUFFER);
        MaskingRuntimeInfo info = maskingRuntimeInfo == null ? MaskingRuntimeInfo.UNKNOWN : maskingRuntimeInfo;

        appendHelpType(sb, "gateway_packets_processed_total", "Total packets processed", "counter");
        appendMetric(sb, "gateway_packets_processed_total", Map.of("signal", "ALL", "status", "received"), snapshot.packetsIn());
        appendMetric(sb, "gateway_packets_processed_total", Map.of("signal", "ALL", "status", "accepted"), snapshot.packetsOut());
        long droppedTotal = 0L;
        for (Long value : snapshot.droppedByReason().values()) {
            if (value != null && value > 0) {
                droppedTotal += value;
            }
        }
        appendMetric(sb, "gateway_packets_processed_total", Map.of("signal", "ALL", "status", "dropped"), droppedTotal);

        appendHelpType(sb, "gateway_queue_depth", "Current queue depth", "gauge");
        appendMetric(sb, "gateway_queue_depth", Map.of(), snapshot.queueDepth());

        appendHelpType(sb, "gateway_dropped_total", "Dropped packets by reason code", "counter");
        for (Map.Entry<Integer, Long> e : snapshot.droppedByReason().entrySet()) {
            appendMetric(sb, "gateway_dropped_total", Map.of("reason_code", Integer.toString(e.getKey())), e.getValue());
        }

        appendHelpType(sb, "gateway_parse_errors_total", "Parse errors by code", "counter");
        for (Map.Entry<Integer, Long> e : snapshot.parseErrorsByCode().entrySet()) {
            appendMetric(sb, "gateway_parse_errors_total", Map.of("error_code", Integer.toString(e.getKey())), e.getValue());
        }

        appendHelpType(sb, "gateway_parse_duration_nanos", "Parse duration summary in nanoseconds", "summary");
        appendMetric(sb, "gateway_parse_duration_nanos_sum", Map.of(), snapshot.parseNanosTotal());
        appendMetric(sb, "gateway_parse_duration_nanos_count", Map.of(), snapshot.parseSamples());

        appendHelpType(sb, "gateway_end_to_end_duration_nanos", "End-to-end duration summary in nanoseconds", "summary");
        appendMetric(sb, "gateway_end_to_end_duration_nanos_sum", Map.of(), snapshot.endToEndNanosTotal());
        appendMetric(sb, "gateway_end_to_end_duration_nanos_count", Map.of(), snapshot.endToEndSamples());

        appendHelpType(sb, "gateway_end_to_end_p99_nanos", "End-to-end p99 latency in nanoseconds", "gauge");
        appendMetric(sb, "gateway_end_to_end_p99_nanos", Map.of(), snapshot.endToEndP99Nanos());

        appendHelpType(sb, "gateway_mask_writer_active", "Current mask writer selection", "gauge");
        appendMetric(
            sb,
            "gateway_mask_writer_active",
            Map.of("requested_mode", info.requestedMode(), "active_writer", info.effectiveWriter()),
            1L
        );
        appendHelpType(sb, "gateway_masking_simd_available", "Vector API availability for masking", "gauge");
        appendMetric(sb, "gateway_masking_simd_available", Map.of(), info.simdAvailable() ? 1L : 0L);
        appendHelpType(sb, "gateway_masking_simd_strict_mode", "Strict SIMD mode flag", "gauge");
        appendMetric(sb, "gateway_masking_simd_strict_mode", Map.of(), info.strictMode() ? 1L : 0L);
        if (info.fallbackReason() != null) {
            appendHelpType(sb, "gateway_masking_simd_fallback", "SIMD fallback indicator by reason", "gauge");
            appendMetric(sb, "gateway_masking_simd_fallback", Map.of("reason", info.fallbackReason()), 1L);
        }

        if (additionalCounters != null && !additionalCounters.isEmpty()) {
            for (Map.Entry<String, Long> e : additionalCounters.entrySet()) {
                String metricName = toMetricName("gateway_" + e.getKey() + "_total");
                appendHelpType(sb, metricName, "Additional gateway counter: " + e.getKey(), "counter");
                appendMetric(sb, metricName, Map.of(), e.getValue());
            }
        }
        return sb.toString();
    }

    private static String toMetricName(String raw) {
        String normalized = METRIC_NAME_SANITIZER.matcher(raw).replaceAll("_");
        if (normalized.isBlank()) {
            return "gateway_unknown_metric_total";
        }
        char first = normalized.charAt(0);
        if ((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z') || first == '_') {
            return normalized;
        }
        return "gateway_" + normalized;
    }

    private static void appendHelpType(StringBuilder sb, String metric, String help, String type) {
        sb.append("# HELP ").append(metric).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(metric).append(' ').append(type).append('\n');
    }

    private static void appendMetric(StringBuilder sb, String name, Map<String, String> labels, long value) {
        sb.append(name);
        if (labels != null && !labels.isEmpty()) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, String> e : labels.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(e.getKey()).append("=\"").append(escapeLabelValue(e.getValue())).append('"');
            }
            sb.append('}');
        }
        sb.append(' ').append(value).append('\n');
    }

    private static String escapeLabelValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
