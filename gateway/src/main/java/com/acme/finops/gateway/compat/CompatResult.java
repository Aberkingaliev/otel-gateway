package com.acme.finops.gateway.compat;

public sealed interface CompatResult permits CompatResult.Compatible, CompatResult.RewriteRequired, CompatResult.Incompatible {
    record Compatible(long requestId) implements CompatResult {}
    record RewriteRequired(long requestId, int rewritePlanId) implements CompatResult {}
    record Incompatible(long requestId, int reasonCode) implements CompatResult {}
}
