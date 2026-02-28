package com.acme.finops.gateway.transport.proxy;

import com.acme.finops.gateway.audit.AuditEvent;
import com.acme.finops.gateway.audit.AuditSink;
import com.acme.finops.gateway.audit.NoopAuditSink;
import com.acme.finops.gateway.memory.PacketAllocator;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.policy.AdmissionPolicy;
import com.acme.finops.gateway.policy.DecisionAction;
import com.acme.finops.gateway.policy.PolicyContext;
import com.acme.finops.gateway.policy.PolicyDecision;
import com.acme.finops.gateway.policy.PolicyMode;
import com.acme.finops.gateway.telemetry.HotPathMetrics;
import com.acme.finops.gateway.telemetry.NoopHotPathMetrics;
import com.acme.finops.gateway.transport.api.InboundPacket;
import com.acme.finops.gateway.transport.api.IngestResult;
import com.acme.finops.gateway.transport.api.IngressPort;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.TransportAck;
import com.acme.finops.gateway.transport.api.TransportAdapter;
import com.acme.finops.gateway.transport.api.TransportNack;
import com.acme.finops.gateway.transport.api.TransportResponse;
import com.acme.finops.gateway.util.GatewayStatusCodes;
import com.acme.finops.gateway.util.OtlpContentTypes;
import com.acme.finops.gateway.wire.cursor.FastWireCursor;
import com.acme.finops.gateway.wire.cursor.WireException;
import com.acme.finops.gateway.wire.mutate.DefaultReframeWriter;
import com.acme.finops.gateway.wire.mutate.MaskWriter;
import com.acme.finops.gateway.wire.mutate.MutationPlan;
import com.acme.finops.gateway.wire.mutate.MutationPlanValidator;
import com.acme.finops.gateway.wire.mutate.MutationPlanner;
import com.acme.finops.gateway.wire.mutate.ReframeResult;
import com.acme.finops.gateway.wire.mutate.ReframeWriter;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletionException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main processing orchestrator:
 * Network ingress -> policy decision -> mutation planning/execution -> async export.
 */
public final class OtlpProcessingPipeline implements IngressPort, TransportAdapter.InboundHandler {
    private static final Logger LOG = Logger.getLogger(OtlpProcessingPipeline.class.getName());
    private static final Set<ProtocolKind> SUPPORTED = Set.of(
        ProtocolKind.OTLP_GRPC,
        ProtocolKind.OTLP_HTTP_PROTO
    );

    private final AsyncOtlpHttpExporter exporter;
    private final PacketAllocator allocator;
    private final AdmissionPolicy admissionPolicy;
    private final MutationPlanner mutationPlanner;
    private final ReframeWriter reframeWriter;
    private final MutationPlanValidator mutationPlanValidator;
    private final MaskWriter maskWriter;
    private final boolean reframeEnabled;
    private final AsyncIngressDispatcher dispatcher;
    private final HotPathMetrics metrics;
    private final AuditSink auditSink;
    private final ThreadLocal<FastWireCursor> protobufSanityCursor;

    public OtlpProcessingPipeline(AsyncOtlpHttpExporter exporter,
                                  PacketAllocator allocator,
                                  MutationPlanner mutationPlanner) {
        this(
            exporter, allocator, allowAllPolicy(), mutationPlanner, new DefaultReframeWriter(),
            new MutationPlanValidator(), MaskWriter.scalar(), false,
            null, NoopHotPathMetrics.INSTANCE, NoopAuditSink.INSTANCE
        );
    }

    public OtlpProcessingPipeline(AsyncOtlpHttpExporter exporter,
                                  PacketAllocator allocator,
                                  MutationPlanner mutationPlanner,
                                  boolean reframeEnabled) {
        this(
            exporter, allocator, allowAllPolicy(), mutationPlanner, new DefaultReframeWriter(),
            new MutationPlanValidator(), MaskWriter.scalar(), reframeEnabled,
            null, NoopHotPathMetrics.INSTANCE, NoopAuditSink.INSTANCE
        );
    }

