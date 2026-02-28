package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.memory.AllocationTag;
import com.acme.finops.gateway.memory.LeaseResult;
import com.acme.finops.gateway.memory.PacketAllocator;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.util.GatewayStatusCodes;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * Week-1 reference implementation for reframe pass.
 *
 * Pass A:
 * - optional in-place masking.
 *
 * Pass B:
 * - rebuild destination packet from source slices and byte overwrites.
 */
public final class DefaultReframeWriter implements ReframeWriter {
    private final LenCascadeRecalculator lenCascadeRecalculator;
    private final IntegrityRepair integrityRepair;
    private final MaskWriter maskWriter;

    public DefaultReframeWriter() {
        this(new LenCascadeRecalculator(), IntegrityRepair.NOOP, MaskWriter.scalar());
    }

    public DefaultReframeWriter(LenCascadeRecalculator lenCascadeRecalculator) {
        this(lenCascadeRecalculator, IntegrityRepair.NOOP, MaskWriter.scalar());
    }

    public DefaultReframeWriter(LenCascadeRecalculator lenCascadeRecalculator,
                                IntegrityRepair integrityRepair) {
        this(lenCascadeRecalculator, integrityRepair, MaskWriter.scalar());
    }

    public DefaultReframeWriter(LenCascadeRecalculator lenCascadeRecalculator,
                                IntegrityRepair integrityRepair,
                                MaskWriter maskWriter) {
        this.lenCascadeRecalculator = Objects.requireNonNull(lenCascadeRecalculator, "lenCascadeRecalculator");
        this.integrityRepair = Objects.requireNonNull(integrityRepair, "integrityRepair");
        this.maskWriter = Objects.requireNonNull(maskWriter, "maskWriter");
    }

    @Override
    public ReframeResult write(MutationPlan plan, PacketRef source, PacketAllocator allocator) {
        return apply(plan, source, allocator);
    }

    public ReframeResult apply(MutationPlan plan, PacketRef src, PacketAllocator allocator) {
        if (plan == null || src == null || allocator == null) {
            return new ReframeResult.Failed(GatewayStatusCodes.BAD_REQUEST);
        }
        if (plan.isDrop()) {
            return new ReframeResult.Failed(GatewayStatusCodes.GONE);
        }

        int baseTargetLength = plan.targetLength() > 0 ? plan.targetLength() : src.length();
        if (baseTargetLength <= 0) {
            return new ReframeResult.Failed(GatewayStatusCodes.UNPROCESSABLE_ENTITY);
        }

        LenCascadeRecalculator.RecalculationResult recalculation = lenCascadeRecalculator.recalculate(plan);
        int outLen = recalculation.targetLength() > 0 ? recalculation.targetLength() : src.length();
        if (outLen <= 0) {
            return new ReframeResult.Failed(GatewayStatusCodes.UNPROCESSABLE_ENTITY);
        }

        LeaseResult lease = allocator.allocate(outLen, allocationTag(src));
        PacketRef dst = switch (lease) {
            case LeaseResult.Granted granted -> granted.packetRef();
            case LeaseResult.Denied denied -> {
                yield null;
            }
        };
        if (dst == null) {
            return new ReframeResult.Failed(GatewayStatusCodes.INSUFFICIENT_STORAGE);
        }

        boolean success = false;
        try {
            applyPassA(plan, src, maskWriter);
            MemorySegment dstSeg = dst.segment().asSlice(dst.offset(), dst.length());
            int finalLength;
            if (baseTargetLength > outLen) {
                byte[] working = new byte[baseTargetLength];
                MemorySegment workingSeg = MemorySegment.ofArray(working);
                applyPassB(plan, src, workingSeg, maskWriter);
                finalLength = applyLenVarintPatches(workingSeg, src.length(), plan, recalculation);
                integrityRepair.repair(workingSeg, finalLength);
                if (finalLength > dstSeg.byteSize()) {
                    throw new IllegalStateException("Reframe output exceeds allocated destination");
                }
                dstSeg.asSlice(0, finalLength).copyFrom(workingSeg.asSlice(0, finalLength));
            } else {
                applyPassB(plan, src, dstSeg, maskWriter);
                finalLength = applyLenVarintPatches(dstSeg, src.length(), plan, recalculation);
                integrityRepair.repair(dstSeg, finalLength);
            }

            success = true;
            return new ReframeResult.Success(dst);
        } catch (RuntimeException e) {
            return new ReframeResult.Failed(GatewayStatusCodes.INTERNAL_ERROR);
        } finally {
            if (!success) {
                dst.release();
            }
        }
    }

    private static void applyPassA(MutationPlan plan, PacketRef src, MaskWriter maskWriter) {
        MemorySegment srcSeg = src.segment().asSlice(src.offset(), src.length());
        for (MutationPlan.Op op : plan.passAOps()) {
            if (op instanceof MutationPlan.InplaceMaskOp mask) {
                maskWriter.mask(srcSeg, mask);
            }
        }
    }

