package com.acme.finops.gateway.transport.api;

import com.acme.finops.gateway.memory.PacketRef;

public record InboundPacket(
    long requestId,
    ProtocolKind protocol,
    SignalKind signalKind,
    PacketRef packetRef,
    String contentType
) {
    public InboundPacket(long requestId,
                         ProtocolKind protocol,
                         SignalKind signalKind,
                         PacketRef packetRef) {
        this(requestId, protocol, signalKind, packetRef, null);
    }
}
