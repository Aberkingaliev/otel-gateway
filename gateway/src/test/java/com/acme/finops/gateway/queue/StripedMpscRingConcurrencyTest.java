package com.acme.finops.gateway.queue;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripedMpscRingConcurrencyTest {

    @Test
    void shouldPreserveDepthInvariantsUnderConcurrentLoad() throws Exception {
        StripedMpscRing<Integer> ring = new StripedMpscRing<>(2048, 8);
        int producerThreads = 4;
        int totalItems = 30_000;

        ExecutorService pool = Executors.newFixedThreadPool(producerThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger nextId = new AtomicInteger(0);
        AtomicInteger consumed = new AtomicInteger(0);
        Map<Integer, Boolean> seen = new ConcurrentHashMap<>();
        try {
            Future<?>[] producers = new Future<?>[producerThreads];
            for (int i = 0; i < producerThreads; i++) {
                producers[i] = pool.submit(() -> {
                    start.await();
                    while (true) {
                        int id = nextId.getAndIncrement();
                        if (id >= totalItems) {
                            return null;
                        }
                        int shard = Math.floorMod(id, ring.shardCount());
                        while (true) {
                            OfferResult offer = ring.offer(shard, id);
                            if (offer instanceof OfferResult.Ok) {
                                break;
                            }
                            if (offer instanceof OfferResult.Full) {
                                Thread.onSpinWait();
                                continue;
                            }
                            throw new IllegalStateException("Queue unexpectedly closed");
                        }
                    }
                });
            }

            Future<?> consumer = pool.submit(() -> {
                start.await();
                int shard = 0;
                while (consumed.get() < totalItems) {
                    Integer value = ring.pollShard(shard);
                    shard = (shard + 1) % ring.shardCount();
                    if (value == null) {
                        Thread.onSpinWait();
                        continue;
                    }
                    if (seen.putIfAbsent(value, Boolean.TRUE) != null) {
                        throw new IllegalStateException("Duplicate value consumed: " + value);
                    }
                    consumed.incrementAndGet();
                }
                return null;
            });

            start.countDown();
            for (Future<?> producer : producers) {
                producer.get(20, TimeUnit.SECONDS);
            }
            consumer.get(20, TimeUnit.SECONDS);

            assertEquals(totalItems, seen.size());
            assertEquals(totalItems, consumed.get());
            assertEquals(0, ring.sizeApprox());
            assertTrue(ring.validateInvariants());
        } finally {
            ring.close();
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldHandleHighContention() throws Exception {
        StripedMpscRing<Integer> ring = new StripedMpscRing<>(1024, 4);
        int producerThreads = 16;
        int totalItems = 100_000;

        ExecutorService pool = Executors.newFixedThreadPool(producerThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger nextId = new AtomicInteger(0);
        AtomicInteger consumed = new AtomicInteger(0);
        Map<Integer, Boolean> seen = new ConcurrentHashMap<>();
        try {
            Future<?>[] producers = new Future<?>[producerThreads];
            for (int i = 0; i < producerThreads; i++) {
                producers[i] = pool.submit(() -> {
                    start.await();
                    while (true) {
                        int id = nextId.getAndIncrement();
                        if (id >= totalItems) {
                            return null;
                        }
                        int shard = Math.floorMod(id, ring.shardCount());
                        while (true) {
                            OfferResult offer = ring.offer(shard, id);
                            if (offer instanceof OfferResult.Ok) {
                                break;
                            }
                            if (offer instanceof OfferResult.Full) {
                                Thread.onSpinWait();
                                continue;
                            }
                            throw new IllegalStateException("Queue unexpectedly closed");
                        }
                    }
                });
            }

            Future<?> consumer = pool.submit(() -> {
                start.await();
                int shard = 0;
                while (consumed.get() < totalItems) {
                    Integer value = ring.pollShard(shard);
                    shard = (shard + 1) % ring.shardCount();
                    if (value == null) {
                        Thread.onSpinWait();
                        continue;
                    }
                    if (seen.putIfAbsent(value, Boolean.TRUE) != null) {
                        throw new IllegalStateException("Duplicate value consumed: " + value);
                    }
                    consumed.incrementAndGet();
                }
                return null;
            });

            start.countDown();
            for (Future<?> producer : producers) {
                producer.get(30, TimeUnit.SECONDS);
            }
            consumer.get(30, TimeUnit.SECONDS);

            assertEquals(totalItems, seen.size());
            assertEquals(totalItems, consumed.get());
            assertEquals(0, ring.sizeApprox());
            assertTrue(ring.validateInvariants());
        } finally {
            ring.close();
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldHandleRepeatedFillAndDrain() throws Exception {
        StripedMpscRing<Integer> ring = new StripedMpscRing<>(256, 4);
        int cycles = 10;
        int itemsPerCycle = ring.capacity();
        int producerThreads = 4;

        ExecutorService pool = Executors.newFixedThreadPool(producerThreads + 1);
        try {
            for (int cycle = 0; cycle < cycles; cycle++) {
                int offset = cycle * itemsPerCycle;
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger nextId = new AtomicInteger(0);
                AtomicInteger consumed = new AtomicInteger(0);
                Map<Integer, Boolean> seen = new ConcurrentHashMap<>();

                Future<?>[] producers = new Future<?>[producerThreads];
                for (int i = 0; i < producerThreads; i++) {
                    producers[i] = pool.submit(() -> {
                        start.await();
                        while (true) {
                            int id = nextId.getAndIncrement();
                            if (id >= itemsPerCycle) {
                                return null;
                            }
                            int shard = Math.floorMod(id, ring.shardCount());
                            while (true) {
                                OfferResult offer = ring.offer(shard, offset + id);
                                if (offer instanceof OfferResult.Ok) {
                                    break;
                                }
                                if (offer instanceof OfferResult.Full) {
                                    Thread.onSpinWait();
                                    continue;
                                }
                                throw new IllegalStateException("Queue unexpectedly closed");
                            }
                        }
                    });
                }

                Future<?> consumer = pool.submit(() -> {
                    start.await();
                    int shard = 0;
                    while (consumed.get() < itemsPerCycle) {
                        Integer value = ring.pollShard(shard);
                        shard = (shard + 1) % ring.shardCount();
                        if (value == null) {
                            Thread.onSpinWait();
                            continue;
                        }
                        seen.putIfAbsent(value, Boolean.TRUE);
                        consumed.incrementAndGet();
                    }
                    return null;
                });

                start.countDown();
                for (Future<?> producer : producers) {
                    producer.get(15, TimeUnit.SECONDS);
                }
                consumer.get(15, TimeUnit.SECONDS);

                assertEquals(itemsPerCycle, consumed.get(), "cycle " + cycle);
                assertEquals(0, ring.sizeApprox(), "cycle " + cycle + " not drained");
                assertTrue(ring.validateInvariants(), "cycle " + cycle + " invariants");
            }
        } finally {
            ring.close();
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
