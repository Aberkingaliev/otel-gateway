package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.memory.PacketDescriptor;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.memory.PacketRefImpl;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.wire.cursor.DefaultEvalScratch;
import com.acme.finops.gateway.wire.cursor.FastWireCursor;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceAttributeSpanSelectorTest {

    @Test
    void shouldCollectSpanFromProtobufResourceAttribute() {
        PacketRef packetRef = packetRef(exportTraceRequestWithAttribute("tenant_id", "black_list"));
        try {
            ResourceAttributeSpanSelector selector = new ResourceAttributeSpanSelector("tenant_id");
            ValueSpanCollector collector = new ValueSpanCollector(8);
            int matched = selector.collect(packetRef, new FastWireCursor(), new DefaultEvalScratch(), collector);
            assertTrue(matched > 0);
            assertEquals(1, collector.count());
        } finally {
            packetRef.release();
        }
    }

    @Test
    void shouldCollectSpanFromJsonResourceAttribute() {
        byte[] payload = """
            {
              "resourceSpans": [{
                "resource": {
                  "attributes": [
                    {"key":"tenant_id","value":{"stringValue":"black_list"}},
                    {"key":"service.name","value":{"stringValue":"gateway"}}
                  ]
                }
              }]
            }
            """.getBytes(StandardCharsets.UTF_8);
        PacketRef packetRef = packetRef(payload);
        try {
            ResourceAttributeSpanSelector selector = new ResourceAttributeSpanSelector("tenant_id");
            ValueSpanCollector collector = new ValueSpanCollector(8);
            int matched = selector.collect(packetRef, new FastWireCursor(), new DefaultEvalScratch(), collector);
            assertEquals(1, matched);
            int start = collector.offsetAt(0);
            int len = collector.lengthAt(0);
            String value = new String(payload, start, len, StandardCharsets.UTF_8);
            assertEquals("black_list", value);
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
