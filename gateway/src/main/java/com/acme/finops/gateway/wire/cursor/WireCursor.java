package com.acme.finops.gateway.wire.cursor;

import java.lang.foreign.MemorySegment;

/**
 * Zero-copy, forward-only cursor for reading protobuf wire-format data
 * directly from a {@link MemorySegment}.
 *
 * <p>NOT thread-safe. Call {@link #reset} to bind the cursor to a new segment
 * before each use. Instances are designed to be reused to avoid allocation.
 */
public interface WireCursor {
    /** Binds this cursor to the given memory region. Must be called before any read. */
    void reset(MemorySegment segment, int offset, int length);

    /** Returns the current byte position within the segment. */
    int position();

    /** Returns the end position (exclusive) of the current scope. */
    int limit();

    /** Returns the number of bytes remaining in the current scope. */
    int remaining();

    /** Advances to the next field. Returns {@code false} when the scope is exhausted. */
    boolean nextField() throws WireException;

    /** Returns the field number of the current field. */
    int fieldNumber();

    /** Returns the protobuf wire type of the current field. */
    int wireType();

    /** Returns the byte offset where the current field's tag begins. */
    int fieldStart();

    /** Returns the byte offset where the current field's value begins. */
    int valueOffset();

    /** Returns the byte length of the current field's value. */
    int valueLength();

    /** Reads a varint-encoded 32-bit integer at the current position. */
    int readVarint32() throws WireException;

    /** Reads a varint-encoded 64-bit integer at the current position. */
    long readVarint64() throws WireException;

    /** Reads a fixed 32-bit value at the current position. */
    int readFixed32() throws WireException;

    /** Reads a fixed 64-bit value at the current position. */
    long readFixed64() throws WireException;

    /** Returns a zero-copy slice of the current field's value bytes. */
    MemorySegment sliceValue() throws WireException;

    /** Skips over the current field's value without reading it. */
    void skipValue() throws WireException;

    /** Enters a LEN-delimited embedded message, pushing the current scope. */
    void enterMessage() throws WireException;

    /** Leaves the current embedded message, restoring the enclosing scope. */
    void leaveMessage() throws WireException;
}
