package com.acme.finops.gateway.telemetry;

public final class NoopHotPathMetrics implements HotPathMetrics {
    public static final NoopHotPathMetrics INSTANCE = new NoopHotPathMetrics();

    private NoopHotPathMetrics() {
    }

    @Override
    public void incPacketsIn(long n) {
    }

    @Override
    public void incPacketsOut(long n) {
    }

    @Override
    public void incDropped(long n, int reasonCode) {
    }

    @Override
    public void incParseErrors(long n, int errorCode) {
    }

    @Override
    public void observeParseNanos(long nanos) {
    }

    @Override
    public void observeEndToEndNanos(long nanos) {
    }

    @Override
    public void setQueueDepth(int depth) {
    }
}
