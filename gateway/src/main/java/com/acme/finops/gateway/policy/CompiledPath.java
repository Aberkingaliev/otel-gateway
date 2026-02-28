package com.acme.finops.gateway.policy;

public record CompiledPath(PathProgram program, ValueType terminalType, Cardinality terminalCardinality) {}
