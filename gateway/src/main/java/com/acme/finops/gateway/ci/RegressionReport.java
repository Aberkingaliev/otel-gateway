package com.acme.finops.gateway.ci;

public record RegressionReport(
    String scenario,
    boolean pass,
    String reason,
    BenchmarkSnapshot baseline,
    BenchmarkSnapshot current
) {}
