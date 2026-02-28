package com.acme.finops.gateway.telemetry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsHttpEndpointTest {

    @Test
    void shouldRenderPrometheusTextFormat() {
        AtomicHotPathMetrics metrics = new AtomicHotPathMetrics();
        metrics.incPacketsIn(10);
        metrics.incPacketsOut(7);
        metrics.incDropped(3, 429);
        metrics.incParseErrors(2, 400);
        metrics.observeParseNanos(1_000);
        metrics.observeEndToEndNanos(2_000);
        metrics.setQueueDepth(5);

        String body = MetricsHttpEndpoint.renderPrometheus(metrics.snapshot(), Map.of("auditDroppedEvents", 4L));

        assertTrue(body.contains("# HELP gateway_packets_processed_total"));
        assertTrue(body.matches("(?s).*gateway_packets_processed_total\\{(?:signal=\"ALL\",status=\"accepted\"|status=\"accepted\",signal=\"ALL\")\\} 7\\n.*"));
        assertTrue(body.matches("(?s).*gateway_packets_processed_total\\{(?:signal=\"ALL\",status=\"dropped\"|status=\"dropped\",signal=\"ALL\")\\} 3\\n.*"));
        assertTrue(body.contains("gateway_queue_depth 5"));
        assertTrue(body.contains("gateway_dropped_total{reason_code=\"429\"} 3"));
        assertTrue(body.contains("gateway_parse_errors_total{error_code=\"400\"} 2"));
        assertTrue(body.contains("gateway_mask_writer_active"));
        assertTrue(body.contains("gateway_masking_simd_available 0"));
        assertTrue(body.contains("gateway_auditDroppedEvents_total 4"));
    }

    @Test
    void shouldRenderMaskingRuntimeInfo() {
        AtomicHotPathMetrics metrics = new AtomicHotPathMetrics();
        String body = MetricsHttpEndpoint.renderPrometheus(
            metrics.snapshot(),
            Map.of(),
            new MaskingRuntimeInfo("on", "simd", true, true, null)
        );

        assertTrue(body.matches("(?s).*gateway_mask_writer_active\\{(?:requested_mode=\"on\",active_writer=\"simd\"|active_writer=\"simd\",requested_mode=\"on\")\\} 1\\n.*"));
        assertTrue(body.contains("gateway_masking_simd_available 1"));
        assertTrue(body.contains("gateway_masking_simd_strict_mode 1"));
    }
}
