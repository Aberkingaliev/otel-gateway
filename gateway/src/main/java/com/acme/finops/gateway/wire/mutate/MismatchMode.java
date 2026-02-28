package com.acme.finops.gateway.wire.mutate;

import java.util.Locale;

enum MismatchMode {
    SKIP,
    FAIL_CLOSED;

    static MismatchMode fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return SKIP;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "fail_closed", "fail-closed", "closed" -> FAIL_CLOSED;
            default -> SKIP;
        };
    }
}
