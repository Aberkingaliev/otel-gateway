package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.wire.cursor.EvalScratch;
import com.acme.finops.gateway.wire.cursor.FastWireCursor;
import com.acme.finops.gateway.wire.cursor.WireException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class ResourceAttributeSpanSelector implements ValueSpanSelector {
    private static final int ROOT_RESOURCE_COLLECTION_FIELD = 1;
    private static final int CONTAINER_RESOURCE_FIELD = 1;
    private static final int RESOURCE_ATTRIBUTES_FIELD = 1;
    private static final int KEYVALUE_KEY_FIELD = 1;
    private static final int KEYVALUE_VALUE_FIELD = 2;
    private static final int ANYVALUE_STRING_FIELD = 1;
    private static final int ANYVALUE_BYTES_FIELD = 7;
    private static final int LEN_WIRE_TYPE = 2;
    private static final byte[] JSON_KEY_FIELD = "\"key\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON_STRING_VALUE_FIELD = "\"stringValue\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON_BYTES_VALUE_FIELD = "\"bytesValue\"".getBytes(StandardCharsets.UTF_8);

    private final byte[] keyUtf8;

    ResourceAttributeSpanSelector(String key) {
        Objects.requireNonNull(key, "key");
        this.keyUtf8 = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public int collect(PacketRef packetRef,
                       FastWireCursor cursor,
                       EvalScratch scratch,
                       ValueSpanCollector collector) {
        collector.reset();
        if (isLikelyJson(packetRef)) {
            scanJson(packetRef, collector);
            return collector.count();
        }
        cursor.reset(packetRef.segment(), packetRef.offset(), packetRef.length());
        try {
            while (cursor.nextField()) {
                if (cursor.fieldNumber() != ROOT_RESOURCE_COLLECTION_FIELD || cursor.wireType() != LEN_WIRE_TYPE) {
                    continue;
                }
                cursor.enterMessage();
                try {
                    scanContainerMessage(packetRef, cursor, collector);
                } finally {
                    cursor.leaveMessage();
                }
            }
            return collector.count();
        } catch (WireException ignored) {
            // Fail-open path: malformed payloads are treated as non-match.
            return collector.count();
        }
    }

    private void scanJson(PacketRef packetRef, ValueSpanCollector collector) {
        MemorySegment json = packetRef.segment().asSlice(packetRef.offset(), packetRef.length());
        int limit = packetRef.length();
        int pos = 0;
        while (pos < limit) {
            int keyFieldPos = indexOf(json, JSON_KEY_FIELD, pos, limit);
            if (keyFieldPos < 0) {
                return;
            }

            int keyValueStart = findNextStringValue(json, keyFieldPos + JSON_KEY_FIELD.length, limit);
            if (keyValueStart < 0) {
                return;
            }
            int keyValueEnd = findStringEnd(json, keyValueStart + 1, limit);
            if (keyValueEnd < 0) {
                return;
            }
            boolean matched = stringEquals(json, keyValueStart + 1, keyValueEnd, keyUtf8);
            if (!matched) {
                pos = keyValueEnd + 1;
                continue;
            }

            int objectStart = findObjectStart(json, keyFieldPos);
            if (objectStart < 0) {
                pos = keyValueEnd + 1;
                continue;
            }
            int objectEnd = findObjectEnd(json, objectStart, limit);
            if (objectEnd < 0) {
                return;
            }

            int valueFieldPos = indexOfEither(json, JSON_STRING_VALUE_FIELD, JSON_BYTES_VALUE_FIELD, keyValueEnd + 1, objectEnd);
            if (valueFieldPos >= 0) {
                byte[] valueField = matchesAt(json, valueFieldPos, JSON_STRING_VALUE_FIELD, objectEnd)
                    ? JSON_STRING_VALUE_FIELD
                    : JSON_BYTES_VALUE_FIELD;
                int valueStart = findNextStringValue(json, valueFieldPos + valueField.length, objectEnd);
                if (valueStart >= 0) {
                    int valueEnd = findStringEnd(json, valueStart + 1, objectEnd);
                    if (valueEnd >= 0) {
                        collector.add(valueStart + 1, valueEnd - (valueStart + 1));
                    }
                }
            }
            pos = objectEnd + 1;
        }
    }

    private void scanContainerMessage(PacketRef packetRef,
                                      FastWireCursor cursor,
                                      ValueSpanCollector collector) throws WireException {
        while (cursor.nextField()) {
            if (cursor.fieldNumber() != CONTAINER_RESOURCE_FIELD || cursor.wireType() != LEN_WIRE_TYPE) {
                continue;
            }
            cursor.enterMessage();
            try {
                scanResourceMessage(packetRef, cursor, collector);
            } finally {
                cursor.leaveMessage();
            }
        }
    }

    private void scanResourceMessage(PacketRef packetRef,
                                     FastWireCursor cursor,
                                     ValueSpanCollector collector) throws WireException {
        while (cursor.nextField()) {
            if (cursor.fieldNumber() != RESOURCE_ATTRIBUTES_FIELD || cursor.wireType() != LEN_WIRE_TYPE) {
                continue;
            }
            cursor.enterMessage();
            try {
                scanKeyValue(packetRef, cursor, collector);
            } finally {
                cursor.leaveMessage();
            }
        }
    }

    private void scanKeyValue(PacketRef packetRef,
                              FastWireCursor cursor,
                              ValueSpanCollector collector) throws WireException {
        boolean keyMatched = false;
        while (cursor.nextField()) {
            if (cursor.fieldNumber() == KEYVALUE_KEY_FIELD && cursor.wireType() == LEN_WIRE_TYPE) {
                keyMatched = keyEquals(cursor.sliceValue());
                continue;
            }

            if (cursor.fieldNumber() == KEYVALUE_VALUE_FIELD && cursor.wireType() == LEN_WIRE_TYPE && keyMatched) {
                cursor.enterMessage();
                try {
                    scanAnyValue(packetRef, cursor, collector);
                } finally {
                    cursor.leaveMessage();
                }
                return;
            }
        }
    }

    private void scanAnyValue(PacketRef packetRef,
                              FastWireCursor cursor,
                              ValueSpanCollector collector) throws WireException {
        while (cursor.nextField()) {
            if (cursor.wireType() != LEN_WIRE_TYPE) {
                continue;
            }
            int field = cursor.fieldNumber();
            if (field != ANYVALUE_STRING_FIELD && field != ANYVALUE_BYTES_FIELD) {
                continue;
            }
            int relativeOffset = cursor.valueOffset() - packetRef.offset();
            collector.add(relativeOffset, cursor.valueLength());
            return;
        }
    }

    private boolean keyEquals(MemorySegment value) {
        long size = value.byteSize();
        if (size != keyUtf8.length) {
            return false;
        }
        for (int i = 0; i < keyUtf8.length; i++) {
            if (value.get(ValueLayout.JAVA_BYTE, i) != keyUtf8[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLikelyJson(PacketRef packetRef) {
        MemorySegment payload = packetRef.segment().asSlice(packetRef.offset(), packetRef.length());
        for (int i = 0; i < packetRef.length(); i++) {
            byte b = payload.get(ValueLayout.JAVA_BYTE, i);
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                continue;
            }
            return b == '{' || b == '[';
        }
        return false;
    }

    private static int indexOf(MemorySegment src, byte[] pattern, int from, int to) {
        int max = to - pattern.length;
        for (int i = Math.max(0, from); i <= max; i++) {
            if (matchesAt(src, i, pattern, to)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfEither(MemorySegment src, byte[] a, byte[] b, int from, int to) {
        int i = Math.max(0, from);
        while (i < to) {
            if (matchesAt(src, i, a, to) || matchesAt(src, i, b, to)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static boolean matchesAt(MemorySegment src, int at, byte[] pattern, int limitExclusive) {
        if (at < 0 || at + pattern.length > limitExclusive) {
            return false;
        }
        for (int i = 0; i < pattern.length; i++) {
            if (src.get(ValueLayout.JAVA_BYTE, at + i) != pattern[i]) {
                return false;
            }
        }
        return true;
    }

    private static int findNextStringValue(MemorySegment src, int from, int limit) {
        int colon = -1;
        for (int i = from; i < limit; i++) {
            byte b = src.get(ValueLayout.JAVA_BYTE, i);
            if (colon < 0) {
                if (b == ':') {
                    colon = i;
                }
                continue;
            }
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                continue;
            }
            return b == '"' ? i : -1;
        }
        return -1;
    }

    private static int findStringEnd(MemorySegment src, int from, int limit) {
        boolean escaped = false;
        for (int i = from; i < limit; i++) {
            byte b = src.get(ValueLayout.JAVA_BYTE, i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (b == '\\') {
                escaped = true;
                continue;
            }
            if (b == '"') {
                return i;
            }
        }
        return -1;
    }

    private static int findObjectStart(MemorySegment src, int before) {
        for (int i = before; i >= 0; i--) {
            if (src.get(ValueLayout.JAVA_BYTE, i) == '{') {
                return i;
            }
        }
        return -1;
    }

    private static int findObjectEnd(MemorySegment src, int objectStart, int limit) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = objectStart; i < limit; i++) {
            byte b = src.get(ValueLayout.JAVA_BYTE, i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (b == '\\') {
                    escaped = true;
                    continue;
                }
                if (b == '"') {
                    inString = false;
                }
                continue;
            }
            if (b == '"') {
                inString = true;
                continue;
            }
            if (b == '{') {
                depth++;
            } else if (b == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean stringEquals(MemorySegment src, int startInclusive, int endExclusive, byte[] expected) {
        if (endExclusive < startInclusive) {
            return false;
        }
        int len = endExclusive - startInclusive;
        if (len != expected.length) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (src.get(ValueLayout.JAVA_BYTE, startInclusive + i) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
