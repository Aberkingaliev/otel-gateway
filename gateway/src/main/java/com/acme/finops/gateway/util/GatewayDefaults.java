package com.acme.finops.gateway.util;

/**
 * Default capacity, timeout, and tuning constants for the gateway runtime.
 * <p>
 * These values are used when the corresponding environment variable is not set.
 */
public final class GatewayDefaults {

    // ---- Slab allocator ----
    public static final long DEFAULT_SLAB_SIZE_BYTES = 1024L * 1024 * 1024;
    public static final int DEFAULT_ALLOCATOR_SHARDS = 4;
    public static final int DEFAULT_SLAB_REGIONS = 8;

    // ---- Exporter ----
    public static final int DEFAULT_MAX_INFLIGHT = 16_384;
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_RESPONSE_TIMEOUT_MS = 10_000;
    public static final int HTTPS_DEFAULT_PORT = 443;
    public static final int HTTP_DEFAULT_PORT = 80;
    public static final int DEFAULT_EXPORTER_POOL_SIZE = 256;
    public static final int DEFAULT_EXPORTER_IO_THREADS = 4;

    // ---- Queue ----
    public static final int DEFAULT_QUEUE_CAPACITY = 65_536;
    public static final int DEFAULT_QUEUE_SHARDS = 16;
    public static final int DEFAULT_QUEUE_WORKERS = 16;

    // ---- Netty transport ----
    public static final int DEFAULT_SO_BACKLOG = 1024;
    public static final int MAX_CONTENT_LENGTH = 16 * 1024 * 1024;

    // ---- Throttle retry intervals (millis) ----
    public static final long RETRY_PASS_MS = 25L;
    public static final long RETRY_SHED_LIGHT_MS = 75L;
    public static final long RETRY_CLOSED_MS = 100L;
    public static final long RETRY_SHED_AGGRESSIVE_MS = 150L;
    public static final long RETRY_PAUSE_INGRESS_MS = 250L;

    // ---- Throttle ----
    public static final long DEFAULT_THROTTLE_PAUSE_NANOS = 5_000_000L;

    // ---- Exporter response aggregator ----
    public static final int EXPORTER_RESPONSE_LIMIT = 2 * 1024 * 1024;

    // ---- Metrics endpoint ----
    public static final int DEFAULT_METRICS_RENDER_BUFFER = 2048;

    private GatewayDefaults() {
    }
}
