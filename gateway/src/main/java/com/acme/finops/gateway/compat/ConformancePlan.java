package com.acme.finops.gateway.compat;

public record ConformancePlan(
    String profileId,
    int corpusSize,
    int passedCases,
    int failedCases,
    double requiredPassRate
) {
    public ConformancePlan(String profileId, int corpusSize) {
        this(profileId, corpusSize, corpusSize, 0, 0.99d);
    }
}
