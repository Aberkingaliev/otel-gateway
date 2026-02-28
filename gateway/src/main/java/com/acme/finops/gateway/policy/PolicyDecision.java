package com.acme.finops.gateway.policy;

import java.util.Objects;

public record PolicyDecision(
    long requestId,
    DecisionAction action,
    int reasonCode
) {
    public PolicyDecision {
        Objects.requireNonNull(action, "action");
    }

    public static PolicyDecision pass(long requestId) {
        return new PolicyDecision(requestId, DecisionAction.PASS, 0);
    }

    public static PolicyDecision routeDefault(long requestId) {
        return new PolicyDecision(requestId, DecisionAction.ROUTE_DEFAULT, 0);
    }

    public static PolicyDecision drop(long requestId, int reasonCode) {
        return new PolicyDecision(requestId, DecisionAction.DROP, reasonCode);
    }
}
