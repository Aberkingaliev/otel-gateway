package com.acme.finops.gateway.transport.grpc;

public interface GrpcStatusMapper {
    int toGrpcStatus(int gatewayErrorCode);
}
