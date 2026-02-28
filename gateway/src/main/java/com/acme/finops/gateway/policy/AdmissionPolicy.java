package com.acme.finops.gateway.policy;

public interface AdmissionPolicy {
    PolicyMode mode();
    PolicyDecision evaluate(PolicyContext context);
}
