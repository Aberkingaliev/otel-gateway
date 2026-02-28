package com.acme.finops.gateway.audit;

import java.util.Map;

public record AuditEvent(
    String eventId,
    long tsEpochMs,
    String eventType,
    String actor,
    String tenantId,
    long requestId,
    String policyBundleId,
    String policyVersion,
    String outcome,
    Map<String, String> attrs
) {}
