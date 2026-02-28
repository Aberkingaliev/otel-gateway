package com.acme.finops.gateway.wire.cursor;

public sealed interface EvalResult permits EvalResult.MatchFound, EvalResult.NoMatch, EvalResult.EvalError {
    record MatchFound(int terminalTypeCode) implements EvalResult {}
    record NoMatch(RuntimeMismatchCode reason) implements EvalResult {}
    record EvalError(RuntimeMismatchCode code, int position) implements EvalResult {}
}
