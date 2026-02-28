package com.acme.finops.gateway.transport.http;

import com.acme.finops.gateway.memory.AllocationTag;
import com.acme.finops.gateway.memory.PacketAllocator;
import com.acme.finops.gateway.memory.PacketDescriptor;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.memory.PacketRefImpl;
import com.acme.finops.gateway.memory.SlabPacketAllocator;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import com.acme.finops.gateway.transport.api.TransportAck;
import com.acme.finops.gateway.transport.api.TransportNack;
import com.acme.finops.gateway.transport.netty.NettyPacketRefImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyOtlpHttpAdapterTest {

    @Test
    void shouldHandleCommonHttpIngressBranches() throws Exception {
        int port = freePort();
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

        try (PacketAllocator allocator = new SlabPacketAllocator(4 * 1024 * 1024)) {
            NettyOtlpHttpAdapter adapter = new NettyOtlpHttpAdapter(
                port,
                allocator,
                new AllocationTag("test", "http", 1)
            );

            adapter.start();
            try {
                // 405: wrong method
                HttpResponse<byte[]> get = client.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/v1/traces"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray()
                );
                assertEquals(405, get.statusCode());

                // 404: unknown path
                HttpResponse<byte[]> notFound = client.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/v1/unknown"))
                        .header("Content-Type", "application/x-protobuf")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1}))
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray()
                );
                assertEquals(404, notFound.statusCode());

                // 415: unsupported media type
                HttpResponse<byte[]> unsupported = client.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/v1/traces"))
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1}))
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray()
                );
                assertEquals(415, unsupported.statusCode());

                // Nack path
                adapter.setInboundHandler(packet -> new TransportNack(0, 429, true, 25));
                HttpResponse<byte[]> nack = client.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/v1/traces"))
                        .header("Content-Type", "application/x-protobuf")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{0x0A, 0x01, 0x01}))
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray()
                );
                assertEquals(429, nack.statusCode());

                // Ack path with response payload
                PacketRef responseRef = responseRef(new byte[]{7, 8, 9});
                adapter.setInboundHandler(packet -> new TransportAck(200, responseRef));
                HttpResponse<byte[]> ack = client.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/v1/traces"))
                        .header("Content-Type", "application/x-protobuf")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{0x0A, 0x01, 0x01}))
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray()
                );
                assertEquals(200, ack.statusCode());
                assertArrayEquals(new byte[]{7, 8, 9}, ack.body());
                assertEquals(0, responseRef.refCount(), "adapter must release ack.responsePayload");

                // Else branch (null response)
                adapter.setInboundHandler(packet -> null);
                HttpResponse<byte[]> nullResponse = client.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/v1/traces"))
                        .header("Content-Type", "application/x-protobuf")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{0x0A, 0x01, 0x01}))
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray()
                );
                assertEquals(200, nullResponse.statusCode());

                // Exception path
                adapter.setInboundHandler(packet -> { throw new IllegalStateException("boom"); });
                HttpResponse<byte[]> fail = client.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/v1/traces"))
                        .header("Content-Type", "application/x-protobuf")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{0x0A, 0x01, 0x01}))
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray()
                );
                assertEquals(500, fail.statusCode());
            } finally {
                adapter.stop();
            }
        }
    }

    @Test
    void shouldConvertDirectAndHeapPayloadsToPacketRef() throws Exception {
        int port = freePort();
        try (PacketAllocator allocator = new SlabPacketAllocator(1024 * 1024)) {
            NettyOtlpHttpAdapter adapter = new NettyOtlpHttpAdapter(
                port,
                allocator,
                new AllocationTag("test", "http-toPacketRef", 1)
            );

            Method toPacketRef = NettyOtlpHttpAdapter.class.getDeclaredMethod("toPacketRef", ByteBuf.class, SignalKind.class);
            toPacketRef.setAccessible(true);

            ByteBuf direct = Unpooled.directBuffer(4);
            ByteBuf heap = Unpooled.buffer(4);
            try {
                direct.writeBytes(new byte[]{1, 2, 3, 4});
                heap.writeBytes(new byte[]{5, 6, 7, 8});

                PacketRef directRef = (PacketRef) toPacketRef.invoke(adapter, direct, SignalKind.TRACES);
                assertInstanceOf(NettyPacketRefImpl.class, directRef);
                directRef.release();

                PacketRef heapRef = (PacketRef) toPacketRef.invoke(adapter, heap, SignalKind.TRACES);
                assertNotNull(heapRef);
                assertTrue(heapRef.length() > 0);
                heapRef.release();
            } finally {
                direct.release();
                heap.release();
            }
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static PacketRef responseRef(byte[] payload) {
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
}

