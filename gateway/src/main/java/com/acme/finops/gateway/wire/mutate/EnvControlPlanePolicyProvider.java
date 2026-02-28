package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.policy.CompileResult;
import com.acme.finops.gateway.policy.CompiledPath;
import com.acme.finops.gateway.policy.OtlpPathCompiler;
import com.acme.finops.gateway.policy.PathStringPool;
import com.acme.finops.gateway.policy.PolicyActionType;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.util.EnvVars;
import com.acme.finops.gateway.util.GatewayEnvKeys;
import com.acme.finops.gateway.util.OtlpEndpoints;
import com.acme.finops.gateway.wire.SchemaId;
import com.acme.finops.gateway.wire.SignalType;
import com.acme.finops.gateway.wire.cursor.BytecodeCompiledPathEvaluator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class EnvControlPlanePolicyProvider implements ControlPlanePolicyProvider {
    private static final Logger LOG = Logger.getLogger(EnvControlPlanePolicyProvider.class.getName());
    private static final String[] RESOURCE_ATTRIBUTE_PREFIXES = new String[]{
        "resource.attributes.",
        "resourcespans.resource.attributes.",
        "resourcemetrics.resource.attributes.",
        "resourcelogs.resource.attributes."
    };

    private final AtomicReference<CompiledMaskingSnapshot> snapshotRef = new AtomicReference<>(CompiledMaskingSnapshot.disabled());
    private final PathStringPool pool = new PathStringPool();
    private final OtlpPathCompiler compiler = new OtlpPathCompiler(pool);
    private final BytecodeCompiledPathEvaluator evaluator = new BytecodeCompiledPathEvaluator(pool::resolve);

    private EnvControlPlanePolicyProvider() {
    }

    public static EnvControlPlanePolicyProvider fromEnvironment() {
        EnvControlPlanePolicyProvider provider = new EnvControlPlanePolicyProvider();
        provider.refresh(System.getenv());
        return provider;
    }

    void refresh(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        boolean enabled = EnvVars.getBoolean(env, GatewayEnvKeys.GATEWAY_MASKING_ENABLED, false);
        if (!enabled) {
            snapshotRef.set(CompiledMaskingSnapshot.disabled());
            return;
        }

        String rawRules = EnvVars.getOrDefault(env, GatewayEnvKeys.GATEWAY_MASKING_RULES, "");
        List<CompiledMaskingRule> compiledRules = compileRules(rawRules);
        snapshotRef.set(new CompiledMaskingSnapshot(System.currentTimeMillis(), true, compiledRules));
    }

    @Override
    public CompiledMaskingSnapshot activeSnapshot() {
        return snapshotRef.get();
    }

    private List<CompiledMaskingRule> compileRules(String rawRules) {
        if (rawRules == null || rawRules.isBlank()) {
            return List.of();
        }
        String[] defs = rawRules.split(";");
        List<CompiledMaskingRule> out = new ArrayList<>(defs.length);
        for (String def : defs) {
            CompiledMaskingRule compiled = compileRule(def.trim());
            if (compiled != null) {
                out.add(compiled);
            }
        }
        out.sort(Comparator.comparingInt(CompiledMaskingRule::priority));
        return out;
    }

    private CompiledMaskingRule compileRule(String ruleDef) {
        if (ruleDef.isBlank()) {
            return null;
        }

        // format:
        // ruleId|signal|action|sourcePath|redactionToken|priority|onMismatch|enabled
        String[] parts = ruleDef.split("\\|", -1);
        if (parts.length < 4) {
            LOG.warning("Skipping malformed masking rule: " + ruleDef);
            return null;
        }
        String ruleId = parts[0].isBlank() ? "rule-" + Math.abs(ruleDef.hashCode()) : parts[0].trim();

        SignalParse signalParse = parseSignal(parts[1]);
        PolicyActionType action = parseAction(parts[2]);
        if (action == null) {
            LOG.warning("Skipping rule " + ruleId + ": unsupported action=" + parts[2]);
            return null;
        }

        String sourcePath = parts[3].trim();
        String redactionToken = parts.length > 4 ? parts[4] : "";
        int priority = parts.length > 5 ? parseInt(parts[5], 100, Integer.MIN_VALUE, Integer.MAX_VALUE) : 100;
        MismatchMode mismatchMode = parts.length > 6 ? MismatchMode.fromString(parts[6]) : MismatchMode.SKIP;
        boolean enabled = parts.length <= 7 || Boolean.parseBoolean(parts[7]);
        if (!enabled) {
            return null;
        }

        ValueSpanSelector selector = buildSelector(sourcePath, signalParse);
        if (selector == null) {
            LOG.warning("Skipping rule " + ruleId + ": unsupported path=" + sourcePath);
            return null;
        }

        byte[] tokenBytes = action == PolicyActionType.REDACT_MASK
            ? redactionToken.getBytes(StandardCharsets.UTF_8)
            : new byte[0];

        return new CompiledMaskingRule(
            ruleId,
            priority,
            true,
            signalParse.signalKind(),
            signalParse.allSignals(),
            action,
            tokenBytes,
            mismatchMode,
            selector
        );
    }

    private ValueSpanSelector buildSelector(String sourcePath, SignalParse signalParse) {
        String resourceAttrKey = tryExtractResourceAttributeKey(sourcePath);
        if (resourceAttrKey != null) {
            return new ResourceAttributeSpanSelector(resourceAttrKey);
        }

        if (signalParse.allSignals()) {
            return null;
        }
        SignalType signalType = toSignalType(signalParse.signalKind());
        CompileResult result = compiler.compile(
            sourcePath,
            new SchemaId(OtlpEndpoints.SCHEMA_NAME_OTLP, signalType.name().toLowerCase(Locale.ROOT), OtlpEndpoints.SCHEMA_VERSION_V1),
            signalType
        );
        if (!(result instanceof CompileResult.Success success)) {
            return null;
        }
        CompiledPath compiledPath = success.compiledPath();
        return new CompiledPathFirstMatchSelector(evaluator, compiledPath);
    }

    private static String tryExtractResourceAttributeKey(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return null;
        }
        String normalized = sourcePath.trim();
        String low = normalized.toLowerCase(Locale.ROOT);
        for (String p : RESOURCE_ATTRIBUTE_PREFIXES) {
            if (low.startsWith(p)) {
                String key = normalized.substring(p.length());
                return key.isBlank() ? null : key;
            }
        }
        return null;
    }

    private static SignalParse parseSignal(String raw) {
        if (raw == null || raw.isBlank()) {
            return new SignalParse(SignalKind.TRACES, true);
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (value.equals("ALL")) {
            return new SignalParse(SignalKind.TRACES, true);
        }
        return switch (value) {
            case "TRACES" -> new SignalParse(SignalKind.TRACES, false);
            case "METRICS" -> new SignalParse(SignalKind.METRICS, false);
            case "LOGS" -> new SignalParse(SignalKind.LOGS, false);
            default -> new SignalParse(SignalKind.TRACES, true);
        };
    }

    private static PolicyActionType parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "DROP" -> PolicyActionType.DROP;
            case "REDACT_MASK", "MASK" -> PolicyActionType.REDACT_MASK;
            default -> null;
        };
    }

    private static int parseInt(String raw, int defaultValue, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < min) return min;
            return Math.min(parsed, max);
        } catch (NumberFormatException ignore) {
            return defaultValue;
        }
    }

    private static SignalType toSignalType(SignalKind signalKind) {
        return switch (signalKind) {
            case TRACES -> SignalType.TRACES;
            case METRICS -> SignalType.METRICS;
            case LOGS -> SignalType.LOGS;
        };
    }

    private record SignalParse(SignalKind signalKind, boolean allSignals) {}
}
