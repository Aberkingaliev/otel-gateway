package com.acme.finops.gateway.queue;

import com.acme.finops.gateway.transport.api.InboundPacket;

public record QueueEnvelope(
    InboundPacket packet,
    long packetId,
    long requestId,
    int shardId,
    long enqueueSeq,
    long enqueueNanos
) {}
