package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.memory.PacketDescriptor;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.memory.PacketRefImpl;
import com.acme.finops.gateway.policy.PolicyActionType;
import com.acme.finops.gateway.policy.PolicyDecision;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PolicyDrivenMutationPlannerTest {

    @Test
    void shouldBuildInplaceOnlyPlanWithoutReframe() {
        CompiledMaskingRule rule = new CompiledMaskingRule(
            "mask-1",
            10,
            true,
            SignalKind.TRACES,
            true,
            PolicyActionType.REDACT_MASK,
            "********".getBytes(),
            MismatchMode.SKIP,
            fixedSpanSelector(4, 8)
        );
        PolicyDrivenMutationPlanner planner = new PolicyDrivenMutationPlanner(
            () -> new CompiledMaskingSnapshot(1L, true, List.of(rule)),
            16
        );

        PacketRef packetRef = packetRef();
        MutationPlan plan;
        try {
            plan = planner.plan(packetRef, allowDecision());
        } finally {
            packetRef.release();
        }

        assertEquals(MutationPlan.PlanMode.INPLACE_ONLY, plan.mode());
        assertFalse(plan.requiresReframe());
        assertEquals(1, plan.passAOps().size());
    }

    @Test
    void shouldSkipMaskWhenLengthMismatchInStrictInplaceMode() {
        CompiledMaskingRule rule = new CompiledMaskingRule(
            "mask-mismatch-skip",
            10,
            true,
            SignalKind.TRACES,
            true,
            PolicyActionType.REDACT_MASK,
            "********".getBytes(), // 8
            MismatchMode.SKIP,
            fixedSpanSelector(2, 6) // 6
        );
        PolicyDrivenMutationPlanner planner = new PolicyDrivenMutationPlanner(
            () -> new CompiledMaskingSnapshot(1L, true, List.of(rule)),
            16
        );

        PacketRef packetRef = packetRef();
        MutationPlan plan;
        try {
            plan = planner.plan(packetRef, allowDecision());
        } finally {
            packetRef.release();
        }

        assertEquals(MutationPlan.PlanMode.NOOP, plan.mode());
        assertEquals(0, plan.passAOps().size());
    }

    @Test
    void shouldDropWhenMismatchModeIsFailClosed() {
        CompiledMaskingRule rule = new CompiledMaskingRule(
            "mask-mismatch-drop",
            10,
            true,
            SignalKind.TRACES,
            true,
            PolicyActionType.REDACT_MASK,
            "*****".getBytes(), // 5
            MismatchMode.FAIL_CLOSED,
            fixedSpanSelector(1, 7) // 7
        );
        PolicyDrivenMutationPlanner planner = new PolicyDrivenMutationPlanner(
            () -> new CompiledMaskingSnapshot(1L, true, List.of(rule)),
            16
        );

        PacketRef packetRef = packetRef();
        MutationPlan plan;
        try {
            plan = planner.plan(packetRef, allowDecision());
        } finally {
            packetRef.release();
        }

        assertEquals(MutationPlan.PlanMode.DROP, plan.mode());
    }

    @Test
    void shouldBuildDropPlanWhenDropRuleMatches() {
        CompiledMaskingRule rule = new CompiledMaskingRule(
            "drop-1",
            5,
            true,
            SignalKind.TRACES,
            true,
            PolicyActionType.DROP,
            new byte[0],
            MismatchMode.SKIP,
            fixedSpanSelector(0, 1)
        );
        PolicyDrivenMutationPlanner planner = new PolicyDrivenMutationPlanner(
            () -> new CompiledMaskingSnapshot(1L, true, List.of(rule)),
            16
        );

        PacketRef packetRef = packetRef();
        MutationPlan plan;
        try {
            plan = planner.plan(packetRef, allowDecision());
        } finally {
            packetRef.release();
        }

        assertEquals(MutationPlan.PlanMode.DROP, plan.mode());
        assertEquals(0, plan.targetLength());
    }

    private static ValueSpanSelector fixedSpanSelector(int offset, int length) {
        return (packetRef, cursor, scratch, collector) -> {
            collector.reset();
            collector.add(offset, length);
            return collector.count();
        };
    }

    private static PolicyDecision allowDecision() {
        return PolicyDecision.routeDefault(42L);
    }

    private static PacketRef packetRef() {
        byte[] payload = new byte[64];
        PacketDescriptor descriptor = new PacketDescriptor(
            1L,
            42L,
            SignalKind.TRACES,
            ProtocolKind.OTLP_HTTP_PROTO,
            0,
            payload.length,
            System.nanoTime()
        );
        return new PacketRefImpl(1L, descriptor, MemorySegment.ofArray(payload), 0, payload.length);
    }
}
