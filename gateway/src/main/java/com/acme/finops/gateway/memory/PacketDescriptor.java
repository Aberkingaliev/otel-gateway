package com.acme.finops.gateway.memory;

import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;

public record PacketDescriptor(
    long packetId,
    long requestId,
    SignalKind signalKind,
    ProtocolKind protocol,
    int payloadOffset,
    int payloadLength,
    long ingestNanos
) { }
