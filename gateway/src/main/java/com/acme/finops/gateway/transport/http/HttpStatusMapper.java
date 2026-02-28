package com.acme.finops.gateway.transport.http;

public interface HttpStatusMapper {
    int toHttpStatus(int gatewayErrorCode);
}
