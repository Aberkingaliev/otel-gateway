package com.acme.finops.gateway.memory;

import java.lang.foreign.MemorySegment;

public interface SlabLease {
    long slabId();
    MemorySegment segment();
    int capacity();
    boolean isRecycled();
}