    public OtlpProcessingPipeline(AsyncOtlpHttpExporter exporter,
                                  PacketAllocator allocator,
                                  MutationPlanner mutationPlanner,
                                  ReframeWriter reframeWriter,
                                  boolean reframeEnabled) {
        this(
            exporter, allocator, allowAllPolicy(), mutationPlanner, reframeWriter,
            new MutationPlanValidator(), MaskWriter.scalar(), reframeEnabled,
            null, NoopHotPathMetrics.INSTANCE, NoopAuditSink.INSTANCE
        );
    }

    public OtlpProcessingPipeline(AsyncOtlpHttpExporter exporter,
                                  PacketAllocator allocator,
                                  MutationPlanner mutationPlanner,
                                  ReframeWriter reframeWriter,
                                  MaskWriter maskWriter,
                                  boolean reframeEnabled) {
        this(
            exporter, allocator, allowAllPolicy(), mutationPlanner, reframeWriter,
            new MutationPlanValidator(), maskWriter, reframeEnabled,
            null, NoopHotPathMetrics.INSTANCE, NoopAuditSink.INSTANCE
        );
    }

    public OtlpProcessingPipeline(AsyncOtlpHttpExporter exporter,
                                  PacketAllocator allocator,
                                  AdmissionPolicy admissionPolicy,
                                  MutationPlanner mutationPlanner,
                                  ReframeWriter reframeWriter,
                                  MutationPlanValidator mutationPlanValidator) {
        this(
            exporter, allocator, admissionPolicy, mutationPlanner, reframeWriter,
            mutationPlanValidator, MaskWriter.scalar(), false,
            null, NoopHotPathMetrics.INSTANCE, NoopAuditSink.INSTANCE
        );
    }

    public OtlpProcessingPipeline(AsyncOtlpHttpExporter exporter,
                                  PacketAllocator allocator,
                                  AdmissionPolicy admissionPolicy,
                                  MutationPlanner mutationPlanner,
                                  ReframeWriter reframeWriter,
                                  MutationPlanValidator mutationPlanValidator,
                                  MaskWriter maskWriter,
                                  boolean reframeEnabled) {
        this(
            exporter, allocator, admissionPolicy, mutationPlanner, reframeWriter,
            mutationPlanValidator, maskWriter, reframeEnabled,
            null, NoopHotPathMetrics.INSTANCE, NoopAuditSink.INSTANCE
        );
    }

    public OtlpProcessingPipeline(AsyncOtlpHttpExporter exporter,
                                  PacketAllocator allocator,
                                  AdmissionPolicy admissionPolicy,
                                  MutationPlanner mutationPlanner,
                                  ReframeWriter reframeWriter,
                                  MutationPlanValidator mutationPlanValidator,
                                  MaskWriter maskWriter,
                                  boolean reframeEnabled,
                                  AsyncIngressDispatcher dispatcher,
                                  HotPathMetrics metrics,
                                  AuditSink auditSink) {
        this.exporter = Objects.requireNonNull(exporter, "exporter");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.admissionPolicy = Objects.requireNonNull(admissionPolicy, "admissionPolicy");
        this.mutationPlanner = Objects.requireNonNull(mutationPlanner, "mutationPlanner");
        this.reframeWriter = Objects.requireNonNull(reframeWriter, "reframeWriter");
        this.mutationPlanValidator = Objects.requireNonNull(mutationPlanValidator, "mutationPlanValidator");
        this.maskWriter = Objects.requireNonNull(maskWriter, "maskWriter");
        this.reframeEnabled = reframeEnabled;
        this.dispatcher = dispatcher;
        this.metrics = metrics == null ? NoopHotPathMetrics.INSTANCE : metrics;
        this.auditSink = auditSink == null ? NoopAuditSink.INSTANCE : auditSink;
        this.protobufSanityCursor = ThreadLocal.withInitial(FastWireCursor::new);
    }

