package com.acme.finops.gateway.wire.cursor;

enum RuntimeMismatchCode {
    PATH_NOT_PRESENT,
    WIRE_TYPE_MISMATCH,
    ONEOF_BRANCH_MISMATCH,
    MAP_KEY_NOT_FOUND,
    REPEATED_EMPTY,
    MALFORMED_PROTO,
    SCHEMA_DRIFT_DETECTED
}
