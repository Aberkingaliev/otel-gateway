package com.acme.finops.gateway.wire.mutate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Week-1 planning IR for mutation pipeline.
 *
 * Pass A:
 * - in-place operations on source packet (zero-copy masking, lightweight overwrites).
 *
 * Pass B:
 * - reframing operations into destination packet (slice copy + overwrite).
 */
public record MutationPlan(
    long requestId,
    PlanMode mode,
    List<Op> passAOps,
    List<Op> passBOps,
    List<LengthDelta> lengthDeltas,
    int sourceLength,
    int targetLength,
    String reasonCode
) {
    public MutationPlan {
        Objects.requireNonNull(mode, "mode");
        passAOps = List.copyOf(passAOps == null ? List.of() : passAOps);
        passBOps = List.copyOf(passBOps == null ? List.of() : passBOps);
        lengthDeltas = List.copyOf(lengthDeltas == null ? List.of() : lengthDeltas);
        reasonCode = reasonCode == null ? "" : reasonCode;
    }

    public int operationCount() {
        return passAOps.size() + passBOps.size();
    }

    public boolean requiresReframe() {
        return mode == PlanMode.REFRAME || !passBOps.isEmpty();
    }

    public boolean isDrop() {
        return mode == PlanMode.DROP;
    }

    public enum PlanMode {
        NOOP,
        INPLACE_ONLY,
        REFRAME,
        DROP
    }

    public sealed interface Op permits SliceCopyOp, OverwriteOp, InplaceMaskOp, DropRangeOp {}

    /**
     * Copy [srcOffset, srcOffset + length) from source packet into destination packet at dstOffset.
     */
    public record SliceCopyOp(int srcOffset, int length, int dstOffset) implements Op {}

    /**
     * Write raw bytes into destination packet at dstOffset.
     */
    public record OverwriteOp(int dstOffset, byte[] bytes, String reason) implements Op {
        public OverwriteOp {
            bytes = bytes == null ? new byte[0] : bytes.clone();
            reason = reason == null ? "" : reason;
        }
    }

    /**
     * In-place masking in source or destination (depends on pass assignment).
     */
    public record InplaceMaskOp(int absoluteOffset,
                                int length,
                                byte maskByte,
                                byte[] tokenBytes,
                                String reason) implements Op {
        public InplaceMaskOp(int absoluteOffset, int length, byte maskByte, String reason) {
            this(absoluteOffset, length, maskByte, null, reason);
        }

        public InplaceMaskOp {
            tokenBytes = tokenBytes == null ? new byte[0] : tokenBytes.clone();
            reason = reason == null ? "" : reason;
        }

        public boolean hasTokenBytes() {
            return tokenBytes.length > 0;
        }
    }

    /**
     * Drop logical range from source model (used by planner accounting; no direct write action).
     */
    public record DropRangeOp(int srcOffset, int length, String reason) implements Op {
        public DropRangeOp {
            reason = reason == null ? "" : reason;
        }
    }

    /**
     * Length delta metadata for bottom-up protobuf LEN varint cascade.
     *
     * Hierarchy is explicit via frameId/parentFrameId. Root frame uses parentFrameId = -1.
     *
     * newBodyLength is local planner output for this frame before child cascade; recalculator
     * applies descendant encoded-size deltas bottom-up.
     */
    public record LengthDelta(
        int frameId,
        int parentFrameId,
        int frameStartOffset,
        int lengthFieldOffset,
        int oldBodyLength,
        int newBodyLength
    ) {
        public LengthDelta {
            if (frameId < 0) {
                throw new IllegalArgumentException("frameId must be >= 0");
            }
            if (parentFrameId < -1) {
                throw new IllegalArgumentException("parentFrameId must be >= -1");
            }
            if (oldBodyLength < 0 || newBodyLength < 0) {
                throw new IllegalArgumentException("body lengths must be >= 0");
            }
        }

        public boolean lengthChanged() {
            return oldBodyLength != newBodyLength;
        }
    }

    public static final class Builder {
        private final long requestId;
        private PlanMode mode = PlanMode.NOOP;
        private final List<Op> passAOps = new ArrayList<>();
        private final List<Op> passBOps = new ArrayList<>();
        private final List<LengthDelta> lengthDeltas = new ArrayList<>();
        private int sourceLength;
        private int targetLength;
        private String reasonCode = "";

        public Builder(long requestId) {
            this.requestId = requestId;
        }

        public Builder mode(PlanMode value) {
            this.mode = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder sourceLength(int value) {
            this.sourceLength = value;
            return this;
        }

        public Builder targetLength(int value) {
            this.targetLength = value;
            return this;
        }

        public Builder reasonCode(String value) {
            this.reasonCode = value == null ? "" : value;
            return this;
        }

        public Builder addPassA(Op op) {
            this.passAOps.add(Objects.requireNonNull(op, "op"));
            return this;
        }

        public Builder addPassB(Op op) {
            this.passBOps.add(Objects.requireNonNull(op, "op"));
            return this;
        }

        public Builder addLengthDelta(LengthDelta delta) {
            this.lengthDeltas.add(Objects.requireNonNull(delta, "delta"));
            return this;
        }

        public MutationPlan build() {
            return new MutationPlan(
                requestId,
                mode,
                passAOps,
                passBOps,
                lengthDeltas,
                sourceLength,
                targetLength,
                reasonCode
            );
        }
    }
}
