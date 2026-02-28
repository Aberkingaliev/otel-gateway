package com.acme.finops.gateway.wire.mutate;

public interface ControlPlanePolicyProvider {
    CompiledMaskingSnapshot activeSnapshot();
}
