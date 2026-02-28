package com.acme.finops.gateway.memory;

public interface SlabPool {
    SlabLease acquire(int minBytes) throws OutOfSlabMemoryException;
    void recycle(SlabLease lease);
    SlabPoolStats stats();
}

