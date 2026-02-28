package com.acme.finops.gateway.transport.grpc;

import com.acme.finops.gateway.transport.api.TransportAdapter;

public interface OtlpGrpcAdapter extends TransportAdapter {
    int listenPort(); // default 4317
}
