package com.acme.finops.gateway.transport.api;

public record TransportNack(
    int statusCode,
    int errorCode,
    boolean retryable,
    long retryAfterMillis
) implements TransportResponse { }
