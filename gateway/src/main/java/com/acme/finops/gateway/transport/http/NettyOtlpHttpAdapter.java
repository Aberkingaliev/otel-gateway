package com.acme.finops.gateway.transport.http;

import com.acme.finops.gateway.memory.AllocationDeniedException;
import com.acme.finops.gateway.memory.AllocationTag;
import com.acme.finops.gateway.memory.PacketAllocator;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.transport.api.InboundPacket;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.transport.api.TransportAck;
import com.acme.finops.gateway.transport.api.TransportAdapter;
import com.acme.finops.gateway.transport.api.TransportNack;
import com.acme.finops.gateway.transport.netty.NettyPacketRefImpl;
import com.acme.finops.gateway.telemetry.HotPathMetrics;
import com.acme.finops.gateway.telemetry.NoopHotPathMetrics;
import com.acme.finops.gateway.util.GatewayDefaults;
import com.acme.finops.gateway.util.GatewayStatusCodes;
import com.acme.finops.gateway.util.OtlpContentTypes;
import com.acme.finops.gateway.util.OtlpEndpoints;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NettyOtlpHttpAdapter implements OtlpHttpProtobufAdapter {
    private static final Logger LOG = Logger.getLogger(NettyOtlpHttpAdapter.class.getName());
    private static final int DEFAULT_PORT = OtlpEndpoints.DEFAULT_HTTP_PORT;
    private static final int MAX_CONTENT_LENGTH = GatewayDefaults.MAX_CONTENT_LENGTH;

    private final int port;
    private final PacketAllocator packetAllocator;
    private final AllocationTag allocationTag;
    private final HttpStatusMapper httpStatusMapper;
    private final HotPathMetrics metrics;
    private final AtomicLong requestIds = new AtomicLong(1);

    private volatile InboundHandler inboundHandler = packet -> new TransportAck(GatewayStatusCodes.OK, null);

    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile Channel serverChannel;

    public NettyOtlpHttpAdapter(PacketAllocator packetAllocator,
                                AllocationTag allocationTag) {
        this(DEFAULT_PORT, packetAllocator, allocationTag, NettyOtlpHttpAdapter::defaultHttpStatus, NoopHotPathMetrics.INSTANCE);
    }

    public NettyOtlpHttpAdapter(int port,
                                PacketAllocator packetAllocator,
                                AllocationTag allocationTag) {
        this(port, packetAllocator, allocationTag, NettyOtlpHttpAdapter::defaultHttpStatus, NoopHotPathMetrics.INSTANCE);
    }

    public NettyOtlpHttpAdapter(int port,
                                PacketAllocator packetAllocator,
                                AllocationTag allocationTag,
                                HotPathMetrics metrics) {
        this(port, packetAllocator, allocationTag, NettyOtlpHttpAdapter::defaultHttpStatus, metrics);
    }

    public NettyOtlpHttpAdapter(int port,
                                PacketAllocator packetAllocator,
                                AllocationTag allocationTag,
                                HttpStatusMapper httpStatusMapper) {
        this(port, packetAllocator, allocationTag, httpStatusMapper, NoopHotPathMetrics.INSTANCE);
    }

    public NettyOtlpHttpAdapter(int port,
                                PacketAllocator packetAllocator,
                                AllocationTag allocationTag,
                                HttpStatusMapper httpStatusMapper,
                                HotPathMetrics metrics) {
        this.port = port;
        this.packetAllocator = Objects.requireNonNull(packetAllocator, "packetAllocator");
        this.allocationTag = Objects.requireNonNull(allocationTag, "allocationTag");
        this.httpStatusMapper = Objects.requireNonNull(httpStatusMapper, "httpStatusMapper");
        this.metrics = metrics == null ? NoopHotPathMetrics.INSTANCE : metrics;
    }

    @Override
    public ProtocolKind protocol() {
        return ProtocolKind.OTLP_HTTP_PROTO;
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
                .option(ChannelOption.SO_BACKLOG, GatewayDefaults.DEFAULT_SO_BACKLOG)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                        ch.pipeline().addLast(new OtlpHttpHandler());
                    }
                });

            serverChannel = bootstrap.bind(port).sync().channel();
            LOG.info(() -> "Netty OTLP HTTP adapter started on port " + port);
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

        LOG.info(() -> "Netty OTLP HTTP adapter stopped");

        if (first != null) {
            throw first;
        }
    }

    @Override
    public void setInboundHandler(InboundHandler handler) {
        this.inboundHandler = Objects.requireNonNull(handler, "handler");
    }

    private final class OtlpHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            if (!req.decoderResult().isSuccess()) {
                metrics.incParseErrors(1L, GatewayStatusCodes.BAD_REQUEST);
                writeResponse(ctx, req, HttpResponseStatus.BAD_REQUEST, "bad request", "text/plain");
                return;
            }
            if (req.method() != HttpMethod.POST) {
                metrics.incParseErrors(1L, GatewayStatusCodes.METHOD_NOT_ALLOWED);
                writeResponse(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, "method not allowed", "text/plain");
                return;
            }

            SignalKind signalKind = resolveSignalKind(req.uri());
            if (signalKind == null) {
                metrics.incParseErrors(1L, GatewayStatusCodes.NOT_FOUND);
                writeResponse(ctx, req, HttpResponseStatus.NOT_FOUND, "unknown otlp path", "text/plain");
                return;
            }

            String contentType = req.headers().get(HttpHeaderNames.CONTENT_TYPE);
            if (!OtlpContentTypes.isSupportedRequestContentType(contentType)) {
                metrics.incParseErrors(1L, GatewayStatusCodes.UNSUPPORTED_MEDIA_TYPE);
                writeResponse(ctx, req, HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE,
                    "supported content-types: application/x-protobuf, application/json", "text/plain");
                return;
            }

            ByteBuf payload = req.content().retainedSlice();
            PacketRef packetRef = null;
            try {
                packetRef = toPacketRef(payload, signalKind);
                long requestId = requestIds.getAndIncrement();

                var result = inboundHandler.onPacket(new InboundPacket(
                    requestId,
                    ProtocolKind.OTLP_HTTP_PROTO,
                    signalKind,
                    packetRef,
                    contentType
                ));

                if (result instanceof TransportNack nack) {
                    int statusCode = nack.statusCode() > 0 ? nack.statusCode() : httpStatusMapper.toHttpStatus(nack.errorCode());
                    writeResponse(ctx, req, HttpResponseStatus.valueOf(statusCode),
                        "nack errorCode=" + nack.errorCode(), "text/plain");
                } else if (result instanceof TransportAck ack) {
                    ByteBuf body = null;
                    try {
                        if (ack.responsePayload() != null) {
                            PacketRef responseRef = ack.responsePayload();
                            try {
                                byte[] out = responseRef.segment()
                                    .asSlice(responseRef.offset(), responseRef.length())
                                    .toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                                body = Unpooled.wrappedBuffer(out);
                            } finally {
                                responseRef.release();
                            }
                        }
                        int code = ack.statusCode() > 0 ? ack.statusCode() : GatewayStatusCodes.OK;
                        writeResponse(ctx, req, HttpResponseStatus.valueOf(code),
                            body == null ? Unpooled.EMPTY_BUFFER : body,
                            OtlpContentTypes.normalizeResponseContentType(contentType));
                    } finally {
                        if (body != null && body.refCnt() > 0) {
                            body.release();
                        }
                    }
                } else {
                    writeResponse(ctx, req, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER, OtlpContentTypes.normalizeResponseContentType(contentType));
                }
            } catch (AllocationDeniedException ade) {
                metrics.incParseErrors(1L, ade.reasonCode());
                writeResponse(ctx, req, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "slab allocation denied", "text/plain");
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "HTTP ingest failure", t);
                metrics.incParseErrors(1L, GatewayStatusCodes.INTERNAL_ERROR);
                writeResponse(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, "internal error", "text/plain");
            } finally {
                try {
                    payload.release();
                } catch (Throwable ignored) {
                }
                if (packetRef != null) {
                    try {
                        packetRef.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.log(Level.SEVERE, "HTTP pipeline failure", cause);
            ctx.close();
        }
    }

    private PacketRef toPacketRef(ByteBuf buf, SignalKind signalKind) {
        if (buf.isDirect() && buf.hasMemoryAddress()) {
            return new NettyPacketRefImpl(buf, signalKind, ProtocolKind.OTLP_HTTP_PROTO);
        }
        return copyToDirect(buf, signalKind);
    }

    private PacketRef copyToDirect(ByteBuf buf, SignalKind signalKind) {
        int readable = buf.readableBytes();
        if (readable == 0) {
            throw new IllegalArgumentException("empty payload");
        }
        ByteBuf direct = io.netty.buffer.PooledByteBufAllocator.DEFAULT.directBuffer(readable, readable);
        try {
            direct.writeBytes(buf, buf.readerIndex(), readable);
            // NettyPacketRefImpl retains the ByteBuf; finally releases our allocation ref
            return new NettyPacketRefImpl(direct, signalKind, ProtocolKind.OTLP_HTTP_PROTO);
        } finally {
            direct.release();
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

    private static SignalKind resolveSignalKind(String uri) {
        String path = uri;
        int q = uri.indexOf('?');
        if (q >= 0) {
            path = uri.substring(0, q);
        }
        return OtlpEndpoints.signalKindFromHttpPath(path);
    }

    private static int defaultHttpStatus(int gatewayErrorCode) {
        if (gatewayErrorCode == 0) {
            return GatewayStatusCodes.OK;
        }
        if (gatewayErrorCode == GatewayStatusCodes.TOO_MANY_REQUESTS) {
            return GatewayStatusCodes.TOO_MANY_REQUESTS;
        }
        if (gatewayErrorCode >= GatewayStatusCodes.BAD_REQUEST && gatewayErrorCode < GatewayStatusCodes.INTERNAL_ERROR) {
            return gatewayErrorCode;
        }
        return GatewayStatusCodes.INTERNAL_ERROR;
    }

    private static void writeResponse(ChannelHandlerContext ctx,
                                      FullHttpRequest req,
                                      HttpResponseStatus status,
                                      String message,
                                      String contentType) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = ctx.alloc().buffer(bytes.length);
        buf.writeBytes(bytes);
        try {
            writeResponse(ctx, req, status, buf, contentType);
        } finally {
            buf.release();
        }
    }

    private static void writeResponse(ChannelHandlerContext ctx,
                                      FullHttpRequest req,
                                      HttpResponseStatus status,
                                      ByteBuf body,
                                      String contentType) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, body.retainedDuplicate());
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

        boolean keepAlive = HttpUtil.isKeepAlive(req);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

}
