package com.acme.finops.gateway.wire.mutate;

public sealed interface MutationOp permits KeepSlice, SkipSlice, InplaceMask, ReencodeField {}
