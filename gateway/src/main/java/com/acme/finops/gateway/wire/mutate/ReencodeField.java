package com.acme.finops.gateway.wire.mutate;

public record ReencodeField(int fieldStart, int fieldEnd, byte[] encodedField) implements MutationOp {}
