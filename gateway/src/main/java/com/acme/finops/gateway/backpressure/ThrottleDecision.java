package com.acme.finops.gateway.backpressure;

public record ThrottleDecision(
    ThrottleMode mode,
    double shedRatio,
    long pauseNanos,
    String reason
) {}