    /**
     * Core Pass B loop:
     * - take slices from src
     * - copy into dst
     * - apply byte-level overwrites
     */
    private static void applyPassB(MutationPlan plan, PacketRef src, MemorySegment dstSeg, MaskWriter maskWriter) {
        MemorySegment srcSeg = src.segment().asSlice(src.offset(), src.length());

        for (MutationPlan.Op op : plan.passBOps()) {
            if (op instanceof MutationPlan.SliceCopyOp slice) {
                MemorySegment srcSlice = srcSeg.asSlice(slice.srcOffset(), slice.length());
                MemorySegment dstSlice = dstSeg.asSlice(slice.dstOffset(), slice.length());
                dstSlice.copyFrom(srcSlice);
                continue;
            }

            if (op instanceof MutationPlan.OverwriteOp overwrite) {
                byte[] bytes = overwrite.bytes();
                MemorySegment.copy(bytes, 0, dstSeg, ValueLayout.JAVA_BYTE, overwrite.dstOffset(), bytes.length);
                continue;
            }

            if (op instanceof MutationPlan.InplaceMaskOp mask) {
                maskWriter.mask(dstSeg, mask);
                continue;
            }

            if (op instanceof MutationPlan.DropRangeOp) {
                // DropRangeOp is accounted by planner. Pass B has nothing to write for this segment.
                continue;
            }

            throw new IllegalStateException("Unsupported op in Pass B: " + op.getClass().getSimpleName());
        }
    }

    private static int applyLenVarintPatches(MemorySegment dstSeg,
                                             int sourceLength,
                                             MutationPlan plan,
                                             LenCascadeRecalculator.RecalculationResult recalculation) {
        if (recalculation.patches().isEmpty()) {
            return plan.targetLength() > 0 ? plan.targetLength() : sourceLength;
        }

        int effectiveLength = plan.targetLength() > 0 ? plan.targetLength() : sourceLength;
        int cumulativeShift = 0;

        for (LenCascadeRecalculator.VarintPatch patch : recalculation.patches()) {
            int writeOffset = patch.lengthFieldOffset() + cumulativeShift;
            int oldVarintSize = patch.oldVarintSize();
            int newVarintSize = patch.newVarintSize();
            int delta = newVarintSize - oldVarintSize;

            int bodyStart = writeOffset + oldVarintSize;
            int tailLen = effectiveLength - bodyStart;
            if (tailLen < 0) {
                throw new IllegalStateException("Invalid LEN patch tail range for frameId=" + patch.frameId());
            }

            if (delta > 0) {
                shiftRight(dstSeg, bodyStart, tailLen, delta);
            } else if (delta < 0) {
                shiftLeft(dstSeg, bodyStart, tailLen, -delta);
            }

            int written = encodeVarint32(dstSeg, writeOffset, patch.newBodyLength());
            if (written != newVarintSize) {
                throw new IllegalStateException(
                    "Varint size mismatch frameId=" + patch.frameId()
                        + " expected=" + newVarintSize
                        + " actual=" + written
                );
            }

            effectiveLength += delta;
            cumulativeShift += delta;
        }

        int expectedLength = recalculation.targetLength() > 0 ? recalculation.targetLength() : effectiveLength;
        if (effectiveLength != expectedLength) {
            throw new IllegalStateException(
                "LEN patch length mismatch expected=" + expectedLength + " actual=" + effectiveLength
            );
        }
        return effectiveLength;
    }

    private static void shiftRight(MemorySegment seg, int start, int length, int delta) {
        if (length <= 0 || delta <= 0) return;
        long segSize = seg.byteSize();
        if (start < 0 || (long) start + length + delta > segSize) {
            throw new IllegalStateException(
                "shiftRight out of bounds: start=" + start + " length=" + length + " delta=" + delta + " segSize=" + segSize);
        }
        MemorySegment.copy(seg, start, seg, start + delta, length);
    }

    private static void shiftLeft(MemorySegment seg, int start, int length, int delta) {
        if (length <= 0 || delta <= 0) return;
        if (start - delta < 0 || start < 0 || (long) start + length > seg.byteSize()) {
            throw new IllegalStateException(
                "shiftLeft out of bounds: start=" + start + " length=" + length + " delta=" + delta + " segSize=" + seg.byteSize());
        }
        MemorySegment.copy(seg, start, seg, start - delta, length);
    }

    private static int encodeVarint32(MemorySegment dst, int offset, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Varint value must be >= 0");
        }
        int pos = offset;
        int v = value;
        while ((v & ~0x7F) != 0) {
            dst.set(ValueLayout.JAVA_BYTE, pos++, (byte) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        dst.set(ValueLayout.JAVA_BYTE, pos++, (byte) v);
        return pos - offset;
    }

    private static AllocationTag allocationTag(PacketRef src) {
        int signalTypeCode = signalTypeCode(src.descriptor().signalKind());
        return new AllocationTag("reframe", "unknown", signalTypeCode);
    }

    private static int signalTypeCode(SignalKind signalKind) {
        if (signalKind == null) return 0;
        return switch (signalKind) {
            case TRACES -> 1;
            case METRICS -> 2;
            case LOGS -> 3;
        };
    }
}
