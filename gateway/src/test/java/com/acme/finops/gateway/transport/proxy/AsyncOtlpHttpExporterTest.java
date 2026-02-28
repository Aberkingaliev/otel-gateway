package com.acme.finops.gateway.transport.proxy;

import com.acme.finops.gateway.memory.PacketDescriptor;
import com.acme.finops.gateway.memory.PacketRef;
import com.acme.finops.gateway.memory.PacketRefImpl;
import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.SignalKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncOtlpHttpExporterTest {

    @Test
    void shouldTimeoutAndReleaseInFlightSlot() throws Exception {
        try (HangingHttpServer server = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 server.uri("/v1/traces"),
                 server.uri("/v1/metrics"),
                 server.uri("/v1/logs"),
                 Map.of(),
                 1,
                 150
             )) {
            var first = exporter.exportAsync(SignalKind.TRACES, new byte[]{0x01}, "application/x-protobuf");
            Throwable firstFailure = waitFailure(first);
            assertInstanceOf(TimeoutException.class, firstFailure);

            var second = exporter.exportAsync(SignalKind.TRACES, new byte[]{0x02}, "application/x-protobuf");
            Throwable secondFailure = waitFailure(second);
            assertInstanceOf(TimeoutException.class, secondFailure);
        }
    }

    @Test
    void shouldRemainOperationalUnderConcurrentContention() throws Exception {
        try (HangingHttpServer server = new HangingHttpServer();
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 server.uri("/v1/traces"),
                 server.uri("/v1/metrics"),
                 server.uri("/v1/logs"),
                 Map.of(),
                 2,
                 120
             )) {
            List<CompletableFuture<Throwable>> outcomes = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                CompletableFuture<Integer> export = exporter.exportAsync(
                    SignalKind.TRACES,
                    new byte[]{(byte) i},
                    "application/x-protobuf"
                );
                outcomes.add(export.handle((ignored, error) -> unwrap(error)));
            }

            CompletableFuture.allOf(outcomes.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);

            long timeoutFailures = outcomes.stream()
                .map(CompletableFuture::join)
                .filter(TimeoutException.class::isInstance)
                .count();
            long inFlightFailures = outcomes.stream()
                .map(CompletableFuture::join)
                .filter(IllegalStateException.class::isInstance)
                .filter(t -> t.getMessage() != null && t.getMessage().contains("too many in-flight exports"))
                .count();

            assertTrue(timeoutFailures > 0, "Expected at least one timeout under contention");
            assertTrue(inFlightFailures > 0, "Expected at least one in-flight saturation failure");

            Throwable probeFailure = waitFailure(
                exporter.exportAsync(SignalKind.TRACES, new byte[]{0x7F}, "application/x-protobuf")
            );
            assertInstanceOf(TimeoutException.class, probeFailure);
        }
    }

    @Test
    void shouldReturnStatusCodeForSuccessfulResponse() throws Exception {
        try (SimpleHttpServer server = new SimpleHttpServer(200);
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 server.uri("/v1/traces"),
                 server.uri("/v1/metrics"),
                 server.uri("/v1/logs"),
                 Map.of(),
                 8,
                 1_000
             )) {
            int status = exporter.exportAsync(SignalKind.TRACES, new byte[]{0x01, 0x02}, "application/x-protobuf")
                .get(3, TimeUnit.SECONDS);
            assertEquals(200, status);
        }
    }

    @Test
    void shouldReleasePacketRefAfterExportCompletion() throws Exception {
        try (SimpleHttpServer server = new SimpleHttpServer(200);
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 server.uri("/v1/traces"),
                 server.uri("/v1/metrics"),
                 server.uri("/v1/logs"),
                 Map.of(),
                 8,
                 1_000
             )) {
            PacketRef ref = packetRef(new byte[]{1, 2, 3});
            var future = exporter.exportAsync(SignalKind.TRACES, ref, "application/x-protobuf");
            ref.release(); // caller ownership

            int status = future.get(3, TimeUnit.SECONDS);
            assertEquals(200, status);
            // completionCleanup runs in whenComplete which may execute asynchronously
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ref.refCount() > 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(0, ref.refCount(), "completion cleanup must release retained PacketRef");
        }
    }

    @Test
    void shouldReusePooledConnectionsWithKeepAlive() throws Exception {
        try (KeepAliveHttpServer server = new KeepAliveHttpServer(200);
             AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
                 server.uri("/v1/traces"),
                 server.uri("/v1/metrics"),
                 server.uri("/v1/logs"),
                 Map.of(),
                 8,
                 2_000
             )) {
            for (int i = 0; i < 5; i++) {
                int status = exporter.exportAsync(SignalKind.TRACES, new byte[]{(byte) i}, "application/x-protobuf")
                    .get(3, TimeUnit.SECONDS);
                assertEquals(200, status, "request " + i + " should succeed");
            }
            assertEquals(1, server.connectionCount(), "all requests should reuse a single TCP connection");
        }
    }

    @Test
    void shouldFailFastOnConnectFailure() throws Exception {
        int port = freePort();
        URI target = URI.create("http://127.0.0.1:" + port + "/v1/traces");
        try (AsyncOtlpHttpExporter exporter = new AsyncOtlpHttpExporter(
            target,
            target,
            target,
            Map.of(),
            8,
            300
        )) {
            Throwable failure = waitFailure(exporter.exportAsync(SignalKind.TRACES, new byte[]{0x01}, "application/x-protobuf"));
            assertTrue(failure instanceof ConnectException || failure.getClass().getSimpleName().contains("Connect"),
                "Expected connect failure, got: " + failure);
        }
    }

    private static Throwable waitFailure(Future<?> future) throws InterruptedException {
        ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        return error.getCause();
    }

    private static Throwable unwrap(Throwable error) {
        if (error == null) {
            return null;
        }
        if (error instanceof ExecutionException && error.getCause() != null) {
            return error.getCause();
        }
        if (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
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

    private static final class KeepAliveHttpServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final ExecutorService acceptLoop;
        private final int statusCode;
        private final AtomicInteger connections = new AtomicInteger();

        private KeepAliveHttpServer(int statusCode) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.acceptLoop = Executors.newCachedThreadPool();
            this.statusCode = statusCode;
            this.acceptLoop.submit(this::acceptForever);
        }

        private void acceptForever() {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    connections.incrementAndGet();
                    acceptLoop.submit(() -> serveConnection(socket));
                } catch (IOException ignored) {
                    if (serverSocket.isClosed()) {
                        return;
                    }
                }
            }
        }

        private void serveConnection(Socket socket) {
            try (socket) {
                socket.setSoTimeout(5_000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                while (!socket.isClosed() && !serverSocket.isClosed()) {
                    if (!readUntilHeadersEnd(in)) {
                        return;
                    }
                    String resp = "HTTP/1.1 " + statusCode + " OK\r\n"
                        + "Content-Length: 0\r\n"
                        + "Connection: keep-alive\r\n"
                        + "\r\n";
                    out.write(resp.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    out.flush();
                }
            } catch (IOException ignored) {
            }
        }

        private static boolean readUntilHeadersEnd(InputStream in) throws IOException {
            int prev3 = -1, prev2 = -1, prev1 = -1;
            while (true) {
                int b = in.read();
                if (b == -1) {
                    return false;
                }
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') {
                    return true;
                }
                prev3 = prev2;
                prev2 = prev1;
                prev1 = b;
            }
        }

        int connectionCount() {
            return connections.get();
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + serverSocket.getLocalPort() + path);
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            acceptLoop.shutdownNow();
            acceptLoop.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static final class SimpleHttpServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final ExecutorService acceptLoop;
        private final int statusCode;

        private SimpleHttpServer(int statusCode) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.acceptLoop = Executors.newSingleThreadExecutor();
            this.statusCode = statusCode;
            this.acceptLoop.submit(this::serveForever);
        }

        private void serveForever() {
            while (!serverSocket.isClosed()) {
                try (Socket socket = serverSocket.accept()) {
                    socket.setSoTimeout(2_000);
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    readUntilHeadersEnd(in);
                    String resp = "HTTP/1.1 " + statusCode + " OK\r\n"
                        + "Content-Length: 0\r\n"
                        + "Connection: close\r\n"
                        + "\r\n";
                    out.write(resp.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    out.flush();
                } catch (IOException ignored) {
                    if (serverSocket.isClosed()) {
                        return;
                    }
                }
            }
        }

        private static void readUntilHeadersEnd(InputStream in) throws IOException {
            int prev3 = -1, prev2 = -1, prev1 = -1;
            while (true) {
                int b = in.read();
                if (b == -1) {
                    return;
                }
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') {
                    return;
                }
                prev3 = prev2;
                prev2 = prev1;
                prev1 = b;
            }
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + serverSocket.getLocalPort() + path);
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            acceptLoop.shutdownNow();
            acceptLoop.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
