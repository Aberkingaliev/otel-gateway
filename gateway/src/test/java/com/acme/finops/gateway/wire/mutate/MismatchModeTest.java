package com.acme.finops.gateway.wire.mutate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MismatchModeTest {

    @Test
    void shouldParseMismatchModeFromString() {
        assertEquals(MismatchMode.SKIP, MismatchMode.fromString(null));
        assertEquals(MismatchMode.SKIP, MismatchMode.fromString("   "));
        assertEquals(MismatchMode.FAIL_CLOSED, MismatchMode.fromString("fail_closed"));
        assertEquals(MismatchMode.FAIL_CLOSED, MismatchMode.fromString("FAIL-CLOSED"));
        assertEquals(MismatchMode.FAIL_CLOSED, MismatchMode.fromString("closed"));
        assertEquals(MismatchMode.SKIP, MismatchMode.fromString("unknown"));
    }
}

