package com.acme.finops.gateway.util;

/**
 * Canonical environment variable names used by gateway runtime.
 */
public final class GatewayEnvKeys {
    public static final String OTLP_UPSTREAM_TRACES_URL = "OTLP_UPSTREAM_TRACES_URL";
    public static final String OTLP_UPSTREAM_METRICS_URL = "OTLP_UPSTREAM_METRICS_URL";
    public static final String OTLP_UPSTREAM_LOGS_URL = "OTLP_UPSTREAM_LOGS_URL";
    public static final String OTLP_UPSTREAM_AUTH_HEADER = "OTLP_UPSTREAM_AUTH_HEADER";
    public static final String OTLP_UPSTREAM_AUTH_VALUE = "OTLP_UPSTREAM_AUTH_VALUE";
    public static final String DD_API_KEY = "DD_API_KEY";

    public static final String GATEWAY_HEALTHCHECK_PATH = "GATEWAY_HEALTHCHECK_PATH";
    public static final String GATEWAY_ENABLE_REFRAME = "GATEWAY_ENABLE_REFRAME";
    public static final String GATEWAY_REFRAME_INTEGRITY_MODE = "GATEWAY_REFRAME_INTEGRITY_MODE";
    public static final String GATEWAY_MASKING_ENABLED = "GATEWAY_MASKING_ENABLED";
    public static final String GATEWAY_MASKING_RULES = "GATEWAY_MASKING_RULES";
    public static final String GATEWAY_MASKING_SIMD = "GATEWAY_MASKING_SIMD";
    public static final String GATEWAY_MASKING_MAX_OPS_PER_PACKET = "GATEWAY_MASKING_MAX_OPS_PER_PACKET";

    public static final String GATEWAY_QUEUE_ENABLED = "GATEWAY_QUEUE_ENABLED";
    public static final String GATEWAY_QUEUE_CAPACITY = "GATEWAY_QUEUE_CAPACITY";
    public static final String GATEWAY_QUEUE_SHARDS = "GATEWAY_QUEUE_SHARDS";
    public static final String GATEWAY_ALLOCATOR_SHARDS = "GATEWAY_ALLOCATOR_SHARDS";
    public static final String GATEWAY_SLAB_SIZE_BYTES = "GATEWAY_SLAB_SIZE_BYTES";
    public static final String GATEWAY_QUEUE_WORKERS = "GATEWAY_QUEUE_WORKERS";

    public static final String GATEWAY_MAX_INFLIGHT = "GATEWAY_MAX_INFLIGHT";
    public static final String GATEWAY_EXPORTER_POOL_SIZE = "GATEWAY_EXPORTER_POOL_SIZE";
    public static final String GATEWAY_EXPORTER_IO_THREADS = "GATEWAY_EXPORTER_IO_THREADS";

    public static final String GATEWAY_BACKPRESSURE_LOW = "GATEWAY_BACKPRESSURE_LOW";
    public static final String GATEWAY_BACKPRESSURE_HIGH = "GATEWAY_BACKPRESSURE_HIGH";
    public static final String GATEWAY_BACKPRESSURE_CRITICAL = "GATEWAY_BACKPRESSURE_CRITICAL";
    public static final String GATEWAY_BACKPRESSURE_MAX_QUEUE_WAIT_MS = "GATEWAY_BACKPRESSURE_MAX_QUEUE_WAIT_MS";
    public static final String GATEWAY_BACKPRESSURE_SHED_LIGHT_RATIO = "GATEWAY_BACKPRESSURE_SHED_LIGHT_RATIO";
    public static final String GATEWAY_BACKPRESSURE_SHED_AGGRESSIVE_RATIO = "GATEWAY_BACKPRESSURE_SHED_AGGRESSIVE_RATIO";

    public static final String GATEWAY_METRICS_ENABLED = "GATEWAY_METRICS_ENABLED";
    public static final String GATEWAY_METRICS_LOG_INTERVAL_SEC = "GATEWAY_METRICS_LOG_INTERVAL_SEC";
    public static final String GATEWAY_METRICS_HTTP_ENABLED = "GATEWAY_METRICS_HTTP_ENABLED";
    public static final String GATEWAY_METRICS_HTTP_PORT = "GATEWAY_METRICS_HTTP_PORT";
    public static final String GATEWAY_METRICS_HTTP_PATH = "GATEWAY_METRICS_HTTP_PATH";

    public static final String GATEWAY_AUDIT_ENABLED = "GATEWAY_AUDIT_ENABLED";
    public static final String GATEWAY_AUDIT_DIR = "GATEWAY_AUDIT_DIR";
    public static final String GATEWAY_AUDIT_QUEUE_CAPACITY = "GATEWAY_AUDIT_QUEUE_CAPACITY";
    public static final String GATEWAY_AUDIT_FLUSH_INTERVAL_MS = "GATEWAY_AUDIT_FLUSH_INTERVAL_MS";
    public static final String GATEWAY_AUDIT_FSYNC_INTERVAL_MS = "GATEWAY_AUDIT_FSYNC_INTERVAL_MS";
    public static final String GATEWAY_AUDIT_MAX_FILE_MB = "GATEWAY_AUDIT_MAX_FILE_MB";
    public static final String GATEWAY_AUDIT_ROTATE_INTERVAL_SEC = "GATEWAY_AUDIT_ROTATE_INTERVAL_SEC";
    public static final String GATEWAY_AUDIT_RETENTION_DAYS = "GATEWAY_AUDIT_RETENTION_DAYS";

    private GatewayEnvKeys() {
    }
}
