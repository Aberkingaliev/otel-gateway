package com.acme.finops.gateway.ci;

import com.acme.finops.gateway.compat.ConformancePlan;
import com.acme.finops.gateway.compat.ConformanceResult;
import com.acme.finops.gateway.compat.DefaultConformanceHarness;
import com.acme.finops.gateway.util.JsonCodec;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ConformanceHarnessMain {
    private ConformanceHarnessMain() {
    }

    public static void main(String[] args) throws Exception {
        Path input = args.length > 0 ? Path.of(args[0]) : Path.of("ci-input/conformance-plan.json");
        if (!Files.exists(input)) {
            throw new IllegalArgumentException("conformance input file not found: " + input);
        }
        JsonNode root = JsonCodec.readTree(Files.readString(input));
        String profileId = requiredText(root, "profileId");
        int corpusSize = requiredInt(root, "corpusSize");
        int passedCases = optionalInt(root, "passedCases", corpusSize);
        int failedCases = optionalInt(root, "failedCases", 0);
        double requiredPassRate = optionalDouble(root, "requiredPassRate", 0.99d);

        ConformancePlan plan = new ConformancePlan(profileId, corpusSize, passedCases, failedCases, requiredPassRate);
        ConformanceResult result = new DefaultConformanceHarness().run(plan);
        System.out.println("conformanceResult=" + result);
        if (result instanceof ConformanceResult.Failed || result instanceof ConformanceResult.Aborted) {
            System.exit(2);
        }
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("missing required text field: " + field);
        }
        return node.asText().trim();
    }

    private static int requiredInt(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isNumber()) {
            throw new IllegalArgumentException("missing required numeric field: " + field);
        }
        return node.asInt();
    }

    private static int optionalInt(JsonNode root, String field, int fallback) {
        JsonNode node = root.get(field);
        return node == null || !node.isNumber() ? fallback : node.asInt();
    }

    private static double optionalDouble(JsonNode root, String field, double fallback) {
        JsonNode node = root.get(field);
        return node == null || !node.isNumber() ? fallback : node.asDouble();
    }
}

