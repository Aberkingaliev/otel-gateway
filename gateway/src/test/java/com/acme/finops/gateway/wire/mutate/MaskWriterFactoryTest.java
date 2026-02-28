package com.acme.finops.gateway.wire.mutate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskWriterFactoryTest {

    @Test
    void shouldSelectScalarWhenModeIsOff() {
        MaskWriterFactory.MaskWriterSelection selection = MaskWriterFactory.select("off");

        assertEquals("off", selection.requestedMode());
        assertEquals("scalar", selection.effectiveMode());
        assertInstanceOf(ScalarMaskWriter.class, selection.writer());
    }

    @Test
    void shouldSelectAutoWriterConsistentWithSimdAvailability() {
        MaskWriterFactory.MaskWriterSelection selection = MaskWriterFactory.select("auto");

        assertEquals("auto", selection.requestedMode());
        if (selection.simdAvailable()) {
            assertEquals("simd", selection.effectiveMode());
            assertInstanceOf(SimdMaskWriter.class, selection.writer());
        } else {
            assertEquals("scalar", selection.effectiveMode());
            assertInstanceOf(ScalarMaskWriter.class, selection.writer());
        }
    }

    @Test
    void shouldFailFastWhenStrictSimdIsUnavailable() {
        MaskWriterFactory.MaskWriterSelection auto = MaskWriterFactory.select("auto");
        if (auto.simdAvailable()) {
            MaskWriterFactory.MaskWriterSelection strict = MaskWriterFactory.select("on");
            assertEquals("on", strict.requestedMode());
            assertEquals("simd", strict.effectiveMode());
            assertTrue(strict.strictMode());
            assertInstanceOf(SimdMaskWriter.class, strict.writer());
            return;
        }

        assertThrows(IllegalStateException.class, () -> MaskWriterFactory.select("on"));
    }
}
