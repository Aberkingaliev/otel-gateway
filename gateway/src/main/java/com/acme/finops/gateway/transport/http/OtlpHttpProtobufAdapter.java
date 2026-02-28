package com.acme.finops.gateway.transport.http;

import com.acme.finops.gateway.transport.api.TransportAdapter;

public interface OtlpHttpProtobufAdapter extends TransportAdapter {
    int listenPort(); // default 4318
}