    @Override
    public IngestResult ingest(InboundPacket packet) {
        return processCore(packet);
    }

    IngestResult processCore(InboundPacket packet) {
        long nowNanos = System.nanoTime();
        metrics.incPacketsIn(1L);
        if (packet == null || packet.packetRef() == null) {
            metrics.incParseErrors(1L, GatewayStatusCodes.BAD_REQUEST);
            return new IngestResult.Rejected(0L, GatewayStatusCodes.BAD_REQUEST, false);
        }
        if (!SUPPORTED.contains(packet.protocol())) {
            metrics.incParseErrors(1L, GatewayStatusCodes.UNSUPPORTED_MEDIA_TYPE);
            return new IngestResult.Rejected(packet.requestId(), GatewayStatusCodes.UNSUPPORTED_MEDIA_TYPE, false);
        }
        String ingressContentType = OtlpContentTypes.resolveDeclaredOrDetect(packet.contentType(), packet.packetRef());
        if (isProtobufContentType(ingressContentType) && !passesBasicProtobufSanity(packet.packetRef())) {
            metrics.incParseErrors(1L, GatewayStatusCodes.BAD_REQUEST);
            appendAudit("PARSE_REJECTED", packet, "malformed_protobuf", Map.of());
            return new IngestResult.Rejected(packet.requestId(), GatewayStatusCodes.BAD_REQUEST, false);
        }

        PolicyDecision decision;
        try {
            decision = admissionPolicy.evaluate(new PolicyContext(
                packet.requestId(),
                0L,
                packet.packetRef(),
                nowNanos
            ));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Policy evaluation failed requestId=" + packet.requestId(), e);
            if (admissionPolicy.mode() == PolicyMode.FAIL_OPEN) {
                decision = PolicyDecision.routeDefault(packet.requestId());
                appendAudit("POLICY_ALLOW_FAIL_OPEN", packet, "allow_fail_open", Map.of("error", e.getClass().getSimpleName()));
            } else {
                appendAudit("POLICY_DENY", packet, "policy_eval_failed", Map.of());
                metrics.incDropped(1L, GatewayStatusCodes.INTERNAL_ERROR);
                return new IngestResult.Rejected(packet.requestId(), GatewayStatusCodes.INTERNAL_ERROR, true);
            }
        }

        if (decision.action() == DecisionAction.DROP) {
            appendAudit("POLICY_DROP", packet, Integer.toString(decision.reasonCode()), Map.of());
            metrics.incDropped(1L, decision.reasonCode());
            return new IngestResult.Accepted(decision.requestId(), 0L);
        }

        PacketRef outbound = packet.packetRef();
        boolean releaseOutbound = false;
        try {
            MutationPlan plan = mutationPlanner.plan(outbound, decision);
            if (plan == null) {
                metrics.incDropped(1L, GatewayStatusCodes.INTERNAL_ERROR);
                return new IngestResult.Rejected(packet.requestId(), GatewayStatusCodes.INTERNAL_ERROR, true);
            }
            MutationPlanValidator.ValidationResult validation = mutationPlanValidator.validate(plan, outbound.length());
            if (!validation.valid()) {
                LOG.warning("Mutation plan rejected requestId=" + packet.requestId() + " errors=" + validation.errors());
                appendAudit("MUTATION_REJECTED", packet, "invalid_plan", Map.of("errors", Integer.toString(validation.errors().size())));
                metrics.incDropped(1L, GatewayStatusCodes.UNPROCESSABLE_ENTITY);
                return new IngestResult.Rejected(packet.requestId(), GatewayStatusCodes.UNPROCESSABLE_ENTITY, false);
            }

            if (plan.isDrop()) {
                appendAudit("MUTATION_DROP", packet, plan.reasonCode(), Map.of());
                metrics.incDropped(1L, 0);
                return new IngestResult.Accepted(packet.requestId(), 0L);
            }

            if (plan.requiresReframe()) {
                if (!reframeEnabled) {
                    LOG.warning("Reframe disabled requestId=" + packet.requestId() + " planMode=" + plan.mode());
                    metrics.incDropped(1L, GatewayStatusCodes.NOT_IMPLEMENTED);
                    return new IngestResult.Rejected(packet.requestId(), GatewayStatusCodes.NOT_IMPLEMENTED, false);
                }
                ReframeResult reframeResult = reframeWriter.write(plan, outbound, allocator);
                if (reframeResult instanceof ReframeResult.Failed failed) {
                    appendAudit("MUTATION_REFRAME_FAILED", packet, Integer.toString(failed.errorCode()), Map.of());
                    metrics.incDropped(1L, failed.errorCode());
                    return new IngestResult.Rejected(packet.requestId(), failed.errorCode(), true);
                }
                PacketRef reframed = ((ReframeResult.Success) reframeResult).reframed();
                if (reframed != outbound) {
                    outbound = reframed;
                    releaseOutbound = true;
                }
            } else if (!plan.passAOps().isEmpty()) {
                applyInPlaceOps(plan, outbound, maskWriter);
            }

            String contentType = OtlpContentTypes.resolveDeclaredOrDetect(packet.contentType(), outbound);
            var exportFuture = exporter.exportAsync(packet.signalKind(), outbound, contentType);
            if (exportFuture.isDone()) {
                try {
                    exportFuture.join();
                } catch (CompletionException immediateFailure) {
                    LOG.log(Level.WARNING, "Export backpressure requestId=" + packet.requestId(), immediateFailure.getCause());
                    return new IngestResult.Busy(packet.requestId(), 100L);
                }
            }

            exportFuture
                .whenComplete((status, error) -> {
                    if (error != null) {
                        LOG.log(Level.WARNING, "Export failed requestId=" + packet.requestId(), error);
                        appendAudit("EXPORT_FAILED", packet, "export_exception", Map.of("error", error.getClass().getSimpleName()));
                        metrics.incDropped(1L, GatewayStatusCodes.INTERNAL_ERROR);
                        return;
                    }
                    if (status >= 400) {
                        LOG.warning("Upstream rejected requestId=" + packet.requestId() + " status=" + status);
                        appendAudit("EXPORT_REJECTED", packet, Integer.toString(status), Map.of());
                        metrics.incDropped(1L, status);
                    } else {
                        appendAudit("EXPORT_OK", packet, "ok", Map.of());
                    }
                });

            metrics.incPacketsOut(1L);
            observeEndToEnd(packet, System.nanoTime());
            return new IngestResult.Accepted(packet.requestId(), 1L);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Processing failed requestId=" + packet.requestId(), e);
            metrics.incDropped(1L, GatewayStatusCodes.INTERNAL_ERROR);
            return new IngestResult.Rejected(packet.requestId(), GatewayStatusCodes.INTERNAL_ERROR, true);
        } finally {
            if (releaseOutbound) {
                try {
                    outbound.release();
                } catch (RuntimeException releaseError) {
                    LOG.log(Level.WARNING, "Failed to release reframed packet requestId=" + packet.requestId(), releaseError);
                }
            }
        }
    }

