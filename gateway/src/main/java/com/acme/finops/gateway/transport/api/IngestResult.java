package com.acme.finops.gateway.transport.api;

public sealed interface IngestResult permits IngestResult.Accepted, IngestResult.PartialSuccess, IngestResult.Rejected, IngestResult.Busy {
    record Accepted(long requestId, long acceptedItems) implements IngestResult {}
    record PartialSuccess(long requestId, long acceptedItems, long rejectedItems, int reasonCode) implements IngestResult {}
    record Rejected(long requestId, int errorCode, boolean retryable) implements IngestResult {}
    record Busy(long requestId, long retryAfterMillis) implements IngestResult {}
}
