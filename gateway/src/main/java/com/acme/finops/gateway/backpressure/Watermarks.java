package com.acme.finops.gateway.backpressure;

public record Watermarks(int low, int high, int critical) {}
