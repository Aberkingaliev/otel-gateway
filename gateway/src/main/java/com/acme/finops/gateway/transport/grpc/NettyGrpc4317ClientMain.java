package com.acme.finops.gateway.transport.grpc;

import com.acme.finops.gateway.util.GrpcProtocolConstants;
import com.acme.finops.gateway.util.OtlpEndpoints;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class NettyGrpc4317ClientMain {
    private static final Logger LOG = Logger.getLogger(NettyGrpc4317ClientMain.class.getName());

    private NettyGrpc4317ClientMain() {}

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : OtlpEndpoints.DEFAULT_GRPC_PORT;

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
            Http2StreamChannelBootstrap streamBootstrap = new Http2StreamChannelBootstrap(parent);
            Http2StreamChannel stream = streamBootstrap
                .handler(new ResponseHandler(done))
                .open()
                .sync()
                .getNow();

            Http2Headers headers = new DefaultHttp2Headers()
                .method("POST")
                .scheme("http")
                .authority(host + ":" + port)
                .path(OtlpEndpoints.GRPC_TRACE_EXPORT_METHOD)
                .set(GrpcProtocolConstants.HEADER_CONTENT_TYPE, GrpcProtocolConstants.VALUE_GRPC_CONTENT_TYPE)
                .set(GrpcProtocolConstants.HEADER_TE, GrpcProtocolConstants.VALUE_TE_TRAILERS);
            stream.write(new DefaultHttp2HeadersFrame(headers, false));

            byte[] payload = samplePayload();
            ByteBuf data = stream.alloc().buffer(5 + payload.length);
            data.writeByte(0); // compressed flag
            data.writeInt(payload.length); // gRPC message length
            data.writeBytes(payload);
            stream.writeAndFlush(new DefaultHttp2DataFrame(data, true)).sync();

            if (!done.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for gRPC trailers");
            }

            stream.close().sync();
            parent.close().sync();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static byte[] samplePayload() {
        // field 1 varint=150, field 2 len="Hello", field 3 fixed64=0x0102030405060708
        return new byte[]{
            0x08, (byte) 0x96, 0x01,
            0x12, 0x05, 'H', 'e', 'l', 'l', 'o',
            0x19, 0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01
        };
    }

    private static final class ResponseHandler extends ChannelInboundHandlerAdapter {
        private final CountDownLatch done;

        private ResponseHandler(CountDownLatch done) {
            this.done = done;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof Http2HeadersFrame headersFrame) {
                    Http2Headers h = headersFrame.headers();
                    if (headersFrame.isEndStream()) {
                        LOG.info(() -> "trailers grpc-status=" + h.get(GrpcProtocolConstants.HEADER_GRPC_STATUS));
                        done.countDown();
                    }
                } else if (msg instanceof Http2DataFrame dataFrame) {
                    LOG.info(() -> "data bytes=" + dataFrame.content().readableBytes() + " endStream=" + dataFrame.isEndStream());
                    if (dataFrame.isEndStream()) {
                        done.countDown();
                    }
                }
            } finally {
                if (msg instanceof Http2DataFrame dataFrame) {
                    dataFrame.release();
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.severe("client stream error: " + cause.getMessage());
            done.countDown();
            ctx.close();
        }
    }
}
