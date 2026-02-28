package com.acme.finops.gateway.transport.api;

import java.util.Set;

public interface IngressPort {
    IngestResult ingest(InboundPacket packet);
    Set<ProtocolKind> supportedProtocols();
}
