package com.acme.finops.gateway.backpressure;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Queue-depth based throttling strategy with simple hysteresis.
 */
public final class WatermarkThrottleStrategy implements ThrottleStrategy {
    private final double shedLightRatio;
    private final double shedAggressiveRatio;
    private final long pauseNanos;
    private final AtomicReference<ThrottleMode> previousMode = new AtomicReference<>(ThrottleMode.PASS);

    public WatermarkThrottleStrategy(double shedLightRatio,
                                     double shedAggressiveRatio,
                                     long pauseNanos) {
        this.shedLightRatio = clampRatio(shedLightRatio);
        this.shedAggressiveRatio = clampRatio(shedAggressiveRatio);
        this.pauseNanos = Math.max(0L, pauseNanos);
    }

    @Override
    public ThrottleDecision onDepth(int depth, Watermarks watermarks, long nowNanos) {
        Objects.requireNonNull(watermarks, "watermarks");
        int low = Math.max(0, watermarks.low());
        int high = Math.max(low, watermarks.high());
        int critical = Math.max(high, watermarks.critical());

        ThrottleMode next;
        if (depth >= critical) {
            next = ThrottleMode.PAUSE_INGRESS;
        } else if (depth >= high) {
            next = ThrottleMode.SHED_AGGRESSIVE;
        } else if (depth >= low) {
            next = ThrottleMode.SHED_LIGHT;
        } else {
            next = ThrottleMode.PASS;
        }

        // Hysteresis: once we entered shed/pause, keep mode until we're below low watermark.
        ThrottleMode prev = previousMode.get();
        if (prev != ThrottleMode.PASS && depth >= low && next == ThrottleMode.PASS) {
            next = prev;
        }
        previousMode.set(next);

        return switch (next) {
            case PASS -> new ThrottleDecision(ThrottleMode.PASS, 0.0d, 0L, "depth_below_low");
            case SHED_LIGHT -> new ThrottleDecision(ThrottleMode.SHED_LIGHT, shedLightRatio, 0L, "depth_above_low");
            case SHED_AGGRESSIVE -> new ThrottleDecision(ThrottleMode.SHED_AGGRESSIVE, shedAggressiveRatio, 0L, "depth_above_high");
            case PAUSE_INGRESS -> new ThrottleDecision(ThrottleMode.PAUSE_INGRESS, 1.0d, pauseNanos, "depth_above_critical");
        };
    }

    private static double clampRatio(double ratio) {
        if (ratio < 0.0d) return 0.0d;
        return Math.min(ratio, 1.0d);
    }
}
