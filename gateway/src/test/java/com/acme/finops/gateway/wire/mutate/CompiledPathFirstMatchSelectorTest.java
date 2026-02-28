package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.memory.PacketDescriptor;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.memory.PacketRefImpl;
import com.acme.finops.gateway.policy.CompileResult;
import com.acme.finops.gateway.policy.OtlpPathCompiler;
import com.acme.finops.gateway.policy.PathStringPool;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.wire.SchemaId;
import com.acme.finops.gateway.wire.SignalType;
import com.acme.finops.gateway.wire.cursor.BytecodeCompiledPathEvaluator;
import com.acme.finops.gateway.wire.cursor.DefaultEvalScratch;
import com.acme.finops.gateway.wire.cursor.FastWireCursor;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CompiledPathFirstMatchSelectorTest {

    @Test
    void shouldCollectAllWildcardEventNames() {
        PathStringPool pool = new PathStringPool();
        OtlpPathCompiler compiler = new OtlpPathCompiler(pool);
        CompileResult compiled = compiler.compile(
            "scopeSpans[*].spans[*].events[*].name",
            new SchemaId("otlp", "trace", "v1"),
            SignalType.TRACES
        );
        CompileResult.Success success = assertInstanceOf(CompileResult.Success.class, compiled);

        CompiledPathFirstMatchSelector selector = new CompiledPathFirstMatchSelector(
            new BytecodeCompiledPathEvaluator(pool::resolve),
            success.compiledPath()
        );

        byte[] payload = payloadWithEvents("event-a", "event-b");
        PacketRef packetRef = packetRef(payload);
        try {
            ValueSpanCollector collector = new ValueSpanCollector(8);
            int matched = selector.collect(packetRef, new FastWireCursor(), new DefaultEvalScratch(), collector);
            assertEquals(2, matched);
            assertEquals(2, collector.count());

            String first = new String(payload, collector.offsetAt(0), collector.lengthAt(0), StandardCharsets.UTF_8);
            String second = new String(payload, collector.offsetAt(1), collector.lengthAt(1), StandardCharsets.UTF_8);
            assertEquals("event-a", first);
            assertEquals("event-b", second);
        } finally {
            packetRef.release();
        }
    }

    private static PacketRef packetRef(byte[] payload) {
        PacketDescriptor descriptor = new PacketDescriptor(
            1L,
            1L,
            SignalKind.TRACES,
            ProtocolKind.OTLP_HTTP_PROTO,
            0,
            payload.length,
            System.nanoTime()
        );
        return new PacketRefImpl(1L, descriptor, MemorySegment.ofArray(payload), 0, payload.length);
    }

    private static byte[] payloadWithEvents(String first, String second) {
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
