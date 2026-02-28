package com.acme.finops.gateway.ci;

import com.acme.finops.gateway.util.JsonCodec;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PerfRegressionCheckMain {
    private PerfRegressionCheckMain() {
    }

    public static void main(String[] args) throws Exception {
        Path input = args.length > 0 ? Path.of(args[0]) : Path.of("ci-input/perf-regression-input.json");
        if (!Files.exists(input)) {
            throw new IllegalArgumentException("perf input file not found: " + input);
        }
        JsonNode root = JsonCodec.readTree(Files.readString(input));
        BenchmarkSnapshot baseline = parseBenchmark(requiredObject(root, "baseline"), "baseline");
        BenchmarkSnapshot current = parseBenchmark(requiredObject(root, "current"), "current");
        PerfBudget budget = parseBudget(requiredObject(root, "budget"));

        DefaultRegressionChecker checker = new DefaultRegressionChecker();
        RegressionResult result = checker.check(current, baseline, budget);
        boolean pass = result instanceof RegressionResult.Pass;
        String reason = pass ? "ok" : ((RegressionResult.Fail) result).reason();

        RegressionReport report = new RegressionReport(current.scenario(), pass, reason, baseline, current);
        Path outputDir = Path.of("build", "reports", "perf");
        Path reportFile = new RegressionReportWriter().write(outputDir, report);
        System.out.println("regressionResult=" + result + " report=" + reportFile);
        if (!pass) {
            System.exit(2);
        }
    }

    private static JsonNode requiredObject(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("missing required object field: " + field);
        }
        return node;
    }

    private static BenchmarkSnapshot parseBenchmark(JsonNode node, String fallbackScenario) {
        return new BenchmarkSnapshot(
            optionalText(node, "scenario", fallbackScenario),
            requiredDouble(node, "throughputOpsSec"),
            requiredLong(node, "p50Nanos"),
            requiredLong(node, "p95Nanos"),
            requiredLong(node, "p99Nanos"),
            requiredLong(node, "allocBytesPerOp"),
            requiredDouble(node, "gcTimePercent")
        );
    }

    private static PerfBudget parseBudget(JsonNode node) {
        return new PerfBudget(
            optionalText(node, "scenario", "default"),
            requiredDouble(node, "minThroughputOpsSec"),
            requiredLong(node, "maxP99Nanos"),
            requiredLong(node, "maxAllocBytesPerOp"),
            requiredDouble(node, "maxGcTimePercent")
        );
    }

    private static String optionalText(JsonNode root, String field, String fallback) {
        JsonNode node = root.get(field);
        return node == null || !node.isTextual() || node.asText().isBlank() ? fallback : node.asText().trim();
    }

    private static long requiredLong(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isNumber()) {
            throw new IllegalArgumentException("missing required numeric field: " + field);
        }
        return node.asLong();
    }

    private static double requiredDouble(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isNumber()) {
            throw new IllegalArgumentException("missing required numeric field: " + field);
        }
        return node.asDouble();
    }
}

