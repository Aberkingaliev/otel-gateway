package com.acme.finops.gateway.util;

/**
 * Canonical HTTP and gateway-internal status codes used across the data plane.
 * <p>
 * Wire-level constants (protobuf field tags, varint masks) intentionally remain
 * as inline literals in the {@code wire} package.
 */
public final class GatewayStatusCodes {

    // ---- Success ----
    public static final int OK = 200;

    // ---- Client errors ----
    public static final int BAD_REQUEST = 400;
    public static final int NOT_FOUND = 404;
    public static final int METHOD_NOT_ALLOWED = 405;
    public static final int GONE = 410;
    public static final int UNSUPPORTED_MEDIA_TYPE = 415;
    public static final int UNPROCESSABLE_ENTITY = 422;
    public static final int TOO_MANY_REQUESTS = 429;

    // ---- Server errors ----
    public static final int INTERNAL_ERROR = 500;
    public static final int NOT_IMPLEMENTED = 501;
    public static final int SERVICE_UNAVAILABLE = 503;
    public static final int INSUFFICIENT_STORAGE = 507;

    private GatewayStatusCodes() {
    }
}
