package com.acme.finops.gateway.util;

import java.util.Map;

/**
 * Environment parsing helpers with consistent defaulting and clamping.
 *
 * <p>Designed for startup/control-path usage. No regex, no reflection.</p>
 */
public final class EnvVars {
    private EnvVars() {
    }

    public static String getOrDefault(String name, String defaultValue) {
        return getOrDefault(System.getenv(), name, defaultValue);
    }

    public static boolean getBoolean(String name, boolean defaultValue) {
        return getBoolean(System.getenv(), name, defaultValue);
    }

    public static int getIntClamped(String name, int defaultValue, int min, int max) {
        return getIntClamped(System.getenv(), name, defaultValue, min, max);
    }

    public static double getDoubleClamped(String name, double defaultValue, double min, double max) {
        return getDoubleClamped(System.getenv(), name, defaultValue, min, max);
    }

    public static String getOrDefault(Map<String, String> env, String name, String defaultValue) {
        String v = env.get(name);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    public static boolean getBoolean(Map<String, String> env, String name, boolean defaultValue) {
        String v = env.get(name);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(v.trim());
    }

    public static int getIntClamped(Map<String, String> env, String name, int defaultValue, int min, int max) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < min) return min;
            return Math.min(parsed, max);
        } catch (NumberFormatException _) {
            return defaultValue;
        }
    }

    public static double getDoubleClamped(Map<String, String> env,
                                          String name,
                                          double defaultValue,
                                          double min,
                                          double max) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            if (parsed < min) return min;
            return Math.min(parsed, max);
        } catch (NumberFormatException _) {
            return defaultValue;
        }
    }
}
