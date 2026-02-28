package com.acme.finops.gateway.wire.cursor;

import com.acme.finops.gateway.policy.CompiledPath;
import com.acme.finops.gateway.policy.PathOp;
import com.acme.finops.gateway.wire.errors.WireErrorCode;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

public final class BytecodeCompiledPathEvaluator implements CompiledPathEvaluator {
    private static final PathOp[] OPS = PathOp.values();
    private static final int MAX_CURSOR_DEPTH = 32;

    private final IntFunction<String> mapKeyResolver;
    private final ConcurrentHashMap<Integer, byte[]> mapKeyUtf8Cache = new ConcurrentHashMap<>();
    private final ThreadLocal<FastWireCursor[]> cursorPoolTl = ThreadLocal.withInitial(() -> {
        FastWireCursor[] pool = new FastWireCursor[MAX_CURSOR_DEPTH];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = new FastWireCursor();
        }
        return pool;
    });
    private final ThreadLocal<StopFlag> stopFlagTl = ThreadLocal.withInitial(StopFlag::new);

    @FunctionalInterface
    public interface MatchConsumer {
        /**
         * @return true to continue traversal, false to stop traversal early.
         */
        boolean onMatch(int valueOffset, int valueLength, int terminalTypeCode);
    }

    public BytecodeCompiledPathEvaluator(IntFunction<String> mapKeyResolver) {
        this.mapKeyResolver = Objects.requireNonNull(mapKeyResolver, "mapKeyResolver");
    }

    @Override
    public EvalResult evaluate(CompiledPath path, WireCursor cursor, EvalScratch scratch) {
        if (!(cursor instanceof FastWireCursor fastCursor) || fastCursor.segment() == null) {
            return new EvalResult.EvalError(RuntimeMismatchCode.SCHEMA_DRIFT_DETECTED, cursor.position());
        }

        short[] opcodes = path.program().opcodes();
        int[] operands = path.program().operands();
        if (opcodes.length != operands.length) {
            return new EvalResult.EvalError(RuntimeMismatchCode.SCHEMA_DRIFT_DETECTED, cursor.position());
        }

        StopFlag stop = stopFlagTl.get();
        stop.stop = false;
        try {
            int matches = executeFrom(
                path,
                opcodes,
                operands,
                0,
                fastCursor.segment(),
                cursor.position(),
                cursor.remaining(),
                false,
                0,
                0,
                0,
                false,
                0,
                0,
                scratch,
                null,
                true,
                0,
                stop
            );
            if (matches > 0) {
                return new EvalResult.MatchFound(path.terminalType().ordinal());
            }
            return new EvalResult.NoMatch(RuntimeMismatchCode.PATH_NOT_PRESENT);
        } catch (WireException e) {
            return new EvalResult.EvalError(RuntimeMismatchCode.MALFORMED_PROTO, e.position());
        }
    }

    public int evaluateAll(CompiledPath path,
                           MemorySegment segment,
                           int offset,
                           int length,
                           EvalScratch scratch,
                           MatchConsumer consumer) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(segment, "segment");
        Objects.requireNonNull(scratch, "scratch");

        short[] opcodes = path.program().opcodes();
        int[] operands = path.program().operands();
        if (opcodes.length != operands.length) {
            return 0;
        }

        StopFlag stop = stopFlagTl.get();
        stop.stop = false;
        try {
            return executeFrom(
                path,
                opcodes,
                operands,
                0,
                segment,
                offset,
                length,
                false,
                0,
                0,
                0,
                false,
                0,
                0,
                scratch,
                consumer,
                false,
                0,
                stop
            );
        } catch (WireException ignored) {
            return 0;
        }
    }

    private int executeFrom(CompiledPath path,
                            short[] opcodes,
                            int[] operands,
                            int pc,
                            MemorySegment segment,
                            int messageOffset,
                            int messageLength,
                            boolean hasSelected,
                            int selectedWireType,
                            int selectedValueOffset,
                            int selectedValueLength,
                            boolean terminalReady,
                            int terminalValueOffset,
                            int terminalValueLength,
                            EvalScratch scratch,
                            MatchConsumer consumer,
                            boolean stopAfterFirst,
                            int depth,
                            StopFlag stop) throws WireException {
        if (stop.stop) {
            return 0;
        }

        int matches = 0;
        while (pc < opcodes.length) {
            if (stop.stop) {
                return matches;
            }

            int opcodeOrdinal = opcodes[pc] & 0xFFFF;
            if (opcodeOrdinal < 0 || opcodeOrdinal >= OPS.length) {
                throw new WireException(WireErrorCode.SCHEMA_MISMATCH, messageOffset, "Invalid opcode");
            }
            PathOp op = OPS[opcodeOrdinal];
            int operand = operands[pc];

            switch (op) {
                case ENTER_MSG_FIELD -> {
                    PathOp repeatOp = (pc + 1 < opcodes.length) ? OPS[opcodes[pc + 1] & 0xFFFF] : null;
                    if (repeatOp == PathOp.REPEATED_ANY) {
                        int localMatches = 0;
                        FastWireCursor scanner = cursorAtDepth(depth);
                        scanner.reset(segment, messageOffset, messageLength);
                        while (scanner.nextField()) {
                            if (scanner.fieldNumber() != operand) {
                                continue;
                            }
                            localMatches += executeFrom(
                                path,
                                opcodes,
                                operands,
                                pc + 2,
                                segment,
                                messageOffset,
                                messageLength,
                                true,
                                scanner.wireType(),
                                scanner.valueOffset(),
                                scanner.valueLength(),
                                terminalReady,
                                terminalValueOffset,
                                terminalValueLength,
                                scratch,
                                consumer,
                                stopAfterFirst,
                                depth + 1,
                                stop
                            );
                            if (stop.stop) {
                                return matches + localMatches;
                            }
                        }
                        return matches + localMatches;
                    }

                    FastWireCursor scanner = cursorAtDepth(depth);
                    scanner.reset(segment, messageOffset, messageLength);
                    boolean found = false;
                    while (scanner.nextField()) {
                        if (scanner.fieldNumber() != operand) {
                            continue;
                        }
                        found = true;
                        hasSelected = true;
                        selectedWireType = scanner.wireType();
                        selectedValueOffset = scanner.valueOffset();
                        selectedValueLength = scanner.valueLength();
                        break;
                    }
                    if (!found) {
                        return matches;
                    }

                    if (repeatOp == PathOp.REPEATED_FIRST) {
                        pc += 2;
                    } else {
                        pc += 1;
                    }
                }
                case ENTER_LEN_DELIMITED -> {
                    if (!hasSelected || selectedWireType != 2) {
                        return matches;
                    }
                    messageOffset = selectedValueOffset;
                    messageLength = selectedValueLength;
                    hasSelected = false;
                    pc += 1;
                }
                case MAP_SCAN_STRING_KEY -> {
                    byte[] expectedUtf8 = resolveMapKeyUtf8(operand);
                    if (expectedUtf8 == null || expectedUtf8.length == 0) {
                        return matches;
                    }
                    FastWireCursor scanner = cursorAtDepth(depth);
                    scanner.reset(segment, messageOffset, messageLength);
                    boolean found = false;
                    while (scanner.nextField()) {
                        if (scanner.wireType() != 2) {
                            continue;
                        }

                        FastWireCursor entry = cursorAtDepth(depth + 1);
                        entry.reset(segment, scanner.valueOffset(), scanner.valueLength());
                        boolean keyMatched = false;
                        while (entry.nextField()) {
                            if (entry.fieldNumber() == 1 && entry.wireType() == 2) {
                                keyMatched = equalsUtf8(entry.sliceValue(), expectedUtf8);
                                continue;
                            }
                            if (entry.fieldNumber() == 2 && entry.wireType() == 2 && keyMatched) {
                                messageOffset = entry.valueOffset();
                                messageLength = entry.valueLength();
                                hasSelected = false;
                                found = true;
                                break;
                            }
                        }
                        if (found) {
                            break;
                        }
                    }
                    if (!found) {
                        return matches;
                    }
                    pc += 1;
                }
                case MAP_SCAN_ANY_KEY -> {
                    FastWireCursor scanner = cursorAtDepth(depth);
                    scanner.reset(segment, messageOffset, messageLength);
                    boolean found = false;
                    while (scanner.nextField()) {
                        if (scanner.wireType() != 2) {
                            continue;
                        }
                        messageOffset = scanner.valueOffset();
                        messageLength = scanner.valueLength();
                        hasSelected = false;
                        found = true;
                        break;
                    }
                    if (!found) {
                        return matches;
                    }
                    pc += 1;
                }
                case REPEATED_FIRST, REPEATED_ANY, ONEOF_EXPECT_FIELD -> pc += 1;
                case READ_STRING, READ_BYTES -> {
                    boolean matched = readLenDelimitedValue(
                        segment,
                        messageOffset,
                        messageLength,
                        hasSelected,
                        selectedWireType,
                        selectedValueOffset,
                        selectedValueLength,
                        scratch,
                        depth
                    );
                    if (!matched) {
                        return matches;
                    }
                    terminalReady = true;
                    terminalValueOffset = scratch.intStack()[1];
                    terminalValueLength = scratch.intStack()[2];
                    hasSelected = false;
                    pc += 1;
                }
                case READ_BOOL, READ_ENUM, READ_SCALAR -> {
                    boolean matched = readVarintValue(
                        segment,
                        messageOffset,
                        messageLength,
                        hasSelected,
                        selectedWireType,
                        selectedValueOffset,
                        selectedValueLength,
                        scratch,
                        depth
                    );
                    if (!matched) {
                        return matches;
                    }
                    terminalReady = true;
                    terminalValueOffset = scratch.intStack()[1];
                    terminalValueLength = scratch.intStack()[2];
                    hasSelected = false;
                    pc += 1;
                }
                case HALT -> {
                    if (!terminalReady) {
                        return matches;
                    }
                    matches += emitMatch(path, terminalValueOffset, terminalValueLength, consumer, stopAfterFirst, stop);
                    return matches;
                }
            }
        }

        if (terminalReady) {
            matches += emitMatch(path, terminalValueOffset, terminalValueLength, consumer, stopAfterFirst, stop);
        }
        return matches;
    }

    private int emitMatch(CompiledPath path,
                          int terminalValueOffset,
                          int terminalValueLength,
                          MatchConsumer consumer,
                          boolean stopAfterFirst,
                          StopFlag stop) {
        if (consumer != null) {
            boolean keepGoing = consumer.onMatch(terminalValueOffset, terminalValueLength, path.terminalType().ordinal());
            if (!keepGoing) {
                stop.stop = true;
                return 1;
            }
        }
        if (stopAfterFirst) {
            stop.stop = true;
        }
        return 1;
    }

    private boolean readLenDelimitedValue(MemorySegment segment,
                                          int messageOffset,
                                          int messageLength,
                                          boolean hasSelected,
                                          int selectedWireType,
                                          int selectedValueOffset,
                                          int selectedValueLength,
                                          EvalScratch scratch,
                                          int depth) throws WireException {
        if (hasSelected && selectedWireType == 2) {
            if (!copyValue(segment, selectedValueOffset, selectedValueLength, scratch)) {
                return false;
            }
            scratch.intStack()[1] = selectedValueOffset;
            scratch.intStack()[2] = selectedValueLength;
            return true;
        }

        FastWireCursor scanner = cursorAtDepth(depth);
        scanner.reset(segment, messageOffset, messageLength);
        while (scanner.nextField()) {
            if (scanner.wireType() != 2) {
                continue;
            }
            if (!copyValue(segment, scanner.valueOffset(), scanner.valueLength(), scratch)) {
                return false;
            }
            scratch.intStack()[1] = scanner.valueOffset();
            scratch.intStack()[2] = scanner.valueLength();
            return true;
        }
        return false;
    }

    private boolean readVarintValue(MemorySegment segment,
                                    int messageOffset,
                                    int messageLength,
                                    boolean hasSelected,
                                    int selectedWireType,
                                    int selectedValueOffset,
                                    int selectedValueLength,
                                    EvalScratch scratch,
                                    int depth) throws WireException {
        if (hasSelected && selectedWireType == 0) {
            long value = decodeVarint(segment, selectedValueOffset, selectedValueLength);
            scratch.longStack()[0] = value;
            scratch.intStack()[0] = (int) value;
            scratch.intStack()[1] = selectedValueOffset;
            scratch.intStack()[2] = selectedValueLength;
            return true;
        }

        FastWireCursor scanner = cursorAtDepth(depth);
        scanner.reset(segment, messageOffset, messageLength);
        while (scanner.nextField()) {
            if (scanner.wireType() != 0) {
                continue;
            }
            long value = decodeVarint(segment, scanner.valueOffset(), scanner.valueLength());
            scratch.longStack()[0] = value;
            scratch.intStack()[0] = (int) value;
            scratch.intStack()[1] = scanner.valueOffset();
            scratch.intStack()[2] = scanner.valueLength();
            return true;
        }
        return false;
    }

    private FastWireCursor cursorAtDepth(int depth) throws WireException {
        if (depth < 0 || depth >= MAX_CURSOR_DEPTH) {
            throw new WireException(WireErrorCode.FRAME_NESTING_LIMIT, depth, "Path traversal depth overflow");
        }
        return cursorPoolTl.get()[depth];
    }

    private byte[] resolveMapKeyUtf8(int poolId) {
        byte[] cached = mapKeyUtf8Cache.get(poolId);
        if (cached != null) {
            return cached;
        }
        String key = mapKeyResolver.apply(poolId);
        if (key == null || key.isEmpty()) {
            return null;
        }
        byte[] utf8 = key.getBytes(StandardCharsets.UTF_8);
        mapKeyUtf8Cache.putIfAbsent(poolId, utf8);
        return mapKeyUtf8Cache.get(poolId);
    }

    private static boolean equalsUtf8(MemorySegment value, byte[] expectedUtf8) {
        if (value.byteSize() != expectedUtf8.length) {
            return false;
        }
        for (int i = 0; i < expectedUtf8.length; i++) {
            if (value.get(ValueLayout.JAVA_BYTE, i) != expectedUtf8[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean copyValue(MemorySegment segment, int valueOffset, int valueLength, EvalScratch scratch) {
        byte[] dst = scratch.tempBytes();
        if (valueLength > dst.length) {
            return false;
        }
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, valueOffset, dst, 0, valueLength);
        scratch.intStack()[0] = valueLength;
        return true;
    }

    private static long decodeVarint(MemorySegment segment, int valueOffset, int valueLength) {
        long result = 0L;
        int shift = 0;
        for (int i = 0; i < valueLength; i++) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, valueOffset + i);
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        return result;
    }

    private static final class StopFlag {
        private boolean stop;
    }
}
