package com.acme.finops.gateway.audit;

public record ComplianceQuery(long fromEpochMs, long toEpochMs, String tenantId, String policyBundleId) {}
