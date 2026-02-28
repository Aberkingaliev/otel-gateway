package com.acme.finops.gateway.ci;

public sealed interface RegressionResult permits RegressionResult.Pass, RegressionResult.Fail {
    record Pass(String scenario) implements RegressionResult {}
    record Fail(String scenario, String reason) implements RegressionResult {}
}
