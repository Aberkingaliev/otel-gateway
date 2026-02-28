package com.acme.finops.gateway.compat;

import com.acme.finops.gateway.util.GatewayStatusCodes;

public final class DefaultConformanceHarness implements ConformanceHarness {
    @Override
    public ConformanceResult run(ConformancePlan plan) {
        if (plan == null || plan.profileId() == null || plan.profileId().isBlank() || plan.corpusSize() <= 0) {
            return new ConformanceResult.Aborted(GatewayStatusCodes.BAD_REQUEST);
        }
        if (plan.corpusSize() < 50) {
            return new ConformanceResult.Failed("conformance-small-corpus", plan.corpusSize());
        }
        int passed = Math.max(0, plan.passedCases());
        int failed = Math.max(0, plan.failedCases());
        if (passed + failed > plan.corpusSize()) {
            return new ConformanceResult.Aborted(GatewayStatusCodes.UNPROCESSABLE_ENTITY);
        }
        double passRate = plan.corpusSize() == 0 ? 0.0d : (double) passed / (double) plan.corpusSize();
        if (failed > 0 || passRate < plan.requiredPassRate()) {
            return new ConformanceResult.Failed("conformance-" + plan.profileId(), failed);
        }
        return new ConformanceResult.Passed("conformance-" + plan.profileId() + "-" + System.currentTimeMillis());
    }
}
