package com.acme.finops.gateway.wire.cursor;

import com.acme.finops.gateway.policy.CompiledPath;

public interface CompiledPathEvaluator {
    EvalResult evaluate(CompiledPath path, WireCursor cursor, EvalScratch scratch);
}
