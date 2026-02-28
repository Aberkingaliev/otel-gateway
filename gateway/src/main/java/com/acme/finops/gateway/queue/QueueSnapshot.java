package com.acme.finops.gateway.queue;

public record QueueSnapshot(
    int depth,
    int capacity,
    long headSeq,
    long tailSeq,
    long tsNanos
) {}
