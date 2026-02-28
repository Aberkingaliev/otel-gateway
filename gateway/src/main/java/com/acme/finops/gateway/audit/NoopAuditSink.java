package com.acme.finops.gateway.audit;

public final class NoopAuditSink implements AuditSink {
    public static final NoopAuditSink INSTANCE = new NoopAuditSink();

    private NoopAuditSink() {
    }

    @Override
    public void append(AuditEvent event) {
    }
}
