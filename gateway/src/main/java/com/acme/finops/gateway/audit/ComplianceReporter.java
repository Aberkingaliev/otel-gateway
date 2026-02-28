package com.acme.finops.gateway.audit;

public interface ComplianceReporter {
    ComplianceSnapshot snapshot(ComplianceQuery query);
}
