package com.acme.finops.gateway.util;

/**
 * gRPC-over-HTTP2 header and media type constants used by adapters and tools.
 */
public final class GrpcProtocolConstants {
    public static final String HEADER_CONTENT_TYPE = "content-type";
    public static final String HEADER_GRPC_STATUS = "grpc-status";
    public static final String HEADER_GRPC_MESSAGE = "grpc-message";
    public static final String HEADER_TE = "te";

    public static final String VALUE_GRPC_CONTENT_TYPE = "application/grpc+proto";
    public static final String VALUE_GRPC_CONTENT_PREFIX = "application/grpc";
    public static final String VALUE_TE_TRAILERS = "trailers";
    public static final String VALUE_HTTP2_STATUS_OK = "200";

    private GrpcProtocolConstants() {
    }
}
