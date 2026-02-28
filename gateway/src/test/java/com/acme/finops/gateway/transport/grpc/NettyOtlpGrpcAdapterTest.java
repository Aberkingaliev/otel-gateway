package com.acme.finops.gateway.transport.grpc;

import com.acme.finops.gateway.memory.AllocationTag;
import com.acme.finops.gateway.memory.PacketAllocator;
import com.acme.finops.gateway.memory.SlabPacketAllocator;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.transport.api.TransportAck;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyOtlpGrpcAdapterTest {

    @Test
    void shouldReturnInvalidArgumentForEmptyBody() throws Exception {
        int port = freePort();
        try (PacketAllocator allocator = new SlabPacketAllocator(8L * 1024 * 1024)) {
            NettyOtlpGrpcAdapter adapter = new NettyOtlpGrpcAdapter(
                port,
                allocator,
                SignalKind.TRACES,
                new AllocationTag("test", "grpc-empty", 1)
            );
            AtomicBoolean inboundCalled = new AtomicBoolean(false);
            adapter.setInboundHandler(packet -> {
                inboundCalled.set(true);
                return new TransportAck(200, null);
            });

            adapter.start();
            try {
                String grpcStatus = sendGrpcRequest("127.0.0.1", port, "application/grpc+proto", null);
                assertEquals("3", grpcStatus);
                assertFalse(inboundCalled.get());
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void shouldReturnInvalidArgumentForEmptyGrpcFrame() throws Exception {
        int port = freePort();
        try (PacketAllocator allocator = new SlabPacketAllocator(8L * 1024 * 1024)) {
            NettyOtlpGrpcAdapter adapter = new NettyOtlpGrpcAdapter(
                port,
                allocator,
                SignalKind.TRACES,
                new AllocationTag("test", "grpc-empty-frame", 1)
            );
            AtomicBoolean inboundCalled = new AtomicBoolean(false);
            adapter.setInboundHandler(packet -> {
                inboundCalled.set(true);
                return new TransportAck(200, null);
            });

            adapter.start();
            try {
                byte[] emptyGrpcFrame = new byte[]{0, 0, 0, 0, 0};
                String grpcStatus = sendGrpcRequest("127.0.0.1", port, "application/grpc+proto", emptyGrpcFrame);
                assertEquals("3", grpcStatus);
                assertFalse(inboundCalled.get());
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void shouldRejectInvalidContentType() throws Exception {
        int port = freePort();
        try (PacketAllocator allocator = new SlabPacketAllocator(8L * 1024 * 1024)) {
            NettyOtlpGrpcAdapter adapter = new NettyOtlpGrpcAdapter(
                port,
                allocator,
                SignalKind.TRACES,
                new AllocationTag("test", "grpc-bad-content-type", 1)
            );
            AtomicBoolean inboundCalled = new AtomicBoolean(false);
            adapter.setInboundHandler(packet -> {
                inboundCalled.set(true);
                return new TransportAck(200, null);
            });

            adapter.start();
            try {
                String grpcStatus = sendGrpcRequest("127.0.0.1", port, "text/plain", null);
                assertEquals("3", grpcStatus);
                assertFalse(inboundCalled.get());
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void shouldRejectInvalidGrpcFlagsAndCompressionAndTruncation() throws Exception {
        int port = freePort();
        try (PacketAllocator allocator = new SlabPacketAllocator(8L * 1024 * 1024)) {
            NettyOtlpGrpcAdapter adapter = new NettyOtlpGrpcAdapter(
                port,
                allocator,
                SignalKind.TRACES,
                new AllocationTag("test", "grpc-flags", 1)
            );
            adapter.setInboundHandler(packet -> new TransportAck(200, null));

            adapter.start();
            try {
                byte[] invalidFlags = new byte[]{0x02, 0, 0, 0, 1, 0x01};
                assertEquals("3", sendGrpcRequest("127.0.0.1", port, "application/grpc+proto", invalidFlags));

                byte[] compressed = new byte[]{0x01, 0, 0, 0, 1, 0x01};
                assertEquals("12", sendGrpcRequest("127.0.0.1", port, "application/grpc+proto", compressed));

                byte[] truncatedPayload = new byte[]{0x00, 0, 0, 0, 10, 0x01, 0x02};
                assertEquals("13", sendGrpcRequest("127.0.0.1", port, "application/grpc+proto", truncatedPayload));
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void shouldMapNackToGrpcStatusAndReleaseAckPayload() throws Exception {
        int port = freePort();
        try (PacketAllocator allocator = new SlabPacketAllocator(8L * 1024 * 1024)) {
            NettyOtlpGrpcAdapter adapter = new NettyOtlpGrpcAdapter(
                port,
                allocator,
                SignalKind.TRACES,
                new AllocationTag("test", "grpc-nack-map", 1)
            );

            adapter.setInboundHandler(packet -> new com.acme.finops.gateway.transport.api.TransportNack(0, 429, true, 0));
            adapter.start();
            try {
                byte[] ok = new byte[]{0x00, 0, 0, 0, 1, 0x01};
                assertEquals("14", sendGrpcRequest("127.0.0.1", port, "application/grpc+proto", ok));
            } finally {
                adapter.stop();
            }

            com.acme.finops.gateway.memory.PacketRef responseRef = responseRef(new byte[]{7, 8, 9});
            adapter.setInboundHandler(packet -> new TransportAck(200, responseRef));
            adapter.start();
            try {
                byte[] ok = new byte[]{0x00, 0, 0, 0, 1, 0x01};
                assertEquals("0", sendGrpcRequest("127.0.0.1", port, "application/grpc+proto", ok));
                assertEquals(0, responseRef.refCount(), "adapter must release ack.responsePayload");
            } finally {
                adapter.stop();
            }
        }
    }

    private static String sendGrpcRequest(String host, int port, String contentType, byte[] grpcBody) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                        ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
                    }
                });

            Channel parent = bootstrap.connect(host, port).sync().channel();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> status = new AtomicReference<>();

            Http2StreamChannel stream = new Http2StreamChannelBootstrap(parent)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        try {
                            if (msg instanceof Http2HeadersFrame headersFrame && headersFrame.isEndStream()) {
                                CharSequence grpcStatus = headersFrame.headers().get("grpc-status");
                                if (grpcStatus != null) {
                                    status.set(grpcStatus.toString());
                                }
                                done.countDown();
                            }
                        } finally {
                            ReferenceCountUtil.release(msg);
                        }
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        done.countDown();
                        ctx.close();
                    }
                })
                .open()
                .sync()
                .getNow();

            Http2Headers headers = new DefaultHttp2Headers()
                .method("POST")
                .scheme("http")
                .authority(host + ":" + port)
                .path("/opentelemetry.proto.collector.trace.v1.TraceService/Export")
                .set("content-type", contentType)
                .set("te", "trailers");
            boolean endStreamOnHeaders = grpcBody == null || grpcBody.length == 0;
            stream.writeAndFlush(new DefaultHttp2HeadersFrame(headers, endStreamOnHeaders)).sync();
            if (!endStreamOnHeaders) {
                stream.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(grpcBody), true)).sync();
            }

            assertTrue(done.await(5, TimeUnit.SECONDS), "Timed out waiting for gRPC trailers");

            stream.close().sync();
            parent.close().sync();
            return status.get();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static com.acme.finops.gateway.memory.PacketRef responseRef(byte[] payload) {
        com.acme.finops.gateway.memory.PacketDescriptor descriptor = new com.acme.finops.gateway.memory.PacketDescriptor(
            1L,
            1L,
            SignalKind.TRACES,
            com.acme.finops.gateway.transport.api.ProtocolKind.OTLP_GRPC,
            0,
            payload.length,
            System.nanoTime()
        );
        return new com.acme.finops.gateway.memory.PacketRefImpl(1L, descriptor, java.lang.foreign.MemorySegment.ofArray(payload), 0, payload.length);
    }
}
