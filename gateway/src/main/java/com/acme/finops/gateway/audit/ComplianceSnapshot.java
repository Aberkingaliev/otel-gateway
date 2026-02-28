package com.acme.finops.gateway.audit;

public record ComplianceSnapshot(long totalEvents, long policyChanges, long redactionEvents, long droppedEvents, long parserErrors, long rollbackEvents) {}
