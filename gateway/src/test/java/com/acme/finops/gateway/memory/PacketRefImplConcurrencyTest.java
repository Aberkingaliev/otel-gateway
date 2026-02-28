package com.acme.finops.gateway.memory;

import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketRefImplConcurrencyTest {

    @Test
    void shouldKeepRefcountStableUnderConcurrentRetainRelease() throws Exception {
        PacketDescriptor descriptor = new PacketDescriptor(
            100L,
            200L,
            SignalKind.TRACES,
            ProtocolKind.OTLP_HTTP_PROTO,
            0,
            16,
            System.nanoTime()
        );
        PacketRefImpl packetRef = new PacketRefImpl(
            100L,
            descriptor,
            MemorySegment.ofArray(new byte[16]),
            0,
            16
        );

        int threads = 8;
        int iterations = 25_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < iterations; j++) {
                        packetRef.retain();
                        boolean releasedToZero = packetRef.release();
                        if (releasedToZero) {
                            throw new IllegalStateException("Refcount reached zero during paired retain/release");
                        }
                    }
                    return null;
                });
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }

            assertEquals(1, packetRef.refCount());
            packetRef.retain();
            assertFalse(packetRef.release());
            assertTrue(packetRef.release());
            assertEquals(0, packetRef.refCount());
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
