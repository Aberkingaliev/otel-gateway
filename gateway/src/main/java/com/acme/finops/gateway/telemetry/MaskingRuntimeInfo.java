package com.acme.finops.gateway.telemetry;

import java.util.Locale;

/**
 * Runtime masking selection info exposed to metrics for machine-readable diagnostics.
 */
public record MaskingRuntimeInfo(
    String requestedMode,
    String effectiveWriter,
    boolean simdAvailable,
    boolean strictMode,
    String fallbackReason
) {
    public static final MaskingRuntimeInfo UNKNOWN = new MaskingRuntimeInfo(
        "unknown",
        "unknown",
        false,
        false,
        null
    );

    public MaskingRuntimeInfo {
        requestedMode = normalize(requestedMode, "unknown");
        effectiveWriter = normalize(effectiveWriter, "unknown");
        fallbackReason = normalizeNullable(fallbackReason);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
