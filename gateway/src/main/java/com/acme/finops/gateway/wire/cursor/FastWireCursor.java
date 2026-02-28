package com.acme.finops.gateway.wire.cursor;

import com.acme.finops.gateway.wire.errors.WireErrorCode;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Zero-copy wire cursor over MemorySegment for protobuf payloads.
 */
public final class FastWireCursor implements WireCursor {
    private MemorySegment segment;
    private int offset;
    private int limit;

    private int currentFieldNumber;
    private int currentWireType;
    private int currentFieldStart;
    private int currentValueOffset;
    private int currentValueLength;

    private final Deque<Integer> frameEnds = new ArrayDeque<>();

    @Override
    public void reset(MemorySegment segment, int offset, int length) {
        this.segment = segment;
        this.offset = offset;
        this.limit = Math.addExact(offset, length);

        this.currentFieldNumber = 0;
        this.currentWireType = 0;
        this.currentFieldStart = offset;
        this.currentValueOffset = offset;
        this.currentValueLength = 0;

        frameEnds.clear();
    }

    @Override
    public int position() {
        return offset;
    }

    @Override
    public int limit() {
        return limit;
    }

    @Override
    public int remaining() {
        return activeFrameEnd() - offset;
    }

    /**
     * Exposes backing segment for advanced evaluators.
     * Caller must respect current cursor bounds (offset/remaining).
     */
    public MemorySegment segment() {
        return segment;
    }

    /**
     * Reads next protobuf field tag and skips its value in-place by advancing cursor.
     * LEN fields are skipped by length only (body bytes are not decoded).
     */
    @Override
    public boolean nextField() throws WireException {
        ensureInitialized();

        if (offset >= activeFrameEnd()) {
            return false;
        }

        currentFieldStart = offset;
        int tag = readVarint32();
        if (tag == 0) {
            throw new WireException(WireErrorCode.UNSUPPORTED_WIRE_TYPE, currentFieldStart, "Invalid tag=0");
        }

        currentFieldNumber = tag >>> 3;
        currentWireType = tag & 0x07;
        currentValueOffset = offset;

        switch (currentWireType) {
            case 0 -> { // VARINT
                int before = offset;
                readVarint64();
                currentValueLength = offset - before;
            }
            case 1 -> { // I64
                ensureRemaining(8);
                offset += 8;
                currentValueLength = 8;
            }
            case 2 -> { // LEN
                int len = readVarint32();
                if (len < 0) {
                    throw new WireException(WireErrorCode.TRUNCATED_FRAME, offset, "Negative LEN size");
                }
                ensureRemaining(len);
                currentValueOffset = offset;
                currentValueLength = len;
                offset += len; // skip bytes, do not decode body
            }
            case 5 -> { // I32
                ensureRemaining(4);
                offset += 4;
                currentValueLength = 4;
            }
            default -> throw new WireException(
                WireErrorCode.UNSUPPORTED_WIRE_TYPE,
                currentFieldStart,
                "Unsupported wire type: " + currentWireType
            );
        }

        return true;
    }

    @Override
    public int fieldNumber() {
        return currentFieldNumber;
    }

    @Override
    public int wireType() {
        return currentWireType;
    }

    @Override
    public int fieldStart() {
        return currentFieldStart;
    }

    @Override
    public int valueOffset() {
        return currentValueOffset;
    }

    @Override
    public int valueLength() {
        return currentValueLength;
    }

    /**
     * Reads varint32 byte-by-byte directly from MemorySegment.
     * No arrays/heap decode buffers.
     */
    @Override
    public int readVarint32() throws WireException {
        int result = 0;
        int shift = 0;

        while (shift < 32) {
            ensureRemaining(1);
            byte b = segment.get(ValueLayout.JAVA_BYTE, offset++);
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }

        // consume up to 5 extra bytes for malformed/overflowed varint32
        for (int i = 0; i < 5; i++) {
            ensureRemaining(1);
            byte b = segment.get(ValueLayout.JAVA_BYTE, offset++);
            if ((b & 0x80) == 0) return result;
        }

        throw new WireException(WireErrorCode.VARINT_OVERFLOW, offset, "Varint32 overflow");
    }

    @Override
    public long readVarint64() throws WireException {
        long result = 0L;
        int shift = 0;

        while (shift < 64) {
            ensureRemaining(1);
            byte b = segment.get(ValueLayout.JAVA_BYTE, offset++);
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }

        throw new WireException(WireErrorCode.VARINT_OVERFLOW, offset, "Varint64 overflow");
    }

    @Override
    public int readFixed32() throws WireException {
        ensureRemaining(4);
        int v = segment.get(ValueLayout.JAVA_INT_UNALIGNED, offset);
        offset += 4;
        return v;
    }

    @Override
    public long readFixed64() throws WireException {
        ensureRemaining(8);
        long v = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
        offset += 8;
        return v;
    }

    @Override
    public MemorySegment sliceValue() throws WireException {
        ensureInitialized();
        return segment.asSlice(currentValueOffset, currentValueLength);
    }

    @Override
    public void skipValue() {
        // nextField already skips
    }

    @Override
    public void enterMessage() throws WireException {
        if (currentWireType != 2) {
            throw new WireException(WireErrorCode.UNSUPPORTED_WIRE_TYPE, currentFieldStart,
                "enterMessage requires LEN wire type");
        }
        int end = currentValueOffset + currentValueLength;
        if (end > activeFrameEnd()) {
            throw new WireException(WireErrorCode.TRUNCATED_FRAME, end, "Nested frame exceeds parent");
        }
        frameEnds.push(end);
        offset = currentValueOffset;
    }

    @Override
    public void leaveMessage() throws WireException {
        if (frameEnds.isEmpty()) {
            throw new WireException(WireErrorCode.FRAME_STACK_UNDERFLOW, offset, "No frame to leave");
        }
        offset = frameEnds.pop();
    }

    private int activeFrameEnd() {
        return frameEnds.isEmpty() ? limit : frameEnds.peek();
    }

    private void ensureRemaining(int n) throws WireException {
        if (offset + n > activeFrameEnd()) {
            throw new WireException(WireErrorCode.TRUNCATED_FRAME, offset, "Need " + n + " bytes");
        }
    }

    private void ensureInitialized() throws WireException {
        if (segment == null) {
            throw new WireException(WireErrorCode.SCHEMA_MISMATCH, 0, "Cursor not initialized");
        }
    }
}
