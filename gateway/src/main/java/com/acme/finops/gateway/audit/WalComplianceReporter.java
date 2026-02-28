package com.acme.finops.gateway.audit;

import com.acme.finops.gateway.util.JsonCodec;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Compliance snapshot reader over JSONL WAL files produced by {@link AsyncFileAuditSink}.
 */
public final class WalComplianceReporter implements ComplianceReporter {
    private final Path dir;

    public WalComplianceReporter(Path dir) {
        this.dir = Objects.requireNonNull(dir, "dir");
    }

    @Override
    public ComplianceSnapshot snapshot(ComplianceQuery query) {
        long total = 0L;
        long policyChanges = 0L;
        long redactionEvents = 0L;
        long droppedEvents = 0L;
        long parserErrors = 0L;
        long rollbackEvents = 0L;

        if (!Files.exists(dir)) {
            return new ComplianceSnapshot(0, 0, 0, 0, 0, 0);
        }

        try (var stream = Files.list(dir)) {
            for (Path file : stream
                .filter(p -> p.getFileName().toString().startsWith("audit-"))
                .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                .toList()) {
                try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        JsonNode row;
                        try {
                            row = JsonCodec.readTree(line);
                        } catch (Exception ignored) {
                            continue;
                        }
                        if (!matches(row, query)) {
                            continue;
                        }
                        total++;
                        String eventType = normalizedEventType(row);
                        if (eventType.startsWith("POLICY_CHANGE")) policyChanges++;
                        if (eventType.startsWith("MASK_APPLIED")) redactionEvents++;
                        if (eventType.startsWith("DROP")) droppedEvents++;
                        if (eventType.startsWith("PARSER_ERROR")) parserErrors++;
                        if (eventType.startsWith("ROLLBACK")) rollbackEvents++;
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }

        return new ComplianceSnapshot(
            total,
            policyChanges,
            redactionEvents,
            droppedEvents,
            parserErrors,
            rollbackEvents
        );
    }

    private static boolean matches(JsonNode row, ComplianceQuery query) {
        if (row == null || !row.isObject()) {
            return false;
        }
        long ts = optionalLong(row, "tsEpochMs", -1L);
        if (ts < 0 || ts < query.fromEpochMs() || ts > query.toEpochMs()) {
            return false;
        }
        if (!matchesOptionalText(row, "tenantId", query.tenantId())) {
            return false;
        }
        return matchesOptionalText(row, "policyBundleId", query.policyBundleId());
    }

    private static boolean matchesOptionalText(JsonNode row, String field, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String actual = optionalText(row, field, "");
        return expected.equals(actual);
    }

    private static String normalizedEventType(JsonNode row) {
        return optionalText(row, "eventType", "").toUpperCase(Locale.ROOT);
    }

    private static String optionalText(JsonNode row, String field, String fallback) {
        JsonNode v = row.get(field);
        if (v == null || !v.isTextual()) {
            return fallback;
        }
        return v.asText();
    }

    private static long optionalLong(JsonNode row, String field, long fallback) {
        JsonNode v = row.get(field);
        if (v == null) {
            return fallback;
        }
        if (v.isNumber()) {
            return v.asLong();
        }
        if (v.isTextual()) {
            try {
                return Long.parseLong(v.asText().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}

