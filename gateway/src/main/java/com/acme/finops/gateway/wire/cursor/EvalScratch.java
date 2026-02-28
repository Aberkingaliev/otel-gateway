package com.acme.finops.gateway.wire.cursor;

public interface EvalScratch {
    int[] intStack();
    long[] longStack();
    byte[] tempBytes();
}
