# Pre-prod Concurrency and Ownership Audit Checklist

Use this checklist before each AWS pre-prod campaign.

## Scope
- `gateway/src/main/java/com/acme/finops/gateway/transport/proxy/OtlpProcessingPipeline.java`
- `gateway/src/main/java/com/acme/finops/gateway/transport/proxy/AsyncIngressDispatcher.java`
- `gateway/src/main/java/com/acme/finops/gateway/queue/StripedMpscRing.java`
- `gateway/src/main/java/com/acme/finops/gateway/transport/proxy/AsyncOtlpHttpExporter.java`
- `gateway/src/main/java/com/acme/finops/gateway/memory/SlabPacketAllocator.java`

## Ownership / Refcount
- [ ] Every `retain()` has one matching `release()` on all success and failure paths.
- [ ] Queue enqueue path retains ownership once; worker always releases once in `finally`.
- [ ] Reframe replacement path releases old/new references exactly once (no leaks, no double-release).
- [ ] Exporter completion callbacks always release in-flight semaphore and cleanup references.

## Queue and Backpressure Invariants
- [ ] `sizeApprox()` never goes negative under concurrent producer/consumer load.
- [ ] `validateInvariants()` holds during shutdown drain and after stress tests.
- [ ] Backpressure transitions (`PASS/SHED/PAUSE`) do not deadlock ingress workers.
- [ ] Worker parking/unparking does not leave queue stuck while `running=true`.

## Exporter and Timeout Safety
- [ ] `maxInFlight` saturation fails fast without leaked permits.
- [ ] Timeout path closes channel and completes future exceptionally exactly once.
- [ ] Connection/write failures trigger cleanup and do not strand references.

## Control-plane Staleness / Fallback
- [ ] Concurrent snapshot swaps do not throw from endpoint resolver with fallback enabled.
- [ ] Missing route IDs use fallback defaults when enabled.
- [ ] Stale snapshot logging is visible without stopping traffic (fail-open behavior).

## Required Tests
- [ ] `StripedMpscRingConcurrencyTest`
- [ ] `PacketRefImplConcurrencyTest`
- [ ] `AsyncOtlpHttpExporterTest` contention scenario
- [ ] `SnapshotRouteEndpointResolverTest` concurrent snapshot swap scenario
- [ ] `MaskWriterFactoryTest` strict SIMD behavior

## Required Runtime Artifacts
- [ ] `finops-summary.txt`
- [ ] `diagnostics.txt`
- [ ] `metrics-before.prom`
- [ ] `metrics-after.prom`
- [ ] `metrics.prom`
- [ ] `gateway.log`
- [ ] `upstream.log`

## Go/No-Go Policy
- [ ] Any P1/P2 finding blocks AWS testing.
- [ ] Strict SIMD requested (`on`) and confirmed active.
- [ ] Leak/refcount/OOM diagnostics are zero.
- [ ] Resource and latency gates pass for the measured stage.
