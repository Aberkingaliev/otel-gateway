package com.acme.finops.gateway.transport.proxy;

import com.acme.finops.gateway.transport.api.ProtocolKind;
import com.acme.finops.gateway.transport.api.TransportAdapter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyGatewayProxyMainTest {

    @Test
    void shouldRollbackWhenSecondAdapterStartFails() {
        FakeAdapter first = new FakeAdapter(false);
        FakeAdapter second = new FakeAdapter(true);
        AtomicBoolean rollbackCalled = new AtomicBoolean(false);

        assertThrows(Exception.class, () ->
            NettyGatewayProxyMain.startAdaptersWithRollback(first, second, () -> rollbackCalled.set(true))
        );

        assertTrue(first.started);
        assertTrue(second.started);
        assertTrue(rollbackCalled.get());
    }

    @Test
    void shouldNotRollbackWhenBothAdaptersStartSuccessfully() throws Exception {
        FakeAdapter first = new FakeAdapter(false);
        FakeAdapter second = new FakeAdapter(false);
        AtomicBoolean rollbackCalled = new AtomicBoolean(false);

        NettyGatewayProxyMain.startAdaptersWithRollback(first, second, () -> rollbackCalled.set(true));

        assertTrue(first.started);
        assertTrue(second.started);
        assertFalse(rollbackCalled.get());
    }

    private static final class FakeAdapter implements TransportAdapter {
        private final boolean failOnStart;
        private boolean started;

        private FakeAdapter(boolean failOnStart) {
            this.failOnStart = failOnStart;
        }

        @Override
        public ProtocolKind protocol() {
            return ProtocolKind.OTLP_HTTP_PROTO;
        }

        @Override
        public void start() throws Exception {
            started = true;
            if (failOnStart) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public void stop() {
        }

        @Override
        public void setInboundHandler(InboundHandler handler) {
        }
    }
}

