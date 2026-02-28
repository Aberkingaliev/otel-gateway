package com.acme.finops.gateway.queue;

public interface BoundedRing<E> {
    int capacity();
    int sizeApprox();
    OfferResult offer(E e);
    E poll();
    default void close() {}
}
