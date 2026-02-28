package com.acme.finops.gateway.memory;

public record AllocatorStats(long allocCount, long releaseCount, long inUseBytes, long failedAllocations) {}