    @Override
    public Set<ProtocolKind> supportedProtocols() {
        return SUPPORTED;
    }

    @Override
    public TransportResponse onPacket(InboundPacket packet) {
        if (dispatcher != null) {
            EnqueueResult enqueueResult = dispatcher.enqueue(packet);
            if (enqueueResult instanceof EnqueueResult.Accepted) {
                return new TransportAck(GatewayStatusCodes.OK, null);
            }
            if (enqueueResult instanceof EnqueueResult.Busy busy) {
                return new TransportNack(GatewayStatusCodes.TOO_MANY_REQUESTS, busy.reasonCode(), true, busy.retryAfterMillis());
            }
            EnqueueResult.Rejected rejected = (EnqueueResult.Rejected) enqueueResult;
            return new TransportNack(statusFromErrorCode(rejected.errorCode()), rejected.errorCode(), rejected.retryable(), 0L);
        }

        IngestResult result = processCore(packet);
        if (result instanceof IngestResult.Accepted) {
            return new TransportAck(GatewayStatusCodes.OK, null);
        }
        if (result instanceof IngestResult.PartialSuccess) {
            return new TransportAck(GatewayStatusCodes.OK, null);
        }
        if (result instanceof IngestResult.Busy busy) {
            return new TransportNack(GatewayStatusCodes.TOO_MANY_REQUESTS, GatewayStatusCodes.TOO_MANY_REQUESTS, true, busy.retryAfterMillis());
        }
        IngestResult.Rejected rejected = (IngestResult.Rejected) result;
        return new TransportNack(statusFromErrorCode(rejected.errorCode()), rejected.errorCode(), rejected.retryable(), 0L);
    }

