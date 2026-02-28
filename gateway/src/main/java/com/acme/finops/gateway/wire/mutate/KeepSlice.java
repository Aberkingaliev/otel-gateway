package com.acme.finops.gateway.wire.mutate;

public record KeepSlice(int offset, int length) implements MutationOp {}
