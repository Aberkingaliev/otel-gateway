package com.acme.finops.gateway.util;

import com.acme.finops.gateway.transport.api.SignalKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OtlpEndpointsTest {

    @Test
    void shouldResolveSignalKindFromHttpPath() {
        assertEquals(SignalKind.TRACES, OtlpEndpoints.signalKindFromHttpPath(OtlpEndpoints.HTTP_TRACES_PATH));
        assertEquals(SignalKind.METRICS, OtlpEndpoints.signalKindFromHttpPath(OtlpEndpoints.HTTP_METRICS_PATH));
        assertEquals(SignalKind.LOGS, OtlpEndpoints.signalKindFromHttpPath(OtlpEndpoints.HTTP_LOGS_PATH));
        assertNull(OtlpEndpoints.signalKindFromHttpPath("/v1/unknown"));
    }

    @Test
    void shouldResolveSignalKindFromGrpcPathWithFallback() {
        assertEquals(SignalKind.TRACES, OtlpEndpoints.signalKindFromGrpcPath(null, SignalKind.TRACES));
        assertEquals(SignalKind.METRICS, OtlpEndpoints.signalKindFromGrpcPath("", SignalKind.METRICS));

        assertEquals(SignalKind.TRACES, OtlpEndpoints.signalKindFromGrpcPath(
            "/opentelemetry.proto.collector.trace.v1.TraceService/Export",
            SignalKind.LOGS
        ));
        assertEquals(SignalKind.METRICS, OtlpEndpoints.signalKindFromGrpcPath(
            "/opentelemetry.proto.collector.metrics.v1.MetricsService/Export",
            SignalKind.TRACES
        ));
        assertEquals(SignalKind.LOGS, OtlpEndpoints.signalKindFromGrpcPath(
            "/opentelemetry.proto.collector.logs.v1.LogsService/Export",
            SignalKind.TRACES
        ));

        assertEquals(SignalKind.LOGS, OtlpEndpoints.signalKindFromGrpcPath("/not/otlp", SignalKind.LOGS));
    }
}

