package com.acme.finops.gateway.policy;

import com.acme.finops.gateway.memory.PacketRef;

public record PolicyContext(
    long requestId,
    long tenantId,
    PacketRef envelope,
    long nowNanos
) {}