    private void appendAudit(String eventType, InboundPacket packet, String outcome, Map<String, String> attrs) {
        try {
            auditSink.append(new AuditEvent(
                Long.toHexString(System.nanoTime()),
                System.currentTimeMillis(),
                eventType,
                "gateway",
                "",
                packet == null ? 0L : packet.requestId(),
                "",
                "",
                outcome,
                attrs == null ? Map.of() : attrs
            ));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Audit append failed", e);
        }
    }

    private void observeEndToEnd(InboundPacket packet, long nowNanos) {
        try {
            if (packet == null || packet.packetRef() == null || packet.packetRef().descriptor() == null) {
                return;
            }
            long ingestNanos = packet.packetRef().descriptor().ingestNanos();
            if (ingestNanos > 0 && nowNanos > ingestNanos) {
                metrics.observeEndToEndNanos(nowNanos - ingestNanos);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void applyInPlaceOps(MutationPlan plan, PacketRef packetRef, MaskWriter maskWriter) {
        MemorySegment payload = packetRef.segment().asSlice(packetRef.offset(), packetRef.length());
        for (MutationPlan.Op op : plan.passAOps()) {
            if (op instanceof MutationPlan.InplaceMaskOp mask) {
                maskWriter.mask(payload, mask);
            }
        }
    }

    private boolean passesBasicProtobufSanity(PacketRef packetRef) {
        FastWireCursor cursor = protobufSanityCursor.get();
        cursor.reset(packetRef.segment(), packetRef.offset(), packetRef.length());
        try {
            while (cursor.nextField()) {
                // top-level scan validates varints and LEN boundaries without decoding.
            }
            return true;
        } catch (WireException malformed) {
            return false;
        } catch (RuntimeException unexpected) {
            LOG.log(Level.FINE, "Unexpected protobuf sanity-check failure", unexpected);
            return false;
        }
    }

    private static boolean isProtobufContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        return contentType.regionMatches(true, 0, OtlpContentTypes.PROTOBUF, 0, OtlpContentTypes.PROTOBUF.length())
            || contentType.regionMatches(true, 0, OtlpContentTypes.PROTOBUF_ALT, 0, OtlpContentTypes.PROTOBUF_ALT.length());
    }

    private static int statusFromErrorCode(int errorCode) {
        if (errorCode >= GatewayStatusCodes.BAD_REQUEST && errorCode < 600) {
            return errorCode;
        }
        return GatewayStatusCodes.INTERNAL_ERROR;
    }

    static AdmissionPolicy allowAllPolicy() {
        return new AdmissionPolicy() {
            @Override
            public PolicyMode mode() {
                return PolicyMode.FAIL_OPEN;
            }

            @Override
            public PolicyDecision evaluate(PolicyContext context) {
                return PolicyDecision.routeDefault(context.requestId());
            }
        };
    }
}
