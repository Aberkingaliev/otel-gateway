package com.acme.finops.gateway.transport.grpc;

import com.acme.finops.gateway.memory.AllocationDeniedException;
import com.acme.finops.gateway.memory.AllocationTag;
import com.acme.finops.gateway.memory.LeaseResult;
import com.acme.finops.gateway.memory.PacketAllocator;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.transport.api.InboundPacket;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.transport.api.TransportAck;
import com.acme.finops.gateway.transport.api.TransportAdapter;
import com.acme.finops.gateway.transport.api.TransportNack;
import com.acme.finops.gateway.transport.netty.NettyPacketRefImpl;
import com.acme.finops.gateway.util.GrpcProtocolConstants;
import com.acme.finops.gateway.util.OtlpContentTypes;
import com.acme.finops.gateway.util.OtlpEndpoints;
import com.acme.finops.gateway.telemetry.HotPathMetrics;
import com.acme.finops.gateway.telemetry.NoopHotPathMetrics;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.util.ReferenceCountUtil;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NettyOtlpGrpcAdapter implements OtlpGrpcAdapter {
    private static final Logger LOG = Logger.getLogger(NettyOtlpGrpcAdapter.class.getName());
    private static final int DEFAULT_PORT = OtlpEndpoints.DEFAULT_GRPC_PORT;
    private static final int MAX_REQUEST_BODY_BYTES = 16 * 1024 * 1024;

    private final int port;
    private final PacketAllocator packetAllocator;
    private final SignalKind defaultSignalKind;
    private final AllocationTag allocationTag;
    private final GrpcStatusMapper grpcStatusMapper;
    private final HotPathMetrics metrics;
    private final AtomicLong requestIds = new AtomicLong(1);

    private volatile InboundHandler inboundHandler = _ -> new TransportAck(200, null);

    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile Channel serverChannel;

    public NettyOtlpGrpcAdapter(PacketAllocator packetAllocator,
                                SignalKind signalKind,
                                AllocationTag allocationTag) {
        this(DEFAULT_PORT, packetAllocator, signalKind, allocationTag, NettyOtlpGrpcAdapter::defaultGrpcStatus, NoopHotPathMetrics.INSTANCE);
    }

    public NettyOtlpGrpcAdapter(int port,
                                PacketAllocator packetAllocator,
                                SignalKind signalKind,
                                AllocationTag allocationTag) {
        this(port, packetAllocator, signalKind, allocationTag, NettyOtlpGrpcAdapter::defaultGrpcStatus, NoopHotPathMetrics.INSTANCE);
    }

    public NettyOtlpGrpcAdapter(int port,
                                PacketAllocator packetAllocator,
                                SignalKind signalKind,
                                AllocationTag allocationTag,
                                HotPathMetrics metrics) {
        this(port, packetAllocator, signalKind, allocationTag, NettyOtlpGrpcAdapter::defaultGrpcStatus, metrics);
    }

    public NettyOtlpGrpcAdapter(int port,
                                PacketAllocator packetAllocator,
                                SignalKind signalKind,
                                AllocationTag allocationTag,
                                GrpcStatusMapper grpcStatusMapper) {
        this(port, packetAllocator, signalKind, allocationTag, grpcStatusMapper, NoopHotPathMetrics.INSTANCE);
    }

    public NettyOtlpGrpcAdapter(int port,
                                PacketAllocator packetAllocator,
                                SignalKind signalKind,
                                AllocationTag allocationTag,
                                GrpcStatusMapper grpcStatusMapper,
                                HotPathMetrics metrics) {
        this.port = port;
        this.packetAllocator = Objects.requireNonNull(packetAllocator, "packetAllocator");
        this.defaultSignalKind = Objects.requireNonNull(signalKind, "signalKind");
        this.allocationTag = Objects.requireNonNull(allocationTag, "allocationTag");
        this.grpcStatusMapper = Objects.requireNonNull(grpcStatusMapper, "grpcStatusMapper");
        this.metrics = metrics == null ? NoopHotPathMetrics.INSTANCE : metrics;
    }

    @Override
    public ProtocolKind protocol() {
        return ProtocolKind.OTLP_GRPC;
    }

    @Override
    public int listenPort() {
        return port;
    }

    @Override
    public synchronized void start() throws Exception {
        if (serverChannel != null) {
            return;
        }

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());
                        ch.pipeline().addLast(new Http2MultiplexHandler(new GrpcStreamHandler()));
                    }
                });

            serverChannel = bootstrap.bind(port).sync().channel();
            LOG.info(() -> "Netty OTLP gRPC adapter started on port " + port);
        } catch (Exception e) {
            stop();
            throw e;
        }
    }

    @Override
    public synchronized void stop() throws Exception {
        Exception first = null;

        Channel ch = serverChannel;
        serverChannel = null;
        if (ch != null) {
            try {
                ch.close().syncUninterruptibly();
            } catch (Exception e) {
                first = e;
            }
        }

        EventLoopGroup workers = workerGroup;
        workerGroup = null;
        if (workers != null) {
            workers.shutdownGracefully().syncUninterruptibly();
        }

        EventLoopGroup boss = bossGroup;
        bossGroup = null;
        if (boss != null) {
            boss.shutdownGracefully().syncUninterruptibly();
        }

        LOG.info(() -> "Netty OTLP gRPC adapter stopped");

        if (first != null) {
            throw first;
        }
    }

    @Override
    public void setInboundHandler(InboundHandler handler) {
        this.inboundHandler = Objects.requireNonNull(handler, "handler");
    }

    private final class GrpcStreamHandler extends ChannelInboundHandlerAdapter {
        private Http2Headers requestHeaders;
        private ByteBuf requestBody;
        private boolean responseSent;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (responseSent) {
                ReferenceCountUtil.release(msg);
                return;
            }

            if (msg instanceof Http2HeadersFrame headersFrame) {
                try {
                    if (requestHeaders == null) {
                        requestHeaders = new DefaultHttp2Headers().setAll(headersFrame.headers());
                    }
                    if (headersFrame.isEndStream()) {
                        handleRequest(ctx);
                    }
                } finally {
                    ReferenceCountUtil.release(headersFrame);
                }
                return;
            }

            if (msg instanceof Http2DataFrame dataFrame) {
                try {
                    ByteBuf data = dataFrame.content();
                    int current = requestBody == null ? 0 : requestBody.readableBytes();
                    int incoming = data.readableBytes();
                    if ((long) current + incoming > MAX_REQUEST_BODY_BYTES) {
                        if (requestBody != null) {
                            requestBody.release();
                            requestBody = null;
                        }
                        writeGrpcResponse(ctx, 8, "request too large");
                        return;
                    }
                    if (requestBody == null) {
                        requestBody = ctx.alloc().buffer(Math.max(256, incoming));
                    }
                    requestBody.writeBytes(data, data.readerIndex(), incoming);
                    if (dataFrame.isEndStream()) {
                        handleRequest(ctx);
                    }
                } finally {
                    ReferenceCountUtil.release(dataFrame);
                }
                return;
            }

            ReferenceCountUtil.release(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (requestBody != null) {
                requestBody.release();
                requestBody = null;
            }
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.log(Level.SEVERE, "gRPC stream failure", cause);
            if (!responseSent) {
                writeGrpcResponse(ctx, 13, "internal");
            }
            ctx.close();
        }

        private void handleRequest(ChannelHandlerContext ctx) {
            if (responseSent) {
                return;
            }

            int grpcStatus;
            String grpcMessage;
            try {
                if (requestHeaders == null) {
                    grpcStatus = 3;
                    grpcMessage = "missing headers";
                } else if (!isGrpcRequest(requestHeaders)) {
                    grpcStatus = 3;
                    grpcMessage = "invalid content-type";
                } else if (requestBody == null || !requestBody.isReadable()) {
                    grpcStatus = 3;
                    grpcMessage = "empty request body";
                } else {
                    GrpcOutcome outcome = processGrpcMessages(requestBody, resolveSignalKind(requestHeaders));
                    grpcStatus = outcome.grpcStatus();
                    grpcMessage = outcome.grpcMessage();
                }
            } catch (Throwable t) {
                LOG.log(Level.SEVERE, "gRPC request processing failed", t);
                metrics.incParseErrors(1L, 13);
                grpcStatus = 13;
                grpcMessage = "internal";
            } finally {
                if (requestBody != null) {
                    requestBody.release();
                    requestBody = null;
                }
                requestHeaders = null;
            }

            writeGrpcResponse(ctx, grpcStatus, grpcMessage);
        }

        private GrpcOutcome processGrpcMessages(ByteBuf grpcBody, SignalKind signalKind) {
            while (grpcBody.isReadable()) {
                if (grpcBody.readableBytes() < 5) {
                    metrics.incParseErrors(1L, 13);
                    return new GrpcOutcome(13, "truncated grpc frame");
                }

                int flags = grpcBody.readUnsignedByte();
                int messageLength = grpcBody.readInt();
                if ((flags & 0xFE) != 0) {
                    metrics.incParseErrors(1L, 3);
                    return new GrpcOutcome(3, "invalid grpc flags");
                }
                if ((flags & 0x01) != 0) {
                    metrics.incParseErrors(1L, 12);
                    return new GrpcOutcome(12, "compressed payload unsupported");
                }
                if (messageLength < 0 || grpcBody.readableBytes() < messageLength) {
                    metrics.incParseErrors(1L, 13);
                    return new GrpcOutcome(13, "truncated protobuf payload");
                }
                if (messageLength == 0) {
                    metrics.incParseErrors(1L, 3);
                    return new GrpcOutcome(3, "empty protobuf payload");
                }

                ByteBuf payload = grpcBody.readRetainedSlice(messageLength);
                try {
                    GrpcOutcome outcome = dispatchToIngress(payload, signalKind);
                    if (outcome.grpcStatus() != 0) {
                        return outcome;
                    }
                } finally {
                    try {
                        payload.release();
                    } catch (Throwable ignored) {
                    }
                }
            }

            return new GrpcOutcome(0, "");
        }

        private GrpcOutcome dispatchToIngress(ByteBuf payload, SignalKind signalKind) {
            PacketRef packetRef = null;
            try {
                packetRef = toPacketRef(payload, signalKind);

                long requestId = requestIds.getAndIncrement();
                var response = inboundHandler.onPacket(new InboundPacket(
                    requestId,
                    ProtocolKind.OTLP_GRPC,
                    signalKind,
                    packetRef,
                    OtlpContentTypes.PROTOBUF
                ));

                if (response instanceof TransportNack nack) {
                    return new GrpcOutcome(grpcStatusMapper.toGrpcStatus(nack.errorCode()), "nack-" + nack.errorCode());
                }
                if (response instanceof TransportAck ack && ack.responsePayload() != null) {
                    ack.responsePayload().release();
                }
                return new GrpcOutcome(0, "");
            } catch (AllocationDeniedException ade) {
                metrics.incParseErrors(1L, ade.reasonCode());
                return new GrpcOutcome(14, "slab_allocation_denied");
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "ingress dispatch failed", t);
                metrics.incParseErrors(1L, 13);
                return new GrpcOutcome(13, "ingress_error");
            } finally {
                if (packetRef != null) {
                    try {
                        packetRef.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private void writeGrpcResponse(ChannelHandlerContext ctx, int grpcStatus, String grpcMessage) {
            Http2Headers headers = new DefaultHttp2Headers()
                .status(GrpcProtocolConstants.VALUE_HTTP2_STATUS_OK)
                .set(GrpcProtocolConstants.HEADER_CONTENT_TYPE, GrpcProtocolConstants.VALUE_GRPC_CONTENT_TYPE);
            ctx.write(new DefaultHttp2HeadersFrame(headers, false));

            if (grpcStatus == 0) {
                ByteBuf emptyProto = ctx.alloc().buffer(5);
                emptyProto.writeByte(0);
                emptyProto.writeInt(0);
                ctx.write(new DefaultHttp2DataFrame(emptyProto, false));
            }

            Http2Headers trailers = new DefaultHttp2Headers()
                .set(GrpcProtocolConstants.HEADER_GRPC_STATUS, Integer.toString(grpcStatus));
            if (grpcMessage != null && !grpcMessage.isEmpty()) {
                trailers.set(GrpcProtocolConstants.HEADER_GRPC_MESSAGE, grpcMessage);
            }
            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(trailers, true));
            responseSent = true;
        }

        private boolean isGrpcRequest(Http2Headers headers) {
            CharSequence contentType = headers.get(GrpcProtocolConstants.HEADER_CONTENT_TYPE);
            if (contentType == null) {
                return false;
            }
            String v = contentType.toString();
            return v.regionMatches(true, 0, GrpcProtocolConstants.VALUE_GRPC_CONTENT_PREFIX, 0,
                GrpcProtocolConstants.VALUE_GRPC_CONTENT_PREFIX.length());
        }

        private SignalKind resolveSignalKind(Http2Headers headers) {
            CharSequence path = headers.path();
            if (path == null) {
                return defaultSignalKind;
            }
            return OtlpEndpoints.signalKindFromGrpcPath(path.toString(), defaultSignalKind);
        }
    }

    private PacketRef toPacketRef(ByteBuf buf, SignalKind signalKind) {
        if (buf.isDirect() && buf.hasMemoryAddress()) {
            return new NettyPacketRefImpl(buf, signalKind, ProtocolKind.OTLP_GRPC);
        }
        return copyHeapToAllocator(buf, signalKind);
    }

    private PacketRef copyHeapToAllocator(ByteBuf buf, SignalKind signalKind) {
        int readable = buf.readableBytes();
        LeaseResult lease = packetAllocator.allocate(readable, allocationTagFor(signalKind));

        PacketRef ref = switch (lease) {
            case LeaseResult.Granted granted -> granted.packetRef();
            case LeaseResult.Denied denied -> throw new AllocationDeniedException(denied.reasonCode());
        };

        try {
            MemorySegment dst = ref.segment().asSlice(ref.offset(), readable);
            ByteBuffer nio = dst.asByteBuffer();
            nio.clear();
            nio.limit(readable);
            buf.getBytes(buf.readerIndex(), nio);
            return ref;
        } catch (Throwable t) {
            try {
                ref.release();
            } catch (Throwable ignored) {
            }
            throw t;
        }
    }

    private AllocationTag allocationTagFor(SignalKind signalKind) {
        return new AllocationTag(
            allocationTag.pipeline(),
            allocationTag.tenantId(),
            signalTypeCode(signalKind)
        );
    }

    private static int signalTypeCode(SignalKind signalKind) {
        if (signalKind == null) {
            return 0;
        }
        return switch (signalKind) {
            case TRACES -> 1;
            case METRICS -> 2;
            case LOGS -> 3;
        };
    }

    private static int defaultGrpcStatus(int gatewayErrorCode) {
        if (gatewayErrorCode == 0) {
            return 0;
        }
        if (gatewayErrorCode == 429) {
            return 14;
        }
        if (gatewayErrorCode >= 400 && gatewayErrorCode < 500) {
            return 3;
        }
        if (gatewayErrorCode >= 500 && gatewayErrorCode < 600) {
            return 13;
        }
        return 13;
    }

    private record GrpcOutcome(int grpcStatus, String grpcMessage) {}
}
