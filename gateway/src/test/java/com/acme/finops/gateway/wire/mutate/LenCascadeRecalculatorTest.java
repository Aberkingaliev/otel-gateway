package com.acme.finops.gateway.wire.mutate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LenCascadeRecalculatorTest {

    @Test
    void shouldComputeVarintSizesOnBoundaryValues() {
        assertEquals(1, LenCascadeRecalculator.varintSize(0));
        assertEquals(1, LenCascadeRecalculator.varintSize(127));
        assertEquals(2, LenCascadeRecalculator.varintSize(128));
        assertEquals(2, LenCascadeRecalculator.varintSize(16_383));
        assertEquals(3, LenCascadeRecalculator.varintSize(16_384));
        assertEquals(3, LenCascadeRecalculator.varintSize(2_097_151));
        assertEquals(4, LenCascadeRecalculator.varintSize(2_097_152));
        assertEquals(4, LenCascadeRecalculator.varintSize(268_435_455));
        assertEquals(5, LenCascadeRecalculator.varintSize(268_435_456));
        assertEquals(5, LenCascadeRecalculator.varintSize(Integer.MAX_VALUE));
    }
}
