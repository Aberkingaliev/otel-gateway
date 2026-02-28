package com.acme.finops.gateway.policy;

import com.acme.finops.gateway.memory.PacketDescriptor;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.memory.PacketRefImpl;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.wire.SchemaId;
import com.acme.finops.gateway.wire.SignalType;
import com.acme.finops.gateway.wire.cursor.BytecodeCompiledPathEvaluator;
import com.acme.finops.gateway.wire.cursor.DefaultEvalScratch;
import com.acme.finops.gateway.wire.cursor.EvalResult;
import com.acme.finops.gateway.wire.cursor.FastWireCursor;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledPathPipelineTest {

    @Test
    void shouldCompileAndEvaluateTenantIdFromTraceExportPayload() {
        PathStringPool pool = new PathStringPool();
        OtlpPathCompiler compiler = new OtlpPathCompiler(pool);

        CompileResult result = compiler.compile(
            "resource.attributes.tenant_id",
            new SchemaId("otlp", "trace", "v1"),
            SignalType.TRACES
        );

        CompileResult.Success success = assertInstanceOf(CompileResult.Success.class, result);
        BytecodeCompiledPathEvaluator evaluator = new BytecodeCompiledPathEvaluator(pool::resolve);

        FastWireCursor cursor = new FastWireCursor();
        byte[] payload = exportTraceRequestWithTenant("black_list");
        cursor.reset(MemorySegment.ofArray(payload), 0, payload.length);

        DefaultEvalScratch scratch = new DefaultEvalScratch();
        EvalResult eval = evaluator.evaluate(success.compiledPath(), cursor, scratch);

        assertInstanceOf(EvalResult.MatchFound.class, eval);
        String extracted = new String(scratch.tempBytes(), 0, scratch.intStack()[0], StandardCharsets.UTF_8);
        assertEquals("black_list", extracted);
    }

    @Test
    void shouldFailCompileOnEmptyOrMalformedPaths() {
        PathStringPool pool = new PathStringPool();
        OtlpPathCompiler compiler = new OtlpPathCompiler(pool);

        CompileResult empty = compiler.compile("", new SchemaId("otlp", "trace", "v1"), SignalType.TRACES);
        assertInstanceOf(CompileResult.Failure.class, empty);

        CompileResult malformed = compiler.compile("resource.attributes[", new SchemaId("otlp", "trace", "v1"), SignalType.TRACES);
        assertInstanceOf(CompileResult.Failure.class, malformed);

        CompileResult missingKey = compiler.compile("resource.attributes", new SchemaId("otlp", "trace", "v1"), SignalType.TRACES);
        CompileResult.Failure missingKeyFailure = assertInstanceOf(CompileResult.Failure.class, missingKey);
        assertTrue(missingKeyFailure.code().contains("MAP_KEY_REQUIRED"));
    }

    @Test
    void shouldEvaluateNoMatchWhenKeyIsAbsent() {
        PathStringPool pool = new PathStringPool();
        OtlpPathCompiler compiler = new OtlpPathCompiler(pool);
        CompileResult result = compiler.compile(
            "resource.attributes.tenant_id",
            new SchemaId("otlp", "trace", "v1"),
            SignalType.TRACES
        );
        CompileResult.Success success = assertInstanceOf(CompileResult.Success.class, result);

        BytecodeCompiledPathEvaluator evaluator = new BytecodeCompiledPathEvaluator(pool::resolve);
        FastWireCursor cursor = new FastWireCursor();
        byte[] payload = exportTraceRequestWithAttr("other_key", "value");
        cursor.reset(MemorySegment.ofArray(payload), 0, payload.length);

        DefaultEvalScratch scratch = new DefaultEvalScratch();
        EvalResult eval = evaluator.evaluate(success.compiledPath(), cursor, scratch);
        assertInstanceOf(EvalResult.NoMatch.class, eval);
    }

    @Test
    void shouldMatchWildcardAcrossRepeatedScopeSpansAndSpans() {
        PathStringPool pool = new PathStringPool();
        OtlpPathCompiler compiler = new OtlpPathCompiler(pool);

        CompileResult result = compiler.compile(
            "scopeSpans[*].spans[*].attributes.tenant_id",
            new SchemaId("otlp", "trace", "v1"),
            SignalType.TRACES
        );

        CompileResult.Success success = assertInstanceOf(CompileResult.Success.class, result);
        BytecodeCompiledPathEvaluator evaluator = new BytecodeCompiledPathEvaluator(pool::resolve);

        FastWireCursor cursor = new FastWireCursor();
        byte[] payload = exportTraceRequestWithRepeatedScopeSpansAndTenantInSecondScope();
        cursor.reset(MemorySegment.ofArray(payload), 0, payload.length);

        DefaultEvalScratch scratch = new DefaultEvalScratch();
        EvalResult eval = evaluator.evaluate(success.compiledPath(), cursor, scratch);

        assertInstanceOf(EvalResult.MatchFound.class, eval);
        String extracted = new String(scratch.tempBytes(), 0, scratch.intStack()[0], StandardCharsets.UTF_8);
        assertEquals("black_list", extracted);
    }

    @Test
    void shouldCollectMultipleMatchesForWildcardPath() {
        PathStringPool pool = new PathStringPool();
        OtlpPathCompiler compiler = new OtlpPathCompiler(pool);

        CompileResult result = compiler.compile(
            "scopeSpans[*].spans[*].events[*].name",
            new SchemaId("otlp", "trace", "v1"),
            SignalType.TRACES
        );

        CompileResult.Success success = assertInstanceOf(CompileResult.Success.class, result);
        BytecodeCompiledPathEvaluator evaluator = new BytecodeCompiledPathEvaluator(pool::resolve);

        byte[] payload = exportTraceRequestWithSpanEvents("event-a", "event-b");
        DefaultEvalScratch scratch = new DefaultEvalScratch();
        List<String> names = new ArrayList<>();
        int matches = evaluator.evaluateAll(
            success.compiledPath(),
            MemorySegment.ofArray(payload),
            0,
            payload.length,
            scratch,
            (valueOffset, valueLength, terminalTypeCode) -> {
                names.add(new String(payload, valueOffset, valueLength, StandardCharsets.UTF_8));
                return true;
            }
        );

        assertEquals(2, matches);
        assertEquals(List.of("event-a", "event-b"), names);
    }

    @Test
    void shouldStopEvaluateAllWhenConsumerReturnsFalse() {
        PathStringPool pool = new PathStringPool();
        OtlpPathCompiler compiler = new OtlpPathCompiler(pool);

        CompileResult result = compiler.compile(
            "scopeSpans[*].spans[*].events[*].name",
            new SchemaId("otlp", "trace", "v1"),
            SignalType.TRACES
        );

        CompileResult.Success success = assertInstanceOf(CompileResult.Success.class, result);
        BytecodeCompiledPathEvaluator evaluator = new BytecodeCompiledPathEvaluator(pool::resolve);

        byte[] payload = exportTraceRequestWithSpanEvents("event-a", "event-b");
        DefaultEvalScratch scratch = new DefaultEvalScratch();
        List<String> names = new ArrayList<>();
        int matches = evaluator.evaluateAll(
            success.compiledPath(),
            MemorySegment.ofArray(payload),
            0,
            payload.length,
            scratch,
            (valueOffset, valueLength, terminalTypeCode) -> {
                names.add(new String(payload, valueOffset, valueLength, StandardCharsets.UTF_8));
                return false;
            }
        );

        assertEquals(1, matches);
        assertEquals(List.of("event-a"), names);
    }

    private static byte[] exportTraceRequestWithTenant(String tenant) {
        byte[] tenantBytes = tenant.getBytes(StandardCharsets.UTF_8);

        byte[] anyValue = concat(
            new byte[]{0x0A, (byte) tenantBytes.length},
            tenantBytes
        );

        byte[] key = "tenant_id".getBytes(StandardCharsets.UTF_8);
        byte[] keyField = concat(new byte[]{0x0A, (byte) key.length}, key);
        byte[] valueField = concat(new byte[]{0x12, (byte) anyValue.length}, anyValue);
        byte[] keyValue = concat(keyField, valueField);

        byte[] resource = concat(new byte[]{0x0A, (byte) keyValue.length}, keyValue);
        byte[] resourceSpans = concat(new byte[]{0x0A, (byte) resource.length}, resource);
        return concat(new byte[]{0x0A, (byte) resourceSpans.length}, resourceSpans);
    }

    private static byte[] exportTraceRequestWithAttr(String keyText, String valueText) {
        byte[] valueBytes = valueText.getBytes(StandardCharsets.UTF_8);
        byte[] anyValue = concat(new byte[]{0x0A, (byte) valueBytes.length}, valueBytes);

        byte[] key = keyText.getBytes(StandardCharsets.UTF_8);
        byte[] keyField = concat(new byte[]{0x0A, (byte) key.length}, key);
        byte[] valueField = concat(new byte[]{0x12, (byte) anyValue.length}, anyValue);
        byte[] keyValue = concat(keyField, valueField);

        byte[] resource = concat(new byte[]{0x0A, (byte) keyValue.length}, keyValue);
        byte[] resourceSpans = concat(new byte[]{0x0A, (byte) resource.length}, resource);
        return concat(new byte[]{0x0A, (byte) resourceSpans.length}, resourceSpans);
    }

    private static byte[] exportTraceRequestWithRepeatedScopeSpansAndTenantInSecondScope() {
        byte[] emptySpan = new byte[0];
        byte[] spanWithTenant = spanWithAttribute("tenant_id", "black_list");

        byte[] scopeSpans1 = concat(new byte[]{0x12, (byte) emptySpan.length}, emptySpan);
        byte[] scopeSpans2 = concat(new byte[]{0x12, (byte) spanWithTenant.length}, spanWithTenant);

        byte[] resourceSpans1 = concat(new byte[]{0x12, (byte) scopeSpans1.length}, scopeSpans1);
        byte[] resourceSpans2 = concat(new byte[]{0x12, (byte) scopeSpans2.length}, scopeSpans2);

        byte[] root1 = concat(new byte[]{0x0A, (byte) resourceSpans1.length}, resourceSpans1);
        byte[] root2 = concat(new byte[]{0x0A, (byte) resourceSpans2.length}, resourceSpans2);
        return concat(root1, root2);
    }

    private static byte[] exportTraceRequestWithSpanEvents(String first, String second) {
        byte[] e1 = event(first);
        byte[] e2 = event(second);
        byte[] span = concat(
            concat(new byte[]{0x5A, (byte) e1.length}, e1),
            concat(new byte[]{0x5A, (byte) e2.length}, e2)
        );
        byte[] scopeSpans = concat(new byte[]{0x12, (byte) span.length}, span);
        byte[] resourceSpans = concat(new byte[]{0x12, (byte) scopeSpans.length}, scopeSpans);
        return concat(new byte[]{0x0A, (byte) resourceSpans.length}, resourceSpans);
    }

    private static byte[] spanWithAttribute(String key, String value) {
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] anyValue = concat(new byte[]{0x0A, (byte) valueBytes.length}, valueBytes);

        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] keyField = concat(new byte[]{0x0A, (byte) keyBytes.length}, keyBytes);
        byte[] valueField = concat(new byte[]{0x12, (byte) anyValue.length}, anyValue);
        byte[] keyValue = concat(keyField, valueField);
        return concat(new byte[]{0x4A, (byte) keyValue.length}, keyValue);
    }

    private static byte[] event(String name) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        return concat(new byte[]{0x12, (byte) nameBytes.length}, nameBytes);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
