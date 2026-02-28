package com.acme.finops.gateway.util;

import com.acme.finops.gateway.memory.PacketRef;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * OTLP content-type helpers shared by HTTP and proxy ingress paths.
 *
 * <p>Hot-path constraints:
 * branch-only scanning over payload bytes, no regex/streams and no extra arrays.</p>
 */
public final class OtlpContentTypes {
    public static final String JSON = "application/json";
    public static final String PROTOBUF = "application/x-protobuf";
    public static final String PROTOBUF_ALT = "application/protobuf";

    private OtlpContentTypes() {
    }

    public static String detectPayload(PacketRef ref) {
        MemorySegment payload = ref.segment().asSlice(ref.offset(), ref.length());
        for (int i = 0; i < ref.length(); i++) {
            byte b = payload.get(ValueLayout.JAVA_BYTE, i);
            if (isAsciiWhitespace(b)) {
                continue;
            }
            if (b == '{' || b == '[') {
                return JSON;
            }
            break;
        }
        return PROTOBUF;
    }

    public static String resolveDeclaredOrDetect(String declared, PacketRef ref) {
        if (declared == null || declared.isBlank()) {
            return detectPayload(ref);
        }
        return declared;
    }

    public static boolean isSupportedRequestContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        return startsWithIgnoreCase(contentType, PROTOBUF)
            || startsWithIgnoreCase(contentType, JSON)
            || startsWithIgnoreCase(contentType, PROTOBUF_ALT);
    }

    public static String normalizeResponseContentType(String requestContentType) {
        return startsWithIgnoreCase(requestContentType, JSON) ? JSON : PROTOBUF;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        if (value == null || prefix == null || value.length() < prefix.length()) {
            return false;
        }
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean isAsciiWhitespace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == '\f';
    }
}
