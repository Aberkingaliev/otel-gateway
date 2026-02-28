package com.acme.finops.gateway.queue;

public sealed interface OfferResult permits OfferResult.Ok, OfferResult.Full, OfferResult.Closed {
    record Ok(long seq) implements OfferResult {}
    record Full(int depth, int capacity) implements OfferResult {}
    record Closed() implements OfferResult {}
}
