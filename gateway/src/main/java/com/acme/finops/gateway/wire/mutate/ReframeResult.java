package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.memory.PacketRef;

public sealed interface ReframeResult permits ReframeResult.Success, ReframeResult.Failed {
    record Success(PacketRef reframed) implements ReframeResult {}
    record Failed(int errorCode) implements ReframeResult {}
}
