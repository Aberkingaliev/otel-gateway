package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.memory.PacketDescriptor;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.memory.PacketRefImpl;
import com.acme.finops.gateway.policy.CompileResult;
import com.acme.finops.gateway.policy.OtlpPathCompiler;
import com.acme.finops.gateway.policy.PathStringPool;
import com.acme.finops.gateway.policy.PolicyDecision;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.wire.SchemaId;
import com.acme.finops.gateway.wire.SignalType;
import com.acme.finops.gateway.wire.cursor.BytecodeCompiledPathEvaluator;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class HealthcheckSuccessDropPlannerTest {

    @Test
    void shouldBuildDropPlanWhenCompiledPathMatches() {
        PathStringPool pool = new PathStringPool();
        OtlpPathCompiler compiler = new OtlpPathCompiler(pool);
        CompileResult compile = compiler.compile(
            "resource.attributes.tenant_id",
            new SchemaId("otlp", "trace", "v1"),
            SignalType.TRACES
        );
        CompileResult.Success success = assertInstanceOf(CompileResult.Success.class, compile);

        HealthcheckSuccessDropPlanner planner = new HealthcheckSuccessDropPlanner(
            new BytecodeCompiledPathEvaluator(pool::resolve),
            success.compiledPath()
        );

        PacketRef packetRef = packet(exportTraceRequestWithAttribute("tenant_id", "black_list"));
        PolicyDecision decision = PolicyDecision.routeDefault(42L);

        MutationPlan plan = planner.plan(packetRef, decision);
        assertEquals(MutationPlan.PlanMode.DROP, plan.mode());
        assertEquals(0, plan.targetLength());
    }

    @Test
    void shouldBuildNoopPlanWhenCompiledPathNotFound() {
        PathStringPool pool = new PathStringPool();
        OtlpPathCompiler compiler = new OtlpPathCompiler(pool);
        CompileResult compile = compiler.compile(
            "resource.attributes.tenant_id",
            new SchemaId("otlp", "trace", "v1"),
            SignalType.TRACES
        );
        CompileResult.Success success = assertInstanceOf(CompileResult.Success.class, compile);

        HealthcheckSuccessDropPlanner planner = new HealthcheckSuccessDropPlanner(
            new BytecodeCompiledPathEvaluator(pool::resolve),
            success.compiledPath()
        );

        PacketRef packetRef = packet(exportTraceRequestWithAttribute("service.name", "another_key"));
        PolicyDecision decision = PolicyDecision.routeDefault(43L);

        MutationPlan plan = planner.plan(packetRef, decision);
        assertEquals(MutationPlan.PlanMode.NOOP, plan.mode());
        assertEquals(packetRef.length(), plan.targetLength());
    }

    private static PacketRef packet(byte[] payload) {
        PacketDescriptor descriptor = new PacketDescriptor(
            1L,
            1L,
            SignalKind.TRACES,
            ProtocolKind.OTLP_GRPC,
            0,
            payload.length,
            System.nanoTime()
        );
        return new PacketRefImpl(1L, descriptor, MemorySegment.ofArray(payload), 0, payload.length);
    }

    private static byte[] exportTraceRequestWithAttribute(String attributeKey, String attributeValue) {
        byte[] valueBytes = attributeValue.getBytes(StandardCharsets.UTF_8);
        byte[] anyValue = concat(new byte[]{0x0A, (byte) valueBytes.length}, valueBytes);

        byte[] key = attributeKey.getBytes(StandardCharsets.UTF_8);
        byte[] keyField = concat(new byte[]{0x0A, (byte) key.length}, key);
        byte[] valueField = concat(new byte[]{0x12, (byte) anyValue.length}, anyValue);
        byte[] keyValue = concat(keyField, valueField);

        byte[] resource = concat(new byte[]{0x0A, (byte) keyValue.length}, keyValue);
        byte[] resourceSpans = concat(new byte[]{0x0A, (byte) resource.length}, resource);
        return concat(new byte[]{0x0A, (byte) resourceSpans.length}, resourceSpans);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
