package com.acme.finops.gateway.util;

import com.acme.finops.gateway.memory.PacketDescriptor;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.memory.PacketRefImpl;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpContentTypesTest {

    @Test
    void shouldDetectJsonPayload() {
        PacketRef ref = packetRef(new byte[]{' ', '\n', '{', '}', '\n'});
        try {
            assertEquals(OtlpContentTypes.JSON, OtlpContentTypes.detectPayload(ref));
        } finally {
            ref.release();
        }
    }

    @Test
    void shouldDetectProtobufPayload() {
        PacketRef ref = packetRef(new byte[]{0x0A, 0x01, 0x01});
        try {
            assertEquals(OtlpContentTypes.PROTOBUF, OtlpContentTypes.detectPayload(ref));
        } finally {
            ref.release();
        }
    }

    @Test
    void shouldResolveDeclaredOrDetect() {
        PacketRef ref = packetRef(new byte[]{'{', '}', '\n'});
        try {
            assertEquals(OtlpContentTypes.JSON, OtlpContentTypes.resolveDeclaredOrDetect(null, ref));
            assertEquals("application/custom", OtlpContentTypes.resolveDeclaredOrDetect("application/custom", ref));
        } finally {
            ref.release();
        }
    }

    @Test
    void shouldValidateSupportedRequestContentTypes() {
        assertTrue(OtlpContentTypes.isSupportedRequestContentType("application/json"));
        assertTrue(OtlpContentTypes.isSupportedRequestContentType("Application/JSON; charset=utf-8"));
        assertTrue(OtlpContentTypes.isSupportedRequestContentType("application/x-protobuf"));
        assertTrue(OtlpContentTypes.isSupportedRequestContentType("application/protobuf"));
        assertFalse(OtlpContentTypes.isSupportedRequestContentType("text/plain"));
        assertFalse(OtlpContentTypes.isSupportedRequestContentType(null));
    }

    @Test
    void shouldNormalizeResponseContentType() {
        assertEquals(OtlpContentTypes.JSON, OtlpContentTypes.normalizeResponseContentType("application/json"));
        assertEquals(OtlpContentTypes.JSON, OtlpContentTypes.normalizeResponseContentType("Application/JSON; charset=utf-8"));
        assertEquals(OtlpContentTypes.PROTOBUF, OtlpContentTypes.normalizeResponseContentType("application/x-protobuf"));
        assertEquals(OtlpContentTypes.PROTOBUF, OtlpContentTypes.normalizeResponseContentType(null));
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
}
