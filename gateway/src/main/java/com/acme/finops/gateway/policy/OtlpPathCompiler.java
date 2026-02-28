package com.acme.finops.gateway.policy;

import com.acme.finops.gateway.wire.SchemaId;
import com.acme.finops.gateway.wire.SignalType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class OtlpPathCompiler implements PathCompiler {
    private final PathStringPool stringPool;

    public OtlpPathCompiler(PathStringPool stringPool) {
        this.stringPool = Objects.requireNonNull(stringPool, "stringPool");
    }

    public PathStringPool stringPool() {
        return stringPool;
    }

    @Override
    public CompileResult compile(String sourcePath, SchemaId schemaId, SignalType signalType) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return new CompileResult.Failure("EMPTY_PATH", "sourcePath is blank", 0);
        }

        List<PathSegment> segments;
        try {
            segments = parseSegments(sourcePath);
        } catch (IllegalArgumentException e) {
            return new CompileResult.Failure("PARSE_ERROR", e.getMessage(), 0);
        }

        String rootFieldName = rootFieldName(signalType);

        // Convenience: allow short paths like resource.attributes.tenant_id
        if (!segments.isEmpty() && !segments.getFirst().name().equals(rootFieldName)) {
            segments.add(0, new PathSegment(rootFieldName, RepeatMode.ANY, null));
        }

        List<Short> opcodes = new ArrayList<>();
        List<Integer> operands = new ArrayList<>();
        Context context = Context.ROOT;

        for (int i = 0; i < segments.size(); i++) {
            PathSegment segment = segments.get(i);
            String name = segment.name();

            // Special-case map access in resource.attributes.*
            if (context == Context.RESOURCE && name.equals("attributes")) {
                String key = segment.mapKey();
                if (key == null && i + 1 < segments.size()) {
                    PathSegment maybeKey = segments.get(i + 1);
                    if (maybeKey.repeatMode() == RepeatMode.NONE && maybeKey.mapKey() == null) {
                        key = maybeKey.name();
                        i++;
                    }
                }
                if (key == null || key.isBlank()) {
                    return new CompileResult.Failure("MAP_KEY_REQUIRED", "attributes requires map key", i);
                }
                emit(opcodes, operands, PathOp.MAP_SCAN_STRING_KEY, stringPool.intern(key));
                emit(opcodes, operands, PathOp.READ_STRING, 0);
                emit(opcodes, operands, PathOp.HALT, 0);
                return new CompileResult.Success(new CompiledPath(
                    new PathProgram(toShortArray(opcodes), toIntArray(operands)),
                    ValueType.STRING,
                    Cardinality.OPTIONAL
                ));
            }

            switch (context) {
                case ROOT -> {
                    if (!name.equals(rootFieldName)) {
                        return new CompileResult.Failure("UNKNOWN_FIELD", "Unknown root field: " + name, i);
                    }
                    emit(opcodes, operands, PathOp.ENTER_MSG_FIELD, 1);
                    emitRepeat(opcodes, operands, segment.repeatMode());
                    emit(opcodes, operands, PathOp.ENTER_LEN_DELIMITED, 0);
                    context = Context.RESOURCE_CONTAINER;
                }
                case RESOURCE_CONTAINER -> {
                    if (name.equals("resource")) {
                        emit(opcodes, operands, PathOp.ENTER_MSG_FIELD, 1);
                        emit(opcodes, operands, PathOp.ENTER_LEN_DELIMITED, 0);
                        context = Context.RESOURCE;
                    } else if (signalType == SignalType.TRACES && name.equals("scopeSpans")) {
                        emit(opcodes, operands, PathOp.ENTER_MSG_FIELD, 2);
                        emitRepeat(opcodes, operands, segment.repeatMode());
                        emit(opcodes, operands, PathOp.ENTER_LEN_DELIMITED, 0);
                        context = Context.SCOPE_SPANS;
                    } else {
                        return new CompileResult.Failure("UNKNOWN_FIELD", "Unknown resource container field: " + name, i);
                    }
                }
                case RESOURCE -> {
                    if (name.equals("schemaUrl")) {
                        emit(opcodes, operands, PathOp.ENTER_MSG_FIELD, 2);
                        emit(opcodes, operands, PathOp.READ_STRING, 0);
                        emit(opcodes, operands, PathOp.HALT, 0);
                        return new CompileResult.Success(new CompiledPath(
                            new PathProgram(toShortArray(opcodes), toIntArray(operands)),
                            ValueType.STRING,
                            Cardinality.OPTIONAL
                        ));
                    }
                    return new CompileResult.Failure("UNKNOWN_FIELD", "Unknown Resource field: " + name, i);
                }
                case SCOPE_SPANS -> {
                    if (name.equals("scope")) {
                        emit(opcodes, operands, PathOp.ENTER_MSG_FIELD, 1);
                        emit(opcodes, operands, PathOp.ENTER_LEN_DELIMITED, 0);
                        context = Context.SCOPE;
                    } else if (name.equals("spans")) {
                        emit(opcodes, operands, PathOp.ENTER_MSG_FIELD, 2);
                        emitRepeat(opcodes, operands, segment.repeatMode());
                        emit(opcodes, operands, PathOp.ENTER_LEN_DELIMITED, 0);
                        context = Context.SPAN;
                    } else {
                        return new CompileResult.Failure("UNKNOWN_FIELD", "Unknown ScopeSpans field: " + name, i);
                    }
                }
                case SCOPE -> {
                    if (name.equals("name")) {
                        emit(opcodes, operands, PathOp.ENTER_MSG_FIELD, 1);
                        emit(opcodes, operands, PathOp.READ_STRING, 0);
                        emit(opcodes, operands, PathOp.HALT, 0);
                        return new CompileResult.Success(new CompiledPath(
                            new PathProgram(toShortArray(opcodes), toIntArray(operands)),
                            ValueType.STRING,
                            Cardinality.OPTIONAL
                        ));
                    }
                    return new CompileResult.Failure("UNKNOWN_FIELD", "Unknown InstrumentationScope field: " + name, i);
                }
                case SPAN -> {
                    if (name.equals("status")) {
                        emit(opcodes, operands, PathOp.ENTER_MSG_FIELD, 15);
                        emit(opcodes, operands, PathOp.ENTER_LEN_DELIMITED, 0);
                        context = Context.STATUS;
                    } else if (name.equals("attributes")) {
                        String key = segment.mapKey();
                        if (key == null && i + 1 < segments.size()) {
                            PathSegment maybeKey = segments.get(i + 1);
                            if (maybeKey.repeatMode() == RepeatMode.NONE && maybeKey.mapKey() == null) {
                                key = maybeKey.name();
                                i++;
                            }
                        }
                        if (key == null || key.isBlank()) {
                            return new CompileResult.Failure("MAP_KEY_REQUIRED", "attributes requires map key", i);
                        }
                        // Span attributes are repeated KeyValue fields inside Span.
                        // MAP_SCAN_STRING_KEY walks LEN entries and extracts matching value->AnyValue.
                        emit(opcodes, operands, PathOp.MAP_SCAN_STRING_KEY, stringPool.intern(key));
                        emit(opcodes, operands, PathOp.READ_STRING, 0);
                        emit(opcodes, operands, PathOp.HALT, 0);
                        return new CompileResult.Success(new CompiledPath(
                            new PathProgram(toShortArray(opcodes), toIntArray(operands)),
                            ValueType.STRING,
                            Cardinality.OPTIONAL
                        ));
                    } else if (name.equals("events")) {
                        emit(opcodes, operands, PathOp.ENTER_MSG_FIELD, 11);
                        emitRepeat(opcodes, operands, segment.repeatMode());
                        emit(opcodes, operands, PathOp.ENTER_LEN_DELIMITED, 0);
                        context = Context.EVENT;
                    } else {
                        return new CompileResult.Failure("UNKNOWN_FIELD", "Unknown Span field: " + name, i);
                    }
                }
                case STATUS -> {
                    if (!name.equals("code")) {
                        return new CompileResult.Failure("UNKNOWN_FIELD", "Unknown Status field: " + name, i);
                    }
                    emit(opcodes, operands, PathOp.ENTER_MSG_FIELD, 1);
                    emit(opcodes, operands, PathOp.READ_ENUM, 0);
                    emit(opcodes, operands, PathOp.HALT, 0);
                    return new CompileResult.Success(new CompiledPath(
                        new PathProgram(toShortArray(opcodes), toIntArray(operands)),
                        ValueType.ENUM,
                        Cardinality.OPTIONAL
                    ));
                }
                case EVENT -> {
                    if (!name.equals("name")) {
                        return new CompileResult.Failure("UNKNOWN_FIELD", "Unknown Event field: " + name, i);
                    }
                    emit(opcodes, operands, PathOp.ENTER_MSG_FIELD, 2);
                    emit(opcodes, operands, PathOp.READ_STRING, 0);
                    emit(opcodes, operands, PathOp.HALT, 0);
                    return new CompileResult.Success(new CompiledPath(
                        new PathProgram(toShortArray(opcodes), toIntArray(operands)),
                        ValueType.STRING,
                        Cardinality.OPTIONAL
                    ));
                }
            }
        }

        return new CompileResult.Failure("INCOMPLETE_PATH", "Path does not terminate in readable scalar", segments.size());
    }

    private static List<PathSegment> parseSegments(String sourcePath) {
        List<String> raw = splitPath(sourcePath);
        List<PathSegment> out = new ArrayList<>(raw.size());
        for (String token : raw) {
            String t = token.trim();
            if (t.isEmpty()) {
                throw new IllegalArgumentException("Empty path segment");
            }

            int lb = t.indexOf('[');
            if (lb < 0) {
                out.add(new PathSegment(t, RepeatMode.NONE, null));
                continue;
            }

            int rb = t.lastIndexOf(']');
            if (rb <= lb) {
                throw new IllegalArgumentException("Malformed bracket segment: " + t);
            }

            String name = t.substring(0, lb);
            String inside = t.substring(lb + 1, rb).trim();
            if (inside.equals("*")) {
                out.add(new PathSegment(name, RepeatMode.ANY, null));
            } else if (inside.equals("0")) {
                out.add(new PathSegment(name, RepeatMode.FIRST, null));
            } else if ((inside.startsWith("\"") && inside.endsWith("\"")) || (inside.startsWith("'") && inside.endsWith("'"))) {
                String mapKey = inside.substring(1, inside.length() - 1);
                out.add(new PathSegment(name, RepeatMode.NONE, mapKey));
            } else {
                throw new IllegalArgumentException("Unsupported bracket token: " + inside);
            }
        }
        return out;
    }

    private static List<String> splitPath(String sourcePath) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bracketDepth = 0;
        boolean inQuotes = false;
        char quote = 0;

        for (int i = 0; i < sourcePath.length(); i++) {
            char c = sourcePath.charAt(i);
            if ((c == '"' || c == '\'') && (i == 0 || sourcePath.charAt(i - 1) != '\\')) {
                if (!inQuotes) {
                    inQuotes = true;
                    quote = c;
                } else if (quote == c) {
                    inQuotes = false;
                }
            }
            if (!inQuotes) {
                if (c == '[') bracketDepth++;
                if (c == ']') bracketDepth--;
            }

            if (c == '.' && bracketDepth == 0 && !inQuotes) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    private static void emit(List<Short> opcodes, List<Integer> operands, PathOp op, int operand) {
        opcodes.add((short) op.ordinal());
        operands.add(operand);
    }

    private static void emitRepeat(List<Short> opcodes, List<Integer> operands, RepeatMode repeatMode) {
        if (repeatMode == RepeatMode.ANY) {
            emit(opcodes, operands, PathOp.REPEATED_ANY, 0);
        } else if (repeatMode == RepeatMode.FIRST) {
            emit(opcodes, operands, PathOp.REPEATED_FIRST, 0);
        }
    }

    private static short[] toShortArray(List<Short> values) {
        short[] out = new short[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
        return out;
    }

    private static String rootFieldName(SignalType signalType) {
        return switch (signalType) {
            case TRACES -> "resourceSpans";
            case METRICS -> "resourceMetrics";
            case LOGS -> "resourceLogs";
        };
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
        return out;
    }

    private enum Context {
        ROOT,
        RESOURCE_CONTAINER,
        RESOURCE,
        SCOPE_SPANS,
        SCOPE,
        SPAN,
        STATUS,
        EVENT
    }

    private enum RepeatMode { NONE, FIRST, ANY }

    private record PathSegment(String name, RepeatMode repeatMode, String mapKey) {
        private PathSegment {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(repeatMode, "repeatMode");
            String normalized = name.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Empty segment name");
            }
            name = normalized.substring(0, 1).toLowerCase(Locale.ROOT) + normalized.substring(1);
        }
    }
}
