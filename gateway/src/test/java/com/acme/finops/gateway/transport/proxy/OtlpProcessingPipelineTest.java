package com.acme.finops.gateway.transport.proxy;

import com.acme.finops.gateway.memory.PacketDescriptor;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.memory.PacketRefImpl;
import com.acme.finops.gateway.memory.SlabPacketAllocator;
import com.acme.finops.gateway.policy.AdmissionPolicy;
import com.acme.finops.gateway.policy.PolicyContext;
import com.acme.finops.gateway.policy.PolicyDecision;
import com.acme.finops.gateway.policy.PolicyMode;
import com.acme.finops.gateway.transport.api.InboundPacket;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.transport.api.TransportAck;
import com.acme.finops.gateway.transport.api.TransportNack;
import com.acme.finops.gateway.wire.mutate.MaskWriter;
import com.acme.finops.gateway.wire.mutate.MutationPlanValidator;
import com.acme.finops.gateway.wire.mutate.ReframeResult;
import com.acme.finops.gateway.wire.mutate.ReframeWriter;
import com.acme.finops.gateway.wire.mutate.MutationPlan;
import com.acme.finops.gateway.wire.mutate.MutationPlanner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpProcessingPipelineTest {

    @Test
    void shouldReturnHttp200ForAcceptedIngest() throws Exception {
        try (HangingHttpServer upstream = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 upstream.uri("/v1/traces"),
                 upstream.uri("/v1/metrics"),
                 upstream.uri("/v1/logs"),
                 Map.of(),
                 8,
                 300
             );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            MutationPlanner noopPlanner = (packet, decision) -> new MutationPlan.Builder(packet.descriptor().requestId())
                .mode(MutationPlan.PlanMode.NOOP)
                .sourceLength(packet.length())
                .targetLength(packet.length())
                .build();
            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter,
                allocator,
                noopPlanner,
                false
            );

            PacketRef packetRef = packetRef(new byte[]{0x0A, 0x01, 0x01});
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    42L,
                    ProtocolKind.OTLP_HTTP_PROTO,
                    SignalKind.TRACES,
                    packetRef,
                    "application/x-protobuf"
                ));
                TransportAck ack = assertInstanceOf(TransportAck.class, response);
                assertEquals(200, ack.statusCode());
            } finally {
                packetRef.release();
            }
        }
    }

    @Test
    void shouldRejectReframeWhenFeatureDisabled() throws Exception {
        try (AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
            URI.create("http://127.0.0.1:43181/v1/traces"),
            URI.create("http://127.0.0.1:43181/v1/metrics"),
            URI.create("http://127.0.0.1:43181/v1/logs"),
            Map.of()
        );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            MutationPlanner reframePlanner = (packet, decision) -> new MutationPlan.Builder(packet.descriptor().requestId())
                .mode(MutationPlan.PlanMode.REFRAME)
                .sourceLength(packet.length())
                .targetLength(packet.length())
                .addPassB(new MutationPlan.SliceCopyOp(0, packet.length(), 0))
                .build();

            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter,
                allocator,
                reframePlanner
            );

            PacketRef packetRef = packetRef(new byte[]{0x0A, 0x01, 0x01});
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    100L,
                    ProtocolKind.OTLP_HTTP_PROTO,
                    SignalKind.TRACES,
                    packetRef,
                    "application/x-protobuf"
                ));
                TransportNack nack = assertInstanceOf(TransportNack.class, response);
                assertEquals(501, nack.statusCode());
                assertEquals(501, nack.errorCode());
                assertFalse(nack.retryable());
            } finally {
                packetRef.release();
            }
        }
    }

    @Test
    void shouldRejectMalformedTopLevelProtobufBeforeExport() throws Exception {
        try (HangingHttpServer upstream = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 upstream.uri("/v1/traces"),
                 upstream.uri("/v1/metrics"),
                 upstream.uri("/v1/logs"),
                 Map.of(),
                 8,
                 300
             );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            MutationPlanner noopPlanner = (packet, decision) -> new MutationPlan.Builder(packet.descriptor().requestId())
                .mode(MutationPlan.PlanMode.NOOP)
                .sourceLength(packet.length())
                .targetLength(packet.length())
                .build();
            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter,
                allocator,
                noopPlanner,
                false
            );

            byte[] malformed = new byte[] {0x0A, (byte) 0xC7, (byte) 0x96, 0x01};
            PacketRef packetRef = packetRef(malformed);
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    77L,
                    ProtocolKind.OTLP_HTTP_PROTO,
                    SignalKind.LOGS,
                    packetRef,
                    "application/x-protobuf"
                ));
                TransportNack nack = assertInstanceOf(TransportNack.class, response);
                assertEquals(400, nack.statusCode());
                assertEquals(400, nack.errorCode());
                assertFalse(nack.retryable());
            } finally {
                packetRef.release();
            }
        }
    }

    @Test
    void shouldFailOpenWhenPolicyThrowsInFailOpenMode() throws Exception {
        try (HangingHttpServer upstream = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 upstream.uri("/v1/traces"),
                 upstream.uri("/v1/metrics"),
                 upstream.uri("/v1/logs"),
                 Map.of(),
                 8,
                 300
             );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            AdmissionPolicy throwingFailOpen = new AdmissionPolicy() {
                @Override
                public PolicyMode mode() {
                    return PolicyMode.FAIL_OPEN;
                }

                @Override
                public PolicyDecision evaluate(PolicyContext context) {
                    throw new RuntimeException("boom");
                }
            };

            MutationPlanner noopPlanner = (packet, decision) -> new MutationPlan.Builder(packet.descriptor().requestId())
                .mode(MutationPlan.PlanMode.NOOP)
                .sourceLength(packet.length())
                .targetLength(packet.length())
                .build();

            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter,
                allocator,
                throwingFailOpen,
                noopPlanner,
                new com.acme.finops.gateway.wire.mutate.DefaultReframeWriter(),
                new MutationPlanValidator(),
                MaskWriter.scalar(),
                false
            );

            PacketRef packetRef = packetRef(new byte[]{0x0A, 0x01, 0x01});
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    1L,
                    ProtocolKind.OTLP_HTTP_PROTO,
                    SignalKind.TRACES,
                    packetRef,
                    "application/x-protobuf"
                ));
                TransportAck ack = assertInstanceOf(TransportAck.class, response);
                assertEquals(200, ack.statusCode());
            } finally {
                packetRef.release();
            }
        }
    }

    @Test
    void shouldFailClosedWhenPolicyThrowsInFailClosedMode() throws Exception {
        try (HangingHttpServer upstream = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 upstream.uri("/v1/traces"),
                 upstream.uri("/v1/metrics"),
                 upstream.uri("/v1/logs"),
                 Map.of(),
                 8,
                 300
             );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            AdmissionPolicy throwingFailClosed = new AdmissionPolicy() {
                @Override
                public PolicyMode mode() {
                    return PolicyMode.FAIL_CLOSED;
                }

                @Override
                public PolicyDecision evaluate(PolicyContext context) {
                    throw new RuntimeException("boom");
                }
            };

            MutationPlanner noopPlanner = (packet, decision) -> new MutationPlan.Builder(packet.descriptor().requestId())
                .mode(MutationPlan.PlanMode.NOOP)
                .sourceLength(packet.length())
                .targetLength(packet.length())
                .build();

            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter,
                allocator,
                throwingFailClosed,
                noopPlanner,
                new com.acme.finops.gateway.wire.mutate.DefaultReframeWriter(),
                new MutationPlanValidator(),
                MaskWriter.scalar(),
                false
            );

            PacketRef packetRef = packetRef(new byte[]{0x0A, 0x01, 0x01});
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    1L,
                    ProtocolKind.OTLP_HTTP_PROTO,
                    SignalKind.TRACES,
                    packetRef,
                    "application/x-protobuf"
                ));
                TransportNack nack = assertInstanceOf(TransportNack.class, response);
                assertEquals(500, nack.statusCode());
                assertEquals(500, nack.errorCode());
            } finally {
                packetRef.release();
            }
        }
    }

    @Test
    void shouldAckWhenPolicyDecidesDrop() throws Exception {
        try (HangingHttpServer upstream = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 upstream.uri("/v1/traces"),
                 upstream.uri("/v1/metrics"),
                 upstream.uri("/v1/logs"),
                 Map.of(),
                 8,
                 300
             );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            AdmissionPolicy dropAll = new AdmissionPolicy() {
                @Override
                public PolicyMode mode() {
                    return PolicyMode.FAIL_OPEN;
                }

                @Override
                public PolicyDecision evaluate(PolicyContext context) {
                    return PolicyDecision.drop(context.requestId(), 403);
                }
            };

            MutationPlanner planner = (packet, decision) -> {
                throw new AssertionError("planner should not be called when policy drops");
            };

            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter,
                allocator,
                dropAll,
                planner,
                new com.acme.finops.gateway.wire.mutate.DefaultReframeWriter(),
                new MutationPlanValidator(),
                MaskWriter.scalar(),
                false
            );

            PacketRef packetRef = packetRef(new byte[]{0x0A, 0x01, 0x01});
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    2L,
                    ProtocolKind.OTLP_HTTP_PROTO,
                    SignalKind.TRACES,
                    packetRef,
                    "application/x-protobuf"
                ));
                TransportAck ack = assertInstanceOf(TransportAck.class, response);
                assertEquals(200, ack.statusCode());
            } finally {
                packetRef.release();
            }
        }
    }

    @Test
    void shouldRejectInvalidMutationPlan() throws Exception {
        try (HangingHttpServer upstream = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 upstream.uri("/v1/traces"),
                 upstream.uri("/v1/metrics"),
                 upstream.uri("/v1/logs"),
                 Map.of(),
                 8,
                 300
             );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            MutationPlanner invalidPlanner = (packet, decision) -> new MutationPlan.Builder(packet.descriptor().requestId())
                .mode(MutationPlan.PlanMode.NOOP)
                .sourceLength(packet.length())
                .targetLength(packet.length())
                .addPassA(new MutationPlan.InplaceMaskOp(0, 4, (byte) 'x', new byte[]{1, 2, 3}, "bad"))
                .build();

            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter,
                allocator,
                invalidPlanner,
                new com.acme.finops.gateway.wire.mutate.DefaultReframeWriter(),
                MaskWriter.scalar(),
                false
            );

            // Payload must pass protobuf sanity: field1 LEN=2 body=[0x01,0x02]
            PacketRef packetRef = packetRef(new byte[]{0x0A, 0x02, 0x01, 0x02});
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    3L,
                    ProtocolKind.OTLP_HTTP_PROTO,
                    SignalKind.TRACES,
                    packetRef,
                    "application/x-protobuf"
                ));
                TransportNack nack = assertInstanceOf(TransportNack.class, response);
                assertEquals(422, nack.statusCode());
                assertEquals(422, nack.errorCode());
            } finally {
                packetRef.release();
            }
        }
    }

    @Test
    void shouldApplyPassAInPlaceMaskingWhenPlanned() throws Exception {
        try (HangingHttpServer upstream = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 upstream.uri("/v1/traces"),
                 upstream.uri("/v1/metrics"),
                 upstream.uri("/v1/logs"),
                 Map.of(),
                 8,
                 300
             );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            MutationPlanner maskPlanner = (packet, decision) -> new MutationPlan.Builder(packet.descriptor().requestId())
                .mode(MutationPlan.PlanMode.INPLACE_ONLY)
                .sourceLength(packet.length())
                .targetLength(packet.length())
                .addPassA(new MutationPlan.InplaceMaskOp(1, 1, (byte) 'X', "mask"))
                .build();

            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter,
                allocator,
                maskPlanner,
                new com.acme.finops.gateway.wire.mutate.DefaultReframeWriter(),
                MaskWriter.scalar(),
                false
            );

            byte[] payload = new byte[]{0x0A, 0x01, 0x01};
            PacketRef packetRef = packetRef(payload);
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    4L,
                    ProtocolKind.OTLP_HTTP_PROTO,
                    SignalKind.TRACES,
                    packetRef,
                    "application/x-protobuf"
                ));
                TransportAck ack = assertInstanceOf(TransportAck.class, response);
                assertEquals(200, ack.statusCode());
                assertEquals('X', payload[1]);
            } finally {
                packetRef.release();
            }
        }
    }

    @Test
    void shouldPropagateReframeWriterFailureWhenEnabled() throws Exception {
        try (HangingHttpServer upstream = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 upstream.uri("/v1/traces"),
                 upstream.uri("/v1/metrics"),
                 upstream.uri("/v1/logs"),
                 Map.of(),
                 8,
                 300
             );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            MutationPlanner reframePlanner = (packet, decision) -> new MutationPlan.Builder(packet.descriptor().requestId())
                .mode(MutationPlan.PlanMode.REFRAME)
                .sourceLength(packet.length())
                .targetLength(packet.length())
                .addPassB(new MutationPlan.SliceCopyOp(0, packet.length(), 0))
                .build();
            ReframeWriter failingReframeWriter = (plan, src, alloc) -> new ReframeResult.Failed(500);

            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter,
                allocator,
                OtlpProcessingPipeline.allowAllPolicy(),
                reframePlanner,
                failingReframeWriter,
                new MutationPlanValidator(),
                MaskWriter.scalar(),
                true
            );

            PacketRef packetRef = packetRef(new byte[]{0x0A, 0x01, 0x01});
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    5L,
                    ProtocolKind.OTLP_HTTP_PROTO,
                    SignalKind.TRACES,
                    packetRef,
                    "application/x-protobuf"
                ));
                TransportNack nack = assertInstanceOf(TransportNack.class, response);
                assertEquals(500, nack.statusCode());
                assertEquals(500, nack.errorCode());
            } finally {
                packetRef.release();
            }
        }
    }

    // ── Leak/refcount tests ──────────────────────────────────────────────────

    @Test
    void shouldNotLeakSourceRefOnPolicyDrop() throws Exception {
        try (HangingHttpServer upstream = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 upstream.uri("/v1/traces"),
                 upstream.uri("/v1/metrics"),
                 upstream.uri("/v1/logs"),
                 Map.of(),
                 8,
                 300
             );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            AdmissionPolicy dropPolicy = new AdmissionPolicy() {
                @Override
                public PolicyMode mode() { return PolicyMode.FAIL_OPEN; }
                @Override
                public PolicyDecision evaluate(PolicyContext ctx) {
                    return PolicyDecision.drop(ctx.requestId(), 403);
                }
            };
            MutationPlanner planner = (packet, decision) -> {
                throw new AssertionError("planner must not be called for dropped packets");
            };
            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter, allocator, dropPolicy, planner,
                new com.acme.finops.gateway.wire.mutate.DefaultReframeWriter(),
                new MutationPlanValidator(), MaskWriter.scalar(), false
            );

            PacketRef packetRef = packetRef(new byte[]{0x0A, 0x01, 0x01});
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    10L, ProtocolKind.OTLP_HTTP_PROTO, SignalKind.TRACES,
                    packetRef, "application/x-protobuf"
                ));
                assertInstanceOf(TransportAck.class, response);
                // Pipeline must NOT release the source ref — caller owns it
                assertEquals(1, packetRef.refCount(),
                    "Source PacketRef must have refCount=1 after policy DROP (caller still owns it)");
            } finally {
                packetRef.release();
            }
            assertEquals(0, packetRef.refCount());
        }
    }

    @Test
    void shouldNotLeakSourceRefOnMalformedProtobuf() throws Exception {
        try (HangingHttpServer upstream = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 upstream.uri("/v1/traces"),
                 upstream.uri("/v1/metrics"),
                 upstream.uri("/v1/logs"),
                 Map.of(),
                 8,
                 300
             );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            MutationPlanner noopPlanner = (packet, decision) -> new MutationPlan.Builder(packet.descriptor().requestId())
                .mode(MutationPlan.PlanMode.NOOP)
                .sourceLength(packet.length())
                .targetLength(packet.length())
                .build();
            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter, allocator, noopPlanner, false
            );

            // LEN field claims 19479 bytes but only 2 remain — triggers TRUNCATED_FRAME
            byte[] malformed = new byte[]{0x0A, (byte) 0xC7, (byte) 0x96, 0x01};
            PacketRef packetRef = packetRef(malformed);
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    20L, ProtocolKind.OTLP_HTTP_PROTO, SignalKind.LOGS,
                    packetRef, "application/x-protobuf"
                ));
                TransportNack nack = assertInstanceOf(TransportNack.class, response);
                assertEquals(400, nack.statusCode());
                assertEquals(1, packetRef.refCount(),
                    "Source PacketRef must have refCount=1 after parse rejection");
            } finally {
                packetRef.release();
            }
            assertEquals(0, packetRef.refCount());
        }
    }

    @Test
    void shouldNotLeakSourceRefOnReframeFailure() throws Exception {
        try (HangingHttpServer upstream = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 upstream.uri("/v1/traces"),
                 upstream.uri("/v1/metrics"),
                 upstream.uri("/v1/logs"),
                 Map.of(),
                 8,
                 300
             );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            MutationPlanner reframePlanner = (packet, decision) -> new MutationPlan.Builder(packet.descriptor().requestId())
                .mode(MutationPlan.PlanMode.REFRAME)
                .sourceLength(packet.length())
                .targetLength(packet.length())
                .addPassB(new MutationPlan.SliceCopyOp(0, packet.length(), 0))
                .build();
            ReframeWriter failingWriter = (plan, src, alloc) -> new ReframeResult.Failed(500);
            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter, allocator, OtlpProcessingPipeline.allowAllPolicy(), reframePlanner,
                failingWriter, new MutationPlanValidator(), MaskWriter.scalar(), true
            );

            PacketRef packetRef = packetRef(new byte[]{0x0A, 0x01, 0x01});
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    30L, ProtocolKind.OTLP_HTTP_PROTO, SignalKind.TRACES,
                    packetRef, "application/x-protobuf"
                ));
                TransportNack nack = assertInstanceOf(TransportNack.class, response);
                assertEquals(500, nack.statusCode());
                assertEquals(1, packetRef.refCount(),
                    "Source PacketRef must have refCount=1 after reframe failure");
            } finally {
                packetRef.release();
            }
            assertEquals(0, packetRef.refCount());
        }
    }

    @Test
    void shouldReleaseReframedRefOnSuccessfulReframe() throws Exception {
        try (HangingHttpServer upstream = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 upstream.uri("/v1/traces"),
                 upstream.uri("/v1/metrics"),
                 upstream.uri("/v1/logs"),
                 Map.of(),
                 8,
                 300
             );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            PacketRef[] capturedReframed = new PacketRef[1];
            MutationPlanner reframePlanner = (packet, decision) -> new MutationPlan.Builder(packet.descriptor().requestId())
                .mode(MutationPlan.PlanMode.REFRAME)
                .sourceLength(packet.length())
                .targetLength(packet.length())
                .addPassB(new MutationPlan.SliceCopyOp(0, packet.length(), 0))
                .build();
            ReframeWriter capturingWriter = (plan, src, alloc) -> {
                // Allocate a new packet and capture it for refcount verification
                var lease = alloc.allocate(src.length(),
                    new com.acme.finops.gateway.memory.AllocationTag("test", "test", 1));
                if (lease instanceof com.acme.finops.gateway.memory.LeaseResult.Granted granted) {
                    PacketRef reframed = granted.packetRef();
                    // Copy source into destination
                    MemorySegment srcSlice = src.segment().asSlice(src.offset(), src.length());
                    MemorySegment dstSlice = reframed.segment().asSlice(reframed.offset(), reframed.length());
                    dstSlice.copyFrom(srcSlice);
                    capturedReframed[0] = reframed;
                    reframed.retain(); // extra retain so we can check refcount after pipeline releases
                    return new ReframeResult.Success(reframed);
                }
                return new ReframeResult.Failed(507);
            };
            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter, allocator, OtlpProcessingPipeline.allowAllPolicy(), reframePlanner,
                capturingWriter, new MutationPlanValidator(), MaskWriter.scalar(), true
            );

            PacketRef packetRef = packetRef(new byte[]{0x0A, 0x01, 0x01});
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    40L, ProtocolKind.OTLP_HTTP_PROTO, SignalKind.TRACES,
                    packetRef, "application/x-protobuf"
                ));
                assertInstanceOf(TransportAck.class, response);
                assertEquals(1, packetRef.refCount(),
                    "Source PacketRef must have refCount=1 after reframe (caller still owns it)");
                // After pipeline: our test retain (1) + exporter async retain (1) = 2
                // Pipeline's releaseOutbound already decremented once (from 3 to 2)
                assertEquals(2, capturedReframed[0].refCount(),
                    "Reframed PacketRef should have refCount=2 (test retain + exporter async retain)");
            } finally {
                packetRef.release();
                if (capturedReframed[0] != null) {
                    capturedReframed[0].release(); // release our extra retain
                }
            }
        }
    }

    @Test
    void shouldNotLeakSourceRefWhenPlannerThrows() throws Exception {
        try (HangingHttpServer upstream = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 upstream.uri("/v1/traces"),
                 upstream.uri("/v1/metrics"),
                 upstream.uri("/v1/logs"),
                 Map.of(),
                 8,
                 300
             );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            MutationPlanner throwingPlanner = (packet, decision) -> {
                throw new RuntimeException("planner explosion");
            };
            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter, allocator, throwingPlanner, false
            );

            PacketRef packetRef = packetRef(new byte[]{0x0A, 0x01, 0x01});
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    50L, ProtocolKind.OTLP_HTTP_PROTO, SignalKind.TRACES,
                    packetRef, "application/x-protobuf"
                ));
                TransportNack nack = assertInstanceOf(TransportNack.class, response);
                assertEquals(500, nack.statusCode());
                assertEquals(1, packetRef.refCount(),
                    "Source PacketRef must have refCount=1 after planner exception");
            } finally {
                packetRef.release();
            }
            assertEquals(0, packetRef.refCount());
        }
    }

    @Test
    void shouldNotLeakOnNullMutationPlan() throws Exception {
        try (HangingHttpServer upstream = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 upstream.uri("/v1/traces"),
                 upstream.uri("/v1/metrics"),
                 upstream.uri("/v1/logs"),
                 Map.of(),
                 8,
                 300
             );
             SlabPacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            MutationPlanner nullPlanner = (packet, decision) -> null;
            OtlpProcessingPipeline pipeline = new OtlpProcessingPipeline(
                exporter, allocator, nullPlanner, false
            );

            PacketRef packetRef = packetRef(new byte[]{0x0A, 0x01, 0x01});
            try {
                var response = pipeline.onPacket(new InboundPacket(
                    60L, ProtocolKind.OTLP_HTTP_PROTO, SignalKind.TRACES,
                    packetRef, "application/x-protobuf"
                ));
                TransportNack nack = assertInstanceOf(TransportNack.class, response);
                assertEquals(500, nack.statusCode());
                assertEquals(1, packetRef.refCount(),
                    "Source PacketRef must have refCount=1 after null plan");
            } finally {
                packetRef.release();
            }
            assertEquals(0, packetRef.refCount());
        }
    }

    private static PacketRef packetRef(byte[] payload) {
        PacketDescriptor descriptor = new PacketDescriptor(
            1L,
            1L,
            SignalKind.TRACES,
            ProtocolKind.OTLP_HTTP_PROTO,
            0,
            payload.length,
            System.nanoTime()
        );
        return new PacketRefImpl(1L, descriptor, MemorySegment.ofArray(payload), 0, payload.length);
    }

    private static final class HangingHttpServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final ExecutorService acceptLoop;
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

        private HangingHttpServer() throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.acceptLoop = Executors.newSingleThreadExecutor();
            this.acceptLoop.submit(this::acceptForever);
        }

        private void acceptForever() {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    sockets.add(socket);
                } catch (IOException ignored) {
                    if (serverSocket.isClosed()) {
                        return;
                    }
                }
            }
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + serverSocket.getLocalPort() + path);
        }

        @Override
        public void close() throws Exception {
            for (Socket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
            serverSocket.close();
            acceptLoop.shutdownNow();
            acceptLoop.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
