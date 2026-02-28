package com.acme.finops.gateway.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvVarsTest {

    @Test
    void shouldReturnDefaultForMissingOrBlank() {
        Map<String, String> env = Map.of("EMPTY", "   ");
        assertEquals("fallback", EnvVars.getOrDefault(env, "MISSING", "fallback"));
        assertEquals("fallback", EnvVars.getOrDefault(env, "EMPTY", "fallback"));
    }

    @Test
    void shouldParseBooleanWithDefault() {
        Map<String, String> env = Map.of("ENABLED", "true", "DISABLED", "false");
        assertTrue(EnvVars.getBoolean(env, "ENABLED", false));
        assertFalse(EnvVars.getBoolean(env, "DISABLED", true));
        assertTrue(EnvVars.getBoolean(env, "MISSING", true));
    }

    @Test
    void shouldClampIntAndFallbackOnMalformed() {
        Map<String, String> env = Map.of(
            "LOW", "-10",
            "HIGH", "9000",
            "OK", "42",
            "BAD", "abc"
        );
        assertEquals(1, EnvVars.getIntClamped(env, "LOW", 10, 1, 100));
        assertEquals(100, EnvVars.getIntClamped(env, "HIGH", 10, 1, 100));
        assertEquals(42, EnvVars.getIntClamped(env, "OK", 10, 1, 100));
        assertEquals(10, EnvVars.getIntClamped(env, "BAD", 10, 1, 100));
        assertEquals(10, EnvVars.getIntClamped(env, "MISSING", 10, 1, 100));
    }

    @Test
    void shouldClampDoubleAndAcceptWhitespace() {
        Map<String, String> env = Map.of(
            "LOW", "  -0.5 ",
            "HIGH", "  9.5 ",
            "OK", "  0.25 ",
            "BAD", "oops"
        );
        assertEquals(0.0d, EnvVars.getDoubleClamped(env, "LOW", 1.0d, 0.0d, 1.0d));
        assertEquals(1.0d, EnvVars.getDoubleClamped(env, "HIGH", 0.0d, 0.0d, 1.0d));
        assertEquals(0.25d, EnvVars.getDoubleClamped(env, "OK", 0.0d, 0.0d, 1.0d));
        assertEquals(0.75d, EnvVars.getDoubleClamped(env, "BAD", 0.75d, 0.0d, 1.0d));
        assertEquals(0.75d, EnvVars.getDoubleClamped(env, "MISSING", 0.75d, 0.0d, 1.0d));
    }
}
