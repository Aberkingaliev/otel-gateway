package com.acme.finops.gateway.wire.cursor;

import com.acme.finops.gateway.wire.errors.WireErrorCode;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastWireCursorTest {

    @Test
    void shouldParseVarintLenFixed64AndSkipLenBodyCorrectly() throws Exception {
        // Protobuf message:
        // field 1 (varint, tag=8): 150 -> 0x96 0x01
        // field 2 (len, tag=18): "Hello" (len=5)
        // field 3 (fixed64, tag=25): 0x0102030405060708L (little-endian on wire)
        byte[] message = new byte[]{
            0x08, (byte) 0x96, 0x01,
            0x12, 0x05, 'H', 'e', 'l', 'l', 'o',
            0x19, 0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01
        };

        MemorySegment segment = MemorySegment.ofArray(message);
        FastWireCursor cursor = new FastWireCursor();
        cursor.reset(segment, 0, message.length);

        // ---- Field 1: varint ----
        assertTrue(cursor.nextField(), "field1 expected");
        assertEquals(1, cursor.fieldNumber());
        assertEquals(0, cursor.wireType()); // VARINT
        assertEquals(2, cursor.valueLength()); // 150 encoded in 2 bytes

        // Verify readVarint32() returns correct value
        FastWireCursor valueCursor1 = new FastWireCursor();
        valueCursor1.reset(segment, cursor.valueOffset(), cursor.valueLength());
        assertEquals(150, valueCursor1.readVarint32());

        // ---- Field 2: LEN string "Hello" ----
        assertTrue(cursor.nextField(), "field2 expected");
        assertEquals(2, cursor.fieldNumber());
        assertEquals(2, cursor.wireType()); // LEN
        assertEquals(5, cursor.valueLength()); // "Hello".length()

        // Verify cursor skipped body correctly (didn't parse it, but offsets are correct)
        MemorySegment strSlice = segment.asSlice(cursor.valueOffset(), cursor.valueLength());
        byte[] helloBytes = strSlice.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        assertEquals("Hello", new String(helloBytes, StandardCharsets.UTF_8));

        // ---- Field 3: fixed64 ----
        assertTrue(cursor.nextField(), "field3 expected");
        assertEquals(3, cursor.fieldNumber());
        assertEquals(1, cursor.wireType()); // I64
        assertEquals(8, cursor.valueLength());

        // Verify readFixed64() returns correct value
        FastWireCursor valueCursor3 = new FastWireCursor();
        valueCursor3.reset(segment, cursor.valueOffset(), cursor.valueLength());
        long fixed64 = valueCursor3.readFixed64();
        assertEquals(0x0102030405060708L, fixed64);

        // End
        assertFalse(cursor.nextField(), "no more fields expected");
    }

    // ---- Test 1: single-byte varints (values 0 and 127) ----
    @Test
    void shouldParseSingleByteVarint() throws Exception {
        // field 1 (varint) = 0  -> tag = (1 << 3) | 0 = 0x08, value = 0x00
        // field 2 (varint) = 127 -> tag = (2 << 3) | 0 = 0x10, value = 0x7F
        byte[] message = new byte[]{
            0x08, 0x00,
            0x10, 0x7F
        };

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(message.length);
            segment.copyFrom(MemorySegment.ofArray(message));

            FastWireCursor cursor = new FastWireCursor();
            cursor.reset(segment, 0, message.length);

            // field 1: varint = 0
            assertTrue(cursor.nextField(), "field 1 expected");
            assertEquals(1, cursor.fieldNumber());
            assertEquals(0, cursor.wireType());
            assertEquals(1, cursor.valueLength()); // single byte for value 0

            FastWireCursor v1 = new FastWireCursor();
            v1.reset(segment, cursor.valueOffset(), cursor.valueLength());
            assertEquals(0, v1.readVarint32());

            // field 2: varint = 127
            assertTrue(cursor.nextField(), "field 2 expected");
            assertEquals(2, cursor.fieldNumber());
            assertEquals(0, cursor.wireType());
            assertEquals(1, cursor.valueLength()); // single byte for value 127

            FastWireCursor v2 = new FastWireCursor();
            v2.reset(segment, cursor.valueOffset(), cursor.valueLength());
            assertEquals(127, v2.readVarint32());

            assertFalse(cursor.nextField(), "no more fields expected");
        }
    }

    // ---- Test 2: varint value 128, boundary between 1-byte and 2-byte encoding ----
    @Test
    void shouldParseVarintAtBoundary127to128() throws Exception {
        // field 1 (varint) = 128 -> tag = 0x08, value = 0x80, 0x01
        byte[] message = new byte[]{
            0x08, (byte) 0x80, 0x01
        };

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(message.length);
            segment.copyFrom(MemorySegment.ofArray(message));

            FastWireCursor cursor = new FastWireCursor();
            cursor.reset(segment, 0, message.length);

            assertTrue(cursor.nextField(), "field 1 expected");
            assertEquals(1, cursor.fieldNumber());
            assertEquals(0, cursor.wireType());
            assertEquals(2, cursor.valueLength()); // 128 needs 2 bytes

            FastWireCursor vc = new FastWireCursor();
            vc.reset(segment, cursor.valueOffset(), cursor.valueLength());
            assertEquals(128, vc.readVarint32());

            assertFalse(cursor.nextField(), "no more fields expected");
        }
    }

    // ---- Test 3: 5-byte varint for Integer.MAX_VALUE (2147483647) ----
    @Test
    void shouldParseMaxVarint32() throws Exception {
        // Integer.MAX_VALUE = 0x7FFFFFFF
        // Varint encoding of 0x7FFFFFFF:
        //   0xFF, 0xFF, 0xFF, 0xFF, 0x07
        // Tag for field 1, varint: 0x08
        byte[] message = new byte[]{
            0x08,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07
        };

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(message.length);
            segment.copyFrom(MemorySegment.ofArray(message));

            FastWireCursor cursor = new FastWireCursor();
            cursor.reset(segment, 0, message.length);

            assertTrue(cursor.nextField(), "field 1 expected");
            assertEquals(1, cursor.fieldNumber());
            assertEquals(0, cursor.wireType());
            assertEquals(5, cursor.valueLength()); // 5-byte varint

            FastWireCursor vc = new FastWireCursor();
            vc.reset(segment, cursor.valueOffset(), cursor.valueLength());
            assertEquals(Integer.MAX_VALUE, vc.readVarint32());

            assertFalse(cursor.nextField(), "no more fields expected");
        }
    }

    // ---- Test 4: fixed64 field + direct readVarint64 ----
    @Test
    void shouldParseVarint64() throws Exception {
        // field 1 (fixed64, wire type 1): tag = (1 << 3) | 1 = 0x09
        //   value = 0x00000000DEADBEEFL (little-endian: EF BE AD DE 00 00 00 00)
        // After parsing the field, we also test readVarint64 directly with a large 64-bit value.
        byte[] fixed64Message = new byte[]{
            0x09,
            (byte) 0xEF, (byte) 0xBE, (byte) 0xAD, (byte) 0xDE,
            0x00, 0x00, 0x00, 0x00
        };

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(fixed64Message.length);
            segment.copyFrom(MemorySegment.ofArray(fixed64Message));

            FastWireCursor cursor = new FastWireCursor();
            cursor.reset(segment, 0, fixed64Message.length);

            assertTrue(cursor.nextField(), "field 1 expected");
            assertEquals(1, cursor.fieldNumber());
            assertEquals(1, cursor.wireType()); // I64
            assertEquals(8, cursor.valueLength());

            FastWireCursor vc = new FastWireCursor();
            vc.reset(segment, cursor.valueOffset(), cursor.valueLength());
            long value = vc.readFixed64();
            assertEquals(0x00000000DEADBEEFL, value);

            assertFalse(cursor.nextField(), "no more fields expected");

            // Now test readVarint64 directly with a large 64-bit varint.
            // Value: 0x0000000100000000L (4294967296)
            // Varint encoding: 0x80 0x80 0x80 0x80 0x10
            byte[] varint64Bytes = new byte[]{
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10
            };
            MemorySegment v64seg = arena.allocate(varint64Bytes.length);
            v64seg.copyFrom(MemorySegment.ofArray(varint64Bytes));

            FastWireCursor v64cursor = new FastWireCursor();
            v64cursor.reset(v64seg, 0, varint64Bytes.length);
            long v64 = v64cursor.readVarint64();
            assertEquals(0x0000000100000000L, v64);
        }
    }

    // ---- Test 5: LEN field with length = 0 (empty body) ----
    @Test
    void shouldHandleEmptyLenDelimited() throws Exception {
        // field 1 (LEN, wire type 2): tag = (1 << 3) | 2 = 0x0A, length = 0
        byte[] message = new byte[]{
            0x0A, 0x00
        };

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(message.length);
            segment.copyFrom(MemorySegment.ofArray(message));

            FastWireCursor cursor = new FastWireCursor();
            cursor.reset(segment, 0, message.length);

            assertTrue(cursor.nextField(), "field 1 expected");
            assertEquals(1, cursor.fieldNumber());
            assertEquals(2, cursor.wireType()); // LEN
            assertEquals(0, cursor.valueLength()); // empty body

            MemorySegment slice = cursor.sliceValue();
            assertEquals(0, slice.byteSize());

            assertFalse(cursor.nextField(), "no more fields expected");
        }
    }

    // ---- Test 6: truncated payload (ends mid-field) ----
    @Test
    void shouldRejectTruncatedPayload() throws Exception {
        // field 1 (fixed64, wire type 1): tag = 0x09, but only 3 bytes of the 8-byte body
        byte[] message = new byte[]{
            0x09, 0x01, 0x02, 0x03
        };

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(message.length);
            segment.copyFrom(MemorySegment.ofArray(message));

            FastWireCursor cursor = new FastWireCursor();
            cursor.reset(segment, 0, message.length);

            WireException ex = assertThrows(WireException.class, cursor::nextField);
            assertEquals(WireErrorCode.TRUNCATED_FRAME, ex.code());
        }
    }

    // ---- Test 7: malformed varint with more than 10 continuation bytes ----
    @Test
    void shouldRejectMalformedVarint() throws Exception {
        // 11 continuation bytes (all 0x80) followed by a terminator 0x00 -- but the cursor
        // should reject after reading 10 bytes since varint64 max is 10 bytes.
        // We encode as a raw varint (no tag wrapping), call readVarint64 directly.
        byte[] varintBytes = new byte[]{
            (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
            (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
            0x00
        };

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(varintBytes.length);
            segment.copyFrom(MemorySegment.ofArray(varintBytes));

            FastWireCursor cursor = new FastWireCursor();
            cursor.reset(segment, 0, varintBytes.length);

            WireException ex = assertThrows(WireException.class, cursor::readVarint64);
            assertEquals(WireErrorCode.VARINT_OVERFLOW, ex.code());
        }
    }

    // ---- Test 8: nested sub-messages via enterMessage / leaveMessage ----
    @Test
    void shouldHandleNestedSubMessages() throws Exception {
        // Outer message:
        //   field 1 (varint) = 42  -> tag 0x08, value 0x2A
        //   field 2 (LEN, sub-message) -> tag 0x12, length = 4
        //     Inner sub-message (4 bytes):
        //       field 1 (varint) = 99 -> tag 0x08, value 0x63
        //       field 3 (varint) = 7  -> tag 0x18, value 0x07
        //   field 3 (varint) = 55 -> tag 0x18, value 0x37
        byte[] inner = new byte[]{
            0x08, 0x63,  // field 1 = 99
            0x18, 0x07   // field 3 = 7
        };
        byte[] message = new byte[]{
            0x08, 0x2A,                          // field 1 = 42
            0x12, (byte) inner.length,           // field 2 = LEN(4)
            inner[0], inner[1], inner[2], inner[3],
            0x18, 0x37                           // field 3 = 55
        };

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(message.length);
            segment.copyFrom(MemorySegment.ofArray(message));

            FastWireCursor cursor = new FastWireCursor();
            cursor.reset(segment, 0, message.length);

            // Outer field 1: varint = 42
            assertTrue(cursor.nextField());
            assertEquals(1, cursor.fieldNumber());
            assertEquals(0, cursor.wireType());
            FastWireCursor vc1 = new FastWireCursor();
            vc1.reset(segment, cursor.valueOffset(), cursor.valueLength());
            assertEquals(42, vc1.readVarint32());

            // Outer field 2: LEN (sub-message)
            assertTrue(cursor.nextField());
            assertEquals(2, cursor.fieldNumber());
            assertEquals(2, cursor.wireType());
            assertEquals(4, cursor.valueLength());

            // Enter the sub-message
            cursor.enterMessage();

            // Inner field 1: varint = 99
            assertTrue(cursor.nextField());
            assertEquals(1, cursor.fieldNumber());
            assertEquals(0, cursor.wireType());
            FastWireCursor vi1 = new FastWireCursor();
            vi1.reset(segment, cursor.valueOffset(), cursor.valueLength());
            assertEquals(99, vi1.readVarint32());

            // Inner field 3: varint = 7
            assertTrue(cursor.nextField());
            assertEquals(3, cursor.fieldNumber());
            assertEquals(0, cursor.wireType());
            FastWireCursor vi2 = new FastWireCursor();
            vi2.reset(segment, cursor.valueOffset(), cursor.valueLength());
            assertEquals(7, vi2.readVarint32());

            // No more inner fields
            assertFalse(cursor.nextField());

            // Leave sub-message
            cursor.leaveMessage();

            // Outer field 3: varint = 55
            assertTrue(cursor.nextField());
            assertEquals(3, cursor.fieldNumber());
            assertEquals(0, cursor.wireType());
            FastWireCursor vc3 = new FastWireCursor();
            vc3.reset(segment, cursor.valueOffset(), cursor.valueLength());
            assertEquals(55, vc3.readVarint32());

            assertFalse(cursor.nextField(), "no more outer fields expected");
        }
    }

    // ---- Test 9: empty message (0-byte payload) ----
    @Test
    void shouldHandleEmptyMessage() throws Exception {
        byte[] message = new byte[0];

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(1); // minimum allocation, use length 0
            FastWireCursor cursor = new FastWireCursor();
            cursor.reset(segment, 0, 0);

            assertFalse(cursor.nextField(), "no fields expected in empty message");
        }
    }

    // ---- Test 10: unsupported wire type (3 = SGROUP, deprecated) ----
    @Test
    void shouldRejectUnsupportedWireType() throws Exception {
        // tag with field_number=1, wire_type=3: (1 << 3) | 3 = 0x0B
        byte[] message = new byte[]{
            0x0B
        };

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(message.length);
            segment.copyFrom(MemorySegment.ofArray(message));

            FastWireCursor cursor = new FastWireCursor();
            cursor.reset(segment, 0, message.length);

            WireException ex = assertThrows(WireException.class, cursor::nextField);
            assertEquals(WireErrorCode.UNSUPPORTED_WIRE_TYPE, ex.code());
        }

        // Also test wire type 4 (EGROUP): (1 << 3) | 4 = 0x0C
        byte[] message4 = new byte[]{
            0x0C
        };

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment4 = arena.allocate(message4.length);
            segment4.copyFrom(MemorySegment.ofArray(message4));

            FastWireCursor cursor4 = new FastWireCursor();
            cursor4.reset(segment4, 0, message4.length);

            WireException ex4 = assertThrows(WireException.class, cursor4::nextField);
            assertEquals(WireErrorCode.UNSUPPORTED_WIRE_TYPE, ex4.code());
        }
    }

    // ---- Test 11: overflow in reset when offset + length exceeds Integer.MAX_VALUE ----
    @Test
    void shouldRejectOverflowInReset() throws Exception {
        byte[] data = new byte[]{0x01};
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(data.length);
            segment.copyFrom(MemorySegment.ofArray(data));

            FastWireCursor cursor = new FastWireCursor();

            // offset near Integer.MAX_VALUE + length that causes overflow
            assertThrows(ArithmeticException.class, () ->
                cursor.reset(segment, Integer.MAX_VALUE, Integer.MAX_VALUE)
            );
        }
    }
}
