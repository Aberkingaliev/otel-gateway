package com.acme.finops.gateway.transport.proxy;

import com.acme.finops.gateway.audit.AsyncFileAuditSink;
import com.acme.finops.gateway.audit.AuditSink;
import com.acme.finops.gateway.audit.NoopAuditSink;
import com.acme.finops.gateway.backpressure.QueueAwareDropPolicy;
import com.acme.finops.gateway.backpressure.ThrottleStrategy;
import com.acme.finops.gateway.backpressure.WatermarkThrottleStrategy;
import com.acme.finops.gateway.backpressure.Watermarks;
import com.acme.finops.gateway.memory.AllocationTag;
import com.acme.finops.gateway.memory.PacketAllocator;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.memory.SlabPacketAllocator;
import com.acme.finops.gateway.memory.StripedPacketAllocator;
import com.acme.finops.gateway.policy.CompileResult;
import com.acme.finops.gateway.policy.CompiledPath;
import com.acme.finops.gateway.policy.OtlpPathCompiler;
import com.acme.finops.gateway.policy.PathStringPool;
import com.acme.finops.gateway.policy.PolicyDecision;
import com.acme.finops.gateway.queue.QueueEnvelope;
import com.acme.finops.gateway.queue.StripedMpscRing;
import com.acme.finops.gateway.telemetry.AtomicHotPathMetrics;
import com.acme.finops.gateway.telemetry.HotPathMetrics;
import com.acme.finops.gateway.telemetry.MaskingRuntimeInfo;
import com.acme.finops.gateway.telemetry.MetricsHttpEndpoint;
import com.acme.finops.gateway.telemetry.NoopHotPathMetrics;
import com.acme.finops.gateway.telemetry.PeriodicMetricsReporter;
import com.acme.finops.gateway.transport.api.TransportAdapter;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.transport.api.IngestResult;
import com.acme.finops.gateway.transport.grpc.NettyOtlpGrpcAdapter;
import com.acme.finops.gateway.transport.http.NettyOtlpHttpAdapter;
import com.acme.finops.gateway.util.EnvVars;
import com.acme.finops.gateway.util.GatewayEnvKeys;
import com.acme.finops.gateway.util.OtlpEndpoints;
import com.acme.finops.gateway.wire.SchemaId;
import com.acme.finops.gateway.wire.SignalType;
import com.acme.finops.gateway.wire.cursor.BytecodeCompiledPathEvaluator;
import com.acme.finops.gateway.wire.mutate.Crc32TailIntegrityRepair;
import com.acme.finops.gateway.wire.mutate.DefaultReframeWriter;
import com.acme.finops.gateway.wire.mutate.EnvControlPlanePolicyProvider;
import com.acme.finops.gateway.wire.mutate.HealthcheckSuccessDropPlanner;
import com.acme.finops.gateway.wire.mutate.IntegrityRepair;
import com.acme.finops.gateway.wire.mutate.LenCascadeRecalculator;
import com.acme.finops.gateway.wire.mutate.MaskWriter;
import com.acme.finops.gateway.wire.mutate.MaskWriterFactory;
import com.acme.finops.gateway.wire.mutate.MutationPlan;
import com.acme.finops.gateway.wire.mutate.MutationPlanValidator;
import com.acme.finops.gateway.wire.mutate.MutationPlanner;
import com.acme.finops.gateway.wire.mutate.PolicyDrivenMutationPlanner;
import com.acme.finops.gateway.wire.mutate.ReframeWriter;

