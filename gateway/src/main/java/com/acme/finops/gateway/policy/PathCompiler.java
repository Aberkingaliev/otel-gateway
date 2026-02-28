package com.acme.finops.gateway.policy;

import com.acme.finops.gateway.wire.SchemaId;
import com.acme.finops.gateway.wire.SignalType;

/**
 * Compiles human-readable path expressions (e.g. {@code "resource.attributes.host"})
 * into an executable form suitable for wire-level field evaluation.
 *
 * <p>Implementations should be thread-safe and may cache compiled results.
 */
public interface PathCompiler {
    /**
     * Compiles a single path expression for the given schema and signal type.
     *
     * @param sourcePath  the dotted path expression to compile
     * @param schemaId    the protobuf schema the path targets
     * @param signalType  the OTLP signal type (traces, metrics, logs)
     * @return the compilation result, which may indicate errors
     */
    CompileResult compile(String sourcePath, SchemaId schemaId, SignalType signalType);
}
