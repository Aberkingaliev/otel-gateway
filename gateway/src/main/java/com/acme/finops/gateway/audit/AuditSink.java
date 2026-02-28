package com.acme.finops.gateway.audit;

/**
 * Asynchronous sink for audit events produced by the gateway pipeline.
 *
 * <p>Implementations must be thread-safe. Events may be buffered and flushed
 * asynchronously; callers must not assume synchronous delivery.
 */
public interface AuditSink {
    /** Enqueues an audit event for asynchronous delivery. Never blocks the caller. */
    void append(AuditEvent event);
}
