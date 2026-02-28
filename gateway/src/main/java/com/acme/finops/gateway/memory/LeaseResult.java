package com.acme.finops.gateway.memory;

public sealed interface LeaseResult permits LeaseResult.Granted, LeaseResult.Denied {
    record Granted(PacketRef packetRef) implements LeaseResult {}
    record Denied(int reasonCode) implements LeaseResult {}
}
