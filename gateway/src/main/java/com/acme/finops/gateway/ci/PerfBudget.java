package com.acme.finops.gateway.ci;

public record PerfBudget(
    String scenario,
    double minThroughputOpsSec,
    long maxP99Nanos,
    long maxAllocBytesPerOp,
    double maxGcTimePercent
) {}
