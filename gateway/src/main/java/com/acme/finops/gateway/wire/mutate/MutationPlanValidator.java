package com.acme.finops.gateway.wire.mutate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Structural validation for mutation plan.
 *
 * Week-1 focus:
 * - bounds checks
 * - overlap detection for in-place and destination writes
 */
public final class MutationPlanValidator {

    public ValidationResult validate(MutationPlan plan, int sourceLength) {
        List<String> errors = new ArrayList<>();

        if (sourceLength < 0) {
            errors.add("sourceLength must be >= 0");
        }
        if (plan.sourceLength() > 0 && plan.sourceLength() != sourceLength) {
            errors.add("plan.sourceLength mismatch");
        }

        int targetLength = plan.targetLength() > 0 ? plan.targetLength() : sourceLength;
        if (targetLength < 0) {
            errors.add("targetLength must be >= 0");
        }

        List<Interval> changedLengthZones = buildChangedLengthZones(plan, sourceLength, errors);
        if (!changedLengthZones.isEmpty() && plan.mode() == MutationPlan.PlanMode.INPLACE_ONLY) {
            errors.add("INPLACE_ONLY mode is invalid when LEN frame size changes");
        }

        List<Interval> passAIntervals = new ArrayList<>();
        List<Interval> dstIntervals = new ArrayList<>();

        for (MutationPlan.Op op : plan.passAOps()) {
            if (op instanceof MutationPlan.InplaceMaskOp mask) {
                validateRange(mask.absoluteOffset(), mask.length(), sourceLength, "passA.mask", errors);
                if (mask.hasTokenBytes() && mask.tokenBytes().length != mask.length()) {
                    errors.add("passA.mask token length must match mask length for strict in-place mode");
                }
                Interval inPlaceWrite = new Interval(mask.absoluteOffset(), mask.absoluteOffset() + mask.length(), "passA.mask");
                passAIntervals.add(inPlaceWrite);
                if (intersectsAny(inPlaceWrite, changedLengthZones)) {
                    errors.add("passA in-place mutation overlaps LEN-changing frame: " + inPlaceWrite);
                }
            }
        }

        for (MutationPlan.Op op : plan.passBOps()) {
            if (op instanceof MutationPlan.SliceCopyOp s) {
                validateRange(s.srcOffset(), s.length(), sourceLength, "passB.slice.src", errors);
                validateRange(s.dstOffset(), s.length(), targetLength, "passB.slice.dst", errors);
                dstIntervals.add(new Interval(s.dstOffset(), s.dstOffset() + s.length(), "passB.slice.dst"));
                continue;
            }

            if (op instanceof MutationPlan.OverwriteOp w) {
                int len = w.bytes().length;
                validateRange(w.dstOffset(), len, targetLength, "passB.overwrite.dst", errors);
                dstIntervals.add(new Interval(w.dstOffset(), w.dstOffset() + len, "passB.overwrite.dst"));
                continue;
            }

            if (op instanceof MutationPlan.InplaceMaskOp m) {
                validateRange(m.absoluteOffset(), m.length(), targetLength, "passB.mask.dst", errors);
                if (m.hasTokenBytes() && m.tokenBytes().length != m.length()) {
                    errors.add("passB.mask token length must match mask length for strict in-place mode");
                }
                dstIntervals.add(new Interval(m.absoluteOffset(), m.absoluteOffset() + m.length(), "passB.mask.dst"));
            }
        }

        errors.addAll(detectOverlap(passAIntervals, "passA"));
        errors.addAll(detectOverlap(dstIntervals, "passB.dst"));

        return new ValidationResult(errors.isEmpty(), List.copyOf(errors));
    }

    private static List<Interval> buildChangedLengthZones(MutationPlan plan, int sourceLength, List<String> errors) {
        List<Interval> changed = new ArrayList<>();
        for (MutationPlan.LengthDelta delta : plan.lengthDeltas()) {
            int oldVarintSize = LenCascadeRecalculator.varintSize(delta.oldBodyLength());
            long end = (long) delta.lengthFieldOffset() + oldVarintSize + delta.oldBodyLength();

            if (delta.lengthFieldOffset() < 0 || end < 0 || end > Integer.MAX_VALUE) {
                errors.add("lengthDelta out of integer bounds for frameId=" + delta.frameId());
                continue;
            }
            if (sourceLength >= 0 && end > sourceLength) {
                errors.add("lengthDelta exceeds source bounds for frameId=" + delta.frameId());
                continue;
            }
            if (delta.lengthChanged()) {
                changed.add(new Interval(delta.lengthFieldOffset(), (int) end, "lenDelta#" + delta.frameId()));
            }
        }
        return changed;
    }

    private static void validateRange(int offset, int length, int upperBound, String label, List<String> errors) {
        if (offset < 0 || length < 0) {
            errors.add(label + " offset/length must be >= 0");
            return;
        }
        long end = (long) offset + length;
        if (end > upperBound) {
            errors.add(label + " out of bounds: [" + offset + "," + end + ") > " + upperBound);
        }
    }

    private static List<String> detectOverlap(List<Interval> intervals, String scope) {
        if (intervals.size() <= 1) {
            return List.of();
        }

        List<Interval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparingInt(Interval::start).thenComparingInt(Interval::end));

        List<String> errors = new ArrayList<>();
        Interval prev = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            Interval curr = sorted.get(i);
            if (curr.start() < prev.end()) {
                errors.add(scope + " overlap: " + prev + " vs " + curr);
                if (curr.end() > prev.end()) {
                    prev = curr;
                }
            } else {
                prev = curr;
            }
        }
        return errors;
    }

    private static boolean intersectsAny(Interval interval, List<Interval> all) {
        for (Interval other : all) {
            if (interval.start() < other.end() && other.start() < interval.end()) {
                return true;
            }
        }
        return false;
    }

    public record ValidationResult(boolean valid, List<String> errors) {}

    private record Interval(int start, int end, String source) {}
}
