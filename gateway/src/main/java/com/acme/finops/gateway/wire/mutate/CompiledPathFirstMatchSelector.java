package com.acme.finops.gateway.wire.mutate;

import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.policy.CompiledPath;
import com.acme.finops.gateway.policy.ValueType;
import com.acme.finops.gateway.wire.cursor.BytecodeCompiledPathEvaluator;
import com.acme.finops.gateway.wire.cursor.EvalResult;
import com.acme.finops.gateway.wire.cursor.EvalScratch;
import com.acme.finops.gateway.wire.cursor.FastWireCursor;

import java.util.Objects;

final class CompiledPathFirstMatchSelector implements ValueSpanSelector {
    private final BytecodeCompiledPathEvaluator evaluator;
    private final CompiledPath compiledPath;
    private final ThreadLocal<CollectorMatchConsumer> consumerTl = ThreadLocal.withInitial(CollectorMatchConsumer::new);

    CompiledPathFirstMatchSelector(BytecodeCompiledPathEvaluator evaluator, CompiledPath compiledPath) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.compiledPath = Objects.requireNonNull(compiledPath, "compiledPath");
    }

    @Override
    public int collect(PacketRef packetRef,
                       FastWireCursor cursor,
                       EvalScratch scratch,
                       ValueSpanCollector collector) {
        collector.reset();
        // For drop rules we only need "match yes/no", for masking only STRING/BYTES are collected.
        if (compiledPath.terminalType() != ValueType.STRING && compiledPath.terminalType() != ValueType.BYTES) {
            cursor.reset(packetRef.segment(), packetRef.offset(), packetRef.length());
            EvalResult result = evaluator.evaluate(compiledPath, cursor, scratch);
            return (result instanceof EvalResult.MatchFound) ? 1 : 0;
        }

        CollectorMatchConsumer consumer = consumerTl.get();
        consumer.reset(packetRef.offset(), collector);
        return evaluator.evaluateAll(
            compiledPath,
            packetRef.segment(),
            packetRef.offset(),
            packetRef.length(),
            scratch,
            consumer
        );
    }

    private static final class CollectorMatchConsumer implements BytecodeCompiledPathEvaluator.MatchConsumer {
        private int packetOffset;
        private ValueSpanCollector collector;

        void reset(int packetOffset, ValueSpanCollector collector) {
            this.packetOffset = packetOffset;
            this.collector = collector;
        }

        @Override
        public boolean onMatch(int valueOffset, int valueLength, int terminalTypeCode) {
            if (collector == null || collector.count() >= collector.capacity()) {
                return false;
            }
            return collector.add(valueOffset - packetOffset, valueLength);
        }
    }
}
