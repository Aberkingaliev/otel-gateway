package com.acme.finops.gateway.wire.cursor;

public final class DefaultEvalScratch implements EvalScratch {
    private final int[] intStack;
    private final long[] longStack;
    private final byte[] tempBytes;

    public DefaultEvalScratch() {
        this(32, 32, 4096);
    }

    public DefaultEvalScratch(int intStackSize, int longStackSize, int tempBytesSize) {
        this.intStack = new int[intStackSize];
        this.longStack = new long[longStackSize];
        this.tempBytes = new byte[tempBytesSize];
    }

    @Override
    public int[] intStack() {
        return intStack;
    }

    @Override
    public long[] longStack() {
        return longStack;
    }

    @Override
    public byte[] tempBytes() {
        return tempBytes;
    }
}
