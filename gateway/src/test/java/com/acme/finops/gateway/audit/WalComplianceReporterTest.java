package com.acme.finops.gateway.audit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WalComplianceReporterTest {

    @Test
    void shouldAggregateMatchingWindowAndFilters() throws Exception {
        Path dir = Files.createTempDirectory("audit-wal-test");
        try {
            Path wal = dir.resolve("audit-test.jsonl");
            Files.write(wal, List.of(
                line(1_000L, "POLICY_CHANGE_PUBLISH", "tenant-a", "bundle-a"),
                line(1_100L, "MASK_APPLIED_EMAIL", "tenant-a", "bundle-a"),
                line(1_200L, "DROP_PACKET", "tenant-a", "bundle-a"),
                line(1_300L, "PARSER_ERROR_PROTO", "tenant-a", "bundle-a"),
                line(1_400L, "ROLLBACK_BUNDLE", "tenant-a", "bundle-a"),
                line(1_500L, "MASK_APPLIED_PHONE", "tenant-b", "bundle-b")
            ), StandardCharsets.UTF_8);

            WalComplianceReporter reporter = new WalComplianceReporter(dir);
            ComplianceSnapshot snapshot = reporter.snapshot(new ComplianceQuery(900L, 1_450L, "tenant-a", "bundle-a"));

            assertEquals(5L, snapshot.totalEvents());
            assertEquals(1L, snapshot.policyChanges());
            assertEquals(1L, snapshot.redactionEvents());
            assertEquals(1L, snapshot.droppedEvents());
            assertEquals(1L, snapshot.parserErrors());
            assertEquals(1L, snapshot.rollbackEvents());
        } finally {
            try (var files = Files.list(dir)) {
                files.forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
            Files.deleteIfExists(dir);
        }
    }

    private static String line(long ts, String eventType, String tenantId, String bundleId) {
        return "{\"eventId\":\"e" + ts + "\",\"tsEpochMs\":\"" + ts + "\",\"eventType\":\"" + eventType + "\","
            + "\"actor\":\"gateway\",\"tenantId\":\"" + tenantId + "\",\"requestId\":\"1\","
            + "\"policyBundleId\":\"" + bundleId + "\",\"policyVersion\":\"v1\",\"outcome\":\"ok\",\"attrs\":{}}";
    }
}

