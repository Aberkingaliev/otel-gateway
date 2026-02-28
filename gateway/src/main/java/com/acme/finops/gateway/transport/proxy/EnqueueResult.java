package com.acme.finops.gateway.transport.proxy;

public sealed interface EnqueueResult permits EnqueueResult.Accepted, EnqueueResult.Busy, EnqueueResult.Rejected {
    record Accepted(long seq, int shardId, int depth) implements EnqueueResult {}
    record Busy(long retryAfterMillis, int reasonCode) implements EnqueueResult {}
    record Rejected(int errorCode, boolean retryable) implements EnqueueResult {}
}
