package com.acme.finops.gateway.memory;

public record SlabPoolStats(long totalBytes, long freeBytes, int inUseLeases) {}
