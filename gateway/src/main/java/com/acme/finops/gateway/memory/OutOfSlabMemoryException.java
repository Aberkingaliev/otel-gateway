package com.acme.finops.gateway.memory;

public class OutOfSlabMemoryException extends Exception {
    public OutOfSlabMemoryException(String message) {
        super(message);
    }
}
