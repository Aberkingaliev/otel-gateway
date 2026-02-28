package com.acme.finops.gateway.memory;

public record AllocationTag(String pipeline, String tenantId, int signalTypeCode) {}
