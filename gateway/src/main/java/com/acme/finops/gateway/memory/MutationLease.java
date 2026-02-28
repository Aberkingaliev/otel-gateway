package com.acme.finops.gateway.memory;

import java.lang.foreign.MemorySegment;

public interface MutationLease extends AutoCloseable {
    MemorySegment mutableSegment();
    int offset();
    int length();
    @Override void close();
}
