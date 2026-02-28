package com.acme.finops.gateway.wire.mutate;

public record SkipSlice(int offset, int length, DropReason reason) implements MutationOp {}
