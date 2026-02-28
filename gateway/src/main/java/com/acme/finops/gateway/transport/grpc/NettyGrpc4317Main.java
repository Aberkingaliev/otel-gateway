package com.acme.finops.gateway.transport.grpc;

import com.acme.finops.gateway.memory.AllocationTag;
import com.acme.finops.gateway.memory.PacketAllocator;
import com.acme.finops.gateway.memory.SlabPacketAllocator;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.transport.api.TransportNack;
import com.acme.finops.gateway.transport.http.NettyOtlpHttpAdapter;
import com.acme.finops.gateway.util.OtlpEndpoints;
import com.acme.finops.gateway.wire.cursor.FastWireCursor;
import com.acme.finops.gateway.wire.cursor.WireException;

import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NettyGrpc4317Main {
    private static final Logger LOG = Logger.getLogger(NettyGrpc4317Main.class.getName());

    private NettyGrpc4317Main() {}

    public static void main(String[] args) throws Exception {
        int grpcPort = args.length > 0 ? Integer.parseInt(args[0]) : OtlpEndpoints.DEFAULT_GRPC_PORT;
        int httpPort = args.length > 1 ? Integer.parseInt(args[1]) : OtlpEndpoints.DEFAULT_HTTP_PORT;
        PacketAllocator allocator = new SlabPacketAllocator(64L * 1024 * 1024);
        NettyOtlpGrpcAdapter grpcAdapter = new NettyOtlpGrpcAdapter(
            grpcPort,
            allocator,
            SignalKind.TRACES,
            new AllocationTag("netty-grpc", OtlpEndpoints.ALLOCATION_SCOPE_DEFAULT, 1)
        );
        NettyOtlpHttpAdapter httpAdapter = new NettyOtlpHttpAdapter(
            httpPort,
            allocator,
            new AllocationTag("netty-http", OtlpEndpoints.ALLOCATION_SCOPE_DEFAULT, 1)
        );

        var inboundHandler = (com.acme.finops.gateway.transport.api.TransportAdapter.InboundHandler) packet -> {
            try {
                FastWireCursor cursor = new FastWireCursor();
                cursor.reset(packet.packetRef().segment(), packet.packetRef().offset(), packet.packetRef().length());

                int fields = 0;
                StringBuilder sb = new StringBuilder();
                while (cursor.nextField()) {
                    fields++;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append("field=").append(cursor.fieldNumber())
                        .append("/wire=").append(cursor.wireType())
                        .append("/len=").append(cursor.valueLength());
                }

                long reqId = packet.requestId();
                LOG.info("ingest requestId=" + reqId + " fields=" + fields + " [" + sb + "]");
            } catch (WireException e) {
                LOG.log(Level.WARNING, "Wire parse failed requestId=" + packet.requestId(), e);
                return new TransportNack(400, 1001, false, 0);
            }
            return new TransportNack(200, 0, false, 0);
        };

        grpcAdapter.setInboundHandler(inboundHandler);
        httpAdapter.setInboundHandler(inboundHandler);

        grpcAdapter.start();
        httpAdapter.start();
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                httpAdapter.stop();
            } catch (Exception ignored) {
            }
            try {
                grpcAdapter.stop();
            } catch (Exception ignored) {
            }
            try {
                allocator.close();
            } catch (Exception ignored) {
            }
            shutdownLatch.countDown();
        }));

        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
