package com.acme.finops.gateway.ci;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RegressionReportWriter {
    public Path write(Path outputDir, RegressionReport report) throws IOException {
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve("regression-" + report.scenario() + ".json");
        String json = toJson(report);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static String toJson(RegressionReport report) {
        return "{\n"
            + "  \"scenario\": \"" + escape(report.scenario()) + "\",\n"
            + "  \"pass\": " + report.pass() + ",\n"
            + "  \"reason\": \"" + escape(report.reason()) + "\",\n"
            + "  \"baseline\": " + benchmarkJson(report.baseline()) + ",\n"
            + "  \"current\": " + benchmarkJson(report.current()) + "\n"
            + "}\n";
    }

    private static String benchmarkJson(BenchmarkSnapshot b) {
        return "{"
            + "\"scenario\":\"" + escape(b.scenario()) + "\","
            + "\"throughputOpsSec\":" + b.throughputOpsSec() + ","
            + "\"p50Nanos\":" + b.p50Nanos() + ","
            + "\"p95Nanos\":" + b.p95Nanos() + ","
            + "\"p99Nanos\":" + b.p99Nanos() + ","
            + "\"allocBytesPerOp\":" + b.allocBytesPerOp() + ","
            + "\"gcTimePercent\":" + b.gcTimePercent()
            + "}";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
