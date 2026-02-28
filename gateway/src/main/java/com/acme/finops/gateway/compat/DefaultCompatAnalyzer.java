package com.acme.finops.gateway.compat;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DefaultCompatAnalyzer implements CompatAnalyzer {
    private static final Pattern VERSION_PATTERN = Pattern.compile("v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    @Override
    public CompatResult analyze(CompatInput input) {
        Objects.requireNonNull(input, "input");
        if (input.sourceSchema() == null || input.targetSchema() == null) {
            return new CompatResult.Incompatible(input.requestId(), 1000);
        }
        if (!safeEq(input.sourceSchema().namespace(), input.targetSchema().namespace())) {
            return new CompatResult.Incompatible(input.requestId(), 1001);
        }

        String sourceSignal = normalizeSignal(input.sourceSchema().name());
        String targetSignal = normalizeSignal(input.targetSchema().name());
        if (!safeEq(sourceSignal, targetSignal)) {
            return new CompatResult.Incompatible(input.requestId(), 1002);
        }

        if (input.sourceSchema().equals(input.targetSchema())) {
            return new CompatResult.Compatible(input.requestId());
        }

        Version sourceVersion = parseVersion(input.sourceSchema().version());
        Version targetVersion = parseVersion(input.targetSchema().version());
        if (targetVersion.major < sourceVersion.major) {
            return new CompatResult.Incompatible(input.requestId(), 1003);
        }
        if (targetVersion.major > sourceVersion.major) {
            return new CompatResult.RewriteRequired(input.requestId(), rewriteId(input));
        }
        if (targetVersion.minor != sourceVersion.minor || targetVersion.patch != sourceVersion.patch) {
            return new CompatResult.RewriteRequired(input.requestId(), rewriteId(input));
        }
        return new CompatResult.Compatible(input.requestId());
    }

    private static boolean safeEq(String a, String b) {
        return Objects.equals(a, b);
    }

    private static int rewriteId(CompatInput input) {
        return Math.abs((input.sourceSchema() + "->" + input.targetSchema()).hashCode());
    }

    private static String normalizeSignal(String raw) {
        if (raw == null) return "";
        String v = raw.trim().toLowerCase();
        return switch (v) {
            case "trace", "traces" -> "traces";
            case "metric", "metrics" -> "metrics";
            case "log", "logs" -> "logs";
            default -> v;
        };
    }

    private static Version parseVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Version(0, 0, 0);
        }
        Matcher m = VERSION_PATTERN.matcher(raw.trim().toLowerCase());
        if (!m.matches()) {
            return new Version(0, 0, 0);
        }
        int major = parseGroup(m, 1);
        int minor = parseGroup(m, 2);
        int patch = parseGroup(m, 3);
        return new Version(major, minor, patch);
    }

    private static int parseGroup(Matcher m, int group) {
        String value = m.group(group);
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Integer.parseInt(value);
    }

    private record Version(int major, int minor, int patch) {
    }
}
