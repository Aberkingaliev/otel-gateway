package com.acme.finops.gateway.compat;

import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.wire.SchemaId;

public record CompatInput(
    long requestId,
    SchemaId sourceSchema,
    SchemaId targetSchema,
    PacketRef envelope
) {}
