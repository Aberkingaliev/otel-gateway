package com.acme.finops.gateway.transport.api;

import com.acme.finops.gateway.memory.PacketRef;

public record TransportAck(int statusCode, PacketRef responsePayload) implements TransportResponse { }
