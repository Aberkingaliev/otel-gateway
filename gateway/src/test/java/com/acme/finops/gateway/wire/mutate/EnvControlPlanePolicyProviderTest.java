package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.policy.PolicyActionType;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.util.GatewayEnvKeys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvControlPlanePolicyProviderTest {

    @Test
    void shouldDisableSnapshotWhenMaskingFlagIsFalse() {
        EnvControlPlanePolicyProvider provider = EnvControlPlanePolicyProvider.fromEnvironment();
        provider.refresh(Map.of(GatewayEnvKeys.GATEWAY_MASKING_ENABLED, "false"));

        CompiledMaskingSnapshot snapshot = provider.activeSnapshot();
        assertFalse(snapshot.enabled());
        assertTrue(snapshot.rules().isEmpty());
    }

    @Test
    void shouldEnableSnapshotWithEmptyRulesWhenConfigured() {
        EnvControlPlanePolicyProvider provider = EnvControlPlanePolicyProvider.fromEnvironment();
        provider.refresh(Map.of(
            GatewayEnvKeys.GATEWAY_MASKING_ENABLED, "true",
            GatewayEnvKeys.GATEWAY_MASKING_RULES, "   "
        ));

        CompiledMaskingSnapshot snapshot = provider.activeSnapshot();
        assertTrue(snapshot.enabled());
        assertTrue(snapshot.rules().isEmpty());
    }

    @Test
    void shouldCompileAndSortRulesFromEnvironment() {
        EnvControlPlanePolicyProvider provider = EnvControlPlanePolicyProvider.fromEnvironment();

        Map<String, String> env = new HashMap<>();
        env.put(GatewayEnvKeys.GATEWAY_MASKING_ENABLED, "true");
        env.put(GatewayEnvKeys.GATEWAY_MASKING_RULES, String.join(";",
            "badRule",
            "r2|TRACES|UNKNOWN|resource.attributes.tenant_id",
            "r3|TRACES|DROP|resource.attributes.tenant_id||||false",
            "r4|TRACES|REDACT_MASK|resource.attributes.tenant_id|***|10|fail_closed|true",
            "r5|TRACES|DROP|scopeSpans[*].spans[*].attributes.tenant_id||5|skip|true",
            "r6||DROP|scopeSpans[*].spans[*].attributes.tenant_id|||skip|true"
        ));

        provider.refresh(env);

        CompiledMaskingSnapshot snapshot = provider.activeSnapshot();
        assertTrue(snapshot.enabled());

        List<CompiledMaskingRule> rules = snapshot.rules();
        assertEquals(2, rules.size(), rules::toString);

        CompiledMaskingRule first = rules.get(0);
        assertEquals("r5", first.ruleId());
        assertEquals(5, first.priority());
        assertEquals(SignalKind.TRACES, first.signalKind());
        assertFalse(first.allSignals());
        assertEquals(PolicyActionType.DROP, first.actionType());
        assertEquals(0, first.redactionToken().length);
        assertEquals(MismatchMode.SKIP, first.mismatchMode());
        assertInstanceOf(CompiledPathFirstMatchSelector.class, first.selector());

        CompiledMaskingRule second = rules.get(1);
        assertEquals("r4", second.ruleId());
        assertEquals(10, second.priority());
        assertEquals(PolicyActionType.REDACT_MASK, second.actionType());
        assertEquals(MismatchMode.FAIL_CLOSED, second.mismatchMode());
        assertEquals("***", new String(second.redactionToken(), StandardCharsets.UTF_8));
        assertInstanceOf(ResourceAttributeSpanSelector.class, second.selector());
    }
}

