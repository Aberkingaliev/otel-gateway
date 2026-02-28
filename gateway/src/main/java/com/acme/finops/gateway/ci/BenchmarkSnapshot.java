package com.acme.finops.gateway.ci;

public record BenchmarkSnapshot(
    String scenario,
    double throughputOpsSec,
    long p50Nanos,
    long p95Nanos,
    long p99Nanos,
    long allocBytesPerOp,
    double gcTimePercent
) {}
