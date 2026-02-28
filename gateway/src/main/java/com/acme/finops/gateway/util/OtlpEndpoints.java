package com.acme.finops.gateway.util;

import com.acme.finops.gateway.transport.api.SignalKind;

/**
 * Shared OTLP endpoint and protocol route constants.
 */
public final class OtlpEndpoints {
    public static final int DEFAULT_GRPC_PORT = 4317;
    public static final int DEFAULT_HTTP_PORT = 4318;

    public static final String HTTP_TRACES_PATH = "/v1/traces";
    public static final String HTTP_METRICS_PATH = "/v1/metrics";
    public static final String HTTP_LOGS_PATH = "/v1/logs";

    public static final String DEFAULT_UPSTREAM_BASE_URL = "http://127.0.0.1:14328";
    public static final String DEFAULT_UPSTREAM_TRACES_URL = DEFAULT_UPSTREAM_BASE_URL + HTTP_TRACES_PATH;
    public static final String DEFAULT_UPSTREAM_METRICS_URL = DEFAULT_UPSTREAM_BASE_URL + HTTP_METRICS_PATH;
    public static final String DEFAULT_UPSTREAM_LOGS_URL = DEFAULT_UPSTREAM_BASE_URL + HTTP_LOGS_PATH;

    public static final String GRPC_TRACE_EXPORT_METHOD = "/opentelemetry.proto.collector.trace.v1.TraceService/Export";
    public static final String GRPC_TRACE_EXPORT_SUFFIX = "TraceService/Export";
    public static final String GRPC_METRICS_EXPORT_SUFFIX = "MetricsService/Export";
    public static final String GRPC_LOGS_EXPORT_SUFFIX = "LogsService/Export";

    public static final String SCHEMA_NAME_OTLP = "otlp";
    public static final String SCHEMA_KIND_TRACE = "trace";
    public static final String SCHEMA_VERSION_V1 = "v1";

    public static final String ALLOCATION_SCOPE_DEFAULT = "default";

    private OtlpEndpoints() {
    }

    public static SignalKind signalKindFromHttpPath(String path) {
        return switch (path) {
            case HTTP_TRACES_PATH -> SignalKind.TRACES;
            case HTTP_METRICS_PATH -> SignalKind.METRICS;
            case HTTP_LOGS_PATH -> SignalKind.LOGS;
            default -> null;
        };
    }

    public static SignalKind signalKindFromGrpcPath(String path, SignalKind fallback) {
        if (path == null || path.isEmpty()) {
            return fallback;
        }
        if (path.contains(GRPC_TRACE_EXPORT_SUFFIX)) {
            return SignalKind.TRACES;
        }
        if (path.contains(GRPC_METRICS_EXPORT_SUFFIX)) {
            return SignalKind.METRICS;
        }
        if (path.contains(GRPC_LOGS_EXPORT_SUFFIX)) {
            return SignalKind.LOGS;
        }
        return fallback;
    }
}
