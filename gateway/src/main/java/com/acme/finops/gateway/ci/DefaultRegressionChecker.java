package com.acme.finops.gateway.ci;

import java.util.Objects;

public final class DefaultRegressionChecker implements RegressionChecker {
    @Override
    public RegressionResult check(BenchmarkSnapshot current, BenchmarkSnapshot baseline, PerfBudget budget) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(budget, "budget");

        if (current.throughputOpsSec() < budget.minThroughputOpsSec()) {
            return new RegressionResult.Fail(current.scenario(), "throughput_below_budget");
        }
        if (current.p99Nanos() > budget.maxP99Nanos()) {
            return new RegressionResult.Fail(current.scenario(), "p99_above_budget");
        }
        if (current.allocBytesPerOp() > budget.maxAllocBytesPerOp()) {
            return new RegressionResult.Fail(current.scenario(), "alloc_above_budget");
        }
        if (current.gcTimePercent() > budget.maxGcTimePercent()) {
            return new RegressionResult.Fail(current.scenario(), "gc_above_budget");
        }

        if (current.throughputOpsSec() + 1e-9 < baseline.throughputOpsSec() * 0.9) {
            return new RegressionResult.Fail(current.scenario(), "throughput_drop_vs_baseline");
        }
        if (current.p99Nanos() > baseline.p99Nanos() * 1.2) {
            return new RegressionResult.Fail(current.scenario(), "p99_regression_vs_baseline");
        }

        return new RegressionResult.Pass(current.scenario());
    }
}
