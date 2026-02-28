package com.acme.finops.gateway.transport.proxy;

import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.transport.api.SignalKind;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.SimpleChannelPool;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

import com.acme.finops.gateway.util.GatewayDefaults;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class AsyncOtlpHttpExporter implements AutoCloseable {
    private static final int RESPONSE_LIMIT = GatewayDefaults.EXPORTER_RESPONSE_LIMIT;
    private static final int DEFAULT_RESPONSE_TIMEOUT_MILLIS = GatewayDefaults.DEFAULT_RESPONSE_TIMEOUT_MS;

    private final URI tracesUri;
    private final URI metricsUri;
    private final URI logsUri;
    private final Map<String, String> staticHeaders;

    private final EventLoopGroup ioGroup;
    private final Bootstrap bootstrap;
    private final SslContext sslContext;
    private final Semaphore inFlight;
    private final int responseTimeoutMillis;
    private final int poolSize;
    private final ConcurrentHashMap<String, SimpleChannelPool> pools = new ConcurrentHashMap<>();

    public AsyncOtlpHttpExporter(URI tracesUri,
                                 URI metricsUri,
                                 URI logsUri,
                                 Map<String, String> staticHeaders) {
        this(tracesUri, metricsUri, logsUri, staticHeaders,
            GatewayDefaults.DEFAULT_MAX_INFLIGHT, DEFAULT_RESPONSE_TIMEOUT_MILLIS,
            GatewayDefaults.DEFAULT_EXPORTER_IO_THREADS, GatewayDefaults.DEFAULT_EXPORTER_POOL_SIZE);
    }

    public AsyncOtlpHttpExporter(URI tracesUri,
                                 URI metricsUri,
                                 URI logsUri,
                                 Map<String, String> staticHeaders,
                                 int maxInFlight) {
        this(tracesUri, metricsUri, logsUri, staticHeaders,
            maxInFlight, DEFAULT_RESPONSE_TIMEOUT_MILLIS,
            GatewayDefaults.DEFAULT_EXPORTER_IO_THREADS, GatewayDefaults.DEFAULT_EXPORTER_POOL_SIZE);
    }

    public AsyncOtlpHttpExporter(URI tracesUri,
                                 URI metricsUri,
                                 URI logsUri,
                                 Map<String, String> staticHeaders,
                                 int maxInFlight,
                                 int responseTimeoutMillis) {
        this(tracesUri, metricsUri, logsUri, staticHeaders,
            maxInFlight, responseTimeoutMillis,
            GatewayDefaults.DEFAULT_EXPORTER_IO_THREADS, GatewayDefaults.DEFAULT_EXPORTER_POOL_SIZE);
    }

    public AsyncOtlpHttpExporter(URI tracesUri,
                                 URI metricsUri,
                                 URI logsUri,
                                 Map<String, String> staticHeaders,
                                 int maxInFlight,
                                 int responseTimeoutMillis,
                                 int ioThreads,
                                 int poolSize) {
        this.tracesUri = Objects.requireNonNull(tracesUri, "tracesUri");
        this.metricsUri = Objects.requireNonNull(metricsUri, "metricsUri");
        this.logsUri = Objects.requireNonNull(logsUri, "logsUri");
        this.staticHeaders = Map.copyOf(staticHeaders == null ? Map.of() : staticHeaders);
        this.inFlight = new Semaphore(Math.max(1, maxInFlight));
        this.responseTimeoutMillis = Math.max(1, responseTimeoutMillis);
        this.poolSize = Math.max(1, poolSize);

        int threads = ioThreads > 0 ? ioThreads : Math.max(2, Runtime.getRuntime().availableProcessors());
        this.ioGroup = new NioEventLoopGroup(threads);
        this.bootstrap = new Bootstrap()
            .group(ioGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, GatewayDefaults.DEFAULT_CONNECT_TIMEOUT_MS);

        boolean hasHttps = isHttps(this.tracesUri) || isHttps(this.metricsUri) || isHttps(this.logsUri);
        try {
            this.sslContext = hasHttps ? SslContextBuilder.forClient().build() : null;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build TLS context", e);
        }
    }

    public CompletableFuture<Integer> exportAsync(SignalKind signalKind, byte[] payload, String contentType) {
        return exportAsync(signalKind, Unpooled.wrappedBuffer(payload), payload.length, contentType, () -> { });
    }

    public CompletableFuture<Integer> exportAsync(SignalKind signalKind, PacketRef packetRef, String contentType) {
        Objects.requireNonNull(packetRef, "packetRef");
        packetRef.retain();
        try {
            ByteBuf payload = Unpooled.wrappedBuffer(
                packetRef.segment().asSlice(packetRef.offset(), packetRef.length()).asByteBuffer()
            );
            return exportAsync(signalKind, payload, packetRef.length(), contentType, packetRef::release);
        } catch (Throwable t) {
            packetRef.release();
            throw t;
        }
    }

    private CompletableFuture<Integer> exportAsync(SignalKind signalKind,
                                                   ByteBuf payload,
                                                   int payloadLength,
                                                   String contentType,
                                                   Runnable completionCleanup) {
        URI target = resolveTarget(signalKind);

        CompletableFuture<Integer> result = new CompletableFuture<>();
        if (!inFlight.tryAcquire()) {
            payload.release();
            completionCleanup.run();
            result.completeExceptionally(new IllegalStateException("too many in-flight exports"));
            return result;
        }
        AtomicReference<ScheduledFuture<?>> timeoutFutureRef = new AtomicReference<>();
        String host = Objects.requireNonNull(target.getHost(), "target host required");
        int port = resolvePort(target);
        boolean https = isHttps(target);

        SimpleChannelPool pool = poolFor(host, port, https);

        result.whenComplete((ignored, error) -> {
            ScheduledFuture<?> timeoutFuture = timeoutFutureRef.getAndSet(null);
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
            inFlight.release();
            completionCleanup.run();
        });

        pool.acquire().addListener((FutureListener<Channel>) acquireFuture -> {
            if (!acquireFuture.isSuccess()) {
                payload.release();
                result.completeExceptionally(acquireFuture.cause());
                return;
            }

            Channel ch = acquireFuture.getNow();

            // Add per-request response handler
            ch.pipeline().addLast("export-response", new ExportResponseHandler(result, pool, ch));

            ScheduledFuture<?> timeoutFuture = ch.eventLoop().schedule(() -> {
                if (result.completeExceptionally(new TimeoutException("upstream response timeout"))) {
                    ch.close();
                }
            }, responseTimeoutMillis, TimeUnit.MILLISECONDS);
            timeoutFutureRef.set(timeoutFuture);
            if (result.isDone() && timeoutFutureRef.compareAndSet(timeoutFuture, null)) {
                timeoutFuture.cancel(false);
            }

            String pathAndQuery = pathAndQuery(target);
            FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                pathAndQuery,
                payload
            );
            req.headers().set(HttpHeaderNames.HOST, hostHeader(target));
            req.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            req.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            req.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, payloadLength);
            req.headers().set(HttpHeaderNames.USER_AGENT, "otel-gateway-proxy/1");
            for (Map.Entry<String, String> e : staticHeaders.entrySet()) {
                req.headers().set(e.getKey(), e.getValue());
            }
            ch.writeAndFlush(req).addListener((ChannelFutureListener) writeFuture -> {
                if (!writeFuture.isSuccess()) {
                    ReferenceCountUtil.safeRelease(req);
                    result.completeExceptionally(writeFuture.cause());
                    writeFuture.channel().close();
                }
            });
        });

        return result;
    }

    private SimpleChannelPool poolFor(String host, int port, boolean https) {
        String key = host + ":" + port;
        return pools.computeIfAbsent(key, k -> {
            Bootstrap perUri = bootstrap.clone().remoteAddress(host, port);
            return new SimpleChannelPool(perUri, new ExporterChannelPoolHandler(host, port, https));
        });
    }

    private URI resolveTarget(SignalKind signalKind) {
        return switch (signalKind) {
            case TRACES -> tracesUri;
            case METRICS -> metricsUri;
            case LOGS -> logsUri;
        };
    }

    @Override
    public void close() {
        for (SimpleChannelPool pool : pools.values()) {
            pool.close();
        }
        pools.clear();
        ioGroup.shutdownGracefully().syncUninterruptibly();
    }

    private final class ExporterChannelPoolHandler implements ChannelPoolHandler {
        private final String host;
        private final int port;
        private final boolean https;

        ExporterChannelPoolHandler(String host, int port, boolean https) {
            this.host = host;
            this.port = port;
            this.https = https;
        }

        @Override
        public void channelCreated(Channel ch) {
            ChannelPipeline p = ch.pipeline();
            if (https) {
                p.addLast(sslContext.newHandler(ch.alloc(), host, port));
            }
            p.addLast(new HttpClientCodec());
            p.addLast(new HttpObjectAggregator(RESPONSE_LIMIT));
        }

        @Override
        public void channelAcquired(Channel ch) {
            // no-op
        }

        @Override
        public void channelReleased(Channel ch) {
            if (ch.pipeline().get("export-response") != null) {
                ch.pipeline().remove("export-response");
            }
        }
    }

    private static final class ExportResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final CompletableFuture<Integer> result;
        private final SimpleChannelPool pool;
        private final Channel channel;

        private ExportResponseHandler(CompletableFuture<Integer> result, SimpleChannelPool pool, Channel channel) {
            this.result = result;
            this.pool = pool;
            this.channel = channel;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) {
            result.complete(msg.status().code());
            pool.release(channel);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            result.completeExceptionally(cause);
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!result.isDone()) {
                result.completeExceptionally(new IllegalStateException("upstream closed before response"));
            }
            ctx.fireChannelInactive();
        }
    }

    private static boolean isHttps(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme());
    }

    private static int resolvePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return isHttps(uri) ? GatewayDefaults.HTTPS_DEFAULT_PORT : GatewayDefaults.HTTP_DEFAULT_PORT;
    }

    private static String pathAndQuery(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            return path + "?" + uri.getRawQuery();
        }
        return path;
    }

    private static String hostHeader(URI uri) {
        int port = resolvePort(uri);
        if ((isHttps(uri) && port == GatewayDefaults.HTTPS_DEFAULT_PORT) || (!isHttps(uri) && port == GatewayDefaults.HTTP_DEFAULT_PORT)) {
            return uri.getHost();
        }
        return uri.getHost() + ":" + port;
    }
}
