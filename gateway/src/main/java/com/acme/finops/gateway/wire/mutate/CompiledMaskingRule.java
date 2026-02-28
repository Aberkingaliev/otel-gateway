package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.policy.PolicyActionType;
import com.acme.finops.gateway.transport.api.SignalKind;

import java.util.Objects;

record CompiledMaskingRule(
    String ruleId,
    int priority,
    boolean enabled,
    SignalKind signalKind,
    boolean allSignals,
    PolicyActionType actionType,
    byte[] redactionToken,
    MismatchMode mismatchMode,
    ValueSpanSelector selector
) {
    CompiledMaskingRule {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(signalKind, "signalKind");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(mismatchMode, "mismatchMode");
        Objects.requireNonNull(selector, "selector");
        redactionToken = redactionToken == null ? new byte[0] : redactionToken.clone();
    }

    boolean appliesTo(SignalKind signal) {
        return allSignals || signalKind == signal;
    }
}
