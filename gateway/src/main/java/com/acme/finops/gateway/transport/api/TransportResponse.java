package com.acme.finops.gateway.transport.api;

public sealed interface TransportResponse permits TransportAck, TransportNack { }
