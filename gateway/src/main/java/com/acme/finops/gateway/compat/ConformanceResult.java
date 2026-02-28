package com.acme.finops.gateway.compat;

public sealed interface ConformanceResult permits ConformanceResult.Passed, ConformanceResult.Failed, ConformanceResult.Aborted {
    record Passed(String reportId) implements ConformanceResult {}
    record Failed(String reportId, int failedCases) implements ConformanceResult {}
    record Aborted(int errorCode) implements ConformanceResult {}
}
