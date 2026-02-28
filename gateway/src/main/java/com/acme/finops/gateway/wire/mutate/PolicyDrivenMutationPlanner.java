package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.policy.PolicyActionType;
import com.acme.finops.gateway.policy.PolicyDecision;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.wire.cursor.DefaultEvalScratch;
import com.acme.finops.gateway.wire.cursor.FastWireCursor;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class PolicyDrivenMutationPlanner implements MutationPlanner {
    private static final Logger LOG = Logger.getLogger(PolicyDrivenMutationPlanner.class.getName());
    private static final byte DEFAULT_MASK_BYTE = (byte) '*';

    private final ControlPlanePolicyProvider policyProvider;
    private final int maxMaskOpsPerPacket;
    private final ThreadLocal<FastWireCursor> cursorTl = ThreadLocal.withInitial(FastWireCursor::new);
    private final ThreadLocal<DefaultEvalScratch> scratchTl = ThreadLocal.withInitial(DefaultEvalScratch::new);
    private final ThreadLocal<ValueSpanCollector> collectorTl;

    public PolicyDrivenMutationPlanner(ControlPlanePolicyProvider policyProvider, int maxMaskOpsPerPacket) {
        this.policyProvider = policyProvider;
        this.maxMaskOpsPerPacket = Math.max(1, maxMaskOpsPerPacket);
        this.collectorTl = ThreadLocal.withInitial(() -> new ValueSpanCollector(this.maxMaskOpsPerPacket));
    }

    @Override
    public MutationPlan plan(PacketRef envelope, PolicyDecision decision) {
        long requestId = decision.requestId();
        MutationPlan.Builder builder = new MutationPlan.Builder(requestId)
            .sourceLength(envelope.length())
            .targetLength(envelope.length());

        CompiledMaskingSnapshot snapshot = policyProvider.activeSnapshot();
        if (!snapshot.enabled() || snapshot.rules().isEmpty()) {
            return builder.mode(MutationPlan.PlanMode.NOOP).build();
        }

        SignalKind signal = SignalKind.TRACES;
        if (envelope.descriptor() != null && envelope.descriptor().signalKind() != null) {
            signal = envelope.descriptor().signalKind();
        }

        int emittedMaskOps = 0;
        int[] emittedStarts = new int[maxMaskOpsPerPacket];
        int[] emittedEnds = new int[maxMaskOpsPerPacket];
        try {
            FastWireCursor cursor = cursorTl.get();
            DefaultEvalScratch scratch = scratchTl.get();
            ValueSpanCollector collector = collectorTl.get();

            for (CompiledMaskingRule rule : snapshot.rules()) {
                if (!rule.enabled() || !rule.appliesTo(signal)) {
                    continue;
                }

                collector.reset();
                int matches = rule.selector().collect(envelope, cursor, scratch, collector);
                if (matches <= 0) {
                    continue;
                }

                if (rule.actionType() == PolicyActionType.DROP) {
                    return builder
                        .mode(MutationPlan.PlanMode.DROP)
                        .reasonCode("DROP_RULE_" + rule.ruleId())
                        .targetLength(0)
                        .build();
                }

                if (rule.actionType() != PolicyActionType.REDACT_MASK) {
                    continue;
                }

                for (int i = 0; i < collector.count(); i++) {
                    if (emittedMaskOps >= maxMaskOpsPerPacket) {
                        break;
                    }
                    int offset = collector.offsetAt(i);
                    int length = collector.lengthAt(i);
                    byte[] token = rule.redactionToken();
                    if (token.length > 0 && token.length != length) {
                        if (rule.mismatchMode() == MismatchMode.FAIL_CLOSED) {
                            return builder
                                .mode(MutationPlan.PlanMode.DROP)
                                .reasonCode("MASK_MISMATCH_FAIL_CLOSED_" + rule.ruleId())
                                .targetLength(0)
                                .build();
                        }
                        continue;
                    }
                    MutationPlan.InplaceMaskOp maskOp = new MutationPlan.InplaceMaskOp(
                        offset,
                        length,
                        DEFAULT_MASK_BYTE,
                        token,
                        "MASK_RULE_" + rule.ruleId()
                    );
                    if (overlapsExisting(emittedStarts, emittedEnds, emittedMaskOps, offset, offset + length)) {
                        continue;
                    }
                    builder.addPassA(maskOp);
                    emittedStarts[emittedMaskOps] = offset;
                    emittedEnds[emittedMaskOps] = offset + length;
                    emittedMaskOps++;
                }
                if (emittedMaskOps >= maxMaskOpsPerPacket) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Policy planner failed requestId=" + requestId + ", fail-open NOOP", e);
            return builder.mode(MutationPlan.PlanMode.NOOP).build();
        }

        if (emittedMaskOps == 0) {
            return builder.mode(MutationPlan.PlanMode.NOOP).build();
        }
        return builder.mode(MutationPlan.PlanMode.INPLACE_ONLY).build();
    }

    private static boolean overlapsExisting(int[] starts, int[] ends, int size, int start, int end) {
        for (int i = 0; i < size; i++) {
            if (start < ends[i] && starts[i] < end) {
                return true;
            }
        }
        return false;
    }

}
