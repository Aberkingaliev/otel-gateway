package com.acme.finops.gateway.backpressure;

public sealed interface DropDecision permits DropDecision.Keep, DropDecision.Drop {
    record Keep() implements DropDecision {}
    record Drop(DropReasonCode reason) implements DropDecision {}
}

enum DropReasonCode {
    QUEUE_FULL, TENANT_QUOTA_EXCEEDED, STALE_PACKET, LOW_PRIORITY_SHEDDING, MALFORMED_PACKET
}
