package com.acme.finops.gateway.wire.mutate;

import java.util.Locale;
import java.util.logging.Logger;

public final class MaskWriterFactory {
    private static final Logger LOG = Logger.getLogger(MaskWriterFactory.class.getName());

    private MaskWriterFactory() {
    }

    public static MaskWriter create(String mode) {
        return select(mode).writer();
    }

    public static MaskWriterSelection select(String mode) {
        String normalized = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "off" -> new MaskWriterSelection(
                MaskWriter.scalar(),
                "off",
                "scalar",
                isVectorApiAvailable(),
                false,
                null
            );
            case "on" -> tryCreateSimd("on", true);
            default -> tryCreateSimd("auto", false);
        };
    }

    private static MaskWriterSelection tryCreateSimd(String requestedMode, boolean strict) {
        try {
            if (!isVectorApiAvailable()) {
                throw new IllegalStateException("Vector API module unavailable");
            }
            return new MaskWriterSelection(
                new SimdMaskWriter(),
                requestedMode,
                "simd",
                true,
                strict,
                null
            );
        } catch (Throwable t) {
            if (strict) {
                throw new IllegalStateException("SIMD masking requested but Vector API is unavailable", t);
            }
            LOG.warning("SIMD masking unavailable, falling back to scalar: " + t.getClass().getSimpleName());
            return new MaskWriterSelection(
                MaskWriter.scalar(),
                requestedMode,
                "scalar",
                false,
                false,
                t.getClass().getSimpleName()
            );
        }
    }

    private static boolean isVectorApiAvailable() {
        try {
            // Touch Vector API class so unavailable module/flags fail fast.
            Class.forName("jdk.incubator.vector.ByteVector");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public record MaskWriterSelection(
        MaskWriter writer,
        String requestedMode,
        String effectiveMode,
        boolean simdAvailable,
        boolean strictMode,
        String fallbackReason
    ) {
        public MaskWriterSelection {
            writer = writer == null ? MaskWriter.scalar() : writer;
            requestedMode = normalize(requestedMode, "auto");
            effectiveMode = normalize(effectiveMode, "scalar");
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
}
