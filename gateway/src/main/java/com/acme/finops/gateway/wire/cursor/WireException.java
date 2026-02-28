package com.acme.finops.gateway.wire.cursor;

import com.acme.finops.gateway.wire.errors.WireErrorCode;

public class WireException extends Exception {
    private final WireErrorCode code;
    private final int position;

    public WireException(WireErrorCode code, int position, String message) {
        super(message);
        this.code = code;
        this.position = position;
    }

    public WireErrorCode code() { return code; }
    public int position() { return position; }
}