import com.acme.finops.gateway.util.GatewayDefaults;
import com.acme.finops.gateway.util.GatewayStatusCodes;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class NettyGatewayProxyMain {
    private static final Logger LOG = Logger.getLogger(NettyGatewayProxyMain.class.getName());

    private NettyGatewayProxyMain() {}

    public static void main(String[] args) throws Exception {
        int grpcPort = args.length > 0 ? Integer.parseInt(args[0]) : OtlpEndpoints.DEFAULT_GRPC_PORT;
        int httpPort = args.length > 1 ? Integer.parseInt(args[1]) : OtlpEndpoints.DEFAULT_HTTP_PORT;

        String tracesUrl = EnvVars.getOrDefault(GatewayEnvKeys.OTLP_UPSTREAM_TRACES_URL, OtlpEndpoints.DEFAULT_UPSTREAM_TRACES_URL);
        String metricsUrl = EnvVars.getOrDefault(GatewayEnvKeys.OTLP_UPSTREAM_METRICS_URL, OtlpEndpoints.DEFAULT_UPSTREAM_METRICS_URL);
        String logsUrl = EnvVars.getOrDefault(GatewayEnvKeys.OTLP_UPSTREAM_LOGS_URL, OtlpEndpoints.DEFAULT_UPSTREAM_LOGS_URL);
        URI tracesUri = URI.create(tracesUrl);
        URI metricsUri = URI.create(metricsUrl);
        URI logsUri = URI.create(logsUrl);

        Map<String, String> headers = new HashMap<>();
        String ddApiKey = System.getenv(GatewayEnvKeys.DD_API_KEY);
        if (ddApiKey != null && !ddApiKey.isBlank()) {
            headers.put("DD-API-KEY", ddApiKey);
        }
        String authHeader = System.getenv(GatewayEnvKeys.OTLP_UPSTREAM_AUTH_HEADER);
        String authValue = System.getenv(GatewayEnvKeys.OTLP_UPSTREAM_AUTH_VALUE);
        if (authHeader != null && !authHeader.isBlank() && authValue != null && !authValue.isBlank()) {
            headers.put(authHeader, authValue);
        }

        String maskingSimdMode = EnvVars.getOrDefault(GatewayEnvKeys.GATEWAY_MASKING_SIMD, "auto");
        MaskWriterFactory.MaskWriterSelection maskWriterSelection = MaskWriterFactory.select(maskingSimdMode);
        MaskWriter maskWriter = maskWriterSelection.writer();
        MaskingRuntimeInfo maskingRuntimeInfo = new MaskingRuntimeInfo(
            maskWriterSelection.requestedMode(),
            maskWriterSelection.effectiveMode(),
            maskWriterSelection.simdAvailable(),
            maskWriterSelection.strictMode(),
            maskWriterSelection.fallbackReason()
        );
        LOG.info(() -> "Mask writer selected"
            + " requestedMode=" + maskingRuntimeInfo.requestedMode()
            + " effectiveWriter=" + maskingRuntimeInfo.effectiveWriter()
            + " simdAvailable=" + maskingRuntimeInfo.simdAvailable()
            + " strictMode=" + maskingRuntimeInfo.strictMode()
            + (maskingRuntimeInfo.fallbackReason() == null ? "" : " fallbackReason=" + maskingRuntimeInfo.fallbackReason()));

        boolean metricsEnabled = EnvVars.getBoolean(GatewayEnvKeys.GATEWAY_METRICS_ENABLED, true);
        HotPathMetrics hotPathMetrics = metricsEnabled ? new AtomicHotPathMetrics() : NoopHotPathMetrics.INSTANCE;

        AuditSink auditSink = NoopAuditSink.INSTANCE;
        AsyncFileAuditSink asyncAuditSink = null;
        if (EnvVars.getBoolean(GatewayEnvKeys.GATEWAY_AUDIT_ENABLED, false)) {
            try {
                Path dir = Path.of(EnvVars.getOrDefault(GatewayEnvKeys.GATEWAY_AUDIT_DIR, "./build/audit"));
                int queueCap = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_AUDIT_QUEUE_CAPACITY, 4096, 128, 1_000_000);
                int flushMs = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_AUDIT_FLUSH_INTERVAL_MS, 200, 50, 10_000);
                int fsyncMs = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_AUDIT_FSYNC_INTERVAL_MS, 1_000, 0, 60_000);
                int maxFileMb = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_AUDIT_MAX_FILE_MB, 64, 1, 2048);
                int rotateIntervalSec = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_AUDIT_ROTATE_INTERVAL_SEC, 900, 1, 86_400);
                int retentionDays = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_AUDIT_RETENTION_DAYS, 7, 1, 365);
                asyncAuditSink = new AsyncFileAuditSink(
                    dir,
                    queueCap,
                    flushMs,
                    fsyncMs,
                    maxFileMb * 1024L * 1024L,
                    rotateIntervalSec * 1_000L,
                    retentionDays,
                    false
                );
                auditSink = asyncAuditSink;
            } catch (RuntimeException e) {
                LOG.warning("Audit sink init failed, falling back to noop: " + e.getClass().getSimpleName());
            }
        }

        Supplier<Map<String, Long>> additionalMetrics = () -> Map.of();
        if (asyncAuditSink != null) {
            AsyncFileAuditSink sink = asyncAuditSink;
            additionalMetrics = () -> Map.of(
                "auditDroppedEvents", sink.droppedEvents(),
                "auditWriteErrors", sink.writeErrorCount(),
                "auditFlushErrors", sink.flushErrorCount(),
                "auditFsyncErrors", sink.fsyncErrorCount()
            );
        }
        PeriodicMetricsReporter metricsReporter = null;
        MetricsHttpEndpoint metricsEndpoint = null;
        if (metricsEnabled && hotPathMetrics instanceof AtomicHotPathMetrics atomicMetrics) {
            int intervalSec = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_METRICS_LOG_INTERVAL_SEC, 30, 1, 3600);
            metricsReporter = new PeriodicMetricsReporter(atomicMetrics, intervalSec, additionalMetrics);
            boolean metricsHttpEnabled = EnvVars.getBoolean(GatewayEnvKeys.GATEWAY_METRICS_HTTP_ENABLED, true);
            if (metricsHttpEnabled) {
                int metricsPort = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_METRICS_HTTP_PORT, 9464, 1, 65535);
                String metricsPath = EnvVars.getOrDefault(GatewayEnvKeys.GATEWAY_METRICS_HTTP_PATH, "/metrics");
                try {
                    metricsEndpoint = new MetricsHttpEndpoint(
                        atomicMetrics,
                        metricsPort,
                        metricsPath,
                        additionalMetrics,
                        () -> maskingRuntimeInfo
                    );
                } catch (Exception e) {
                    LOG.warning("Metrics endpoint init failed: " + e.getClass().getSimpleName());
                }
            }
        }

        int maxInFlight = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_MAX_INFLIGHT,
            GatewayDefaults.DEFAULT_MAX_INFLIGHT, 64, 65_536);
        int exporterPoolSize = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_EXPORTER_POOL_SIZE,
            GatewayDefaults.DEFAULT_EXPORTER_POOL_SIZE, 1, 1024);
        int exporterIoThreads = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_EXPORTER_IO_THREADS,
            GatewayDefaults.DEFAULT_EXPORTER_IO_THREADS, 0, 64);

        AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
            tracesUri,
            metricsUri,
            logsUri,
            headers,
            maxInFlight,
            GatewayDefaults.DEFAULT_RESPONSE_TIMEOUT_MS,
            exporterIoThreads,
            exporterPoolSize
        );
        boolean reframeEnabled = EnvVars.getBoolean(GatewayEnvKeys.GATEWAY_ENABLE_REFRAME, true);
        ReframeWriter reframeWriter = new DefaultReframeWriter(
            new LenCascadeRecalculator(),
            resolveIntegrityRepair(),
            maskWriter
        );

        int allocatorShards = Integer.highestOneBit(
            EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_ALLOCATOR_SHARDS,
                GatewayDefaults.DEFAULT_ALLOCATOR_SHARDS, 1, 64));
        long slabSizeBytes = EnvVars.getLongClamped(GatewayEnvKeys.GATEWAY_SLAB_SIZE_BYTES,
            GatewayDefaults.DEFAULT_SLAB_SIZE_BYTES, 64L * 1024 * 1024, 8L * 1024 * 1024 * 1024);
        PacketAllocator allocator = new StripedPacketAllocator(
            slabSizeBytes, allocatorShards);
        NettyOtlpGrpcAdapter grpcAdapter = new NettyOtlpGrpcAdapter(
            grpcPort,
            allocator,
            SignalKind.TRACES,
            new AllocationTag("proxy-grpc", OtlpEndpoints.ALLOCATION_SCOPE_DEFAULT, 1),
            hotPathMetrics
        );
        NettyOtlpHttpAdapter httpAdapter = new NettyOtlpHttpAdapter(
            httpPort,
            allocator,
            new AllocationTag("proxy-http", OtlpEndpoints.ALLOCATION_SCOPE_DEFAULT, 1),
            hotPathMetrics
        );

        MutationPlanner mutationPlanner = buildMutationPlanner();
        boolean queueEnabled = EnvVars.getBoolean(GatewayEnvKeys.GATEWAY_QUEUE_ENABLED, false);
        if (!queueEnabled) {
            LOG.warning("Queue disabled (GATEWAY_QUEUE_ENABLED=false) — pipeline runs synchronously on Netty EventLoop. "
                + "Not recommended for production use at high RPS.");
        }
        AsyncIngressDispatcher dispatcher = null;
        AtomicReference<OtlpProcessingPipeline> pipelineRef = new AtomicReference<>();
        if (queueEnabled) {
            int queueCapacity = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_QUEUE_CAPACITY, 32_768, 256, 1_000_000);
            int queueShards = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_QUEUE_SHARDS,
                Runtime.getRuntime().availableProcessors(), 1, 128);
            int queueWorkers = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_QUEUE_WORKERS,
                Runtime.getRuntime().availableProcessors(), 1, 256);
            int low = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_BACKPRESSURE_LOW, queueCapacity / 2, 1, queueCapacity);
            int high = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_BACKPRESSURE_HIGH, Math.max(low, (queueCapacity * 3) / 4), low, queueCapacity);
            int critical = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_BACKPRESSURE_CRITICAL, Math.max(high, (queueCapacity * 9) / 10), high, queueCapacity);
            int maxQueueWaitMs = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_BACKPRESSURE_MAX_QUEUE_WAIT_MS, 500, 1, 60_000);
            double shedLightRatio = EnvVars.getDoubleClamped(GatewayEnvKeys.GATEWAY_BACKPRESSURE_SHED_LIGHT_RATIO, 0.05d, 0.0d, 1.0d);
            double shedAggressiveRatio = EnvVars.getDoubleClamped(GatewayEnvKeys.GATEWAY_BACKPRESSURE_SHED_AGGRESSIVE_RATIO, 0.25d, 0.0d, 1.0d);

            StripedMpscRing<QueueEnvelope> queue = new StripedMpscRing<>(queueCapacity, queueShards);
            Watermarks watermarks = new Watermarks(low, high, critical);
            ThrottleStrategy throttle = new WatermarkThrottleStrategy(shedLightRatio, shedAggressiveRatio, GatewayDefaults.DEFAULT_THROTTLE_PAUSE_NANOS);
            dispatcher = new AsyncIngressDispatcher(
                queue,
                queueWorkers,
                throttle,
                watermarks,
                new QueueAwareDropPolicy(throttle, watermarks, maxQueueWaitMs * 1_000_000L),
                inbound -> {
                    OtlpProcessingPipeline p = pipelineRef.get();
                    if (p == null) {
                        return new IngestResult.Rejected(inbound.requestId(), GatewayStatusCodes.SERVICE_UNAVAILABLE, true);
                    }
                    return p.processCore(inbound);
                },
                hotPathMetrics
            );
        }

        OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
            exporter,
            allocator,
            OtlpProcessingPipeline.allowAllPolicy(),
            mutationPlanner,
            reframeWriter,
            new MutationPlanValidator(),
            maskWriter,
            reframeEnabled,
            dispatcher,
            hotPathMetrics,
            auditSink
        );
        pipelineRef.set(pipeline);
        grpcAdapter.setInboundHandler(pipeline);
        httpAdapter.setInboundHandler(pipeline);

        AtomicBoolean stopped = new AtomicBoolean(false);
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        AsyncIngressDispatcher dispatcherRef = dispatcher;
        PeriodicMetricsReporter reporterRef = metricsReporter;
        MetricsHttpEndpoint metricsEndpointRef = metricsEndpoint;
        AsyncFileAuditSink auditRef = asyncAuditSink;
        Runnable stopAndSignal = () -> {
            try {
                stopAll(
                    httpAdapter,
                    grpcAdapter,
                    exporter,
                    dispatcherRef,
                    reporterRef,
                    metricsEndpointRef,
                    auditRef,
                    allocator,
                    stopped
                );
            } finally {
                shutdownLatch.countDown();
            }
        };
        Thread shutdownHook = new Thread(stopAndSignal, "gateway-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            if (metricsReporter != null) {
                metricsReporter.start();
            }
            if (metricsEndpoint != null) {
                metricsEndpoint.start();
            }
            if (dispatcher != null) {
                dispatcher.start();
            }
            startAdaptersWithRollback(grpcAdapter, httpAdapter, stopAndSignal);
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is shutting down and hook is already in-flight.
            }
            stopAndSignal.run();
        }
    }

    private static MutationPlanner buildHealthcheckPlanner() {
        String sourcePath = System.getenv(GatewayEnvKeys.GATEWAY_HEALTHCHECK_PATH);
        if (sourcePath == null || sourcePath.isBlank()) {
            LOG.info("Healthcheck planner disabled: " + GatewayEnvKeys.GATEWAY_HEALTHCHECK_PATH + " is not set");
            return NettyGatewayProxyMain::noopPlan;
        }

        PathStringPool pool = new PathStringPool();
        OtlpPathCompiler compiler = new OtlpPathCompiler(pool);
        CompileResult result = compiler.compile(
            sourcePath,
            new SchemaId(OtlpEndpoints.SCHEMA_NAME_OTLP, OtlpEndpoints.SCHEMA_KIND_TRACE, OtlpEndpoints.SCHEMA_VERSION_V1),
            SignalType.TRACES
        );

        if (result instanceof CompileResult.Success success) {
            CompiledPath compiledPath = success.compiledPath();
            LOG.info(() -> "Healthcheck planner enabled for path: " + sourcePath);
            return new HealthcheckSuccessDropPlanner(
                new BytecodeCompiledPathEvaluator(pool::resolve),
                compiledPath
            );
        }

        CompileResult.Failure failure = (CompileResult.Failure) result;
        LOG.warning("Healthcheck planner disabled: failed to compile path '" + sourcePath
            + "', code=" + failure.code() + ", message=" + failure.message());
        return NettyGatewayProxyMain::noopPlan;
    }

    private static MutationPlanner buildMutationPlanner() {
        boolean maskingEnabled = EnvVars.getBoolean(GatewayEnvKeys.GATEWAY_MASKING_ENABLED, false);
        if (maskingEnabled) {
            int maxOps = EnvVars.getIntClamped(GatewayEnvKeys.GATEWAY_MASKING_MAX_OPS_PER_PACKET, 128, 1, 4096);
            LOG.info("Masking planner enabled: source=control-plane-env maxOpsPerPacket=" + maxOps);
            return new PolicyDrivenMutationPlanner(EnvControlPlanePolicyProvider.fromEnvironment(), maxOps);
        }
        return buildHealthcheckPlanner();
    }

    /**
     * Shared NOOP plan builder for disabled/fallback startup paths.
     */
    private static MutationPlan noopPlan(PacketRef envelope, PolicyDecision decision) {
        return new MutationPlan.Builder(decision.requestId())
            .sourceLength(envelope.length())
            .targetLength(envelope.length())
            .mode(MutationPlan.PlanMode.NOOP)
            .build();
    }

    static void startAdaptersWithRollback(TransportAdapter first,
                                          TransportAdapter second,
                                          Runnable rollback) throws Exception {
        first.start();
        try {
            second.start();
        } catch (Exception secondStartError) {
            rollback.run();
            throw secondStartError;
        }
    }

    private static IntegrityRepair resolveIntegrityRepair() {
        String mode = EnvVars.getOrDefault(GatewayEnvKeys.GATEWAY_REFRAME_INTEGRITY_MODE, "none").trim().toLowerCase();
        return switch (mode) {
            case "crc32_tail_le" -> new Crc32TailIntegrityRepair(Crc32TailIntegrityRepair.Endianness.LITTLE);
            case "crc32_tail_be" -> new Crc32TailIntegrityRepair(Crc32TailIntegrityRepair.Endianness.BIG);
            default -> IntegrityRepair.NOOP;
        };
    }

    private static void stopAll(NettyOtlpHttpAdapter httpAdapter,
                                NettyOtlpGrpcAdapter grpcAdapter,
                                AsyncOtlpHttpExporter exporter,
                                AsyncIngressDispatcher dispatcher,
                                PeriodicMetricsReporter metricsReporter,
                                MetricsHttpEndpoint metricsEndpoint,
                                AsyncFileAuditSink auditSink,
                                PacketAllocator allocator,
                                AtomicBoolean stopped) {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        if (dispatcher != null) {
            try {
                dispatcher.stopAndDrain(java.time.Duration.ofSeconds(5));
            } catch (Exception e) {
                LOG.fine("Shutdown: dispatcher stop failed: " + e.getClass().getSimpleName());
            }
        }
        try {
            httpAdapter.stop();
        } catch (Exception e) {
            LOG.fine("Shutdown: httpAdapter stop failed: " + e.getClass().getSimpleName());
        }
        try {
            grpcAdapter.stop();
        } catch (Exception e) {
            LOG.fine("Shutdown: grpcAdapter stop failed: " + e.getClass().getSimpleName());
        }
        if (metricsReporter != null) {
            try {
                metricsReporter.close();
            } catch (Exception e) {
                LOG.fine("Shutdown: metricsReporter stop failed: " + e.getClass().getSimpleName());
            }
        }
        if (metricsEndpoint != null) {
            try {
                metricsEndpoint.close();
            } catch (Exception e) {
                LOG.fine("Shutdown: metricsEndpoint stop failed: " + e.getClass().getSimpleName());
            }
        }
        if (auditSink != null) {
            try {
                auditSink.close();
            } catch (Exception e) {
                LOG.fine("Shutdown: auditSink stop failed: " + e.getClass().getSimpleName());
            }
        }
        try {
            exporter.close();
        } catch (Exception e) {
            LOG.fine("Shutdown: exporter stop failed: " + e.getClass().getSimpleName());
        }
        if (allocator != null) {
            try {
                allocator.close();
            } catch (Exception e) {
                LOG.fine("Shutdown: allocator stop failed: " + e.getClass().getSimpleName());
            }
        }
    }
}
