package com.acme.finops.gateway.backpressure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WatermarkThrottleStrategyTest {

    @Test
    void shouldTransitionAcrossWatermarks() {
        WatermarkThrottleStrategy strategy = new WatermarkThrottleStrategy(0.1d, 0.5d, 7_000_000L);
        Watermarks watermarks = new Watermarks(10, 20, 30);

        assertEquals(ThrottleMode.PASS, strategy.onDepth(9, watermarks, System.nanoTime()).mode());
        assertEquals(ThrottleMode.SHED_LIGHT, strategy.onDepth(10, watermarks, System.nanoTime()).mode());
        assertEquals(ThrottleMode.SHED_AGGRESSIVE, strategy.onDepth(21, watermarks, System.nanoTime()).mode());
        assertEquals(ThrottleMode.PAUSE_INGRESS, strategy.onDepth(31, watermarks, System.nanoTime()).mode());
    }

    @Test
    void shouldClampRatios() {
        WatermarkThrottleStrategy strategy = new WatermarkThrottleStrategy(-1.0d, 2.0d, 0L);
        Watermarks watermarks = new Watermarks(1, 2, 3);

        ThrottleDecision light = strategy.onDepth(1, watermarks, System.nanoTime());
        assertEquals(0.0d, light.shedRatio());
        ThrottleDecision aggressive = strategy.onDepth(2, watermarks, System.nanoTime());
        assertEquals(1.0d, aggressive.shedRatio());
    }
}

