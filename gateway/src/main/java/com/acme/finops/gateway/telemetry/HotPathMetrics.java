package com.acme.finops.gateway.telemetry;

public interface HotPathMetrics {
    void incPacketsIn(long n);
    void incPacketsOut(long n);
    void incDropped(long n, int reasonCode);
    void incParseErrors(long n, int errorCode);
    void observeParseNanos(long nanos);
    void observeEndToEndNanos(long nanos);
    void setQueueDepth(int depth);
}
