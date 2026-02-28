package com.acme.finops.gateway.ci;

public interface RegressionChecker {
    RegressionResult check(BenchmarkSnapshot current, BenchmarkSnapshot baseline, PerfBudget budget);
}
