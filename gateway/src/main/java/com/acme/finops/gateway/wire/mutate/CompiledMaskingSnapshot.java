package com.acme.finops.gateway.wire.mutate;

import java.util.List;

public record CompiledMaskingSnapshot(
    long revision,
    boolean enabled,
    List<CompiledMaskingRule> rules
) {
    public CompiledMaskingSnapshot {
        rules = List.copyOf(rules == null ? List.of() : rules);
    }

    public static CompiledMaskingSnapshot disabled() {
        return new CompiledMaskingSnapshot(0L, false, List.of());
    }
}
