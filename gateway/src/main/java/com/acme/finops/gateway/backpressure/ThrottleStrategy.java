package com.acme.finops.gateway.backpressure;

/**
 * Maps current queue depth to a throttle mode (pass, shed, or pause)
 * using configured watermark thresholds.
 *
 * <p>Implementations must be thread-safe and allocation-free on the hot path.
 */
public interface ThrottleStrategy {
    /**
     * Evaluates the throttle mode for the current queue depth.
     *
     * @param depth      current number of items in the queue
     * @param watermarks configured low/high watermark thresholds
     * @param nowNanos   monotonic timestamp in nanoseconds
     * @return the throttle decision (pass, shed, or pause)
     */
    ThrottleDecision onDepth(int depth, Watermarks watermarks, long nowNanos);
}
