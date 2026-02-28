package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.policy.CompiledPath;
import com.acme.finops.gateway.policy.PolicyDecision;
import com.acme.finops.gateway.wire.cursor.BytecodeCompiledPathEvaluator;
import com.acme.finops.gateway.wire.cursor.DefaultEvalScratch;
import com.acme.finops.gateway.wire.cursor.EvalResult;
import com.acme.finops.gateway.wire.cursor.FastWireCursor;

import java.util.Objects;

/**
 * Week-1 FinOps planner:
 * - runs compiled-path evaluator (brain) on payload
 * - emits DROP/NOOP mutation plan (surgeon input)
 */
public final class HealthcheckSuccessDropPlanner implements MutationPlanner {
    private final BytecodeCompiledPathEvaluator evaluator;
    private final CompiledPath healthcheckPath;
    private final ThreadLocal<FastWireCursor> cursor = ThreadLocal.withInitial(FastWireCursor::new);
    private final ThreadLocal<DefaultEvalScratch> scratch = ThreadLocal.withInitial(DefaultEvalScratch::new);

    public HealthcheckSuccessDropPlanner(BytecodeCompiledPathEvaluator evaluator,
                                         CompiledPath healthcheckPath) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.healthcheckPath = Objects.requireNonNull(healthcheckPath, "healthcheckPath");
    }

    @Override
    public MutationPlan plan(PacketRef envelope, PolicyDecision decision) {
        FastWireCursor wireCursor = cursor.get();
        wireCursor.reset(envelope.segment(), envelope.offset(), envelope.length());

        EvalResult result = evaluator.evaluate(healthcheckPath, wireCursor, scratch.get());
        boolean shouldDrop = result instanceof EvalResult.MatchFound;

        long requestId = decision.requestId();

        MutationPlan.Builder b = new MutationPlan.Builder(requestId).sourceLength(envelope.length());

        if (shouldDrop) {
            return b.mode(MutationPlan.PlanMode.DROP)
                .reasonCode("FINOPS_HEALTHCHECK_DROP")
                .targetLength(0)
                .build();
        }

        return b.mode(MutationPlan.PlanMode.NOOP)
            .targetLength(envelope.length())
            .build();
    }

}
