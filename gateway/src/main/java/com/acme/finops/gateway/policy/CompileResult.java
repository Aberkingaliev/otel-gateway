package com.acme.finops.gateway.policy;

public sealed interface CompileResult permits CompileResult.Success, CompileResult.Failure {
    record Success(CompiledPath compiledPath) implements CompileResult {}
    record Failure(String code, String message, int position) implements CompileResult {}
}
