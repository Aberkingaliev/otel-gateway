package com.acme.finops.gateway.wire.mutate;

public record InplaceMask(int absoluteOffset, int length, byte maskByte) implements MutationOp {}
