package com.acme.finops.gateway.wire.mutate;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationPlanValidatorTest {

    @Test
    void shouldAcceptValidNoopPlan() {
        MutationPlan plan = new MutationPlan.Builder(1L)
            .mode(MutationPlan.PlanMode.NOOP)
            .sourceLength(3)
            .targetLength(3)
            .build();

        MutationPlanValidator.ValidationResult result = new MutationPlanValidator().validate(plan, 3);
        assertTrue(result.valid(), result.errors()::toString);
    }

    @Test
    void shouldRejectNegativeSourceLengthAndDerivedNegativeTargetLength() {
        MutationPlan plan = new MutationPlan.Builder(1L)
            .mode(MutationPlan.PlanMode.NOOP)
            .sourceLength(0)
            .targetLength(0)
            .build();

        MutationPlanValidator.ValidationResult result = new MutationPlanValidator().validate(plan, -1);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("sourceLength must be >= 0")));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("targetLength must be >= 0")));
    }

    @Test
    void shouldRejectInplaceOnlyWhenLengthDeltaChangesFrameSize() {
        MutationPlan plan = new MutationPlan.Builder(1L)
            .mode(MutationPlan.PlanMode.INPLACE_ONLY)
            .sourceLength(10)
            .targetLength(10)
            .addLengthDelta(new MutationPlan.LengthDelta(
                0,
                -1,
                0,
                0,
                1,
                2
            ))
            .build();

        MutationPlanValidator.ValidationResult result = new MutationPlanValidator().validate(plan, 10);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("INPLACE_ONLY mode is invalid")));
    }

    @Test
    void shouldDetectTokenLengthMismatchAndOverlapWithLenChangingZone() {
        byte[] token = "***".getBytes(StandardCharsets.UTF_8);
        MutationPlan plan = new MutationPlan.Builder(1L)
            .mode(MutationPlan.PlanMode.NOOP)
            .sourceLength(10)
            .targetLength(10)
            .addLengthDelta(new MutationPlan.LengthDelta(
                0,
                -1,
                0,
                0,
                1,
                2
            ))
            .addPassA(new MutationPlan.InplaceMaskOp(0, 4, (byte) '*', token, "mask"))
            .build();

        MutationPlanValidator.ValidationResult result = new MutationPlanValidator().validate(plan, 10);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("token length must match")));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("overlaps LEN-changing frame")));
    }

    @Test
    void shouldDetectOutOfBoundsAndOverlapsAcrossPasses() {
        MutationPlan plan = new MutationPlan.Builder(1L)
            .mode(MutationPlan.PlanMode.REFRAME)
            .sourceLength(6)
            .targetLength(6)
            .addPassA(new MutationPlan.InplaceMaskOp(0, 4, (byte) 'x', "a"))
            .addPassA(new MutationPlan.InplaceMaskOp(2, 4, (byte) 'x', "b"))
            .addPassB(new MutationPlan.SliceCopyOp(0, 4, 0))
            .addPassB(new MutationPlan.OverwriteOp(2, new byte[]{1, 2, 3, 4, 5}, "ovr"))
            .build();

        MutationPlanValidator.ValidationResult result = new MutationPlanValidator().validate(plan, 6);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("passA overlap")));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("passB.overwrite.dst out of bounds")));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("passB.dst overlap")));
    }
}

